package com.swaran.airbridge.core.network.ktor.routes

import com.swaran.airbridge.core.network.ktor.ResponseFields
import io.ktor.http.CacheControl
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Health check endpoint for Ktor server.
 *
 * Returns a simple JSON response indicating the server is operational.
 * This endpoint is used by:
 * - Load balancers / health checks
 * - Browser clients to verify connectivity before transfers
 * - Migration testing (Phase 2) to confirm Ktor is responding
 *
 * ## Cache Control
 *
 * Uses `Cache-Control: no-store` to prevent stale responses during migration.
 * This ensures clients always get fresh health status.
 *
 * ## Response Format
 *
 * ```json
 * { "status": "ok", "server": "ktor", "version": "1.0" }
 * ```
 */
fun Route.healthRoute() {
    get("/api/health") {
        call.response.cacheControl(CacheControl.NoStore(CacheControl.Visibility.Private))
        call.respond(
            HttpStatusCode.OK,
            mapOf(
                ResponseFields.STATUS to "ok",
                ResponseFields.SERVER to "ktor",
                ResponseFields.VERSION to "1.0"
            )
        )
    }

    // Legacy /health endpoint for backwards compatibility
    get("/health") {
        call.response.cacheControl(CacheControl.NoStore(CacheControl.Visibility.Private))
        call.respond(
            HttpStatusCode.OK,
            mapOf(
                ResponseFields.STATUS to "ok",
                ResponseFields.SERVICE to "AirBridge",
                ResponseFields.VERSION to "1.0",
                ResponseFields.ENGINE to "ktor"
            )
        )
    }
}
