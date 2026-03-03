package com.swaran.airbridge.feature.dashboard.viewmodel

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.swaran.airbridge.core.common.ResultState
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.core.mvi.MviViewModel
import com.swaran.airbridge.core.network.PushManager
import com.swaran.airbridge.core.network.upload.UploadQueueManager
import com.swaran.airbridge.core.service.ServerPreferences
import com.swaran.airbridge.core.service.mdns.AirBridgeMdnsService
import com.swaran.airbridge.domain.model.ServerStatus
import com.swaran.airbridge.domain.model.UploadRequest
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.model.UploadStatus
import com.swaran.airbridge.domain.repository.StorageAccessManager
import com.swaran.airbridge.domain.usecase.GenerateQrCodeUseCase
import com.swaran.airbridge.domain.usecase.GetServerAddressUseCase
import com.swaran.airbridge.domain.usecase.StartServerUseCase
import com.swaran.airbridge.domain.usecase.StopServerUseCase
import com.swaran.airbridge.feature.dashboard.mvi.DashboardEffect
import com.swaran.airbridge.feature.dashboard.mvi.DashboardIntent
import com.swaran.airbridge.feature.dashboard.mvi.DashboardState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Dashboard screen.
 *
 * Manages:
 * - Server start/stop
 * - QR code generation
 * - Upload progress display
 * - Upload pause/resume/cancel actions
 * - Storage directory selection
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val startServerUseCase: StartServerUseCase,
    private val stopServerUseCase: StopServerUseCase,
    private val getServerAddressUseCase: GetServerAddressUseCase,
    private val generateQrCodeUseCase: GenerateQrCodeUseCase,
    private val storageAccessManager: StorageAccessManager,
    private val pushManager: PushManager,
    private val uploadQueueManager: UploadQueueManager,
    private val serverPreferences: ServerPreferences,
    private val mdnsService: AirBridgeMdnsService,
    private val logger: AirLogger
) : MviViewModel<DashboardIntent, DashboardState, DashboardEffect>(DashboardState()) {

    private val actionCooldowns = mutableMapOf<String, Long>()
    private val lastLoggedPercent = mutableMapOf<String, Int>()
    private val lastLoggedState = mutableMapOf<String, String>()
    
    private companion object {
        const val TAG = "DashboardViewModel"
        const val ACTION_COOLDOWN_MS = 250L // Aggressive sync with browser poll
    }

    private fun isOnCooldown(uploadId: String): Boolean {
        val lastAction = actionCooldowns[uploadId] ?: 0
        val now = System.currentTimeMillis()
        // Clean up old entries (older than 10 seconds)
        actionCooldowns.entries.removeAll { now - it.value > 10000 }
        val elapsed = now - lastAction
        return if (elapsed < ACTION_COOLDOWN_MS) {
            logger.w(TAG, "isOnCooldown", "[$uploadId] Action blocked - ${ACTION_COOLDOWN_MS - elapsed}ms remaining on cooldown")
            true
        } else {
            actionCooldowns[uploadId] = now
            false
        }
    }

    init {
        updateState { 
            copy(
                hasStoragePermission = true,
                storageDirectoryUri = Uri.parse(storageAccessManager.getStorageDirectoryUri())
            ) 
        }
        loadServerAddress()

        // Collect active uploads from UploadScheduler
        uploadQueueManager.scheduler.activeUploads
            .onEach { uploads ->
                // Show ALL uploads including completed (don't filter isTerminal)
                val uploadProgressList = uploads.values
                    .map { it.toUploadProgress() }
                    .toImmutableList()
                logger.d(TAG, "init", "UI received ${uploadProgressList.size} uploads, first: ${uploadProgressList.firstOrNull()?.let { "${it.fileName} ${it.percentage}% ${it.status}" } ?: "none"}")
                updateState { copy(activeUploads = uploadProgressList) }
            }
            .launchIn(viewModelScope)
    }

    override suspend fun handleIntent(intent: DashboardIntent) {
        when (intent) {
            is DashboardIntent.StartServer -> handleStartServer()
            is DashboardIntent.StopServer -> handleStopServer()
            is DashboardIntent.RefreshStatus -> loadServerAddress()
            is DashboardIntent.GenerateQrCode -> handleGenerateQrCode()
            is DashboardIntent.UpdatePermissionState -> updateState {
                copy(hasStoragePermission = intent.hasPermission)
            }
            is DashboardIntent.RequestStorageDirectory -> {
                sendEffect(DashboardEffect.LaunchStorageDirectoryPicker)
            }
            is DashboardIntent.SelectStorageDirectory -> {
                storageAccessManager.setStorageDirectory(intent.uri.toString())
                updateState { copy(storageDirectoryUri = intent.uri) }
            }
            is DashboardIntent.SendToComputer -> {
                pushManager.enqueuePush(intent.fileName, intent.uri)
            }
            is DashboardIntent.PauseUpload -> {
                logger.d(TAG, "handleIntent", "Pause upload: ${intent.uploadId}")
                if (isOnCooldown(intent.uploadId)) return
                uploadQueueManager.pause(intent.uploadId)
            }
            is DashboardIntent.ResumeUpload -> {
                logger.d(TAG, "handleIntent", "Resume upload: ${intent.uploadId}")
                if (isOnCooldown(intent.uploadId)) return
                // Transition state so browser will detect and re-POST
                uploadQueueManager.resume(intent.uploadId)
            }
            is DashboardIntent.CancelUpload -> {
                logger.d(TAG, "handleIntent", "Cancel upload: ${intent.uploadId}")
                val status = uploadQueueManager.scheduler.getStatus(intent.uploadId)
                if (status != null) {
                    val request = UploadRequest(
                        uploadId = intent.uploadId,
                        fileUri = status.metadata.fileUri,
                        fileName = status.metadata.displayName,
                        path = status.metadata.path,
                        offset = 0,
                        totalBytes = 0
                    )
                    viewModelScope.launch {
                        uploadQueueManager.cancel(intent.uploadId, request)
                    }
                }
            }
            is DashboardIntent.NavigateToFileBrowser -> {
                // Navigation handled via Effect in Route
            }
            is DashboardIntent.ScanQrCode -> {
                // QR scanning handled in Route (camera permission + navigation)
            }
        }
    }

    private suspend fun handleStartServer() {
        logger.d(TAG, "handleStartServer", "Starting server...")

        val port = serverPreferences.getLastPort()
        handleResultState(
            flow = startServerUseCase(StartServerUseCase.Params(port = port)),
            loadingState = state.value.copy(isLoading = true),
            onSuccess = { result ->
                logger.i(TAG, "handleStartServer", "Server started on ${result.serverAddress}:${result.serverPort}")
                loadServerAddress()
                state.value.copy(
                    isLoading = false,
                    serverStatus = ServerStatus.Running(
                        address = result.serverAddress,
                        port = result.serverPort,
                        sessionToken = result.token
                    )
                ) to null
            },
            onError = { error ->
                logger.e(TAG, "handleStartServer", error, "Failed to start server")
                state.value.copy(isLoading = false, errorMessage = error.message) to
                    DashboardEffect.ShowError(error.message ?: "Failed to start server")
            }
        )
    }

    private suspend fun handleStopServer() {
        handleResultState(
            flow = stopServerUseCase(Unit),
            loadingState = state.value.copy(isLoading = true),
            onSuccess = {
                state.value.copy(
                    isLoading = false,
                    serverStatus = ServerStatus.Stopped,
                    serverAddress = null,
                    qrCodeUrl = null
                ) to null
            },
            onError = { error ->
                state.value to DashboardEffect.ShowError(error.message ?: "Failed to stop server")
            }
        )
    }

    private fun loadServerAddress() {
        viewModelScope.launch {
            getServerAddressUseCase(Unit)
                .onEach { result ->
                    if (result is ResultState.Success) {
                        val mdnsHostname = mdnsService.getMdnsHostname()
                        updateState { copy(serverAddress = result.data, mdnsHostname = mdnsHostname) }
                    }
                }
                .launchIn(viewModelScope)
        }
    }

    private suspend fun handleGenerateQrCode() {
        handleResultState(
            flow = generateQrCodeUseCase(Unit),
            loadingState = state.value.copy(isLoading = true),
            onSuccess = { result ->
                state.value.copy(qrCodeUrl = result, isLoading = false) to
                    DashboardEffect.ShowQrCode(result)
            },
            onError = { error ->
                state.value.copy(isLoading = false) to
                    DashboardEffect.ShowError(error.message ?: "Failed to generate QR code")
            }
        )
    }

    /**
     * Maps domain UploadStatus to UI UploadProgress model.
     */
    private fun UploadStatus.toUploadProgress(): UploadProgress {
        val percentage = if (metadata.totalBytes > 0) {
            ((bytesReceived.toFloat() / metadata.totalBytes.toFloat()) * 100)
                .toInt()
                .coerceIn(0, 100)
        } else 0

        val mappedStatus = state.value
        
        // Log button state visibility (throttled: only log on 25% milestones or state change)
        val buttonState = when (state) {
            UploadState.UPLOADING,
            UploadState.PAUSING -> "[PAUSE][CANCEL]"
            UploadState.PAUSED,
            UploadState.RESUMING -> "[RESUME][CANCEL]"
            UploadState.NONE,
            UploadState.QUEUED -> "[CANCEL]"
            UploadState.COMPLETED -> "[DONE]"
            UploadState.CANCELLED -> "[CANCELLED]"
            UploadState.ERROR -> "[RETRY][CANCEL]"
        }
        val lastLogged = lastLoggedPercent.getOrDefault(metadata.uploadId, -1)
        val milestones = setOf(0, 25, 50, 75, 100)
        if (percentage in milestones && percentage != lastLogged || state.name != lastLoggedState.getOrDefault(metadata.uploadId, "")) {
            logger.v(TAG, "toUploadProgress", "[${metadata.uploadId}] UI state: $state -> $mappedStatus $buttonState ($percentage%)")
            lastLoggedPercent[metadata.uploadId] = percentage
            lastLoggedState[metadata.uploadId] = state.name
        }

        return UploadProgress(
            id = metadata.uploadId,
            fileName = metadata.displayName,
            bytesReceived = bytesReceived,
            totalBytes = metadata.totalBytes,
            percentage = percentage,
            status = mappedStatus,
            speedBps = bytesPerSecond,
            etaSeconds = estimatedSecondsRemaining
        )
    }
}

/**
 * UI model for upload progress.
 */
data class UploadProgress(
    val id: String,
    val fileName: String,
    val bytesReceived: Long,
    val totalBytes: Long,
    val percentage: Int,
    val status: String,
    val speedBps: Float = 0f,
    val etaSeconds: Long = -1
)