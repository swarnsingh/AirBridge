package com.swaran.airbridge.core.network.ktor.plugins

import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.UploadResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Upload metrics for observability and debugging.
 *
 * Tracks aggregate statistics across all uploads:
 * - Total upload count (success/failure)
 * - Average upload duration
 * - Bytes transferred
 * - Resume/pause/cancel rates
 * - Error classifications
 *
 * These metrics help identify:
 * - Network quality issues (high retry rate)
 * - User behavior patterns (pause/resume frequency)
 * - Storage problems (permission/storage full errors)
 * - Performance trends (average duration changes)
 *
 * @property totalUploads Total completed uploads (success or failure)
 * @property successfulUploads Number of successfully completed uploads
 * @property failedUploads Number of permanently failed uploads
 * @property cancelledUploads Number of user-cancelled uploads
 * @property resumedUploads Number of uploads that required resume
 * @property totalBytesTransferred Total bytes successfully written
 * @property averageDurationMs Average upload duration in milliseconds
 * @property errorBreakdown Map of error type -> count
 */
data class UploadMetrics(
    val totalUploads: Int = 0,
    val successfulUploads: Int = 0,
    val failedUploads: Int = 0,
    val cancelledUploads: Int = 0,
    val pausedUploads: Int = 0,
    val resumedUploads: Int = 0,
    val totalBytesTransferred: Long = 0L,
    val averageDurationMs: Long = 0L,
    val errorBreakdown: Map<String, Int> = emptyMap()
) {
    val successRate: Float
        get() = if (totalUploads > 0) successfulUploads.toFloat() / totalUploads else 0f

    val failureRate: Float
        get() = if (totalUploads > 0) failedUploads.toFloat() / totalUploads else 0f

    val resumeRate: Float
        get() = if (totalUploads > 0) resumedUploads.toFloat() / totalUploads else 0f
}

/**
 * Individual upload session metrics (not exposed publicly, used for calculations).
 */
private data class UploadSession(
    val uploadId: String,
    val startTime: Long = System.currentTimeMillis(),
    var endTime: Long? = null,
    var bytesTransferred: Long = 0L,
    var didResume: Boolean = false,
    var wasPaused: Boolean = false,
    var errorType: String? = null
)

/**
 * Central metrics collector for upload observability.
 *
 * Thread-safe singleton that tracks upload lifecycle events and
 * provides aggregated metrics for monitoring and debugging.
 *
 * Usage:
 * ```kotlin
 * // In UploadScheduler or routes
 * metricsCollector.startUpload(uploadId)
 * metricsCollector.recordProgress(uploadId, bytes)
 * metricsCollector.completeUpload(uploadId, result)
 *
 * // In UI or monitoring
 * val metrics = metricsCollector.metrics.value
 * println("Success rate: ${metrics.successRate * 100}%")
 * ```
 */
