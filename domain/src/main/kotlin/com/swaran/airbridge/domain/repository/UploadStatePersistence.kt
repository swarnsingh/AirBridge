package com.swaran.airbridge.domain.repository

import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.model.UploadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Persistence layer for upload state.
 *
 * Responsibilities:
 * - Persist upload states to survive app kills / reboots
 * - Atomic writes (write temp file, then rename)
 * - Automatic cleanup of terminal states after TTL
 * - Recovery of interrupted uploads on app restart
 */
interface UploadStatePersistence {

    /**
     * Persist a single upload state.
     * Should only be called on state transitions, not progress updates.
     */
    suspend fun persist(status: UploadStatus)

    /**
     * Remove a persisted upload state (after completion/cancellation).
     */
    suspend fun remove(uploadId: String)

    /**
     * Load all non-terminal upload states.
     * Called on app startup to recover interrupted uploads.
     */
    suspend fun loadAll(): List<PersistedUpload>

    /**
     * Clear all persisted state (for testing or reset).
     */
    suspend fun clear()

    /**
     * Observe persisted state changes.
     */
    val persistedStates: Flow<Map<String, PersistedUpload>>
}

/**
 * Serializable representation of persisted upload state.
 * Uses primitives for stable JSON serialization.
 */
@Serializable
data class PersistedUpload(
    val uploadId: String,
    val fileUri: String,
    val displayName: String,
    val path: String,
    val totalBytes: Long,
    val bytesReceived: Long,
    val state: String,  // UploadState.name
    val bytesPerSecond: Float,
    val startedAtNanos: Long,
    val updatedAtNanos: Long,
    val errorMessage: String?,
    val retryCount: Int
) {
    /**
     * Check if this upload can be resumed after recovery.
     */
    fun canRecover(): Boolean {
        val recoverableStates = setOf(
            UploadState.UPLOADING.name,
            UploadState.PAUSING.name,
            UploadState.PAUSED.name,
            UploadState.RESUMING.name,
            UploadState.ERROR_RETRYABLE.name
        )
        // Allow zero-byte files (>= 0 instead of > 0)
        return state in recoverableStates && bytesReceived >= 0 && bytesReceived < totalBytes
    }

    /**
     * Determine the correct recovery state after app restart.
     */
    fun recoveryState(): UploadState {
        return when (state) {
            UploadState.UPLOADING.name, UploadState.RESUMING.name -> UploadState.ERROR_RETRYABLE
            UploadState.PAUSING.name -> UploadState.PAUSED
            UploadState.PAUSED.name -> UploadState.PAUSED
            UploadState.ERROR_RETRYABLE.name -> UploadState.ERROR_RETRYABLE
            else -> UploadState.ERROR_PERMANENT
        }
    }
}
