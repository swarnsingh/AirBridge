package com.swaran.airbridge.core.network.ktor.routes

import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.core.network.ktor.QueryParams
import com.swaran.airbridge.core.network.ktor.ResponseFields
import com.swaran.airbridge.core.network.upload.UploadScheduler
import com.swaran.airbridge.domain.model.UploadRequest
import com.swaran.airbridge.domain.model.UploadResult
import io.ktor.http.CacheControl
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveStream
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin HTTP layer for upload endpoints.
 *
 * All business logic is delegated to [UploadScheduler].
 * This class only handles:
 * - Auth validation
 * - Parameter parsing
 * - Content-Range header parsing
 * - HTTP response formatting
 * - Filename sanitization (security)
 */
@Singleton
class UploadRoutes @Inject constructor(
    private val scheduler: UploadScheduler,
    private val sessionTokenManager: SessionTokenManager,
    private val logger: AirLogger
) {
    companion object {
        private const val TAG = "UploadRoutes"
        private const val MAX_FILENAME_LENGTH = 255
        private const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024 * 1024 // 50 GB hard limit
    }

    fun install(routing: Route) {
        routing.apply {
            uploadStatusRoute()
            uploadPostRoute()
            uploadCancelRoute()
        }
    }

    private fun Route.uploadStatusRoute() {
        get("/api/upload/status") {
            val token = call.request.queryParameters[QueryParams.TOKEN]
                ?: return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    errorJson("Missing token")
                )

            if (!sessionTokenManager.validateSession(token)) {
                return@get call.respond(
                    HttpStatusCode.Unauthorized,
                    errorJson("Invalid token")
                )
            }

            val fileName = call.request.queryParameters[QueryParams.FILENAME]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    errorJson("Missing filename")
                )

            val path = call.request.queryParameters[QueryParams.PATH] ?: "/"
            val uploadId = call.request.queryParameters[QueryParams.UPLOAD_ID]

            val status = scheduler.getUploadStatusForQuery(path, fileName, uploadId)

            call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                put(ResponseFields.EXISTS, status.exists)
                put(ResponseFields.SIZE, status.size)
                put(ResponseFields.STATUS, status.status)
                put("can_resume", status.canResume)
            })
        }
    }

    private fun Route.uploadPostRoute() {
        post("/api/upload") {
            val token = call.request.queryParameters[QueryParams.TOKEN]
                ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    errorJson("Missing token")
                )

            if (!sessionTokenManager.validateSession(token)) {
                return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    errorJson("Invalid token")
                )
            }

            val path = call.request.queryParameters[QueryParams.PATH] ?: "/"
            val rawFileName = call.request.queryParameters[QueryParams.FILENAME] ?: "file"
            
            logger.d(TAG, "uploadPostRoute", "Received upload request: path='$path', rawFileName='$rawFileName'")
            
            // Sanitize filename to prevent path traversal and other attacks
            val fileName = sanitizeFileName(rawFileName)
            logger.d(TAG, "uploadPostRoute", "Sanitized filename: '$fileName'")
            if (fileName.isBlank()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    errorJson("Invalid filename")
                )
            }
            
            val uploadId = call.request.queryParameters[QueryParams.UPLOAD_ID] ?: UUID.randomUUID().toString()
            val contentRange = call.request.header("Content-Range")
            val contentLength = call.request.header("Content-Length")?.toLongOrNull()

            // Parse offset + totalBytes from Content-Range header (resume case)
            // Fall back to Content-Length when no Content-Range (initial upload)
            val (offset, totalBytes) = parseContentRange(contentRange, contentLength)

            // Reject oversized files before allocating any resources
            if (totalBytes > MAX_FILE_SIZE_BYTES) {
                logger.w(TAG, "uploadPostRoute", "Rejected oversized file: $totalBytes bytes > limit $MAX_FILE_SIZE_BYTES")
                return@post call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    errorJson("File too large. Maximum size is 50GB")
                )
            }

            val request = UploadRequest(
                uploadId = uploadId,
                fileUri = "", // Resolved by scheduler
                fileName = fileName,
                path = path,
                offset = offset,
                totalBytes = totalBytes,
                contentRange = contentRange
            )

            val result = scheduler.handleUpload(
                request = request,
                requestSource = call.request,
                receiveStream = { call.receiveStream() }
            )

            logger.d(TAG, "uploadPostRoute", "Upload result: ${result.javaClass.simpleName}, retryable=${if (result is UploadResult.Failure) result.isRetryable else "N/A"}")

            when (result) {
                is UploadResult.Success -> {
                    call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                        put(ResponseFields.SUCCESS, true)
                        put(ResponseFields.UPLOAD_ID, result.uploadId)
                        put(ResponseFields.BYTES_RECEIVED, result.bytesReceived)
                    })
                }
                is UploadResult.Paused -> {
                    // Return "interrupted" — this is what the browser JS expects
                    // (browser only handles 'interrupted', not 'paused')
                    call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                        put(ResponseFields.SUCCESS, false)
                        put(ResponseFields.STATUS, "interrupted")
                        put(ResponseFields.BYTES_RECEIVED, result.bytesReceived)
                    })
                }
                is UploadResult.Cancelled -> {
                    call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                        put(ResponseFields.SUCCESS, false)
                        put(ResponseFields.STATUS, "cancelled")
                    })
                }
                is UploadResult.Busy -> {
                    // Return 409 Conflict with busy status - browser will retry
                    call.respondNoCache(HttpStatusCode.Conflict, buildJsonObject {
                        put(ResponseFields.SUCCESS, false)
                        put(ResponseFields.STATUS, "busy")
                    })
                }
                is UploadResult.Failure.OffsetMismatch -> {
                    call.respondNoCache(HttpStatusCode.Conflict, buildJsonObject {
                        put(ResponseFields.SUCCESS, false)
                        put(ResponseFields.STATUS, "offset_mismatch")
                        put("expected_offset", result.expectedOffset)
                        put("actual_disk_size", result.actualDiskSize)
                        put(ResponseFields.BYTES_RECEIVED, result.bytesReceived)
                    })
                }
                is UploadResult.Failure.PermissionRevoked -> {
                    call.respondNoCache(HttpStatusCode.Forbidden, buildJsonObject {
                        put(ResponseFields.SUCCESS, false)
                        put(ResponseFields.STATUS, "permission_revoked")
                        put(ResponseFields.BYTES_RECEIVED, result.bytesReceived)
                    })
                }
                is UploadResult.Failure.StorageFull -> {
                    call.respondNoCache(HttpStatusCode.InsufficientStorage, buildJsonObject {
                        put(ResponseFields.SUCCESS, false)
                        put(ResponseFields.STATUS, "storage_full")
                        put(ResponseFields.BYTES_RECEIVED, result.bytesReceived)
                    })
                }
                is UploadResult.Failure.FileDeleted,
                is UploadResult.Failure.NetworkError,
                is UploadResult.Failure.UnknownError -> {
                    val retryable = result.isRetryable
                    call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                        put(ResponseFields.SUCCESS, false)
                        put(ResponseFields.STATUS, if (retryable) "error_retryable" else "error")
                        put(ResponseFields.BYTES_RECEIVED, result.bytesReceived)
                        put("retryable", retryable)
                    })
                }
            }
        }
    }

    private fun Route.uploadCancelRoute() {
        post("/api/upload/cancel") {
            val token = call.request.queryParameters[QueryParams.TOKEN]
                ?: return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    errorJson("Missing token")
                )

            if (!sessionTokenManager.validateSession(token)) {
                return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    errorJson("Invalid token")
                )
            }

            val uploadId = call.request.queryParameters[QueryParams.UPLOAD_ID]
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    errorJson("Missing uploadId")
                )

            scheduler.cancelUpload(uploadId)

            call.respondNoCache(HttpStatusCode.OK, buildJsonObject {
                put(ResponseFields.SUCCESS, true)
                put(ResponseFields.STATUS, "cancelled")
            })
        }
    }

    private fun parseContentRange(contentRange: String?, contentLength: Long?): Pair<Long, Long> {
        if (contentRange == null) {
            // No Content-Range = initial upload (no resume)
            // Use Content-Length as total file size
            return 0L to (contentLength ?: -1L)
        }

        val regex = Regex("""bytes (\d+)-(\d*)/(\d+)""")
        val match = regex.find(contentRange) ?: return 0L to (contentLength ?: -1L)

        val offset = match.groupValues[1].toLongOrNull() ?: 0L
        val total = match.groupValues[3].toLongOrNull() ?: (contentLength ?: -1L)

        return offset to total
    }

    /**
     * Sanitizes a filename to prevent path traversal and other attacks.
     * 
     * - Removes path separators and parent directory references
     * - Removes control characters
     * - Limits length to MAX_FILENAME_LENGTH
     * - Returns empty string if result is not valid
     */
    private fun sanitizeFileName(fileName: String): String {
        // Remove path traversal attempts
        var sanitized = fileName
            .replace("..", "_")
            .replace("/", "_")
            .replace("\\", "_")
            .replace(Regex("[\\x00-\\x1f\\x7f]"), "") // Remove control chars
            .trim()
        
        // Limit length
        if (sanitized.length > MAX_FILENAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_FILENAME_LENGTH)
        }
        
        // Ensure not all dots (would be hidden file on Unix)
        if (sanitized.matches(Regex("^\\.+$"))) {
            sanitized = "_" + sanitized
        }
        
        return sanitized
    }

    private fun errorJson(message: String): JsonObject = buildJsonObject {
        put(ResponseFields.ERROR, message)
    }

    private suspend fun ApplicationCall.respondNoCache(
        status: HttpStatusCode,
        body: JsonObject
    ) {
        response.cacheControl(CacheControl.NoStore(CacheControl.Visibility.Private))
        respond(status, body)
    }
}