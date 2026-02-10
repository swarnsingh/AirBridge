package com.swaran.airbridge.feature.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swaran.airbridge.core.network.controller.UploadProgress
import kotlinx.coroutines.flow.StateFlow

@Composable
fun UploadProgressCard(
    uploadsFlow: StateFlow<Map<String, UploadProgress>>
) {
    val uploads by uploadsFlow.collectAsState()
    
    if (uploads.isNotEmpty()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Active Uploads",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    items(uploads.entries.toList(), key = { it.key }) { entry ->
                        UploadProgressItem(entry.value)
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadProgressItem(progress: UploadProgress) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = progress.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            
            Text(
                text = when (progress.status) {
                    "completed" -> "✓ Done"
                    "error" -> "✗ Error"
                    else -> "${progress.percentage}%"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (progress.status) {
                    "completed" -> MaterialTheme.colorScheme.primary
                    "error" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        LinearProgressIndicator(
            progress = { progress.percentage / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = when (progress.status) {
                "completed" -> MaterialTheme.colorScheme.primary
                "error" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
        )
        
        Spacer(modifier = Modifier.height(2.dp))
        
        Text(
            text = "${formatBytes(progress.bytesReceived)} / ${formatBytes(progress.totalBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
