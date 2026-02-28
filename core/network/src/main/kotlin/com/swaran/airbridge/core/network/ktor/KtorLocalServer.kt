package com.swaran.airbridge.core.network.ktor

import android.content.Context
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.core.network.LocalServer
import com.swaran.airbridge.core.network.IpAddressProvider
import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.core.network.ktor.routes.StaticRoutes
import com.swaran.airbridge.core.network.ktor.routes.FileRoutes
import com.swaran.airbridge.core.network.ktor.routes.UploadRoutes
import com.swaran.airbridge.core.network.ktor.routes.PairingPushZipRoutes
import com.swaran.airbridge.core.network.ktor.routes.healthRoute
import com.swaran.airbridge.domain.repository.StorageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ktor-based HTTP server for AirBridge (CIO engine).
 *
 * This is the primary HTTP server implementation for AirBridge.
 * It runs on port 8081 by default.
 *
 * ## Key Features
 *
 * | Feature | Ktor (this) |
 * |---------|-------------|
 * | Resume support | ✅ Native |
 * | Streaming | ✅ Coroutine-based |
 * | Range requests | ✅ Automatic |
 * | Backpressure | ✅ Built-in |
 *
 * ## Architecture
 *
 * - Uses CIO engine for non-blocking I/O
 * - Structured concurrency with proper cancellation
 * - File-level locking prevents concurrent writes
 * - State machine for upload lifecycle
 *
 * @param context Application context for assets and resources
 * @param storageRepository File storage abstraction (SAF/MediaStore)
 * @param sessionTokenManager Session validation
 * @param ipAddressProvider IP address for QR code generation
 */
@Singleton
class KtorLocalServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageRepository: StorageRepository,
    private val sessionTokenManager: SessionTokenManager,
    private val ipAddressProvider: IpAddressProvider,
    private val staticRoutes: StaticRoutes,
    private val fileRoutes: FileRoutes,
    private val uploadRoutes: UploadRoutes,
    private val pairingPushZipRoutes: PairingPushZipRoutes,
    private val logger: AirLogger
) : LocalServer {

    private var engine: io.ktor.server.engine.EmbeddedServer<*, *>? = null
    private var currentPort: Int = -1

    companion object {
        private const val TAG = "KtorLocalServer"
    }

    /**
     * Starts the Ktor HTTP server on the specified port.
     *
     * If already running on the requested port, returns true immediately.
     * If running on a different port, stops and restarts on the new port.
     *
     * @param port The port to listen on (8081 for parallel testing, 8080 for production)
     * @return true if started successfully or already running on requested port
     */
    override fun start(port: Int): Boolean {
        if (engine != null && currentPort == port) {
            logger.d(TAG, "log", "Already running on port $port")
            return true
        }

        // Stop if running on different port
        if (engine != null) {
            stop()
        }

        return try {
            engine = embeddedServer(
                CIO,
                host = "0.0.0.0",
                port = port
            ) {
                // JSON serialization
                install(ContentNegotiation) {
                    json(Json {
                        prettyPrint = false
                        isLenient = true
                    })
                }

                // CORS for browser clients
                install(CORS) {
                    anyHost()
                    allowHeader(HttpHeaders.ContentType)
                    allowHeader(HttpHeaders.Range)
                    allowHeader(HttpHeaders.Authorization)
                    allowMethod(HttpMethod.Get)
                    allowMethod(HttpMethod.Post)
                    allowMethod(HttpMethod.Put)
                    allowMethod(HttpMethod.Delete)
                }

                // Note: CallLogging plugin omitted in Phase 1 for simplicity
                // Can be added later with: install(CallLogging)

                routing {
                    // Phase 2: Health and static routes (with Cache-Control: no-store)
                    healthRoute()
                    staticRoutes.install(this)

                    // Phase 3: File browse and download with Range support
                    fileRoutes.install(this)

                    // Phase 4: Upload routes with streaming and client-driven resume
                    uploadRoutes.install(this)

                    // Phase 5: Pairing, Push, and Zip routes
                    pairingPushZipRoutes.install(this)
                }
            }.start(wait = false)

            currentPort = port
            logger.i(TAG, "log", "Ktor server started on port $port (CIO engine)")
            true
        } catch (e: Exception) {
            logger.e(TAG, "log", "Failed to start server on port $port", e)
            engine = null
            currentPort = -1
            false
        }
    }

    /**
     * Stops the Ktor server gracefully.
     *
     * Waits up to 2 seconds for active connections to complete.
     * Uploads/downloads in progress will be interrupted.
     */
    override fun stop() {
        try {
            engine?.stop(gracePeriodMillis = 1000, timeoutMillis = 2000)
            logger.i(TAG, "log", "Ktor server stopped")
        } catch (e: Exception) {
            logger.w(TAG, "log", "Error stopping server", e)
        } finally {
            engine = null
            currentPort = -1
        }
    }

    /**
     * Returns true if the server is currently running.
     */
    override fun isRunning(): Boolean = engine != null

    /**
     * Gets the device's local IP address for QR code generation.
     *
     * @return IP string like "192.168.1.12" or null if unavailable
     */
    override fun getLocalIpAddress(): String? = ipAddressProvider.getLocalIpAddress()

    /**
     * Returns true because Ktor has native, reliable HTTP Range support.
     *
     * This enables the UI to show pause/resume controls for downloads.
     */
    override fun supportsResume(): Boolean = true

    /**
     * Returns the current port the server is running on, or -1 if not running.
     */
    fun getCurrentPort(): Int = currentPort
}
