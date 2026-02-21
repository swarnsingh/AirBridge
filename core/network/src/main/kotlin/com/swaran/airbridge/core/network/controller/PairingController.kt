package com.swaran.airbridge.core.network.controller

import org.json.JSONObject
import com.swaran.airbridge.core.common.AirDispatchers
import com.swaran.airbridge.core.common.Dispatcher
import com.swaran.airbridge.core.network.LocalHttpServer
import com.swaran.airbridge.core.network.SessionTokenManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class PairingSession(
    val id: String,
    var approved: Boolean = false,
    var token: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Singleton
class PairingController @Inject constructor(
    server: LocalHttpServer,
    private val sessionTokenManager: SessionTokenManager,
    @Dispatcher(AirDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) : LocalHttpServer.RequestHandler {

    private val pairingSessions = ConcurrentHashMap<String, PairingSession>()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    init {
        server.registerHandler(this)
        startCleanupTask()
    }

    override fun canHandle(session: NanoHTTPD.IHTTPSession): Boolean {
        return session.uri.startsWith("/api/pair/")
    }

    override fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return when {
            session.uri == "/api/pair/request" && session.method == NanoHTTPD.Method.POST -> handlePairRequest()
            session.uri.startsWith("/api/pair/status") && session.method == NanoHTTPD.Method.GET -> handleStatusCheck(session)
            session.uri == "/api/pair/approve" && session.method == NanoHTTPD.Method.POST -> handleApprove(session)
            else -> notFound()
        }
    }

    private fun handlePairRequest(): NanoHTTPD.Response {
        val pairingId = UUID.randomUUID().toString().take(8)
        val pairingSession = PairingSession(id = pairingId)
        pairingSessions[pairingId] = pairingSession

        return jsonResponse(JSONObject().put("pairingId", pairingId))
    }

    private fun handleStatusCheck(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val pairingId = session.parameters["id"]?.firstOrNull() ?: return badRequest("Missing pairing ID")
        val pairingSession = pairingSessions[pairingId] ?: return notFound("Pairing session not found")

        return jsonResponse(JSONObject().apply {
            put("approved", pairingSession.approved)
            put("token", pairingSession.token ?: JSONObject.NULL)
        })
    }

    private fun handleApprove(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val content = body["postData"] ?: return badRequest("Missing body")

        val request = try { JSONObject(content) } catch (e: Exception) { return badRequest("Invalid JSON") }
        val pairingId = request.optString("pairingId", null) ?: return badRequest("Missing pairing ID")
        val pairingSession = pairingSessions[pairingId] ?: return notFound("Pairing session not found")

        val future = CompletableFuture<NanoHTTPD.Response>()
        scope.launch {
            try {
                val sessionInfo = sessionTokenManager.generateSession()
                pairingSession.approved = true
                pairingSession.token = sessionInfo.token
                future.complete(jsonResponse(JSONObject().put("success", true)))
            } catch (e: Exception) {
                future.complete(errorResponse("Approval failed: ${e.message}"))
            }
        }

        return try { future.get() } catch (e: Exception) { errorResponse("Approval timeout") }
    }

    private fun startCleanupTask() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                val now = System.currentTimeMillis()
                pairingSessions.entries.removeIf { (_, session) ->
                    now - session.createdAt > 5 * 60 * 1000 // 5 minutes
                }
            }
        }
    }

    private fun jsonResponse(obj: JSONObject): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", obj.toString())

    private fun errorResponse(msg: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", JSONObject().put("error", msg).toString())

    private fun badRequest(msg: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "application/json", JSONObject().put("error", msg).toString())

    private fun notFound(msg: String = "Not Found"): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "application/json", JSONObject().put("error", msg).toString())
}
