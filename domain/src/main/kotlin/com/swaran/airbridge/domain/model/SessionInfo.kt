package com.swaran.airbridge.domain.model

data class SessionInfo(
    val token: String,
    val expiryTime: Long,
    val serverAddress: String,
    val serverPort: Int
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expiryTime
}
