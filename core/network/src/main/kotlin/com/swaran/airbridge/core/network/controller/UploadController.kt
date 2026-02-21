package com.swaran.airbridge.core.network.controller

import android.content.Context
import android.os.PowerManager
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

            // Ground-truth offset = actual bytes currently on disk, read via fd.statSize.
            // This is CRITICAL for resume correctness: "wa" mode always appends to the end
            // of the file, so the offset we report must exactly equal the current file length.
            // MediaStore's SIZE column is unreliable (often reports 0 for partial files).
            // Memory (bytesReceived) can be ahead of disk if the write buffer wasn't fully
            // flushed when the upload was paused.
            val diskSize: Long = diskFile?.id?.let { uriStr ->
                try {
                    context.contentResolver.openFileDescriptor(android.net.Uri.parse(uriStr), "r")
                        ?.use { pfd -> pfd.statSize }
                } catch (_: Exception) { null }
            } ?: 0L

            val actualSize = when {
                // Active uploads: memory is the ONLY accurate source because:
                // 1. ParcelFileDescriptor.statSize() returns 0 for partial files being written
                // 2. MediaStore SIZE column is not updated until write completes
                // 3. memProgress tracks actual bytes written via onProgress callback
                memProgress != null -> memProgress.bytesReceived
                // No active upload but file exists on disk (app restart scenario)
                diskSize > 0L -> diskSize
                diskFile != null -> diskFile.size
                else -> 0L
            }
            val currentStatus = memProgress?.status ?: if (diskFile != null) TransferStatus.INTERRUPTED.value else "none"

            future.complete(jsonResponse(JSONObject().apply {
                put("exists", diskFile != null)
                put("size", actualSize)
                put("status", currentStatus)
                put("isPaused", currentStatus == TransferStatus.PAUSED.value)
            }))
        }
        
        return try { future.get() } catch (e: Exception) { errorResponse("Status check failed") }
    }

    private fun handlePause(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uploadId = session.parameters["id"]?.firstOrNull() ?: return errorResponse("Missing upload ID")
        stopUploadJob(uploadId, TransferStatus.PAUSED.value)
        return jsonResponse(JSONObject().put("success", true))
    }

    private fun handleCancel(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uploadId = session.parameters["id"]?.firstOrNull() ?: return errorResponse("Missing upload ID")
        val fileName = session.parameters["filename"]?.firstOrNull()
        val path     = session.parameters["path"]?.firstOrNull() ?: "/"
        
        stopUploadJob(uploadId, TransferStatus.CANCELLED.value)
        
        scope.launch {
            if (fileName != null) {
                storageRepository.findFileByName(path, fileName).onSuccess { it?.let { f -> storageRepository.deleteFile(f.id) } }
            }
            delay(2000)
            _activeUploads.update { it - uploadId }
        }
        return jsonResponse(JSONObject().put("success", true))
    }

    private fun handleUpload(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val parameters = session.parameters
        val path       = parameters["path"]?.firstOrNull() ?: "/"
        val fileName   = parameters["filename"]?.firstOrNull() ?: "uploaded_file"
        val uploadId   = parameters["id"]?.firstOrNull() ?: "${System.currentTimeMillis()}"

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
        // Force-set status to UPLOADING — bypasses terminal-status protection in updateProgress.
        // This is required so that a browser-initiated resume (which arrives as a fresh POST)
        // correctly overrides the previous "paused" status in _activeUploads and prevents the
        // server-sync loop from seeing "isPaused=true" and re-pausing the browser mid-upload.
        forceStartUploading(uploadId, fileName, offset, totalBytes)

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
                        updateProgress(uploadId, fileName, offset + bytesRead, totalBytes, TransferStatus.UPLOADING.value)
                        ensureActive()
                    }
                )
                result.fold(
                    onSuccess = {
                        // Force-set COMPLETED — bypasses terminal "paused" protection so the
                        // app UI correctly shows 100% done even if a late pause raced with completion.
                        forceCompleted(uploadId, totalBytes)
                        future.complete(jsonResponse(JSONObject().put("success", true)))
                        scope.launch {
                            delay(5000)
                            // Only remove if still "completed" — if the user somehow paused right
                            // as the upload finished the cleanup timer must not steal the entry.
                            _activeUploads.update { current ->
                                val existing = current[uploadId]
                                if (existing?.status == TransferStatus.COMPLETED.value) current - uploadId else current
                            }
                        }
                    },
                    onFailure = { error ->
                        val existing = _activeUploads.value[uploadId]
                        val currentStatus = existing?.status
                        val bytesReceived = existing?.bytesReceived ?: offset
                        
                        if (currentStatus == TransferStatus.PAUSED.value || 
                            currentStatus == TransferStatus.CANCELLED.value ||
                            error is CancellationException) {
                            
                            val finalState = currentStatus ?: TransferStatus.INTERRUPTED.value
                            future.complete(jsonResponse(JSONObject().apply {
                                put("success", false)
                                put("status", finalState)
                                put("isPaused", finalState == TransferStatus.PAUSED.value)
                                put("bytesReceived", bytesReceived)
                            }))
                        } else {
                            updateProgress(uploadId, fileName, bytesReceived, totalBytes, TransferStatus.ERROR.value)
                            future.complete(errorResponse(error.message ?: "Upload failed"))
                            scope.launch {
                                delay(10000)
                                _activeUploads.update { current ->
                                    val existing = current[uploadId]
                                    if (existing?.status == TransferStatus.ERROR.value) current - uploadId else current
                                }
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                val existing = _activeUploads.value[uploadId]
                val bytesReceived = existing?.bytesReceived ?: offset
                
                if (existing?.status == TransferStatus.PAUSED.value || e is CancellationException) {
                    future.complete(jsonResponse(JSONObject().apply {
                        put("success", false)
                        put("status", TransferStatus.PAUSED.value)
                        put("isPaused", true)
                        put("bytesReceived", bytesReceived)
                    }))
                } else {
                    updateProgress(uploadId, fileName, bytesReceived, totalBytes, TransferStatus.INTERRUPTED.value)
                    future.complete(jsonResponse(JSONObject().apply {
                        put("success", false)
                        put("status", "interrupted")
                        put("bytesReceived", bytesReceived)
                    }))
                }
            } finally {
                uploadJobs.remove(uploadId)
                checkAndReleaseWakeLock()
            }
        }

        uploadJobs[uploadId] = job
        // Race guard: if stopUploadJob ran before we stored the job, cancel it now.
        val storedStatus = _activeUploads.value[uploadId]?.status
        if (storedStatus == TransferStatus.PAUSED.value || storedStatus == TransferStatus.CANCELLED.value) {
            job.cancel()
        }

        return try {
            future.get()
        } catch (e: Exception) {
            errorResponse(e.message ?: "Upload failed")
        }
    }

    fun pauseUpload(uploadId: String) {
        stopUploadJob(uploadId, TransferStatus.PAUSED.value)
    }

    fun resumeUpload(uploadId: String) {
        _activeUploads.update { current ->
            val existing = current[uploadId]
            // Never resume a completed upload — it would flip to "resuming", trigger the
            // browser to call start(), which then auto-completes based on the full disk size.
            if (existing == null || existing.status == TransferStatus.COMPLETED.value) return@update current
            current + (uploadId to existing.copy(status = TransferStatus.RESUMING.value))
        }
    }

    fun cancelUpload(uploadId: String) {
        val progress = _activeUploads.value[uploadId]
        stopUploadJob(uploadId, TransferStatus.CANCELLED.value)
        scope.launch {
            if (progress != null) {
                storageRepository.findFileByName("/", progress.fileName).onSuccess { it?.let { f -> storageRepository.deleteFile(f.id) } }
            }
            delay(2000)
            _activeUploads.update { it - uploadId }
        }
    }

    private fun stopUploadJob(uploadId: String, newStatus: String) {
        _activeUploads.update { current ->
            val existing = current[uploadId]
            if (existing != null) {
                current + (uploadId to existing.copy(status = newStatus))
            } else current
        }
        uploadJobs[uploadId]?.cancel()
    }

    /**
     * Directly sets the progress entry to UPLOADING, overriding any terminal status
     * (including "paused").  Only call this when a real HTTP upload connection has
     * just been accepted — not from the ongoing onProgress callback.
     */
    private fun forceStartUploading(uploadId: String, fileName: String, bytesReceived: Long, totalBytes: Long) {
        val pct = if (totalBytes > 0) ((bytesReceived * 100) / totalBytes).toInt() else 0
        _activeUploads.update { current ->
            val existing = current[uploadId]
            current + (uploadId to UploadProgress(
                id            = uploadId,
                fileName      = fileName,
                bytesReceived = bytesReceived,
                totalBytes    = totalBytes,
                percentage    = pct,
                status        = TransferStatus.UPLOADING.value,
                timestamp     = existing?.timestamp ?: System.currentTimeMillis()
            ))
        }
    }

    private fun forceCompleted(uploadId: String, totalBytes: Long) {
        _activeUploads.update { current ->
            val existing = current[uploadId] ?: return@update current
            current + (uploadId to existing.copy(
                status        = TransferStatus.COMPLETED.value,
                bytesReceived = totalBytes,
                totalBytes    = totalBytes,
                percentage    = 100
            ))
        }
    }

    private fun updateProgress(uploadId: String, fileName: String, bytesReceived: Long, totalBytes: Long, status: String) {
        val pct = if (totalBytes > 0) ((bytesReceived * 100) / totalBytes).toInt() else 0
        _activeUploads.update { current ->
            val existing = current[uploadId]
            val finalStatus = when (existing?.status) {
                TransferStatus.PAUSED.value, 
                TransferStatus.CANCELLED.value, 
                TransferStatus.COMPLETED.value, 
                TransferStatus.ERROR.value -> existing.status
                else -> status
            }
            current + (uploadId to UploadProgress(
                id            = uploadId,
                fileName      = fileName,
                bytesReceived = bytesReceived,
                totalBytes    = totalBytes,
                percentage    = pct,
                status        = finalStatus,
                timestamp     = existing?.timestamp ?: System.currentTimeMillis()
            ))
        }
    }

    @Synchronized private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { acquire(30 * 60 * 1000L) }
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
