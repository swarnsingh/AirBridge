package com.swaran.airbridge.core.network.upload

import com.swaran.airbridge.core.common.di.ApplicationScope
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.FileDeletedExternallyException
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AirBridge Upload Scheduler - Protocol v2.1 (Deterministic Fail-Fast)
 *
 * Core engine for handling resumable file uploads with deterministic state management
 * and fail-fast concurrency control.
 *
 * ## Architecture Role
 *
 * UploadScheduler sits between the HTTP layer ([UploadQueueManager]) and storage layer
 * ([StorageRepository]). It enforces:
 *
 * - File-level locking (one writer per file)
 * - Global concurrency limits (semaphore)
 * - Strict offset validation (disk size is source of truth)
 * - Cooperative cancellation (8KB chunks with ensureActive())
 * - Deterministic state transitions (validated by [UploadStateManager])
 *
 * ## Fail-Fast Concurrency
 *
 * Unlike traditional blocking approaches, this scheduler uses **fail-fast** semantics:
 *
 * ```
 * tryLock() → success → acquire lock
 *      ↓
 *   failed → return Busy immediately
 * ```
 *
 * This prevents:
 * - Deadlocks between concurrent uploads
 * - Resume races (second POST waits indefinitely)
 * - Resource exhaustion from blocked coroutines
 *
 * The browser is responsible for retry with exponential backoff.
 *
 * ## Deterministic Resume Protocol
 *
 * 1. Phone calls [resume] → state = RESUMING + 5s deadline
 * 2. SSE notifies browser
 * 3. Browser **immediately** POSTs to resume
 * 4. [handleUpload] validates offset and starts upload
 * 5. If no POST within 5s → revert to PAUSED
 *
 * **Critical**: Server never blocks waiting for browser. Browser never waits for server state.
 *
 * ## State Machine Enforcement
 *
 * All state transitions go through [UploadStateManager.transition] which validates:
 * - Current state can transition to target state
 * - No illegal transitions (e.g., COMPLETED → UPLOADING)
 * - Thread-safe atomic updates
 *
 * ## Usage
 *
 * ```kotlin
 * // From HTTP route (suspended context)
 * val result = scheduler.handleUpload(request) { call.receiveStream() }
 *
 * when (result) {
 *     is UploadResult.Success -> // Upload complete
 *     is UploadResult.Busy -> // Return 409, browser will retry
 *     is UploadResult.Paused -> // User paused mid-upload
 *     is UploadResult.Failure.OffsetMismatch -> // Browser sent wrong offset
 *     // ...
 * }
 * ```
 *
 * @see UploadQueueManager HTTP layer orchestration
 * @see UploadStateManager Deterministic state machine
 * @see StorageRepository Direct append storage I/O
 */
