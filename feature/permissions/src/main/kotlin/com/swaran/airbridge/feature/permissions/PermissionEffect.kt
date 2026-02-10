package com.swaran.airbridge.feature.permissions

import com.swaran.airbridge.core.mvi.MviEffect

sealed class PermissionEffect : MviEffect {
    data object LaunchStoragePermissionRequest : PermissionEffect()
    data object NavigateToSettings : PermissionEffect()
    data object NavigateToDashboard : PermissionEffect()
}
