package com.swaran.airbridge.core.network.upload

import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.domain.model.FolderItem
import com.swaran.airbridge.domain.model.UploadMetadata
import com.swaran.airbridge.domain.model.UploadRequest
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.repository.StorageRepository
import com.swaran.airbridge.domain.usecase.UploadStateManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.InputStream

/**
 * Unit tests for UploadQueueManager
 */
class UploadQueueManagerTest {

    private lateinit var queueManager: UploadQueueManager
    private lateinit var stateManager: UploadStateManager
    private lateinit var scheduler: UploadScheduler

    @Before
    fun setup() {
        stateManager = UploadStateManager(TestLogger())

        val storageRepository = object : StorageRepository {
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
            override suspend fun deleteFile(fileId: String): Result<Unit> = Result.success(Unit)
            override suspend fun createFolder(path: String, folderName: String): Result<FolderItem> = Result.failure(Exception("Not used in test"))
        }

        scheduler = UploadScheduler(
            storageRepository = storageRepository,
            stateManager = stateManager,
            logger = TestLogger(),
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        )

        queueManager = UploadQueueManager(
            scheduler = scheduler,
            stateManager = stateManager,
            logger = TestLogger()
        )
    }

    @Test
    fun `pause sets PAUSING state`() {
        val uploadId = "pause-test"
        stateManager.initialize(createMetadata(uploadId))
        stateManager.transition(uploadId, UploadState.UPLOADING)

        queueManager.pause(uploadId)

        assertEquals(UploadState.PAUSING, stateManager.getStatus(uploadId)?.state)
    }

    @Test
    fun `resume sets RESUMING state when paused`() {
        val uploadId = "resume-test"
        stateManager.initialize(createMetadata(uploadId))
        stateManager.transition(uploadId, UploadState.UPLOADING)
        stateManager.transition(uploadId, UploadState.PAUSING)
        stateManager.transition(uploadId, UploadState.PAUSED)

        queueManager.resume(uploadId)

        assertEquals(UploadState.RESUMING, stateManager.getStatus(uploadId)?.state)
    }


    @Test
    fun `resume returns true when paused`() {
        val uploadId = "resume-bool-true"
        stateManager.initialize(createMetadata(uploadId))
        stateManager.transition(uploadId, UploadState.UPLOADING)
        stateManager.transition(uploadId, UploadState.PAUSING)
        stateManager.transition(uploadId, UploadState.PAUSED)

        val accepted = queueManager.resume(uploadId)

        assertTrue(accepted)
        assertEquals(UploadState.RESUMING, stateManager.getStatus(uploadId)?.state)
    }

    @Test
    fun `resume returns false when not paused`() {
        val uploadId = "resume-bool-false"
        stateManager.initialize(createMetadata(uploadId))
        stateManager.transition(uploadId, UploadState.UPLOADING)

        val accepted = queueManager.resume(uploadId)

        assertFalse(accepted)
        assertEquals(UploadState.UPLOADING, stateManager.getStatus(uploadId)?.state)
    }

    @Test
    fun `pauseAll sets global pause flag`() {
        queueManager.pauseAll()

        assertTrue(queueManager.queueState.value.isPaused)
    }

    @Test
    fun `resumeAll clears global pause`() {
        queueManager.pauseAll()
        assertTrue(queueManager.queueState.value.isPaused)

        queueManager.resumeAll()
        assertFalse(queueManager.queueState.value.isPaused)
    }

    @Test
    fun `cancel sets CANCELLED state`() = runBlocking {
        val uploadId = "cancel-test"
        stateManager.initialize(createMetadata(uploadId))
        stateManager.transition(uploadId, UploadState.UPLOADING)
        stateManager.transition(uploadId, UploadState.PAUSING)
        stateManager.transition(uploadId, UploadState.PAUSED)

        val request = UploadRequest(
            uploadId = uploadId,
            fileUri = "",
            fileName = "$uploadId.txt",
            path = "/",
            offset = 0,
            totalBytes = 0
        )

        queueManager.cancel(uploadId, request)

        assertEquals(UploadState.CANCELLED, stateManager.getStatus(uploadId)?.state)
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