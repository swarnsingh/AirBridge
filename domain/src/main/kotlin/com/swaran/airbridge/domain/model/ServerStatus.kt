package com.swaran.airbridge.domain.model

/**
 * Sealed class representing HTTP server status states.
 *
 * Used for UI state management and reactive updates.
 */
sealed class ServerStatus {

    /**
     * Server is stopped.
     */
    data object Stopped : ServerStatus()

    /**
     * Server is running and accepting connections.
     *
     * @property address Local IP address
     * @property port HTTP port
     * @property sessionToken Current session token for authentication
     */
    data class Running(
        val address: String,
        val port: Int,
        val sessionToken: String
    ) : ServerStatus()

    /**
     * Server encountered an error.
     *
     * @property message Error description
     */
    data class Error(val message: String) : ServerStatus()
}
