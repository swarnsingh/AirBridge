package com.swaran.airbridge.core.service.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.core.network.upload.UploadScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors network connectivity and pauses/resumes uploads accordingly.
 *
 * - WiFi connected → uploads resume
 * - WiFi disconnected → uploads pause (wait for reconnect)
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadScheduler: UploadScheduler,
    private val logger: AirLogger
) {
    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var wasWifiConnected = true

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val isWifi = isWifiNetwork(network)
            logger.d(TAG, "log", "Network available: isWifi=$isWifi")
            if (isWifi && !wasWifiConnected) {
                logger.i(TAG, "log", "WiFi reconnected - resuming uploads")
                uploadScheduler.resumeAll()
                wasWifiConnected = true
            }
        }

        override fun onLost(network: Network) {
            val isWifi = isWifiNetwork(network)
            logger.d(TAG, "log", "Network lost: isWifi=$isWifi")
            if (isWifi) {
                logger.w(TAG, "log", "WiFi disconnected - pausing uploads")
                uploadScheduler.pauseAll()
                wasWifiConnected = false
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Optional: monitor bandwidth changes
        }
    }

    fun startMonitoring() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        connectivityManager.registerNetworkCallback(request, networkCallback)
        wasWifiConnected = isWifiCurrentlyConnected()
        logger.i(TAG, "log", "Network monitoring started. WiFi connected: $wasWifiConnected")
    }

    fun stopMonitoring() {
        connectivityManager.unregisterNetworkCallback(networkCallback)
        logger.i(TAG, "log", "Network monitoring stopped")
    }

    private fun isWifiNetwork(network: Network): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isWifiCurrentlyConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        return isWifiNetwork(network)
    }
}
