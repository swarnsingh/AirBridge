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

/**
 * Repository implementation for file storage operations.
 *
 * ## Dual Storage Strategy
 *
 * This repository supports two storage backends:
 *
 * 1. **MediaStore (Default)**: Uses Android's MediaStore API to save files to
 *    `Downloads/AirBridge/`. Works on all Android versions without requiring
 *    SAF permissions. Files are immediately visible to file managers.
 *
 * 2. **SAF (Storage Access Framework)**: User can select any folder via the
 *    system picker. Uses `DocumentFile` for all operations. Requires the
 *    user to grant persistent URI permission.
 *
 * ## SAF State Management
 *
 * The selected SAF tree URI is persisted to SharedPreferences. On app restart,
 * [validateSafUri] checks if the URI is still valid. If the folder was deleted
 * or permissions were revoked, it automatically falls back to MediaStore.
 *
 * ## Resume Support
 *
 * The [uploadFile] method supports resume via the `append` parameter:
 * - `append=false`: Creates new file (deletes existing if present)
 * - `append=true`: Opens file in "wa" (write-append) mode
 *
 * The output stream is positioned at the end of existing data, so new bytes
 * are appended correctly. This requires the input stream to be positioned
 * at the correct offset (handled by browser's Content-Range header).
 *
 * ## Cancellation Handling
 *
 * The [copyToCancellable] method uses `runInterruptible` to allow the
 * blocking `read()` call to be interrupted when the coroutine is cancelled.
 * This is crucial for pause/resume - without it, the read() would block
 * until the socket timeout (5 minutes) even after cancel() is called.
 *
 * @param context Application context for ContentResolver and preferences
 * @param mediaStoreDataSource MediaStore query/write operations
 * @param safDataSource SAF (DocumentFile) operations
 */
class FileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStoreDataSource: MediaStoreDataSource,
    private val safDataSource: SafDataSource
) : StorageRepository, StorageAccessManager {

    /**
     * The currently selected SAF tree URI, or null to use MediaStore.
     *
     * Set via [setStorageDirectory] when user picks a custom folder.
     * Cleared via [resetToDefaultDirectory] to revert to MediaStore.
     */
    private var safTreeUri: Uri? = null

    init {
        // Restore persisted SAF URI on startup
        val prefs = context.getSharedPreferences("airbridge_storage", Context.MODE_PRIVATE)
        val savedUri = prefs.getString(PREF_SAF_TREE_URI, null)
        if (savedUri != null) {
            try {
                safTreeUri = Uri.parse(savedUri)
                // Validate on startup - permissions may have been revoked
                validateSafUri(safTreeUri!!)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore SAF tree URI", e)
            }
        }
    }

    /**
     * Validates that the SAF URI is still accessible.
     *
     * Called on startup and before SAF operations. If the folder no longer
     * exists or we lost write permission, resets to default (MediaStore).
     *
     * @param uri The SAF tree URI to validate
     * @return true if valid and writable, false otherwise (also resets)
     */
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

    /**
     * Sets a custom storage directory via SAF.
     *
     * Persists the URI to SharedPreferences and requests persistent
     * read/write permissions from the system.
     *
     * @param uriString The SAF tree URI string from the picker
     */
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

    /**
     * Resets to default MediaStore storage (Downloads/AirBridge).
     *
     * Clears the persisted SAF URI so files will be saved via MediaStore.
     */
    override fun resetToDefaultDirectory() {
        safTreeUri = null
        val prefs = context.getSharedPreferences("airbridge_storage", Context.MODE_PRIVATE)
        prefs.edit().remove(PREF_SAF_TREE_URI).apply()
    }

    /**
     * Gets the current storage URI for display purposes.
     *
     * @return MediaStore URI or SAF tree URI as string
     */
    override fun getStorageDirectoryUri(): String = (safTreeUri ?: getDefaultStorageUri()).toString()

    /**
     * Gets a human-readable name for the current storage location.
     *
     * @return "Custom Folder" name for SAF, or "Downloads/AirBridge" for default
     */
    override fun getStorageDirectoryName(): String {
        return if (safTreeUri != null) {
            DocumentFile.fromTreeUri(context, safTreeUri!!)?.name ?: "Custom Folder"
        } else {
            "Downloads/AirBridge"
        }
    }

    /**
     * Checks if user has selected a custom SAF directory.
     *
     * @return true if using SAF, false if using default MediaStore
     */
    override fun hasCustomStorageDirectory(): Boolean = safTreeUri != null

    /**
     * Lists files in the given directory path.
     *
     * Uses SAF if configured and valid, otherwise MediaStore.
     *
     * @param path Directory path relative to storage root (e.g., "/", "/photos")
     * @return Result containing list of files, or exception on error
     */
    override suspend fun browseFiles(path: String): Result<List<FileItem>> = runCatching {
        if (safTreeUri != null && validateSafUri(safTreeUri!!)) {
            safDataSource.listFiles(safTreeUri!!, path).map { it.toDomain() }
        } else {
            mediaStoreDataSource.queryFiles(getDefaultStoragePath(path)).map { it.toDomain() }
        }
    }

    /**
     * Gets a single file by its ID.
     *
     * The ID is the URI string for the file.
     *
     * @param fileId The file ID/URI
     * @return Result containing the file, or exception if not found
     */
    override suspend fun getFile(fileId: String): Result<FileItem> = runCatching {
        (safTreeUri?.let { if(validateSafUri(it)) safDataSource.findFile(it, fileId) else null }
            ?: mediaStoreDataSource.queryFiles().find { it.id == fileId })?.toDomain()
            ?: throw IllegalArgumentException("File not found: $fileId")
    }

    /**
     * Finds a file by name in the given path.
     *
     * Used by UploadController to check for existing files before resume.
     *
     * @param path Directory path to search
     * @param fileName The file name to find
     * @return Result containing the file or null if not found
     */
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

    /**
     * Alias for [findFileByName] for resume checks.
     *
     * In this implementation, partial files are stored at the same path
     * as completed files (we don't use .part extension or separate folder).
     */
    override suspend fun findPartialFile(path: String, fileName: String, uploadId: String): Result<FileItem?> =
        findFileByName(path, fileName)

    /**
     * Opens an input stream for downloading a file.
     *
     * @param fileId The file ID/URI to open
     * @return Result containing the input stream
     */
    override suspend fun downloadFile(fileId: String): Result<InputStream> = runCatching {
        (safDataSource.getFileInputStream(fileId) ?: mediaStoreDataSource.getFileInputStream(fileId))
            ?: throw IllegalArgumentException("Cannot open file: $fileId")
    }

    /**
     * Uploads (writes) a file to storage with progress callback.
     *
     * ## Resume Logic
     * When `append=true`, opens the output stream in "wa" mode which:
     * - Creates the file if it doesn't exist
     * - Seeks to the end if it does exist
     * - Writes new data at that position
     *
     * The caller must ensure the input stream is positioned correctly
     * (using Content-Range header offset).
     *
     * ## Cancellation
     * The [copyToCancellable] method uses `runInterruptible` which allows
     * the blocking read() call to be interrupted when the coroutine is
     * cancelled. Without this, pause would hang until socket timeout.
     *
     * @param path Directory path relative to storage root
     * @param fileName Name for the new file
     * @param uploadId Unique ID for tracking (used in logs)
     * @param inputStream The data to write
     * @param totalBytes Expected total file size (for validation)
     * @param append If true, append to existing file (resume). If false, create new.
     * @param onProgress Called with bytes written so far (for progress UI)
     * @return Result containing the created file metadata
     */
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
            // SAF storage path
            val root = DocumentFile.fromTreeUri(context, safTreeUri!!)!!
            val dir = safDataSource.findDirectory(root, path) ?: throw IllegalStateException("Directory not found")
            val targetFile = dir.findFile(fileName) ?: dir.createFile("*/*", fileName) ?: throw IllegalStateException("Failed access")
            
            context.contentResolver.openOutputStream(targetFile.uri, if (append) "wa" else "wt")?.use { output ->
                inputStream.copyToCancellable(output, onProgress = onProgress)
            } ?: throw IllegalStateException("Failed write")
            safDataSource.fileNodeFromDocument(targetFile, path).toDomain()
        } else {
            // MediaStore storage path
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/AirBridge${if (path != "/") "/$path" else ""}"
            uploadToMediaStore(fileName, relativePath, inputStream, append, onProgress)
        }
    }

    /**
     * Deletes a file by its ID/URI.
     *
     * Used by cancel operations to clean up partial files.
     *
     * @param fileId The file ID/URI to delete
     * @return Result containing Unit on success
     */
    override suspend fun deleteFile(fileId: String): Result<Unit> = runCatching {
        val uri = Uri.parse(fileId)
        val docFile = DocumentFile.fromSingleUri(context, uri)
        if (docFile == null || !docFile.delete()) throw IllegalStateException("Delete failed")
    }

    /**
     * Creates a new folder.
     *
     * Only supported for SAF storage. MediaStore folders are implicit
     * (created automatically via RELATIVE_PATH).
     *
     * @param path Parent directory path
     * @param folderName Name for the new folder
     * @return Result containing the created folder
     */
    override suspend fun createFolder(path: String, folderName: String): Result<FolderItem> = runCatching {
        if (safTreeUri != null && validateSafUri(safTreeUri!!)) {
            val parentDir = safDataSource.findDirectory(DocumentFile.fromTreeUri(context, safTreeUri!!)!!, path)
            val newFolder = parentDir?.createDirectory(folderName) ?: throw IllegalStateException("Failed")
            val node = safDataSource.fileNodeFromDocument(newFolder, path)
            FolderItem(node.id, node.name, node.path, 0, node.lastModified, path)
        } else {
            // MediaStore folders are implicit - return placeholder
            FolderItem("default_$folderName", folderName, "$path/$folderName", 0, System.currentTimeMillis(), path)
        }
    }

    /**
     * Gets the default MediaStore URI for downloads.
     */
    private fun getDefaultStorageUri(): Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    /**
     * Gets the absolute filesystem path for the default storage.
     *
     * Used by MediaStore queries to filter files by path.
     *
     * @param path Relative path (e.g., "/", "/subdir")
     * @return Absolute path like /storage/emulated/0/Download/AirBridge/subdir
     */
    private fun getDefaultStoragePath(path: String): String {
        val base = "${Environment.getExternalStorageDirectory().absolutePath}/${Environment.DIRECTORY_DOWNLOADS}/AirBridge"
        return if (path == "/") base else "$base/$path"
    }

    /**
     * Internal method to upload via MediaStore API.
     *
     * ## File Creation
     * For new files (append=false), inserts a row into MediaStore.Downloads
     * with DISPLAY_NAME and RELATIVE_PATH. This creates the file entry
     * but the actual data isn't written until we open the output stream.
     *
     * ## Append Mode
     * For resume (append=true), queries for existing file by name in the
     * expected path, then opens it in "wa" mode to append.
     *
     * ## Cleanup on Error
     * If an error occurs during write (not CancellationException), the
     * partial file is deleted to avoid leaving corrupt data. Note: pause
     * uses CancellationException which intentionally skips cleanup.
     *
     * @param fileName Name for the file
     * @param relativePath MediaStore relative path (e.g., "Download/AirBridge")
     * @param inputStream Data to write
     * @param append true to append, false to create new
     * @param onProgress Progress callback
     * @return The created/existing file metadata
     */
    private suspend fun uploadToMediaStore(fileName: String, relativePath: String, inputStream: InputStream, append: Boolean, onProgress: (Long) -> Unit): FileItem {
        val contentResolver = context.contentResolver
        val uri = if (append) {
            // Find existing file for resume
            mediaStoreDataSource.queryFiles().find { it.name == fileName && it.path.contains(relativePath) }?.let { Uri.parse(it.uri) } ?: throw IllegalStateException("Missing")
        } else {
            // Delete any existing file with same name
            mediaStoreDataSource.queryFiles().find { it.name == fileName && it.path.contains(relativePath) }?.let { contentResolver.delete(Uri.parse(it.uri), null, null) }
            // Create new file entry
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

    /**
     * Copies input to output with cancellation support.
     *
     * Uses [runInterruptible] to make the blocking [read] call cancellable.
     * Without this, pausing an upload would hang until the socket timeout
     * because [read] blocks waiting for network data.
     *
     * @param out Output stream to write to
     * @param bufferSize Buffer size for each read/write (default 16KB)
     * @param onProgress Called after each write with total bytes written
     */
    private suspend fun InputStream.copyToCancellable(out: OutputStream, bufferSize: Int = 16*1024, onProgress: (Long) -> Unit) {
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

    /**
     * Determines MIME type from file extension.
     *
     * @param fileName The file name
     * @return MIME type string (e.g., image/jpeg) or star/star if unknown
     */
    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "")
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    companion object {
        const val TAG = "FileRepository"
        private const val PREF_SAF_TREE_URI = "saf_tree_uri"
    }
}
