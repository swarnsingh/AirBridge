package com.swaran.airbridge.core.network.ktor.routes

import com.swaran.airbridge.core.network.ktor.QueryParams
import com.swaran.airbridge.core.network.ktor.ResponseFields
import com.swaran.airbridge.domain.model.UploadState
import org.junit.Assert.*
import org.junit.Test

/**
 * API Contract Tests
 * 
 * These tests verify the HTTP API contract that the browser depends on.
 * They ensure query parameters and JSON fields match between browser and server.
 */
class ApiContractTest {

    @Test
    fun `query params - UPLOAD_ID must be id not uploadId`() {
        // Browser sends: ?id=123
        // Server expects: QueryParams.UPLOAD_ID = "id"
        assertEquals("id", QueryParams.UPLOAD_ID)
    }

    @Test
    fun `query params - TOKEN must be token`() {
        assertEquals("token", QueryParams.TOKEN)
    }

    @Test
    fun `query params - FILENAME must be filename`() {
        assertEquals("filename", QueryParams.FILENAME)
    }

    @Test
    fun `response fields - UPLOAD_ID must be uploadId`() {
        // Server sends JSON: { "uploadId": "..." }
        assertEquals("uploadId", ResponseFields.UPLOAD_ID)
    }

    @Test
    fun `response fields - BYTES_RECEIVED must be bytesReceived`() {
        assertEquals("bytesReceived", ResponseFields.BYTES_RECEIVED)
    }

    @Test
    fun `response fields - SUCCESS must be success`() {
        assertEquals("success", ResponseFields.SUCCESS)
    }

    @Test
    fun `all state values match expected strings`() {
        // Browser checks these exact string values
        assertEquals("none", UploadState.NONE.value)
        assertEquals("queued", UploadState.QUEUED.value)
        assertEquals("resuming", UploadState.RESUMING.value)
        assertEquals("uploading", UploadState.UPLOADING.value)
        assertEquals("pausing", UploadState.PAUSING.value)
        assertEquals("paused", UploadState.PAUSED.value)
        assertEquals("completed", UploadState.COMPLETED.value)
        assertEquals("cancelled", UploadState.CANCELLED.value)
        assertEquals("error", UploadState.ERROR.value)
    }
}
