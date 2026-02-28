package com.swaran.airbridge.core.network.ktor.routes

import android.content.Context
import android.net.Uri
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.core.network.PushManager
import com.swaran.airbridge.core.network.SessionTokenManager
import com.swaran.airbridge.core.network.ktor.QueryParams
import com.swaran.airbridge.core.network.ktor.ResponseFields
import com.swaran.airbridge.domain.repository.StorageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.http.CacheControl
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data class representing a pairing session.
 */
data class PairingSession(
    val id: String,
    var approved: Boolean = false,
    var token: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Pairing, Push, and Zip routes for Ktor.
 *
 * ## Routes
 *
 * ### Pairing
 * - `POST /api/pair/request` → Create new pairing session
 * - `GET /api/pair/status?id={id}` → Check pairing status
 * - `POST /api/pair/approve` → Approve pairing (body: {"pairingId":"..."})
 *
 * ### Push
 * - `GET /api/push/status?token={token}` → Check pending push
 * - `GET /api/push/next?token={token}` → Get next pushed file
 *
 * ### Zip
 * - `GET /api/zip?ids={id1,id2}&token={token}` → Download multiple files as ZIP
 */
@Singleton
class PairingPushZipRoutes @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val storageRepository: StorageRepository,
    private val sessionTokenManager: SessionTokenManager,
    private val pushManager: PushManager,
    private val logger: AirLogger
) {

    companion object {
        private const val TAG = "PairingPushZipRoutes"
    }

    private val pairingSessions = ConcurrentHashMap<String, PairingSession>()

    /**
     * Installs pairing, push, and zip routes into Ktor routing.
     */
    fun install(routing: Route) {
        routing.apply {
            pairingRoutes()
            pushRoutes()
            zipRoute()
        }
    }

    private fun Route.pairingRoutes() {
        // POST /api/pair/request - Create new pairing
        post("/api/pair/request") {
            val pairingId = UUID.randomUUID().toString().take(8)
            val session = PairingSession(id = pairingId)
            pairingSessions[pairingId] = session
            
            logger.d(TAG, "log", "Created pairing session: $pairingId, total sessions: ${pairingSessions.size}")

            call.response.cacheControl(CacheControl.NoStore(CacheControl.Visibility.Private))
            call.respond(
                HttpStatusCode.OK,
                mapOf(ResponseFields.PAIRING_ID to pairingId)
            )
        }

        // GET /api/pair/status?id={id} - Check pairing status
        get("/api/pair/status") {
            val pairingId = call.request.queryParameters["id"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf(ResponseFields.ERROR to "Missing pairing ID"))

            val session = pairingSessions[pairingId]
            
            logger.d(TAG, "log", "Status check for: $pairingId, found: ${session != null}, approved: ${session?.approved}")
            
            if (session == null) {
                return@get call.respond(HttpStatusCode.NotFound, mapOf(ResponseFields.ERROR to "Pairing session not found"))
            }

            call.response.cacheControl(CacheControl.NoStore(CacheControl.Visibility.Private))
            call.respond(
                HttpStatusCode.OK,
                buildJsonObject {
                    put(ResponseFields.APPROVED, session.approved)
                    put(ResponseFields.TOKEN, session.token ?: "")
                }
            )
        }

        // POST /api/pair/approve - Approve pairing
        post("/api/pair/approve") {
            val body = call.receiveText()
            
            logger.d(TAG, "log", "Approve request body: $body")
            
            // Simple JSON parsing
            val pairingId = try {
                val json = Json.parseToJsonElement(body)
                json.jsonObject[ResponseFields.PAIRING_ID]?.jsonPrimitive?.content
            } catch (e: Exception) {
                logger.e(TAG, "log", "JSON parse error", e)
                null
            } ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf(ResponseFields.ERROR to "Missing or invalid pairingId"))

            val session = pairingSessions[pairingId]
            
            logger.d(TAG, "log", "Approve for: $pairingId, found: ${session != null}, sessions: ${pairingSessions.keys}")
            
            if (session == null) {
                return@post call.respond(HttpStatusCode.NotFound, mapOf(ResponseFields.ERROR to "Pairing session not found"))
            }

            // Generate session token
            val sessionInfo = sessionTokenManager.generateSession()
            session.approved = true
            session.token = sessionInfo.token
            
            pairingSessions[pairingId] = session
            
            logger.d(TAG, "log", "Approved pairing: $pairingId, token: ${sessionInfo.token}")

            call.response.cacheControl(CacheControl.NoStore(CacheControl.Visibility.Private))
            call.respond(
                HttpStatusCode.OK,
                mapOf(ResponseFields.SUCCESS to true)
            )
        }
    }

    private fun Route.pushRoutes() {
        // GET /api/push/status?token={token}
        get("/api/push/status") {
            val token = call.request.queryParameters[QueryParams.TOKEN]
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf(ResponseFields.ERROR to "Missing token"))

            if (!sessionTokenManager.validateSession(token)) {
                return@get call.respond(HttpStatusCode.Unauthorized, mapOf(ResponseFields.ERROR to "Invalid token"))
            }

            val nextPush = pushManager.peekPush()
            
            call.response.cacheControl(CacheControl.NoStore(CacheControl.Visibility.Private))
            if (nextPush != null) {
                val fileLength = try {
                    appContext.contentResolver.openFileDescriptor(
                        Uri.parse(nextPush.uri), "r"
                    )?.use { it.statSize } ?: -1L
                } catch (e: Exception) {
                    -1L
                }

                call.respond(
                    HttpStatusCode.OK,
                    buildJsonObject {
                        put(ResponseFields.PENDING, true)
                        put(ResponseFields.FILE, buildJsonObject {
                            put(ResponseFields.NAME, nextPush.fileName)
                            put(ResponseFields.URI, nextPush.uri)
                            put(ResponseFields.SIZE, fileLength)
                        })
                    }
                )
            } else {
                call.respond(HttpStatusCode.OK, buildJsonObject { put(ResponseFields.PENDING, false) })
            }
        }

        // GET /api/push/next?token={token}
        get("/api/push/next") {
            val token = call.request.queryParameters[QueryParams.TOKEN]
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf(ResponseFields.ERROR to "Missing token"))

            if (!sessionTokenManager.validateSession(token)) {
                return@get call.respond(HttpStatusCode.Unauthorized, mapOf(ResponseFields.ERROR to "Invalid token"))
            }

            val item = pushManager.getNextPush()
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf(ResponseFields.ERROR to "No pending push"))

            val uri = Uri.parse(item.uri)
            val inputStream = appContext.contentResolver.openInputStream(uri)
                ?: return@get call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(ResponseFields.ERROR to "File not found or unreadable")
                )

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, item.fileName
                ).toString()
            )
            
            call.response.cacheControl(CacheControl.NoStore(CacheControl.Visibility.Private))
            
            inputStream.use { stream ->
                call.respondBytesWriter(
                    status = HttpStatusCode.OK,
                    contentType = ContentType.Application.OctetStream
                ) {
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (stream.read(buffer).also { read = it } != -1) {
                        writeFully(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun Route.zipRoute() {
        // GET /api/zip?ids={id1,id2}&token={token}
        get("/api/zip") {
            val token = call.request.queryParameters[QueryParams.TOKEN]
                ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf(ResponseFields.ERROR to "Missing token"))

            if (!sessionTokenManager.validateSession(token)) {
                return@get call.respond(HttpStatusCode.Unauthorized, mapOf(ResponseFields.ERROR to "Invalid token"))
            }

            val ids = call.request.queryParameters[QueryParams.FILE_IDS]
                ?.split(",")
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

            if (ids.isEmpty()) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(ResponseFields.ERROR to "No files selected")
                )
            }

            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName, "AirBridge-Download.zip"
                ).toString()
            )
            
            call.response.cacheControl(CacheControl.NoStore(CacheControl.Visibility.Private))

            // Build ZIP in memory (for small-to-medium file sets)
            val baos = ByteArrayOutputStream()
            ZipOutputStream(baos).use { zos ->
                ids.forEach { id ->
                    val file = storageRepository.getFile(id).getOrNull() ?: return@forEach
                    storageRepository.downloadFile(id).getOrNull()?.use { input ->
                        zos.putNextEntry(ZipEntry(file.name))
                        input.copyTo(zos)
                        zos.closeEntry()
                    }
                }
            }

            val zipBytes = baos.toByteArray()
            
            call.respondBytesWriter(
                status = HttpStatusCode.OK,
                contentType = ContentType.Application.Zip
            ) {
                writeFully(zipBytes)
            }
        }
    }
}
