package com.swaran.airbridge.feature.permissions.viewmodel

import com.swaran.airbridge.core.mvi.MviViewModel
import com.swaran.airbridge.feature.permissions.mvi.PermissionEffect
import com.swaran.airbridge.feature.permissions.mvi.PermissionIntent
import com.swaran.airbridge.feature.permissions.mvi.PermissionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * ViewModel for the Permissions feature.
 *
 * Manages storage permission requests and navigation based on permission state.
 */
@HiltViewModel
class PermissionViewModel @Inject constructor() :
    MviViewModel<PermissionIntent, PermissionState, PermissionEffect>(PermissionState()) {

    override suspend fun handleIntent(intent: PermissionIntent) {
        when (intent) {
            is PermissionIntent.RequestStoragePermission -> {
                sendEffect(PermissionEffect.LaunchStoragePermissionRequest)
            }
            is PermissionIntent.OnPermissionResult -> {
                if (intent.granted) {
                    sendEffect(PermissionEffect.NavigateToDashboard)
                }
            }
        }
    }
}
