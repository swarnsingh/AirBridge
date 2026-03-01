package com.swaran.airbridge.domain.usecase

import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.model.UploadStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic upload state manager - Protocol v2.
 *
 * States: NONE, UPLOADING, PAUSED, COMPLETED, CANCELLED, ERROR
 * No transitional states (QUEUED, PAUSING, RESUMING, ERROR_RETRYABLE).
 */
@Singleton
class UploadStateManager @Inject constructor(
    private val logger: AirLogger
) {
    private val states = ConcurrentHashMap<String, AtomicReference<UploadStatus>>()
    private val _activeUploads = MutableStateFlow<Map<String, UploadStatus>>(emptyMap())
    val activeUploads: StateFlow<Map<String, UploadStatus>> = _activeUploads.asStateFlow()

    private val lastFlowUpdateMs = AtomicLong(0L)

    companion object {
        private const val TAG = "UploadStateManager"
        private const val FLOW_THROTTLE_MS = 250L
    }

    private fun monotonicTimeMs(): Long = System.nanoTime() / 1_000_000L

    /**
     * Initialize upload. Idempotent - no-op if exists.
     */
    fun initialize(metadata: UploadMetadata): Boolean {
        val ref = AtomicReference(
            UploadStatus(
                metadata = metadata,
                state = UploadState.NONE,
                startedAt = monotonicTimeMs()
            )
        )
        val existing = states.putIfAbsent(metadata.uploadId, ref)
        if (existing == null) {
            updateFlow()
            return true
        }
        return false
    }

    /**
     * State transition. Validated against state machine rules.
     */
    fun transition(uploadId: String, newState: UploadState, errorMessage: String? = null): Boolean {
        val ref = states[uploadId] ?: return false

        while (true) {
            val current = ref.get()
            if (!current.state.canTransitionTo(newState)) {
                logger.w(TAG, "transition", "[$uploadId] Invalid: ${current.state} -> $newState")
                return false
            }

            val updated = current.copy(
                state = newState,
                updatedAt = monotonicTimeMs(),
                errorMessage = errorMessage
            )

            if (ref.compareAndSet(current, updated)) {
                logger.d(TAG, "transition", "[$uploadId] ${current.state} -> $newState")
                updateFlow()
                return true
            }
        }
    }

    /**
     * Update progress. Only allowed during UPLOADING.
     */
    fun updateProgress(uploadId: String, bytesReceived: Long): Boolean {
        val ref = states[uploadId] ?: return false

        while (true) {
            val current = ref.get()
            if (current.state != UploadState.UPLOADING) return false

            val now = monotonicTimeMs()
            val elapsed = (now - current.startedAt) / 1000f
            val speed = if (elapsed > 0) bytesReceived / elapsed else 0f

            val updated = current.copy(
                bytesReceived = bytesReceived,
                bytesPerSecond = speed,
                updatedAt = now
            )

            if (ref.compareAndSet(current, updated)) {
                // Throttle flow updates
                val last = lastFlowUpdateMs.get()
                val shouldEmit = (now - last > FLOW_THROTTLE_MS) || bytesReceived >= current.metadata.totalBytes
                if (shouldEmit && lastFlowUpdateMs.compareAndSet(last, now)) {
                    updateFlow()
                }
                return true
            }
        }
    }

    fun getStatus(uploadId: String): UploadStatus? = states[uploadId]?.get()

    fun remove(uploadId: String) {
        states.remove(uploadId)
        updateFlow()
    }

    private fun updateFlow() {
        _activeUploads.value = states.mapValues { it.value.get() }
    }

    fun clear() {
        states.clear()
        updateFlow()
    }
}
