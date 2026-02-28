package com.swaran.airbridge.core.network.upload

import android.content.Context
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.OffsetMismatchException
import com.swaran.airbridge.domain.model.PermissionRevokedException
import com.swaran.airbridge.domain.model.UploadCancelledException
import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadPausedException
import com.swaran.airbridge.domain.model.UploadRequest
import com.swaran.airbridge.domain.model.UploadResult
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.model.UploadStatus
import com.swaran.airbridge.domain.model.UploadTimeoutException
import com.swaran.airbridge.domain.repository.StorageRepository
import com.swaran.airbridge.domain.usecase.UploadStateManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.server.request.ApplicationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.lang.SecurityException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central upload orchestrator with file-level locking and defensive error handling.
 *
 * Responsibilities:
 * - File-level mutex (prevents concurrent writes to same file)
 * - Offset validation (prevents duplicate bytes)
 * - Error classification (retryable vs permanent)
 * - State machine coordination
 * - Progress throttling
 */
@Singleton
class UploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageRepository: StorageRepository,
    private val stateManager: UploadStateManager,
    private val logger: AirLogger
) {
    companion object {
        private const val TAG = "UploadScheduler"
        private const val UPLOAD_TIMEOUT_SECONDS = 60L // 60 seconds without progress = stall
        private const val MAX_CONCURRENT_UPLOADS = 3
        private const val MIN_SPEED_BPS = 1000f // 1KB/s threshold for stall detection
        private const val PROGRESS_UPDATE_INTERVAL_MS = 250L
        private const val ACTION_DEBOUNCE_MS = 250L // Aggressive sync with UI cooldown and browser poll
    }

    // Debounce rapid toggling per uploadId
    private val lastActionTime = ConcurrentHashMap<String, Long>()

    /**
     * File-level locks, keyed by "$path/$fileName".
     * This prevents concurrent writes to the same logical file from different uploadIds.
     */
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Semaphore to limit concurrent uploads and protect device resources.
     */
    private val uploadSemaphore = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT_UPLOADS)

    val isGlobalPaused: StateFlow<Boolean> = stateManager.isGlobalPaused
    val activeUploads: StateFlow<Map<String, UploadStatus>> = stateManager.activeUploads

    /**
     * Handle a new upload request with full validation and locking.
     */
    suspend fun handleUpload(
        request: UploadRequest,
        requestSource: ApplicationRequest,
        receiveStream: suspend () -> java.io.InputStream
    ): UploadResult {
        val uploadId = request.uploadId
        val lockKey = "${request.path}/${request.fileName}"

        logger.d(TAG, "log", "[$uploadId] Starting upload: $lockKey, offset=${request.offset}")

        // 1. Check for duplicate/conflicting uploads
        if (stateManager.shouldRejectRequest(uploadId, request.fileUri)) {
            logger.w(TAG, "log", "[$uploadId] Rejected: upload already active")
            return UploadResult.Failure.UnknownError(
                uploadId = uploadId,
                bytesReceived = 0,
                cause = IllegalStateException("Upload already active")
            )
        }

        // 2. Initialize state machine
        val metadata = UploadMetadata(
            uploadId = uploadId,
            fileUri = request.fileUri,
            displayName = request.fileName,
            path = request.path,
            totalBytes = request.totalBytes
        )
        stateManager.initialize(metadata)

        // 3. Acquire file-level lock (prevents concurrent writes)
        val fileMutex = fileLocks.getOrPut(lockKey) { Mutex() }
        
        // Use tryLock to avoid blocking - if another upload is finishing, return busy status
        if (!fileMutex.tryLock()) {
            logger.d(TAG, "log", "[$uploadId] File locked by previous upload, returning BUSY")
            uploadSemaphore.release() // Release the semaphore we acquired
            return UploadResult.Busy(uploadId)
        }

        // 4. Limit concurrent uploads with semaphore
        uploadSemaphore.acquire()
        return try {
            performUpload(request, receiveStream)
        } finally {
            fileMutex.unlock()
            cleanupLock(lockKey)
            uploadSemaphore.release()
        }
    }

    private suspend fun performUpload(
        request: UploadRequest,
        receiveStream: suspend () -> java.io.InputStream
    ): UploadResult {
        val uploadId = request.uploadId
        var bytesReceived = request.offset
        var lastProgressTime = System.nanoTime()

        try {
            // 4. Validate offset against actual disk state
            val diskFile = storageRepository.findFileByName(request.path, request.fileName).getOrNull()
            val diskSize = diskFile?.size ?: 0L

            // Check if file was deleted externally
            if (diskFile != null && request.offset > 0 && diskSize == 0L) {
                logger.e(TAG, "log", "[$uploadId] File was deleted externally during resume")
                stateManager.transition(uploadId, UploadState.ERROR_PERMANENT, "File deleted externally")
                return UploadResult.Failure.FileDeleted(uploadId, bytesReceived)
            }

            if (request.offset != diskSize) {
                // Distinguish between disk smaller vs larger than expected
                val errorDetail = when {
                    diskSize < request.offset -> "Disk file smaller than expected (partial data lost)"
                    diskSize > request.offset -> "Disk file larger than expected (possible corruption or concurrent write)"
                    else -> "Offset mismatch"
                }
                logger.e(TAG, "log", "[$uploadId] Offset mismatch: expected=${request.offset}, disk=$diskSize - $errorDetail")
                stateManager.transition(uploadId, UploadState.ERROR_PERMANENT, errorDetail)
                return UploadResult.Failure.OffsetMismatch(
                    uploadId = uploadId,
                    bytesReceived = bytesReceived,
                    expectedOffset = request.offset,
                    actualDiskSize = diskSize
                )
            }

            // 5. Transition to UPLOADING
            if (!stateManager.transition(uploadId, UploadState.UPLOADING)) {
                val status = stateManager.getStatus(uploadId)
                logger.w(TAG, "log", "[$uploadId] Failed to transition to UPLOADING, state=${status?.state}")
                return when (status?.state) {
                    UploadState.CANCELLED -> UploadResult.Cancelled(uploadId)
                    UploadState.PAUSED, UploadState.PAUSING -> UploadResult.Paused(uploadId, bytesReceived)
                    else -> UploadResult.Failure.UnknownError(
                        uploadId = uploadId,
                        bytesReceived = bytesReceived,
                        cause = IllegalStateException("Cannot transition to UPLOADING from ${status?.state}")
                    )
                }
            }

            // 6. Execute upload with progress tracking and timeout detection
            val stream = receiveStream()
            logger.d(TAG, "performUpload", "[$uploadId] Starting storageRepository.uploadFile()")

            val result = storageRepository.uploadFile(
                path = request.path,
                fileName = request.fileName,
                uploadId = uploadId,
                inputStream = stream,
                totalBytes = request.totalBytes,
                append = request.offset > 0,
                onProgress = { newBytes ->
                    bytesReceived = request.offset + newBytes
                    lastProgressTime = System.nanoTime()

                    // Throttled progress logging (every ~50MB or on completion)
                    val totalReceived = request.offset + newBytes
                    if (totalReceived % (50 * 1024 * 1024) < 16384 || totalReceived >= request.totalBytes) {
                        logger.v(TAG, "performUpload", "[$uploadId] Progress: $totalReceived / ${request.totalBytes} bytes (${(totalReceived * 100 / request.totalBytes)}%)")
                    }

                    // Check for cancellation/pause
                    checkForInterruption(uploadId)

                    // Check for timeout (stall detection)
                    checkForTimeout(uploadId, lastProgressTime)

                    // Update progress (throttled internally)
                    stateManager.updateProgress(uploadId, bytesReceived)
                }
            )
            logger.d(TAG, "performUpload", "[$uploadId] storageRepository.uploadFile() completed: success=${result.isSuccess}")

            // 7. Handle result
            return if (result.isSuccess) {
                handleSuccess(uploadId, bytesReceived, result.getOrNull()?.id ?: "")
            } else {
                handleFailure(uploadId, bytesReceived, result.exceptionOrNull())
            }

        } catch (e: UploadPausedException) {
            logger.d(TAG, "log", "[$uploadId] Upload paused at $bytesReceived bytes")
            stateManager.transition(uploadId, UploadState.PAUSED)
            return UploadResult.Paused(uploadId, bytesReceived)

        } catch (e: UploadCancelledException) {
            logger.d(TAG, "log", "[$uploadId] Upload cancelled at $bytesReceived bytes")
            stateManager.transition(uploadId, UploadState.CANCELLED)
            cleanupPartialFile(request)
            return UploadResult.Cancelled(uploadId)

        } catch (e: OffsetMismatchException) {
            logger.e(TAG, "log", "[$uploadId] Offset mismatch: ${e.message}")
            stateManager.transition(uploadId, UploadState.ERROR_PERMANENT, e.message)
            return UploadResult.Failure.OffsetMismatch(
                uploadId = uploadId,
                bytesReceived = bytesReceived,
                expectedOffset = e.expectedOffset,
                actualDiskSize = e.actualOffset
            )

        } catch (e: PermissionRevokedException) {
            logger.e(TAG, "log", "[$uploadId] Permission revoked: ${e.message}")
            stateManager.transition(uploadId, UploadState.ERROR_PERMANENT, "Storage permission revoked")
            return UploadResult.Failure.PermissionRevoked(uploadId, bytesReceived)

        } catch (e: SecurityException) {
            logger.e(TAG, "log", "[$uploadId] SecurityException: ${e.message}")
            stateManager.transition(uploadId, UploadState.ERROR_PERMANENT, "SAF permission revoked")
            return UploadResult.Failure.PermissionRevoked(uploadId, bytesReceived)

        } catch (e: IOException) {
            val message = e.message?.lowercase() ?: ""
            return when {
                message.contains("nospace") || message.contains("no space") -> {
                    logger.e(TAG, "log", "[$uploadId] Storage full: ${e.message}")
                    stateManager.transition(uploadId, UploadState.ERROR_RETRYABLE, "Storage full")
                    UploadResult.Failure.StorageFull(uploadId, bytesReceived)
                }
                message.contains("enospc") -> {
                    logger.e(TAG, "log", "[$uploadId] Storage full: ${e.message}")
                    stateManager.transition(uploadId, UploadState.ERROR_RETRYABLE, "Storage full")
                    UploadResult.Failure.StorageFull(uploadId, bytesReceived)
                }
                else -> {
                    logger.e(TAG, "log", "[$uploadId] Network/IO error: ${e.message}")
                    stateManager.transition(uploadId, UploadState.ERROR_RETRYABLE, e.message)
                    UploadResult.Failure.NetworkError(uploadId, bytesReceived, e)
                }
            }

        } catch (e: Exception) {
            logger.e(TAG, "log", "[$uploadId] Unexpected error: ${e.message}", e)
            stateManager.transition(uploadId, UploadState.ERROR_RETRYABLE, e.message)
            return UploadResult.Failure.UnknownError(uploadId, bytesReceived, e)
        }
    }

    private fun checkForInterruption(uploadId: String) {
        val status = stateManager.getStatus(uploadId)

        when (status?.state) {
            UploadState.PAUSING, UploadState.PAUSED -> {
                throw UploadPausedException()
            }
            UploadState.CANCELLED -> {
                throw UploadCancelledException()
            }
            else -> { /* Continue */ }
        }

        if (isGlobalPaused.value) {
            throw UploadPausedException()
        }
    }

    private fun checkForTimeout(uploadId: String, lastProgressNanos: Long) {
        val now = System.nanoTime()
        val elapsedSeconds = (now - lastProgressNanos) / 1_000_000_000L
        if (elapsedSeconds > UPLOAD_TIMEOUT_SECONDS) {
            logger.w(TAG, "log", "[$uploadId] Upload stalled: no progress for ${elapsedSeconds}s")
            stateManager.transition(uploadId, UploadState.ERROR_RETRYABLE, "Upload stalled")
            throw UploadTimeoutException()
        }
    }

    private fun handleSuccess(uploadId: String, bytesReceived: Long, fileId: String): UploadResult.Success {
        stateManager.transition(uploadId, UploadState.COMPLETED)
        logger.d(TAG, "log", "[$uploadId] Upload completed: $bytesReceived bytes")
        return UploadResult.Success(uploadId, bytesReceived, fileId)
    }

    private fun handleFailure(uploadId: String, bytesReceived: Long, cause: Throwable?): UploadResult.Failure {
        logger.e(TAG, "handleFailure", cause ?: Exception("Unknown"), "[$uploadId] Upload failed: ${cause?.javaClass?.simpleName}: ${cause?.message}")
        return when (cause) {
            is SecurityException -> {
                stateManager.transition(uploadId, UploadState.ERROR_PERMANENT, "Permission revoked")
                UploadResult.Failure.PermissionRevoked(uploadId, bytesReceived)
            }
            else -> {
                stateManager.transition(uploadId, UploadState.ERROR_RETRYABLE, cause?.message)
                UploadResult.Failure.NetworkError(uploadId, bytesReceived, cause ?: Exception("Unknown"))
            }
        }
    }

    private suspend fun cleanupPartialFile(request: UploadRequest) {
        try {
            storageRepository.findFileByName(request.path, request.fileName).getOrNull()?.let { file ->
                storageRepository.deleteFile(file.id)
                logger.d(TAG, "log", "[${request.uploadId}] Deleted partial file: ${file.id}")
            }
        } catch (e: Exception) {
            logger.w(TAG, "log", "[${request.uploadId}] Failed to delete partial file: ${e.message}")
        }
    }

    private fun cleanupLock(lockKey: String) {
        val mutex = fileLocks[lockKey] ?: return
        // Only remove if unlocked (no active upload using this file)
        if (!mutex.isLocked && mutex.tryLock()) {
            try {
                fileLocks.remove(lockKey)
            } finally {
                mutex.unlock()
            }
        }
    }

    // ==================== Public Control Methods ====================

    fun pauseUpload(uploadId: String): Boolean {
        logger.d(TAG, "log", "[$uploadId] Pause requested")
        if (!debounce(uploadId)) {
            logger.w(TAG, "log", "[$uploadId] Pause debounced - too rapid")
            return false
        }
        return stateManager.pauseUpload(uploadId)
    }

    fun resumeUpload(uploadId: String): Boolean {
        logger.d(TAG, "log", "[$uploadId] Resume requested")
        if (!debounce(uploadId)) {
            logger.w(TAG, "log", "[$uploadId] Resume debounced - too rapid")
            return false
        }
        return stateManager.resumeUpload(uploadId)
    }

    suspend fun cancelUpload(uploadId: String): Boolean {
        logger.d(TAG, "log", "[$uploadId] Cancel requested")
        lastActionTime.remove(uploadId) // Cancel always goes through

        // Get upload info before cancelling to cleanup file
        val status = stateManager.getStatus(uploadId)
        val path = status?.metadata?.path ?: "/"
        val fileName = status?.metadata?.displayName

        val result = stateManager.cancelUpload(uploadId)

        // Explicitly delete file if upload is cancelled
        if (result && fileName != null) {
            logger.d(TAG, "log", "[$uploadId] Deleting cancelled file: $path/$fileName")
            try {
                val file = storageRepository.findFileByName(path, fileName).getOrNull()
            if (file != null) {
                // Convert ID to proper MediaStore URI
                val fileUri = "content://media/external/file/${file.id}"
                storageRepository.deleteFile(fileUri)
                logger.d(TAG, "log", "[$uploadId] Deleted cancelled file: $fileUri")
            } else {
                    logger.w(TAG, "log", "[$uploadId] Could not find file to delete: $path/$fileName")
                }
            } catch (e: Exception) {
                logger.w(TAG, "log", "[$uploadId] Failed to delete cancelled file", e)
            }
        }

        return result
    }

    private fun debounce(uploadId: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastActionTime[uploadId] ?: 0L
        return if (now - last >= ACTION_DEBOUNCE_MS) {
            lastActionTime[uploadId] = now
            true
        } else {
            false
        }
    }

    fun pauseAll() {
        logger.d(TAG, "log", "Pause all uploads")
        stateManager.setGlobalPause(true)
    }

    fun resumeAll() {
        logger.d(TAG, "log", "Resume all uploads")
        stateManager.setGlobalPause(false)
    }

    suspend fun cancelAll() {
        logger.d(TAG, "log", "Cancel all uploads")
        activeUploads.value.keys.forEach { uploadId ->
            cancelUpload(uploadId)
        }
    }

    fun getStatus(uploadId: String): UploadStatus? {
        return stateManager.getStatus(uploadId)
    }

    /**
     * Get status for status endpoint (handles partial/resumable uploads).
     */
    suspend fun getUploadStatusForQuery(
        path: String,
        fileName: String,
        uploadId: String?
    ): UploadQueryResult {
        val diskFile = storageRepository.findFileByName(path, fileName).getOrNull()
        val diskSize = diskFile?.size ?: 0L

        val memState = uploadId?.let { stateManager.getStatus(it) }
        val actualSize = memState?.bytesReceived?.coerceAtLeast(diskSize) ?: diskSize

        val status = when {
            memState?.state == UploadState.COMPLETED -> "completed"
            memState?.state == UploadState.CANCELLED -> "cancelled"
            memState?.state == UploadState.ERROR_PERMANENT -> "error_permanent"
            // ERROR_RETRYABLE should signal browser to retry - use "interrupted" so browser re-POSTs
            memState?.state == UploadState.ERROR_RETRYABLE -> "interrupted"
            memState?.state == UploadState.ERROR_PERMANENT -> "error"
            // "interrupted" is the browser-protocol term for paused
            memState?.state in setOf(UploadState.PAUSING, UploadState.PAUSED) -> "interrupted"
            // RESUMING and UPLOADING both mean "actively uploading" to browser
            memState?.state in setOf(UploadState.UPLOADING, UploadState.RESUMING) -> "uploading"
            diskSize > 0 && memState == null -> "interrupted"
            else -> memState?.state?.name?.lowercase() ?: "none"
        }

        return UploadQueryResult(
            exists = diskSize > 0,
            size = actualSize,
            status = status,
            canResume = status == "paused" || status == "interrupted"
        )
    }

    data class UploadQueryResult(
        val exists: Boolean,
        val size: Long,
        val status: String,
        val canResume: Boolean
    )
}
