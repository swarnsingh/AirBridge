package com.swaran.airbridge.core.network.upload

import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.UploadRequest
import com.swaran.airbridge.domain.model.UploadResult
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.usecase.UploadStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AirBridge Upload Queue Manager - Protocol v2.1
 *
 * Orchestrates multiple file uploads with deterministic concurrency control.
 * This is the HTTP layer's entry point for upload handling.
 *
 * ## Architecture Principles
 *
 * 1. **Fail-Fast Concurrency**: Uses tryAcquire() pattern - never blocks waiting for resources.
 *    If server is at capacity, returns UploadResult.Busy immediately.
 *
 * 2. **POST-Driven Resume**: Browser initiates all uploads via POST. Server never auto-resumes.
 *    This eliminates deadlocks and race conditions.
 *
 * 3. **Layer Separation**: QueueManager handles HTTP-level orchestration, UploadScheduler handles
 *    file-level locking and state transitions, StorageRepository handles raw I/O.
 *
 * ## Concurrency Model
 *
 * ```
 * Browser POST → QueueManager.enqueue()
 *                      │
 *                      ▼
 *              tryAcquire semaphore slot
 *                      │
 *          ┌───────────┴───────────┐
 *          ▼                       ▼
 *     Success (slot            Fail (at capacity)
 *     available)                    │
 *          │                       ▼
 *          ▼              return UploadResult.Busy
 *   scheduler.handleUpload()        │
 *          │              Browser retries with
 *          ▼              exponential backoff
 *   perform upload
 * ```
 *
 * ## Queue State Broadcasting
 *
 * Queue state is exposed via [queueState] StateFlow for SSE broadcasting to browsers.
 * This provides real-time visibility into:
 * - Active upload count
 * - Paused uploads
 * - Global pause status
 *
 * @see UploadScheduler For file-level locking and state machine
 * @see UploadStateManager For deterministic state transitions
 */
