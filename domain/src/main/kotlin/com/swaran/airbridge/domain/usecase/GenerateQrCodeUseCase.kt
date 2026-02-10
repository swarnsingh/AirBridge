package com.swaran.airbridge.domain.usecase

import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.common.ResultState
import com.swaran.airbridge.core.common.UseCase
import com.swaran.airbridge.domain.model.ServerStatus
import com.swaran.airbridge.domain.repository.ServerRepository
import com.swaran.airbridge.domain.repository.SessionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class GenerateQrCodeUseCase @Inject constructor(
    private val serverRepository: ServerRepository,
    private val sessionRepository: SessionRepository,
    @Dispatcher(AirDispatchers.IO) dispatcher: CoroutineDispatcher
) : UseCase<Unit, String, String>(dispatcher) {

    override fun mapToDomain(data: String): String = data

    override suspend fun execute(parameters: Unit): Flow<ResultState<out String>> =
        fetchResponse {
            // Get the current server status to check if running
            val status = serverRepository.getServerStatus().first()
            
            if (status is ServerStatus.Running) {
                // Use the existing session token from the running server
                val address = serverRepository.getServerAddress() ?: "http://localhost:8080"
                "$address/?token=${status.sessionToken}"
            } else {
                // Server not running, generate new session
                val session = sessionRepository.generateSession()
                val address = serverRepository.getServerAddress() ?: "http://${session.serverAddress}:8080"
                "$address/?token=${session.token}"
            }
        }
}
