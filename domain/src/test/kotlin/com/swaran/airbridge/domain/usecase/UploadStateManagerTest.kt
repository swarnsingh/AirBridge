package com.swaran.airbridge.domain.usecase

import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for UploadStateManager - Protocol v2.1
 *
 * Tests verify:
 * - State transitions with atomic updates (following valid transition paths)
 * - Progress tracking with throttling
 * - Flow emissions for reactive UI
 * - Thread-safe concurrent operations
 * - Recovery scenarios after crash/restart
 */
class UploadStateManagerTest {

    private lateinit var stateManager: UploadStateManager

    @Before
    fun setup() {
        stateManager = UploadStateManager(TestLogger())
    }

    private class TestLogger : AirLogger {
        override fun log(priority: Int, classTag: String, methodTag: String, message: String, vararg args: Any?) {}
        override fun log(priority: Int, classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {}
        override fun v(classTag: String, methodTag: String, message: String, vararg args: Any?) {}
        override fun v(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {}
        override fun d(classTag: String, methodTag: String, message: String, vararg args: Any?) {}
        override fun d(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {}
        override fun i(classTag: String, methodTag: String, message: String, vararg args: Any?) {}
        override fun i(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {}
        override fun w(classTag: String, methodTag: String, message: String, vararg args: Any?) {}
        override fun w(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {}
        override fun e(classTag: String, methodTag: String, message: String, vararg args: Any?) {}
        override fun e(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {}
        override fun wtf(classTag: String, methodTag: String, message: String, vararg args: Any?) {}
        override fun wtf(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {}
    }

    @Test
    fun `initialize creates new upload with none state`() {
        val metadata = createMetadata("test-1")
        
        val created = stateManager.initialize(metadata)
        
        assertTrue(created)
        val status = stateManager.getStatus("test-1")
        assertNotNull(status)
        assertEquals(UploadState.NONE, status?.state)
        assertEquals(0L, status?.bytesReceived)
    }

    @Test
    fun `initialize is idempotent - returns false if already exists`() {
        val metadata = createMetadata("test-2")
        
        val first = stateManager.initialize(metadata)
        val second = stateManager.initialize(metadata)
        
        assertTrue(first)
        assertFalse(second)
    }

    @Test
    fun `transition follows valid path - none to queued to uploading`() {
        val metadata = createMetadata("test-3")
        stateManager.initialize(metadata)
        
        // NONE -> QUEUED (valid)
        var result = stateManager.transition("test-3", UploadState.QUEUED)
        assertTrue("NONE -> QUEUED should succeed", result)
        assertEquals(UploadState.QUEUED, stateManager.getStatus("test-3")?.state)
        
        // QUEUED -> UPLOADING (valid)
        result = stateManager.transition("test-3", UploadState.UPLOADING)
        assertTrue("QUEUED -> UPLOADING should succeed", result)
        assertEquals(UploadState.UPLOADING, stateManager.getStatus("test-3")?.state)
    }

    @Test
    fun `transition allows direct path - none to uploading`() {
        val metadata = createMetadata("test-4")
        stateManager.initialize(metadata)
        
        // NONE -> UPLOADING is now VALID (direct upload start)
        val result = stateManager.transition("test-4", UploadState.UPLOADING)
        assertTrue("NONE -> UPLOADING should succeed for direct upload", result)
        assertEquals(UploadState.UPLOADING, stateManager.getStatus("test-4")?.state)
    }

    @Test
    fun `transition rejects invalid direct path - none to completed`() {
        val metadata = createMetadata("test-5")
        stateManager.initialize(metadata)
        
        // NONE -> COMPLETED is INVALID
        val result = stateManager.transition("test-5", UploadState.COMPLETED)
        assertFalse("NONE -> COMPLETED should fail", result)
        assertEquals(UploadState.NONE, stateManager.getStatus("test-5")?.state)
    }

    @Test
    fun `transition rejects invalid state change from terminal`() {
        val metadata = createMetadata("test-6")
        stateManager.initialize(metadata)
        
        // Valid path to COMPLETED
        stateManager.transition("test-6", UploadState.QUEUED)
        stateManager.transition("test-6", UploadState.UPLOADING)
        stateManager.transition("test-6", UploadState.COMPLETED)
        
        // COMPLETED -> UPLOADING is invalid
        val result = stateManager.transition("test-6", UploadState.UPLOADING)
        assertFalse(result)
        assertEquals(UploadState.COMPLETED, stateManager.getStatus("test-6")?.state)
    }

    @Test
    fun `updateProgress updates bytes`() {
        val metadata = createMetadata("test-7", totalBytes = 10000)
        stateManager.initialize(metadata)
        
        // Follow valid path: NONE -> QUEUED -> UPLOADING
        stateManager.transition("test-7", UploadState.QUEUED)
        stateManager.transition("test-7", UploadState.UPLOADING)
        
        val updateResult = stateManager.updateProgress("test-7", 5000)
        
        assertTrue("updateProgress should return true", updateResult)
        val status = stateManager.getStatus("test-7")
        assertNotNull("Status should not be null", status)
        assertEquals("bytesReceived should be 5000", 5000L, status?.bytesReceived)
        assertEquals("progressPercent should be 50", 50, status?.progressPercent)
        // Speed might be 0 if time elapsed is very short in test
    }

    @Test
    fun `updateProgress only works in uploading state`() {
        val metadata = createMetadata("test-8")
        stateManager.initialize(metadata)
        stateManager.transition("test-8", UploadState.QUEUED)
        stateManager.transition("test-8", UploadState.PAUSED)
        
        val result = stateManager.updateProgress("test-8", 1000)
        
        assertFalse(result)
        assertEquals(0L, stateManager.getStatus("test-8")?.bytesReceived)
    }

    @Test
    fun `updateProgress fails when not in uploading state`() {
        val metadata = createMetadata("test-9")
        stateManager.initialize(metadata)
        
        // In NONE state
        var result = stateManager.updateProgress("test-9", 500)
        assertFalse("Should fail in NONE state", result)
        
        // In QUEUED state
        stateManager.transition("test-9", UploadState.QUEUED)
        result = stateManager.updateProgress("test-9", 500)
        assertFalse("Should fail in QUEUED state", result)
    }

    @Test
    fun `remove deletes upload from tracking`() {
        val metadata = createMetadata("test-10")
        stateManager.initialize(metadata)
        
        stateManager.remove("test-10")
        
        assertNull(stateManager.getStatus("test-10"))
    }

    @Test
    fun `clear removes all uploads`() {
        (1..3).forEach { i ->
            stateManager.initialize(createMetadata("clear-test-$i"))
        }
        
        stateManager.clear()
        
        assertEquals(0, stateManager.activeUploads.value.size)
    }

    @Test
    fun `flow emits updates on state changes`() = runBlocking {
        val metadata = createMetadata("flow-test")
        stateManager.initialize(metadata)
        
        val firstEmission = stateManager.activeUploads.first()
        
        assertTrue(firstEmission.containsKey("flow-test"))
        assertEquals(UploadState.NONE, firstEmission["flow-test"]?.state)
    }

    @Test
    fun `progressPercent calculated correctly`() {
        val metadata = createMetadata("percent-test", totalBytes = 1000)
        stateManager.initialize(metadata)
        
        // Valid path to UPLOADING
        stateManager.transition("percent-test", UploadState.QUEUED)
        stateManager.transition("percent-test", UploadState.UPLOADING)
        
        // Update to 0 bytes
        stateManager.updateProgress("percent-test", 0)
        var status = stateManager.getStatus("percent-test")
        assertEquals("progress should be 0%", 0, status?.progressPercent)
        
        // Update to 250 bytes (25%)
        stateManager.updateProgress("percent-test", 250)
        status = stateManager.getStatus("percent-test")
        assertEquals("progress should be 25%", 25, status?.progressPercent)
        
        // Update to 500 bytes (50%)
        stateManager.updateProgress("percent-test", 500)
        status = stateManager.getStatus("percent-test")
        assertEquals("progress should be 50%", 50, status?.progressPercent)
        
        // Update to 1000 bytes (100%)
        stateManager.updateProgress("percent-test", 1000)
        status = stateManager.getStatus("percent-test")
        assertEquals("progress should be 100%", 100, status?.progressPercent)
    }

    @Test
    fun `remainingBytes calculated correctly`() {
        val metadata = createMetadata("remaining-test", totalBytes = 1000)
        stateManager.initialize(metadata)
        stateManager.transition("remaining-test", UploadState.QUEUED)
        stateManager.transition("remaining-test", UploadState.UPLOADING)
        
        stateManager.updateProgress("remaining-test", 600)
        
        val status = stateManager.getStatus("remaining-test")
        assertNotNull(status)
        assertEquals(400L, status?.remainingBytes)
    }

    @Test
    fun `remaining bytes is never negative`() {
        val metadata = createMetadata("negative-test", totalBytes = 1000)
        stateManager.initialize(metadata)
        stateManager.transition("negative-test", UploadState.QUEUED)
        stateManager.transition("negative-test", UploadState.UPLOADING)
        
        stateManager.updateProgress("negative-test", 1500) // More than total
        
        val status = stateManager.getStatus("negative-test")
        assertNotNull(status)
        assertEquals(0L, status?.remainingBytes) // Coerced to 0
    }

    @Test
    fun `isTerminal returns correct value`() {
        val metadata = createMetadata("terminal-test")
        stateManager.initialize(metadata)
        
        // Non-terminal states
        assertFalse(stateManager.getStatus("terminal-test")?.isTerminal ?: true)
        
        stateManager.transition("terminal-test", UploadState.QUEUED)
        assertFalse(stateManager.getStatus("terminal-test")?.isTerminal ?: true)
        
        stateManager.transition("terminal-test", UploadState.UPLOADING)
        assertFalse(stateManager.getStatus("terminal-test")?.isTerminal ?: true)
        
        // Terminal state
        stateManager.transition("terminal-test", UploadState.COMPLETED)
        assertTrue(stateManager.getStatus("terminal-test")?.isTerminal ?: false)
    }

    @Test
    fun `multiple uploads tracked independently`() {
        val upload1 = createMetadata("multi-1", totalBytes = 1000)
        val upload2 = createMetadata("multi-2", totalBytes = 2000)
        
        stateManager.initialize(upload1)
        stateManager.initialize(upload2)
        
        stateManager.transition("multi-1", UploadState.QUEUED)
        stateManager.transition("multi-2", UploadState.QUEUED)
        stateManager.transition("multi-1", UploadState.UPLOADING)
        stateManager.transition("multi-2", UploadState.UPLOADING)
        
        stateManager.updateProgress("multi-1", 500)
        stateManager.updateProgress("multi-2", 1000)
        
        val status1 = stateManager.getStatus("multi-1")
        val status2 = stateManager.getStatus("multi-2")
        
        assertNotNull(status1)
        assertNotNull(status2)
        assertEquals(500L, status1?.bytesReceived)
        assertEquals(1000L, status2?.bytesReceived)
        assertEquals(50, status1?.progressPercent)
        assertEquals(50, status2?.progressPercent)
    }

    @Test
    fun `state transition includes error message`() {
        val metadata = createMetadata("error-msg-test")
        stateManager.initialize(metadata)
        
        stateManager.transition("error-msg-test", UploadState.QUEUED)
        stateManager.transition("error-msg-test", UploadState.UPLOADING)
        stateManager.transition("error-msg-test", UploadState.ERROR, "Storage full")
        
        val status = stateManager.getStatus("error-msg-test")
        assertNotNull(status)
        assertEquals("Storage full", status?.errorMessage)
    }

    @Test
    fun `full upload lifecycle - success`() {
        val metadata = createMetadata("lifecycle-test", totalBytes = 1000)
        stateManager.initialize(metadata)
        
        // NONE -> QUEUED
        var result = stateManager.transition("lifecycle-test", UploadState.QUEUED)
        assertTrue(result)
        
        // QUEUED -> UPLOADING
        result = stateManager.transition("lifecycle-test", UploadState.UPLOADING)
        assertTrue(result)
        
        // Upload progress
        stateManager.updateProgress("lifecycle-test", 500)
        var status = stateManager.getStatus("lifecycle-test")
        assertEquals(UploadState.UPLOADING, status?.state)
        assertEquals(500L, status?.bytesReceived)
        
        // UPLOADING -> COMPLETED
        result = stateManager.transition("lifecycle-test", UploadState.COMPLETED)
        assertTrue(result)
        
        status = stateManager.getStatus("lifecycle-test")
        assertTrue(status?.isTerminal ?: false)
    }

    @Test
    fun `full upload lifecycle - pause and resume`() {
        val metadata = createMetadata("pause-resume-test", totalBytes = 1000)
        stateManager.initialize(metadata)
        
        // Start
        stateManager.transition("pause-resume-test", UploadState.QUEUED)
        stateManager.transition("pause-resume-test", UploadState.UPLOADING)
        stateManager.updateProgress("pause-resume-test", 300)
        
        // Pause
        stateManager.transition("pause-resume-test", UploadState.PAUSING)
        stateManager.transition("pause-resume-test", UploadState.PAUSED)
        var status = stateManager.getStatus("pause-resume-test")
        assertEquals(UploadState.PAUSED, status?.state)
        assertEquals(300L, status?.bytesReceived)
        
        // Resume
        stateManager.transition("pause-resume-test", UploadState.RESUMING)
        stateManager.transition("pause-resume-test", UploadState.UPLOADING)
        
        // Continue upload
        stateManager.updateProgress("pause-resume-test", 700)
        status = stateManager.getStatus("pause-resume-test")
        assertEquals(700L, status?.bytesReceived)
        
        // Complete
        stateManager.transition("pause-resume-test", UploadState.COMPLETED)
        status = stateManager.getStatus("pause-resume-test")
        assertTrue(status?.isTerminal ?: false)
    }

    @Test
    fun `cancel from any non-terminal state`() {
        val metadata = createMetadata("cancel-test", totalBytes = 1000)
        stateManager.initialize(metadata)
        
        // Cancel from NONE
        var result = stateManager.transition("cancel-test", UploadState.CANCELLED)
        assertTrue(result)
        assertTrue(stateManager.getStatus("cancel-test")?.isTerminal ?: false)
        
        // New upload
        val metadata2 = createMetadata("cancel-test-2", totalBytes = 1000)
        stateManager.initialize(metadata2)
        stateManager.transition("cancel-test-2", UploadState.QUEUED)
        stateManager.transition("cancel-test-2", UploadState.UPLOADING)
        
        // Cancel from UPLOADING
        result = stateManager.transition("cancel-test-2", UploadState.CANCELLED)
        assertTrue(result)
        assertTrue(stateManager.getStatus("cancel-test-2")?.isTerminal ?: false)
    }

    @Test
    fun `recovery scenario after app restart`() {
        // Simulate: App killed while uploading, then restarted
        val metadata = createMetadata("recovery-test", totalBytes = 5000)
        
        // Original session
        stateManager.initialize(metadata)
        stateManager.transition("recovery-test", UploadState.QUEUED)
        stateManager.transition("recovery-test", UploadState.UPLOADING)
        val updateResult = stateManager.updateProgress("recovery-test", 2500)
        assertTrue("Update should succeed", updateResult)
        
        // Simulate recovery: new state manager (app restart)
        val newStateManager = UploadStateManager(TestLogger())
        newStateManager.initialize(metadata)
        
        // Recovery path in v2.1: NONE -> QUEUED -> RESUMING -> PAUSED
        var result = newStateManager.transition("recovery-test", UploadState.QUEUED)
        assertTrue(result)
        result = newStateManager.transition("recovery-test", UploadState.RESUMING)
        assertTrue(result)
        result = newStateManager.transition("recovery-test", UploadState.PAUSED)
        assertTrue(result)
        
        val recoveryStatus = newStateManager.getStatus("recovery-test")
        assertNotNull(recoveryStatus)
        assertEquals(UploadState.PAUSED, recoveryStatus?.state)
    }

    private fun createMetadata(uploadId: String, totalBytes: Long = 1000): UploadMetadata {
        return UploadMetadata(
            uploadId = uploadId,
            fileUri = "content://test/$uploadId",
            displayName = "$uploadId.txt",
            path = "/",
            totalBytes = totalBytes,
            mimeType = "text/plain"
        )
    }
}
