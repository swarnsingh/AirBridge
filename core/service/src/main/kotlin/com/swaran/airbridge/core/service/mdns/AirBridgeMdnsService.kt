package com.swaran.airbridge.core.service.mdns

import com.swaran.airbridge.core.common.logging.AirLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * mDNS service broadcaster for AirBridge.
 *
 * Advertises the AirBridge HTTP server on the local network using Bonjour/mDNS,
 * allowing clients to connect via `http://airbridge.local:8081` instead of IP address.
 *
 * Features:
 * - Registers `_airbridge._tcp.local.` service
 * - Auto-discovers local IP address
 * - Graceful shutdown on service stop
 * - Coroutine-managed lifecycle
 *
 * @param logger Structured logging
 */
@Singleton
class AirBridgeMdnsService @Inject constructor(
    private val logger: AirLogger
) {
    companion object {
        private const val TAG = "AirBridgeMdns"
        private const val SERVICE_TYPE = "_http._tcp.local."
        private const val SERVICE_NAME = "AirBridge"
        private const val PROP_SERVER = "server"
        private const val PROP_VERSION = "version"
        private const val VERSION = "1.0"
    }

    private var jmDNS: JmDNS? = null
    private var serviceInfo: ServiceInfo? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Start broadcasting the AirBridge mDNS service.
     *
     * @param port The port the HTTP server is running on (e.g., 8081)
     * @param localIp Optional local IP address. If null, will attempt auto-detection.
     * @return true if service started successfully
     */
    fun start(port: Int, localIp: String? = null): Boolean {
        if (jmDNS != null) {
            logger.d(TAG, "start", "mDNS already running")
            return true
        }

        return try {
            val address = localIp?.let { InetAddress.getByName(it) }
                ?: getLocalIpAddress()
                ?: run {
                    logger.w(TAG, "start", "Could not determine local IP address")
                    return false
                }

            logger.i(TAG, "start", "Starting mDNS service on $address:$port")

            jmDNS = JmDNS.create(address, SERVICE_NAME)

            serviceInfo = ServiceInfo.create(
                SERVICE_TYPE,
                SERVICE_NAME,
                port,
                "AirBridge LAN File Sharing"
            ).apply {
                setText(mapOf(
                    PROP_SERVER to "AirBridge",
                    PROP_VERSION to VERSION,
                    "path" to "/",
                    "protocol" to "http"
                ))
            }

            jmDNS?.registerService(serviceInfo)

            logger.i(TAG, "start", "mDNS service registered: $SERVICE_NAME.$SERVICE_TYPE at $address:$port")
            true
        } catch (e: Exception) {
            logger.e(TAG, "start", "Failed to start mDNS service", e)
            // Cleanup partial state in background
            serviceScope.launch { stop() }
            false
        }
    }

    /**
     * Stop the mDNS service and unregister from the network.
     * Runs on IO dispatcher to avoid blocking the calling thread.
     */
    suspend fun stop() {
        withContext(Dispatchers.IO) {
            logger.d(TAG, "stop", "Stopping mDNS service")

            try {
                serviceInfo?.let { info ->
                    jmDNS?.unregisterService(info)
                    logger.d(TAG, "stop", "Unregistered service: ${info.name}")
                }
            } catch (e: Exception) {
                logger.w(TAG, "stop", "Error unregistering service", e)
            }

            try {
                jmDNS?.close()
                logger.d(TAG, "stop", "JmDNS closed")
            } catch (e: Exception) {
                logger.w(TAG, "stop", "Error closing JmDNS", e)
            }

            jmDNS = null
            serviceInfo = null
        }
    }

    /**
     * Returns true if the mDNS service is currently running.
     */
    fun isRunning(): Boolean = jmDNS != null

    /**
     * Get the mDNS hostname that can be used to access the service.
     *
     * @return The .local hostname (e.g., "AirBridge.local") or null if not running
     */
    fun getMdnsHostname(): String? {
        return if (jmDNS != null) "$SERVICE_NAME.local" else null
    }

    /**
     * Attempt to find the local IP address suitable for mDNS.
     * Prefers Wi-Fi interfaces over mobile data.
     */
    private fun getLocalIpAddress(): InetAddress? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            var wifiAddress: InetAddress? = null
            var fallbackAddress: InetAddress? = null

            interfaces.asSequence().forEach { networkInterface ->
                // Skip loopback and inactive interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) return@forEach

                // Check interface name for Wi-Fi indicators
                val isWifi = networkInterface.displayName.contains("wlan", ignoreCase = true) ||
                        networkInterface.displayName.contains("wifi", ignoreCase = true) ||
                        networkInterface.name.startsWith("wl", ignoreCase = true)

                networkInterface.inetAddresses.asSequence()
                    .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                    .forEach { address ->
                        if (isWifi && wifiAddress == null) {
                            wifiAddress = address
                            logger.d(TAG, "getLocalIp", "Found Wi-Fi address: $address (${networkInterface.displayName})")
                        } else if (fallbackAddress == null) {
                            fallbackAddress = address
                            logger.d(TAG, "getLocalIp", "Found fallback address: $address (${networkInterface.displayName})")
                        }
                    }
            }

            wifiAddress ?: fallbackAddress
        } catch (e: Exception) {
            logger.w(TAG, "getLocalIp", "Failed to get local IP address", e)
            null
        }
    }
}
