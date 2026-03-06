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
import com.swaran.airbridge.core.common.logging.AirLogger
import androidx.core.app.NotificationCompat
import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.core.network.ktor.KtorLocalServer
import com.swaran.airbridge.core.network.upload.UploadScheduler
import com.swaran.airbridge.core.service.doze.DozeModeMonitor
import com.swaran.airbridge.core.service.mdns.AirBridgeMdnsService
import com.swaran.airbridge.core.service.network.NetworkMonitor
import com.swaran.airbridge.core.service.notification.UploadNotificationManager
import com.swaran.airbridge.core.service.thermal.ThermalMonitor
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.usecase.UploadStateManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service managing the AirBridge HTTP server.
 *
 * ## Architecture
 *
 * This service runs the Ktor CIO server for LAN file transfers.
 * It maintains a foreground notification to keep the service alive
 * and displays upload progress with pause/resume/cancel actions.
 *
 * ## Features
 *
 * - HTTP server on port 8081
 * - Upload progress notification with actions
 * - Thermal throttling detection and auto-pause
 * - Crash recovery via persisted upload state
 */
@AndroidEntryPoint
class ForegroundServerService : Service() {

    @Inject
    lateinit var ktorLocalServer: KtorLocalServer
    @Inject
    lateinit var serverPreferences: ServerPreferences
    @Inject
    lateinit var sessionTokenManager: SessionTokenManager
    @Inject
    lateinit var uploadScheduler: UploadScheduler
    @Inject
    lateinit var uploadStateManager: UploadStateManager
    @Inject
    lateinit var notificationManager: UploadNotificationManager
    @Inject
    lateinit var thermalMonitor: ThermalMonitor
    @Inject
    lateinit var networkMonitor: NetworkMonitor
    @Inject
    lateinit var dozeModeMonitor: DozeModeMonitor
    @Inject
    lateinit var logger: AirLogger
    @Inject
    lateinit var mdnsService: AirBridgeMdnsService

    @Inject
    @Dispatcher(AirDispatchers.IO)
    lateinit var ioDispatcher: CoroutineDispatcher

    private val serviceScope by lazy { CoroutineScope(ioDispatcher + SupervisorJob()) }

    companion object {
        private const val TAG = "ForegroundServerService"
        private const val DEFAULT_PORT = 8081
        const val CHANNEL_ID = "airbridge_server_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.swaran.airbridge.action.START"
        const val ACTION_STOP = "com.swaran.airbridge.action.STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        thermalMonitor.startMonitoring()
        networkMonitor.startMonitoring()
        dozeModeMonitor.startMonitoring()
        
        // Recover interrupted uploads from previous session
        recoverInterruptedUploads()
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
        thermalMonitor.stopMonitoring()
        networkMonitor.stopMonitoring()
        dozeModeMonitor.stopMonitoring()
        ktorLocalServer.stop()
        _isRunning.value = false
    }

    /**
     * Recovers uploads that were interrupted by app termination.
     */
    private fun recoverInterruptedUploads() {
        serviceScope.launch {
            try {
                uploadStateManager.recoverPersistedUploads()
            } catch (e: Exception) {
                logger.e(TAG, "log", "Failed to recover interrupted uploads", e)
            }
        }
    }

    private fun startServer() {
        serviceScope.launch {
            val session = sessionTokenManager.generateSession()
            val port = serverPreferences.getLastPort()

            logger.i(TAG, "log", "Starting Ktor server on port $port")
            val success = ktorLocalServer.start(port)

            if (success) {
                _isRunning.value = true
                serverPreferences.saveLastPort(port)

                // Start mDNS service for airbridge.local discovery
                val mdnsSuccess = mdnsService.start(port, session.serverAddress)
                if (mdnsSuccess) {
                    logger.i(TAG, "log", "mDNS service started: ${mdnsService.getMdnsHostname()} available")
                }

                observeUploadsAndUpdateNotification(port, session.serverAddress)

                val mdnsHostname = mdnsService.getMdnsHostname()
                val sharingText = if (mdnsHostname != null) {
                    "Sharing at $mdnsHostname or ${session.serverAddress}:$port"
                } else {
                    "Sharing at ${session.serverAddress}:$port"
                }

                val notification = buildNotification(
                    "AirBridge is Active",
                    sharingText
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                logger.e(TAG, "log", "Failed to start Ktor server on port $port")
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        serviceScope.launch {
            mdnsService.stop()
            ktorLocalServer.stop()
            _isRunning.value = false
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AirBridge Server",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the AirBridge file server active in the background."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, content: String): Notification {
        val stopPendingIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ForegroundServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Server",
                stopPendingIntent
            )
            .build()
    }

    private fun observeUploadsAndUpdateNotification(port: Int, serverAddress: String) {
        serviceScope.launch {
            uploadStateManager.activeUploads
                .collectLatest { uploads ->
                    val activeUploads = uploads.values.filter { !it.isTerminal }
                    val isPaused = activeUploads.all { it.state == UploadState.PAUSED }
                    val notification = if (activeUploads.isNotEmpty()) {
                        notificationManager.buildUploadNotification(
                            activeUploads = activeUploads,
                            isGlobalPaused = isPaused
                        )
                    } else {
                        notificationManager.buildIdleNotification(serverAddress, port)
                    }

                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(UploadNotificationManager.UPLOAD_NOTIFICATION_ID, notification)
                }
        }
    }
}