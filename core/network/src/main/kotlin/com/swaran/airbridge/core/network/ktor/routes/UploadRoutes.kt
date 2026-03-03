package com.swaran.airbridge.core.network.ktor.routes

import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.core.network.ktor.QueryParams
import com.swaran.airbridge.core.network.ktor.ResponseFields
import com.swaran.airbridge.core.network.upload.UploadQueueManager
import com.swaran.airbridge.core.network.upload.UploadScheduler
import com.swaran.airbridge.domain.model.UploadRequest
import com.swaran.airbridge.domain.model.UploadResult
import com.swaran.airbridge.domain.model.UploadState
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveStream
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP layer for upload endpoints - Protocol v2 with Queue Manager.
 *
 * Simplified deterministic protocol:
 * - POST is idempotent
 * - Disk size is source of truth
 * - Queue manager handles parallel execution
 */
@Singleton
class UploadRoutes @Inject constructor(
    private val scheduler: UploadScheduler,
    private val queueManager: UploadQueueManager,
    private val sessionTokenManager: SessionTokenManager,
    private val logger: AirLogger
) {
    companion object {
        private const val TAG = "UploadRoutes"
        private const val MAX_FILENAME_LENGTH = 255
        private const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024 * 1024 // 50 GB
    }

    fun install(routing: Route) {
        routing.apply {
            uploadStatusRoute()
            uploadStatusSseRoute()
            uploadPostRoute()
            uploadPauseRoute()
            uploadResumeRoute()
            uploadCancelRoute()
            uploadMetricsRoute()
        }
    }

    private fun Route.uploadStatusRoute() {
        get("/api/upload/status") {
            val token = call.request.queryParameters[QueryParams.TOKEN]
                ?: return@get call.respond(HttpStatusCode.Unauthorized, errorJson("Missing token"))

            if (!sessionTokenManager.validateSession(token)) {
                return@get call.respond(HttpStatusCode.Unauthorized, errorJson("Invalid token"))
            }

            val fileName = call.request.queryParameters[QueryParams.FILENAME]
                ?: return@get call.respond(HttpStatusCode.BadRequest, errorJson("Missing filename"))

            val path = call.request.queryParameters[QueryParams.PATH] ?: "/"
            val uploadId = call.request.queryParameters[QueryParams.UPLOAD_ID]

            val status = scheduler.queryStatus(path, fileName, uploadId)

            call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                put("exists", status.exists)
                put("bytesReceived", status.bytesReceived)
                put("status", status.state)
                put("canResume", status.canResume)
                put("isBusy", status.isBusy)
            })
        }
    }

    private fun Route.uploadStatusSseRoute() {
        get("/api/upload/events") {
            val token = call.request.queryParameters[QueryParams.TOKEN]
                ?: return@get call.respond(HttpStatusCode.Unauthorized, errorJson("Missing token"))

            if (!sessionTokenManager.validateSession(token)) {
                return@get call.respond(HttpStatusCode.Unauthorized, errorJson("Invalid token"))
            }

            val filterUploadId = call.request.queryParameters[QueryParams.UPLOAD_ID]

            call.response.cacheControl(CacheControl.NoCache(null))

            val flow = callbackFlow {
                // Combine upload states + queue state
                combine(
                    scheduler.activeUploads,
                    queueManager.queueState
                ) { uploads, queueState ->
                    Pair(uploads, queueState)
                }.collect { (uploads, queueState) ->
                    // Send individual upload events
                    val filtered = if (filterUploadId != null) {
                        uploads[filterUploadId]?.let { listOf(it) } ?: emptyList()
                    } else {
                        uploads.values.toList()
                    }

                    filtered.forEach { status ->
                        trySend(formatUploadEvent(status))
                    }

                    // Send queue state event
                    trySend(formatQueueEvent(queueState))
                }

                awaitClose { }
            }

            call.respondText(
                contentType = ContentType.Text.EventStream,
                provider = {
                    buildString {
                        flow.collectLatest { event ->
                            append(event)
                            append("\n")
                        }
                    }
                }
            )
        }
    }

    private fun formatUploadEvent(status: com.swaran.airbridge.domain.model.UploadStatus): String {
        return buildString {
            append("data: {")
            append("\"type\":\"upload\",")
            append("\"${ResponseFields.UPLOAD_ID}\":\"${status.metadata.uploadId}\",")
            append("\"fileName\":\"${status.metadata.displayName}\",")
            append("\"state\":\"${status.state.value}\",")
            append("\"bytesReceived\":${status.bytesReceived},")
            append("\"totalBytes\":${status.metadata.totalBytes},")
            append("\"progress\":${status.progressPercent}")
            append("}\n\n")
        }
    }

    private fun formatQueueEvent(state: UploadQueueManager.QueueState): String {
        return buildString {
            append("data: {")
            append("\"type\":\"queue\",")
            append("\"isPaused\":${state.isPaused},")
            append("\"active\":${state.activeCount},")
            append("\"queued\":${state.queuedCount},")
            append("\"paused\":${state.pausedCount}")
            append("}\n\n")
        }
    }

    private fun Route.uploadPostRoute() {
        post("/api/upload") {
            val token = call.request.queryParameters[QueryParams.TOKEN]
                ?: return@post call.respond(HttpStatusCode.Unauthorized, errorJson("Missing token"))

            if (!sessionTokenManager.validateSession(token)) {
                return@post call.respond(HttpStatusCode.Unauthorized, errorJson("Invalid token"))
            }

            val path = call.request.queryParameters[QueryParams.PATH] ?: "/"
            val rawFileName = call.request.queryParameters[QueryParams.FILENAME] ?: "file"
            val fileName = sanitizeFileName(rawFileName)

            if (fileName.isBlank()) {
                return@post call.respond(HttpStatusCode.BadRequest, errorJson("Invalid filename"))
            }

            val uploadId = call.request.queryParameters[QueryParams.UPLOAD_ID] ?: UUID.randomUUID().toString()
            val contentRange = call.request.header("Content-Range")
            val contentLength = call.request.header("Content-Length")?.toLongOrNull()
            val (offset, totalBytes) = parseContentRange(contentRange, contentLength)

            if (totalBytes > MAX_FILE_SIZE_BYTES) {
                return@post call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    errorJson("File too large (max 50GB)")
                )
            }

            val request = UploadRequest(
                uploadId = uploadId,
                fileUri = "",
                fileName = fileName,
                path = path,
                offset = offset,
                totalBytes = totalBytes,
                contentRange = contentRange
            )

            logger.d(TAG, "uploadPost", "[$uploadId] POST received: $fileName, offset=$offset, total=$totalBytes")

            // Enqueue and execute via queue manager
            val result = queueManager.enqueue(request) { call.receiveStream() }

            logger.d(TAG, "uploadPost", "[$uploadId] Result: ${result.javaClass.simpleName}")

            when (result) {
                is UploadResult.Success -> {
                    call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                        put(ResponseFields.SUCCESS, true)
                        put(ResponseFields.UPLOAD_ID, result.uploadId)
                        put(ResponseFields.BYTES_RECEIVED, result.bytesReceived)
                        put("state", "completed")
                    })
                }

                is UploadResult.Busy -> {
                    logger.d(TAG, "uploadPost", "[$uploadId] Returning 409 busy (retryAfterMs=${result.retryAfterMs})")
                    call.respondNoCache(HttpStatusCode.Conflict, buildJsonObject {
                        put(ResponseFields.SUCCESS, false)
                        put(ResponseFields.UPLOAD_ID, result.uploadId)
                        put("status", "busy")
                        put("retryAfterMs", result.retryAfterMs)
                        put("message", "Server busy - retry with backoff")
                    })
                }

                is UploadResult.Paused -> {
                    call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                        put(ResponseFields.SUCCESS, false)
                        put(ResponseFields.UPLOAD_ID, result.uploadId)
                        put(ResponseFields.BYTES_RECEIVED, result.bytesReceived)
                        put("state", "paused")
                    })
                }

                is UploadResult.Cancelled -> {
                    call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                        put(ResponseFields.SUCCESS, false)
                        put(ResponseFields.UPLOAD_ID, result.uploadId)
                        put("state", "cancelled")
                    })
                }

                is UploadResult.Failure.FileDeleted -> {
                    logger.w(TAG, "uploadPost", "[$uploadId] File deleted externally during upload")
                    call.respondNoCache(HttpStatusCode.Gone, buildJsonObject {
                        put(ResponseFields.SUCCESS, false)
                        put(ResponseFields.UPLOAD_ID, result.uploadId)
                        put(ResponseFields.BYTES_RECEIVED, result.bytesReceived)
                        put("state", "error")
                        put("error", "file_deleted")
                        put("message", "File was deleted externally")
                    })
                }

                is UploadResult.Failure.OffsetMismatch -> {
                    logger.w(TAG, "uploadPost", "[$uploadId] Offset mismatch: expected=${result.expectedOffset}, disk=${result.actualDiskSize}")
                    call.respondNoCache(HttpStatusCode.Conflict, buildJsonObject {
                        put(ResponseFields.SUCCESS, false)
                        put(ResponseFields.UPLOAD_ID, result.uploadId)
                        put(ResponseFields.BYTES_RECEIVED, result.bytesReceived)
                        put("state", "error")
                        put("error", "offset_mismatch")
                        put("expectedOffset", result.actualDiskSize)
                    })
                }

                is UploadResult.Failure -> {
                    logger.e(TAG, "uploadPost", "[$uploadId] Error: ${result.javaClass.simpleName}")
                    call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                        put(ResponseFields.SUCCESS, false)
                        put(ResponseFields.UPLOAD_ID, result.uploadId)
                        put(ResponseFields.BYTES_RECEIVED, result.bytesReceived)
                        put("state", "error")
                        put("error", result.javaClass.simpleName)
                    })
                }

                else -> {
                    call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                        put(ResponseFields.SUCCESS, false)
                        put("state", "error")
                    })
                }
            }
        }
    }

    private fun Route.uploadPauseRoute() {
        post("/api/upload/pause") {
            val token = call.request.queryParameters[QueryParams.TOKEN]
                ?: return@post call.respond(HttpStatusCode.Unauthorized, errorJson("Missing token"))

            if (!sessionTokenManager.validateSession(token)) {
                return@post call.respond(HttpStatusCode.Unauthorized, errorJson("Invalid token"))
            }

            val uploadId = call.request.queryParameters[QueryParams.UPLOAD_ID]
                ?: return@post call.respond(HttpStatusCode.BadRequest, errorJson("Missing uploadId"))

            queueManager.pause(uploadId)

            call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                put(ResponseFields.SUCCESS, true)
                put("state", "paused")
            })
        }
    }

    private fun Route.uploadResumeRoute() {
        post("/api/upload/resume") {
            val token = call.request.queryParameters[QueryParams.TOKEN]
                ?: return@post call.respond(HttpStatusCode.Unauthorized, errorJson("Missing token"))

            if (!sessionTokenManager.validateSession(token)) {
                return@post call.respond(HttpStatusCode.Unauthorized, errorJson("Invalid token"))
            }

            val uploadId = call.request.queryParameters[QueryParams.UPLOAD_ID]
                ?: return@post call.respond(HttpStatusCode.BadRequest, errorJson("Missing uploadId"))

            queueManager.resume(uploadId)

            call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                put(ResponseFields.SUCCESS, true)
                put("message", "Resume requested - browser will POST from disk offset")
            })
        }
    }

    private fun Route.uploadCancelRoute() {
        post("/api/upload/cancel") {
            val token = call.request.queryParameters[QueryParams.TOKEN]
                ?: return@post call.respond(HttpStatusCode.Unauthorized, errorJson("Missing token"))

            if (!sessionTokenManager.validateSession(token)) {
                return@post call.respond(HttpStatusCode.Unauthorized, errorJson("Invalid token"))
            }

            val uploadId = call.request.queryParameters[QueryParams.UPLOAD_ID]
                ?: return@post call.respond(HttpStatusCode.BadRequest, errorJson("Missing uploadId"))

            // Get upload status to extract file info
            val status = scheduler.activeUploads.value[uploadId]
            logger.d(TAG, "uploadCancel", "[$uploadId] Status found: ${status != null}, state: ${status?.state}, metadata: ${status?.metadata}")
            
            val path = status?.metadata?.path ?: (call.request.queryParameters[QueryParams.PATH] ?: "/")
            val fileName = status?.metadata?.displayName ?: (call.request.queryParameters[QueryParams.FILENAME] ?: "")

            val request = UploadRequest(
                uploadId = uploadId,
                fileUri = "",
                fileName = fileName,
                path = path,
                offset = 0,
                totalBytes = 0
            )

            queueManager.cancel(uploadId, request)

            call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                put(ResponseFields.SUCCESS, true)
                put("state", "cancelled")
            })
        }
    }

    private fun Route.uploadMetricsRoute() {
        get("/api/metrics") {
            val token = call.request.queryParameters[QueryParams.TOKEN]
                ?: return@get call.respond(HttpStatusCode.Unauthorized, errorJson("Missing token"))

            if (!sessionTokenManager.validateSession(token)) {
                return@get call.respond(HttpStatusCode.Unauthorized, errorJson("Invalid token"))
            }

            val uploads = scheduler.activeUploads.value
            val queueState = queueManager.queueState.value

            // Calculate metrics
            val activeUploads = uploads.values.count { it.state == UploadState.UPLOADING }
            val pausedUploads = uploads.values.count { it.state == UploadState.PAUSED }
            val completedUploads = uploads.values.count { it.state == UploadState.COMPLETED }
            val errorUploads = uploads.values.count { it.state == UploadState.ERROR }

            // Calculate average throughput
            val uploadingItems = uploads.values.filter { it.state == UploadState.UPLOADING && it.bytesPerSecond > 0 }
            val avgThroughput = if (uploadingItems.isNotEmpty()) {
                uploadingItems.map { it.bytesPerSecond }.average()
            } else 0.0

            call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                put("timestamp", System.currentTimeMillis())
                put("uploads", buildJsonObject {
                    put("total", uploads.size)
                    put("active", activeUploads)
                    put("paused", pausedUploads)
                    put("completed", completedUploads)
                    put("error", errorUploads)
                    put("queued", queueState.queuedCount)
                })
                put("throughput", buildJsonObject {
                    put("avgBytesPerSecond", avgThroughput)
                    put("avgMBps", (avgThroughput / 1_000_000).toFloat())
                })
                put("protocol", "v2.1-failfast")
            })
        }
    }

    private fun parseContentRange(contentRange: String?, contentLength: Long?): Pair<Long, Long> {
        if (contentRange == null) {
            return 0L to (contentLength ?: -1L)
        }
        val regex = Regex("""bytes (\d+)-(\d*)/(\d+)""")
        val match = regex.find(contentRange) ?: return 0L to (contentLength ?: -1L)
        val offset = match.groupValues[1].toLongOrNull() ?: 0L
        val total = match.groupValues[3].toLongOrNull() ?: (contentLength ?: -1L)
        return offset to total
    }

    private fun sanitizeFileName(fileName: String): String {
        var sanitized = fileName
            .replace("..", "_")
            .replace("/", "_")
            .replace("\\", "_")
            .replace(Regex("[\\x00-\\x1f\\x7f]"), "")
            .trim()
        if (sanitized.length > MAX_FILENAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_FILENAME_LENGTH)
        }
        if (sanitized.matches(Regex("^\\.+$"))) {
            sanitized = "_$sanitized"
        }
        return sanitized
    }

    private fun errorJson(message: String): JsonObject = buildJsonObject {
        put(ResponseFields.ERROR, message)
    }

    private suspend fun ApplicationCall.respondNoCache(status: HttpStatusCode, body: JsonObject) {
        response.cacheControl(CacheControl.NoStore(CacheControl.Visibility.Private))
        respond(status, body)
    }
}
