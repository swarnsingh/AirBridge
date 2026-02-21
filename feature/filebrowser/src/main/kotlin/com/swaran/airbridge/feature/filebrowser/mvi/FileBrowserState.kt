package com.swaran.airbridge.feature.filebrowser.mvi

import com.swaran.airbridge.core.mvi.MviState
import com.swaran.airbridge.domain.model.FileItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * State representation for the File Browser feature.
 *
 * @property isLoading Whether files are currently being loaded
 * @property files List of files in the current directory
 * @property currentPath Current directory path being browsed
 * @property selectedFile Currently selected file for details/actions
 * @property errorMessage Error message to display if loading failed
 */
data class FileBrowserState(
    val isLoading: Boolean = false,
    val files: ImmutableList<FileItem> = persistentListOf(),
    val currentPath: String = "/",
    val selectedFile: FileItem? = null,
    val errorMessage: String? = null
) : MviState
