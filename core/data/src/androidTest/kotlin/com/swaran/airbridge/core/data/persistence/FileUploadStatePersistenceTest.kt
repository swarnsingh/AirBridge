package com.swaran.airbridge.core.data.persistence

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.model.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for FileUploadStatePersistence using app files directory.
 */
@RunWith(AndroidJUnit4::class)
class FileUploadStatePersistenceTest {

    private lateinit var persistence: FileUploadStatePersistence
    private lateinit var context: android.content.Context
    private lateinit var persistenceDir: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        persistence = FileUploadStatePersistence(
            context = context,
            ioDispatcher = Dispatchers.IO,
            logger = TestLogger()
        )
        persistenceDir = File(context.filesDir, "upload_states")
        persistenceDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        persistenceDir.deleteRecursively()
    }

    @Test
    fun persist_and_loadAll_returns_saved_upload() = runBlocking {
        val status = createStatus("upload-1", UploadState.UPLOADING, 500)
        
        persistence.persist(status)
        val loaded = persistence.loadAll()
        
        assertEquals(1, loaded.size)
        assertEquals("upload-1", loaded.first().uploadId)
        assertEquals(500L, loaded.first().bytesReceived)
    }

    @Test
    fun remove_deletes_persisted_upload() = runBlocking {
        val status = createStatus("upload-2", UploadState.PAUSED, 700)
        
        persistence.persist(status)
        persistence.remove("upload-2")
        
        val loaded = persistence.loadAll()
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun clear_removes_all_uploads() = runBlocking {
        persistence.persist(createStatus("upload-3", UploadState.UPLOADING, 100))
        persistence.persist(createStatus("upload-4", UploadState.PAUSED, 200))
        
        persistence.clear()
        
        val loaded = persistence.loadAll()
        assertEquals(0, loaded.size)
    }

    @Test
    fun persistedStates_flow_emits_updates() = runBlocking {
        val status = createStatus("upload-5", UploadState.UPLOADING, 300)
        
        persistence.persist(status)
        val snapshot = persistence.persistedStates.first()
        
        assertTrue(snapshot.containsKey("upload-5"))
        assertEquals(300L, snapshot["upload-5"]?.bytesReceived)
    }

    @Test
    fun terminal_state_triggers_cleanup() = runBlocking {
        val completed = createStatus("upload-6", UploadState.COMPLETED, 1000)
        
        persistence.persist(completed)
        
        // Completed uploads should still be present immediately after persist
        val loaded = persistence.loadAll()
        assertEquals(1, loaded.size)
    }

    private fun createStatus(uploadId: String, state: UploadState, bytes: Long): UploadStatus {
        val metadata = UploadMetadata(
            uploadId = uploadId,
            fileUri = "content://test/$uploadId",
            displayName = "$uploadId.txt",
            path = "/",
            totalBytes = 1000L,
            mimeType = "text/plain"
        )
        return UploadStatus(
            metadata = metadata,
            state = state,
            bytesReceived = bytes,
            bytesPerSecond = 0f,
            startedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
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
