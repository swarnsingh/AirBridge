package com.swaran.airbridge.feature.filebrowser.mvi

import com.swaran.airbridge.core.mvi.MviEffect
import com.swaran.airbridge.domain.model.FileItem

/**
 * Side effects for the File Browser feature.
 */
sealed class FileBrowserEffect : MviEffect {
    /**
     * Show file details dialog or bottom sheet.
     *
     * @property file The file to show details for
     */
    data class ShowFileDetails(val file: FileItem) : FileBrowserEffect()

    /**
     * Show an error message.
     *
     * @property message Error message to display
     */
    data class ShowError(val message: String) : FileBrowserEffect()
}
