package com.swaran.airbridge.core.network

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for QrCodeGenerator (Robolectric)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class QrCodeGeneratorTest {

    @Test
    fun `generateQrCode returns non-null bitmap`() {
        val generator = QrCodeGenerator()
        val result = generator.generateQrCode("airbridge://pair?id=test")

        assertNotNull(result)
    }

    @Test
    fun `generateQrCode throws on empty string`() {
        val generator = QrCodeGenerator()

        assertThrows(IllegalArgumentException::class.java) {
            generator.generateQrCode("")
        }
    }
}