@Singleton
class UploadScheduler @Inject constructor(
    private val storageRepository: StorageRepository,
    private val stateManager: UploadStateManager,
    private val logger: AirLogger,
    @ApplicationScope private val applicationScope: kotlinx.coroutines.CoroutineScope
) {
    companion object {
        private const val TAG = "UploadScheduler"
        private const val MAX_CONCURRENT_UPLOADS = 3
        private const val RESUME_DEADLINE_MS = 5000L  // 5s window for browser to POST
    }

    private val fileLocks = ConcurrentHashMap<String, Mutex>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val uploadSemaphore = Semaphore(MAX_CONCURRENT_UPLOADS)
    private val resumeDeadlines = ConcurrentHashMap<String, Long>()

    val activeUploads: StateFlow<Map<String, UploadStatus>> = stateManager.activeUploads

    /**
     * Handle upload POST. Idempotent - same uploadId + offset is safe to retry.
     *
     * Protocol:
     * 1. Try file lock (fail-fast, return Busy if locked)
     * 2. Try semaphore (fail-fast, return Busy if at capacity)
     * 3. Validate offset == disk size
     * 4. Stream data
     * 5. Release resources
     *
     * Never blocks on resource acquisition.
     */
    suspend fun handleUpload(
        request: UploadRequest,
        receiveStream: suspend () -> java.io.InputStream
    ): UploadResult {
        val uploadId = request.uploadId
        val lockKey = "${request.path}/${request.fileName}"

        logger.d(TAG, "handleUpload", "[$uploadId] Incoming POST offset=${request.offset}")

        // 1️⃣ Check state before any work
        val currentStatus = stateManager.getStatus(uploadId)
        when (currentStatus?.state) {
            UploadState.CANCELLED -> return UploadResult.Cancelled(uploadId)
            UploadState.PAUSED -> {
                logger.d(TAG, "handleUpload", "[$uploadId] Upload is paused, rejecting POST")
                return UploadResult.Paused(uploadId, currentStatus.bytesReceived)
            }
            else -> {} // Continue with upload
        }

        // 2️⃣ FAIL-FAST: Try file mutex immediately (no blocking)
        val fileMutex = fileLocks.getOrPut(lockKey) { Mutex() }
        if (!fileMutex.tryLock()) {
            logger.d(TAG, "handleUpload", "[$uploadId] File busy (locked by another upload)")
            return UploadResult.Busy(uploadId, retryAfterMs = 200)
        }

        // 3️⃣ FAIL-FAST: Try semaphore (no blocking)
        if (!uploadSemaphore.tryAcquire()) {
            fileMutex.unlock()
            logger.d(TAG, "handleUpload", "[$uploadId] Concurrency limit reached")
            return UploadResult.Busy(uploadId, retryAfterMs = 300)
        }

        // 4️⃣ Register job for instant pause
        val currentJob = currentCoroutineContext()[Job]
        if (currentJob != null) {
            activeJobs[uploadId] = currentJob
        }

        try {
            // Clear any resume deadline since browser has now POSTed
            resumeDeadlines.remove(uploadId)

            return performUpload(request, receiveStream)
        } finally {
            activeJobs.remove(uploadId)
            uploadSemaphore.release()
            fileMutex.unlock()
            cleanupLock(lockKey)
        }
    }

    private suspend fun performUpload(
        request: UploadRequest,
        receiveStream: suspend () -> java.io.InputStream
    ): UploadResult {
        val uploadId = request.uploadId
        var bytesReceived = request.offset

        // 1️⃣ Validate offset against disk (source of truth)
        val diskFile = storageRepository
            .findFileByName(request.path, request.fileName)
            .getOrNull()

        val diskSize = diskFile?.size ?: 0L

        if (request.offset != diskSize) {
            logger.w(TAG, "performUpload", "[$uploadId] Offset mismatch: expected=${request.offset}, disk=$diskSize")
            return UploadResult.Failure.OffsetMismatch(
                uploadId = uploadId,
                bytesReceived = diskSize,
                expectedOffset = request.offset,
                actualDiskSize = diskSize
            )
        }

        // 2️⃣ Initialize metadata if new upload
        val metadata = UploadMetadata(
            uploadId = uploadId,
            fileUri = request.fileUri,
            displayName = request.fileName,
            path = request.path,
            totalBytes = request.totalBytes
        )
        stateManager.initialize(metadata)

        // 3️⃣ Transition to UPLOADING
        val allowed = stateManager.transition(uploadId, UploadState.UPLOADING)
        if (!allowed) {
            logger.w(TAG, "performUpload", "[$uploadId] State transition rejected")
            return UploadResult.Busy(uploadId, retryAfterMs = 100)
        }

        logger.d(TAG, "performUpload", "[$uploadId] Starting upload from diskSize=$diskSize")

        return try {
            val stream = receiveStream()

            val result = storageRepository.uploadFile(
                path = request.path,
                fileName = request.fileName,
                uploadId = uploadId,
                inputStream = stream,
                totalBytes = request.totalBytes,
                append = diskSize > 0,
                onProgress = { newBytes ->
                    bytesReceived = diskSize + newBytes
                    stateManager.updateProgress(uploadId, bytesReceived)

                    // Explicit pause/cancel detection (non-suspend, checks state only)
                    val state = stateManager.getStatus(uploadId)?.state
                    if (state == UploadState.PAUSING || state == UploadState.PAUSED) {
                        throw UploadPausedException()
                    }
                    if (state == UploadState.CANCELLED) {
                        throw UploadCancelledException()
                    }
                }
            )

            if (result.isSuccess) {
                stateManager.transition(uploadId, UploadState.COMPLETED)
                UploadResult.Success(uploadId, bytesReceived, result.getOrNull()?.id ?: "")
            } else {
                stateManager.transition(uploadId, UploadState.ERROR, result.exceptionOrNull()?.message)
                UploadResult.Failure.UnknownError(uploadId, bytesReceived, result.exceptionOrNull() ?: Exception("Unknown"))
            }

        } catch (e: UploadPausedException) {
            stateManager.transition(uploadId, UploadState.PAUSED)
            UploadResult.Paused(uploadId, bytesReceived)

        } catch (e: UploadCancelledException) {
            stateManager.transition(uploadId, UploadState.CANCELLED)
            cleanupPartial(request)
            UploadResult.Cancelled(uploadId)

        } catch (e: FileDeletedExternallyException) {
            logger.w(TAG, "performUpload", "[$uploadId] File deleted externally, returning error")
            stateManager.transition(uploadId, UploadState.ERROR, "File deleted externally")
            UploadResult.Failure.FileDeleted(uploadId, bytesReceived)

        } catch (e: kotlinx.coroutines.CancellationException) {
            // Job was cancelled - check current state to determine if pause or cancel
            val status = stateManager.getStatus(uploadId)
            logger.d(TAG, "performUpload", "[$uploadId] Coroutine cancelled, state: ${status?.state}")
            if (status?.state == UploadState.PAUSED || status?.state == UploadState.PAUSING) {
                UploadResult.Paused(uploadId, bytesReceived)
            } else {
                stateManager.transition(uploadId, UploadState.CANCELLED)
                cleanupPartial(request)
                UploadResult.Cancelled(uploadId)
            }

        } catch (e: Exception) {
            logger.e(TAG, "performUpload", "[$uploadId] Failed: ${e.message}", e)
            stateManager.transition(uploadId, UploadState.ERROR, e.message)
            UploadResult.Failure.UnknownError(uploadId, bytesReceived, e)
        }
    }

    /**
     * Pause upload. Sets state to PAUSING (transitional), then cancels job.
     * Scheduler catches exception and finalizes to PAUSED.
     */
    fun pause(uploadId: String) {
        logger.d(TAG, "pause", "[$uploadId] Requested")
        // Signal PAUSING state - upload loop will detect and throw
        stateManager.transition(uploadId, UploadState.PAUSING)
        activeJobs[uploadId]?.cancel()
    }

    /**
     * Resume upload. Sets state to RESUMING with deadline.
     * Browser MUST POST within RESUME_DEADLINE_MS or server reverts to PAUSED.
     */
    fun resume(uploadId: String) {
        val status = stateManager.getStatus(uploadId)
        logger.d(TAG, "resume", "[$uploadId] Current state: ${status?.state}")

        if (status?.state != UploadState.PAUSED) {
            logger.w(TAG, "resume", "[$uploadId] Cannot resume from state ${status?.state}")
            return
        }

        // Set RESUMING state and deadline
        val deadline = System.currentTimeMillis() + RESUME_DEADLINE_MS
        resumeDeadlines[uploadId] = deadline
        stateManager.transition(uploadId, UploadState.RESUMING)

        logger.d(TAG, "resume", "[$uploadId] Set RESUMING, deadline in ${RESUME_DEADLINE_MS}ms")

        // Start deadline watcher - runs independently of streaming job
        // The browser must POST within RESUME_DEADLINE_MS or we revert to PAUSED
        applicationScope.launch {
            delay(RESUME_DEADLINE_MS)
            checkResumeDeadline(uploadId)
        }
    }

    private fun checkResumeDeadline(uploadId: String) {
        val deadline = resumeDeadlines[uploadId] ?: return
        if (System.currentTimeMillis() >= deadline) {
            val status = stateManager.getStatus(uploadId)
            if (status?.state == UploadState.RESUMING) {
                logger.w(TAG, "checkResumeDeadline", "[$uploadId] Deadline expired, reverting to PAUSED")
                stateManager.transition(uploadId, UploadState.PAUSED)
                resumeDeadlines.remove(uploadId)
            }
        }
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
        
        // 1. Cancel active job first (signals upload to stop)
        val job = activeJobs[uploadId]
        if (job != null) {
            logger.d(TAG, "cancel", "[$uploadId] Cancelling active job")
            job.cancel()
            job.join() // Wait for job to complete cancellation
        }
        
        // 2. Remove deadline if present
        resumeDeadlines.remove(uploadId)
        
        // 3. Clean up partial file
        cleanupPartial(request)
        
        // 4. Mark as cancelled in state
        stateManager.transition(uploadId, UploadState.CANCELLED)
    }

    private suspend fun cleanupPartial(request: UploadRequest) {
        val cleanPath = request.path.trimEnd('/')
        val fileName = request.fileName
        logger.d(TAG, "cleanupPartial", "Looking for file: path='$cleanPath', filename='$fileName'")
        
        val fileResult = storageRepository.findFileByName(cleanPath, fileName)
        logger.d(TAG, "cleanupPartial", "Find result: ${fileResult.isSuccess}, exists: ${fileResult.getOrNull() != null}")
        
        fileResult.getOrNull()?.let { file ->
            logger.d(TAG, "cleanupPartial", "Found file id: ${file.id}, deleting...")
            val deleteResult = storageRepository.deleteFile(file.id)
            if (deleteResult.isSuccess) {
                logger.d(TAG, "cleanupPartial", "File deleted successfully")
            } else {
                logger.w(TAG, "cleanupPartial", "Delete failed: ${deleteResult.exceptionOrNull()?.message}")
            }
        } ?: logger.w(TAG, "cleanupPartial", "File not found: $cleanPath/$fileName")
    }

    private fun cleanupLock(lockKey: String) {
        val mutex = fileLocks[lockKey]
        if (mutex != null && !mutex.isLocked) {
            fileLocks.remove(lockKey)
        }
    }

    /**
     * Query status. Disk size is source of truth.
     */
    suspend fun queryStatus(path: String, fileName: String, uploadId: String?): UploadQueryResult {
        val diskFile = storageRepository.findFileByName(path, fileName).getOrNull()
        val diskSize = diskFile?.size ?: 0L
        val memState = uploadId?.let { stateManager.getStatus(it) }

        val state = when (memState?.state) {
            UploadState.COMPLETED -> UploadState.COMPLETED.value
            UploadState.CANCELLED -> UploadState.CANCELLED.value
            UploadState.ERROR -> UploadState.ERROR.value
            UploadState.UPLOADING -> UploadState.UPLOADING.value
            UploadState.RESUMING -> UploadState.RESUMING.value
            UploadState.PAUSING -> UploadState.PAUSING.value
            UploadState.PAUSED -> UploadState.PAUSED.value
            else -> if (diskSize > 0) UploadState.PAUSED.value else UploadState.NONE.value
        }

        // Check if busy (file locked)
        val lockKey = "$path/$fileName"
        val isBusy = fileLocks[lockKey]?.isLocked == true

        return UploadQueryResult(
            exists = diskSize > 0,
            bytesReceived = diskSize,
            state = state,
            canResume = diskSize > 0 && state != "completed",
            isBusy = isBusy
        )
    }

    fun getStatus(uploadId: String): UploadStatus? = stateManager.getStatus(uploadId)

    data class UploadQueryResult(
        val exists: Boolean,
        val bytesReceived: Long,
        val state: String,
        val canResume: Boolean,
        val isBusy: Boolean = false
    )
}
