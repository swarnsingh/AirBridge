package com.swaran.airbridge.app.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.ServerStatus
import com.swaran.airbridge.domain.repository.ServerRepository
import com.swaran.airbridge.domain.usecase.StartServerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val startServerUseCase: StartServerUseCase,
    private val logger: AirLogger,
    @param:Dispatcher(AirDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    companion object {
        private const val TAG = "PairingViewModel"
    }

    sealed class PairingState {
        data object Idle : PairingState()
        data object StartingServer : PairingState()
        data object Approving : PairingState()
        data object Success : PairingState()
        data class Error(val message: String) : PairingState()
    }

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    fun approvePairing(pairingId: String, serverUrl: String) {
        viewModelScope.launch {
            try {
                // Check if server is running
                val currentStatus = serverRepository.getServerStatus().first()
                val isRunning = currentStatus is ServerStatus.Running

                // Auto-start server if needed
                if (!isRunning) {
                    logger.d(TAG, "approvePairing", "Auto-starting server for pairing")
                    _state.value = PairingState.StartingServer

                    startServerUseCase(StartServerUseCase.Params()).collect { result ->
                        logger.d(TAG, "approvePairing", "Server start result: $result")
                    }
                }

                _state.value = PairingState.Approving

                // Send approval request on IO dispatcher
                val code = withContext(ioDispatcher) {
                    val approveUrl = "$serverUrl/api/pair/approve"
                    logger.d(TAG, "approvePairing", "Sending approve request to: $approveUrl")

                    val url = URL(approveUrl)
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        connectTimeout = 10000
                        readTimeout = 10000
                    }

                    val body = """{"pairingId":"$pairingId"}"""
                    connection.outputStream.use { os ->
                        OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                            writer.write(body)
                            writer.flush()
                        }
                    }

                    connection.responseCode
                }
                logger.d(TAG, "approvePairing", "Response code: $code")

                if (code in 200..299) {
                    _state.value = PairingState.Success
                } else {
                    _state.value = PairingState.Error("Server returned $code")
                }

            } catch (e: Exception) {
                logger.e(TAG, "approvePairing", "Pairing error", e)
                _state.value = PairingState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun resetState() {
        _state.value = PairingState.Idle
    }
}
