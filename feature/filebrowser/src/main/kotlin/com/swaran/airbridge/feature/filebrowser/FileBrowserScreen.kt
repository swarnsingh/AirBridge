package com.swaran.airbridge.feature.filebrowser

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.swaran.airbridge.domain.model.FileItem
import com.swaran.airbridge.feature.filebrowser.mvi.FileBrowserIntent
import com.swaran.airbridge.feature.filebrowser.mvi.FileBrowserState
import kotlinx.collections.immutable.persistentListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    state: FileBrowserState,
    onIntent: (FileBrowserIntent) -> Unit
) {
    val context = LocalContext.current
    var selectedFile by remember { mutableStateOf<FileItem?>(null) }

    // Show bottom sheet for selected file (triggered by user click, not effect)
    selectedFile?.let { file ->
        FileDetailsBottomSheet(
            file = file,
            onDismiss = { selectedFile = null },
            onSend = {
                Toast.makeText(context, "Send: ${file.name}", Toast.LENGTH_SHORT).show()
                selectedFile = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.files)) },
                navigationIcon = {
                    if (state.currentPath != "/") {
                        IconButton(onClick = { onIntent(FileBrowserIntent.NavigateUp) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(id = R.string.go_back)
                            )
                        }
                    } else {
                        IconButton(onClick = { onIntent(FileBrowserIntent.NavigateBack) }) {
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
                                        onIntent(FileBrowserIntent.NavigateToFolder(file.path))
                                    } else {
                                        // Show bottom sheet for file details
                                        selectedFile = file
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileDetailsBottomSheet(
    file: FileItem,
    onDismiss: () -> Unit,
    onSend: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.file_details),
                    style = MaterialTheme.typography.headlineSmall
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = file.mimeType ?: "Unknown type",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            DetailRow(label = "Size", value = formatFileSize(file.size))
            DetailRow(label = "Location", value = file.path)
            DetailRow(label = "Modified", value = formatDateTime(file.lastModified))

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onSend,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Send to Computer")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
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

private fun formatDateTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
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
            onIntent = {}
        )
    }
}
