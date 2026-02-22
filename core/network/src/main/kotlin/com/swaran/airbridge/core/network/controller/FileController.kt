package com.swaran.airbridge.core.network.controller

import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.network.LocalHttpServer
import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.domain.repository.StorageRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import javax.inject.Inject

class FileController @Inject constructor(
    private val storageRepository: StorageRepository,
    private val sessionTokenManager: SessionTokenManager,
    @Dispatcher(AirDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    server: LocalHttpServer
) : LocalHttpServer.RequestHandler {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        server.registerHandler(this)
    }

    override fun canHandle(session: NanoHTTPD.IHTTPSession): Boolean {
        val uri = session.uri
        return uri.startsWith("/api/files") ||
                uri.startsWith("/api/browse") ||
                uri.startsWith("/api/download")
    }

    override fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val token = session.parameters["token"]?.firstOrNull() ?: return unauthorized()
        if (!sessionTokenManager.validateSession(token)) return unauthorized()

        return when {
            session.uri.startsWith("/api/browse") -> handleBrowse(session)
            session.uri.startsWith("/api/download") -> handleDownload(session)
            session.uri.startsWith("/api/files") -> {
                if (session.parameters["id"] != null) handleDownload(session) else handleBrowse(session)
            }
            else -> notFound()
        }
    }

    private fun handleBrowse(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val path = session.parameters["path"]?.firstOrNull() ?: "/"
        val future = CompletableFuture<NanoHTTPD.Response>()

        scope.launch {
            storageRepository.browseFiles(path).fold(
                onSuccess = { future.complete(jsonResponse(it)) },
                onFailure = { future.complete(errorResponse(it.message ?: "Browse failed")) }
            )
        }
        return try { future.get() } catch (e: Exception) { errorResponse("Browse failed") }
    }

    private fun handleDownload(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val fileId = session.parameters["id"]?.firstOrNull()
            ?: session.uri.substringAfter("/api/download/").takeIf { it.isNotBlank() }
            ?: return errorResponse("Missing file id")

        val future = CompletableFuture<NanoHTTPD.Response>()

        scope.launch {
            val fileResult = storageRepository.getFile(fileId)
            val fileItem = fileResult.getOrNull() ?: run {
                future.complete(errorResponse("File not found"))
                return@launch
            }

            storageRepository.downloadFile(fileId).fold(
                onSuccess = { inputStream ->
                    val rangeHeader = session.headers["range"]
                    var response: NanoHTTPD.Response

                    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                        // Robust HTTP Range Support (resumable downloads)
                        try {
                            val range = rangeHeader.substring(6).split("-")
                            val start = range[0].toLong()
                            val end = if (range.size > 1 && range[1].isNotEmpty()) range[1].toLong() else fileItem.size - 1
                            
                            inputStream.skip(start)
                            val contentLength = end - start + 1
                            
                            response = NanoHTTPD.newFixedLengthResponse(
                                NanoHTTPD.Response.Status.PARTIAL_CONTENT,
                                fileItem.mimeType,
                                inputStream,
                                contentLength
                            )
                            response.addHeader("Content-Range", "bytes $start-$end/${fileItem.size}")
                        } catch (e: Exception) {
                            response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE, NanoHTTPD.MIME_PLAINTEXT, "")
                        }
                    } else {
                        response = NanoHTTPD.newFixedLengthResponse(
                            NanoHTTPD.Response.Status.OK,
                            fileItem.mimeType,
                            inputStream,
                            fileItem.size
                        )
                    }
                    
                    response.addHeader("Accept-Ranges", "bytes")
                    response.addHeader("Content-Disposition", "attachment; filename=\"${fileItem.name}\"")
                    future.complete(response)
                },
                onFailure = { future.complete(errorResponse("Download failed")) }
            )
        }
        return try { future.get() } catch (e: Exception) { errorResponse("Initialization failed") }
    }

    private fun jsonResponse(files: List<FileItem>): NanoHTTPD.Response {
        val arr = JSONArray()
        files.forEach { file ->
            arr.put(JSONObject().apply {
                put("id", file.id); put("name", file.name); put("path", file.path)
                put("size", file.size); put("mimeType", file.mimeType)
                put("isDirectory", file.isDirectory); put("lastModified", file.lastModified)
            })
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", arr.toString())
    }

    private fun errorResponse(msg: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", JSONObject().put("error", msg).toString())

    private fun unauthorized(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, "application/json", JSONObject().put("error", "Unauthorized").toString())

    private fun notFound(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not Found")
}
