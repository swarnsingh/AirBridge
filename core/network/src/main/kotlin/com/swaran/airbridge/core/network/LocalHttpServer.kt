package com.swaran.airbridge.core.network

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalHttpServer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securityInterceptor: SecurityInterceptor,
    private val ipAddressProvider: IpAddressProvider
) {
    private var server: NanoHTTPD? = null
    private val handlers = mutableListOf<RequestHandler>()

    companion object {
        private const val SOCKET_READ_TIMEOUT = 60_000
    }

    fun start(port: Int): Boolean {
        if (server != null) return true

        return try {
            server = object : NanoHTTPD("0.0.0.0", port) {
                override fun serve(session: IHTTPSession): Response {
                    if (!securityInterceptor.isLocalNetworkRequest(session)) {
                        return newFixedLengthResponse(
                            Response.Status.FORBIDDEN,
                            MIME_PLAINTEXT,
                            "LAN access only"
                        )
                    }
                    val handler = handlers.find { it.canHandle(session) }
                        ?: return newFixedLengthResponse(
                            Response.Status.NOT_FOUND,
                            MIME_PLAINTEXT,
                            "Not Found"
                        )
                    return handler.handle(session)
                }
            }.apply { start(SOCKET_READ_TIMEOUT, false) }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    fun isRunning(): Boolean = server?.isAlive == true

    fun getLocalIpAddress(): String? = ipAddressProvider.getLocalIpAddress()

    fun registerHandler(handler: RequestHandler) {
        handlers.add(handler)
    }

    interface RequestHandler {
        fun canHandle(session: NanoHTTPD.IHTTPSession): Boolean
        fun handle(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response
    }
}
