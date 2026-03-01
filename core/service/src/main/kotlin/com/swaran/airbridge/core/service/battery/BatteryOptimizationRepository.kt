package com.swaran.airbridge.core.service.battery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.swaran.airbridge.core.common.ResultState
import com.swaran.airbridge.domain.usecase.CheckBatteryOptimizationStatusUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for battery optimization status and management.
 *
 * Handles checking and requesting exemption from Android's Doze mode
 * and App Standby for reliable background file transfers.
 */
@Singleton
class BatteryOptimizationRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /**
     * Check if app is ignoring battery optimizations.
     *
     * @return true if battery optimization is enabled (app needs exemption)
     */
    fun isBatteryOptimizationEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            false // Pre-Marshmallow doesn't have this optimization
        }
    }

    /**
     * Get intent to open battery optimization settings.
     */
    fun getBatteryOptimizationSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }
    }

    /**
     * Get intent to open general app battery settings (fallback).
     */
    fun getAppBatterySettingsIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}

/**
 * Concrete implementation of CheckBatteryOptimizationStatusUseCase.
 */
@Singleton
class CheckBatteryOptimizationStatusUseCaseImpl @Inject constructor(
    private val repository: BatteryOptimizationRepository
) : CheckBatteryOptimizationStatusUseCase() {

    override fun invoke(): Flow<ResultState<Boolean>> = flow {
        emit(ResultState.Loading())
        try {
            val isOptimized = repository.isBatteryOptimizationEnabled()
            emit(ResultState.Success(isOptimized))
        } catch (e: Exception) {
            emit(ResultState.Error(e))
        }
    }
}
