package com.swaran.airbridge.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.core.data.db.AirBridgeDatabase
import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for UploadQueueRepository using in-memory Room database.
 */
@RunWith(AndroidJUnit4::class)
class UploadQueueRepositoryTest {

    private lateinit var database: AirBridgeDatabase
    private lateinit var repository: UploadQueueRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AirBridgeDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        repository = UploadQueueRepository(database.uploadQueueDao(), TestLogger())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun enqueue_and_get_returns_upload() = runBlocking {
        val metadata = createMetadata("upload-1")
        
        repository.enqueue(metadata, UploadState.NONE)
        val status = repository.get("upload-1")
        
        assertNotNull(status)
        assertEquals("upload-1", status?.metadata?.uploadId)
        assertEquals(UploadState.NONE, status?.state)
        assertEquals(0L, status?.bytesReceived)
    }

    @Test
    fun updateProgress_updates_bytes_and_state() = runBlocking {
        val metadata = createMetadata("upload-2")
        repository.enqueue(metadata, UploadState.NONE)
        
        repository.updateProgress("upload-2", 500, UploadState.UPLOADING)
        val status = repository.get("upload-2")
        
        assertNotNull(status)
        assertEquals(500L, status?.bytesReceived)
        assertEquals(UploadState.UPLOADING, status?.state)
    }

    @Test
    fun complete_sets_state_completed() = runBlocking {
        val metadata = createMetadata("upload-3")
        repository.enqueue(metadata, UploadState.UPLOADING)
        
        repository.complete("upload-3")
        val status = repository.get("upload-3")
        
        assertEquals(UploadState.COMPLETED, status?.state)
    }

    @Test
    fun cancel_removes_upload() = runBlocking {
        val metadata = createMetadata("upload-4")
        repository.enqueue(metadata, UploadState.UPLOADING)
        
        repository.cancel("upload-4")
        val status = repository.get("upload-4")
        
        assertNull(status)
    }

    @Test
    fun markError_sets_state_error() = runBlocking {
        val metadata = createMetadata("upload-5")
        repository.enqueue(metadata, UploadState.UPLOADING)
        
        repository.markError("upload-5", UploadState.ERROR, "Storage full")
        val status = repository.get("upload-5")
        
        assertEquals(UploadState.ERROR, status?.state)
        assertEquals("Storage full", status?.errorMessage)
    }

    @Test
    fun getResumable_returns_only_resumable_uploads() = runBlocking {
        repository.enqueue(createMetadata("upload-6"), UploadState.NONE)
        repository.enqueue(createMetadata("upload-7"), UploadState.PAUSED)
        repository.enqueue(createMetadata("upload-8"), UploadState.COMPLETED)
        repository.enqueue(createMetadata("upload-9"), UploadState.ERROR)
        
        val resumable = repository.getResumable().map { it.metadata.uploadId }
        
        assertTrue(resumable.contains("upload-6"))
        assertTrue(resumable.contains("upload-7"))
        assertFalse(resumable.contains("upload-8"))
        assertFalse(resumable.contains("upload-9"))
    }

    @Test
    fun pendingUploads_flow_emits_non_terminal_uploads() = runBlocking {
        repository.enqueue(createMetadata("upload-10"), UploadState.NONE)
        repository.enqueue(createMetadata("upload-11"), UploadState.COMPLETED)
        
        val pending = repository.pendingUploads.first().map { it.metadata.uploadId }
        
        assertTrue(pending.contains("upload-10"))
        assertFalse(pending.contains("upload-11"))
    }

    private fun createMetadata(uploadId: String): UploadMetadata {
        return UploadMetadata(
            uploadId = uploadId,
            fileUri = "content://test/$uploadId",
            displayName = "$uploadId.txt",
            path = "/",
            totalBytes = 1000L,
            mimeType = "text/plain"
        )
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
}