@Singleton
class UploadMetricsCollector @Inject constructor(
    private val logger: AirLogger
) {
    companion object {
        private const val TAG = "UploadMetrics"
    }

    private val activeSessions = ConcurrentHashMap<String, UploadSession>()

    // Atomic counters for thread-safe updates
    private val totalUploads = AtomicInteger(0)
    private val successfulUploads = AtomicInteger(0)
    private val failedUploads = AtomicInteger(0)
    private val cancelledUploads = AtomicInteger(0)
    private val pausedUploads = AtomicInteger(0)
    private val resumedUploads = AtomicInteger(0)
    private val totalBytes = AtomicLong(0L)
    private val totalDurationMs = AtomicLong(0L)
    private val errorCounts = ConcurrentHashMap<String, AtomicInteger>()

    private val _metrics = MutableStateFlow(UploadMetrics())
    val metrics: StateFlow<UploadMetrics> = _metrics.asStateFlow()

    /**
     * Start tracking a new upload session.
     */
    fun startUpload(uploadId: String, didResume: Boolean = false) {
        activeSessions[uploadId] = UploadSession(
            uploadId = uploadId,
            didResume = didResume
        )
        if (didResume) {
            resumedUploads.incrementAndGet()
        }
        logger.d(TAG, "startUpload", "[$uploadId] Metrics tracking started (resume=$didResume)")
    }

    /**
     * Record progress update for an upload.
     */
    fun recordProgress(uploadId: String, bytesTransferred: Long) {
        activeSessions[uploadId]?.let { session ->
            session.bytesTransferred = bytesTransferred
        }
    }

    /**
     * Mark an upload as paused.
     */
    fun pauseUpload(uploadId: String) {
        activeSessions[uploadId]?.let { session ->
            session.wasPaused = true
        }
        pausedUploads.incrementAndGet()
        logger.d(TAG, "pauseUpload", "[$uploadId] Upload paused")
    }

    /**
     * Complete tracking for an upload with the final result.
     */
    fun completeUpload(uploadId: String, result: UploadResult) {
        val session = activeSessions.remove(uploadId) ?: return
        session.endTime = System.currentTimeMillis()

        val duration = session.endTime!! - session.startTime
        totalDurationMs.addAndGet(duration)

        when (result) {
            is UploadResult.Success -> {
                successfulUploads.incrementAndGet()
                totalBytes.addAndGet(result.bytesReceived)
                logger.i(TAG, "completeUpload", "[$uploadId] SUCCESS: ${result.bytesReceived} bytes in ${duration}ms")
            }
            is UploadResult.Cancelled -> {
                cancelledUploads.incrementAndGet()
                logger.i(TAG, "completeUpload", "[$uploadId] CANCELLED after ${duration}ms")
            }
            is UploadResult.Paused -> {
                // Don't count as complete - will resume later
                logger.d(TAG, "completeUpload", "[$uploadId] PAUSED at ${result.bytesReceived} bytes")
            }
            is UploadResult.Failure -> {
                failedUploads.incrementAndGet()
                val errorType = classifyError(result)
                errorCounts.computeIfAbsent(errorType) { AtomicInteger(0) }.incrementAndGet()
                logger.w(TAG, "completeUpload", "[$uploadId] FAILURE ($errorType): ${result::class.simpleName}")
            }
            else -> {
                // Other result types (should not happen)
                logger.d(TAG, "completeUpload", "[$uploadId] ${result::class.simpleName}")
            }
        }

        totalUploads.incrementAndGet()
        updateMetricsSnapshot()
    }

    /**
     * Get current metrics snapshot (for immediate read without flow subscription).
     */
    fun getCurrentMetrics(): UploadMetrics = _metrics.value

    /**
     * Reset all metrics (useful for testing or manual reset).
     */
    fun reset() {
        activeSessions.clear()
        totalUploads.set(0)
        successfulUploads.set(0)
        failedUploads.set(0)
        cancelledUploads.set(0)
        pausedUploads.set(0)
        resumedUploads.set(0)
        totalBytes.set(0L)
        totalDurationMs.set(0L)
        errorCounts.clear()
        updateMetricsSnapshot()
        logger.i(TAG, "reset", "Metrics reset")
    }

    /**
     * Format metrics for logging/reporting.
     */
    fun formatMetrics(): String {
        val m = _metrics.value
        return buildString {
            appendLine("=== Upload Metrics ===")
            appendLine("Total: ${m.totalUploads} | Success: ${m.successfulUploads} (${"%.1f".format(m.successRate * 100)}%)")
            appendLine("Failed: ${m.failedUploads} | Cancelled: ${m.cancelledUploads} | Paused: ${m.pausedUploads}")
            appendLine("Resumed: ${m.resumedUploads} (${"%.1f".format(m.resumeRate * 100)}% resume rate)")
            appendLine("Bytes: ${m.totalBytesTransferred / (1024 * 1024)} MB transferred")
            appendLine("Avg Duration: ${m.averageDurationMs}ms")
            if (m.errorBreakdown.isNotEmpty()) {
                appendLine("Errors: ${m.errorBreakdown.entries.joinToString { "${it.key}=${it.value}" }}")
            }
        }
    }

    private fun classifyError(result: UploadResult.Failure): String {
        return when (result) {
            is UploadResult.Failure.OffsetMismatch -> "OFFSET_MISMATCH"
            is UploadResult.Failure.PermissionError -> "PERMISSION_ERROR"
            is UploadResult.Failure.StorageFull -> "STORAGE_FULL"
            is UploadResult.Failure.FileDeleted -> "FILE_DELETED"
            is UploadResult.Failure.UnknownError -> "UNKNOWN"
        }
    }

    private fun updateMetricsSnapshot() {
        val total = totalUploads.get()
        val avgDuration = if (total > 0) totalDurationMs.get() / total else 0L

        _metrics.value = UploadMetrics(
            totalUploads = total,
            successfulUploads = successfulUploads.get(),
            failedUploads = failedUploads.get(),
            cancelledUploads = cancelledUploads.get(),
            pausedUploads = pausedUploads.get(),
            resumedUploads = resumedUploads.get(),
            totalBytesTransferred = totalBytes.get(),
            averageDurationMs = avgDuration,
            errorBreakdown = errorCounts.mapValues { it.value.get() }
        )
    }
}
