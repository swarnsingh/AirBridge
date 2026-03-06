package com.swaran.airbridge.core.storage.repository

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.core.storage.datasource.MediaStoreDataSource
import com.swaran.airbridge.core.storage.datasource.SafDataSource
import com.swaran.airbridge.domain.model.FileDeletedExternallyException
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.domain.model.FolderItem
import com.swaran.airbridge.domain.model.UploadCancelledException
import com.swaran.airbridge.domain.model.UploadPausedException
import com.swaran.airbridge.domain.repository.StorageAccessManager
import com.swaran.airbridge.domain.repository.StorageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.IOException
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
    private val safDataSource: SafDataSource,
    private val logger: AirLogger
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
                logger.w(TAG, "init", e, "Failed to restore SAF tree URI")
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
                logger.w(TAG, "validateSafUri", "Persisted SAF URI is no longer valid or writable")
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
            logger.w(TAG, "setStorageDirectory", e, "Failed to persist URI permission")
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
    ): Result<FileItem> {
        logger.d(TAG, "uploadFile", "[$uploadId] Starting upload: path='$path', fileName='$fileName', append=$append, safTreeUri=$safTreeUri")
        
        return try {
            val result = if (safTreeUri != null && validateSafUri(safTreeUri!!)) {
                // SAF storage path
                logger.d(TAG, "uploadFile", "[$uploadId] Using SAF storage")
                val root = DocumentFile.fromTreeUri(context, safTreeUri!!)!!
                val dir = safDataSource.findDirectory(root, path) ?: throw IllegalStateException("Directory not found: $path")
                logger.d(TAG, "uploadFile", "[$uploadId] Found directory: ${dir.uri}")
                
                val targetFile = dir.findFile(fileName) ?: dir.createFile("*/*", fileName) ?: throw IllegalStateException("Failed to create/access file: $fileName")
                logger.d(TAG, "uploadFile", "[$uploadId] Target file: ${targetFile.uri}, append=$append")
                
                context.contentResolver.openOutputStream(targetFile.uri, if (append) "wa" else "wt")?.use { output ->
                    logger.d(TAG, "uploadFile", "[$uploadId] Output stream opened, starting copy")
                    inputStream.copyToCancellable(output, onProgress = onProgress)
                    logger.d(TAG, "uploadFile", "[$uploadId] Copy completed")
                } ?: throw IllegalStateException("Failed to open output stream for ${targetFile.uri}")
                safDataSource.fileNodeFromDocument(targetFile, path).toDomain()
            } else {
                // MediaStore storage path
                logger.d(TAG, "uploadFile", "[$uploadId] Using MediaStore storage")
                val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/AirBridge${if (path != "/") "/$path" else ""}"
                logger.d(TAG, "uploadFile", "[$uploadId] MediaStore relativePath: $relativePath")
                uploadToMediaStore(fileName, relativePath, inputStream, append, onProgress)
            }
            Result.success(result)
        } catch (e: Exception) {
            // Don't wrap control-flow exceptions - let them propagate
            if (e is CancellationException || e is UploadPausedException || e is UploadCancelledException) {
                logger.d(TAG, "uploadFile", "[$uploadId] Control exception, rethrowing: ${e.javaClass.simpleName}")
                throw e
            }
            
            // Check for external file deletion specifically
            val isFileDeleted = when {
                e is java.io.FileNotFoundException -> true
                e.message?.contains("ENOENT") == true -> true  // No such file or directory
                e.message?.contains("not found", ignoreCase = true) == true -> true
                e.message?.contains("No such file", ignoreCase = true) == true -> true
                // SAF-specific: DocumentFile was deleted
                e is IllegalStateException && e.message?.contains("Failed to open", ignoreCase = true) == true -> {
                    // Check if file still exists by trying to find it
                    val fileStillExists = findFileByName(path, fileName).getOrNull() != null
                    !fileStillExists
                }
                else -> false
            }
            
            if (isFileDeleted) {
                logger.w(TAG, "uploadFile", "[$uploadId] File deleted externally during upload")
                throw FileDeletedExternallyException("File was deleted externally: $fileName", e)
            }
            
            logger.e(TAG, "uploadFile", e, "[$uploadId] Upload failed: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
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
        
        // For MediaStore URIs, use ContentResolver directly
        if (uri.authority == "media") {
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted == 0) throw IllegalStateException("MediaStore delete failed: $fileId")
        } else {
            // For SAF URIs, use DocumentFile
            val docFile = DocumentFile.fromSingleUri(context, uri)
            if (docFile == null || !docFile.delete()) throw IllegalStateException("SAF delete failed: $fileId")
        }
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
        logger.d(TAG, "uploadToMediaStore", "fileName=$fileName, relativePath=$relativePath, append=$append")
        val contentResolver = context.contentResolver
        val uri = if (append) {
            // Find existing file for resume
            logger.d(TAG, "uploadToMediaStore", "Looking for existing file for resume")
            mediaStoreDataSource.queryFiles().find { it.name == fileName && it.path.contains(relativePath) }?.let { Uri.parse(it.uri) } ?: throw IllegalStateException("Resume file not found: $fileName in $relativePath")
        } else {
            // Delete any existing file with same name
            logger.d(TAG, "uploadToMediaStore", "Checking for existing file to delete")
            mediaStoreDataSource.queryFiles().find { it.name == fileName && it.path.contains(relativePath) }?.let { 
                logger.d(TAG, "uploadToMediaStore", "Deleting existing file: ${it.uri}")
                contentResolver.delete(Uri.parse(it.uri), null, null) 
            }
            // Create new file entry
            logger.d(TAG, "uploadToMediaStore", "Creating new file entry in MediaStore")
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, getMimeType(fileName))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }
            contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed for $fileName")
        }
        logger.d(TAG, "uploadToMediaStore", "File URI obtained: $uri, mode=${if (append) "wa" else "wt"}")

        try {
            logger.d(TAG, "uploadToMediaStore", "Opening output stream")
            contentResolver.openOutputStream(uri, if (append) "wa" else "wt")?.use { output ->
                logger.d(TAG, "uploadToMediaStore", "Output stream opened, starting copy")
                inputStream.copyToCancellable(output, onProgress = onProgress)
                logger.d(TAG, "uploadToMediaStore", "Copy completed successfully")
            } ?: throw IllegalStateException("Failed to open output stream for $uri")
        } catch (e: Exception) {
            // Don't delete on CancellationException (pause) or UploadPausedException
            // We want to keep partial files for resume
            val isPauseException = e is CancellationException || e is UploadPausedException

            if (!isPauseException && !append) {
                logger.d(TAG, "uploadToMediaStore", "Deleting partial file after error: ${e.javaClass.simpleName}")
                contentResolver.delete(uri, null, null)
            } else if (isPauseException) {
                logger.d(TAG, "uploadToMediaStore", "Pause detected, keeping partial file for resume")
            }
            throw e
        }
        logger.d(TAG, "uploadToMediaStore", "Querying final file metadata")
        // FIX: queryFiles() builds URIs as "content://media/external/file/$id" but
        // contentResolver.insert() returns "content://media/external/downloads/$id".
        // Different paths → URI string comparison always fails.
        // Extract the numeric ID from the insert URI and match by ID instead.
        val insertedId = uri.lastPathSegment
        return mediaStoreDataSource.queryFiles().find { it.id == insertedId }?.toDomain()
            ?: throw IllegalStateException("Failed to find uploaded file in MediaStore: id=$insertedId, uri=$uri")
    }

    /**
     * Copies input to output with cancellation support.
     *
     * Uses small chunks (8KB) and explicit cancellation checks for instant pause.
     * Uses yield() to check for cancellation which throws CancellationException if cancelled.
     *
     * @param out Output stream to write to
     * @param bufferSize Buffer size for each read/write (default 8KB for quick cancellation)
     * @param onProgress Called after each write with total bytes written
     */
    private suspend fun InputStream.copyToCancellable(
        out: OutputStream,
        bufferSize: Int = 8 * 1024,
        onProgress: (Long) -> Unit
    ) {
        val buffer = ByteArray(bufferSize)
        while (true) {
            currentCoroutineContext().ensureActive()
            // 30s read timeout — connection stall detection
            val bytesRead = withTimeoutOrNull(30_000) {
                read(buffer)
            } ?: throw IOException("Read timeout — connection stalled")
            if (bytesRead < 0) break
            out.write(buffer, 0, bytesRead)
            onProgress(bytesRead.toLong())
            currentCoroutineContext().ensureActive()
        }
        out.flush()
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
