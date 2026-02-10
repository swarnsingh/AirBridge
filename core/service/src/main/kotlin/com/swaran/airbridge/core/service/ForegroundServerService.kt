package com.swaran.airbridge.core.service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.swaran.airbridge.core.network.HttpControllers
import com.swaran.airbridge.core.network.LocalHttpServer
import com.swaran.airbridge.core.network.SessionTokenManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ForegroundServerService : Service() {

    @Inject
    lateinit var localHttpServer: LocalHttpServer

    @Inject
    lateinit var sessionTokenManager: SessionTokenManager

    @Inject
    lateinit var controllers: HttpControllers

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "airbridge_server_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.swaran.airbridge.action.START"
        const val ACTION_STOP = "com.swaran.airbridge.action.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        localHttpServer.stop()
    }

    private fun startServer() {
        serviceScope.launch {
            val session = sessionTokenManager.generateSession()
            val success = localHttpServer.start(session.serverPort)

            if (success) {
                val notification = buildNotification(
                    "Server running on port ${session.serverPort}",
                    "Token: ${session.token.take(8)}..."
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun stopServer() {
        localHttpServer.stop()
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AirBridge Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for AirBridge file server"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val stopIntent = Intent(this, ForegroundServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_airbridge_notification)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_airbridge_notification,
                "Stop",
                stopPendingIntent
            )
            .build()
    }
}
