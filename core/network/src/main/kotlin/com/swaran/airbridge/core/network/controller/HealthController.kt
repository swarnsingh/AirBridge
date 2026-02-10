package com.swaran.airbridge.core.network.controller

import com.google.gson.Gson
import com.swaran.airbridge.core.network.LocalHttpServer
import fi.iki.elonen.NanoHTTPD
import javax.inject.Inject

class HealthController @Inject constructor(
    server: LocalHttpServer
) : LocalHttpServer.RequestHandler {

    private val gson = Gson()

    init {
        server.registerHandler(this)
    }

    override fun canHandle(session: NanoHTTPD.IHTTPSession): Boolean {
        return session.uri == "/health"
    }

    override fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val response = mapOf(
            "status" to "ok",
            "service" to "AirBridge",
            "version" to "1.0"
        )

        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json",
            gson.toJson(response)
        )
    }
}
