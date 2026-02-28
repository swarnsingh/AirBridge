package com.swaran.airbridge.feature.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.swaran.airbridge.domain.model.TransferStatus
import com.swaran.airbridge.feature.dashboard.viewmodel.UploadProgress
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import java.util.Locale

@Composable
fun UploadProgressCard(
    activeUploads: ImmutableList<UploadProgress>,
    onPauseUpload: (String) -> Unit = {},
    onResumeUpload: (String) -> Unit = {},
    onCancelUpload: (String) -> Unit = {}
) {
    AnimatedVisibility(visible = activeUploads.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = stringResource(id = R.string.file_transfers),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
            
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 500.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                // Key is just the upload ID — stable identity for the item.
                // Compose re-renders the item when content changes (percentage, status).
                // animateFloatAsState works correctly because same item instance is reused.
                items(activeUploads, key = { it.id }) { progress ->
                    UploadProgressItem(
                        progress = progress,
                        onPause = { onPauseUpload(progress.id) },
                        onResume = { onResumeUpload(progress.id) },
                        onCancel = { onCancelUpload(progress.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun UploadProgressItem(
    progress: UploadProgress,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val status = TransferStatus.fromValue(progress.status)
    val animatedProgress by animateFloatAsState(
        targetValue = progress.percentage / 100f,
        label = "transferProgress"
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = when (status) {
                TransferStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
                TransferStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = progress.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    TransferStatusText(status, progress.percentage)
                }
                
                TransferActionButtons(
                    status = status,
                    onPause = onPause,
                    onResume = onResume,
                    onCancel = onCancel
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            if (status != TransferStatus.COMPLETED && status != TransferStatus.CANCELLED && status != TransferStatus.ERROR) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                    color = when (status) {
                        TransferStatus.PAUSED -> MaterialTheme.colorScheme.outline
                        TransferStatus.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = stringResource(
                        id = R.string.transfer_progress_format,
                        formatBytes(progress.bytesReceived),
                        formatBytes(progress.totalBytes)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TransferStatusText(status: TransferStatus, percentage: Int) {
    val (text, color, icon) = when (status) {
        TransferStatus.COMPLETED -> Triple(stringResource(id = R.string.finished), MaterialTheme.colorScheme.primary, Icons.Default.CheckCircle)
        TransferStatus.ERROR -> Triple(stringResource(id = R.string.failed), MaterialTheme.colorScheme.error, Icons.Default.Error)
        TransferStatus.CANCELLED -> Triple(stringResource(id = R.string.cancelled), MaterialTheme.colorScheme.outline, Icons.Default.Close)
        TransferStatus.PAUSED -> Triple(stringResource(id = R.string.paused), MaterialTheme.colorScheme.secondary, Icons.Default.Pause)
        else -> Triple(
            stringResource(id = R.string.uploading_format, percentage),
            MaterialTheme.colorScheme.onSurfaceVariant,
            null
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = color
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}

@Composable
private fun TransferActionButtons(
    status: TransferStatus,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            TransferStatus.UPLOADING, TransferStatus.RESUMING -> {
                FilledTonalIconButton(
                    onClick = onPause,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.Pause, contentDescription = stringResource(id = R.string.pause), modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.cancel), tint = MaterialTheme.colorScheme.error)
                }
            }
            TransferStatus.PAUSED, TransferStatus.INTERRUPTED -> {
                FilledIconButton(
                    onClick = onResume,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = stringResource(id = R.string.resume), modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.cancel), tint = MaterialTheme.colorScheme.error)
                }
            }
            TransferStatus.ERROR -> {
                // No resume for errors (e.g. file deleted) — only allow dismissal
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.cancel), tint = MaterialTheme.colorScheme.error)
                }
            }
            else -> {}
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f)
        bytes < 1024 * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
        else -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024f * 1024f * 1024f))
    }
}

@Preview(showBackground = true)
@Composable
fun UploadProgressCardPreview() {
    MaterialTheme {
        UploadProgressCard(
            activeUploads = persistentListOf(
                UploadProgress("id1", "travel_video.mp4", 1024 * 500, 1024 * 1024, 50, TransferStatus.UPLOADING.value),
                UploadProgress("id2", "backup.zip", 1024 * 1024 * 10, 1024 * 1024 * 10, 100, TransferStatus.COMPLETED.value),
                UploadProgress("id3", "report.pdf", 0, 1024 * 100, 0, TransferStatus.ERROR.value),
                UploadProgress("id4", "photos.rar", 1024 * 200, 1024 * 400, 50, TransferStatus.PAUSED.value)
            )
        )
    }
}
