package com.swaran.airbridge.domain.model

/**
 * Domain model representing a file or folder in storage.
 *
 * Immutable data class used across all layers for file metadata.
 *
 * @property id Unique identifier (URI for SAF, ID for MediaStore)
 * @property name Display name with extension
 * @property path Directory path (e.g., "/", "/Photos")
 * @property size File size in bytes (0 for directories)
 * @property mimeType MIME type (e.g., "image/jpeg", "application/pdf")
 * @property isDirectory True if folder, false if file
 * @property lastModified Last modification timestamp (Unix millis)
 * @property parentPath Parent directory path (null for root)
 */
data class FileItem(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val mimeType: String,
    val isDirectory: Boolean,
    val lastModified: Long,
    val parentPath: String? = null
)
