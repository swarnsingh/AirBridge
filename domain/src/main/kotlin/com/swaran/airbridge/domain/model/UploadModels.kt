package com.swaran.airbridge.domain.model

/**
 * Upload state machine states - deterministic protocol v2.
 *
 * Only 6 states. No transitional states (RESUMING, PAUSING, ERROR_RETRYABLE).
 * Disk size is source of truth. Resume is implicit.
 */
enum class UploadState {
    NONE,        // No upload registered
    UPLOADING,   // Actively receiving bytes
    PAUSED,      // Suspended, can resume
    COMPLETED,   // All bytes received successfully
    CANCELLED,   // User cancelled, partial deleted
    ERROR;       // Permanent error (permission, deleted, etc)

    fun canTransitionTo(target: UploadState): Boolean {
        if (this == target) return true

        return when (this) {
            NONE -> target in setOf(UPLOADING, CANCELLED)
            UPLOADING -> target in setOf(PAUSED, COMPLETED, CANCELLED, ERROR)
            PAUSED -> target in setOf(UPLOADING, CANCELLED)  // Browser POSTs -> UPLOADING
            COMPLETED, CANCELLED, ERROR -> false  // Terminal states
        }
    }
}

/**
 * Immutable upload metadata.
 */
data class UploadMetadata(
    val uploadId: String,
    val fileUri: String,
    val displayName: String,
    val path: String,
    val totalBytes: Long,
    val mimeType: String? = null
)

/**
 * Current state of an upload including progress.
 */
data class UploadStatus(
    val metadata: UploadMetadata,
    val state: UploadState,
    val bytesReceived: Long = 0,
    val bytesPerSecond: Float = 0f,
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
    val errorMessage: String? = null
) {
    val isTerminal: Boolean
        get() = state in setOf(UploadState.COMPLETED, UploadState.CANCELLED, UploadState.ERROR)

    val remainingBytes: Long
        get() = (metadata.totalBytes - bytesReceived).coerceAtLeast(0)

    val progressPercent: Int
        get() = if (metadata.totalBytes > 0) {
            ((bytesReceived * 100) / metadata.totalBytes).toInt()
        } else 0

    val estimatedSecondsRemaining: Long
        get() = if (bytesPerSecond > 0) (remainingBytes / bytesPerSecond).toLong() else -1
}

/**
 * Upload request from browser/client.
 */
data class UploadRequest(
    val uploadId: String,
    val fileUri: String,
    val fileName: String,
    val path: String,
    val offset: Long,  // MUST equal disk size (idempotent)
    val totalBytes: Long,
    val contentRange: String? = null
)

/**
 * Result of an upload operation.
 */
sealed class UploadResult {
    data class Success(
        val uploadId: String,
        val bytesReceived: Long,
        val fileId: String
    ) : UploadResult()

    data class Paused(
        val uploadId: String,
        val bytesReceived: Long
    ) : UploadResult()

    data class Cancelled(val uploadId: String) : UploadResult()

    sealed class Failure : UploadResult() {
        abstract val uploadId: String
        abstract val bytesReceived: Long

        data class OffsetMismatch(
            override val uploadId: String,
            override val bytesReceived: Long,
            val expectedOffset: Long,
            val actualDiskSize: Long
        ) : Failure()

        data class PermissionError(
            override val uploadId: String,
            override val bytesReceived: Long
        ) : Failure()

        data class StorageFull(
            override val uploadId: String,
            override val bytesReceived: Long
        ) : Failure()

        data class FileDeleted(
            override val uploadId: String,
            override val bytesReceived: Long
        ) : Failure()

        data class UnknownError(
            override val uploadId: String,
            override val bytesReceived: Long,
            val cause: Throwable
        ) : Failure()
    }
}

/**
 * Exception types for upload flow control.
 */
class UploadPausedException : Exception("Upload paused by user")
class UploadCancelledException : Exception("Upload cancelled by user")
