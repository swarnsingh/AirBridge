package com.swaran.airbridge.feature.permissions

import com.swaran.airbridge.core.mvi.MviState

data class PermissionState(
    val isLoading: Boolean = false,
    val hasStoragePermission: Boolean = false,
    val shouldShowRationale: Boolean = false,
    val permanentlyDenied: Boolean = false
) : MviState
