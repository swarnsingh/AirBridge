package com.swaran.airbridge.core.network

import com.swaran.airbridge.domain.model.SessionInfo
import com.swaran.airbridge.domain.repository.SessionRepository
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionTokenManager @Inject constructor(
    private val ipAddressProvider: IpAddressProvider
) : SessionRepository {

    private val sessions = mutableMapOf<String, SessionInfo>()
    private val random = SecureRandom()

    companion object {
        private const val SESSION_DURATION_MS = 30 * 60 * 1000L // 30 minutes
        private const val TOKEN_LENGTH = 8 // 8 bytes = ~11 chars in Base64
    }

    override suspend fun generateSession(): SessionInfo {
        cleanupExpiredSessions()

        val token = generateToken()
        val serverAddress = ipAddressProvider.getLocalIpAddress() ?: "localhost"
        val session = SessionInfo(
            token = token,
            expiryTime = System.currentTimeMillis() + SESSION_DURATION_MS,
            serverAddress = serverAddress,
            serverPort = 8080
        )
        sessions[token] = session
        return session
    }

    override fun validateSession(token: String): Boolean {
        val session = sessions[token] ?: return false
        return !session.isExpired()
    }

    override suspend fun invalidateSession(token: String) {
        sessions.remove(token)
    }

    override suspend fun getSession(token: String): SessionInfo? {
        return sessions[token]?.takeIf { !it.isExpired() }
    }

    override suspend fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { it.value.expiryTime < now }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_LENGTH)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
