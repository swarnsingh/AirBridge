package com.swaran.airbridge.feature.dashboard

import com.swaran.airbridge.core.mvi.MviState
import com.swaran.airbridge.domain.model.ServerStatus

import android.net.Uri

data class DashboardState(
    val isLoading: Boolean = false,
    val serverStatus: ServerStatus = ServerStatus.Stopped,
    val serverAddress: String? = null,
    val qrCodeUrl: String? = null,
    val hasStoragePermission: Boolean = false,
    val storageDirectoryUri: Uri? = null,
    val errorMessage: String? = null
) : MviState
