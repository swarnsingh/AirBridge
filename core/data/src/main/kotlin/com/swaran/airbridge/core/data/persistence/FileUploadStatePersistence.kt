package com.swaran.airbridge.core.data.persistence

import android.content.Context
import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.domain.model.UploadState
import com.swaran.airbridge.domain.model.UploadStatus
import com.swaran.airbridge.domain.repository.PersistedUpload
import com.swaran.airbridge.domain.repository.UploadStatePersistence
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File-based implementation of upload state persistence.
 *
 * Uses atomic writes (write to temp, then rename) for crash safety.
 * Stores each upload as a separate JSON file for easy debugging.
 */
@Singleton
class FileUploadStatePersistence @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(AirDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    private val logger: AirLogger
) : UploadStatePersistence {

    companion object {
        private const val TAG = "UploadStatePersistence"
        private const val UPLOAD_STATE_DIR = "upload_states"
        private const val TEMP_SUFFIX = ".tmp"
        private const val JSON_SUFFIX = ".json"
        private const val MAX_TERMINAL_STATE_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
        
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val persistenceDir: File by lazy {
        File(context.filesDir, UPLOAD_STATE_DIR).apply {
            if (!exists()) {
                mkdirs()
                logger.d(TAG, "log", "Created persistence directory: $absolutePath")
            }
        }
    }

    private val writeMutex = Mutex()
    private val _persistedStates = MutableStateFlow<Map<String, PersistedUpload>>(emptyMap())
    override val persistedStates: Flow<Map<String, PersistedUpload>> = _persistedStates.asStateFlow()

    init {
        // Load initial state on creation
        kotlinx.coroutines.runBlocking(ioDispatcher) {
            refreshCache()
        }
    }

    override suspend fun persist(status: UploadStatus) = withContext(ioDispatcher) {
        val persisted = PersistedUpload(
            uploadId = status.metadata.uploadId,
            fileUri = status.metadata.fileUri,
            displayName = status.metadata.displayName,
            path = status.metadata.path,
            totalBytes = status.metadata.totalBytes,
            bytesReceived = status.bytesReceived,
            state = status.state.name,
            bytesPerSecond = status.bytesPerSecond,
            startedAtNanos = status.startedAt,
            updatedAtNanos = status.updatedAt,
            errorMessage = status.errorMessage,
            retryCount = status.retryCount
        )

        writeMutex.withLock {
            val file = getStateFile(status.metadata.uploadId)
            val tempFile = File(file.parent, "${file.name}$TEMP_SUFFIX")

            try {
                // Atomic write: write to temp, then rename
                val jsonString = json.encodeToString(persisted)
                tempFile.writeText(jsonString)

                if (!tempFile.renameTo(file)) {
                    throw IOException("Failed to rename temp file to $file")
                }

                // Update cache
                _persistedStates.value += (status.metadata.uploadId to persisted)

                logger.d(TAG, "log", "[${status.metadata.uploadId}] Persisted state: ${status.state}")

                // Cleanup terminal states periodically
                if (status.state.isTerminal()) {
                    cleanupOldTerminalStates()
                }

            } catch (e: Exception) {
                logger.e(TAG, "log", "[${status.metadata.uploadId}] Failed to persist state", e)
                tempFile.delete()
                throw e
            }
        }
    }

    override suspend fun remove(uploadId: String) = withContext(ioDispatcher) {
        writeMutex.withLock {
            val file = getStateFile(uploadId)
            if (file.exists()) {
                file.delete()
                logger.d(TAG, "log", "[$uploadId] Removed persisted state")
            }
            _persistedStates.value -= uploadId
        }
    }

    override suspend fun loadAll(): List<PersistedUpload> = withContext(ioDispatcher) {
        writeMutex.withLock {
            val uploads = mutableListOf<PersistedUpload>()

            if (!persistenceDir.exists()) {
                return@withContext emptyList()
            }

            persistenceDir.listFiles { file ->
                file.isFile && file.name.endsWith(JSON_SUFFIX)
            }?.forEach { file ->
                try {
                    val jsonString = file.readText()
                    val persisted = json.decodeFromString<PersistedUpload>(jsonString)
                    uploads.add(persisted)
                } catch (e: Exception) {
                    logger.e(TAG, "log", "Failed to load state from ${file.name}", e)
                    // Delete corrupted file
                    file.delete()
                }
            }

            logger.d(TAG, "log", "Loaded ${uploads.size} persisted uploads")
            uploads
        }
    }

    override suspend fun clear(): Unit = withContext(ioDispatcher) {
        writeMutex.withLock {
            persistenceDir.listFiles()?.forEach { it.delete() }
            _persistedStates.value = emptyMap()
            logger.d(TAG, "log", "Cleared all persisted state")
        }
    }

    /**
     * Recover interrupted uploads after app restart.
     * Returns list of uploads that should be transitioned to appropriate recovery states.
     */
    suspend fun recoverInterruptedUploads(): List<UploadRecovery> = withContext(ioDispatcher) {
        val all = loadAll()
        val recoverable = all.filter { it.canRecover() }

        val recoveries = recoverable.map { persisted ->
            val recoveryState = persisted.recoveryState()

            logger.d(TAG, "log", "[${persisted.uploadId}] Recovering from ${persisted.state} to ${recoveryState.name}")

            UploadRecovery(
                uploadId = persisted.uploadId,
                fileUri = persisted.fileUri,
                displayName = persisted.displayName,
                path = persisted.path,
                totalBytes = persisted.totalBytes,
                bytesReceived = persisted.bytesReceived,
                recoveredState = recoveryState,
                errorMessage = "Upload interrupted by app termination"
            )
        }

        recoveries
    }

    private fun getStateFile(uploadId: String): File {
        return File(persistenceDir, "$uploadId$JSON_SUFFIX")
    }

    private suspend fun refreshCache() {
        val all = loadAll()
        _persistedStates.value = all.associateBy { it.uploadId }
    }

    private fun cleanupOldTerminalStates() {
        val now = System.currentTimeMillis()

        persistenceDir.listFiles { file ->
            file.isFile && file.name.endsWith(JSON_SUFFIX)
        }?.forEach { file ->
            try {
                val age = now - file.lastModified()
                if (age > MAX_TERMINAL_STATE_AGE_MS) {
                    file.delete()
                    logger.d(TAG, "log", "Cleaned up old terminal state: ${file.name}")
                }
            } catch (e: Exception) {
                logger.w(TAG, "log", "Failed to cleanup ${file.name}", e)
            }
        }
    }

    private fun UploadState.isTerminal(): Boolean {
        return this in setOf(UploadState.COMPLETED, UploadState.CANCELLED, UploadState.ERROR_PERMANENT)
    }
}

/**
 * Represents an upload that needs to be recovered after app restart.
 */
data class UploadRecovery(
    val uploadId: String,
    val fileUri: String,
    val displayName: String,
    val path: String,
    val totalBytes: Long,
    val bytesReceived: Long,
    val recoveredState: UploadState,
    val errorMessage: String
)
