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

/**
 * Represents the current progress and state of an active file upload.
 *
 * This data class is used in the [activeUploads] StateFlow to track all ongoing
 * transfers in real-time. The UI observes this flow to display progress bars,
 * status text, and control buttons.
 *
 * @property id Unique identifier for this upload (timestamp_random)
 * @property fileName The display name of the file being uploaded
 * @property bytesReceived Number of bytes successfully written to storage
 * @property totalBytes Total expected file size in bytes (-1 if unknown)
 * @property percentage Completion percentage (0-100)
 * @property status Current upload state: "uploading", "paused", "resuming", "completed", "cancelled", "interrupted", or "error"
 * @property timestamp When this upload tracking started (for cleanup purposes)
 */
data class UploadProgress(
    val id: String,
    val fileName: String,
    val bytesReceived: Long,
    val totalBytes: Long,
    val percentage: Int,
    val status: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Central controller managing all file uploads between browser and Android device.
 *
 * ## Architecture Overview
 *
 * This controller implements a bidirectional state machine for file uploads.
 * Both the browser (via HTTP endpoints) and the Android app (via direct method calls)
 * can pause, resume, and cancel uploads. State synchronization happens through
 * [activeUploads] StateFlow which both sides observe.
 *
 * ## State Machine
 *
 * ```
 *          ┌──────────┐
 *     ┌───►│ UPLOADING│◄────────────────┐
 *     │    └────┬─────┘                 │
 *     │         │ pause()               │
 *     │         ▼                       │
 *     │    ┌──────────┐    resume()    │
 *     │    │  PAUSED  │───────────────┘
 *     │    └────┬─────┘
 *     │         │ cancel()
 *     │         ▼
 *     │    ┌──────────┐
 *     │    │ CANCELLED│
 *     │    └────┬─────┘
 *     │         │
 *     │         ▼
 *     │    ┌──────────┐
 *     └───►│COMPLETED │ (on success)
 *          └──────────┘
 * ```
 *
 * ## Key Design Decisions
 *
 * ### 1. Why CompletableFuture + Coroutines?
 * NanoHTTPD's serve() method runs on a thread pool and expects synchronous
 * responses. We use `CompletableFuture.get()` to block the HTTP thread
 * while the actual upload runs in a coroutine (`scope.launch`). This allows:
 * - Non-blocking I/O via coroutines
 * - Proper CancellationException handling
 * - Clean separation between HTTP layer and business logic
 *
 * ### 2. Why uploadLocks?
 * Rapid pause/resume clicks from the browser could create overlapping uploads
 * for the same file. The `uploadLocks` map prevents this by synchronizing
 * access to `uploadJobs` checks.
 *
 * ### 3. Why terminal state protection?
 * The `updateProgress()` method refuses to update bytesReceived if the upload
 * is in a terminal state (PAUSED, CANCELLED, COMPLETED, ERROR). This prevents
 * "ghost progress" from late callbacks after user-initiated pause.
 *
 * ### 4. Why MediaStore query for cancel?
 * Files may be stored via SAF or MediaStore. The `deleteFileForCancel()` method
 * first tries a direct MediaStore query by filename (most reliable), then
 * falls back to repository-based deletion with path guessing.
 *
 * ## Bidirectional Sync Flow
 *
 * ### Browser Pause → Phone UI Updates:
 * 1. Browser calls POST /api/upload/pause
 * 2. Server sets PAUSED status in _activeUploads
 * 3. DashboardViewModel observes StateFlow change
 * 4. Phone UI shows "Paused" + Resume button
 *
 * ### Phone Pause → Browser Updates:
 * 1. User taps Pause in Android app
 * 2. ViewModel calls pauseUpload()
 * 3. Server sets PAUSED status in _activeUploads
 * 4. Browser's sync loop (2s interval) detects isPaused=true
 * 5. Browser calls remotePause() to update UI
 *
 * ### Browser Resume → Phone UI Updates:
 * 1. Browser skip server check and calls POST /api/upload
 * 2. Server accepts because we trust user intent
 * 3. Upload starts, state becomes UPLOADING
 * 4. Phone UI observes and shows progress
 *
 * ### Phone Resume → Browser Updates:
 * 1. User taps Resume in Android app
 * 2. ViewModel calls resumeUpload() → sets RESUMING status
 * 3. Browser's sync loop detects status=resuming
 * 4. Browser calls start() to begin uploading
 *
 * ## Security
 *
 * All HTTP endpoints require a valid session token. The token is generated
 * during QR code pairing and validated against SessionTokenManager.
 *
 * @param context Application context for WakeLock and ContentResolver
 * @param storageRepository Abstraction for file storage (MediaStore/SAF)
 * @param sessionTokenManager Validates session tokens from browser
 * @param ioDispatcher IO dispatcher for upload coroutines
 * @param server The NanoHTTPD server instance to register handlers with
 */
@Singleton
class UploadController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageRepository: StorageRepository,
    private val sessionTokenManager: SessionTokenManager,
    @Dispatcher(AirDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    server: LocalHttpServer
) : LocalHttpServer.RequestHandler {

    /**
     * Observable state of all active uploads.
     *
     * The Dashboard UI collects this StateFlow to display the transfer list.
     * Each upload is keyed by its unique ID. Completed/cancelled uploads
     * are automatically removed after a delay (5s for completed, 3s for error).
     */
    private val _activeUploads = MutableStateFlow<Map<String, UploadProgress>>(emptyMap())
    val activeUploads: StateFlow<Map<String, UploadProgress>> = _activeUploads

    /**
     * Active upload jobs keyed by upload ID.
     *
     * Used to cancel ongoing uploads via Job.cancel(). When a job is cancelled,
     * the CancellationException propagates up and triggers appropriate
     * cleanup in the onFailure or catch blocks.
     */
    private val uploadJobs = ConcurrentHashMap<String, Job>()

    /**
     * Synchronization locks for preventing overlapping uploads.
     *
     * Each upload ID has its own lock object. The synchronized block in
     * handleUpload() checks if a job already exists before starting a new one.
     */
    private val uploadLocks = ConcurrentHashMap<String, Any>()

    /**
     * Coroutine scope for all upload operations.
     *
     * Uses SupervisorJob so child failures don't propagate to siblings
     * (important for parallel uploads). All uploads run on IO dispatcher.
     */
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

    /**
     * Determines if this handler can process the given HTTP session.
     *
     * @return true if URI starts with "/api/upload"
     */
    override fun canHandle(session: NanoHTTPD.IHTTPSession): Boolean =
        session.uri.startsWith("/api/upload")

    /**
     * Routes upload-related HTTP requests to appropriate handlers.
     *
     * Endpoints:
     * - GET/POST /api/upload/status → handleCheckStatus()
     * - POST /api/upload/pause → handlePause()
     * - POST /api/upload/cancel → handleCancel()
     * - POST /api/upload (any other path) → handleUpload()
     *
     * All endpoints require a valid session token.
     */
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

    /**
     * Returns current upload status and file offset for resume calculations.
     *
     * This endpoint is polled by the browser every 2 seconds (sync loop) and
     * once before starting/resuming an upload. It returns:
     * - exists: Whether the file exists in storage
     * - size: Bytes already received (for resume offset calculation)
     * - status: Current upload state
     * - isPaused: Convenience boolean for sync loop checks
     *
     * The size calculation uses max(diskSize, memoryBytes) because MediaStore
     * may lag behind actual bytes written for partial files.
     */
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

    /**
     * Handles browser-initiated pause request.
     *
     * Immediately cancels the upload job and sets PAUSED status.
     * The job's CancellationException will be caught in handleUpload's
     * onFailure block, but the PAUSED status prevents it from updating
     * to ERROR state (terminal state protection in updateProgress).
     */
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

    /**
     * Handles browser-initiated cancel request.
     *
     * Delegates to [cancelUpload] which handles:
     * 1. Cancelling the job
     * 2. Setting CANCELLED status
     * 3. Deleting the partial file
     * 4. Removing from active uploads
     */
    private fun handleCancel(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uploadId = session.parameters["id"]?.firstOrNull() ?: return errorResponse("Missing upload ID")
        
        cancelUpload(uploadId)
        
        return jsonResponse(JSONObject().put("success", true))
    }

    /**
     * Handles the actual file upload POST request.
     *
     * ## Flow
     * 1. Extract Content-Range header to determine resume offset
     * 2. Check uploadLocks to prevent overlapping uploads for same ID
     * 3. Check if existing upload is PAUSED (reject until resumed)
     * 4. Initialize UPLOADING state in _activeUploads
     * 5. Launch coroutine to stream input to storage
     * 6. On progress, update _activeUploads (with terminal state protection)
     * 7. On completion, verify we actually got all bytes (with 1KB tolerance)
     * 8. On cancellation, return current paused/interrupted state
     *
     * ## Resume Logic
     * The Content-Range header format is: `bytes {start}-{end}/{total}`
     * We parse the start byte and use "wa" (write-append) mode on the
     * output stream. The storage layer handles seeking to the correct position.
     *
     * ## Error Handling
     * - IOException/SocketException → INTERRUPTED (resumable)
     * - CancellationException → PAUSED (if paused) or INTERRUPTED
     * - Other exceptions → ERROR (not resumable)
     */
    private fun handleUpload(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val parameters = session.parameters
        val path       = parameters["path"]?.firstOrNull() ?: "/"
        val fileName   = parameters["filename"]?.firstOrNull() ?: "uploaded_file"
        val uploadId   = parameters["id"]?.firstOrNull() ?: "${System.currentTimeMillis()}"

        // Prevent overlapping uploads for same ID
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

        // Parse Content-Range for resume
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
        
        // Reject if upload is currently paused - browser must wait for resume
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
        
        // Initialize fresh state
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
                        // Check for cancellation before updating progress
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
                        
                        // Allow 1KB tolerance for completion (buffer lag)
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

    /**
     * Initiates pause from the Android app side.
     *
     * Cancels the upload job and sets PAUSED status. The browser's sync loop
     * will detect this within 2 seconds and update its UI accordingly.
     *
     * @param uploadId The ID of the upload to pause
     */
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

    /**
     * Initiates resume from the Android app side.
     *
     * Only works if the upload is currently in PAUSED or INTERRUPTED state.
     * Sets RESUMING status which the browser detects via sync loop to
     * begin uploading.
     *
     * @param uploadId The ID of the upload to resume
     */
    fun resumeUpload(uploadId: String) {
        val existing = _activeUploads.value[uploadId]
        if (existing == null) return
        if (existing.status !in listOf(TransferStatus.PAUSED.value, TransferStatus.INTERRUPTED.value)) return
        
        _activeUploads.update { current ->
            current + (uploadId to existing.copy(status = TransferStatus.RESUMING.value))
        }
    }

    /**
     * Cancels an upload and deletes the partial file.
     *
     * This can be triggered from either the browser or Android app.
     * The process:
     * 1. Cancel the job and set CANCELLED status
     * 2. Wait for job to finish (with 5s timeout)
     * 3. Delete the partial file via [deleteFileForCancel]
     * 4. Remove from active uploads
     *
     * @param uploadId The ID of the upload to cancel
     */
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
    
    /**
     * Attempts to delete a file by name when cancel is triggered.
     *
     * Files may be stored via SAF or MediaStore. This method:
     * 1. First tries direct MediaStore query by filename (most reliable)
     * 2. Falls back to repository-based deletion with path guessing
     *
     * @param fileName The name of the file to delete
     */
    private suspend fun deleteFileForCancel(fileName: String) {
        Log.d(TAG, "Deleting file: $fileName")
        try {
            // Primary: MediaStore direct query by filename
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
            
            // Fallback: repository paths
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

    /**
     * Updates the progress state for an upload.
     *
     * ## Terminal State Protection
     * If the upload is already in a terminal state (PAUSED, CANCELLED,
     * COMPLETED, or ERROR), this method returns early without updating.
     * This prevents "ghost progress" from late callbacks after the user
     * has already paused or cancelled.
     *
     * @param uploadId The upload ID
     * @param fileName The file name (for creating new entry if needed)
     * @param bytesReceived Current bytes received
     * @param totalBytes Total expected bytes
     * @param status The new status (typically "uploading")
     */
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

    /**
     * Acquires a partial wake lock to keep CPU active during uploads.
     *
     * The wake lock is non-reference-counted and has a 30-minute timeout.
     * It's released when all uploads complete via [checkAndReleaseWakeLock].
     */
    @Synchronized private fun acquireWakeLock() {
        if (wakeLock == null || !wakeLock!!.isHeld) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { 
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L) 
            }
        }
    }

    /**
     * Releases the wake lock if no uploads are active.
     *
     * Called from upload coroutine's finally block when uploadJobs is empty.
     */
    @Synchronized private fun checkAndReleaseWakeLock() {
        if (uploadJobs.isEmpty()) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    /**
     * Creates a JSON HTTP response.
     *
     * @param obj The JSON object to return
     * @return NanoHTTPD response with application/json content type
     */
    private fun jsonResponse(obj: JSONObject): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", obj.toString())

    /**
     * Creates an error HTTP response.
     *
     * @param message The error message
     * @return NanoHTTPD response with 500 status and JSON error body
     */
    private fun errorResponse(message: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", JSONObject().put("error", message).toString())

    /**
     * Creates an unauthorized HTTP response.
     *
     * @return NanoHTTPD response with 401 status
     */
    private fun unauthorized(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, "application/json", JSONObject().put("error", "Unauthorized").toString())
}
