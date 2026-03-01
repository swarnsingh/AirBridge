package com.swaran.airbridge.feature.dashboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import com.swaran.airbridge.core.common.logging.AirLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.swaran.airbridge.domain.model.ServerStatus
import com.swaran.airbridge.feature.dashboard.mvi.DashboardEffect
import com.swaran.airbridge.feature.dashboard.mvi.DashboardIntent
import com.swaran.airbridge.feature.dashboard.qr.QrScannerScreen
import com.swaran.airbridge.feature.dashboard.viewmodel.DashboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "AirLogger"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DashboardLoggerEntryPoint {
    fun airLogger(): AirLogger
}

@Composable
fun DashboardRoute(
    viewModel: DashboardViewModel = hiltViewModel(),
    onNavigateToPermissions: () -> Unit = {},
    onNavigateToFileBrowser: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val logger = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            DashboardLoggerEntryPoint::class.java
        ).airLogger()
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showQrScanner by remember { mutableStateOf(false) }

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

    // Handle navigation effects in Route (system-level operations)
    LaunchedEffect(viewModel.effect) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is DashboardEffect.NavigateToPermissions -> onNavigateToPermissions()
                is DashboardEffect.NavigateToFileBrowser -> onNavigateToFileBrowser()
                // Other effects (ShowError, ShowInfo, ShowQrCode) handled in Screen
                else -> {}
            }
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val hasPermission = checkStoragePermission(context)
            viewModel.sendIntent(DashboardIntent.UpdatePermissionState(hasPermission))
        }
    }

    if (showQrScanner) {
        QrScannerScreen(
            onQrCodeScanned = { qrValue ->
                logger.d(TAG, "onQrCodeScanned", "QR scanned: %s", qrValue)
                showQrScanner = false
                
                // Extract pairing ID from QR (format: "http://...?pairing=ID" or just "ID")
                val pairingId = extractPairingIdFromQr(logger, qrValue)
                logger.d(TAG, "onQrCodeScanned", "Extracted pairingId: %s", pairingId)
                
                if (pairingId == null) {
                    Toast.makeText(context, "Invalid QR code format", Toast.LENGTH_LONG).show()
                    return@QrScannerScreen
                }
                
                // Get server info from Running status
                val runningStatus = state.serverStatus as? ServerStatus.Running
                if (runningStatus == null) {
                    logger.w(TAG, "onQrCodeScanned", "Server not running, cannot approve pairing")
                    Toast.makeText(context, "Server is not running", Toast.LENGTH_LONG).show()
                    return@QrScannerScreen
                }
                
                coroutineScope.launch {
                    val serverAddress = runningStatus.address
                    val port = runningStatus.port
                    
                    logger.d(TAG, "onQrCodeScanned", "Approving pairing at http://%s:%s with id=%s", serverAddress, port, pairingId)
                    
                    val success = approvePairing(logger, serverAddress, port, pairingId)
                    
                    logger.d(TAG, "onQrCodeScanned", "Pairing approval result: %s", success)
                    
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(context, "Device paired successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed to approve pairing. Try again.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onDismiss = { showQrScanner = false }
        )
    } else {
        DashboardScreen(
            state = state,
            activeUploads = state.activeUploads,
            effectFlow = viewModel.effect,
            onIntent = { intent ->
                when (intent) {
                    is DashboardIntent.RequestStorageDirectory -> directoryPicker.launch(null)
                    is DashboardIntent.SendToComputer -> filePicker.launch(arrayOf("*/*"))
                    is DashboardIntent.ScanQrCode -> {
                        // Check if server is running before showing scanner
                        if (state.serverStatus is ServerStatus.Running) {
                            showQrScanner = true
                        } else {
                            Toast.makeText(context, "Start server first to pair", Toast.LENGTH_SHORT).show()
                        }
                    }
                    else -> viewModel.sendIntent(intent)
                }
            }
        )
    }
}

/**
 * Extract pairing ID from QR code value.
 * Supports formats:
 * - "http://host:port/?pairing=ABC123" or "http://host:port?pairing=ABC123"
 * - "ABC123" (raw pairing ID)
 */
private fun extractPairingIdFromQr(logger: AirLogger, qrValue: String): String? {
    logger.d(TAG, "extractPairingIdFromQr", "Extracting pairing ID from: %s", qrValue)

    // Support query params: pairing= or id=
    val pairingRegex = Regex("[?&](pairing|id)=([a-zA-Z0-9-]+)", RegexOption.IGNORE_CASE)
    val match = pairingRegex.find(qrValue)
    if (match != null) {
        val id = match.groupValues[2]
        logger.d(TAG, "extractPairingIdFromQr", "Found pairing ID in URL: %s", id)
        return id
    }

    // Support deep link format: airbridge://pair?id=...
    val deepLinkRegex = Regex("airbridge://pair\\?id=([a-zA-Z0-9-]+)", RegexOption.IGNORE_CASE)
    val deepLinkMatch = deepLinkRegex.find(qrValue)
    if (deepLinkMatch != null) {
        val id = deepLinkMatch.groupValues[1]
        logger.d(TAG, "extractPairingIdFromQr", "Found pairing ID in deep link: %s", id)
        return id
    }

    if (qrValue.startsWith("http")) {
        val pathRegex = Regex("/pair/([a-zA-Z0-9-]+)", RegexOption.IGNORE_CASE)
        val pathMatch = pathRegex.find(qrValue)
        if (pathMatch != null) {
            val id = pathMatch.groupValues[1]
            logger.d(TAG, "extractPairingIdFromQr", "Found pairing ID in path: %s", id)
            return id
        }
    }

    val cleanValue = qrValue.trim()
    return if (cleanValue.matches(Regex("^[a-zA-Z0-9-]+$"))) {
        logger.d(TAG, "extractPairingIdFromQr", "Using raw value as pairing ID: %s", cleanValue)
        cleanValue
    } else {
        logger.w(TAG, "extractPairingIdFromQr", "Could not extract pairing ID from: %s", qrValue)
        null
    }
}

private suspend fun approvePairing(logger: AirLogger, serverAddress: String, port: Int, pairingId: String): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL("http://$serverAddress:$port/api/pair/approve")
            logger.d(TAG, "approvePairing", "Calling %s with pairingId=%s", url, pairingId)

            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val requestBody = """{"pairingId":"$pairingId"}"""
            connection.outputStream.use { it.write(requestBody.toByteArray()) }

            val responseCode = connection.responseCode
            val responseMessage = connection.responseMessage

            logger.d(TAG, "approvePairing", "Response: %s - %s", responseCode, responseMessage)

            connection.disconnect()

            responseCode == 200
        } catch (e: Exception) {
            logger.e(TAG, "approvePairing", e, "Failed to approve pairing")
            false
        }
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
