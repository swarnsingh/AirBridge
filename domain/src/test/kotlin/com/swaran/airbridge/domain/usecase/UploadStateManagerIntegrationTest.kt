package com.swaran.airbridge.domain.usecase

import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests that verify full workflows - the scenarios that manual testing found bugs in.
 * These complement the unit tests by testing complete user scenarios.
 */
class UploadStateManagerIntegrationTest {

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

    /**
     * BUG SCENARIO TEST: Direct upload start
     * This would have caught the bug where NONE→UPLOADING was rejected
     */
    @Test
    fun `direct upload start - NONE to UPLOADING transition should work`() {
        val metadata = createMetadata("direct-start-test")
        
        // Initialize new upload
        stateManager.initialize(metadata)
        
        // Try direct upload start (what happens when browser POSTs new file)
        val result = stateManager.transition("direct-start-test", UploadState.UPLOADING)
        
        // This was failing before the fix
        assertTrue("NONE→UPLOADING should succeed for new uploads", result)
        assertEquals(UploadState.UPLOADING, stateManager.getStatus("direct-start-test")?.state)
    }

    /**
     * BUG SCENARIO TEST: Resume from PAUSED
     * Tests the complete pause→resume→upload flow
     */
    @Test
    fun `pause resume workflow - PAUSED can transition to RESUMING`() {
        val metadata = createMetadata("pause-resume-test")
        
        // Start upload
        stateManager.initialize(metadata)
        stateManager.transition("pause-resume-test", UploadState.UPLOADING)
        stateManager.updateProgress("pause-resume-test", 5000)
        
        // Pause
        stateManager.transition("pause-resume-test", UploadState.PAUSING)
        stateManager.transition("pause-resume-test", UploadState.PAUSED)
        
        assertEquals(UploadState.PAUSED, stateManager.getStatus("pause-resume-test")?.state)
        assertEquals(5000L, stateManager.getStatus("pause-resume-test")?.bytesReceived)
        
        // Resume (this sets RESUMING with deadline)
        val resumeResult = stateManager.transition("pause-resume-test", UploadState.RESUMING)
        assertTrue("PAUSED→RESUMING should succeed", resumeResult)
        assertEquals(UploadState.RESUMING, stateManager.getStatus("pause-resume-test")?.state)
        
        // Browser POSTs (RESUMING→UPLOADING)
        val postResult = stateManager.transition("pause-resume-test", UploadState.UPLOADING)
        assertTrue("RESUMING→UPLOADING should succeed", postResult)
        assertEquals(UploadState.UPLOADING, stateManager.getStatus("pause-resume-test")?.state)
    }

    /**
     * BUG SCENARIO TEST: Cancel from PAUSED
     * Tests that cancel works from paused state
     */
    @Test
    fun `cancel workflow - PAUSED can transition to CANCELLED`() {
        val metadata = createMetadata("cancel-test")
        
        // Upload → Pause
        stateManager.initialize(metadata)
        stateManager.transition("cancel-test", UploadState.UPLOADING)
        stateManager.transition("cancel-test", UploadState.PAUSING)
        stateManager.transition("cancel-test", UploadState.PAUSED)
        
        // Cancel
        val result = stateManager.transition("cancel-test", UploadState.CANCELLED)
        
        assertTrue("PAUSED→CANCELLED should succeed", result)
        assertEquals(UploadState.CANCELLED, stateManager.getStatus("cancel-test")?.state)
        assertEquals(true, stateManager.getStatus("cancel-test")?.isTerminal)
    }

    /**
     * Multi-file scenario: Multiple uploads with independent states
     */
    @Test
    fun `multi-file scenario - uploads have independent states`() {
        // File 1: Uploading
        stateManager.initialize(createMetadata("file-1"))
        stateManager.transition("file-1", UploadState.UPLOADING)
        stateManager.updateProgress("file-1", 1000)
        
        // File 2: Paused (with progress before pausing)
        stateManager.initialize(createMetadata("file-2"))
        stateManager.transition("file-2", UploadState.UPLOADING)
        stateManager.updateProgress("file-2", 5000)  // Progress while UPLOADING
        stateManager.transition("file-2", UploadState.PAUSING)
        stateManager.transition("file-2", UploadState.PAUSED)
        
        // Verify independent states
        assertEquals(UploadState.UPLOADING, stateManager.getStatus("file-1")?.state)
        assertEquals(UploadState.PAUSED, stateManager.getStatus("file-2")?.state)
        
        // Verify independent progress
        assertEquals(1000L, stateManager.getStatus("file-1")?.bytesReceived)
        assertEquals(5000L, stateManager.getStatus("file-2")?.bytesReceived)
        
        // Resume file 2 doesn't affect file 1
        stateManager.transition("file-2", UploadState.RESUMING)
        stateManager.transition("file-2", UploadState.UPLOADING)
        
        assertEquals(UploadState.UPLOADING, stateManager.getStatus("file-1")?.state)
        assertEquals(UploadState.UPLOADING, stateManager.getStatus("file-2")?.state)
    }

