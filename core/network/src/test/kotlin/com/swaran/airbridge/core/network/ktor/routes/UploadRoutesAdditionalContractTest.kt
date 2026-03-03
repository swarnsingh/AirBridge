package com.swaran.airbridge.core.network.ktor.routes

import com.swaran.airbridge.core.network.ktor.QueryParams
import com.swaran.airbridge.core.network.ktor.ResponseFields
import org.junit.Test
import org.junit.Assert.*

/**
 * Additional API contract tests for Upload HTTP endpoints
 */
class UploadRoutesAdditionalContractTest {

    @Test
    fun `query params constants are accessible`() {
        // These should compile and be accessible
        assertNotNull(QueryParams.TOKEN)
        assertNotNull(QueryParams.UPLOAD_ID)
        assertNotNull(QueryParams.FILENAME)
        assertNotNull(QueryParams.PATH)
    }

    @Test
    fun `response field constants are accessible`() {
        // These should compile and be accessible
        assertNotNull(ResponseFields.SUCCESS)
        assertNotNull(ResponseFields.UPLOAD_ID)
        assertNotNull(ResponseFields.BYTES_RECEIVED)
    }

    @Test
    fun `query param values match browser expectations`() {
        assertEquals("token", QueryParams.TOKEN)
        assertEquals("id", QueryParams.UPLOAD_ID)
        assertEquals("filename", QueryParams.FILENAME)
        assertEquals("path", QueryParams.PATH)
    }

    @Test
    fun `response field values match browser expectations`() {
        assertEquals("success", ResponseFields.SUCCESS)
        assertEquals("uploadId", ResponseFields.UPLOAD_ID)
        assertEquals("bytesReceived", ResponseFields.BYTES_RECEIVED)
    }
}
