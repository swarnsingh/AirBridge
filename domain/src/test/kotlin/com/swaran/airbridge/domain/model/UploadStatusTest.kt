package com.swaran.airbridge.domain.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for UploadStatus data class
 */
class UploadStatusTest {

    @Test
    fun `upload status calculates progress percent correctly`() {
        val metadata = UploadMetadata(
            uploadId = "test",
            fileUri = "",
            displayName = "file.txt",
            path = "/",
            totalBytes = 1000,
            mimeType = null
        )
        
        val status = UploadStatus(
            metadata = metadata,
            state = UploadState.UPLOADING,
            bytesReceived = 500,
            bytesPerSecond = 100f
        )
        
        assertEquals(50, status.progressPercent)
    }

    @Test
    fun `upload status handles zero total bytes`() {
        val metadata = UploadMetadata(
            uploadId = "test",
            fileUri = "",
            displayName = "file.txt",
            path = "/",
            totalBytes = 0,
            mimeType = null
        )
        
        val status = UploadStatus(
            metadata = metadata,
            state = UploadState.UPLOADING,
            bytesReceived = 0,
            bytesPerSecond = 0f
        )
        
        assertEquals(0, status.progressPercent)
    }

    @Test
    fun `upload status calculates remaining bytes`() {
        val metadata = UploadMetadata(
            uploadId = "test",
            fileUri = "",
            displayName = "file.txt",
            path = "/",
            totalBytes = 1000,
            mimeType = null
        )
        
        val status = UploadStatus(
            metadata = metadata,
            state = UploadState.UPLOADING,
            bytesReceived = 600,
            bytesPerSecond = 100f
        )
        
        assertEquals(400, status.remainingBytes)
    }

    @Test
    fun `remaining bytes is never negative`() {
        val metadata = UploadMetadata(
            uploadId = "test",
            fileUri = "",
            displayName = "file.txt",
            path = "/",
            totalBytes = 1000,
            mimeType = null
        )
        
        val status = UploadStatus(
            metadata = metadata,
            state = UploadState.UPLOADING,
            bytesReceived = 1500, // More than total
            bytesPerSecond = 100f
        )
        
        assertEquals(0, status.remainingBytes) // Coerced to 0
    }

    @Test
    fun `eta seconds calculated correctly`() {
        val metadata = UploadMetadata(
            uploadId = "test",
            fileUri = "",
            displayName = "file.txt",
            path = "/",
            totalBytes = 10000,
            mimeType = null
        )
        
        val status = UploadStatus(
            metadata = metadata,
            state = UploadState.UPLOADING,
            bytesReceived = 5000,
            bytesPerSecond = 1000f // 1000 bytes/sec
        )
        
        // 5000 remaining / 1000 bytes/sec = 5 seconds
        assertEquals(5, status.estimatedSecondsRemaining)
    }

    @Test
    fun `eta returns -1 when speed is zero`() {
        val metadata = UploadMetadata(
            uploadId = "test",
            fileUri = "",
            displayName = "file.txt",
            path = "/",
            totalBytes = 1000,
            mimeType = null
        )
        
        val status = UploadStatus(
            metadata = metadata,
            state = UploadState.UPLOADING,
            bytesReceived = 500,
            bytesPerSecond = 0f
        )
        
        assertEquals(-1, status.estimatedSecondsRemaining)
    }

    @Test
    fun `isTerminal returns true for terminal states`() {
        val metadata = UploadMetadata(
            uploadId = "test",
            fileUri = "",
            displayName = "file.txt",
            path = "/",
            totalBytes = 1000,
            mimeType = null
        )
        
        val completed = UploadStatus(metadata, UploadState.COMPLETED, 1000)
        val cancelled = UploadStatus(metadata, UploadState.CANCELLED, 0)
        val error = UploadStatus(metadata, UploadState.ERROR, 0)
        
        assertTrue(completed.isTerminal)
        assertTrue(cancelled.isTerminal)
        assertTrue(error.isTerminal)
    }

    @Test
    fun `isTerminal returns false for non-terminal states`() {
        val metadata = UploadMetadata(
            uploadId = "test",
            fileUri = "",
            displayName = "file.txt",
            path = "/",
            totalBytes = 1000,
            mimeType = null
        )
        
        val none = UploadStatus(metadata, UploadState.NONE, 0)
        val queued = UploadStatus(metadata, UploadState.QUEUED, 0)
        val resuming = UploadStatus(metadata, UploadState.RESUMING, 0)
        val uploading = UploadStatus(metadata, UploadState.UPLOADING, 500)
        val pausing = UploadStatus(metadata, UploadState.PAUSING, 500)
        val paused = UploadStatus(metadata, UploadState.PAUSED, 500)
        
        assertFalse(none.isTerminal)
        assertFalse(queued.isTerminal)
        assertFalse(resuming.isTerminal)
        assertFalse(uploading.isTerminal)
        assertFalse(pausing.isTerminal)
        assertFalse(paused.isTerminal)
    }

    @Test
    fun `upload metadata stores all properties`() {
        val metadata = UploadMetadata(
            uploadId = "test-id",
            fileUri = "content://test/file",
            displayName = "My File.txt",
            path = "/documents",
            totalBytes = 5000,
            mimeType = "text/plain"
        )
        
        assertEquals("test-id", metadata.uploadId)
        assertEquals("content://test/file", metadata.fileUri)
        assertEquals("My File.txt", metadata.displayName)
        assertEquals("/documents", metadata.path)
        assertEquals(5000, metadata.totalBytes)
        assertEquals("text/plain", metadata.mimeType)
    }

    @Test
    fun `upload metadata allows null mime type`() {
        val metadata = UploadMetadata(
            uploadId = "test",
            fileUri = "",
            displayName = "file",
            path = "/",
            totalBytes = 1000,
            mimeType = null
        )
        
        assertNull(metadata.mimeType)
    }

    @Test
    fun `upload request stores all properties`() {
        val request = UploadRequest(
            uploadId = "test-id",
            fileUri = "content://file",
            fileName = "file.txt",
            path = "/documents",
            offset = 500,
            totalBytes = 1000,
            contentRange = "bytes 500-999/1000"
        )
        
        assertEquals("test-id", request.uploadId)
        assertEquals("content://file", request.fileUri)
        assertEquals("file.txt", request.fileName)
        assertEquals("/documents", request.path)
        assertEquals(500, request.offset)
        assertEquals(1000, request.totalBytes)
        assertEquals("bytes 500-999/1000", request.contentRange)
    }

    @Test
    fun `upload request content range is optional`() {
        val request = UploadRequest(
            uploadId = "test",
            fileUri = "",
            fileName = "file.txt",
            path = "/",
            offset = 0,
            totalBytes = 1000,
            contentRange = null
        )
        
        assertNull(request.contentRange)
    }
}
