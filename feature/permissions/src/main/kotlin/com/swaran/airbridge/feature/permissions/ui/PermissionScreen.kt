package com.swaran.airbridge.feature.permissions.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.swaran.airbridge.feature.permissions.R
import com.swaran.airbridge.feature.permissions.mvi.PermissionIntent
import com.swaran.airbridge.feature.permissions.mvi.PermissionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(
    state: PermissionState,
    onIntent: (PermissionIntent) -> Unit
) {
    val context = LocalContext.current

    // Show error toast if permission denied
    LaunchedEffect(state.hasPermission) {
        if (!state.hasPermission) {
            Toast.makeText(
                context,
                "Storage permission is required to share files",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.permissions)) }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = stringResource(id = R.string.folder_icon),
                    modifier = Modifier.size(120.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = stringResource(id = R.string.storage_access_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(id = R.string.storage_access_desc),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(48.dp))

                Button(
                    onClick = { onIntent(PermissionIntent.RequestStoragePermission) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(id = R.string.grant_permission))
                }

                if (!state.hasPermission) {
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(
                        onClick = { onIntent(PermissionIntent.RequestStoragePermission) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(id = R.string.open_settings))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PermissionScreenPreview() {
    MaterialTheme {
        PermissionScreen(
            state = PermissionState(),
            onIntent = {}
        )
    }
}
