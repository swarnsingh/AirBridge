package com.swaran.airbridge.domain.model

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for UploadResult sealed class
 */
class UploadResultTest {

    @Test
    fun `success result contains upload details`() {
        val result = UploadResult.Success(
            uploadId = "upload-123",
            bytesReceived = 10000,
            fileId = "content://media/file/123"
        )

        assertEquals("upload-123", result.uploadId)
        assertEquals(10000, result.bytesReceived)
        assertEquals("content://media/file/123", result.fileId)
    }

    @Test
    fun `busy result contains retry information`() {
        val result = UploadResult.Busy(
            uploadId = "upload-456",
            retryAfterMs = 200
        )

        assertEquals("upload-456", result.uploadId)
        assertEquals(200, result.retryAfterMs)
    }

    @Test
    fun `paused result contains progress`() {
        val result = UploadResult.Paused(
            uploadId = "upload-789",
            bytesReceived = 5000
        )

        assertEquals("upload-789", result.uploadId)
        assertEquals(5000, result.bytesReceived)
    }

    @Test
    fun `cancelled result has only uploadId`() {
        val result = UploadResult.Cancelled(uploadId = "upload-abc")

        assertEquals("upload-abc", result.uploadId)
    }

    @Test
    fun `offset mismatch result contains disk size`() {
        val result = UploadResult.Failure.OffsetMismatch(
            uploadId = "upload-def",
            bytesReceived = 3000,
            expectedOffset = 4000,
            actualDiskSize = 3000
        )

        assertEquals("upload-def", result.uploadId)
        assertEquals(3000, result.bytesReceived)
        assertEquals(4000, result.expectedOffset)
        assertEquals(3000, result.actualDiskSize)
    }

    @Test
    fun `file deleted result indicates external deletion`() {
        val result = UploadResult.Failure.FileDeleted(
            uploadId = "upload-ghi",
            bytesReceived = 1000
        )

        assertEquals("upload-ghi", result.uploadId)
        assertEquals(1000, result.bytesReceived)
    }
}