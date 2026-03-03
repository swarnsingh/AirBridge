package com.swaran.airbridge.feature.filebrowser.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.swaran.airbridge.core.mvi.MviViewModel
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.domain.usecase.BrowseFilesUseCase
import com.swaran.airbridge.feature.filebrowser.mvi.FileBrowserEffect
import com.swaran.airbridge.feature.filebrowser.mvi.FileBrowserIntent
import com.swaran.airbridge.feature.filebrowser.mvi.FileBrowserState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the File Browser feature.
 *
 * Manages the state of file browsing operations including loading files,
 * navigating directories, and handling file selection.
 *
 * @property browseFilesUseCase Use case for browsing files in a directory
 * @property savedStateHandle Handle for saving state across process death
 */
@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val browseFilesUseCase: BrowseFilesUseCase,
    private val savedStateHandle: SavedStateHandle
) : MviViewModel<FileBrowserIntent, FileBrowserState, FileBrowserEffect>(
    FileBrowserState(currentPath = savedStateHandle[CURRENT_PATH_KEY] ?: "/")
) {

    companion object {
        private const val CURRENT_PATH_KEY = "current_path"
    }

    init {
        viewModelScope.launch { loadFiles() }
    }

    override suspend fun handleIntent(intent: FileBrowserIntent) {
        when (intent) {
            is FileBrowserIntent.LoadFiles -> loadFiles()
            is FileBrowserIntent.NavigateToFolder -> navigateToFolder(intent.path)
            is FileBrowserIntent.SelectFile -> selectFile(intent.file)
            is FileBrowserIntent.NavigateUp -> navigateUp()
            is FileBrowserIntent.NavigateBack -> { /* Handled in Route */ }
        }
    }

    private suspend fun loadFiles() {
        val currentPath = state.value.currentPath

        handleResultState(
            flow = browseFilesUseCase(BrowseFilesUseCase.Params(currentPath)),
            loadingState = state.value.copy(isLoading = true),
            onSuccess = { files: List<FileItem> ->
                state.value.copy(
                    isLoading = false,
                    files = files.toImmutableList()
                ) to null
            },
            onError = { throwable ->
                state.value.copy(isLoading = false) to
                    FileBrowserEffect.ShowError(throwable.message ?: "Failed to load files")
            }
        )
    }

    private suspend fun navigateToFolder(path: String) {
        updateState { copy(currentPath = path) }
        savedStateHandle[CURRENT_PATH_KEY] = path
        loadFiles()
    }

    private suspend fun navigateUp() {
        val currentPath = state.value.currentPath
        if (currentPath != "/") {
            val parentPath = currentPath.substringBeforeLast('/').takeIf { it.isNotEmpty() } ?: "/"
            updateState { copy(currentPath = parentPath) }
            savedStateHandle[CURRENT_PATH_KEY] = parentPath
            loadFiles()
        }
    }

    private fun selectFile(file: FileItem) {
        updateState { copy(selectedFile = file) }
        sendEffect(FileBrowserEffect.ShowFileDetails(file))
    }
}
