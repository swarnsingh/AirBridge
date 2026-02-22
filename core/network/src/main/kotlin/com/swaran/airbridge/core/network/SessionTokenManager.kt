package com.swaran.airbridge.core.network

import com.swaran.airbridge.domain.model.SessionInfo
import com.swaran.airbridge.domain.repository.SessionRepository
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SessionTokenManager handles secure token generation and lifecycle management.
 * 
 * Implements P3 Security Recommendations:
 * - Uses SecureRandom for unguessable tokens.
 * - Implements TTL (Time-To-Live) for session expiration.
 * - Provides centralized validation for all network endpoints.
 */
@Singleton
class SessionTokenManager @Inject constructor(
    private val ipAddressProvider: IpAddressProvider
) : SessionRepository {

    private val sessions = ConcurrentHashMap<String, SessionInfo>()
    private val random = SecureRandom()

    companion object {
        private const val SESSION_DURATION_MS = 30 * 60 * 1000L // 30 minute TTL
        private const val TOKEN_BYTES = 16 // Higher entropy
    }

    override suspend fun generateSession(): SessionInfo {
        cleanupExpiredSessions()

        val token = generateSecureToken()
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
        if (session.isExpired()) {
            sessions.remove(token)
            return false
        }
        return true
    }

    override suspend fun invalidateSession(token: String) {
        sessions.remove(token)
    }

    override suspend fun getSession(token: String): SessionInfo? {
        val session = sessions[token]
        return if (session != null && !session.isExpired()) {
            session
        } else {
            if (session != null) sessions.remove(token)
            null
        }
    }

    override suspend fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { it.value.expiryTime < now }
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
