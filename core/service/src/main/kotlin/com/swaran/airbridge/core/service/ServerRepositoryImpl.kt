package com.swaran.airbridge.core.service

import com.swaran.airbridge.core.network.LocalHttpServer
import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.domain.model.ServerStatus
import com.swaran.airbridge.domain.model.SessionInfo
import com.swaran.airbridge.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val localHttpServer: LocalHttpServer,
    private val sessionTokenManager: SessionTokenManager,
    private val serviceController: ServiceController
) : ServerRepository {

    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Stopped)

    override fun getServerStatus(): Flow<ServerStatus> = _status.asStateFlow()

    override suspend fun startServer(port: Int): Result<SessionInfo> {
        return try {
            val session = sessionTokenManager.generateSession()
            val success = localHttpServer.start(port)

            if (success) {
                serviceController.startServer()
                _status.value = ServerStatus.Running(
                    address = localHttpServer.getLocalIpAddress() ?: "localhost",
                    port = port,
                    sessionToken = session.token
                )
                Result.success(session)
            } else {
                Result.failure(Exception("Failed to start server"))
            }
        } catch (e: Exception) {
            _status.value = ServerStatus.Error(e.message ?: "Unknown error")
            Result.failure(e)
        }
    }

    override suspend fun stopServer(): Result<Unit> {
        return try {
            localHttpServer.stop()
            serviceController.stopServer()
            _status.value = ServerStatus.Stopped
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getServerAddress(): String? {
        return localHttpServer.getLocalIpAddress()?.let { address ->
            val cleanAddress = address.substringBefore("%") // Remove interface suffix like %wlan0
            "http://$cleanAddress:8080"
        }
    }
}
