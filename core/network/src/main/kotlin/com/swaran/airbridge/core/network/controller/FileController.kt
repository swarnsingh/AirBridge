package com.swaran.airbridge.core.network.controller

import com.google.gson.Gson
import com.swaran.airbridge.core.network.LocalHttpServer
import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.domain.repository.StorageRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import javax.inject.Inject

class FileController @Inject constructor(
    private val storageRepository: StorageRepository,
    private val sessionTokenManager: SessionTokenManager,
    server: LocalHttpServer
) : LocalHttpServer.RequestHandler {

    private val gson = Gson()

    init {
        server.registerHandler(this)
    }

    override fun canHandle(session: NanoHTTPD.IHTTPSession): Boolean {
        val uri = session.uri
        return uri.startsWith("/api/files") || uri.startsWith("/api/browse")
    }

    override fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val token = session.parameters["token"]?.firstOrNull()
            ?: return unauthorized()

        if (!sessionTokenManager.validateSession(token)) {
            return unauthorized()
        }

        return when {
            session.uri.startsWith("/api/browse") -> handleBrowse(session)
            session.uri.startsWith("/api/files") -> handleFile(session)
            else -> notFound()
        }
    }

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

    private fun handleFile(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val fileId = session.parameters["id"]?.firstOrNull()
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

    private fun notFound(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.NOT_FOUND,
            NanoHTTPD.MIME_PLAINTEXT,
            "Not Found"
        )
    }
}
