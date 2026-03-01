package com.swaran.airbridge.core.data.repository

import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.core.data.db.dao.UploadQueueDao
import com.swaran.airbridge.core.data.db.entity.UploadQueueEntity
import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.model.UploadStatus
import com.swaran.airbridge.domain.repository.UploadQueuePersistence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for persistent upload queue operations.
 *
 * Implements UploadQueuePersistence interface using Room database.
 */
@Singleton
class UploadQueueRepository @Inject constructor(
    private val uploadQueueDao: UploadQueueDao,
    private val logger: AirLogger
) : UploadQueuePersistence {

    companion object {
        private const val TAG = "UploadQueueRepository"
    }

    override val pendingUploads: Flow<List<UploadStatus>> =
        uploadQueueDao.getPendingUploadsFlow().map { entities ->
            entities.map { it.toDomainStatus() }
        }

    override suspend fun enqueue(metadata: UploadMetadata, state: UploadState) {
        val entity = UploadQueueEntity(
            uploadId = metadata.uploadId,
            fileUri = metadata.fileUri,
            fileName = metadata.displayName,
            path = metadata.path,
            totalBytes = metadata.totalBytes,
            bytesReceived = 0,
            state = state.name,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        uploadQueueDao.insertUpload(entity)
        logger.d(TAG, "enqueue", "[${metadata.uploadId}] Enqueued: ${metadata.displayName}")
    }

    override suspend fun updateProgress(uploadId: String, bytesReceived: Long, state: UploadState) {
        uploadQueueDao.updateProgress(uploadId, bytesReceived, state.name)
        logger.v(TAG, "updateProgress", "[$uploadId] $bytesReceived bytes, state=$state")
    }

    override suspend fun complete(uploadId: String) {
        uploadQueueDao.updateState(uploadId, UploadState.COMPLETED.name)
        logger.d(TAG, "complete", "[$uploadId] Completed")
    }

    override suspend fun cancel(uploadId: String) {
        uploadQueueDao.updateState(uploadId, UploadState.CANCELLED.name)
        uploadQueueDao.deleteUpload(uploadId)
        logger.d(TAG, "cancel", "[$uploadId] Cancelled and removed")
    }

    override suspend fun markError(uploadId: String, state: UploadState, errorMessage: String?) {
        require(state == UploadState.ERROR) {
            "Error state must be ERROR"
        }
        uploadQueueDao.updateState(uploadId, state.name, errorMessage = errorMessage)
        logger.w(TAG, "markError", "[$uploadId] $state: $errorMessage")
    }

    override suspend fun getResumable(): List<UploadStatus> {
        val entities = uploadQueueDao.getPendingUploads()
        return entities.filter { it.canResume() }.map { it.toDomainStatus() }
    }

    override suspend fun get(uploadId: String): UploadStatus? {
        return uploadQueueDao.getUpload(uploadId)?.toDomainStatus()
    }

    suspend fun remove(uploadId: String) {
        uploadQueueDao.deleteUpload(uploadId)
        logger.d(TAG, "remove", "[$uploadId] Removed")
    }

    suspend fun cleanupOld(olderThanMillis: Long) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        uploadQueueDao.deleteOldCompletedUploads(cutoff)
        logger.d(TAG, "cleanupOld", "Cleaned up old uploads")
    }

    private fun UploadQueueEntity.toDomainStatus(): UploadStatus {
        return UploadStatus(
            metadata = UploadMetadata(
                uploadId = uploadId,
                fileUri = fileUri,
                displayName = fileName,
                path = path,
                totalBytes = totalBytes
            ),
            state = UploadState.valueOf(state),
            bytesReceived = bytesReceived,
            bytesPerSecond = 0f,
            startedAt = createdAt,
            updatedAt = updatedAt,
            errorMessage = errorMessage
        )
    }
}