package com.swaran.airbridge.core.storage.datasource

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import com.swaran.airbridge.core.storage.model.FileNode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject

class MediaStoreDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * Query all files in a directory. Includes pending files (IS_PENDING = 1).
     */
    fun queryFiles(directory: String? = null): List<FileNode> {
        val files = mutableListOf<FileNode>()
        val projection = buildProjection()

        val (selection, selectionArgs) = buildDirectorySelection(directory)

        val sortOrder = "${MediaStore.Files.FileColumns.DISPLAY_NAME} ASC"

        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                files.add(cursor.toFileNode())
            }
        }

        return files
    }

    /**
     * Find a single file by name and directory.
     * Includes pending files (IS_PENDING = 1) which are files being written.
     * Uses RELATIVE_PATH on Android 10+ for better scoped storage compatibility.
     */
    fun findFileByName(fileName: String, directory: String): FileNode? {
        val projection = buildProjection()

        // Build selection based on Android version
        val (selection, selectionArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: Use RELATIVE_PATH for better scoped storage support
            buildRelativePathSelection(fileName, directory)
        } else {
            // Android 9 and below: Use DATA column
            buildDataPathSelection(fileName, directory)
        }

        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.toFileNode()
            }
        }
        return null
    }

    /**
     * Find a file by its MediaStore ID. Works for both regular and pending files.
     */
    fun findFileById(fileId: String): FileNode? {
        val projection = buildProjection()
        val selection = "${MediaStore.Files.FileColumns._ID} = ?"
        val selectionArgs = arrayOf(fileId)

        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.toFileNode()
            }
        }
        return null
    }

    fun getFileInputStream(fileId: String): InputStream? {
        val uri = MediaStore.Files.getContentUri("external").buildUpon()
            .appendPath(fileId)
            .build()
        return try {
            contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Build projection array with IS_PENDING column for Android 10+.
     */
    private fun buildProjection(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.IS_PENDING,
                MediaStore.Files.FileColumns.RELATIVE_PATH
            )
        } else {
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.DATA
            )
        }
    }

    /**
     * Build selection for directory query.
     * Includes both pending (IS_PENDING = 1) and complete (IS_PENDING = 0) files.
     */
    private fun buildDirectorySelection(directory: String?): Pair<String?, Array<String>?> {
        return if (directory != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10+, also check RELATIVE_PATH
                val selection = "(${MediaStore.Files.FileColumns.DATA} LIKE ? OR ${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?)"
                selection to arrayOf("$directory/%", "%${directory.substringAfterLast('/')}/%")
            } else {
                val selection = "${MediaStore.Files.FileColumns.DATA} LIKE ?"
                selection to arrayOf("$directory/%")
            }
        } else {
            null to null
        }
    }

    /**
     * Build selection using DATA column (Android 9 and below).
     */
    private fun buildDataPathSelection(fileName: String, directory: String): Pair<String, Array<String>> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Include both pending and complete files
            val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND ${MediaStore.Files.FileColumns.DATA} LIKE ?"
            selection to arrayOf(fileName, "$directory/%")
        } else {
            val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND ${MediaStore.Files.FileColumns.DATA} LIKE ?"
            selection to arrayOf(fileName, "$directory/%")
        }
    }

    /**
     * Build selection using RELATIVE_PATH (Android 10+).
     * This works better with scoped storage.
     */
    private fun buildRelativePathSelection(fileName: String, directory: String): Pair<String, Array<String>> {
        // Normalize directory to relative path format (e.g., "Download/AirBridge/")
        val relativeDir = directory.removePrefix("/storage/emulated/0/")
            .removePrefix("/sdcard/")
            .trim('/')

        // Try both with and without trailing slash
        val relativePathWithSlash = if (relativeDir.endsWith("/")) relativeDir else "$relativeDir/"
        val relativePathWithoutSlash = relativeDir.trimEnd('/')

        val selection = "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ? AND " +
                "(${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? OR " +
                "${MediaStore.Files.FileColumns.DATA} LIKE ?)"

        return selection to arrayOf(
            fileName,
            relativePathWithSlash,
            relativePathWithoutSlash,
            "$directory/%"
        )
    }

    private fun Cursor.toFileNode(): FileNode {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)).toString()
        val name = getString(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME))
        val size = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE))
        val mimeType = getString(getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: "*/*"
        val modified = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED))
        val path = getString(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)) ?: ""

        // Check if file is pending (Android 10+)
        val isPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getInt(getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_PENDING)) == 1
        } else {
            false
        }

        // Build the correct URI based on the file type
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && path.contains("/downloads/", ignoreCase = true)) {
            "content://media/external/downloads/$id"
        } else {
            "content://media/external/file/$id"
        }

        return FileNode(
            id = id,
            name = name,
            path = path,
            size = size,
            mimeType = mimeType,
            isDirectory = false,
            lastModified = modified * 1000,
            uri = uri,
            parentPath = path.substringBeforeLast('/').takeIf { it != path },
            isPending = isPending
        )
    }
}
