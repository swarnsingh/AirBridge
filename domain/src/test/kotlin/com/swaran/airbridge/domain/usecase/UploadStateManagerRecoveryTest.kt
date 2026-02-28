package com.swaran.airbridge.domain.usecase

import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.model.UploadStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for UploadStateManager crash recovery scenarios.
 *
 * Tests verify that:
 * - Interrupted uploads can be recovered
 * - State transitions work correctly after recovery
 * - Progress is preserved across recovery
 */
class UploadStateManagerRecoveryTest {

    private lateinit var stateManager: UploadStateManager

    @Before
    fun setup() {
        stateManager = UploadStateManager()
    }

    @Test
    fun `recover interrupted upload - uploading becomes error_retryable`() {
        // Given: An upload that was interrupted while UPLOADING
        val metadata = UploadMetadata(
            uploadId = "test-1",
            fileUri = "content://test/file.txt",
            displayName = "file.txt",
            path = "/",
            totalBytes = 1000L
        )
        
        // When: Initialize and simulate interrupted state
        stateManager.initialize(metadata)
        stateManager.transition("test-1", UploadState.UPLOADING)
        stateManager.updateProgress("test-1", 500L)
        
        // Simulate recovery (app restart)
        val newStateManager = UploadStateManager()
        newStateManager.initialize(metadata)
        newStateManager.updateProgress("test-1", 500L)
        newStateManager.transition("test-1", UploadState.ERROR_RETRYABLE, "App killed")
        
        // Then: Upload should be in ERROR_RETRYABLE state with preserved progress
        val status = newStateManager.getStatus("test-1")
        assertNotNull(status)
        assertEquals(UploadState.ERROR_RETRYABLE, status?.state)
        assertEquals(500L, status?.bytesReceived)
        assertEquals(1000L, status?.metadata?.totalBytes)
    }

    @Test
    fun `recover paused upload - stays paused`() {
        // Given: An upload that was explicitly PAUSED
        val metadata = UploadMetadata(
            uploadId = "test-2",
            fileUri = "content://test/file.txt",
            displayName = "file.txt",
            path = "/",
            totalBytes = 2000L
        )
        
        stateManager.initialize(metadata)
        stateManager.transition("test-2", UploadState.UPLOADING)
        stateManager.updateProgress("test-2", 1000L)
        stateManager.transition("test-2", UploadState.PAUSED)
        
        // When: Recover
        val newStateManager = UploadStateManager()
        newStateManager.initialize(metadata)
        newStateManager.updateProgress("test-2", 1000L)
        newStateManager.transition("test-2", UploadState.PAUSED)
        
        // Then: Should stay PAUSED
        val status = newStateManager.getStatus("test-2")
        assertEquals(UploadState.PAUSED, status?.state)
        assertEquals(1000L, status?.bytesReceived)
    }

    @Test
    fun `completed uploads are not recoverable`() {
        // Given: A completed upload
        val metadata = UploadMetadata(
            uploadId = "test-3",
            fileUri = "content://test/file.txt",
            displayName = "file.txt",
            path = "/",
            totalBytes = 1000L
        )
        
        stateManager.initialize(metadata)
        stateManager.transition("test-3", UploadState.COMPLETED)
        
        // When: Check if recoverable
        val status = stateManager.getStatus("test-3")
        
        // Then: Should be terminal
        assertTrue(status?.isTerminal ?: false)
    }

    @Test
    fun `state machine prevents invalid transitions after recovery`() {
        // Given: Recovered upload in ERROR_RETRYABLE
        val metadata = UploadMetadata(
            uploadId = "test-4",
            fileUri = "content://test/file.txt",
            displayName = "file.txt",
            path = "/",
            totalBytes = 1000L
        )
        
        stateManager.initialize(metadata)
        stateManager.transition("test-4", UploadState.ERROR_RETRYABLE)
        
        // When: Try invalid transition (ERROR_RETRYABLE -> UPLOADING directly)
        val result = stateManager.transition("test-4", UploadState.UPLOADING)
        
        // Then: Should fail - must go through QUEUED first
        assertFalse(result)
        assertEquals(UploadState.ERROR_RETRYABLE, stateManager.getStatus("test-4")?.state)
    }

    @Test
    fun `can retry from error_retryable via queued`() {
        // Given: Upload in ERROR_RETRYABLE
        val metadata = UploadMetadata(
            uploadId = "test-5",
            fileUri = "content://test/file.txt",
            displayName = "file.txt",
            path = "/",
            totalBytes = 1000L
        )
        
        stateManager.initialize(metadata)
        stateManager.transition("test-5", UploadState.ERROR_RETRYABLE)
        
        // When: Retry (ERROR_RETRYABLE -> QUEUED -> UPLOADING)
        val queuedResult = stateManager.transition("test-5", UploadState.QUEUED)
        val uploadingResult = stateManager.transition("test-5", UploadState.UPLOADING)
        
        // Then: Should succeed
        assertTrue(queuedResult)
        assertTrue(uploadingResult)
        assertEquals(UploadState.UPLOADING, stateManager.getStatus("test-5")?.state)
    }

    @Test
    fun `progress preserved during state transitions`() = runBlocking {
        // Given: Upload with progress
        val metadata = UploadMetadata(
            uploadId = "test-6",
            fileUri = "content://test/file.txt",
            displayName = "file.txt",
            path = "/",
            totalBytes = 5000L
        )
        
        stateManager.initialize(metadata)
        stateManager.transition("test-6", UploadState.UPLOADING)
        
        // When: Update progress multiple times
        stateManager.updateProgress("test-6", 1000L)
        stateManager.updateProgress("test-6", 2500L)
        stateManager.updateProgress("test-6", 4000L)
        
        // Then: Progress should be preserved
        val status = stateManager.getStatus("test-6")
        assertEquals(4000L, status?.bytesReceived)
        assertEquals(80, status?.progressPercent) // 4000/5000 = 80%
    }

    @Test
    fun `multiple concurrent uploads are tracked independently`() {
        // Given: Multiple uploads
        val uploads = (1..3).map { i ->
            UploadMetadata(
                uploadId = "concurrent-$i",
                fileUri = "content://test/file$i.txt",
                displayName = "file$i.txt",
                path = "/",
                totalBytes = 1000L * i
            )
        }
        
        // When: Initialize all
        uploads.forEach { metadata ->
            stateManager.initialize(metadata)
            stateManager.transition(metadata.uploadId, UploadState.UPLOADING)
        }
        
        // Then: All should be tracked
        val activeUploads = stateManager.getActiveUploads()
        assertEquals(3, activeUploads.size)
        
        // And: Progress updates should not interfere
        stateManager.updateProgress("concurrent-1", 500L)
        stateManager.updateProgress("concurrent-2", 1000L)
        
        assertEquals(500L, stateManager.getStatus("concurrent-1")?.bytesReceived)
        assertEquals(1000L, stateManager.getStatus("concurrent-2")?.bytesReceived)
    }

    @Test
    fun `active uploads flow emits updates`() = runBlocking {
        // Given: Upload
        val metadata = UploadMetadata(
            uploadId = "flow-test",
            fileUri = "content://test/file.txt",
            displayName = "file.txt",
            path = "/",
            totalBytes = 1000L
        )
        
        // When: Initialize and collect first emission
        stateManager.initialize(metadata)
        val firstEmission = stateManager.activeUploads.first()
        
        // Then: Should contain the upload
        assertTrue(firstEmission.containsKey("flow-test"))
        assertEquals(UploadState.QUEUED, firstEmission["flow-test"]?.state)
    }
}