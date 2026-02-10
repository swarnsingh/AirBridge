package com.swaran.airbridge.core.network.controller

import com.swaran.airbridge.core.network.LocalHttpServer
import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.domain.repository.StorageRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

class ZipController @Inject constructor(
    private val storageRepository: StorageRepository,
    private val sessionTokenManager: SessionTokenManager,
    server: LocalHttpServer
) : LocalHttpServer.RequestHandler {

    init {
        server.registerHandler(this)
    }

    override fun canHandle(session: NanoHTTPD.IHTTPSession): Boolean {
        return session.uri.startsWith("/api/zip")
    }

    override fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val token = session.parameters["token"]?.firstOrNull()
            ?: return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.UNAUTHORIZED,
                NanoHTTPD.MIME_PLAINTEXT,
                "Unauthorized"
            )

        if (!sessionTokenManager.validateSession(token)) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.UNAUTHORIZED,
                NanoHTTPD.MIME_PLAINTEXT,
                "Invalid session"
            )
        }

        val ids = session.parameters["ids"]?.firstOrNull()
            ?.split(",")
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        if (ids.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                NanoHTTPD.MIME_PLAINTEXT,
                "No files selected"
            )
        }

        return runBlocking {
            try {
                val zipBytes = createZip(ids)
                val response = NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    "application/zip",
                    ByteArrayInputStream(zipBytes),
                    zipBytes.size.toLong()
                )
                response.addHeader("Content-Disposition", "attachment; filename=\"AirBridge-Download.zip\"")
                response
            } catch (e: Exception) {
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.INTERNAL_ERROR,
                    NanoHTTPD.MIME_PLAINTEXT,
                    "Failed to create ZIP: ${e.message}"
                )
            }
        }
    }

    private suspend fun createZip(fileIds: List<String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            fileIds.forEach { id ->
                val fileResult = storageRepository.getFile(id)
                if (fileResult.isSuccess) {
                    val file = fileResult.getOrNull() ?: return@forEach
                    val streamResult = storageRepository.downloadFile(id)
                    if (streamResult.isSuccess) {
                        val entry = ZipEntry(file.name)
                        zos.putNextEntry(entry)
                        streamResult.getOrNull()?.use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                }
            }
        }
        return baos.toByteArray()
    }
}
