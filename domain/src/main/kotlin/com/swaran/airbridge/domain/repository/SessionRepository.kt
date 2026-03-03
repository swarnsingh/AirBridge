package com.swaran.airbridge.domain.repository

import com.swaran.airbridge.domain.model.SessionInfo

/**
 * Repository for session management and token validation.
 *
 * Handles creation, validation, and cleanup of browser session tokens.
 * Sessions are created during QR code pairing and validated on each request.
 *
 * ## Session Lifecycle
 *
 * 1. **Generate**: Create new session during pairing (with expiration)
 * 2. **Validate**: Check token on every HTTP request
 * 3. **Invalidate**: Explicitly revoke session on logout
 * 4. **Cleanup**: Periodic removal of expired sessions
 *
 * @see com.swaran.airbridge.core.network.SessionTokenManager Implementation
 */
interface SessionRepository {

    /**
     * Generate a new session with random token.
     *
     * @return SessionInfo containing token and metadata
     */
    suspend fun generateSession(): SessionInfo

    /**
     * Validate if a session token is active and not expired.
     *
     * @param token Session token from browser
     * @return true if valid and active, false otherwise
     */
    fun validateSession(token: String): Boolean

    /**
     * Invalidate (revoke) a session token.
     *
     * @param token Session token to revoke
     */
    suspend fun invalidateSession(token: String)

    /**
     * Get session info for a token.
     *
     * @param token Session token
     * @return SessionInfo if found and valid, null otherwise
     */
    suspend fun getSession(token: String): SessionInfo?

    /**
     * Clean up expired sessions.
     *
     * Should be called periodically to remove stale sessions.
     */
    suspend fun cleanupExpiredSessions()
}
