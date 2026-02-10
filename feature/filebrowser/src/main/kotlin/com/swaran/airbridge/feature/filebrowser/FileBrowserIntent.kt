package com.swaran.airbridge.feature.filebrowser

import com.swaran.airbridge.core.mvi.MviIntent
import com.swaran.airbridge.domain.model.FileItem

sealed class FileBrowserIntent : MviIntent {
    data object LoadFiles : FileBrowserIntent()
    data class NavigateToFolder(val path: String) : FileBrowserIntent()
    data class SelectFile(val file: FileItem) : FileBrowserIntent()
    data object NavigateUp : FileBrowserIntent()
}
