package com.swaran.airbridge.core.network.controller

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.network.LocalHttpServer
import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.domain.model.TransferStatus
import com.swaran.airbridge.domain.repository.StorageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class UploadProgress(
    val id: String,
    val fileName: String,
    val bytesReceived: Long,
    val totalBytes: Long,
    val percentage: Int,
    val status: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Singleton
class UploadController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageRepository: StorageRepository,
    private val sessionTokenManager: SessionTokenManager,
    @Dispatcher(AirDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    server: LocalHttpServer
) : LocalHttpServer.RequestHandler {

    private val _activeUploads = MutableStateFlow<Map<String, UploadProgress>>(emptyMap())
    val activeUploads: StateFlow<Map<String, UploadProgress>> = _activeUploads

    private val uploadJobs = ConcurrentHashMap<String, Job>()
    private val uploadLocks = ConcurrentHashMap<String, Any>()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "UploadController"
        private const val WAKE_LOCK_TAG = "AirBridge:UploadWakeLock"
    }

    init {
        server.registerHandler(this)
        Log.d(TAG, "UploadController initialized")
    }

    override fun canHandle(session: NanoHTTPD.IHTTPSession): Boolean =
        session.uri.startsWith("/api/upload")

    override fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val token = session.parameters["token"]?.firstOrNull() ?: return unauthorized()
        if (!sessionTokenManager.validateSession(token)) return unauthorized()
        
        return when {
            session.uri == "/api/upload/status" -> handleCheckStatus(session)
            session.uri == "/api/upload/pause"  -> handlePause(session)
            session.uri == "/api/upload/cancel" -> handleCancel(session)
            session.method == NanoHTTPD.Method.POST -> handleUpload(session)
            else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    private fun handleCheckStatus(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val fileName = session.parameters["filename"]?.firstOrNull() ?: return errorResponse("Missing filename")
        val uploadId = session.parameters["id"]?.firstOrNull()
        val path     = session.parameters["path"]?.firstOrNull() ?: "/"

        val future = CompletableFuture<NanoHTTPD.Response>()
        
        scope.launch {
            val memProgress = if (uploadId != null) _activeUploads.value[uploadId] else null
            val diskFile = storageRepository.findFileByName(path, fileName).getOrNull()
            
            val actualSize = if (memProgress != null) Math.max(diskFile?.size ?: 0L, memProgress.bytesReceived) else diskFile?.size ?: 0L
            val currentStatus = memProgress?.status ?: if (diskFile != null) TransferStatus.INTERRUPTED.value else "none"
            
            future.complete(jsonResponse(JSONObject().apply {
                put("exists", diskFile != null || actualSize > 0)
                put("size", actualSize)
                put("status", currentStatus)
                put("isPaused", currentStatus == TransferStatus.PAUSED.value)
            }))
        }
        
        return try { future.get() } catch (e: Exception) { errorResponse("Status check failed") }
    }

    private fun handlePause(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uploadId = session.parameters["id"]?.firstOrNull() ?: return errorResponse("Missing upload ID")
        Log.d(TAG, "[$uploadId] handlePause() called")
        
        val job = uploadJobs[uploadId]
        job?.cancel()
        
        _activeUploads.update { current ->
            val existing = current[uploadId]
            if (existing != null) {
                current + (uploadId to existing.copy(status = TransferStatus.PAUSED.value))
            } else current
        }
        
        return jsonResponse(JSONObject().put("success", true))
    }

    private fun handleCancel(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uploadId = session.parameters["id"]?.firstOrNull() ?: return errorResponse("Missing upload ID")
        
        // Use the same cancel logic as app-side cancelUpload
        cancelUpload(uploadId)
        
        return jsonResponse(JSONObject().put("success", true))
    }

    private fun handleUpload(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val parameters = session.parameters
        val path       = parameters["path"]?.firstOrNull() ?: "/"
        val fileName   = parameters["filename"]?.firstOrNull() ?: "uploaded_file"
        val uploadId   = parameters["id"]?.firstOrNull() ?: "${System.currentTimeMillis()}"

        val lock = uploadLocks.computeIfAbsent(uploadId) { Any() }
        synchronized(lock) {
            if (uploadJobs[uploadId] != null) {
                Log.w(TAG, "[$uploadId] Rejecting overlapping upload")
                uploadLocks.remove(uploadId)
                return jsonResponse(JSONObject().apply {
                    put("success", false)
                    put("status", "busy")
                })
            }
        }

        val contentRange = session.headers["content-range"]
        val isResuming   = contentRange != null
        var offset       = 0L
        var totalBytes   = -1L

        if (isResuming) {
            try {
                offset     = "bytes (\\d+)-".toRegex().find(contentRange!!)?.groupValues?.get(1)?.toLong() ?: 0L
                totalBytes = (session.headers["content-length"]?.toLong() ?: 0L) + offset
            } catch (e: Exception) {
                Log.e(TAG, "[$uploadId] Bad Content-Range: $contentRange")
            }
        } else {
            totalBytes = session.headers["content-length"]?.toLong() ?: -1L
        }

        acquireWakeLock()
        
        // Don't allow new upload if current one is paused - browser must wait for resume
        val existingUpload = _activeUploads.value[uploadId]
        if (existingUpload?.status == TransferStatus.PAUSED.value) {
            Log.w(TAG, "[$uploadId] Rejecting upload - currently paused")
            uploadLocks.remove(uploadId)
            return jsonResponse(JSONObject().apply {
                put("success", false)
                put("status", "paused")
                put("isPaused", true)
                put("bytesReceived", existingUpload.bytesReceived)
            })
        }
        
        _activeUploads.update { it - uploadId }
        _activeUploads.update { current ->
            current + (uploadId to UploadProgress(
                id = uploadId,
                fileName = fileName,
                bytesReceived = offset,
                totalBytes = totalBytes,
                percentage = if(totalBytes > 0) (offset * 100 / totalBytes).toInt() else 0,
                status = TransferStatus.UPLOADING.value,
                timestamp = System.currentTimeMillis()
            ))
        }

        val future = CompletableFuture<NanoHTTPD.Response>()

        val job = scope.launch {
            try {
                val result = storageRepository.uploadFile(
                    path        = path,
                    fileName    = fileName,
                    uploadId    = uploadId,
                    inputStream = session.inputStream,
                    totalBytes  = totalBytes,
                    append      = isResuming,
                    onProgress  = { bytesRead ->
                        if (uploadJobs[uploadId]?.isCancelled == true) {
                            throw CancellationException("Upload cancelled")
                        }
                        updateProgress(uploadId, fileName, offset + bytesRead, totalBytes, TransferStatus.UPLOADING.value)
                    }
                )
                result.fold(
                    onSuccess = { fileItem ->
                        val existing = _activeUploads.value[uploadId]
                        val bytesReceived = existing?.bytesReceived ?: offset
                        val currentStatus = existing?.status
                        
                        // Allow 1KB tolerance for completion check
                        val isActuallyComplete = bytesReceived >= totalBytes - 1024
                        
                        if (currentStatus == TransferStatus.PAUSED.value || 
                            currentStatus == TransferStatus.CANCELLED.value ||
                            !isActuallyComplete) {
                            val finalStatus = currentStatus ?: TransferStatus.INTERRUPTED.value
                            future.complete(jsonResponse(JSONObject().apply {
                                put("success", false)
                                put("status", finalStatus)
                                put("isPaused", finalStatus == TransferStatus.PAUSED.value)
                                put("bytesReceived", bytesReceived)
                            }))
                        } else {
                            _activeUploads.update { current ->
                                current + (uploadId to UploadProgress(
                                    id = uploadId,
                                    fileName = fileName,
                                    bytesReceived = totalBytes,
                                    totalBytes = totalBytes,
                                    percentage = 100,
                                    status = TransferStatus.COMPLETED.value,
                                    timestamp = System.currentTimeMillis()
                                ))
                            }
                            future.complete(jsonResponse(JSONObject().put("success", true)))
                            // Keep completed upload visible for 5 seconds
                            scope.launch {
                                delay(5000)
                                _activeUploads.update { it - uploadId }
                            }
                        }
                    },
                    onFailure = { error ->
                        val existing = _activeUploads.value[uploadId]
                        val currentStatus = existing?.status
                        val bytesReceived = existing?.bytesReceived ?: offset
                        
                        if (error is CancellationException || 
                            currentStatus == TransferStatus.PAUSED.value ||
                            currentStatus == TransferStatus.CANCELLED.value) {
                            future.complete(jsonResponse(JSONObject().apply {
                                put("success", false)
                                put("status", currentStatus)
                                put("isPaused", currentStatus == TransferStatus.PAUSED.value)
                                put("bytesReceived", bytesReceived)
                            }))
                        } else if (error is java.io.IOException || error is java.net.SocketException) {
                            updateProgress(uploadId, fileName, bytesReceived, totalBytes, TransferStatus.INTERRUPTED.value)
                            future.complete(jsonResponse(JSONObject().apply {
                                put("success", false)
                                put("status", TransferStatus.INTERRUPTED.value)
                                put("isPaused", false)
                                put("bytesReceived", bytesReceived)
                            }))
                        } else {
                            updateProgress(uploadId, fileName, bytesReceived, totalBytes, TransferStatus.ERROR.value)
                            future.complete(errorResponse(error.message ?: "Upload failed"))
                            scope.launch {
                                delay(3000)
                                _activeUploads.update { it - uploadId }
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                val bytesReceived = _activeUploads.value[uploadId]?.bytesReceived ?: offset
                updateProgress(uploadId, fileName, bytesReceived, totalBytes, TransferStatus.INTERRUPTED.value)
                future.complete(jsonResponse(JSONObject().apply {
                    put("success", false)
                    put("status", "interrupted")
                    put("bytesReceived", bytesReceived)
                }))
            } finally {
                uploadJobs.remove(uploadId)
                uploadLocks.remove(uploadId)
                checkAndReleaseWakeLock()
            }
        }

        uploadJobs[uploadId] = job

        return try { future.get() } catch (e: Exception) { errorResponse(e.message ?: "Upload failed") }
    }

    fun pauseUpload(uploadId: String) {
        Log.d(TAG, "[$uploadId] pauseUpload() called")
        val job = uploadJobs[uploadId]
        job?.cancel()
        _activeUploads.update { current ->
            val existing = current[uploadId]
            if (existing != null) {
                current + (uploadId to existing.copy(status = TransferStatus.PAUSED.value))
            } else current
        }
    }

    fun resumeUpload(uploadId: String) {
        val existing = _activeUploads.value[uploadId]
        if (existing == null) return
        if (existing.status !in listOf(TransferStatus.PAUSED.value, TransferStatus.INTERRUPTED.value)) return
        
        _activeUploads.update { current ->
            current + (uploadId to existing.copy(status = TransferStatus.RESUMING.value))
        }
    }

    fun cancelUpload(uploadId: String) {
        val progress = _activeUploads.value[uploadId]
        val job = uploadJobs[uploadId]
        
        job?.cancel()
        _activeUploads.update { current ->
            val existing = current[uploadId]
            if (existing != null) {
                current + (uploadId to existing.copy(status = TransferStatus.CANCELLED.value))
            } else current
        }
        
        scope.launch {
            try { withTimeout(5000) { job?.join() } } catch (e: Exception) {}
            uploadJobs.remove(uploadId)
            
            if (progress != null) {
                deleteFileForCancel(progress.fileName)
            }
            _activeUploads.update { it - uploadId }
        }
    }
    
    private suspend fun deleteFileForCancel(fileName: String) {
        Log.d(TAG, "Deleting file: $fileName")
        try {
            // Try to find by name directly in MediaStore (most reliable)
            val contentResolver = context.contentResolver
            val projection = arrayOf(MediaStore.Files.FileColumns._ID)
            val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(fileName)
            
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection, selection, selectionArgs, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    val uri = Uri.parse("content://media/external/file/$id")
                    try {
                        val deleted = contentResolver.delete(uri, null, null)
                        if (deleted > 0) {
                            Log.d(TAG, "Deleted file via MediaStore: $uri")
                            return@deleteFileForCancel
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaStore delete failed for $uri", e)
                    }
                }
            }
            
            // Fallback: try repository paths (path "/" gives correct base path)
            val paths = listOf("/", "Download")
            for (path in paths) {
                val result = storageRepository.findFileByName(path, fileName)
                result.onSuccess { file ->
                    file?.id?.let { fileId ->
                        try {
                            storageRepository.deleteFile(fileId)
                            Log.d(TAG, "Deleted file via repository: $fileId")
                            return@deleteFileForCancel
                        } catch (e: Exception) {
                            Log.w(TAG, "Repository delete failed for $fileId", e)
                        }
                    }
                }
            }
            
            Log.w(TAG, "Could not find/delete file: $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Error during file deletion", e)
        }
    }

    private fun updateProgress(uploadId: String, fileName: String, bytesReceived: Long, totalBytes: Long, status: String) {
        val pct = if (totalBytes > 0) ((bytesReceived * 100) / totalBytes).toInt() else 0
        _activeUploads.update { current ->
            val existing = current[uploadId]
            if (existing?.status in listOf(TransferStatus.PAUSED.value, TransferStatus.CANCELLED.value, TransferStatus.COMPLETED.value, TransferStatus.ERROR.value)) {
                return@update current
            }
            current + (uploadId to UploadProgress(
                id            = uploadId,
                fileName      = fileName,
                bytesReceived = bytesReceived,
                totalBytes    = totalBytes,
                percentage    = pct,
                status        = status,
                timestamp     = existing?.timestamp ?: System.currentTimeMillis()
            ))
        }
    }

    @Synchronized private fun acquireWakeLock() {
        if (wakeLock == null || !wakeLock!!.isHeld) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { 
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L) 
            }
        }
    }

    @Synchronized private fun checkAndReleaseWakeLock() {
        if (uploadJobs.isEmpty()) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private fun jsonResponse(obj: JSONObject): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", obj.toString())

    private fun errorResponse(message: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", JSONObject().put("error", message).toString())

    private fun unauthorized(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, "application/json", JSONObject().put("error", "Unauthorized").toString())
}
