package com.swaran.airbridge.core.network.controller

import com.swaran.airbridge.core.network.LocalHttpServer
import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.domain.repository.StorageRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Controller for file operations.
 *
 * Handles file browsing, downloading, and listing.
 *
 * @param storageRepository Repository for file storage operations
 * @param sessionTokenManager Manager for session validation
 * @param server Local HTTP server instance
 */
class FileController @Inject constructor(
    private val storageRepository: StorageRepository,
    private val sessionTokenManager: SessionTokenManager,
    server: LocalHttpServer
) : LocalHttpServer.RequestHandler {

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
        val token = session.parameters["token"]?.firstOrNull()
            ?: return unauthorized()

        if (!sessionTokenManager.validateSession(token)) {
            return unauthorized()
        }

        return when {
            session.uri.startsWith("/api/browse") -> handleBrowse(session)
            session.uri.startsWith("/api/download") -> handleDownload(session)
            session.uri.startsWith("/api/files") -> {
                // If 'id' param is present, treat as file download for backward compatibility
                // Otherwise treat as browse/list files
                if (session.parameters["id"] != null) {
                    handleDownload(session)
                } else {
                    handleBrowse(session)
                }
            }

            else -> notFound()
        }
    }

    /**
     * Handles file browsing/listing requests.
     *
     * Endpoint: GET /api/browse?path=/&token=...
     *           GET /api/files?path=/&token=...
     */
    private fun handleBrowse(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val path = session.parameters["path"]?.firstOrNull() ?: "/"

        return runBlocking {
            storageRepository.browseFiles(path)
                .fold(
                    onSuccess = { files ->
                        jsonResponse(files)
                    },
                    onFailure = { error ->
                        errorResponse(error.message ?: "Unknown error")
                    }
                )
        }
    }

    /**
     * Handles file download requests.
     *
     * Endpoint: GET /api/download?id=...&token=...
     *           GET /api/files?id=...&token=...
     */
    private fun handleDownload(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        // Support both /api/download/${id} and /api/download?id=... patterns
        val fileId = session.parameters["id"]?.firstOrNull()
            ?: session.uri.substringAfter("/api/download/").takeIf { it.isNotBlank() }
            ?: return errorResponse("Missing file id")

        return runBlocking {
            storageRepository.downloadFile(fileId)
                .fold(
                    onSuccess = { stream ->
                        NanoHTTPD.newChunkedResponse(
                            NanoHTTPD.Response.Status.OK,
                            "application/octet-stream",
                            stream
                        )
                    },
                    onFailure = { error ->
                        errorResponse(error.message ?: "Download failed")
                    }
                )
        }
    }

    private fun jsonResponse(files: List<FileItem>): NanoHTTPD.Response {
        val jsonArray = JSONArray()
        files.forEach { file ->
            jsonArray.put(JSONObject().apply {
                put("id", file.id)
                put("name", file.name)
                put("path", file.path)
                put("size", file.size)
                put("mimeType", file.mimeType)
                put("isDirectory", file.isDirectory)
                put("lastModified", file.lastModified)
            })
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            jsonArray.toString()
        )
    }

    private fun errorResponse(message: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.INTERNAL_ERROR,
            "application/json",
            JSONObject().put("error", message).toString()
        )
    }

    private fun unauthorized(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.UNAUTHORIZED,
            "application/json",
            JSONObject().put("error", "Invalid or missing token").toString()
        )
    }

    private fun notFound(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.NOT_FOUND,
            NanoHTTPD.MIME_PLAINTEXT,
            "Not Found"
        )
    }
}
