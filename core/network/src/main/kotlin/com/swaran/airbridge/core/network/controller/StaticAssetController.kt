package com.swaran.airbridge.core.network.controller

import android.content.Context
import com.swaran.airbridge.core.network.LocalHttpServer
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import javax.inject.Inject

class StaticAssetController @Inject constructor(
    @ApplicationContext private val context: Context,
    server: LocalHttpServer
) : LocalHttpServer.RequestHandler {

    init { server.registerHandler(this) }

    override fun canHandle(session: NanoHTTPD.IHTTPSession): Boolean {
        return session.uri == "/" || session.uri == "/index.html"
    }

    override fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val stream = context.assets.open("index.html")
        return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "text/html", stream)
    }
}
