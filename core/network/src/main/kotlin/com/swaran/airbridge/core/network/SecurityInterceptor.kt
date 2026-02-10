package com.swaran.airbridge.core.network

import fi.iki.elonen.NanoHTTPD
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurityInterceptor @Inject constructor() {

    fun isLocalNetworkRequest(session: NanoHTTPD.IHTTPSession): Boolean {
        val remoteIp = session.remoteIpAddress ?: return false
        return isLocalAddress(remoteIp)
    }

    private fun isLocalAddress(ip: String): Boolean {
        return try {
            val address = InetAddress.getByName(ip)
            address.isLoopbackAddress ||
            isPrivateIp(ip) ||
            isLinkLocalAddress(address)
        } catch (e: Exception) {
            false
        }
    }

    private fun isPrivateIp(ip: String): Boolean {
        return ip.startsWith("10.") ||
               ip.startsWith("192.168.") ||
               ip.startsWith("172.") && ip.substring(4, 6).toIntOrNull()?.let { it in 16..31 } == true
    }

    private fun isLinkLocalAddress(address: InetAddress): Boolean {
        return address.isLinkLocalAddress || address.isSiteLocalAddress
    }
}
