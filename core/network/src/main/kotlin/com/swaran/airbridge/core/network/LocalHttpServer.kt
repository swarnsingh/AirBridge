package com.swaran.airbridge.core.network

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Embedded HTTP server for LAN file transfers using NanoHTTPD.
 *
 * ## Architecture
 *
 * This class wraps NanoHTTPD and provides a pluggable handler system.
 * Controllers (like [UploadController], [FileController]) register themselves
 * via [registerHandler] to handle specific URL patterns.
 *
 * ## Security
 *
 * All requests first pass through [SecurityInterceptor] which rejects non-LAN
 * requests (anything outside 192.168.x.x, 10.x.x.x, 172.16-31.x.x ranges).
 *
 * ## GZIP Workaround
 *
 * NanoHTTPD 2.3.1 auto-enables gzip for 200 OK responses when the client
 * sends Accept-Encoding: gzip. This breaks JSON parsing in browsers when
 * they receive raw gzip bytes instead of decompressed content.
 *
 * The fix: wrap the response status with a custom IStatus implementation.
 * NanoHTTPD's equals() check fails for custom objects, so gzip is skipped.
 *
 * ## Handler Chain
 *
 * ```
 * HTTP Request
 *     ↓
 * SecurityInterceptor (LAN check)
 *     ↓
 * Find matching RequestHandler
 *     ↓
 * Handler.handle(session) → Response
 *     ↓
 * Wrap status (disable gzip)
 *     ↓
 * Return to client
 * ```
 *
 * ## Timeout
 *
 * Socket read timeout is set to 5 minutes (300s) to accommodate large
 * file uploads over slow Wi-Fi. This is necessary because NanoHTTPD reads
 * the entire request body before calling serve().
 *
 * @param context Application context
 * @param securityInterceptor Validates LAN-only access
 * @param ipAddressProvider Gets device IP for QR code generation
 */
@Singleton
class LocalHttpServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securityInterceptor: SecurityInterceptor,
    private val ipAddressProvider: IpAddressProvider
) {
    private var server: NanoHTTPD? = null
    private val handlers = mutableListOf<RequestHandler>()

    companion object {
        private const val TAG = "LocalHttpServer"
        
        /**
         * 5-minute socket timeout for large file uploads.
         * 
         * NanoHTTPD creates a new thread per connection and reads the entire
         * request body before serve() is called. For a 1GB file at 10MB/s,
         * this takes ~100 seconds. We use 300s as a safe margin.
         */
        private const val SOCKET_READ_TIMEOUT = 300_000
    }

    /**
     * Starts the HTTP server on the specified port.
     *
     * If the server is already running, returns true immediately.
     * The server binds to 0.0.0.0 (all interfaces) to allow connections
     * from any device on the LAN.
     *
     * @param port The port to listen on (typically 8080)
     * @return true if started successfully or already running, false on error
     */
    fun start(port: Int): Boolean {
        if (server != null) return true

        return try {
            server = object : NanoHTTPD("0.0.0.0", port) {
                override fun serve(session: IHTTPSession): Response {
                    // Security check: LAN only
                    if (!securityInterceptor.isLocalNetworkRequest(session)) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "LAN access only")
                    }
                    
                    // Find handler for this request
                    val handler = handlers.find { it.canHandle(session) }
                        ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
                    
                    // Execute handler and catch any errors
                    val response = try {
                        handler.handle(session)
                    } catch (e: Exception) {
                        Log.e(TAG, "Handler failed: ${e.message}")
                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Error")
                    }

                    // Disable auto-gzip by wrapping status
                    // See: https://github.com/NanoHttpd/nanohttpd/issues/356
                    val originalStatus = response.status
                    response.status = object : Response.IStatus {
                        override fun getDescription(): String = originalStatus.description
                        override fun getRequestStatus(): Int = originalStatus.requestStatus
                    }

                    return response
                }
            }.apply { start(SOCKET_READ_TIMEOUT, false) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server on port $port", e)
            false
        }
    }

    /**
     * Stops the HTTP server and releases all resources.
     *
     * This terminates all active connections immediately.
     * Uploads in progress will fail with connection reset.
     */
    fun stop() {
        server?.stop()
        server = null
    }

    /**
     * Checks if the server is currently running.
     *
     * @return true if the server thread is alive
     */
    fun isRunning(): Boolean = server?.isAlive == true

    /**
     * Gets the device's local IP address for display/QR code.
     *
     * @return IP string like "192.168.1.12" or null if unavailable
     */
    fun getLocalIpAddress(): String? = ipAddressProvider.getLocalIpAddress()

    /**
     * Registers a request handler.
     *
     * Handlers are checked in registration order via [RequestHandler.canHandle].
     * The first matching handler processes the request.
     *
     * @param handler The handler to register (e.g., UploadController)
     */
    fun registerHandler(handler: RequestHandler) {
        handlers.add(handler)
    }

    /**
     * Pluggable request handler interface.
     *
     * Controllers implement this to handle specific URL patterns.
     * The canHandle() method should be fast (simple string checks)
     * as it's called for every request.
     */
    interface RequestHandler {
        /**
         * Determines if this handler can process the given request.
         *
         * @param session The HTTP session with URI, headers, params
         * @return true if this handler should process the request
         */
        fun canHandle(session: NanoHTTPD.IHTTPSession): Boolean

        /**
         * Processes the request and returns a response.
         *
         * This runs on a NanoHTTPD worker thread. For long operations
         * (like file uploads), use CompletableFuture to bridge to
         * coroutines without blocking the thread indefinitely.
         *
         * @param session The HTTP session with input stream for uploads
         * @return The HTTP response to send to the client
         */
        fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response
    }
}
