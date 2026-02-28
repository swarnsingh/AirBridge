package com.swaran.airbridge.core.network.ktor.routes

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.core.network.ktor.QueryParams
import com.swaran.airbridge.core.network.ktor.ResponseFields
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.domain.repository.StorageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.http.CacheControl
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeFully
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses HTTP Range header for resumable downloads.
 *
 * Format: `bytes={start}-{end}` or `bytes={start}-`
 * If end is omitted, it means "to end of file"
 *
 * @param range The Range header value (e.g., "bytes=1024-2047")
 * @param total The total file size in bytes
 * @return Pair of (start, end) byte positions, inclusive
 */
fun parseRange(range: String?, total: Long): Pair<Long, Long> {
    if (range == null) return 0L to (total - 1)

    val match = Regex("""bytes=(\d+)-(\d*)""").find(range)
        ?: return 0L to (total - 1)

    val start = match.groupValues[1].toLong()
    val end = match.groupValues[2].takeIf { it.isNotEmpty() }
        ?.toLong() ?: (total - 1)

    // Clamp to valid range
    val clampedStart = start.coerceIn(0, total - 1)
    val clampedEnd = end.coerceIn(clampedStart, total - 1)

    return clampedStart to clampedEnd
}

/**
 * File browse and download routes for Ktor.
 *
 * ## Routes
 *
 * - `GET /api/browse?path={path}&token={token}` - List files in directory
 * - `GET /api/download/{id}?token={token}` - Download file with Range support
 *
 * ## Range Support (Resume Downloads)
 *
 * The download endpoint properly supports HTTP Range requests:
 * - Parses `Range: bytes={start}-{end}` header
 * - Returns `416 Requested Range Not Satisfiable` if start >= file size
 * - Returns `206 Partial Content` for range requests
 * - Uses `FileChannel` for correct SAF seeking (not buggy InputStream.skip)
 * - Sets `Accept-Ranges: bytes` and `Content-Range` headers
 *
 * ## SAF Correctness
 *
 * Uses `FileChannel.position()` via `ParcelFileDescriptor` for reliable seeking
 * in Storage Access Framework files with proper range request support.
 * This is the only reliable way to seek in Storage Access Framework files.
 */
