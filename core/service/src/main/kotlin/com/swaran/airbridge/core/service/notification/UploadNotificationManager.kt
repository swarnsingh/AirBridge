package com.swaran.airbridge.core.service.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.model.UploadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages upload notifications with pause/resume/cancel actions.
 */
@Singleton
class UploadNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val UPLOAD_CHANNEL_ID = "airbridge_upload_channel"
        const val UPLOAD_NOTIFICATION_ID = 2

        const val ACTION_PAUSE_ALL = "com.swaran.airbridge.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.swaran.airbridge.action.RESUME_ALL"
        const val ACTION_CANCEL_ALL = "com.swaran.airbridge.action.CANCEL_ALL"
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        createUploadChannel()
    }

    /**
     * Build notification for active uploads.
     */
    fun buildUploadNotification(
        activeUploads: List<UploadStatus>,
        isGlobalPaused: Boolean
    ): Notification {
        val progress = calculateProgress(activeUploads)
        val speed = calculateSpeed(activeUploads)

        val builder = NotificationCompat.Builder(context, UPLOAD_CHANNEL_ID)
            .setContentTitle(buildTitle(activeUploads, isGlobalPaused))
            .setContentText("$progress% • $speed")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        // Add pause/resume action
        if (isGlobalPaused || activeUploads.all { it.state == UploadState.PAUSED }) {
            builder.addAction(
                android.R.drawable.ic_media_play,
                "Resume All",
                createPendingIntent(ACTION_RESUME_ALL)
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Pause All",
                createPendingIntent(ACTION_PAUSE_ALL)
            )
        }

        // Add cancel action
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Cancel All",
            createPendingIntent(ACTION_CANCEL_ALL)
        )

        return builder.build()
    }

    /**
     * Build notification when no uploads are active but service is running.
     */
    fun buildIdleNotification(serverAddress: String, port: Int): Notification {
        return NotificationCompat.Builder(context, UPLOAD_CHANNEL_ID)
            .setContentTitle("AirBridge Ready")
            .setContentText("Waiting for uploads at $serverAddress:$port")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun buildTitle(uploads: List<UploadStatus>, isPaused: Boolean): String {
        if (isPaused) {
            return "Uploads Paused (${uploads.size})"
        }
        val uploadingCount = uploads.count { it.state == UploadState.UPLOADING }
        return when {
            uploadingCount > 0 -> "Uploading $uploadingCount file${if (uploadingCount > 1) "s" else ""}"
            uploads.isNotEmpty() -> "Preparing uploads (${uploads.size})"
            else -> "AirBridge Upload"
        }
    }

    private fun calculateProgress(uploads: List<UploadStatus>): Int {
        if (uploads.isEmpty()) return 0
        val total = uploads.sumOf { it.metadata.totalBytes }
        val received = uploads.sumOf { it.bytesReceived }
        return if (total > 0) ((received * 100) / total).toInt() else 0
    }

    private fun calculateSpeed(uploads: List<UploadStatus>): String {
        val avgSpeed = uploads.map { it.bytesPerSecond }.average()
        return when {
            avgSpeed >= 1_000_000 -> "%.1f MB/s".format(avgSpeed / 1_000_000)
            avgSpeed >= 1_000 -> "%.1f KB/s".format(avgSpeed / 1_000)
            else -> "%.0f B/s".format(avgSpeed)
        }
    }

    private fun createPendingIntent(action: String): PendingIntent {
        val intent = Intent(context, UploadActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createUploadChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPLOAD_CHANNEL_ID,
                "AirBridge Uploads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of file uploads"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
