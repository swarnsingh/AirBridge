package com.swaran.airbridge.core.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for SessionTokenManager (Robolectric)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class SessionTokenManagerTest {

    private fun createManager(): SessionTokenManager {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return SessionTokenManager(IpAddressProvider(context))
    }

    @Test
    fun `validateSession returns true for existing token`() = runBlocking {
        val manager = createManager()
        val session = manager.generateSession()

        assertTrue(manager.validateSession(session.token))
    }

    @Test
    fun `validateSession returns false for invalid token`() {
        val manager = createManager()

        assertFalse(manager.validateSession("invalid-token"))
    }

    @Test
    fun `getSession returns correct session`() = runBlocking {
        val manager = createManager()
        val session = manager.generateSession()

        val fetched = manager.getSession(session.token)

        assertNotNull(fetched)
        assertEquals(session.token, fetched?.token)
    }

    @Test
    fun `invalidateSession removes token`() = runBlocking {
        val manager = createManager()
        val session = manager.generateSession()

        assertTrue(manager.validateSession(session.token))

        manager.invalidateSession(session.token)

        assertFalse(manager.validateSession(session.token))
    }
}