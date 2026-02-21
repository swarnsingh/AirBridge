package com.swaran.airbridge.feature.filebrowser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.feature.filebrowser.mvi.FileBrowserState
import kotlinx.collections.immutable.persistentListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen composable for browsing files and folders.
 *
 * @param state Current state of the file browser
 * @param onNavigateUp Callback to navigate to parent directory
 * @param onNavigateToFolder Callback to navigate into a folder
 * @param onSelectFile Callback when a file is selected
 * @param onNavigateBack Callback to navigate back from this feature
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    state: FileBrowserState,
    onNavigateUp: () -> Unit,
    onNavigateToFolder: (String) -> Unit,
    onSelectFile: (FileItem) -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.files)) },
                navigationIcon = {
                    if (state.currentPath != "/") {
                        IconButton(onClick = onNavigateUp) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(id = R.string.go_back)
                            )
                        }
                    } else {
                         IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(id = R.string.go_back)
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                state.files.isEmpty() -> {
                    Text(
                        text = stringResource(id = R.string.no_files),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Text(
                                text = state.currentPath,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        items(state.files, key = { it.id }) { file ->
                            FileItemRow(
                                file = file,
                                onClick = {
                                    if (file.isDirectory) {
                                        onNavigateToFolder(file.path)
                                    } else {
                                        onSelectFile(file)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileItemRow(
    file: FileItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = if (file.isDirectory) stringResource(id = R.string.folder_icon) else stringResource(id = R.string.file_icon),
                modifier = Modifier.size(40.dp),
                tint = if (file.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatFileSize(file.size),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = formatDate(file.lastModified),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        else -> "${size / (1024 * 1024 * 1024)} GB"
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

class FileBrowserStateProvider : PreviewParameterProvider<FileBrowserState> {
    override val values = sequenceOf(
        FileBrowserState(isLoading = true),
        FileBrowserState(files = persistentListOf()),
        FileBrowserState(
            files = persistentListOf(
                FileItem("1", "Documents", "/", 0, "", true, System.currentTimeMillis(), null),
                FileItem("2", "image.jpg", "/image.jpg", 1024 * 500, "image/jpeg", false, System.currentTimeMillis(), null),
                FileItem("3", "video.mp4", "/video.mp4", 1024 * 1024 * 10, "video/mp4", false, System.currentTimeMillis(), null)
            ),
            currentPath = "/Download"
        )
    )
}

@Preview(showBackground = true)
@Composable
fun FileBrowserScreenPreview(
    @PreviewParameter(FileBrowserStateProvider::class) state: FileBrowserState
) {
    MaterialTheme {
        FileBrowserScreen(
            state = state,
            onNavigateUp = {},
            onNavigateToFolder = {},
            onSelectFile = {},
            onNavigateBack = {}
        )
    }
}
