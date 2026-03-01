package com.swaran.airbridge.feature.dashboard.mvi

import com.swaran.airbridge.core.mvi.MviIntent
import android.net.Uri

sealed class DashboardIntent : MviIntent {
    data object StartServer : DashboardIntent()
    data object StopServer : DashboardIntent()
    data object RefreshStatus : DashboardIntent()
    data object GenerateQrCode : DashboardIntent()
    data object ScanQrCode : DashboardIntent()
    data class UpdatePermissionState(val hasPermission: Boolean) : DashboardIntent()
    data class SelectStorageDirectory(val uri: Uri) : DashboardIntent()
    data object RequestStorageDirectory : DashboardIntent()

    /** Navigate to file browser screen. */
    data object NavigateToFileBrowser : DashboardIntent()

    data class SendToComputer(val fileName: String, val uri: Uri) : DashboardIntent()
    data class PauseUpload(val uploadId: String) : DashboardIntent()
    data class ResumeUpload(val uploadId: String) : DashboardIntent()
    data class CancelUpload(val uploadId: String) : DashboardIntent()
}
