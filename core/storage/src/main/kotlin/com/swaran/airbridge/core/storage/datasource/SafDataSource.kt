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
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val targetDir = findDirectory(root, path) ?: return emptyList()
        return targetDir.listFiles().map { fileNodeFromDocument(it, path) }
    }

    fun getFileInputStream(fileUri: String): InputStream? {
        return try {
            context.contentResolver.openInputStream(Uri.parse(fileUri))
        } catch (e: Exception) {
            null
        }
    }

    fun createFile(treeUri: Uri, path: String, fileName: String, mimeType: String = "*/*"): FileNode? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val targetDir = findDirectory(root, path) ?: return null
        val newFile = targetDir.createFile(mimeType, fileName) ?: return null
        return fileNodeFromDocument(newFile, path)
    }
    
    fun findFile(treeUri: Uri, fileId: String): FileNode? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        // This is a naive implementation and will be slow for large directories.
        // A real implementation would likely involve a database or more targeted search.
        return findFileRecursive(root, fileId)
    }

    private fun findFileRecursive(directory: DocumentFile, fileId: String): FileNode? {
        for (file in directory.listFiles()) {
            if (file.uri.toString() == fileId) {
                return fileNodeFromDocument(file, "") // Path is not easily known here
            }
            if (file.isDirectory) {
                findFileRecursive(file, fileId)?.let { return it }
            }
        }
        return null
    }

    fun findDirectory(root: DocumentFile, path: String): DocumentFile? {
        if (path == "/" || path.isEmpty()) return root
        val segments = path.trim('/').split('/')
        var current = root
        for (segment in segments) {
            current = current.findFile(segment)?.takeIf { it.isDirectory } ?: return null
        }
        return current
    }

    fun fileNodeFromDocument(doc: DocumentFile, parentPath: String): FileNode {
        val path = if (parentPath == "/") "/${doc.name}" else "$parentPath/${doc.name}"
        return FileNode(
            id = doc.uri.toString(),
            name = doc.name ?: "Unknown",
            path = path,
            size = doc.length(),
            mimeType = doc.type ?: (if (doc.isDirectory) "inode/directory" else "*/*"),
            isDirectory = doc.isDirectory,
            lastModified = doc.lastModified(),
            uri = doc.uri.toString(),
            parentPath = parentPath
        )
    }
}
