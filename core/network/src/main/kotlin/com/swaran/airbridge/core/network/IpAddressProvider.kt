package com.swaran.airbridge.core.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IpAddressProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun getLocalIpAddress(): String? {
        // First try WifiManager for WiFi IP (most reliable)
        val wifiIp = getWifiIpAddress()
        if (wifiIp != null) return wifiIp

        // Fallback to NetworkInterface
        return getNetworkInterfaceIp()
    }

    private fun getWifiIpAddress(): String? {
        return try {
            val wifiManager = ContextCompat.getSystemService(context, WifiManager::class.java)
            val connectionInfo = wifiManager?.connectionInfo
            
            if (connectionInfo != null && connectionInfo.ipAddress != 0) {
                val ip = connectionInfo.ipAddress
                // Handle byte order properly
                val ipInt = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                    Integer.reverseBytes(ip)
                } else {
                    ip
                }
                InetAddress.getByAddress(
                    byteArrayOf(
                        (ipInt shr 24 and 0xFF).toByte(),
                        (ipInt shr 16 and 0xFF).toByte(),
                        (ipInt shr 8 and 0xFF).toByte(),
                        (ipInt and 0xFF).toByte()
                    )
                ).hostAddress
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getNetworkInterfaceIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            var bestAddress: String? = null
            
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                // Skip mobile data, VPN interfaces
                if (name.contains("rmnet") || name.contains("p2p") || name.contains("vpn")) continue
                
                for (addr in intf.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    if (!addr.isSiteLocalAddress) continue
                    if (addr.hostAddress?.contains(":") == true) continue // IPv6
                    
                    val hostAddr = addr.hostAddress?.substringBefore("%")
                    
                    // Prefer WiFi/Ethernet interfaces
                    if (name.contains("wlan") || name.contains("wifi") || name.contains("eth")) {
                        return hostAddr
                    }
                    if (bestAddress == null) {
                        bestAddress = hostAddr
                    }
                }
            }
            bestAddress
        } catch (e: Exception) {
            null
        }
    }
}
