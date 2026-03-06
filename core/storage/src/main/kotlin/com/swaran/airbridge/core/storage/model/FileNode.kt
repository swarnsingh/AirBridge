package com.swaran.airbridge.core.storage.model

import com.swaran.airbridge.domain.model.FileItem

data class FileNode(
    val id: String,
    val name: String,
    val path: String,
    val size: Long = 0,
    val mimeType: String = "",
    val isDirectory: Boolean = false,
    val lastModified: Long = System.currentTimeMillis(),
    val uri: String = "",
    val parentPath: String? = null,
    val isPending: Boolean = false  // True if file is being written (IS_PENDING = 1)
) {
    fun toDomain(): FileItem = FileItem(
        id = id,
        name = name,
        path = path,
        size = size,
        mimeType = mimeType,
        isDirectory = isDirectory,
        lastModified = lastModified,
        parentPath = parentPath
    )
}
