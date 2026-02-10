package com.swaran.airbridge.domain.model

data class FolderItem(
    val id: String,
    val name: String,
    val path: String,
    val itemCount: Int = 0,
    val lastModified: Long,
    val parentPath: String? = null
)
