package com.swaran.airbridge.core.network.ktor.routes

import android.content.Context
import com.swaran.airbridge.core.network.ktor.ResponseFields
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import javax.inject.Inject

/**
 * Static asset routes for serving the web UI.
 *
 * Serves `index.html` from Android assets to browser clients.
 *
 * ## Cache Control
 *
 * Uses `Cache-Control: no-store` to prevent browser caching.
 * This is critical because:
 * - Stale JS files could break resume functionality
 * - Cached HTML might reference wrong API endpoints
 *
 * ## Routes
 *
 * - `GET /` → Serves index.html
 * - `GET /index.html` → Serves index.html
 *
 * ## Error Handling
 *
 * If index.html is missing from assets, returns 500 with error message.
 * This typically indicates a build/packaging problem.
 */
class StaticRoutes @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Installs static routes into the Ktor routing configuration.
     */
    fun install(routing: Route) {
        routing.apply {
            get("/") {
                serveIndexHtml(call)
            }

            get("/index.html") {
                serveIndexHtml(call)
            }
        }
    }

    private suspend fun serveIndexHtml(call: ApplicationCall) {
        call.response.cacheControl(CacheControl.NoStore(CacheControl.Visibility.Private))

        try {
            val html = context.assets.open("index.html").use { stream ->
                stream.bufferedReader().use { reader ->
                    reader.readText()
                }
            }
            call.respondText(
                text = html,
                contentType = ContentType.Text.Html,
                status = HttpStatusCode.OK
            )
        } catch (e: IOException) {
            call.respond(
                HttpStatusCode.InternalServerError,
                buildJsonObject { put(ResponseFields.ERROR, "Failed to load index.html: ${e.message}") }
            )
        }
    }
}
