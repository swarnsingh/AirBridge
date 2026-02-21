package com.swaran.airbridge.core.network.controller

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import com.swaran.airbridge.core.network.LocalHttpServer
import com.swaran.airbridge.core.network.PushManager
import com.swaran.airbridge.core.network.SessionTokenManager
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PushController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pushManager: PushManager,
    private val sessionTokenManager: SessionTokenManager,
    server: LocalHttpServer
) : LocalHttpServer.RequestHandler {

    init {
        server.registerHandler(this)
    }

    override fun canHandle(session: NanoHTTPD.IHTTPSession): Boolean {
        return session.uri.startsWith("/api/push")
    }

    override fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val token = session.parameters["token"]?.firstOrNull()
            ?: return unauthorized()

        if (!sessionTokenManager.validateSession(token)) {
            return unauthorized()
        }

        return when (session.uri) {
            "/api/push/status" -> handleStatus()
            "/api/push/next" -> handleNext()
            else -> notFound()
        }
    }

    private fun handleStatus(): NanoHTTPD.Response {
        val nextPush = pushManager.peekPush()
        return if (nextPush != null) {
            val fileLength = try {
                context.contentResolver.openFileDescriptor(Uri.parse(nextPush.uri), "r")?.use { it.statSize } ?: -1L
            } catch (e: Exception) {
                -1L
            }
            
            jsonResponse(JSONObject().apply {
                put("pending", true)
                put("file", JSONObject().apply {
                    put("fileName", nextPush.fileName)
                    put("uri", nextPush.uri)
                    put("size", fileLength)
                })
            })
        } else {
            jsonResponse(JSONObject().put("pending", false))
        }
    }

    private fun handleNext(): NanoHTTPD.Response {
        val item = pushManager.getNextPush() ?: return notFound()

        val inputStream = context.contentResolver.openInputStream(Uri.parse(item.uri))
            ?: return errorResponse("File not found or unreadable")

        val fileLength = context.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r")?.use { it.statSize } ?: -1L

        val response = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/octet-stream", inputStream, fileLength)
        response.addHeader("Content-Disposition", "attachment; filename=\"${item.fileName}\"")
        return response
    }

    private fun jsonResponse(obj: JSONObject): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", obj.toString())
    }

    private fun errorResponse(message: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "application/json", JSONObject().put("error", message).toString())
    }

    private fun unauthorized(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, "application/json", JSONObject().put("error", "Invalid or missing token").toString())
    }

    private fun notFound(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }
}
