package com.swaran.airbridge.feature.dashboard

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.swaran.airbridge.core.network.controller.UploadController
import com.swaran.airbridge.domain.model.ServerStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onNavigateToPermissions: () -> Unit = {},
    onNavigateToFileBrowser: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val effect by viewModel.effect.collectAsStateWithLifecycle(initialValue = null)

    // SAF directory picker launcher
    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.sendIntent(DashboardIntent.SelectStorageDirectory(it))
        }
    }

    // Check permission on every resume and update ViewModel
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val hasPermission = checkStoragePermission(context)
            viewModel.sendIntent(DashboardIntent.UpdatePermissionState(hasPermission))
        }
    }

    LaunchedEffect(effect) {
        when (val e = effect) {
            is DashboardEffect.NavigateToPermissions -> onNavigateToPermissions()
            is DashboardEffect.NavigateToFileBrowser -> onNavigateToFileBrowser()
            is DashboardEffect.LaunchStorageDirectoryPicker -> directoryPicker.launch(null)
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.app_name)) }
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
                else -> {
                    DashboardContent(
                        state = state,
                        onStartServer = { viewModel.sendIntent(DashboardIntent.StartServer) },
                        onStopServer = { viewModel.sendIntent(DashboardIntent.StopServer) },
                        onGenerateQr = { viewModel.sendIntent(DashboardIntent.GenerateQrCode) },
                        onGrantPermission = onNavigateToPermissions,
                        onBrowseFiles = onNavigateToFileBrowser,
                        onSelectDirectory = { viewModel.sendIntent(DashboardIntent.RequestStorageDirectory) },
                        onOpenCamera = {
                            val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                            if (cameraIntent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(cameraIntent)
                            }
                        },
                        uploadController = viewModel.uploadController
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardContent(
    state: DashboardState,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onGenerateQr: () -> Unit,
    onGrantPermission: () -> Unit,
    onBrowseFiles: () -> Unit,
    onSelectDirectory: () -> Unit,
    onOpenCamera: () -> Unit,
    uploadController: UploadController
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
            address = state.serverAddress
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Show storage directory selection if not set
        if (state.storageDirectoryUri == null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Storage Directory Not Selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "Select a folder where uploaded files will be stored",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = onSelectDirectory,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Select Storage Folder")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        when (state.serverStatus) {
            is ServerStatus.Running -> {
                Button(
                    onClick = onGenerateQr,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(id = R.string.show_qr))
                }

                Button(
                    onClick = onBrowseFiles,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(id = R.string.browse_files))
                }

                Button(
                    onClick = onStopServer,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(id = R.string.stop_server))
                }
            }
            is ServerStatus.Stopped -> {
                if (!state.hasStoragePermission) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(id = R.string.storage_permission_required),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Button(
                                onClick = onGrantPermission,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(id = R.string.grant_permission))
                            }
                        }
                    }
                } else if (state.storageDirectoryUri != null) {
                    Button(
                        onClick = onStartServer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(id = R.string.start_server))
                    }
                }
            }
            else -> {}
        }

        // QR Code display
        state.qrCodeUrl?.let { url ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val context = LocalContext.current
                    Text(
                        text = stringResource(id = R.string.scan_to_connect),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Copy this URL and open in browser:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = onOpenCamera,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(id = R.string.open_camera))
                    }
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("AirBridge URL", url)
                            clipboard.setPrimaryClip(clip)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Copy URL to Clipboard")
                    }
                }
            }
        }
        
        UploadProgressCard(uploadsFlow = uploadController.activeUploads)
    }
}

@Composable
private fun ServerStatusCard(
    status: ServerStatus,
    address: String?
) {
    val (cardColor, textColor, statusText) = when (status) {
        is ServerStatus.Running -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "${stringResource(id = R.string.server_running)}\n${stringResource(id = R.string.server_address, address ?: "")}"
        )
        is ServerStatus.Stopped -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(id = R.string.server_stopped)
        )
        is ServerStatus.Error -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            status.message
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.titleMedium,
            color = textColor,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        )
    }
}

private fun checkStoragePermission(context: Context): Boolean {
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
        else -> {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
}
