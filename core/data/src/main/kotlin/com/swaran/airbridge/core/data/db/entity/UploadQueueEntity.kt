package com.swaran.airbridge.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.swaran.airbridge.domain.model.UploadState

/**
 * Room entity for persistent upload queue - Protocol v2.
 *
 * Stores upload metadata and state to survive app termination.
 */
@Entity(
    tableName = "upload_queue",
    indices = [
        Index(value = ["state"]),
        Index(value = ["uploadId"], unique = true)
    ]
)
data class UploadQueueEntity(
    @PrimaryKey
    val uploadId: String,
    val fileUri: String,
    val fileName: String,
    val path: String,
    val totalBytes: Long,
    val bytesReceived: Long = 0,
    val state: String = UploadState.NONE.name,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
) {
    /**
     * Check if this upload is in a terminal state (cannot be resumed).
     */
    fun isTerminal(): Boolean = state in setOf(
        UploadState.COMPLETED.name,
        UploadState.CANCELLED.name,
        UploadState.ERROR.name
    )

    /**
     * Check if this upload can be resumed.
     */
    fun canResume(): Boolean = state in setOf(
        UploadState.NONE.name,
        UploadState.UPLOADING.name,
        UploadState.PAUSED.name
    )

    /**
     * Calculate progress percentage.
     */
    fun progressPercent(): Int = if (totalBytes > 0) {
        ((bytesReceived * 100) / totalBytes).toInt()
    } else 0
}
