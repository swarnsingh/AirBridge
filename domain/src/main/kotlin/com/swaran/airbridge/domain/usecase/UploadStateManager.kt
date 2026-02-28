package com.swaran.airbridge.domain.usecase

import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.model.UploadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe state machine for upload lifecycle management.
 * 
 * All state transitions are validated. Invalid transitions are ignored.
 * Progress updates are throttled to reduce UI churn.
 * Uses System.nanoTime() for monotonic time (works in pure Kotlin without Android dependency).
 */
@Singleton
class UploadStateManager @Inject constructor(
    private val logger: AirLogger
) {
    
    private val states = ConcurrentHashMap<String, AtomicReference<UploadStatus>>()
    
    private val _activeUploads = MutableStateFlow<Map<String, UploadStatus>>(emptyMap())
    val activeUploads: StateFlow<Map<String, UploadStatus>> = _activeUploads.asStateFlow()
    
    private val _globalPause = MutableStateFlow(false)
    val isGlobalPaused: StateFlow<Boolean> = _globalPause.asStateFlow()

    /**
     * Tracks the last time updateFlow() was actually called.
     * Separate from UploadStatus.updatedAt (which tracks every progress callback).
     * This is the actual throttle reference for UI emissions.
     */
    private val lastFlowUpdateMs = AtomicLong(0L)
    
    companion object {
        private const val TAG = "UploadStateManager"
        private const val FLOW_THROTTLE_MS = 250L
    }
    
    /**
     * Monotonic time source (nanoseconds since arbitrary origin).
     * Used instead of System.currentTimeMillis() to avoid NTP drift issues.
     */
    private fun monotonicTimeMs(): Long = System.nanoTime() / 1_000_000L

    /**
     * Initialize a new upload.
     */
    fun initialize(metadata: UploadMetadata): Boolean {
        val initialStatus = UploadStatus(
            metadata = metadata,
            state = UploadState.QUEUED,
            startedAt = monotonicTimeMs()
        )
        
        val ref = AtomicReference(initialStatus)
        val existing = states.putIfAbsent(metadata.uploadId, ref)
        
        if (existing == null) {
            updateFlow()
            return true
        }
        return false // Already exists
    }
    
    /**
     * Attempt state transition.
     * Returns true if transition succeeded.
     */
    fun transition(uploadId: String, newState: UploadState, errorMessage: String? = null): Boolean {
        val ref = states[uploadId] ?: run {
            logger.w(TAG, "transition", "[$uploadId] Failed: no state found")
            return false
        }

        while (true) {
            val current = ref.get()
            if (!current.state.canTransitionTo(newState)) {
                logger.w(TAG, "transition", "[$uploadId] Failed: ${current.state} cannot transition to $newState")
                return false
            }

            val updated = current.copy(
                state = newState,
                updatedAt = monotonicTimeMs(),
                errorMessage = errorMessage
            )

            if (ref.compareAndSet(current, updated)) {
                logger.i(TAG, "transition", "[$uploadId] State changed: ${current.state} -> $newState")
                updateFlow()
                return true
            }
            // Retry if CAS failed
            logger.d(TAG, "transition", "[$uploadId] CAS failed, retrying...")
        }
    }
    
    /**
     * Update progress for an active upload.
     * Throttles updates to avoid UI churn.
     */
    fun updateProgress(uploadId: String, bytesReceived: Long): Boolean {
        val ref = states[uploadId] ?: return false
        
        // Retry loop — CAS can fail under contention from concurrent state transitions
        while (true) {
            val current = ref.get()
            
            // Only allow progress updates while UPLOADING or RESUMING
            if (current.state != UploadState.UPLOADING && current.state != UploadState.RESUMING) {
                logger.w(TAG, "updateProgress", "[$uploadId] Rejected: state=${current.state}, not UPLOADING/RESUMING")
                return false
            }
            
            val now = monotonicTimeMs()
            val elapsedSec = (now - current.startedAt) / 1000f
            val bytesPerSec = if (elapsedSec > 0) bytesReceived / elapsedSec else 0f
            
            val updated = current.copy(
                bytesReceived = bytesReceived,
                bytesPerSecond = bytesPerSec,
                updatedAt = now
            )
            
            if (ref.compareAndSet(current, updated)) {
                // FIX: Use lastFlowUpdateMs (not current.updatedAt) as throttle reference.
                // current.updatedAt is updated on EVERY progress callback (every ~0.3ms at 50MB/s),
                // so now - current.updatedAt is always < 1ms, making shouldUpdateFlow always false.
                val now2 = monotonicTimeMs()
                val last = lastFlowUpdateMs.get()
                val shouldUpdateFlow = (now2 - last > FLOW_THROTTLE_MS) || bytesReceived >= current.metadata.totalBytes
                if (shouldUpdateFlow && lastFlowUpdateMs.compareAndSet(last, now2)) {
                    logger.d(TAG, "updateProgress", "[$uploadId] Progress: $bytesReceived / ${current.metadata.totalBytes} bytes (${(bytesReceived * 100 / current.metadata.totalBytes)}%), speed=${bytesPerSec.toInt()} B/s")
                    updateFlow()
                }
                return true
            }
            // CAS failed — retry (another thread updated state concurrently)
        }
    }
    
    /**
     * Get current status.
     */
    fun getStatus(uploadId: String): UploadStatus? {
        return states[uploadId]?.get()
    }
    
    /**
     * Get all non-terminal uploads.
     */
    fun getActiveUploads(): List<UploadStatus> {
        return states.values.map { it.get() }.filter { !it.isTerminal }
    }
    
    /**
     * Remove a completed/cancelled upload from tracking.
     */
    fun remove(uploadId: String) {
        states.remove(uploadId)
        updateFlow()
    }
    
    /**
     * Set global pause state.
     * All active uploads should respond to this.
     */
    fun setGlobalPause(paused: Boolean) {
        _globalPause.value = paused
        if (paused) {
            // Transition all UPLOADING to PAUSING
            states.values.forEach { ref ->
                val current = ref.get()
                if (current.state == UploadState.UPLOADING) {
                    transition(current.metadata.uploadId, UploadState.PAUSING)
                }
            }
        }
    }
    
    /**
     * Pause a specific upload.
     */
    fun pauseUpload(uploadId: String): Boolean {
        logger.d(TAG, "pauseUpload", "[$uploadId] Button clicked - attempting PAUSING transition")
        val result = transition(uploadId, UploadState.PAUSING)
        logger.i(TAG, "pauseUpload", "[$uploadId] Result: ${if (result) "PAUSING" else "REJECTED"}")
        return result
    }

    /**
     * Resume a specific upload (transitions to RESUMING, caller must then transition to UPLOADING).
     */
    fun resumeUpload(uploadId: String): Boolean {
        logger.d(TAG, "resumeUpload", "[$uploadId] Button clicked - attempting RESUMING transition")
        val result = transition(uploadId, UploadState.RESUMING)
        logger.i(TAG, "resumeUpload", "[$uploadId] Result: ${if (result) "RESUMING" else "REJECTED"}")
        return result
    }

    /**
     * Cancel a specific upload.
     */
    fun cancelUpload(uploadId: String): Boolean {
        logger.d(TAG, "cancelUpload", "[$uploadId] Button clicked - attempting CANCELLED transition")
        val result = transition(uploadId, UploadState.CANCELLED)
        logger.i(TAG, "cancelUpload", "[$uploadId] Result: ${if (result) "CANCELLED" else "REJECTED"}")
        return result
    }
    
    /**
     * Check if we should reject a new upload request.
     * 
     * Allow RESUMING state - browser sends new POST to resume from where it left off.
     * Allow PAUSED state - can be resumed.
     * Reject only if actively UPLOADING (would cause conflicts).
     */
    fun shouldRejectRequest(uploadId: String, fileUri: String): Boolean {
        val existing = states[uploadId]?.get()
        if (existing != null) {
            // Reject only if actively UPLOADING (concurrent writes would conflict)
            // Allow RESUMING/PAUSED - browser is resuming
            val shouldReject = when (existing.state) {
                UploadState.UPLOADING -> true
                UploadState.QUEUED -> true
                else -> false // RESUMING, PAUSED, PAUSING allowed for resume
            }
            if (shouldReject) {
                logger.w(TAG, "shouldRejectRequest", "[$uploadId] Rejecting request: state=${existing.state} is active")
            } else {
                logger.d(TAG, "shouldRejectRequest", "[$uploadId] Allowing request: state=${existing.state} can resume")
            }
            return shouldReject
        }
        return false
    }
    
    /**
     * Get aggregated stats for all uploads.
     */
    fun getAggregatedStats(): UploadStats {
        val all = states.values.map { it.get() }
        val active = all.filter { !it.isTerminal }
        
        return UploadStats(
            totalUploads = all.size,
            activeUploads = active.size,
            completedUploads = all.count { it.state == UploadState.COMPLETED },
            pausedUploads = all.count { it.state in setOf(UploadState.PAUSING, UploadState.PAUSED) },
            totalBytesReceived = active.sumOf { it.bytesReceived },
            totalBytesRemaining = active.sumOf { it.remainingBytes },
            averageSpeedBps = if (active.isNotEmpty()) {
                active.map { it.bytesPerSecond }.average().toFloat()
            } else 0f
        )
    }
    
    private fun updateFlow() {
        _activeUploads.value = states.mapValues { it.value.get() }
    }
    
    /**
     * Clear all state (for testing or recovery).
     */
    fun clear() {
        states.clear()
        _globalPause.value = false
        updateFlow()
    }
}

data class UploadStats(
    val totalUploads: Int,
    val activeUploads: Int,
    val completedUploads: Int,
    val pausedUploads: Int,
    val totalBytesReceived: Long,
    val totalBytesRemaining: Long,
    val averageSpeedBps: Float,
    val estimatedSecondsRemaining: Long = if (averageSpeedBps > 0) {
        (totalBytesRemaining / averageSpeedBps).toLong()
    } else -1
)
