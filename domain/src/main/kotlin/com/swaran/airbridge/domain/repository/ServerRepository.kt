package com.swaran.airbridge.domain.repository

import com.swaran.airbridge.domain.model.ServerStatus
import com.swaran.airbridge.domain.model.SessionInfo
import kotlinx.coroutines.flow.Flow

interface ServerRepository {
    fun getServerStatus(): Flow<ServerStatus>
    suspend fun startServer(port: Int = 8080): Result<SessionInfo>
    suspend fun stopServer(): Result<Unit>
    fun getServerAddress(): String?
}
