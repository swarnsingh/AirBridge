package com.swaran.airbridge.core.network.ktor.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenAPI/Swagger documentation endpoint.
 *
 * Serves API documentation at `/api/docs` as JSON OpenAPI 3.0 spec.
 * Can be viewed with Swagger UI or other OpenAPI tools.
 *
 * ## Endpoints Documented:
 * - Health check (`/api/health`)
 * - File operations (`/api/files`, `/api/download/{id}`)
 * - Upload (`/api/upload`, `/api/upload/status`, `/api/upload/cancel`, `/api/upload/events`)
 * - Pairing (`/api/pair/request`, `/api/pair/status`)
 * - Push (`/api/push/status`, `/api/push/next`)
 *
 * ## Usage:
 * ```
 * GET /api/docs
 * ```
 *
 * ## Swagger UI:
 * Paste the JSON response into `https://editor.swagger.io` for interactive docs.
 */
fun Route.apiDocsRoute() {
    get("/api/docs") {
        val openApiSpec = buildJsonObject {
            put("openapi", "3.0.3")
            put("info", buildJsonObject {
                put("title", "AirBridge API")
                put("description", "LAN file sharing server API for AirBridge Android app")
                put("version", "1.0.0")
                put("contact", buildJsonObject {
                    put("name", "AirBridge")
                })
            })
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("url", "http://{host}:{port}")
                    put("variables", buildJsonObject {
                        put("host", buildJsonObject {
                            put("default", "localhost")
                        })
                        put("port", buildJsonObject {
                            put("default", "8081")
                        })
                    })
                })
            })
            put("paths", buildJsonObject {
                // Health
                put("/api/health", buildJsonObject {
                    put("get", buildJsonObject {
                        put("summary", "Health check")
                        put("description", "Check if server is running")
                        put("responses", buildJsonObject {
                            put("200", buildJsonObject {
                                put("description", "Server is healthy")
                                put("content", buildJsonObject {
                                    put("application/json", buildJsonObject {
                                        put("schema", buildJsonObject {
                                            put("type", "object")
                                            put("properties", buildJsonObject {
                                                put("status", buildJsonObject {
                                                    put("type", "string")
                                                    put("example", "ok")
                                                })
                                                put("server", buildJsonObject {
                                                    put("type", "string")
                                                    put("example", "ktor")
                                                })
                                                put("version", buildJsonObject {
                                                    put("type", "string")
                                                    put("example", "1.0")
                                                })
                                            })
                                        })
                                    })
                                })
                            })
                        })
                    })
                })

                // Files list
                put("/api/files", buildJsonObject {
                    put("get", buildJsonObject {
                        put("summary", "List files")
                        put("description", "Get list of files in storage")
                        put("parameters", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "token")
                                put("in", "query")
                                put("required", true)
                                put("schema", buildJsonObject {
                                    put("type", "string")
                                })
                                put("description", "Session token")
                            })
                        })
                        put("responses", buildJsonObject {
                            put("200", buildJsonObject {
                                put("description", "List of files")
                            })
                            put("401", buildJsonObject {
                                put("description", "Invalid or missing token")
                            })
                        })
                    })
                })

                // Download
                put("/api/download/{id}", buildJsonObject {
                    put("get", buildJsonObject {
                        put("summary", "Download file")
                        put("description", "Download a file by ID")
                        put("parameters", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "id")
                                put("in", "path")
                                put("required", true)
                                put("schema", buildJsonObject {
                                    put("type", "string")
                                })
                                put("description", "File ID")
                            })
                            add(buildJsonObject {
                                put("name", "token")
                                put("in", "query")
                                put("required", true)
                                put("schema", buildJsonObject {
                                    put("type", "string")
                                })
                            })
                        })
                        put("responses", buildJsonObject {
                            put("200", buildJsonObject {
                                put("description", "File data")
                                put("content", buildJsonObject {
                                    put("application/octet-stream", buildJsonObject {
                                        put("schema", buildJsonObject {
                                            put("type", "string")
                                            put("format", "binary")
                                        })
                                    })
                                })
                            })
                        })
                    })
                })

                // Upload status
                put("/api/upload/status", buildJsonObject {
                    put("get", buildJsonObject {
                        put("summary", "Get upload status")
                        put("description", "Check status of an upload for resume support")
                        put("parameters", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "token")
                                put("in", "query")
                                put("required", true)
                                put("schema", buildJsonObject { put("type", "string") })
                            })
                            add(buildJsonObject {
                                put("name", "filename")
                                put("in", "query")
                                put("required", true)
                                put("schema", buildJsonObject { put("type", "string") })
                            })
                            add(buildJsonObject {
                                put("name", "uploadId")
                                put("in", "query")
                                put("required", false)
                                put("schema", buildJsonObject { put("type", "string") })
                            })
                        })
                        put("responses", buildJsonObject {
                            put("200", buildJsonObject {
                                put("description", "Upload status")
                                put("content", buildJsonObject {
                                    put("application/json", buildJsonObject {
                                        put("schema", buildJsonObject {
                                            put("type", "object")
                                            put("properties", buildJsonObject {
                                                put("exists", buildJsonObject { put("type", "boolean") })
                                                put("size", buildJsonObject { put("type", "integer") })
                                                put("status", buildJsonObject { put("type", "string") })
                                                put("can_resume", buildJsonObject { put("type", "boolean") })
                                            })
                                        })
                                    })
                                })
                            })
                        })
                    })
                })

                // Upload file
                put("/api/upload", buildJsonObject {
                    put("post", buildJsonObject {
                        put("summary", "Upload file")
                        put("description", "Upload a file with resume support. Use Content-Range header for resume.")
                        put("parameters", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "token")
                                put("in", "query")
                                put("required", true)
                                put("schema", buildJsonObject { put("type", "string") })
                            })
                            add(buildJsonObject {
                                put("name", "filename")
                                put("in", "query")
                                put("required", true)
                                put("schema", buildJsonObject { put("type", "string") })
                            })
                            add(buildJsonObject {
                                put("name", "uploadId")
                                put("in", "query")
                                put("required", false)
                                put("schema", buildJsonObject { put("type", "string") })
                            })
                            add(buildJsonObject {
                                put("name", "path")
                                put("in", "query")
                                put("required", false)
                                put("schema", buildJsonObject { put("type", "string") })
                                put("description", "Directory path (default: /)")
                            })
                        })
                        put("requestBody", buildJsonObject {
                            put("content", buildJsonObject {
                                put("application/octet-stream", buildJsonObject {
                                    put("schema", buildJsonObject {
                                        put("type", "string")
                                        put("format", "binary")
                                    })
                                })
                            })
                        })
                        put("responses", buildJsonObject {
                            put("200", buildJsonObject {
                                put("description", "Upload complete or partial")
                                put("content", buildJsonObject {
                                    put("application/json", buildJsonObject {
                                        put("schema", buildJsonObject {
                                            put("type", "object")
                                            put("properties", buildJsonObject {
                                                put("success", buildJsonObject { put("type", "boolean") })
                                                put("upload_id", buildJsonObject { put("type", "string") })
                                                put("bytes_received", buildJsonObject { put("type", "integer") })
                                                put("status", buildJsonObject { put("type", "string") })
                                            })
                                        })
                                    })
                                })
                            })
                            put("409", buildJsonObject {
                                put("description", "Offset mismatch or busy")
                            })
                            put("413", buildJsonObject {
                                put("description", "File too large (max 50GB)")
                            })
                        })
                    })
                })

                // Upload cancel
                put("/api/upload/cancel", buildJsonObject {
                    put("post", buildJsonObject {
                        put("summary", "Cancel upload")
                        put("description", "Cancel an active upload and delete partial file")
                        put("parameters", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "token")
                                put("in", "query")
                                put("required", true)
                                put("schema", buildJsonObject { put("type", "string") })
                            })
                            add(buildJsonObject {
                                put("name", "uploadId")
                                put("in", "query")
                                put("required", true)
                                put("schema", buildJsonObject { put("type", "string") })
                            })
                        })
                        put("responses", buildJsonObject {
                            put("200", buildJsonObject {
                                put("description", "Upload cancelled")
                            })
                        })
                    })
                })

                // SSE Events
                put("/api/upload/events", buildJsonObject {
                    put("get", buildJsonObject {
                        put("summary", "Upload events (SSE)")
                        put("description", "Server-Sent Events stream for real-time upload status updates. Replaces polling.")
                        put("parameters", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "token")
                                put("in", "query")
                                put("required", true)
                                put("schema", buildJsonObject { put("type", "string") })
                            })
                            add(buildJsonObject {
                                put("name", "uploadId")
                                put("in", "query")
                                put("required", false)
                                put("schema", buildJsonObject { put("type", "string") })
                                put("description", "Optional: filter to single upload")
                            })
                        })
                        put("responses", buildJsonObject {
                            put("200", buildJsonObject {
                                put("description", "SSE stream")
                                put("content", buildJsonObject {
                                    put("text/event-stream", buildJsonObject {
                                        put("schema", buildJsonObject {
                                            put("type", "string")
                                        })
                                    })
                                })
                            })
                        })
                    })
                })

                // Pair request
                put("/api/pair/request", buildJsonObject {
                    put("post", buildJsonObject {
                        put("summary", "Request pairing")
                        put("description", "Create a new pairing session for browser access")
                        put("responses", buildJsonObject {
                            put("200", buildJsonObject {
                                put("description", "Pairing session created")
                                put("content", buildJsonObject {
                                    put("application/json", buildJsonObject {
                                        put("schema", buildJsonObject {
                                            put("type", "object")
                                            put("properties", buildJsonObject {
                                                put("pairingId", buildJsonObject { put("type", "string") })
                                                put("expiresAt", buildJsonObject { put("type", "integer") })
                                            })
                                        })
                                    })
                                })
                            })
                        })
                    })
                })

                // Pair status
                put("/api/pair/status", buildJsonObject {
                    put("get", buildJsonObject {
                        put("summary", "Get pairing status")
                        put("description", "Check if pairing was approved by phone")
                        put("parameters", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "id")
                                put("in", "query")
                                put("required", true)
                                put("schema", buildJsonObject { put("type", "string") })
                                put("description", "Pairing ID from /api/pair/request")
                            })
                        })
                        put("responses", buildJsonObject {
                            put("200", buildJsonObject {
                                put("description", "Pairing status")
                                put("content", buildJsonObject {
                                    put("application/json", buildJsonObject {
                                        put("schema", buildJsonObject {
                                            put("type", "object")
                                            put("properties", buildJsonObject {
                                                put("approved", buildJsonObject { put("type", "boolean") })
                                                put("token", buildJsonObject { put("type", "string") })
                                            })
                                        })
                                    })
                                })
                            })
                        })
                    })
                })

                // Push status
                put("/api/push/status", buildJsonObject {
                    put("get", buildJsonObject {
                        put("summary", "Check push status")
                        put("description", "Check if phone has pushed files to browser")
                        put("parameters", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "token")
                                put("in", "query")
                                put("required", true)
                                put("schema", buildJsonObject { put("type", "string") })
                            })
                        })
                        put("responses", buildJsonObject {
                            put("200", buildJsonObject {
                                put("description", "Push status")
                            })
                        })
                    })
                })

                // Push next (download)
                put("/api/push/next", buildJsonObject {
                    put("get", buildJsonObject {
                        put("summary", "Download pushed file")
                        put("description", "Download next file pushed from phone to browser")
                        put("parameters", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "token")
                                put("in", "query")
                                put("required", true)
                                put("schema", buildJsonObject { put("type", "string") })
                            })
                        })
                        put("responses", buildJsonObject {
                            put("200", buildJsonObject {
                                put("description", "File data")
                            })
                            put("204", buildJsonObject {
                                put("description", "No pending push")
                            })
                        })
                    })
                })
            })
            put("components", buildJsonObject {
                put("securitySchemes", buildJsonObject {
                    put("bearerAuth", buildJsonObject {
                        put("type", "http")
                        put("scheme", "bearer")
                        put("description", "Session token from pairing")
                    })
                })
            })
        }

        call.respondText(
            text = openApiSpec.toString(),
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK
        )
    }
}