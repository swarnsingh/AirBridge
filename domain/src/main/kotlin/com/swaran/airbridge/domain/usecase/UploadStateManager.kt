package com.swaran.airbridge.domain.usecase

import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.model.UploadStatus
import com.swaran.airbridge.domain.repository.UploadStatePersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic upload state manager - Protocol v2.1.
 *
 * Manages upload state transitions with atomic compare-and-set operations.
 * All transitions are validated against the UploadState.canTransitionTo() rules.
 * Persists state to survive app restarts.
 *
 * ## Supported States
 *
 * - **NONE**: Initial state, upload registered but not started
 * - **QUEUED**: Waiting for browser POST (transition from NONE)
 * - **RESUMING**: Resume handshake in progress, browser must POST within deadline
 * - **UPLOADING**: Actively receiving bytes from browser
 * - **PAUSING**: Pause requested, finishing current chunk (transitional)
 * - **PAUSED**: Suspended, can resume from disk offset
 * - **COMPLETED**: All bytes received successfully
 * - **CANCELLED**: User cancelled, partial file deleted
 * - **ERROR**: Permanent error (permission denied, file deleted, etc.)
 *
 * ## Thread Safety
 *
 * All operations use ConcurrentHashMap and AtomicReference for thread-safe
 * state updates without blocking. StateFlow is throttled to prevent
 * excessive UI updates. State persistence is async and non-blocking.
 *
 * @see UploadState For state machine transition rules
 */
@Singleton
class UploadStateManager @Inject constructor(
    private val logger: AirLogger,
    private val persistence: UploadStatePersistence
) {
    private val states = ConcurrentHashMap<String, AtomicReference<UploadStatus>>()
    private val _activeUploads = MutableStateFlow<Map<String, UploadStatus>>(emptyMap())
    val activeUploads: StateFlow<Map<String, UploadStatus>> = _activeUploads.asStateFlow()

    // Per-upload flow update throttling - prevents shared bottleneck
    private val lastFlowUpdateMs = ConcurrentHashMap<String, Long>()
    
    // Coroutine scope for persistence operations
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "UploadStateManager"
        private const val FLOW_THROTTLE_MS = 250L
    }

    private fun monotonicTimeMs(): Long = System.nanoTime() / 1_000_000L
    
    /**
     * Recover uploads from persistence on app startup.
     * Should be called once during initialization.
     */
    suspend fun recoverPersistedUploads() {
        val persisted = persistence.loadAll()
        logger.d(TAG, "recover", "Recovering ${persisted.size} persisted uploads")
        
        persisted.forEach { p ->
            if (p.canRecover()) {
                val recoveryState = p.recoveryState()
                val metadata = UploadMetadata(
                    uploadId = p.uploadId,
                    fileUri = p.fileUri,
                    displayName = p.displayName,
                    path = p.path,
                    totalBytes = p.totalBytes
                )
                
                val ref = AtomicReference(
                    UploadStatus(
                        metadata = metadata,
                        state = recoveryState,
                        bytesReceived = p.bytesReceived,
                        startedAt = p.startedAtNanos,
                        updatedAt = monotonicTimeMs()
                    )
                )
                states[p.uploadId] = ref
                logger.d(TAG, "recover", "[${p.uploadId}] Recovered to state: $recoveryState")
            } else {
                // Clean up terminal states
                persistence.remove(p.uploadId)
            }
        }
        updateFlow()
    }

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
            // Persist initial state
            persistenceScope.launch {
                persistence.persist(ref.get())
            }
            return true
        }
        return false
    }

    /**
     * State transition. Validated against state machine rules.
     * Resets startedAt when transitioning to UPLOADING to fix speed calculation on resume.
     */
    fun transition(uploadId: String, newState: UploadState, errorMessage: String? = null): Boolean {
        val ref = states[uploadId] ?: return false

        while (true) {
            val current = ref.get()
            if (!current.state.canTransitionTo(newState)) {
                logger.w(TAG, "transition", "[$uploadId] Invalid: ${current.state} -> $newState")
                return false
            }

            val now = monotonicTimeMs()
            val updated = current.copy(
                state = newState,
                updatedAt = now,
                startedAt = if (newState == UploadState.UPLOADING) now else current.startedAt,
                errorMessage = errorMessage
            )

            if (ref.compareAndSet(current, updated)) {
                logger.d(TAG, "transition", "[$uploadId] ${current.state} -> $newState")
                updateFlow()
                // Persist state change asynchronously
                persistenceScope.launch {
                    persistence.persist(updated)
                    // Clean up terminal states from persistence
                    if (newState in setOf(UploadState.COMPLETED, UploadState.CANCELLED, UploadState.ERROR)) {
                        persistence.remove(uploadId)
                    }
                }
                return true
            }
        }
    }

    /**
     * Update progress. Only allowed during UPLOADING.
     * Uses per-upload throttling to prevent shared bottleneck.
     * Progress updates are NOT persisted (only state transitions).
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
                // Per-upload throttling - prevents shared bottleneck across files
                val last = lastFlowUpdateMs[uploadId] ?: 0L
                val shouldEmit = (now - last > FLOW_THROTTLE_MS) || bytesReceived >= current.metadata.totalBytes
                if (shouldEmit) {
                    lastFlowUpdateMs[uploadId] = now
                    updateFlow()
                }
                return true
            }
        }
    }

    fun getStatus(uploadId: String): UploadStatus? = states[uploadId]?.get()

    fun remove(uploadId: String) {
        states.remove(uploadId)
        lastFlowUpdateMs.remove(uploadId)
        updateFlow()
        persistenceScope.launch {
            persistence.remove(uploadId)
        }
    }

    private fun updateFlow() {
        _activeUploads.value = states.mapValues { it.value.get() }
    }

    fun clear() {
        states.clear()
        lastFlowUpdateMs.clear()
        updateFlow()
        persistenceScope.launch {
            persistence.clear()
        }
    }
}
