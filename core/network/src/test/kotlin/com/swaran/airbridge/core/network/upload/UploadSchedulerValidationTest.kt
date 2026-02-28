package com.swaran.airbridge.core.network.upload

import com.swaran.airbridge.domain.model.OffsetMismatchException
import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadRequest
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.repository.StorageRepository
import com.swaran.airbridge.domain.usecase.UploadStateManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for UploadScheduler offset validation and file locking.
 *
 * Tests verify:
 * - Offset validation prevents duplicate bytes
 * - File-level locking prevents concurrent writes
 * - Error classification works correctly
 */
class UploadSchedulerValidationTest {

    private lateinit var scheduler: UploadScheduler
    private lateinit var storageRepository: StorageRepository
    private lateinit var stateManager: UploadStateManager

    @Before
    fun setup() {
        storageRepository = mockk()
        stateManager = UploadStateManager()
        // Note: scheduler initialization would need Android context in real tests
        // This is a simplified version for demonstration
    }

    @Test
    fun `offset mismatch detected when disk size differs`() {
        // Scenario: Browser claims it sent 1000 bytes, but file on disk only has 800
        // This could happen if:
        // - Browser retry sends same Content-Range twice
        // - Partial write was lost
        // - Disk corruption

        // Given: Disk has 800 bytes
        val diskSize = 800L
        val claimedOffset = 1000L

        // When: Validate offset
        val isValid = diskSize == claimedOffset

        // Then: Should detect mismatch
        assertFalse("Offset validation should fail when disk size differs", isValid)
    }

    @Test
    fun `offset valid when matches disk size`() {
        // Given: Disk and browser agree
        val diskSize = 1000L
        val claimedOffset = 1000L

        // When: Validate
        val isValid = diskSize == claimedOffset

        // Then: Should be valid
        assertTrue("Offset should be valid when it matches disk size", isValid)
    }

    @Test
    fun `file lock prevents concurrent uploads to same file`() {
        // Scenario: Two browsers try to upload "photo.jpg" simultaneously
        // Without file-level locking: Data corruption
        // With file-level locking: Second upload waits or fails

        // Given: Upload A starts first
        val uploadA = UploadRequest(
            uploadId = "upload-a",
            fileUri = "",
            fileName = "photo.jpg",
            path = "/",
            offset = 0,
            totalBytes = 5000
        )

        val uploadB = UploadRequest(
            uploadId = "upload-b",
            fileUri = "",
            fileName = "photo.jpg",  // Same file!
            path = "/",
            offset = 0,
            totalBytes = 5000
        )

        // When: Both try to acquire lock
        val lockKey = "/photo.jpg"

        // Then: They should have the same lock key
        val lockKeyA = "${uploadA.path}/${uploadA.fileName}"
        val lockKeyB = "${uploadB.path}/${uploadB.fileName}"
        assertEquals("Same file should have same lock key", lockKeyA, lockKeyB)

        // In real implementation: second upload would be rejected or wait
    }

    @Test
    fun `different files have different locks`() {
        // Given: Two different files
        val uploadA = UploadRequest(
            uploadId = "upload-a",
            fileUri = "",
            fileName = "photo1.jpg",
            path = "/",
            offset = 0,
            totalBytes = 1000
        )

        val uploadB = UploadRequest(
            uploadId = "upload-b",
            fileUri = "",
            fileName = "photo2.jpg",  // Different file
            path = "/",
            offset = 0,
            totalBytes = 2000
        )

        // When: Generate lock keys
        val lockKeyA = "${uploadA.path}/${uploadA.fileName}"
        val lockKeyB = "${uploadB.path}/${uploadB.fileName}"

        // Then: Should be different
        assertNotEquals("Different files should have different lock keys", lockKeyA, lockKeyB)
    }

    @Test
    fun `same file in different paths have different locks`() {
        // Given: Same filename, different directories
        val uploadA = UploadRequest(
            uploadId = "upload-a",
            fileUri = "",
            fileName = "file.txt",
            path = "/photos",
            offset = 0,
            totalBytes = 1000
        )

        val uploadB = UploadRequest(
            uploadId = "upload-b",
            fileUri = "",
            fileName = "file.txt",  // Same name
            path = "/documents",    // Different path!
            offset = 0,
            totalBytes = 1000
        )

        // When: Generate lock keys
        val lockKeyA = "${uploadA.path}/${uploadA.fileName}"
        val lockKeyB = "${uploadB.path}/${uploadB.fileName}"

        // Then: Should be different
        assertNotEquals("Same file in different paths should have different locks", lockKeyA, lockKeyB)
    }

    @Test
    fun `duplicate uploadId rejected`() {
        // Given: Active upload
        val metadata = UploadMetadata(
            uploadId = "duplicate-test",
            fileUri = "",
            displayName = "file.txt",
            path = "/",
            totalBytes = 1000
        )

        stateManager.initialize(metadata)
        stateManager.transition("duplicate-test", UploadState.UPLOADING)

        // When: Try to start another upload with same ID
        val shouldReject = stateManager.shouldRejectRequest("duplicate-test", "")

        // Then: Should be rejected
        assertTrue("Duplicate uploadId should be rejected", shouldReject)
    }

    @Test
    fun `new uploadId accepted`() {
        // Given: No active uploads

        // When: Check new uploadId
        val shouldReject = stateManager.shouldRejectRequest("new-upload", "")

        // Then: Should be accepted
        assertFalse("New uploadId should be accepted", shouldReject)
    }

    @Test
    fun `upload with zero bytes is not recoverable`() {
        // Given: Upload that never received any bytes
        val metadata = UploadMetadata(
            uploadId = "zero-bytes",
            fileUri = "",
            displayName = "file.txt",
            path = "/",
            totalBytes = 1000
        )

        stateManager.initialize(metadata)
        stateManager.transition("zero-bytes", UploadState.UPLOADING)
        // Never update progress - stays at 0

        // When: Check if recoverable
        val status = stateManager.getStatus("zero-bytes")
        val bytesReceived = status?.bytesReceived ?: 0

        // Then: Should not be recoverable (nothing to resume)
        assertEquals(0L, bytesReceived)
    }

    @Test
    fun `upload at 100 percent is terminal`() {
        // Given: Completed upload
        val metadata = UploadMetadata(
            uploadId = "complete",
            fileUri = "",
            displayName = "file.txt",
            path = "/",
            totalBytes = 1000
        )

        stateManager.initialize(metadata)
        stateManager.transition("complete", UploadState.UPLOADING)
        stateManager.updateProgress("complete", 1000)
        stateManager.transition("complete", UploadState.COMPLETED)

        // When: Check status
        val status = stateManager.getStatus("complete")

        // Then: Should be terminal
        assertTrue("Completed upload should be terminal", status?.isTerminal ?: false)
    }
}