package com.swaran.airbridge.core.storage.datasource

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import com.swaran.airbridge.core.storage.model.FileNode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject

class MediaStoreDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val contentResolver: ContentResolver = context.contentResolver

    fun queryFiles(directory: String? = null): List<FileNode> {
        val files = mutableListOf<FileNode>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATA
        )

        val selection = if (directory != null) {
            "${MediaStore.Files.FileColumns.DATA} LIKE ?"
        } else null
        val selectionArgs = if (directory != null) {
            arrayOf("$directory/%")
        } else null

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

    private fun Cursor.toFileNode(): FileNode {
        val id = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)).toString()
        val name = getString(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME))
        val size = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE))
        val mimeType = getString(getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)) ?: "*/*"
        val modified = getLong(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED))
        val path = getString(getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)) ?: ""

        return FileNode(
            id = id,
            name = name,
            path = path,
            size = size,
            mimeType = mimeType,
            isDirectory = false,
            lastModified = modified * 1000,
            uri = "content://media/external/file/$id",
            parentPath = path.substringBeforeLast('/').takeIf { it != path }
        )
    }
}
