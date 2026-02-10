package com.swaran.airbridge.feature.dashboard

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.swaran.airbridge.core.common.ResultState
import com.swaran.airbridge.core.mvi.MviViewModel
import com.swaran.airbridge.core.network.controller.UploadController
import com.swaran.airbridge.domain.model.ServerStatus
import com.swaran.airbridge.domain.repository.StorageAccessManager
import com.swaran.airbridge.domain.usecase.GenerateQrCodeUseCase
import com.swaran.airbridge.domain.usecase.GetServerAddressUseCase
import com.swaran.airbridge.domain.usecase.StartServerUseCase
import com.swaran.airbridge.domain.usecase.StopServerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val startServerUseCase: StartServerUseCase,
    private val stopServerUseCase: StopServerUseCase,
    private val getServerAddressUseCase: GetServerAddressUseCase,
    private val generateQrCodeUseCase: GenerateQrCodeUseCase,
    private val storageAccessManager: StorageAccessManager,
    val uploadController: UploadController
) : MviViewModel<DashboardIntent, DashboardState, DashboardEffect>(DashboardState()) {

    init {
        updateState { copy(hasStoragePermission = storageAccessManager.hasStorageDirectory()) }
        loadServerAddress()
    }

    init {
        loadServerAddress()
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
        }
    }

    private suspend fun handleStartServer() {
        updateState { copy(isLoading = true) }

        startServerUseCase(StartServerUseCase.Params(port = 8080))
            .onEach { result ->
                when (result) {
                    is ResultState.Loading -> updateState { copy(isLoading = true) }
                    is ResultState.Success -> {
                        updateState {
                            copy(
                                isLoading = false,
                                serverStatus = ServerStatus.Running(
                                    address = result.data.serverAddress,
                                    port = result.data.serverPort,
                                    sessionToken = result.data.token
                                )
                            )
                        }
                        loadServerAddress()
                    }
                    is ResultState.Error -> {
                        updateState { copy(isLoading = false, errorMessage = result.throwable.message) }
                        sendEffect(DashboardEffect.ShowError(result.throwable.message ?: "Failed to start server"))
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private suspend fun handleStopServer() {
        stopServerUseCase(Unit)
            .onEach { result ->
                when (result) {
                    is ResultState.Success -> {
                        updateState {
                            copy(
                                isLoading = false,
                                serverStatus = ServerStatus.Stopped,
                                serverAddress = null,
                                qrCodeUrl = null
                            )
                        }
                    }
                    is ResultState.Error -> {
                        sendEffect(DashboardEffect.ShowError(result.throwable.message ?: "Failed to stop server"))
                    }
                    else -> {}
                }
            }
            .launchIn(viewModelScope)
    }

    private fun loadServerAddress() {
        viewModelScope.launch {
            getServerAddressUseCase(Unit)
                .onEach { result ->
                    if (result is ResultState.Success) {
                        updateState { copy(serverAddress = result.data) }
                    }
                }
                .launchIn(viewModelScope)
        }
    }

    private suspend fun handleGenerateQrCode() {
        generateQrCodeUseCase(Unit)
            .onEach { result ->
                when (result) {
                    is ResultState.Success -> {
                        updateState { copy(qrCodeUrl = result.data) }
                        sendEffect(DashboardEffect.ShowQrCode(result.data))
                    }
                    is ResultState.Error -> {
                        sendEffect(DashboardEffect.ShowError(result.throwable.message ?: "Failed to generate QR code"))
                    }
                    else -> {}
                }
            }
            .launchIn(viewModelScope)
    }
}
