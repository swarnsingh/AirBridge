package com.swaran.airbridge.domain.repository

import com.swaran.airbridge.domain.model.SessionInfo

interface SessionRepository {
    suspend fun generateSession(): SessionInfo
    fun validateSession(token: String): Boolean
    suspend fun invalidateSession(token: String)
    suspend fun getSession(token: String): SessionInfo?
    suspend fun cleanupExpiredSessions()
}
