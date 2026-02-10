package com.swaran.airbridge.feature.filebrowser

import com.swaran.airbridge.core.mvi.MviState
import com.swaran.airbridge.domain.model.FileItem

data class FileBrowserState(
    val isLoading: Boolean = false,
    val files: List<FileItem> = emptyList(),
    val currentPath: String = "/",
    val selectedFile: FileItem? = null,
    val errorMessage: String? = null
) : MviState
