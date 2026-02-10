package com.swaran.airbridge.feature.permissions

import com.swaran.airbridge.core.mvi.MviIntent

sealed class PermissionIntent : MviIntent {
    data object CheckPermissions : PermissionIntent()
    data object RequestStoragePermission : PermissionIntent()
    data class OnPermissionResult(val granted: Boolean) : PermissionIntent()
    data object OpenSettings : PermissionIntent()
}
