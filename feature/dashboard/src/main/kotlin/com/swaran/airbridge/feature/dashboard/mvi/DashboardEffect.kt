package com.swaran.airbridge.feature.dashboard.mvi

import com.swaran.airbridge.core.mvi.MviEffect

/**
 * Side effects for the Dashboard feature.
 */
sealed class DashboardEffect : MviEffect {
    /**
     * Show error message.
     * @property message Error message to display
     */
    data class ShowError(val message: String) : DashboardEffect()

    /**
     * Show QR code dialog.
     * @property url URL to encode in QR code
     */
    data class ShowQrCode(val url: String) : DashboardEffect()

    /** Navigate to permissions screen. */
    data object NavigateToPermissions : DashboardEffect()

    /** Navigate to file browser screen. */
    data object NavigateToFileBrowser : DashboardEffect()

    /** Launch storage directory picker. */
    data object LaunchStorageDirectoryPicker : DashboardEffect()
}
