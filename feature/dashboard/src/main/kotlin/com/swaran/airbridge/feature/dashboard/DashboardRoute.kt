package com.swaran.airbridge.feature.dashboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.swaran.airbridge.feature.dashboard.mvi.DashboardEffect
import com.swaran.airbridge.feature.dashboard.mvi.DashboardIntent
import com.swaran.airbridge.feature.dashboard.viewmodel.DashboardViewModel

@Composable
fun DashboardRoute(
    viewModel: DashboardViewModel = hiltViewModel(),
    onNavigateToPermissions: () -> Unit = {},
    onNavigateToFileBrowser: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val effect by viewModel.effect.collectAsStateWithLifecycle(initialValue = null)

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

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                val fileName = it.path?.substringAfterLast('/') ?: "unknown_file"
                viewModel.sendIntent(DashboardIntent.SendToComputer(fileName, it))
            }
        }
    )

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

    DashboardScreen(
        state = state,
        activeUploads = state.activeUploads,
        onStartServer = { viewModel.sendIntent(DashboardIntent.StartServer) },
        onStopServer = { viewModel.sendIntent(DashboardIntent.StopServer) },
        onGenerateQr = { viewModel.sendIntent(DashboardIntent.GenerateQrCode) },
        onGrantPermission = onNavigateToPermissions,
        onBrowseFiles = onNavigateToFileBrowser,
        onSelectDirectory = { viewModel.sendIntent(DashboardIntent.RequestStorageDirectory) },
        onSendFile = { filePicker.launch(arrayOf("*/*")) },
        onOpenCamera = {
            val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            if (cameraIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(cameraIntent)
            }
        },
        onPauseUpload = { uploadId ->
            viewModel.sendIntent(DashboardIntent.PauseUpload(uploadId))
        },
        onResumeUpload = { uploadId ->
            viewModel.sendIntent(DashboardIntent.ResumeUpload(uploadId))
        },
        onCancelUpload = { uploadId ->
            viewModel.sendIntent(DashboardIntent.CancelUpload(uploadId))
        }
    )
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
