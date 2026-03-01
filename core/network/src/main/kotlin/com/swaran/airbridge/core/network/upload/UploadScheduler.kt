package com.swaran.airbridge.core.network.upload

import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.UploadCancelledException
import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadPausedException
import com.swaran.airbridge.domain.model.UploadRequest
import com.swaran.airbridge.domain.model.UploadResult
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.model.UploadStatus
import com.swaran.airbridge.domain.repository.StorageRepository
import com.swaran.airbridge.domain.usecase.UploadStateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic upload scheduler - Protocol v2.
 *
 * Principles:
 * - Disk size is source of truth
 * - POST is idempotent (offset must equal disk size)
 * - Exactly one writer per file (mutex)
 * - No BUSY state - mutex blocks instead
 * - Resume = POST from disk offset
 */
@Singleton
class UploadScheduler @Inject constructor(
    private val storageRepository: StorageRepository,
    private val stateManager: UploadStateManager,
    private val logger: AirLogger
) {
    companion object {
        private const val TAG = "UploadScheduler"
    }

    private val fileLocks = ConcurrentHashMap<String, Mutex>()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    val activeUploads: StateFlow<Map<String, UploadStatus>> = stateManager.activeUploads

    /**
     * Handle upload POST. Idempotent - same uploadId + offset is safe to retry.
     *
     * Protocol:
     * 1. Acquire file lock (blocks if another upload active)
     * 2. Validate offset == disk size
     * 3. Stream data
     * 4. Release lock
     */
    suspend fun handleUpload(
        request: UploadRequest,
        receiveStream: suspend () -> java.io.InputStream
    ): UploadResult {
        val uploadId = request.uploadId
        val lockKey = "${request.path}/${request.fileName}"
        val mutex = fileLocks.getOrPut(lockKey) { Mutex() }

        return mutex.withLock {
            val diskSize = storageRepository
                .findFileByName(request.path, request.fileName)
                .getOrNull()?.size ?: 0L

            // Strict offset validation (idempotent guarantee)
            if (request.offset != diskSize) {
                logger.w(TAG, "handleUpload", "[$uploadId] Offset mismatch: expected=${request.offset}, disk=$diskSize")
                return UploadResult.Failure.OffsetMismatch(
                    uploadId = uploadId,
                    bytesReceived = diskSize,
                    expectedOffset = request.offset,
                    actualDiskSize = diskSize
                )
            }

            // Register/upload
            val metadata = UploadMetadata(
                uploadId = uploadId,
                fileUri = request.fileUri,
                displayName = request.fileName,
                path = request.path,
                totalBytes = request.totalBytes
            )
            stateManager.initialize(metadata)

            val currentJob = currentCoroutineContext()[Job]
            if (currentJob != null) {
                activeJobs[uploadId] = currentJob
            }

            try {
                performUpload(request, diskSize, receiveStream)
            } finally {
                activeJobs.remove(uploadId)
            }
        }
    }

    private suspend fun performUpload(
        request: UploadRequest,
        diskSize: Long,
        receiveStream: suspend () -> java.io.InputStream
    ): UploadResult {
        val uploadId = request.uploadId
        var bytesWritten = diskSize

        logger.d(TAG, "performUpload", "[$uploadId] Starting upload, diskSize=$diskSize, transitioning to UPLOADING")
        
        // Transition to uploading
        stateManager.transition(uploadId, UploadState.UPLOADING)

        return try {
            val stream = receiveStream()

            storageRepository.uploadFile(
                path = request.path,
                fileName = request.fileName,
                uploadId = uploadId,
                inputStream = stream,
                totalBytes = request.totalBytes,
                append = diskSize > 0,
                onProgress = { delta ->
                    bytesWritten = diskSize + delta
                    stateManager.updateProgress(uploadId, bytesWritten)
                    checkInterrupted(uploadId)
                }
            ).getOrThrow()

            stateManager.transition(uploadId, UploadState.COMPLETED)
            UploadResult.Success(uploadId, bytesWritten, "")

        } catch (e: UploadPausedException) {
            stateManager.transition(uploadId, UploadState.PAUSED)
            UploadResult.Paused(uploadId, bytesWritten)

        } catch (e: UploadCancelledException) {
            stateManager.transition(uploadId, UploadState.CANCELLED)
            cleanupPartial(request)
            UploadResult.Cancelled(uploadId)

        } catch (e: kotlinx.coroutines.CancellationException) {
            // Job was cancelled - check current state to determine if pause or cancel
            val status = stateManager.getStatus(uploadId)
            logger.d(TAG, "performUpload", "[$uploadId] Job cancelled, current state: ${status?.state}")
            if (status?.state == UploadState.PAUSED) {
                // Pause requested - return Paused result (state already PAUSED)
                UploadResult.Paused(uploadId, bytesWritten)
            } else {
                // True cancellation
                stateManager.transition(uploadId, UploadState.CANCELLED)
                UploadResult.Cancelled(uploadId)
            }

        } catch (e: Exception) {
            logger.e(TAG, "performUpload", "[$uploadId] Failed: ${e.message}", e)
            stateManager.transition(uploadId, UploadState.ERROR, e.message)
            UploadResult.Failure.UnknownError(uploadId, bytesWritten, e)
        }
    }

    private fun checkInterrupted(uploadId: String) {
        when (stateManager.getStatus(uploadId)?.state) {
            UploadState.PAUSED -> throw UploadPausedException()
            UploadState.CANCELLED -> throw UploadCancelledException()
            else -> { /* continue */ }
        }
    }

    /**
     * Pause upload. Sets state to PAUSED, then cancels job.
     * Scheduler catches CancellationException and sees PAUSED state.
     */
    fun pause(uploadId: String) {
        logger.d(TAG, "pause", "[$uploadId] Requested")
        // MUST set state to PAUSED before cancelling - this signals "pause" not "cancel"
        stateManager.transition(uploadId, UploadState.PAUSED)
        activeJobs[uploadId]?.cancel()
    }

    /**
     * Resume upload. Just ensures state is PAUSED (ready for browser POST).
     * Resume = client POST again. Server never auto-resumes.
     */
    fun resume(uploadId: String) {
        val status = stateManager.getStatus(uploadId)
        logger.d(TAG, "resume", "[$uploadId] Current state: ${status?.state}, keeping as PAUSED for browser POST")
        // State stays PAUSED - browser will POST and transition to UPLOADING
    }

    /**
     * Check if upload can be resumed.
     */
    fun canResume(uploadId: String): Boolean {
        return stateManager.getStatus(uploadId)?.state == UploadState.PAUSED
    }

    /**
     * Cancel upload and delete partial file.
     */
    suspend fun cancel(uploadId: String, request: UploadRequest) {
        logger.d(TAG, "cancel", "[$uploadId] Requested")
        stateManager.transition(uploadId, UploadState.CANCELLED)
        activeJobs[uploadId]?.cancel()
        cleanupPartial(request)
    }

    private suspend fun cleanupPartial(request: UploadRequest) {
        storageRepository.findFileByName(request.path, request.fileName)
            .getOrNull()
            ?.let { storageRepository.deleteFile(it.id) }
    }

    /**
     * Query status. Disk size is source of truth.
     */
    suspend fun queryStatus(path: String, fileName: String, uploadId: String?): UploadQueryResult {
        val diskFile = storageRepository.findFileByName(path, fileName).getOrNull()
        val diskSize = diskFile?.size ?: 0L
        val memState = uploadId?.let { stateManager.getStatus(it) }

        val state = when (memState?.state) {
            UploadState.COMPLETED -> "completed"
            UploadState.CANCELLED -> "cancelled"
            UploadState.ERROR -> "error"
            UploadState.UPLOADING -> "uploading"
            UploadState.PAUSED -> "paused"
            else -> if (diskSize > 0) "paused" else "none"
        }

        return UploadQueryResult(
            exists = diskSize > 0,
            bytesReceived = diskSize,
            state = state,
            canResume = diskSize > 0 && state != "completed"
        )
    }

    fun getStatus(uploadId: String): UploadStatus? = stateManager.getStatus(uploadId)

    data class UploadQueryResult(
        val exists: Boolean,
        val bytesReceived: Long,
        val state: String,
        val canResume: Boolean
    )
}
