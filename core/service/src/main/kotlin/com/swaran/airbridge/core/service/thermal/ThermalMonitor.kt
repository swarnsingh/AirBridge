package com.swaran.airbridge.core.service.thermal

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.core.network.upload.UploadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors device thermal status and pauses uploads when overheating.
 *
 * Android 10+ (API 29+) provides [PowerManager.getCurrentThermalStatus()]
 * which reports thermal throttling levels.
 *
 * Thermal Status Levels:
 * - THERMAL_STATUS_NONE (0): No thermal issues
 * - THERMAL_STATUS_LIGHT (1): Light throttling - minor performance impact
 * - THERMAL_STATUS_MODERATE (2): Moderate throttling - noticeable performance impact
 * - THERMAL_STATUS_SEVERE (3): Severe throttling - significant performance impact
 * - THERMAL_STATUS_CRITICAL (4): Critical - system at risk
 * - THERMAL_STATUS_EMERGENCY (5): Emergency - shutdown imminent
 * - THERMAL_STATUS_SHUTDOWN (6): Shutdown required
 *
 * This monitor pauses uploads when status >= SEVERE and auto-resumes when
 * status returns to <= MODERATE.
 */
@Singleton
class ThermalMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadScheduler: UploadScheduler,
    @Dispatcher(AirDispatchers.Default) defaultDispatcher: CoroutineDispatcher,
    private val logger: AirLogger
) {
    companion object {
        private const val TAG = "ThermalMonitor"
        private const val CHECK_INTERVAL_MS = 10000L // Check every 10 seconds
        private const val PAUSE_THRESHOLD = PowerManager.THERMAL_STATUS_SEVERE
        private const val RESUME_THRESHOLD = PowerManager.THERMAL_STATUS_MODERATE
    }

    private val powerManager: PowerManager? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        } else {
            null
        }
    }

    private var monitoringJob: Job? = null
    private val scope = CoroutineScope(defaultDispatcher)

    private var wasPausedByThermal = false

    /**
     * Start monitoring thermal status.
     * No-op on devices below Android 10 (API 29).
     */
    fun startMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            logger.d(TAG, "log", "Thermal monitoring not available (API < 29)")
            return
        }

        if (monitoringJob?.isActive == true) {
            logger.d(TAG, "log", "Thermal monitoring already active")
            return
        }

        logger.d(TAG, "log", "Starting thermal monitoring")
        monitoringJob = scope.launch {
            while (true) {
                checkThermalStatus()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop monitoring thermal status.
     */
    fun stopMonitoring() {
        logger.d(TAG, "log", "Stopping thermal monitoring")
        monitoringJob?.cancel()
        monitoringJob = null

        // If we paused uploads due to thermal, resume them now
        if (wasPausedByThermal) {
            logger.d(TAG, "log", "Auto-resuming uploads after thermal monitoring stopped")
            uploadScheduler.resumeAll()
            wasPausedByThermal = false
        }
    }

    /**
     * Check current thermal status and pause/resume uploads accordingly.
     */
    private fun checkThermalStatus() {
        val status = getThermalStatus()
        
        // Only log thermal status when it's elevated (above NONE) or when taking action
        if (status > PowerManager.THERMAL_STATUS_NONE) {
            logger.d(TAG, "checkThermalStatus", "Thermal status: $status (thresholds: pause=$PAUSE_THRESHOLD, resume=$RESUME_THRESHOLD)")
        }

        when {
            status >= PAUSE_THRESHOLD && !wasPausedByThermal -> {
                // Device is overheating - pause uploads
                logger.w(TAG, "checkThermalStatus", "Thermal status SEVERE+ ($status) - pausing all uploads")
                uploadScheduler.pauseAll()
                wasPausedByThermal = true
            }
            status <= RESUME_THRESHOLD && wasPausedByThermal -> {
                // Device has cooled down - resume uploads
                logger.i(TAG, "checkThermalStatus", "Thermal status returned to normal ($status) - resuming uploads")
                uploadScheduler.resumeAll()
                wasPausedByThermal = false
            }
        }
    }

    /**
     * Get current thermal status.
     * Returns THERMAL_STATUS_NONE (0) if not available.
     */
    private fun getThermalStatus(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            } catch (e: Exception) {
                logger.e(TAG, "log", "Failed to get thermal status", e)
                PowerManager.THERMAL_STATUS_NONE
            }
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
    }

    /**
     * Check if uploads are currently paused due to thermal throttling.
     */
    fun isPausedByThermal(): Boolean = wasPausedByThermal

    /**
     * Get human-readable thermal status name.
     */
    fun getThermalStatusName(): String {
        return when (getThermalStatus()) {
            PowerManager.THERMAL_STATUS_NONE -> "None"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Unknown"
        }
    }
}
