package com.swaran.airbridge.feature.permissions.mvi

import com.swaran.airbridge.core.mvi.MviEffect

/**
 * Side effects for the Permissions feature.
 */
sealed class PermissionEffect : MviEffect {
    /** Navigate to the dashboard screen. */
    data object NavigateToDashboard : PermissionEffect()

    /** Navigate to app settings for manual permission grant. */
    data object NavigateToSettings : PermissionEffect()

    /** Launch the system permission request dialog. */
    data object LaunchStoragePermissionRequest : PermissionEffect()
}
