package com.swaran.airbridge.domain.repository

import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.domain.model.FolderItem
import java.io.InputStream

interface StorageRepository {
    suspend fun browseFiles(path: String = "/"): Result<List<FileItem>>
    suspend fun getFile(fileId: String): Result<FileItem>
    suspend fun findFileByName(path: String, fileName: String): Result<FileItem?>
    suspend fun findPartialFile(path: String, fileName: String, uploadId: String): Result<FileItem?>
    suspend fun downloadFile(fileId: String): Result<InputStream>
    suspend fun uploadFile(
        path: String, 
        fileName: String,
        uploadId: String,
        inputStream: InputStream,
        totalBytes: Long,
        append: Boolean = false,
        onProgress: (Long) -> Unit = {}
    ): Result<FileItem>
    suspend fun deleteFile(fileId: String): Result<Unit>
    suspend fun createFolder(path: String, folderName: String): Result<FolderItem>
}