@Singleton
class UploadQueueManager @Inject constructor(
    val scheduler: UploadScheduler,
    private val stateManager: UploadStateManager,
    private val logger: AirLogger
) {
    companion object {
        private const val TAG = "UploadQueueManager"

        /** Maximum concurrent uploads. Server returns Busy if exceeded. */
        private const val DEFAULT_MAX_PARALLEL = 3
    }

    /** Track active upload jobs for pause/cancel operations */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Global pause state - when true, all new uploads return Busy */
    private val isGlobalPaused = AtomicBoolean(false)

    /** Queue state for SSE broadcasting */
    private val _queueState = MutableStateFlow(QueueState())
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    /**
     * Enqueue and execute an upload request.
     *
     * This is the main entry point from [UploadRoutes] when browser POSTs an upload.
     *
     * ## Fail-Fast Behavior
     *
     * If server is at concurrency capacity or globally paused, returns [UploadResult.Busy]
     * immediately. Browser must retry with exponential backoff.
     *
     * ## Protocol Flow
     *
     * 1. Check global pause state
     * 2. Delegate to [UploadScheduler.handleUpload] for file locking
     * 3. Stream data via [receiveStream]
     * 4. Return result based on final state
     *
     * @param request Upload metadata (id, filename, offset, etc.)
     * @param receiveStream Suspended lambda providing the HTTP input stream
     * @return UploadResult indicating success, pause, busy, or error
     */
    suspend fun enqueue(
        request: UploadRequest,
        receiveStream: suspend () -> java.io.InputStream
    ): UploadResult {
        logger.d(TAG, "enqueue", "[${request.uploadId}] Enqueuing ${request.fileName} (offset=${request.offset})")

        // Check global pause - return busy if paused
        if (isGlobalPaused.get()) {
            logger.d(TAG, "enqueue", "[${request.uploadId}] Global pause active, returning Busy")
            return UploadResult.Busy(request.uploadId, retryAfterMs = 500)
        }

        // Track active count for queue state
        updateQueueState()

        // Start upload immediately - UploadScheduler handles fail-fast locking
        return startUpload(request, receiveStream)
    }

    /**
     * Start upload execution.
     *
     * Delegates to [UploadScheduler] which handles:
     * - File-level mutex tryLock()
     * - Semaphore tryAcquire()
     * - Offset validation
     * - State transitions
     * - Cooperative cancellation
     */
    private suspend fun startUpload(
        request: UploadRequest,
        receiveStream: suspend () -> java.io.InputStream
    ): UploadResult {
        val uploadId = request.uploadId
        // Track the calling coroutine's job, not a wrapper
        coroutineContext[Job]?.let { activeJobs[uploadId] = it }
        updateQueueState()

        return try {
            scheduler.handleUpload(request, receiveStream)
        } finally {
            activeJobs.remove(uploadId)
            updateQueueState()
        }
    }

    /**
     * Pause a specific upload.
     *
     * Sets state to PAUSING (transitional) via scheduler, which then cancels the job.
     * The upload loop catches [CancellationException] and transitions to PAUSED.
     *
     * ## Protocol
     *
     * Phone UI → POST /api/upload/pause → Server sets PAUSING → job.cancel() →
     * Server sets PAUSED → SSE event → Browser aborts XHR
     */
    fun pause(uploadId: String) {
        logger.d(TAG, "pause", "[$uploadId] Requested")
        // Signal through scheduler to ensure proper state machine transitions
        scheduler.pause(uploadId)
        activeJobs[uploadId]?.cancel()
    }

    /**
     * Resume a specific upload.
     *
     * In the deterministic protocol, resume is **browser-driven**. This method
     * transitions state to RESUMING, which signals to the browser that it should
     * immediately POST to resume.
     *
     * ## Protocol
     *
     * Phone UI → POST /api/upload/resume → Server sets RESUMING → SSE event →
     * Browser POSTs immediately → Server validates → State → UPLOADING
     *
     * @see UploadScheduler.resume for deadline handling
     */
    fun resume(uploadId: String): Boolean {
        logger.d(TAG, "resume", "[$uploadId] Resume requested")
        val accepted = scheduler.resume(uploadId)
        if (!accepted) {
            logger.w(TAG, "resume", "[$uploadId] Ignored resume from state=${stateManager.getStatus(uploadId)?.state}")
        }
        return accepted
    }

    /**
     * Pause all active uploads.
     *
     * Sets global pause flag and cancels all active jobs via scheduler.
     * New uploads will return [UploadResult.Busy] until [resumeAll] is called.
     */
    fun pauseAll() {
        logger.i(TAG, "pauseAll", "Pausing all uploads")
        isGlobalPaused.set(true)

        activeJobs.forEach { (uploadId, job) ->
            scheduler.pause(uploadId)
            job.cancel()
        }

        updateQueueState { copy(isPaused = true) }
    }

    /**
     * Resume all uploads.
     *
     * Clears global pause flag and signals all PAUSED uploads to resume.
     */
    fun resumeAll() {
        logger.i(TAG, "resumeAll", "Clearing global pause, signalling all PAUSED uploads")
        isGlobalPaused.set(false)

        // Signal all PAUSED uploads to resume
        stateManager.activeUploads.value
            .filter { it.value.state == UploadState.PAUSED }
            .keys
            .forEach { uploadId ->
                scheduler.resume(uploadId) // sets RESUMING → browser POSTs
            }

        updateQueueState { copy(isPaused = false) }
    }

    /**
     * Cancel a specific upload.
     *
     * Cancels the job and deletes partial file via [UploadScheduler.cancel].
     */
    suspend fun cancel(uploadId: String, request: UploadRequest) {
        logger.d(TAG, "cancel", "[$uploadId] Cancelling")

        activeJobs[uploadId]?.cancel()
        activeJobs.remove(uploadId)

        scheduler.cancel(uploadId, request)
        updateQueueState()
    }

    /**
     * Get current queue statistics for metrics endpoint.
     */
    fun getStats(): QueueStats {
        val all = stateManager.activeUploads.value
        return QueueStats(
            total = all.size,
            active = all.count { it.value.state == UploadState.UPLOADING },
            queued = all.count { it.value.state == UploadState.NONE || it.value.state == UploadState.QUEUED },
            paused = all.count { it.value.state == UploadState.PAUSED || it.value.state == UploadState.RESUMING },
            completed = all.count { it.value.state == UploadState.COMPLETED },
            error = all.count { it.value.state == UploadState.ERROR }
        )
    }

    private fun updateQueueState(transform: QueueState.() -> QueueState = { this }) {
        val stats = getStats()
        _queueState.update {
            QueueState(
                isPaused = isGlobalPaused.get(),
                activeCount = stats.active,
                queuedCount = stats.queued,
                pausedCount = stats.paused
            )
        }
    }

    /**
     * Queue state for SSE broadcasting to browsers.
     *
     * @property isPaused Global pause state - new uploads rejected when true
     * @property activeCount Currently uploading files
     * @property queuedCount Files waiting for browser POST
     * @property pausedCount Files in PAUSED or RESUMING state
     */
    data class QueueState(
        val isPaused: Boolean = false,
        val activeCount: Int = 0,
        val queuedCount: Int = 0,
        val pausedCount: Int = 0
    )

    /**
     * Queue statistics for metrics endpoint.
     */
    data class QueueStats(
        val total: Int,
        val active: Int,
        val queued: Int,
        val paused: Int,
        val completed: Int,
        val error: Int
    )
}
