package com.swaran.airbridge.feature.filebrowser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swaran.airbridge.feature.filebrowser.mvi.FileBrowserIntent
import com.swaran.airbridge.feature.filebrowser.viewmodel.FileBrowserViewModel

/**
 * Route composable for the File Browser feature.
 *
 * Handles ViewModel injection and connects the screen to the navigation graph.
 *
 * @param viewModel The FileBrowserViewModel instance
 * @param onNavigateBack Callback to navigate back from this screen
 */
@Composable
fun FileBrowserRoute(
    viewModel: FileBrowserViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    FileBrowserScreen(
        state = state,
        onNavigateUp = { viewModel.sendIntent(FileBrowserIntent.NavigateUp) },
        onNavigateToFolder = { path -> viewModel.sendIntent(FileBrowserIntent.NavigateToFolder(path)) },
        onSelectFile = { file -> viewModel.sendIntent(FileBrowserIntent.SelectFile(file)) },
        onNavigateBack = onNavigateBack
    )
}
