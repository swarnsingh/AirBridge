package com.swaran.airbridge.domain.repository

import com.swaran.airbridge.domain.model.ServerStatus
import com.swaran.airbridge.domain.model.SessionInfo
import kotlinx.coroutines.flow.Flow

/**
 * Repository for controlling the HTTP server lifecycle.
 *
 * Manages the embedded Ktor server that handles browser requests.
 * Provides reactive status updates via [Flow].
 *
 * ## Server Lifecycle
 *
 * 1. **Start**: Start Ktor server on specified port (default 8080)
 * 2. **Status**: Monitor running/stopped state via Flow
 * 3. **Address**: Get local network address for QR code
 * 4. **Stop**: Graceful shutdown
 *
 * @see com.swaran.airbridge.core.service.ServerRepositoryImpl Implementation
 */
interface ServerRepository {

    /**
     * Get reactive server status stream.
     *
     * Emits [ServerStatus.Running] or [ServerStatus.Stopped] changes.
     */
    fun getServerStatus(): Flow<ServerStatus>

    /**
     * Start the HTTP server.
     *
     * @param port Port number (default: 8080)
     * @return Result containing SessionInfo on success
     */
    suspend fun startServer(port: Int = 8080): Result<SessionInfo>

    /**
     * Stop the HTTP server.
     *
     * @return Result containing Unit on success
     */
    suspend fun stopServer(): Result<Unit>

    /**
     * Get the local network address for server access.
     *
     * @return IP address string (e.g., "192.168.1.5"), or null if not running
     */
    fun getServerAddress(): String?
}
