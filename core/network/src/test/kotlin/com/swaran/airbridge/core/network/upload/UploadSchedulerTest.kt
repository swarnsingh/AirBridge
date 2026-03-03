package com.swaran.airbridge.core.network.upload

import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.domain.model.FolderItem
import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.repository.StorageRepository
import com.swaran.airbridge.domain.usecase.UploadStateManager
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.io.InputStream

/**
 * Unit tests for UploadScheduler (basic state transitions only)
 */
class UploadSchedulerTest {

    private lateinit var scheduler: UploadScheduler
    private lateinit var stateManager: UploadStateManager
    private lateinit var storageRepository: StorageRepository

    @Before
    fun setup() {
        stateManager = UploadStateManager(TestLogger())
        
        // Minimal mock storage repository
        storageRepository = object : StorageRepository {
            override suspend fun browseFiles(path: String): Result<List<FileItem>> = Result.success(emptyList())
            override suspend fun getFile(fileId: String): Result<FileItem> = Result.failure(Exception("Not used in test"))
            override suspend fun findFileByName(path: String, fileName: String): Result<FileItem?> = Result.success(null)
            override suspend fun findPartialFile(path: String, fileName: String, uploadId: String): Result<FileItem?> = Result.success(null)
            override suspend fun downloadFile(fileId: String): Result<InputStream> = Result.failure(Exception("Not used in test"))
            override suspend fun uploadFile(
                path: String,
                fileName: String,
                uploadId: String,
                inputStream: InputStream,
                totalBytes: Long,
                append: Boolean,
                onProgress: (Long) -> Unit
            ): Result<FileItem> = Result.failure(Exception("Not used in test"))
            override suspend fun deleteFile(fileId: String) = Result.success(Unit)
            override suspend fun createFolder(path: String, folderName: String): Result<FolderItem> = Result.failure(Exception("Not used in test"))
        }

        scheduler = UploadScheduler(
            storageRepository = storageRepository,
            stateManager = stateManager,
            logger = TestLogger(),
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        )
    }

    @Test
    fun `pause should set PAUSING state`() {
        val uploadId = "pause-test"
        val metadata = createMetadata(uploadId)
        stateManager.initialize(metadata)
        stateManager.transition(uploadId, UploadState.UPLOADING)

        scheduler.pause(uploadId)

        val status = stateManager.getStatus(uploadId)
        assertEquals(UploadState.PAUSING, status?.state)
    }

    @Test
    fun `resume should set RESUMING state`() {
        val uploadId = "resume-test"
        val metadata = createMetadata(uploadId)
        stateManager.initialize(metadata)
        stateManager.transition(uploadId, UploadState.UPLOADING)
        stateManager.transition(uploadId, UploadState.PAUSING)
        stateManager.transition(uploadId, UploadState.PAUSED)

        scheduler.resume(uploadId)

        val status = stateManager.getStatus(uploadId)
        assertEquals(UploadState.RESUMING, status?.state)
    }

    @Test
    fun `canResume returns true when state is PAUSED`() {
        val uploadId = "can-resume-test"
        val metadata = createMetadata(uploadId)
        stateManager.initialize(metadata)
        stateManager.transition(uploadId, UploadState.UPLOADING)
        stateManager.transition(uploadId, UploadState.PAUSING)
        stateManager.transition(uploadId, UploadState.PAUSED)

        assertTrue(scheduler.canResume(uploadId))
    }

    @Test
    fun `canResume returns false when state is not PAUSED`() {
        val uploadId = "cannot-resume-test"
        val metadata = createMetadata(uploadId)
        stateManager.initialize(metadata)
        stateManager.transition(uploadId, UploadState.UPLOADING)

        assertFalse(scheduler.canResume(uploadId))
    }

    private fun createMetadata(uploadId: String): UploadMetadata {
        return UploadMetadata(
            uploadId = uploadId,
            fileUri = "content://test/$uploadId",
            displayName = "$uploadId.txt",
            path = "/",
            totalBytes = 10000,
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