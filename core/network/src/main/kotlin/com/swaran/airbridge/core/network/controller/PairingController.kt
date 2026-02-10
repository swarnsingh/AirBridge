package com.swaran.airbridge.core.network.controller

import com.google.gson.Gson
import com.swaran.airbridge.core.network.LocalHttpServer
import com.swaran.airbridge.core.network.SessionTokenManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.util.UUID
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
    private val sessionTokenManager: SessionTokenManager
) : LocalHttpServer.RequestHandler {

    private val pairingSessions = ConcurrentHashMap<String, PairingSession>()
    private val gson = Gson()

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
            else -> NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "application/json",
                gson.toJson(mapOf("error" to "Not found"))
            )
        }
    }

    private fun handlePairRequest(): NanoHTTPD.Response {
        val pairingId = UUID.randomUUID().toString().take(8)
        val pairingSession = PairingSession(id = pairingId)
        pairingSessions[pairingId] = pairingSession

        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            gson.toJson(mapOf("pairingId" to pairingId))
        )
    }

    private fun handleStatusCheck(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val pairingId = session.parameters["id"]?.firstOrNull()
        
        if (pairingId == null) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "application/json",
                gson.toJson(mapOf("error" to "Missing pairing ID"))
            )
        }

        val pairingSession = pairingSessions[pairingId]
        if (pairingSession == null) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "application/json",
                gson.toJson(mapOf("error" to "Pairing session not found"))
            )
        }

        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            gson.toJson(mapOf(
                "approved" to pairingSession.approved,
                "token" to pairingSession.token
            ))
        )
    }

    private fun handleApprove(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val body = mutableMapOf<String, String>()
        session.parseBody(body)
        val content = body["postData"] ?: return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            "application/json",
            gson.toJson(mapOf("error" to "Missing body"))
        )

        val request = try {
            gson.fromJson(content, Map::class.java)
        } catch (e: Exception) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "application/json",
                gson.toJson(mapOf("error" to "Invalid JSON"))
            )
        }

        val pairingId = request["pairingId"] as? String
        if (pairingId == null) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.BAD_REQUEST,
                "application/json",
                gson.toJson(mapOf("error" to "Missing pairing ID"))
            )
        }

        val pairingSession = pairingSessions[pairingId]
        if (pairingSession == null) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                "application/json",
                gson.toJson(mapOf("error" to "Pairing session not found"))
            )
        }

        // Generate new session token
        val sessionInfo = runBlocking { sessionTokenManager.generateSession() }

        // Approve pairing
        pairingSession.approved = true
        pairingSession.token = sessionInfo.token

        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            gson.toJson(mapOf("success" to true))
        )
    }

    private fun startCleanupTask() {
        Thread {
            while (true) {
                try {
                    Thread.sleep(60_000) // 1 minute
                    val now = System.currentTimeMillis()
                    pairingSessions.entries.removeIf { (_, session) ->
                        now - session.createdAt > 5 * 60 * 1000 // 5 minutes
                    }
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.start()
    }
}
