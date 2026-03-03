package com.swaran.airbridge.domain.model

/**
 * Domain model representing a folder in storage.
 *
 * @property id Unique identifier (URI for SAF)
 * @property name Display name
 * @property path Full path (e.g., "/", "/Photos")
 * @property itemCount Number of items inside (0 if unknown)
 * @property lastModified Last modification timestamp (Unix millis)
 * @property parentPath Parent directory path (null for root)
 */
data class FolderItem(
    val id: String,
    val name: String,
    val path: String,
    val itemCount: Int = 0,
    val lastModified: Long,
    val parentPath: String? = null
)
