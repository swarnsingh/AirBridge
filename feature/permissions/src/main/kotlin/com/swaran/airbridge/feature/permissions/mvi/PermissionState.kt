package com.swaran.airbridge.feature.permissions.mvi

import com.swaran.airbridge.core.mvi.MviState

/**
 * State representation for the Permissions feature.
 *
 * @property hasPermission Whether storage permission is currently granted
 */
data class PermissionState(
    val hasPermission: Boolean = false
) : MviState
