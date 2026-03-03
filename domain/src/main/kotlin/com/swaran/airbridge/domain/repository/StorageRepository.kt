package com.swaran.airbridge.domain.repository

import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.domain.model.FolderItem
import java.io.InputStream

/**
 * Repository interface for file storage operations.
 *
 * Defines the contract for browsing, uploading, downloading, and managing files
 * in the device's storage. Implementations handle both SAF (Storage Access Framework)
 * and MediaStore storage backends.
 *
 * ## Key Operations
 *
 * - **Browse**: List files and folders in a directory
 * - **Upload**: Stream file data with progress callbacks
 * - **Download**: Retrieve file as InputStream
 * - **Find**: Locate files by name or partial upload ID
 * - **Delete**: Remove files by ID
 * - **Create Folder**: Make new directories
 *
 * ## Upload Semantics
 *
 * Uploads support resumable transfers via the [append] parameter:
 * - `append=false`: Create new file (or overwrite)
 * - `append=true`: Append to existing file (for resume)
 *
 * The [onProgress] callback reports bytes written, enabling UI progress bars.
 *
 * @see com.swaran.airbridge.core.storage.repository.FileRepository Implementation
 */
interface StorageRepository {

    /**
     * Browse files in the specified path.
     *
     * @param path Directory path (default: "/" for root)
     * @return Result containing list of FileItem or failure
     */
    suspend fun browseFiles(path: String = "/"): Result<List<FileItem>>

    /**
     * Get file metadata by ID.
     *
     * @param fileId Unique file identifier (URI or MediaStore ID)
     * @return Result containing FileItem or failure
     */
    suspend fun getFile(fileId: String): Result<FileItem>

    /**
     * Find a file by its path and name.
     *
     * Used for resume validation - checks if partial file exists.
     *
     * @param path Directory path
     * @param fileName File name
     * @return Result containing FileItem if found, null if not exists, or failure
     */
    suspend fun findFileByName(path: String, fileName: String): Result<FileItem?>

    /**
     * Find a partial upload file by upload ID.
     *
     * @param path Directory path
     * @param fileName File name
     * @param uploadId Upload session ID
     * @return Result containing FileItem or null
     */
    suspend fun findPartialFile(path: String, fileName: String, uploadId: String): Result<FileItem?>

    /**
     * Get InputStream for downloading a file.
     *
     * @param fileId Unique file identifier
     * @return Result containing InputStream or failure
     */
    suspend fun downloadFile(fileId: String): Result<InputStream>

    /**
     * Upload file data to storage.
     *
     * Streams data from [inputStream] to the destination file.
     * Calls [onProgress] after each chunk write.
     *
     * @param path Destination directory path
     * @param fileName Destination file name
     * @param uploadId Upload session ID (for logging/debugging)
     * @param inputStream Source data stream
     * @param totalBytes Expected total file size
     * @param append If true, append to existing file (resume). If false, create new.
     * @param onProgress Callback with bytes written so far
     * @return Result containing FileItem on success
     */
    suspend fun uploadFile(
        path: String,
        fileName: String,
        uploadId: String,
        inputStream: InputStream,
        totalBytes: Long,
        append: Boolean = false,
        onProgress: (Long) -> Unit = {}
    ): Result<FileItem>

    /**
     * Delete a file by its ID.
     *
     * @param fileId Unique file identifier
     * @return Result containing Unit on success
     */
    suspend fun deleteFile(fileId: String): Result<Unit>

    /**
     * Create a new folder.
     *
     * @param path Parent directory path
     * @param folderName New folder name
     * @return Result containing FolderItem on success
     */
    suspend fun createFolder(path: String, folderName: String): Result<FolderItem>
}
