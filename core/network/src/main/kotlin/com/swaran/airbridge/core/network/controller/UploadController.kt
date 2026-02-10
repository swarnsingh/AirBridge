package com.swaran.airbridge.core.network.controller

import android.util.Log
import com.google.gson.Gson
import com.swaran.airbridge.core.network.LocalHttpServer
import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.domain.repository.StorageRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

data class UploadProgress(
    val fileName: String,
    val bytesReceived: Long,
    val totalBytes: Long,
    val percentage: Int,
    val status: String
)

@Singleton
class UploadController @Inject constructor(
    private val storageRepository: StorageRepository,
    private val sessionTokenManager: SessionTokenManager,
    server: LocalHttpServer
) : LocalHttpServer.RequestHandler {

    private val gson = Gson()
    private val _activeUploads = MutableStateFlow<Map<String, UploadProgress>>(emptyMap())
    val activeUploads: StateFlow<Map<String, UploadProgress>> = _activeUploads

    init {
        server.registerHandler(this)
        Log.d(TAG, "UploadController initialized")
    }

    companion object {
        private const val TAG = "UploadController"
    }

    override fun canHandle(session: NanoHTTPD.IHTTPSession): Boolean {
        return session.uri.startsWith("/api/upload") && session.method == NanoHTTPD.Method.POST
    }

    override fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val token = session.parameters["token"]?.firstOrNull()
            ?: return unauthorized()

        if (!sessionTokenManager.validateSession(token)) {
            Log.w(TAG, "Upload rejected: invalid token")
            return unauthorized()
        }

        val path = session.parameters["path"]?.firstOrNull() ?: "/"

        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)

            val uploadedFile = files["file"]
                ?: return errorResponse("No file uploaded")

            val fileName = session.parameters["filename"]?.firstOrNull()
                ?: File(uploadedFile).name

            Log.d(TAG, "Starting upload: $fileName")

            val uploadId = "${System.currentTimeMillis()}_${fileName.hashCode()}"
            val file = File(uploadedFile)
            val totalBytes = file.length()

            val current = _activeUploads.value.toMutableMap()
            current[uploadId] = UploadProgress(
                fileName = fileName,
                bytesReceived = 0L,
                totalBytes = totalBytes,
                percentage = 0,
                status = "uploading"
            )
            _activeUploads.value = current

            runBlocking {
                var bytesRead = 0L
                val inputStream = file.inputStream().buffered()
                val monitoredInputStream = object : InputStream() {
                    override fun read(): Int {
                        val byte = inputStream.read()
                        if (byte != -1) {
                            bytesRead++
                            updateProgress(uploadId, fileName, bytesRead, totalBytes)
                        }
                        return byte
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val count = inputStream.read(b, off, len)
                        if (count > 0) {
                            bytesRead += count
                            updateProgress(uploadId, fileName, bytesRead, totalBytes)
                        }
                        return count
                    }
                }

                storageRepository.uploadFile(
                    path = path,
                    fileName = fileName,
                    inputStream = monitoredInputStream
                ).fold(
                    onSuccess = { result ->
                        Log.i(TAG, "Upload completed: $fileName ($totalBytes bytes)")
                        val finalMap = _activeUploads.value.toMutableMap()
                        finalMap[uploadId] = UploadProgress(
                            fileName = fileName,
                            bytesReceived = totalBytes,
                            totalBytes = totalBytes,
                            percentage = 100,
                            status = "completed"
                        )
                        _activeUploads.value = finalMap
                        jsonResponse(mapOf(
                            "success" to true,
                            "file" to result
                        ))
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Upload failed: $fileName - ${error.message}", error)
                        val finalMap = _activeUploads.value.toMutableMap()
                        finalMap[uploadId] = UploadProgress(
                            fileName = fileName,
                            bytesReceived = bytesRead,
                            totalBytes = totalBytes,
                            percentage = 0,
                            status = "error"
                        )
                        _activeUploads.value = finalMap
                        errorResponse(error.message ?: "Upload failed")
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception: ${e.message}", e)
            errorResponse(e.message ?: "Upload failed")
        }
    }

    private fun updateProgress(uploadId: String, fileName: String, bytesReceived: Long, totalBytes: Long) {
        val percentage = if (totalBytes > 0) ((bytesReceived * 100) / totalBytes).toInt() else 0
        val current = _activeUploads.value.toMutableMap()
        current[uploadId] = UploadProgress(
            fileName = fileName,
            bytesReceived = bytesReceived,
            totalBytes = totalBytes,
            percentage = percentage,
            status = "uploading"
        )
        _activeUploads.value = current
    }

    private fun jsonResponse(data: Any): NanoHTTPD.Response {
        val json = gson.toJson(data)
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            json
        )
    }

    private fun errorResponse(message: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.INTERNAL_ERROR,
            "application/json",
            gson.toJson(mapOf("error" to message))
        )
    }

    private fun unauthorized(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.UNAUTHORIZED,
            "application/json",
            gson.toJson(mapOf("error" to "Invalid or missing token"))
        )
    }
}
