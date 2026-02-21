package com.swaran.airbridge.core.network.controller

import org.json.JSONObject
import com.swaran.airbridge.core.network.LocalHttpServer
import fi.iki.elonen.NanoHTTPD
import javax.inject.Inject

class HealthController @Inject constructor(
    server: LocalHttpServer
) : LocalHttpServer.RequestHandler {

    init {
        server.registerHandler(this)
    }

    override fun canHandle(session: NanoHTTPD.IHTTPSession): Boolean {
        return session.uri == "/health"
    }

    override fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val response = JSONObject().apply {
            put("status", "ok")
            put("service", "AirBridge")
            put("version", "1.0")
        }

        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            response.toString()
        )
    }
}
