package com.swaran.airbridge.core.storage.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for FileNode model
 */
class FileNodeTest {

    @Test
    fun `file node properties are set correctly`() {
        val node = FileNode(
            id = "file-id",
            name = "test.txt",
            path = "/documents",
            isDirectory = false,
            size = 1024,
            mimeType = "text/plain",
            lastModified = 1000L
        )

        assertEquals("file-id", node.id)
        assertEquals("test.txt", node.name)
        assertEquals("/documents", node.path)
        assertFalse(node.isDirectory)
        assertEquals(1024, node.size)
        assertEquals("text/plain", node.mimeType)
        assertEquals(1000L, node.lastModified)
    }

    @Test
    fun `directory node has zero size by default`() {
        val node = FileNode(
            id = "dir-id",
            name = "Photos",
            path = "/",
            isDirectory = true,
            size = 0,
            mimeType = "",
            lastModified = 0L
        )

        assertTrue(node.isDirectory)
        assertEquals(0, node.size)
    }

    @Test
    fun `file node toDomain conversion`() {
        val node = FileNode(
            id = "file-id",
            name = "file.jpg",
            path = "/photos",
            isDirectory = false,
            size = 2048,
            mimeType = "image/jpeg",
            lastModified = 5000L
        )

        val domain = node.toDomain()

        assertEquals("file-id", domain.id)
        assertEquals("file.jpg", domain.name)
        assertEquals("/photos", domain.path)
        assertFalse(domain.isDirectory)
        assertEquals(2048, domain.size)
        assertEquals("image/jpeg", domain.mimeType)
        assertEquals(5000L, domain.lastModified)
    }
}