    /**
     * Rapid state toggling - tests state machine robustness
     */
    @Test
    fun `rapid pause resume toggling`() {
        val metadata = createMetadata("rapid-toggle-test")
        
        stateManager.initialize(metadata)
        stateManager.transition("rapid-toggle-test", UploadState.UPLOADING)
        
        // Toggle 5 times
        repeat(5) { iteration ->
            // Pause
            assertTrue("Iteration $iteration: PAUSING should succeed",
                stateManager.transition("rapid-toggle-test", UploadState.PAUSING))
            assertTrue("Iteration $iteration: PAUSED should succeed",
                stateManager.transition("rapid-toggle-test", UploadState.PAUSED))
            
            // Resume
            assertTrue("Iteration $iteration: RESUMING should succeed",
                stateManager.transition("rapid-toggle-test", UploadState.RESUMING))
            assertTrue("Iteration $iteration: UPLOADING should succeed",
                stateManager.transition("rapid-toggle-test", UploadState.UPLOADING))
        }
        
        // Progress should be preserved
        assertEquals(UploadState.UPLOADING, stateManager.getStatus("rapid-toggle-test")?.state)
    }

    /**
     * Full workflow: Upload → Pause → Resume → Complete
     */
    @Test
    fun `complete workflow from start to finish`() {
        val metadata = createMetadata("complete-workflow-test", totalBytes = 10000)
        
        // 1. Initialize and start
        stateManager.initialize(metadata)
        assertTrue(stateManager.transition("complete-workflow-test", UploadState.UPLOADING))
        
        // 2. Upload some data
        stateManager.updateProgress("complete-workflow-test", 3000)
        assertEquals(3000L, stateManager.getStatus("complete-workflow-test")?.bytesReceived)
        
        // 3. Pause
        assertTrue(stateManager.transition("complete-workflow-test", UploadState.PAUSING))
        assertTrue(stateManager.transition("complete-workflow-test", UploadState.PAUSED))
        assertEquals(UploadState.PAUSED, stateManager.getStatus("complete-workflow-test")?.state)
        
        // 4. Resume
        assertTrue(stateManager.transition("complete-workflow-test", UploadState.RESUMING))
        assertEquals(UploadState.RESUMING, stateManager.getStatus("complete-workflow-test")?.state)
        
        // 5. Back to uploading
        assertTrue(stateManager.transition("complete-workflow-test", UploadState.UPLOADING))
        
        // 6. More progress
        stateManager.updateProgress("complete-workflow-test", 8000)
        
        // 7. Complete
        assertTrue(stateManager.transition("complete-workflow-test", UploadState.COMPLETED))
        assertEquals(UploadState.COMPLETED, stateManager.getStatus("complete-workflow-test")?.state)
        assertEquals(true, stateManager.getStatus("complete-workflow-test")?.isTerminal)
    }

    /**
     * Edge case: Resume deadline expiration (RESUMING → PAUSED)
     */
    @Test
    fun `resume deadline expiration - can revert from RESUMING to PAUSED`() {
        val metadata = createMetadata("deadline-test")
        
        // Upload → Pause → Resume
        stateManager.initialize(metadata)
        stateManager.transition("deadline-test", UploadState.UPLOADING)
        stateManager.transition("deadline-test", UploadState.PAUSING)
        stateManager.transition("deadline-test", UploadState.PAUSED)
        stateManager.transition("deadline-test", UploadState.RESUMING)
        
        // Without browser POST, should be able to revert to PAUSED
        val result = stateManager.transition("deadline-test", UploadState.PAUSED)
        assertTrue("RESUMING→PAUSED should succeed for deadline expiration", result)
    }

    private fun createMetadata(uploadId: String, totalBytes: Long = 10000): UploadMetadata {
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
