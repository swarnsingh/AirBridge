package com.swaran.airbridge.feature.filebrowser

import com.swaran.airbridge.core.mvi.MviEffect
import com.swaran.airbridge.domain.model.FileItem

sealed class FileBrowserEffect : MviEffect {
    data class ShowFileDetails(val file: FileItem) : FileBrowserEffect()
    data class ShowError(val message: String) : FileBrowserEffect()
}
