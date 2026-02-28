package com.swaran.airbridge.domain.model

/**
 * Upload state machine states.
 * 
 * State transitions are enforced - invalid transitions return false.
 */
enum class UploadState {
    QUEUED,      // Waiting to start
    UPLOADING,   // Actively receiving bytes
    PAUSING,     // Pause requested, finishing current chunk
    PAUSED,      // Suspended, can resume
    RESUMING,    // Resume requested, validating offset
    COMPLETED,   // All bytes received successfully
    CANCELLED,   // User cancelled
    ERROR_RETRYABLE,   // Failed but can retry (network, storage transient)
    ERROR_PERMANENT;  // Failed permanently (SAF permission, file deleted)
    
    fun canTransitionTo(target: UploadState): Boolean {
        return when (this) {
            QUEUED -> target == UPLOADING || target == CANCELLED
            UPLOADING -> target in setOf(PAUSING, COMPLETED, CANCELLED, ERROR_RETRYABLE, ERROR_PERMANENT)
            PAUSING -> target in setOf(PAUSED, CANCELLED, ERROR_RETRYABLE)
            PAUSED -> target in setOf(RESUMING, CANCELLED)
            RESUMING -> target in setOf(UPLOADING, ERROR_RETRYABLE, CANCELLED)
            COMPLETED, CANCELLED, ERROR_PERMANENT -> false // Terminal states
            ERROR_RETRYABLE -> target in setOf(QUEUED, CANCELLED) // Can retry from queued
        }
    }
}

/**
 * Immutable upload metadata.
 * URI is the source of truth - filename is display only.
 */
data class UploadMetadata(
    val uploadId: String,
    val fileUri: String,        // SAF URI - source of truth
    val displayName: String,    // Human-readable filename
    val path: String,           // Virtual path in AirBridge
    val totalBytes: Long,
    val mimeType: String? = null
)

/**
 * Current state of an upload including progress.
 * All timestamps use elapsedRealtime for monotonic guarantees.
 */
data class UploadStatus(
    val metadata: UploadMetadata,
    val state: UploadState,
    val bytesReceived: Long = 0,
    val bytesPerSecond: Float = 0f,
    val startedAt: Long = 0,    // SystemClock.elapsedRealtime()
    val updatedAt: Long = 0,    // SystemClock.elapsedRealtime()
    val errorMessage: String? = null,
    val retryCount: Int = 0
) {
    val isTerminal: Boolean
        get() = state in setOf(UploadState.COMPLETED, UploadState.CANCELLED, UploadState.ERROR_PERMANENT)
    
    val remainingBytes: Long
        get() = (metadata.totalBytes - bytesReceived).coerceAtLeast(0)
    
    val estimatedSecondsRemaining: Long
        get() = if (bytesPerSecond > 0) (remainingBytes / bytesPerSecond).toLong() else -1
    
    val progressPercent: Int
        get() = if (metadata.totalBytes > 0) {
            ((bytesReceived * 100) / metadata.totalBytes).toInt()
        } else 0
}

/**
 * Upload request from browser/client.
 */
data class UploadRequest(
    val uploadId: String,
    val fileUri: String,
    val fileName: String,
    val path: String,
    val offset: Long,           // Resume position (must match disk size)
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
     * Indicates upload is temporarily busy (file locked by previous upload finishing).
     * Browser should retry in 300ms.
     */
    data class Busy(val uploadId: String) : UploadResult()
    
    sealed class Failure : UploadResult() {
        abstract val uploadId: String
        abstract val bytesReceived: Long
        
        data class OffsetMismatch(
            override val uploadId: String,
            override val bytesReceived: Long,
            val expectedOffset: Long,
            val actualDiskSize: Long
        ) : Failure()
        
        data class PermissionRevoked(
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
        
        data class NetworkError(
            override val uploadId: String,
            override val bytesReceived: Long,
            val cause: Throwable
        ) : Failure()
        
        data class UnknownError(
            override val uploadId: String,
            override val bytesReceived: Long,
            val cause: Throwable
        ) : Failure()
        
        val isRetryable: Boolean
            get() = this is NetworkError || this is StorageFull
    }
}

/**
 * Exception types for upload failures.
 */
class OffsetMismatchException(val expectedOffset: Long, val actualOffset: Long) : Exception(
    "Offset mismatch: expected $expectedOffset, disk has $actualOffset"
)

class PermissionRevokedException : Exception("SAF permission revoked during upload")

class UploadPausedException : Exception("Upload paused by user")

class UploadCancelledException : Exception("Upload cancelled by user")

class UploadTimeoutException : Exception("Upload stalled - no progress detected")
