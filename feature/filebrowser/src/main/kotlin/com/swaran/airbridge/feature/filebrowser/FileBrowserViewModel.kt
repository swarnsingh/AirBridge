package com.swaran.airbridge.feature.filebrowser

import androidx.lifecycle.viewModelScope
import com.swaran.airbridge.core.common.ResultState
import com.swaran.airbridge.core.mvi.MviViewModel
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.domain.usecase.BrowseFilesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val browseFilesUseCase: BrowseFilesUseCase
) : MviViewModel<FileBrowserIntent, FileBrowserState, FileBrowserEffect>(FileBrowserState()) {

    init {
        viewModelScope.launch { loadFiles() }
    }

    override suspend fun handleIntent(intent: FileBrowserIntent) {
        when (intent) {
            is FileBrowserIntent.LoadFiles -> loadFiles()
            is FileBrowserIntent.NavigateToFolder -> navigateToFolder(intent.path)
            is FileBrowserIntent.SelectFile -> selectFile(intent.file)
            is FileBrowserIntent.NavigateUp -> navigateUp()
        }
    }

    private suspend fun loadFiles() {
        val currentPath = state.value.currentPath

        browseFilesUseCase(BrowseFilesUseCase.Params(currentPath))
            .onEach { result ->
                when (result) {
                    is ResultState.Loading -> updateState { copy(isLoading = true) }
                    is ResultState.Success -> {
                        updateState {
                            copy(
                                isLoading = false,
                                files = result.data
                            )
                        }
                    }
                    is ResultState.Error -> {
                        updateState { copy(isLoading = false) }
                        sendEffect(FileBrowserEffect.ShowError(result.throwable.message ?: "Failed to load files"))
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    private fun navigateToFolder(path: String) {
        updateState { copy(currentPath = path) }
        viewModelScope.launch { loadFiles() }
    }

    private fun navigateUp() {
        val currentPath = state.value.currentPath
        if (currentPath != "/") {
            val parentPath = currentPath.substringBeforeLast('/').takeIf { it.isNotEmpty() } ?: "/"
            updateState { copy(currentPath = parentPath) }
            viewModelScope.launch { loadFiles() }
        }
    }

    private fun selectFile(file: FileItem) {
        updateState { copy(selectedFile = file) }
        sendEffect(FileBrowserEffect.ShowFileDetails(file))
    }
}
