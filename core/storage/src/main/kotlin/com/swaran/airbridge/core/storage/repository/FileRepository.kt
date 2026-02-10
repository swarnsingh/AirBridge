package com.swaran.airbridge.core.storage.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.swaran.airbridge.core.storage.datasource.MediaStoreDataSource
import com.swaran.airbridge.core.storage.datasource.SafDataSource
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.domain.model.FolderItem
import com.swaran.airbridge.domain.repository.StorageAccessManager
import com.swaran.airbridge.domain.repository.StorageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject

class FileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStoreDataSource: MediaStoreDataSource,
    private val safDataSource: SafDataSource
) : StorageRepository, StorageAccessManager {

    private var safTreeUri: Uri? = null

    init {
        // Restore last selected SAF tree URI if available
        val prefs = context.getSharedPreferences("airbridge_storage", Context.MODE_PRIVATE)
        val savedUri = prefs.getString(PREF_SAF_TREE_URI, null)
        if (savedUri != null) {
            try {
                safTreeUri = Uri.parse(savedUri)
                Log.d(TAG, "Restored SAF tree URI from prefs")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore SAF tree URI", e)
            }
        }
    }

    override fun setStorageDirectory(uriString: String) {
        val uri = Uri.parse(uriString)
        safTreeUri = uri
        val prefs = context.getSharedPreferences("airbridge_storage", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_SAF_TREE_URI, uri.toString()).apply()
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist URI permission for SAF tree", e)
        }
    }

    override fun hasStorageDirectory(): Boolean = safTreeUri != null

    override suspend fun browseFiles(path: String): Result<List<FileItem>> = runCatching {
        safTreeUri?.let { uri ->
            safDataSource.listFiles(uri, path).map { it.toDomain() }
        } ?: mediaStoreDataSource.queryFiles(path.takeIf { it != "/" }).map { it.toDomain() }
    }

    override suspend fun getFile(fileId: String): Result<FileItem> = runCatching {
        browseFiles().getOrThrow().find { it.id == fileId }
            ?: throw IllegalArgumentException("File not found: $fileId")
    }

    override suspend fun downloadFile(fileId: String): Result<InputStream> = runCatching {
        safDataSource.getFileInputStream(fileId)
            ?: mediaStoreDataSource.getFileInputStream(fileId)
            ?: throw IllegalArgumentException("Cannot open file: $fileId")
    }

    override suspend fun uploadFile(
        path: String,
        fileName: String,
        inputStream: InputStream
    ): Result<FileItem> = runCatching {
        val uri = safTreeUri ?: throw IllegalStateException("SAF not initialized")
        val fileNode = safDataSource.createFile(uri, path, fileName)
            ?: throw IllegalStateException("Failed to create file")

        context.contentResolver.openOutputStream(Uri.parse(fileNode.uri))?.use { output ->
            inputStream.copyTo(output)
        } ?: throw IllegalStateException("Failed to write file")

        fileNode.toDomain()
    }

    override suspend fun deleteFile(fileId: String): Result<Unit> = runCatching {
        val uri = safTreeUri ?: throw IllegalStateException("SAF not initialized")
        val file = DocumentFile.fromTreeUri(context, uri)
            ?.findFile(fileId) ?: throw IllegalArgumentException("File not found")
        if (!file.delete()) throw IllegalStateException("Failed to delete file")
    }

    override suspend fun createFolder(path: String, folderName: String): Result<FolderItem> = runCatching {
        val uri = safTreeUri ?: throw IllegalStateException("SAF not initialized")
        val parent = DocumentFile.fromTreeUri(context, uri)
            ?: throw IllegalStateException("Cannot access directory")
        val newFolder = parent.createDirectory(folderName)
            ?: throw IllegalStateException("Failed to create folder")
        FolderItem(
            id = newFolder.uri.toString(),
            name = folderName,
            path = "$path/$folderName",
            itemCount = 0,
            lastModified = System.currentTimeMillis(),
            parentPath = path
        )
    }

    fun setSafTreeUri(uri: Uri) {
        safTreeUri = uri
    }

    companion object {
        const val TAG = "FileRepository"
        private const val PREF_SAF_TREE_URI = "saf_tree_uri"
    }
}
