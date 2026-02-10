package com.swaran.airbridge.core.storage.datasource

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.swaran.airbridge.core.storage.model.FileNode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject

class SafDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun listFiles(treeUri: Uri, path: String = "/"): List<FileNode> {
        val docFile = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val targetDir = if (path == "/") docFile else findDirectory(docFile, path) ?: return emptyList()

        return targetDir.listFiles().map { doc ->
            FileNode(
                id = doc.uri.toString(),
                name = doc.name ?: "Unknown",
                path = "$path/${doc.name}",
                size = doc.length(),
                mimeType = doc.type ?: "*/*",
                isDirectory = doc.isDirectory,
                lastModified = doc.lastModified(),
                uri = doc.uri.toString(),
                parentPath = path
            )
        }
    }

    fun getFileInputStream(fileUri: String): InputStream? {
        return try {
            context.contentResolver.openInputStream(Uri.parse(fileUri))
        } catch (e: Exception) {
            null
        }
    }

    fun createFile(treeUri: Uri, path: String, fileName: String, mimeType: String = "*/*"): FileNode? {
        val docFile = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val targetDir = if (path == "/") docFile else findDirectory(docFile, path) ?: return null

        val newFile = targetDir.createFile(mimeType, fileName) ?: return null

        return FileNode(
            id = newFile.uri.toString(),
            name = newFile.name ?: fileName,
            path = "$path/$fileName",
            size = 0,
            mimeType = mimeType,
            isDirectory = false,
            lastModified = System.currentTimeMillis(),
            uri = newFile.uri.toString(),
            parentPath = path
        )
    }

    private fun findDirectory(root: DocumentFile, path: String): DocumentFile? {
        if (path == "/" || path.isEmpty()) return root

        val segments = path.trim('/').split('/')
        var current = root

        for (segment in segments) {
            current = current.findFile(segment)?.takeIf { it.isDirectory } ?: return null
        }

        return current
    }
}
