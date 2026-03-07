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
 */
@Singleton
class UploadQueueManager @Inject constructor(
    val scheduler: UploadScheduler,
    private val stateManager: UploadStateManager,
    private val logger: AirLogger
) {
    companion object {
        private const val TAG = "UploadQueueManager"
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
     */
    suspend fun enqueue(
        request: UploadRequest,
        receiveStream: suspend () -> java.io.InputStream
    ): UploadResult {
        logger.d(TAG, "enqueue", "[${request.uploadId}] Enqueuing ${request.fileName} (offset=${request.offset})")

        if (isGlobalPaused.get()) {
            logger.d(TAG, "enqueue", "[${request.uploadId}] Global pause active, returning Busy")
            return UploadResult.Busy(request.uploadId, retryAfterMs = 500)
        }

        updateQueueState()
        return startUpload(request, receiveStream)
    }

    private suspend fun startUpload(
        request: UploadRequest,
        receiveStream: suspend () -> java.io.InputStream
    ): UploadResult {
        val uploadId = request.uploadId
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
     */
    fun pause(uploadId: String): Boolean {
        logger.d(TAG, "pause", "[$uploadId] Requested")
        val success = scheduler.pauseUpload(uploadId)
        activeJobs[uploadId]?.cancel()
        return success
    }

    /**
     * Resume a specific upload.
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
     */
    fun pauseAll() {
        logger.i(TAG, "pauseAll", "Pausing all uploads")
        isGlobalPaused.set(true)

        activeJobs.forEach { (uploadId, job) ->
            scheduler.pauseUpload(uploadId)
            job.cancel()
        }

        updateQueueState()
    }

    /**
     * Resume all uploads.
     * 
     * UC-07: Fix deadline expiration by NOT marking all as RESUMING immediately.
     * We just clear global pause; the browser will iterate and POST each item,
     * which handles state transition safely without mass-triggering 30s deadlines.
     */
    fun resumeAll() {
        logger.i(TAG, "resumeAll", "Clearing global pause")
        isGlobalPaused.set(false)
        updateQueueState()
    }

    /**
     * Cancel a specific upload.
     */
    suspend fun cancel(uploadId: String, request: UploadRequest) {
        logger.d(TAG, "cancel", "[$uploadId] Cancelling")
        activeJobs[uploadId]?.cancel()
        activeJobs.remove(uploadId)
        scheduler.cancel(uploadId, request)
        updateQueueState()
    }

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

    private fun updateQueueState() {
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

    data class QueueState(
        val isPaused: Boolean = false,
        val activeCount: Int = 0,
        val queuedCount: Int = 0,
        val pausedCount: Int = 0
    )

    data class QueueStats(
        val total: Int,
        val active: Int,
        val queued: Int,
        val paused: Int,
        val completed: Int,
        val error: Int
    )
}