@Singleton
class FileRoutes @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageRepository: StorageRepository,
    private val sessionTokenManager: SessionTokenManager
) {

    /**
     * Installs file routes into the Ktor routing configuration.
     */
    fun install(routing: Route) {
        routing.apply {
            browseRoute()
            filesRoute()  // Alias for /api/files
            downloadRoute()
        }
    }

    private fun Route.browseRoute() {
        get("/api/browse") {
            handleBrowse(call)
        }
    }
    
    private fun Route.filesRoute() {
        // Alias: /api/files is same as /api/browse (backwards compatibility)
        get("/api/files") {
            handleBrowse(call)
        }
    }
    
    private suspend fun handleBrowse(call: ApplicationCall) {
        val token = call.request.queryParameters[QueryParams.TOKEN]
            ?: return call.respond(HttpStatusCode.Unauthorized, buildJsonObject { put(ResponseFields.ERROR, "Missing token") })

        if (!sessionTokenManager.validateSession(token)) {
            return call.respond(HttpStatusCode.Unauthorized, buildJsonObject { put(ResponseFields.ERROR, "Invalid token") })
        }

        val path = call.request.queryParameters[QueryParams.PATH] ?: "/"

        val result = storageRepository.browseFiles(path)
        result.fold(
            onSuccess = { files ->
                call.response.cacheControl(CacheControl.NoStore(CacheControl.Visibility.Private))
                call.respond(HttpStatusCode.OK, files.map { it.toJson() })
            },
            onFailure = { error ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    buildJsonObject { put(ResponseFields.ERROR, error.message ?: "Browse failed") }
                )
            }
        )
    }

    private fun Route.downloadRoute() {
        get("/api/download/{${QueryParams.FILE_ID}}") {
            val token = call.request.queryParameters[QueryParams.TOKEN]
                ?: return@get call.respond(HttpStatusCode.Unauthorized, buildJsonObject { put(ResponseFields.ERROR, "Missing token") })

            if (!sessionTokenManager.validateSession(token)) {
                return@get call.respond(HttpStatusCode.Unauthorized, buildJsonObject { put(ResponseFields.ERROR, "Invalid token") })
            }

            val fileId = call.parameters[QueryParams.FILE_ID]
                ?: return@get call.respond(HttpStatusCode.BadRequest, buildJsonObject { put(ResponseFields.ERROR, "Missing file id") })

            val fileResult = storageRepository.getFile(fileId)
            val file = fileResult.getOrNull()
                ?: return@get call.respond(HttpStatusCode.NotFound, buildJsonObject { put(ResponseFields.ERROR, "File not found") })

            val totalSize = file.size
            val rangeHeader = call.request.header(HttpHeaders.Range)
            val (start, end) = parseRange(rangeHeader, totalSize)

            // Staff review guard: Return 416 if start >= total size
            // This prevents corrupt resumes and browser infinite retry loops
            if (start >= totalSize) {
                call.response.header(HttpHeaders.ContentRange, "bytes */$totalSize")
                call.respond(
                    HttpStatusCode.RequestedRangeNotSatisfiable,
                    buildJsonObject { put(ResponseFields.ERROR, "Range start $start exceeds file size $totalSize") }
                )
                return@get
            }

            // SAF-correct: Open FileChannel at offset (not InputStream.skip)
            val uri = Uri.parse(file.id)
            val channel = openFileChannel(uri, start)
                ?: return@get call.respond(
                    HttpStatusCode.InternalServerError,
                    buildJsonObject { put(ResponseFields.ERROR, "Cannot open file") }
                )

            try {
                val contentLength = end - start + 1
                val isPartial = start > 0 || rangeHeader != null

                call.response.header(HttpHeaders.AcceptRanges, "bytes")

                if (isPartial) {
                    call.response.header(
                        HttpHeaders.ContentRange,
                        "bytes $start-$end/$totalSize"
                    )
                }

                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(
                        ContentDisposition.Parameters.FileName, file.name
                    ).toString()
                )

                call.response.cacheControl(CacheControl.NoStore(CacheControl.Visibility.Private))

                call.respondBytesWriter(
                    status = if (isPartial) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                    contentType = ContentType.defaultForFilePath(file.name)
                ) {
                    val buffer = ByteBuffer.allocate(64 * 1024)
                    var remaining = contentLength

                    while (remaining > 0) {
                        buffer.clear()
                        val bytesToRead = minOf(buffer.capacity().toLong(), remaining).toInt()
                        val read = channel.read(buffer)

                        if (read <= 0) break

                        buffer.flip()
                        val toWrite = minOf(buffer.remaining().toLong(), remaining).toInt()
                        writeFully(buffer.array(), 0, toWrite)
                        remaining -= toWrite
                    }
                }
            } finally {
                channel.close()
            }
        }
    }

    private fun FileItem.toJson(): kotlinx.serialization.json.JsonObject {
        return buildJsonObject {
            put(ResponseFields.ID, id)
            put(ResponseFields.NAME, name)
            put(ResponseFields.PATH_FIELD, path)
            put(ResponseFields.SIZE, size)
            put(ResponseFields.MIME_TYPE, mimeType)
            put(ResponseFields.IS_DIRECTORY, isDirectory)
            put(ResponseFields.LAST_MODIFIED, lastModified)
        }
    }

    /**
     * Opens a SAF file as a [FileChannel] at the specified offset.
     *
     * This is the **correct** way to seek in SAF files (unlike InputStream.skip which is
     * unreliable for large offsets). Uses FileDescriptor from ParcelFileDescriptor.
     *
     * @param uri The SAF content URI
     * @param offset The byte position to seek to
     * @return FileChannel positioned at offset, or null if failed (caller must close)
     */
    private fun openFileChannel(uri: Uri, offset: Long): FileChannel? {
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return null
            val channel = FileInputStream(pfd.fileDescriptor).channel
            channel.position(offset)
            channel
        } catch (e: Exception) {
            pfd?.close()
            null
        }
    }
}
