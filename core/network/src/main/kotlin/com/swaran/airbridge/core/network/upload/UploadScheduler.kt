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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encapsulates all coordination state for a single upload.
 *
 * Single ownership per upload eliminates cross-map coordination:
 * - Lock (Mutex) for file-level single writer
 * - Job reference for cancellation
 * - Centralized lifecycle management
 */
private data class UploadSession(
    val uploadId: String,
    val lock: Mutex = Mutex(),
    val jobRef: AtomicReference<Job?> = AtomicReference(null)
) {
    fun setJob(job: Job) {
        jobRef.set(job)
    }

    fun cancel() {
        jobRef.getAndSet(null)?.cancel()
    }

    fun clearJob() {
        jobRef.set(null)
    }

    fun hasActiveJob(): Boolean {
        return jobRef.get()?.isActive == true
    }
}

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
        private const val RESUME_DEADLINE_MS = 30000L  // 30s window for browser POST (was 5s)
    }

    private val sessions = ConcurrentHashMap<String, UploadSession>()
    private val uploadSemaphore = Semaphore(MAX_CONCURRENT_UPLOADS)
    private val resumeDeadlines = ConcurrentHashMap<String, Job>()

    val activeUploads: StateFlow<Map<String, UploadStatus>> = stateManager.activeUploads

    private fun getSession(uploadId: String): UploadSession {
        return sessions.getOrPut(uploadId) {
            UploadSession(uploadId)
        }
    }

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

        // Cancel any pending resume deadline - browser has POSTed
        resumeDeadlines.remove(uploadId)?.cancel()

        // Atomic session creation
        val session = sessions.computeIfAbsent(uploadId) { UploadSession(it) }

        // Fail-fast semaphore — never suspend the caller
        if (!uploadSemaphore.tryAcquire()) {
            return UploadResult.Busy(uploadId, retryAfterMs = 300)
        }

        try {
            // Fail-fast file lock — never suspend the caller
            if (!session.lock.tryLock()) {
                return UploadResult.Busy(uploadId, retryAfterMs = 100)
            }
            try {
                val currentJob = currentCoroutineContext()[Job]
                currentJob?.let { session.setJob(it) }
                return performUpload(request, receiveStream)
            } finally {
                session.lock.unlock()
                session.clearJob()
            }
        } finally {
            uploadSemaphore.release()
            // Clean up session if upload is terminal
            val state = stateManager.getStatus(uploadId)?.state
            if (state == UploadState.COMPLETED ||
                state == UploadState.CANCELLED ||
                state == UploadState.ERROR) {
                sessions.remove(uploadId)
            }
        }
    }

    private suspend fun performUpload(
        request: UploadRequest,
        receiveStream: suspend () -> java.io.InputStream
    ): UploadResult {
        val uploadId = request.uploadId
        var bytesReceived = request.offset

        // 1️⃣ ALWAYS initialize metadata first - ensures queryStatus can find this upload
        val metadata = UploadMetadata(
            uploadId = uploadId,
            fileUri = request.fileUri,
            displayName = request.fileName,
            path = request.path,
            totalBytes = request.totalBytes
        )
        stateManager.initialize(metadata)

        // 2️⃣ Validate offset against disk (source of truth)
        val diskFile = storageRepository
            .findFileByName(request.path, request.fileName)
            .getOrNull()

        val diskSize = diskFile?.size ?: 0L

        if (request.offset != diskSize) {
            logger.w(TAG, "performUpload", "[$uploadId] Offset mismatch: expected=${request.offset}, disk=$diskSize")
            // Initialize in stateManager so queryStatus can return correct disk size
            stateManager.updateProgress(uploadId, diskSize)
            return UploadResult.Failure.OffsetMismatch(
                uploadId = uploadId,
                bytesReceived = diskSize,
                expectedOffset = request.offset,
                actualDiskSize = diskSize
            )
        }

        // 3️⃣ Transition to UPLOADING
        val allowed = stateManager.transition(uploadId, UploadState.UPLOADING)
        if (!allowed) {
            logger.w(TAG, "performUpload", "[$uploadId] State transition rejected")
            return UploadResult.Busy(uploadId, retryAfterMs = 100)
        }

        logger.d(TAG, "performUpload", "[$uploadId] Starting upload from diskSize=$diskSize")

        // Set up completion handler to catch unexpected coroutine death (network disconnect)
        val currentJob = currentCoroutineContext()[Job]
        currentJob?.invokeOnCompletion { cause ->
            if (cause != null) {
                val currentState = stateManager.getStatus(uploadId)?.state
                logger.w(TAG, "performUpload", "[$uploadId] Coroutine died unexpectedly: ${cause.javaClass.simpleName}, state=$currentState")
                if (currentState == UploadState.UPLOADING || currentState == UploadState.RESUMING) {
                    stateManager.transition(uploadId, UploadState.PAUSED, "Connection lost")
                }
            }
        }

        return try {
            val stream = receiveStream()
            
            // UC-05: Validate state still UPLOADING before streaming (catches pause-immediately-after-resume race)
            val preStreamState = stateManager.getStatus(uploadId)?.state
            if (preStreamState != UploadState.UPLOADING) {
                logger.w(TAG, "performUpload", "[$uploadId] State changed to $preStreamState before streaming, aborting")
                stream.close()
                return UploadResult.Busy(uploadId, retryAfterMs = 100)
            }
            
            // Track accumulated bytes starting from disk size
            var totalBytesReceived = diskSize

            val result = storageRepository.uploadFile(
                path = request.path,
                fileName = request.fileName,
                uploadId = uploadId,
                inputStream = stream,
                totalBytes = request.totalBytes,
                append = diskSize > 0,
                onProgress = { incrementalBytes ->
                    // ACCUMULATE bytes properly - incrementalBytes is just this chunk
                    totalBytesReceived += incrementalBytes
                    bytesReceived = totalBytesReceived
                    stateManager.updateProgress(uploadId, totalBytesReceived)

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
                sessions.remove(uploadId) // Terminal state - clean up session
                UploadResult.Success(uploadId, bytesReceived, result.getOrNull()?.id ?: "")
            } else {
                stateManager.transition(uploadId, UploadState.ERROR, result.exceptionOrNull()?.message)
                sessions.remove(uploadId) // Terminal state - clean up session
                UploadResult.Failure.UnknownError(uploadId, bytesReceived, result.exceptionOrNull() ?: Exception("Unknown"))
            }

        } catch (e: UploadPausedException) {
            stateManager.transition(uploadId, UploadState.PAUSED)
            UploadResult.Paused(uploadId, bytesReceived)

        } catch (e: UploadCancelledException) {
            stateManager.transition(uploadId, UploadState.CANCELLED)
            cleanupPartial(request)
            sessions.remove(uploadId) // Terminal state - clean up session
            UploadResult.Cancelled(uploadId)

        } catch (e: FileDeletedExternallyException) {
            logger.w(TAG, "performUpload", "[$uploadId] File deleted externally, returning error")
            stateManager.transition(uploadId, UploadState.ERROR, "File deleted externally")
            sessions.remove(uploadId) // Terminal state - clean up session
            UploadResult.Failure.FileDeleted(uploadId, bytesReceived)

        } catch (e: kotlinx.coroutines.CancellationException) {
            // Job was cancelled - check current state to determine if pause or cancel
            val status = stateManager.getStatus(uploadId)
            logger.d(TAG, "performUpload", "[$uploadId] Coroutine cancelled, state: ${status?.state}")
            if (status?.state == UploadState.PAUSING) {
                // Transition to PAUSED properly
                stateManager.transition(uploadId, UploadState.PAUSED)
                UploadResult.Paused(uploadId, bytesReceived)
            } else if (status?.state == UploadState.PAUSED) {
                UploadResult.Paused(uploadId, bytesReceived)
            } else {
                stateManager.transition(uploadId, UploadState.CANCELLED)
                cleanupPartial(request)
                sessions.remove(uploadId) // Terminal state - clean up session
                UploadResult.Cancelled(uploadId)
            }

        } catch (e: Exception) {
            logger.e(TAG, "performUpload", "[$uploadId] Failed: ${e.message}", e)
            stateManager.transition(uploadId, UploadState.ERROR, e.message)
            sessions.remove(uploadId) // Terminal state - clean up session
            UploadResult.Failure.UnknownError(uploadId, bytesReceived, e)
        }
    }

    /**
     * Pause upload. Sets state to PAUSING (transitional), cancels and clears job via session.
     * Clearing job early ensures resume POST doesn't wait behind lock cleanup.
     */
    fun pauseUpload(uploadId: String): Boolean {
        // Always transition state first - session may not exist if upload hasn't started
        val transitioned = stateManager.transition(uploadId, UploadState.PAUSING)
        if (!transitioned) return false

        // Cancel/clear job if session exists
        sessions[uploadId]?.let { session ->
            session.cancel()
            session.clearJob() // Clear early so resume sees no active job
        }
        return true
    }

    /**
     * Legacy pause method - delegates to pauseUpload
     */
    fun pause(uploadId: String) {
        pauseUpload(uploadId)
    }

    /**
     * Resume upload. Sets state to RESUMING with 30s deadline.
     * Browser should POST within deadline or server reverts to PAUSED.
     * Deadline is cancelled when browser successfully POSTs.
     */
    fun resume(uploadId: String): Boolean {
        // Atomic transition check+set avoids TOCTOU between read and transition
        val transitioned = stateManager.transition(uploadId, UploadState.RESUMING)
        if (!transitioned) {
            logger.w(TAG, "resume", "[$uploadId] Cannot resume from state ${stateManager.getStatus(uploadId)?.state}")
            return false
        }

        logger.d(TAG, "resume", "[$uploadId] Set RESUMING, 30s deadline started")

        // Deadline: revert to PAUSED if browser never POSTs
        val deadlineJob = applicationScope.launch {
            delay(RESUME_DEADLINE_MS)
            resumeDeadlines.remove(uploadId)
            if (stateManager.getStatus(uploadId)?.state == UploadState.RESUMING) {
                logger.w(TAG, "resume", "[$uploadId] Browser missed 30s window, reverting to PAUSED")
                stateManager.transition(uploadId, UploadState.PAUSED)
            }
        }
        resumeDeadlines[uploadId] = deadlineJob
        return true
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
        sessions[uploadId]?.let { session ->
            session.cancel()
            withTimeoutOrNull(3000) {
                session.jobRef.get()?.join()
            } ?: logger.w(TAG, "cancel", "[$uploadId] Job didn't finish in 3s, forcing cleanup")
        }
        cleanupPartial(request)
        stateManager.transition(uploadId, UploadState.CANCELLED)
        sessions.remove(uploadId)
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

    /**
     * Query status. Disk size is source of truth, but memory state takes precedence
     * for active uploads to handle in-flight bytes not yet flushed to disk.
     */
    suspend fun queryStatus(path: String, fileName: String, uploadId: String?): UploadQueryResult {
        // Always try to find the file on disk first
        val diskFile = storageRepository.findFileByName(path, fileName).getOrNull()
        val diskSize = diskFile?.size ?: 0L

        // Get memory state if uploadId provided
        val memState = uploadId?.let { stateManager.getStatus(it) }

        // Determine bytes received:
        // 1. If memory state exists and has bytes, use the larger of mem/disk
        // 2. If upload exists in stateManager but file not on disk, use memState bytes
        // 3. Otherwise use disk size
        val bytesReceived = when {
            memState != null && memState.bytesReceived > diskSize -> memState.bytesReceived
            memState != null && diskSize == 0L -> memState.bytesReceived
            else -> diskSize
        }

        // Determine state - prioritize memory state for active uploads
        val state = when (memState?.state) {
            UploadState.COMPLETED -> UploadState.COMPLETED.value
            UploadState.CANCELLED -> UploadState.CANCELLED.value
            UploadState.ERROR -> UploadState.ERROR.value
            UploadState.UPLOADING -> UploadState.UPLOADING.value
            UploadState.RESUMING -> UploadState.RESUMING.value
            UploadState.PAUSING -> UploadState.PAUSING.value
            UploadState.PAUSED -> UploadState.PAUSED.value
            UploadState.QUEUED -> UploadState.QUEUED.value
            UploadState.NONE -> {
                // Fresh upload initialized but not yet transitioned - treat as uploading
                // since metadata exists in stateManager
                UploadState.UPLOADING.value
            }
            null -> {
                // No memory state - determine from disk
                when {
                    diskSize > 0 -> UploadState.PAUSED.value  // File exists but no active upload
                    else -> UploadState.NONE.value
                }
            }
        }

        // Check if busy (any session holding lock for this file)
        val isBusy = uploadId?.let { sessions[it]?.lock?.isLocked == true } ?: false

        // More accurate exists check - either in memory or on disk
        val exists = memState != null || bytesReceived > 0

        return UploadQueryResult(
            exists = exists,
            bytesReceived = bytesReceived,
            state = state,
            canResume = bytesReceived > 0 && state != "completed" && state != "cancelled" && state != "error",
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
