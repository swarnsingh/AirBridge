package com.swaran.airbridge.feature.dashboard

import com.swaran.airbridge.core.mvi.MviEffect

sealed class DashboardEffect : MviEffect {
    data class ShowError(val message: String) : DashboardEffect()
    data class ShowQrCode(val url: String) : DashboardEffect()
    data object NavigateToPermissions : DashboardEffect()
    data object NavigateToFileBrowser : DashboardEffect()
    data object LaunchStorageDirectoryPicker : DashboardEffect()
}
