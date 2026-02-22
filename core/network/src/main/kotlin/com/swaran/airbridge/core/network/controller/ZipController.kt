package com.swaran.airbridge.core.network.controller

import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.network.LocalHttpServer
import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.domain.repository.StorageRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ZipController @Inject constructor(
    private val storageRepository: StorageRepository,
    private val sessionTokenManager: SessionTokenManager,
    @Dispatcher(AirDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    server: LocalHttpServer
) : LocalHttpServer.RequestHandler {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        server.registerHandler(this)
    }

    override fun canHandle(session: NanoHTTPD.IHTTPSession): Boolean =
        session.uri.startsWith("/api/zip")

    override fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val token = session.parameters["token"]?.firstOrNull() ?: return unauthorized()
        if (!sessionTokenManager.validateSession(token)) return unauthorized()

        val ids = session.parameters["ids"]?.firstOrNull()?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        if (ids.isEmpty()) return badRequest("No files selected")

        // USE PIPED STREAMS FOR MEMORY EFFICIENCY
        // This avoids building the entire ZIP in memory (preventing OOM)
        val pipedIn = PipedInputStream()
        val pipedOut = PipedOutputStream(pipedIn)

        scope.launch {
            try {
                ZipOutputStream(pipedOut).use { zos ->
                    ids.forEach { id ->
                        val file = storageRepository.getFile(id).getOrNull() ?: return@forEach
                        storageRepository.downloadFile(id).getOrNull()?.use { input ->
                            zos.putNextEntry(ZipEntry(file.name))
                            input.copyTo(zos)
                            zos.closeEntry()
                        }
                    }
                }
            } catch (e: Exception) {
                // Pipe will break and NanoHTTPD will handle the partial stream closure gracefully
            } finally {
                try { pipedOut.close() } catch (_: Exception) {}
            }
        }

        val response = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/zip", pipedIn)
        response.addHeader("Content-Disposition", "attachment; filename=\"AirBridge-Download.zip\"")
        return response
    }

    private fun unauthorized(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, NanoHTTPD.MIME_PLAINTEXT, "Unauthorized")

    private fun badRequest(msg: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, msg)
}
