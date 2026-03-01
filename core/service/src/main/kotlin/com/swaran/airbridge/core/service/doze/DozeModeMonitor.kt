package com.swaran.airbridge.core.service.doze

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.core.network.upload.UploadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors Doze mode (App Standby) state and manages upload behavior.
 *
 * Doze mode (API 23+) restricts:
 * - Network access when idle
 * - Wake locks
 * - Background jobs
 *
 * This monitor:
 * - Detects Doze mode entry/exit
 * - Pauses uploads when Doze active
 * - Resumes uploads when Doze exits
 * - Logs Doze state changes for debugging
 */
@Singleton
class DozeModeMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadScheduler: UploadScheduler,
    private val logger: AirLogger
) {
    companion object {
        private const val TAG = "DozeModeMonitor"
    }

    private val powerManager: PowerManager? = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var wasInDoze = false

    private val dozeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> checkDozeState()
            }
        }
    }

    /**
     * Start monitoring Doze mode changes.
     */
    fun startMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            logger.d(TAG, "startMonitoring", "Doze mode not supported below API 23")
            return
        }

        val filter = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        context.registerReceiver(dozeReceiver, filter)
        
        // Check initial state
        checkDozeState()
        
        logger.d(TAG, "startMonitoring", "Doze mode monitoring started")
    }

    /**
     * Stop monitoring.
     */
    fun stopMonitoring() {
        try {
            context.unregisterReceiver(dozeReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }
        logger.d(TAG, "stopMonitoring", "Doze mode monitoring stopped")
    }

    /**
     * Check if device is in Doze mode and pause/resume accordingly.
     */
    private fun checkDozeState() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val isInDoze = powerManager?.isDeviceIdleMode ?: false
        
        if (isInDoze && !wasInDoze) {
            // Entered Doze mode - pause all active uploads
            logger.w(TAG, "checkDozeState", "Device entered Doze mode - pausing uploads")
            uploadScheduler.activeUploads.value.keys.forEach { uploadId ->
                uploadScheduler.pause(uploadId)
            }
            wasInDoze = true
        } else if (!isInDoze && wasInDoze) {
            // Exited Doze mode - browser will auto-resume via POST
            logger.i(TAG, "checkDozeState", "Device exited Doze mode - uploads will resume automatically")
            wasInDoze = false
        }
    }

    /**
     * Check if app is ignoring battery optimizations (whitelisted).
     * This allows running in Doze mode.
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            true
        }
    }

    /**
     * Request battery optimization whitelist (requires user action).
     */
    fun requestIgnoreBatteryOptimizations(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations()) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
        } else {
            null
        }
    }
}
