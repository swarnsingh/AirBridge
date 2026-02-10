package com.swaran.airbridge.feature.permissions

import com.swaran.airbridge.core.mvi.MviViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PermissionViewModel @Inject constructor() :
    MviViewModel<PermissionIntent, PermissionState, PermissionEffect>(PermissionState()) {

    override suspend fun handleIntent(intent: PermissionIntent) {
        when (intent) {
            is PermissionIntent.CheckPermissions -> { }
            is PermissionIntent.RequestStoragePermission -> {
                sendEffect(PermissionEffect.LaunchStoragePermissionRequest)
            }
            is PermissionIntent.OnPermissionResult -> {
                val shouldNavigate = intent.granted && !state.value.hasStoragePermission
                updateState { copy(hasStoragePermission = intent.granted) }
                if (shouldNavigate) {
                    sendEffect(PermissionEffect.NavigateToDashboard)
                }
            }
            is PermissionIntent.OpenSettings -> {
                sendEffect(PermissionEffect.NavigateToSettings)
            }
        }
    }
}
