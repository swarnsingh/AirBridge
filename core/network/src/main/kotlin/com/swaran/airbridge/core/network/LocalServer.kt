package com.swaran.airbridge.core.network

/**
 * Abstraction for the local HTTP server used by AirBridge.
 *
 * This interface provides capability introspection so the UI
 * and domain layers can adapt behavior.
 *
 * ## Implementation
 *
 * [KtorLocalServer] is the sole implementation, using Ktor CIO engine.
 *
 * @see KtorLocalServer Ktor CIO implementation
 */
interface LocalServer {

    /**
     * Starts the HTTP server on the specified port.
     *
     * If the server is already running, returns true immediately.
     * The server binds to 0.0.0.0 (all interfaces) to allow connections
     * from any device on the LAN.
     *
     * @param port The port to listen on (typically 8081)
     * @return true if started successfully or already running, false on error
     */
    fun start(port: Int): Boolean

    /**
     * Stops the HTTP server and releases all resources.
     *
     * This terminates all active connections immediately.
     * Transfers in progress will fail with connection reset.
     */
    fun stop()

    /**
     * Checks if the server is currently running.
     *
     * @return true if the server is accepting connections
     */
    fun isRunning(): Boolean

    /**
     * Gets the device's local IP address for display/QR code.
     *
     * @return IP string like "192.168.1.12" or null if unavailable
     */
    fun getLocalIpAddress(): String?

    /**
     * Returns true if this server implementation supports reliable
     * HTTP Range requests for pause/resume functionality.
     *
     * Ktor: returns true (native Range support with proper backpressure
     * and cancellation)
     *
     * The UI uses this to show/hide pause/resume controls.
     */
    fun supportsResume(): Boolean
}
