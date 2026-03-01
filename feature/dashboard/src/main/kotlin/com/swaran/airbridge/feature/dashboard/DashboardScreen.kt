package com.swaran.airbridge.feature.dashboard

import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import com.swaran.airbridge.domain.model.ServerStatus
import com.swaran.airbridge.feature.dashboard.mvi.DashboardEffect
import com.swaran.airbridge.feature.dashboard.mvi.DashboardIntent
import com.swaran.airbridge.feature.dashboard.mvi.DashboardState
import com.swaran.airbridge.feature.dashboard.viewmodel.UploadProgress
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardState,
    activeUploads: ImmutableList<UploadProgress>,
    effectFlow: Flow<DashboardEffect>,
    onIntent: (DashboardIntent) -> Unit
) {
    val context = LocalContext.current
    var showConnectDialog by remember { mutableStateOf(false) }

    // Collect effects from ViewModel
    LaunchedEffect(effectFlow) {
        effectFlow.collect { effect ->
            when (effect) {
                is DashboardEffect.ShowError -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
                }
                is DashboardEffect.ShowInfo -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
                is DashboardEffect.ShowQrCode -> {
                    // QR code shown via dialog, state handles this
                }
                is DashboardEffect.NavigateToPermissions -> {
                    // Navigation handled by Route
                }
                is DashboardEffect.NavigateToFileBrowser -> {
                    // Navigation handled by Route
                }
                is DashboardEffect.LaunchStorageDirectoryPicker -> {
                    // Directory picker launch handled by Route
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.app_name)) },
                actions = {
                    IconButton(onClick = { onIntent(DashboardIntent.RequestStorageDirectory) }) {
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
            // Content always visible
            DashboardContent(
                state = state,
                activeUploads = activeUploads,
                isLoading = state.isLoading,
                onStartServer = { onIntent(DashboardIntent.StartServer) },
                onStopServer = { onIntent(DashboardIntent.StopServer) },
                onShowConnect = { showConnectDialog = true },
                onBrowseFiles = { onIntent(DashboardIntent.NavigateToFileBrowser) },
                onSendFile = { onIntent(DashboardIntent.NavigateToFileBrowser) },
                onPauseUpload = { onIntent(DashboardIntent.PauseUpload(it)) },
                onResumeUpload = { onIntent(DashboardIntent.ResumeUpload(it)) },
                onCancelUpload = { onIntent(DashboardIntent.CancelUpload(it)) }
            )

            if (showConnectDialog && state.serverStatus is ServerStatus.Running) {
                val runningStatus = state.serverStatus
                ConnectBrowserDialog(
                    serverAddress = state.serverAddress,
                    mdnsHostname = state.mdnsHostname,
                    port = runningStatus.port,
                    onDismiss = { showConnectDialog = false },
                    onScanQr = {
                        showConnectDialog = false
                        onIntent(DashboardIntent.ScanQrCode)
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
    isLoading: Boolean,
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
            mdnsHostname = state.mdnsHostname,
            isLoading = isLoading,
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
                    onClick = onShowConnect,
                    enabled = !isLoading
                )
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Folder,
                    label = stringResource(id = R.string.browse_files),
                    onClick = onBrowseFiles,
                    enabled = !isLoading
                )
                ActionCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.AutoMirrored.Filled.Send,
                    label = stringResource(id = R.string.send_file_to_computer),
                    onClick = onSendFile,
                    enabled = !isLoading
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
    mdnsHostname: String?,
    isLoading: Boolean,
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
                if (status is ServerStatus.Running) {
                    val port = status.port
                    val ip = status.address
                    val mdnsLabel = mdnsHostname?.let { "@$it:$port" }
                    val ipLabel = "@$ip:$port"

                    Text(
                        text = stringResource(id = R.string.server_address, mdnsLabel ?: ipLabel),
                        style = MaterialTheme.typography.bodySmall
                    )

                    if (mdnsLabel != null) {
                        Text(
                            text = "IP: $ipLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(
                onClick = if (isRunning) onStop else onStart,
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        if (isRunning) stringResource(id = R.string.stop_server)
                        else stringResource(id = R.string.start_server)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
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
    mdnsHostname: String?,
    port: Int,
    onDismiss: () -> Unit,
    onScanQr: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current

    val displayLabel = mdnsHostname?.let { "@$it:$port" }
        ?: serverAddress?.let { "@$it:$port" }
        ?: ""
    val displayUrl = mdnsHostname?.let { "http://$it:$port" }
        ?: serverAddress?.let { "http://$it:$port" }
        ?: ""

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
                        text = displayLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (displayUrl.isNotEmpty()) {
                                clipboardManager.setText(AnnotatedString(displayUrl))
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

                if (mdnsHostname != null && serverAddress != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "IP: @$serverAddress:$port",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString("http://$serverAddress:$port"))
                            }
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy IP URL",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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

class DashboardStateProvider : androidx.compose.ui.tooling.preview.PreviewParameterProvider<DashboardState> {
    override val values = sequenceOf(
        DashboardState(isLoading = true),
        DashboardState(
            serverStatus = ServerStatus.Stopped,
            hasStoragePermission = true,
            storageDirectoryUri = null
        ),
        DashboardState(
            serverStatus = ServerStatus.Running("192.168.1.100", 8080, "token"),
            serverAddress = "192.168.1.100",
            mdnsHostname = "AirBridge.local"
        ),
    )
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun DashboardScreenPreview(
    @PreviewParameter(DashboardStateProvider::class) state: DashboardState
) {
    MaterialTheme {
        DashboardScreen(
            state = state,
            activeUploads = persistentListOf(),
            effectFlow = emptyFlow(),
            onIntent = {}
        )
    }
}
