package com.swaran.airbridge.core.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for IpAddressProvider (Robolectric)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class IpAddressProviderTest {

    @Test
    fun `getLocalIpAddress returns null or valid IP`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = IpAddressProvider(context)
        val ip = provider.getLocalIpAddress()

        // Either null or a valid IP address string
        if (ip != null) {
            assertTrue(ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")))
        }
    }
}