package com.swaran.airbridge.feature.filebrowser

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.feature.filebrowser.mvi.FileBrowserEffect
import com.swaran.airbridge.feature.filebrowser.mvi.FileBrowserIntent
import com.swaran.airbridge.feature.filebrowser.viewmodel.FileBrowserViewModel

@Composable
fun FileBrowserRoute(
    viewModel: FileBrowserViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit = {},
    onNavigateToFileDetails: (FileItem) -> Unit = {}
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Route handles all effects: navigation + UI feedback
    LaunchedEffect(viewModel.effect) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is FileBrowserEffect.ShowError -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
                }
                is FileBrowserEffect.ShowFileDetails -> {
                    // Navigate to details screen (or could pass to Screen callback)
                    onNavigateToFileDetails(effect.file)
                }
            }
        }
    }

    FileBrowserScreen(
        state = state,
        onIntent = { intent ->
            when (intent) {
                is FileBrowserIntent.NavigateBack -> onNavigateBack()
                else -> viewModel.sendIntent(intent)
            }
        }
    )
}
