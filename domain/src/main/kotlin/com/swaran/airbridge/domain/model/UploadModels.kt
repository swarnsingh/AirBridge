package com.swaran.airbridge.domain.model

/**
 * Upload state machine states - deterministic protocol v2.1 (fail-fast).
 *
 * States: NONE → QUEUED → RESUMING → UPLOADING → PAUSING → PAUSED → COMPLETED/CANCELLED/ERROR
 * Disk size is source of truth. Resume is POST-driven.
 */
enum class UploadState(val value: String) {
    NONE("none"),        // No upload registered
    QUEUED("queued"),    // Waiting for resources
    RESUMING("resuming"),// Resume handshake window (deadline enforced)
    UPLOADING("uploading"),// Actively receiving bytes
    PAUSING("pausing"),  // Cancellation requested, finishing current chunk
    PAUSED("paused"),    // Suspended, can resume from disk size
    COMPLETED("completed"),// All bytes received successfully
    CANCELLED("cancelled"),// User cancelled, partial file deleted
    ERROR("error");      // Permanent error (permission, deleted, etc)

    fun canTransitionTo(target: UploadState): Boolean {
        if (this == target) return true

        return when (this) {
            NONE -> target in setOf(QUEUED, UPLOADING, CANCELLED)
            QUEUED -> target in setOf(RESUMING, UPLOADING, CANCELLED)
            RESUMING -> target in setOf(UPLOADING, PAUSED, CANCELLED)
            UPLOADING -> target in setOf(PAUSING, PAUSED, COMPLETED, CANCELLED, ERROR)
            PAUSING -> target in setOf(PAUSED, CANCELLED, ERROR)
            PAUSED -> target in setOf(RESUMING, CANCELLED)
            COMPLETED, CANCELLED, ERROR -> false  // Terminal states
        }
    }

    val isTerminal: Boolean
        get() = this in setOf(COMPLETED, CANCELLED, ERROR)
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

    /**
     * Server is busy - file locked or concurrency limit reached.
     * Client should retry with exponential backoff.
     */
    data class Busy(
        val uploadId: String,
        val retryAfterMs: Int = 200
    ) : UploadResult()

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

/**
 * Exception thrown when file is deleted externally during upload.
 * This allows the scheduler to handle it as a retryable error with specific messaging.
 */
class FileDeletedExternallyException(message: String, cause: Throwable? = null) : Exception(message, cause)
