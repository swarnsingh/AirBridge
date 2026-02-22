package com.swaran.airbridge.core.storage.repository

import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.runInterruptible
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
                // VALIDATE PERSISTED URI ON STARTUP
                validateSafUri(safTreeUri!!)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore SAF tree URI", e)
            }
        }
    }

    private fun validateSafUri(uri: Uri): Boolean {
        return try {
            val doc = DocumentFile.fromTreeUri(context, uri)
            if (doc == null || !doc.exists() || !doc.canWrite()) {
                Log.w(TAG, "Persisted SAF URI is no longer valid or writable")
                resetToDefaultDirectory()
                false
            } else true
        } catch (e: Exception) {
            resetToDefaultDirectory()
            false
        }
    }

    override fun setStorageDirectory(uriString: String) {
        val uri = Uri.parse(uriString)
        safTreeUri = uri
        val prefs = context.getSharedPreferences("airbridge_storage", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_SAF_TREE_URI, uriString).apply()
        
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist URI permission", e)
        }
    }

    override fun resetToDefaultDirectory() {
        safTreeUri = null
        val prefs = context.getSharedPreferences("airbridge_storage", Context.MODE_PRIVATE)
        prefs.edit().remove(PREF_SAF_TREE_URI).apply()
    }

    override fun getStorageDirectoryUri(): String = (safTreeUri ?: getDefaultStorageUri()).toString()

    override fun getStorageDirectoryName(): String {
        return if (safTreeUri != null) {
            DocumentFile.fromTreeUri(context, safTreeUri!!)?.name ?: "Custom Folder"
        } else {
            "Downloads/AirBridge"
        }
    }

    override fun hasCustomStorageDirectory(): Boolean = safTreeUri != null

    override suspend fun browseFiles(path: String): Result<List<FileItem>> = runCatching {
        if (safTreeUri != null && validateSafUri(safTreeUri!!)) {
            safDataSource.listFiles(safTreeUri!!, path).map { it.toDomain() }
        } else {
            mediaStoreDataSource.queryFiles(getDefaultStoragePath(path)).map { it.toDomain() }
        }
    }

    override suspend fun getFile(fileId: String): Result<FileItem> = runCatching {
        (safTreeUri?.let { if(validateSafUri(it)) safDataSource.findFile(it, fileId) else null }
            ?: mediaStoreDataSource.queryFiles().find { it.id == fileId })?.toDomain()
            ?: throw IllegalArgumentException("File not found: $fileId")
    }

    override suspend fun findFileByName(path: String, fileName: String): Result<FileItem?> = runCatching {
        if (safTreeUri != null && validateSafUri(safTreeUri!!)) {
            val root = DocumentFile.fromTreeUri(context, safTreeUri!!)!!
            val dir = safDataSource.findDirectory(root, path)
            dir?.findFile(fileName)?.let { safDataSource.fileNodeFromDocument(it, path).toDomain() }
        } else {
            val fullPath = getDefaultStoragePath(path) + "/" + fileName
            mediaStoreDataSource.queryFiles().find { it.path == fullPath || it.name == fileName }?.toDomain()
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
        if (safTreeUri != null && validateSafUri(safTreeUri!!)) {
            val root = DocumentFile.fromTreeUri(context, safTreeUri!!)!!
            val dir = safDataSource.findDirectory(root, path) ?: throw IllegalStateException("Directory not found")
            val targetFile = dir.findFile(fileName) ?: dir.createFile("*/*", fileName) ?: throw IllegalStateException("Failed access")
            
            context.contentResolver.openOutputStream(targetFile.uri, if (append) "wa" else "wt")?.use { output ->
                inputStream.copyToCancellable(output, onProgress = onProgress)
            } ?: throw IllegalStateException("Failed write")
            safDataSource.fileNodeFromDocument(targetFile, path).toDomain()
        } else {
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/AirBridge${if (path != "/") "/$path" else ""}"
            uploadToMediaStore(fileName, relativePath, inputStream, append, onProgress)
        }
    }

    override suspend fun deleteFile(fileId: String): Result<Unit> = runCatching {
        val uri = Uri.parse(fileId)
        val docFile = DocumentFile.fromSingleUri(context, uri)
        if (docFile == null || !docFile.delete()) throw IllegalStateException("Delete failed")
    }

    override suspend fun createFolder(path: String, folderName: String): Result<FolderItem> = runCatching {
        if (safTreeUri != null && validateSafUri(safTreeUri!!)) {
            val parentDir = safDataSource.findDirectory(DocumentFile.fromTreeUri(context, safTreeUri!!)!!, path)
            val newFolder = parentDir?.createDirectory(folderName) ?: throw IllegalStateException("Failed")
            val node = safDataSource.fileNodeFromDocument(newFolder, path)
            FolderItem(node.id, node.name, node.path, 0, node.lastModified, path)
        } else {
            FolderItem("default_$folderName", folderName, "$path/$folderName", 0, System.currentTimeMillis(), path)
        }
    }

    private fun getDefaultStorageUri(): Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    private fun getDefaultStoragePath(path: String): String {
        val base = "${Environment.getExternalStorageDirectory().absolutePath}/${Environment.DIRECTORY_DOWNLOADS}/AirBridge"
        return if (path == "/") base else "$base/$path"
    }

    private suspend fun uploadToMediaStore(fileName: String, relativePath: String, inputStream: InputStream, append: Boolean, onProgress: (Long) -> Unit): FileItem {
        val contentResolver = context.contentResolver
        val uri = if (append) {
            mediaStoreDataSource.queryFiles().find { it.name == fileName && it.path.contains(relativePath) }?.let { Uri.parse(it.uri) } ?: throw IllegalStateException("Missing")
        } else {
            mediaStoreDataSource.queryFiles().find { it.name == fileName && it.path.contains(relativePath) }?.let { contentResolver.delete(Uri.parse(it.uri), null, null) }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(fileName))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("Insert failed")
        }

        try {
            contentResolver.openOutputStream(uri, if (append) "wa" else "wt")?.use { output ->
                inputStream.copyToCancellable(output, onProgress = onProgress)
            } ?: throw IllegalStateException("Write failed")
        } catch (e: Exception) {
            // Only delete partial file on actual errors, NOT on CancellationException
            // (which is used for pause - we want to keep partial files for resume).
            // Cancel explicitly deletes via cancelUpload() after job.join().
            if (!append && e !is CancellationException) contentResolver.delete(uri, null, null)
            throw e
        }
        return mediaStoreDataSource.queryFiles().find { it.uri == uri.toString() }?.toDomain() ?: throw IllegalStateException("Missing")
    }

    private suspend fun InputStream.copyToCancellable(out: OutputStream, bufferSize: Int = 16*1024, onProgress: (Long) -> Unit) {
        // runInterruptible allows Kotlin to interrupt the blocking read() call
        // when the coroutine is cancelled, instead of waiting for read() to return
        kotlinx.coroutines.runInterruptible {
            var total: Long = 0
            val buffer = ByteArray(bufferSize)
            var bytes = read(buffer)
            while (bytes >= 0) {
                out.write(buffer, 0, bytes)
                total += bytes
                onProgress(total)
                bytes = read(buffer)
            }
        }
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "")
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    companion object {
        const val TAG = "FileRepository"
        private const val PREF_SAF_TREE_URI = "saf_tree_uri"
    }
}
