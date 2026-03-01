package com.swaran.airbridge.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.swaran.airbridge.core.data.db.entity.UploadQueueEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for upload queue operations - Protocol v2.
 */
@Dao
interface UploadQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUpload(upload: UploadQueueEntity)

    @Update
    suspend fun updateUpload(upload: UploadQueueEntity)

    @Query("DELETE FROM upload_queue WHERE uploadId = :uploadId")
    suspend fun deleteUpload(uploadId: String)

    @Query("SELECT * FROM upload_queue WHERE uploadId = :uploadId LIMIT 1")
    suspend fun getUpload(uploadId: String): UploadQueueEntity?

    @Query("""
        SELECT * FROM upload_queue 
        WHERE state NOT IN ('COMPLETED', 'CANCELLED', 'ERROR')
        ORDER BY createdAt ASC
    """)
    fun getPendingUploadsFlow(): Flow<List<UploadQueueEntity>>

    @Query("""
        SELECT * FROM upload_queue 
        WHERE state NOT IN ('COMPLETED', 'CANCELLED', 'ERROR')
        ORDER BY createdAt ASC
    """)
    suspend fun getPendingUploads(): List<UploadQueueEntity>

    @Query("SELECT * FROM upload_queue ORDER BY createdAt DESC")
    fun getAllUploadsFlow(): Flow<List<UploadQueueEntity>>

    @Query("SELECT * FROM upload_queue WHERE state = :state ORDER BY createdAt ASC")
    suspend fun getUploadsByState(state: String): List<UploadQueueEntity>

    @Query("""
        UPDATE upload_queue 
        SET state = :newState, updatedAt = :timestamp, errorMessage = :errorMessage 
        WHERE uploadId = :uploadId
    """)
    suspend fun updateState(
        uploadId: String,
        newState: String,
        timestamp: Long = System.currentTimeMillis(),
        errorMessage: String? = null
    )

    @Query("""
        UPDATE upload_queue 
        SET bytesReceived = :bytesReceived, state = :newState, updatedAt = :timestamp 
        WHERE uploadId = :uploadId
    """)
    suspend fun updateProgress(
        uploadId: String,
        bytesReceived: Long,
        newState: String,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM upload_queue WHERE state = 'COMPLETED' AND updatedAt < :cutoffTimestamp")
    suspend fun deleteOldCompletedUploads(cutoffTimestamp: Long)

    @Query("""
        SELECT COUNT(*) FROM upload_queue 
        WHERE state NOT IN ('COMPLETED', 'CANCELLED', 'ERROR')
    """)
    suspend fun getPendingUploadCount(): Int

    @Query("DELETE FROM upload_queue")
    suspend fun clearAll()
}
