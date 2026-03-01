package com.swaran.airbridge.domain.repository

import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.model.UploadStatus
import kotlinx.coroutines.flow.Flow

/**
 * Interface for persistent upload queue storage.
 *
 * Abstracts the database implementation from the upload scheduler.
 * Implementations (e.g., Room) handle actual persistence.
 *
 * This interface lives in the domain layer to avoid circular dependencies
 * between network (scheduler) and data (database) modules.
 */
interface UploadQueuePersistence {
    /**
     * Flow of pending uploads that updates automatically.
     */
    val pendingUploads: Flow<List<UploadStatus>>

    /**
     * Add a new upload to the persistent queue.
     */
    suspend fun enqueue(metadata: UploadMetadata, state: UploadState = UploadState.NONE)

    /**
     * Update upload progress and state.
     */
    suspend fun updateProgress(uploadId: String, bytesReceived: Long, state: UploadState)

    /**
     * Mark upload as completed.
     */
    suspend fun complete(uploadId: String)

    /**
     * Mark upload as cancelled and remove from queue.
     */
    suspend fun cancel(uploadId: String)

    /**
     * Mark upload with error state.
     */
    suspend fun markError(uploadId: String, state: UploadState, errorMessage: String? = null)

    /**
     * Get all uploads that can be resumed (called on app startup).
     */
    suspend fun getResumable(): List<UploadStatus>

    /**
     * Get single upload by ID.
     */
    suspend fun get(uploadId: String): UploadStatus?
}