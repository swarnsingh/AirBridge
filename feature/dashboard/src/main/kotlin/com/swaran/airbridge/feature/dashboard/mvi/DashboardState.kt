package com.swaran.airbridge.feature.dashboard.mvi

import com.swaran.airbridge.core.mvi.MviState
import com.swaran.airbridge.domain.model.ServerStatus
import android.net.Uri
import com.swaran.airbridge.feature.dashboard.viewmodel.UploadProgress
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class DashboardState(
    val isLoading: Boolean = false,
    val serverStatus: ServerStatus = ServerStatus.Stopped,
    val serverAddress: String? = null,
    val qrCodeUrl: String? = null,
    val isBrowserConnected: Boolean = false,
    val hasStoragePermission: Boolean = false,
    val storageDirectoryUri: Uri? = null,
    val errorMessage: String? = null,
    val activeUploads: ImmutableList<UploadProgress> = persistentListOf()
) : MviState
