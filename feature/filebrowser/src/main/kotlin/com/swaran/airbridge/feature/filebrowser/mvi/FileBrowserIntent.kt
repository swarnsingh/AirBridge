package com.swaran.airbridge.feature.filebrowser.mvi

import com.swaran.airbridge.core.mvi.MviIntent
import com.swaran.airbridge.domain.model.FileItem

/**
 * Intent events for the File Browser feature.
 */
sealed class FileBrowserIntent : MviIntent {
    /**
     * Load files for the current directory.
     */
    data object LoadFiles : FileBrowserIntent()

    /**
     * Navigate into a specific folder.
     *
     * @property path Path of the folder to navigate to
     */
    data class NavigateToFolder(val path: String) : FileBrowserIntent()

    /**
     * Select a file for details or actions.
     *
     * @property file The selected file item
     */
    data class SelectFile(val file: FileItem) : FileBrowserIntent()

    /**
     * Navigate up to the parent directory.
     */
    data object NavigateUp : FileBrowserIntent()
}
