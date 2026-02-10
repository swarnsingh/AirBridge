package com.swaran.airbridge.domain.model

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
