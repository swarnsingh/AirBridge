package com.swaran.airbridge.feature.permissions.mvi

import com.swaran.airbridge.core.mvi.MviIntent

/**
 * Intent events for the Permissions feature.
 */
sealed class PermissionIntent : MviIntent {
    /** Request storage permission from the user. */
    data object RequestStoragePermission : PermissionIntent()

    /**
     * Handle permission request result.
     * @property granted Whether permission was granted
     */
    data class OnPermissionResult(val granted: Boolean) : PermissionIntent()
}
