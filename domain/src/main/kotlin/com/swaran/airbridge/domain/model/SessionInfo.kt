package com.swaran.airbridge.domain.model

/**
 * Session information for browser authentication.
 *
 * Created during QR code pairing. Token must be included in all HTTP requests.
 *
 * @property token Random session token (e.g., "a3f7d2e1-8c5b-4d6e-9f0a-1b2c3d4e5f6")
 * @property expiryTime Token expiration timestamp (Unix millis)
 * @property serverAddress Local IP address (e.g., "192.168.1.5")
 * @property serverPort HTTP port (default: 8080)
 */
data class SessionInfo(
    val token: String,
    val expiryTime: Long,
    val serverAddress: String,
    val serverPort: Int
) {
    /**
     * Check if this session has expired.
     *
     * @return true if current time > expiryTime
     */
    fun isExpired(): Boolean = System.currentTimeMillis() > expiryTime
}
