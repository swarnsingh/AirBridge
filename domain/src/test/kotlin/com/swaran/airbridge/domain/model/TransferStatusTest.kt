package com.swaran.airbridge.domain.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for TransferStatus enum
 */
class TransferStatusTest {

    @Test
    fun `transfer status values are correct`() {
        assertEquals("uploading", TransferStatus.UPLOADING.value)
        assertEquals("completed", TransferStatus.COMPLETED.value)
        assertEquals("paused", TransferStatus.PAUSED.value)
        assertEquals("pausing", TransferStatus.PAUSING.value)
        assertEquals("resuming", TransferStatus.RESUMING.value)
        assertEquals("queued", TransferStatus.QUEUED.value)
        assertEquals("cancelled", TransferStatus.CANCELLED.value)
        assertEquals("interrupted", TransferStatus.INTERRUPTED.value)
        assertEquals("error", TransferStatus.ERROR.value)
    }

    @Test
    fun `fromValue returns correct enum`() {
        assertEquals(TransferStatus.UPLOADING, TransferStatus.fromValue("uploading"))
        assertEquals(TransferStatus.COMPLETED, TransferStatus.fromValue("completed"))
        assertEquals(TransferStatus.PAUSED, TransferStatus.fromValue("paused"))
        assertEquals(TransferStatus.ERROR, TransferStatus.fromValue("error"))
    }

    @Test
    fun `fromValue returns error for unknown value`() {
        assertEquals(TransferStatus.ERROR, TransferStatus.fromValue("unknown"))
        assertEquals(TransferStatus.ERROR, TransferStatus.fromValue(null))
        assertEquals(TransferStatus.ERROR, TransferStatus.fromValue(""))
    }

    @Test
    fun `all transfer statuses have unique values`() {
        val values = TransferStatus.entries.map { it.value }
        val uniqueValues = values.toSet()
        
        assertEquals(values.size, uniqueValues.size)
    }

    @Test
    fun `all values are non-empty`() {
        TransferStatus.entries.forEach { status ->
            assertTrue(status.value.isNotBlank())
        }
    }

    @Test
    fun `companion object fromValue works for all entries`() {
        TransferStatus.entries.forEach { status ->
            assertEquals(status, TransferStatus.fromValue(status.value))
        }
    }
}
