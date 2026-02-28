package com.swaran.airbridge.core.service

import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.core.network.ktor.KtorLocalServer
import com.swaran.airbridge.domain.model.ServerStatus
import com.swaran.airbridge.domain.model.SessionInfo
import com.swaran.airbridge.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [ServerRepository] using Ktor HTTP server.
 *
 * ## Architecture
 *
 * This repository manages the Ktor server's lifecycle through [KtorLocalServer].
 * It coordinates with [ServiceController] to ensure the foreground service
 * stays active while the server is running.
 *
 * ## Port Selection
 *
 * The server runs on port 8081 by default. This can be configured via
 * [ServerPreferences].
 *
 * @see KtorLocalServer Ktor CIO server implementation
 * @see ForegroundServerService Foreground service for background operation
 */
@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val ktorLocalServer: KtorLocalServer,
    private val serverPreferences: ServerPreferences,
    private val sessionTokenManager: SessionTokenManager,
    private val serviceController: ServiceController
) : ServerRepository {

    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)

    companion object {
        private const val DEFAULT_PORT = 8081
    }

    override fun getServerStatus(): Flow<ServerStatus> = _status.asStateFlow()

    override suspend fun startServer(port: Int): Result<SessionInfo> {
        return try {
            val session = sessionTokenManager.generateSession()
            val portToUse = serverPreferences.getLastPort().takeIf { it > 0 } ?: DEFAULT_PORT

            val success = ktorLocalServer.start(portToUse)

            if (success) {
                serverPreferences.saveLastPort(portToUse)
                serviceController.startServer()
                val address = ktorLocalServer.getLocalIpAddress() ?: "localhost"
                _status.value = ServerStatus.Running(
                    address = address,
                    port = portToUse,
                    sessionToken = session.token
                )
                Result.success(session.copy(serverAddress = address, serverPort = portToUse))
            } else {
                Result.failure(Exception("Failed to start Ktor server on port $portToUse"))
            }
        } catch (e: Exception) {
            _status.value = ServerStatus.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    override suspend fun stopServer(): Result<Unit> {
        return try {
            ktorLocalServer.stop()
            serviceController.stopServer()
            _status.value = ServerStatus.Stopped
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getServerAddress(): String? {
        val port = serverPreferences.getLastPort().takeIf { it > 0 } ?: DEFAULT_PORT
        return ktorLocalServer.getLocalIpAddress()?.let { address ->
            val cleanAddress = address.substringBefore("%") // Remove interface suffix like %wlan0
            "http://$cleanAddress:$port"
        }
    }
}
