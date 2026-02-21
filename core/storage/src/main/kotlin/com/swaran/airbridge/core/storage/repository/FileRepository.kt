package com.swaran.airbridge.core.storage.repository

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.swaran.airbridge.core.storage.datasource.MediaStoreDataSource
import com.swaran.airbridge.core.storage.datasource.SafDataSource
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.domain.model.FolderItem
import com.swaran.airbridge.domain.repository.StorageAccessManager
import com.swaran.airbridge.domain.repository.StorageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

class FileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStoreDataSource: MediaStoreDataSource,
    private val safDataSource: SafDataSource
) : StorageRepository, StorageAccessManager {

    private var safTreeUri: Uri? = null

    init {
        val prefs = context.getSharedPreferences("airbridge_storage", Context.MODE_PRIVATE)
        val savedUri = prefs.getString(PREF_SAF_TREE_URI, null)
        if (savedUri != null) {
            try {
                safTreeUri = Uri.parse(savedUri)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore SAF tree URI", e)
            }
        }
    }

    override fun setStorageDirectory(uriString: String) {
        safTreeUri = Uri.parse(uriString)
        val prefs = context.getSharedPreferences("airbridge_storage", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_SAF_TREE_URI, uriString).apply()
    }

    override fun resetToDefaultDirectory() {
        safTreeUri = null
        val prefs = context.getSharedPreferences("airbridge_storage", Context.MODE_PRIVATE)
        prefs.edit().remove(PREF_SAF_TREE_URI).apply()
    }

    override fun getStorageDirectoryUri(): String {
        return (safTreeUri ?: getDefaultStorageUri()).toString()
    }

    override fun getStorageDirectoryName(): String {
        return if (safTreeUri != null) {
            DocumentFile.fromTreeUri(context, safTreeUri!!)?.name ?: "Custom Folder"
        } else {
            "Downloads/AirBridge"
        }
    }

    override fun hasCustomStorageDirectory(): Boolean = safTreeUri != null

    override suspend fun browseFiles(path: String): Result<List<FileItem>> = runCatching {
        if (safTreeUri != null) {
            safDataSource.listFiles(safTreeUri!!, path).map { it.toDomain() }
        } else {
            mediaStoreDataSource.queryFiles(getDefaultStoragePath(path)).map { it.toDomain() }
        }
    }

    override suspend fun getFile(fileId: String): Result<FileItem> = runCatching {
        (safTreeUri?.let { safDataSource.findFile(it, fileId) }
            ?: mediaStoreDataSource.queryFiles().find { it.id == fileId })?.toDomain()
            ?: throw IllegalArgumentException("File not found: $fileId")
    }

    override suspend fun findFileByName(path: String, fileName: String): Result<FileItem?> = runCatching {
        if (safTreeUri != null) {
            val root = DocumentFile.fromTreeUri(context, safTreeUri!!)!!
            val dir = safDataSource.findDirectory(root, path)
            dir?.findFile(fileName)?.let { safDataSource.fileNodeFromDocument(it, path).toDomain() }
        } else {
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/AirBridge${if (path != "/") "/$path" else ""}"
            mediaStoreDataSource.queryFiles().find { it.name == fileName && it.path.contains(relativePath) }?.toDomain()
        }
    }

    override suspend fun findPartialFile(path: String, fileName: String, uploadId: String): Result<FileItem?> =
        findFileByName(path, fileName)

    override suspend fun downloadFile(fileId: String): Result<InputStream> = runCatching {
        (safDataSource.getFileInputStream(fileId) ?: mediaStoreDataSource.getFileInputStream(fileId))
            ?: throw IllegalArgumentException("Cannot open file: $fileId")
    }

    override suspend fun uploadFile(
        path: String,
        fileName: String,
        uploadId: String,
        inputStream: InputStream,
        totalBytes: Long,
        append: Boolean,
        onProgress: (Long) -> Unit
    ): Result<FileItem> = runCatching {
        if (safTreeUri != null) {
            val root = DocumentFile.fromTreeUri(context, safTreeUri!!)!!
            val dir = safDataSource.findDirectory(root, path) ?: throw IllegalStateException("Directory not found")
            
            // Staff Fix: Find existing first to prevent auto-renaming duplicates
            val targetFile = dir.findFile(fileName) ?: dir.createFile("*/*", fileName)
                ?: throw IllegalStateException("Failed to access/create file")
            
            val mode = if (append) "wa" else "wt"
            context.contentResolver.openOutputStream(targetFile.uri, mode)?.use { output ->
                inputStream.copyToCancellable(output, onProgress = onProgress)
            } ?: throw IllegalStateException("Failed to write to file")
            
            safDataSource.fileNodeFromDocument(targetFile, path).toDomain()
        } else {
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/AirBridge${if (path != "/") "/$path" else ""}"
            uploadToMediaStore(fileName, relativePath, inputStream, append, onProgress)
        }
    }

    override suspend fun deleteFile(fileId: String): Result<Unit> = runCatching {
        val uri = Uri.parse(fileId)
        val docFile = DocumentFile.fromSingleUri(context, uri)
        if (docFile == null || !docFile.delete()) {
            throw IllegalStateException("Failed to delete file")
        }
    }

    override suspend fun createFolder(path: String, folderName: String): Result<FolderItem> = runCatching {
        if (safTreeUri != null) {
            val parentDir = safDataSource.findDirectory(DocumentFile.fromTreeUri(context, safTreeUri!!)!!, path)
            val newFolder = parentDir?.createDirectory(folderName) ?: throw IllegalStateException("Cannot create folder")
            val node = safDataSource.fileNodeFromDocument(newFolder, path)
            FolderItem(node.id, node.name, node.path, 0, node.lastModified, path)
        } else {
            FolderItem("default_$folderName", folderName, "$path/$folderName", 0, System.currentTimeMillis(), path)
        }
    }

    private fun getDefaultStorageUri(): Uri {
        return MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }

    private fun getDefaultStoragePath(path: String): String {
        val base = "${Environment.getExternalStorageDirectory().absolutePath}/${Environment.DIRECTORY_DOWNLOADS}/AirBridge"
        return if (path == "/") base else "$base/$path"
    }

    private suspend fun uploadToMediaStore(
        fileName: String,
        relativePath: String,
        inputStream: InputStream,
        append: Boolean,
        onProgress: (Long) -> Unit
    ): FileItem {
        val contentResolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        
        val uri = if (append) {
            mediaStoreDataSource.queryFiles().find { it.name == fileName && it.path.contains(relativePath) }?.let { Uri.parse(it.uri) }
                ?: throw IllegalStateException("File to append not found")
        } else {
            // Delete existing to avoid duplicates on fresh start
            mediaStoreDataSource.queryFiles().find { it.name == fileName && it.path.contains(relativePath) }?.let { 
                contentResolver.delete(Uri.parse(it.uri), null, null)
            }
            
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(fileName))
                // Removed IS_PENDING=1 because it renames the file to .pending and breaks resumption checks
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
            }
            contentResolver.insert(collection, contentValues) ?: throw IllegalStateException("Failed to insert record")
        }

        try {
            val mode = if (append) "wa" else "wt"
            contentResolver.openOutputStream(uri, mode)?.use { output ->
                inputStream.copyToCancellable(output, onProgress = onProgress)
            } ?: throw IllegalStateException("Failed to write to file")
        } catch (e: Exception) {
            // Only delete if it's NOT an intentional pause
            if (!append && e !is CancellationException) contentResolver.delete(uri, null, null)
            throw e
        }

        return mediaStoreDataSource.queryFiles().find { it.uri == uri.toString() }?.toDomain()
            ?: throw IllegalStateException("File not found after write")
    }

    private suspend fun InputStream.copyToCancellable(out: OutputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE, onProgress: (Long) -> Unit) {
        // runInterruptible wraps blocking IO so that when the coroutine is cancelled,
        // the JVM interrupts the thread — immediately unblocking any read() or write()
        // call instead of waiting for the next buffer boundary to hit ensureActive().
        // This is what makes a single pause/cancel tap respond instantly.
        kotlinx.coroutines.runInterruptible {
            var totalRead: Long = 0
            val buffer = ByteArray(bufferSize)
            var bytes = read(buffer)
            while (bytes >= 0) {
                out.write(buffer, 0, bytes)
                totalRead += bytes
                onProgress(totalRead)
                bytes = read(buffer)
            }
        }
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }

    companion object {
        const val TAG = "FileRepository"
        private const val PREF_SAF_TREE_URI = "saf_tree_uri"
    }
}
