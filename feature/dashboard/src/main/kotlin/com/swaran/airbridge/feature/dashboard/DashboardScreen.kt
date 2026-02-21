package com.swaran.airbridge.feature.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.swaran.airbridge.core.network.controller.UploadProgress
import com.swaran.airbridge.domain.model.ServerStatus
import com.swaran.airbridge.feature.dashboard.mvi.DashboardState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardState,
    activeUploads: ImmutableList<UploadProgress>,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onGenerateQr: () -> Unit,
    onGrantPermission: () -> Unit,
    onBrowseFiles: () -> Unit,
    onSelectDirectory: () -> Unit,
    onSendFile: () -> Unit,
    onOpenCamera: () -> Unit,
    onPauseUpload: (String) -> Unit,
    onResumeUpload: (String) -> Unit,
    onCancelUpload: (String) -> Unit
) {
    var showConnectDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSelectDirectory) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(id = R.string.storage_location)
                        )
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
            if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                DashboardContent(
                    state = state,
                    activeUploads = activeUploads,
                    onStartServer = onStartServer,
                    onStopServer = onStopServer,
                    onShowConnect = { showConnectDialog = true },
                    onBrowseFiles = onBrowseFiles,
                    onSendFile = onSendFile,
                    onPauseUpload = onPauseUpload,
                    onResumeUpload = onResumeUpload,
                    onCancelUpload = onCancelUpload
                )
            }

            if (showConnectDialog && state.serverStatus is ServerStatus.Running) {
                ConnectBrowserDialog(
                    serverAddress = state.serverAddress,
                    onDismiss = { showConnectDialog = false },
                    onScanQr = {
                        showConnectDialog = false
                        onOpenCamera()
                    }
                )
            }
        }
    }
}

@Composable
private fun DashboardContent(
    state: DashboardState,
    activeUploads: ImmutableList<UploadProgress>,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onShowConnect: () -> Unit,
    onBrowseFiles: () -> Unit,
    onSendFile: () -> Unit,
    onPauseUpload: (String) -> Unit,
    onResumeUpload: (String) -> Unit,
    onCancelUpload: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ServerStatusCard(
            status = state.serverStatus,
            address = state.serverAddress,
            onStart = onStartServer,
            onStop = onStopServer
        )

        AnimatedVisibility(visible = state.serverStatus is ServerStatus.Running) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Devices,
                    label = stringResource(id = R.string.connect_browser),
                    onClick = onShowConnect
                )
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Folder,
                    label = stringResource(id = R.string.browse_files),
                    onClick = onBrowseFiles
                )
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.AutoMirrored.Filled.Send,
                    label = stringResource(id = R.string.send_file_to_computer),
                    onClick = onSendFile
                )
            }
        }

        StorageLocationSection(
            name = "Downloads/AirBridge"
        )

        UploadProgressCard(
            activeUploads = activeUploads,
            onPauseUpload = onPauseUpload,
            onResumeUpload = onResumeUpload,
            onCancelUpload = onCancelUpload
        )
    }
}

@Composable
private fun ServerStatusCard(
    status: ServerStatus,
    address: String?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val isRunning = status is ServerStatus.Running

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isRunning) stringResource(id = R.string.server_running)
                    else stringResource(id = R.string.server_stopped),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (isRunning && address != null) {
                    run {
                        Text(
                            text = stringResource(id = R.string.server_address, address),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Button(
                onClick = if (isRunning) onStop else onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    if (isRunning) stringResource(id = R.string.stop_server)
                    else stringResource(id = R.string.start_server)
                )
            }
        }
    }
}

@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.height(100.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun StorageLocationSection(
    name: String
) {
    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(
            text = stringResource(id = R.string.storage_location),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConnectBrowserDialog(
    serverAddress: String?,
    onDismiss: () -> Unit,
    onScanQr: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(id = R.string.browser_access),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(id = R.string.scan_to_connect),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onScanQr,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(id = R.string.scan_qr_code),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(id = R.string.url_copy_desc),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = serverAddress ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            serverAddress?.let {
                                clipboardManager.setText(AnnotatedString(it))
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(id = R.string.copy_url),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.close))
            }
        }
    )
}

class DashboardStateProvider : PreviewParameterProvider<DashboardState> {
    override val values = sequenceOf(
        DashboardState(isLoading = true),
        DashboardState(
            serverStatus = ServerStatus.Stopped,
            hasStoragePermission = true,
            storageDirectoryUri = null
        ),
        DashboardState(
            serverStatus = ServerStatus.Running("192.168.1.100", 8080, "token"),
            serverAddress = "192.168.1.100"
        ),
    )
}

@Preview(showBackground = true)
@Composable
fun DashboardScreenPreview(
    @PreviewParameter(DashboardStateProvider::class) state: DashboardState
) {
    MaterialTheme {
        DashboardScreen(
            state = state,
            activeUploads = persistentListOf(),
            onStartServer = {},
            onStopServer = {},
            onGenerateQr = {},
            onGrantPermission = {},
            onBrowseFiles = {},
            onSelectDirectory = {},
            onSendFile = {},
            onOpenCamera = {},
            onPauseUpload = {},
            onResumeUpload = {},
            onCancelUpload = {}
        )
    }
}
