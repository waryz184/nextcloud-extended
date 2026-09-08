package xyz.luna.nextcloudextended.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.luna.nextcloudextended.upload.NextcloudDatabase
import xyz.luna.nextcloudextended.upload.UploadEntity
import xyz.luna.nextcloudextended.upload.DownloadEntity

private data class TransferHistoryRow(
    val kind: String,
    val id: Long,
    val title: String,
    val detail: String,
    val state: String,
    val timestamp: Long
)

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun TransferHistoryScreen(onDismiss: () -> Unit, onAction: (String, Long, String) -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<TransferHistoryRow>?>(null) }

    LaunchedEffect(Unit) {
        rows = withContext(Dispatchers.IO) {
            val database = NextcloudDatabase.get(context)
            val uploads = database.uploads().all().map { upload ->
                TransferHistoryRow("upload", upload.id, "Upload: ${upload.name}", upload.accountId, upload.state, upload.createdAt)
            }
            val downloads = database.downloads().all().map { download ->
                TransferHistoryRow("download", download.id, "Download: ${download.fileName}", download.accountId, download.state, download.createdAt)
            }
            (uploads + downloads).sortedByDescending { it.timestamp }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Transfer history") },
            navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } }
        )
    }) { padding ->
        val history = rows
        if (history == null) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        } else if (history.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("No transfers yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(history) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (row.state == UploadEntity.STATE_FAILED || row.state == DownloadEntity.STATE_FAILED) Icons.Default.Refresh else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (row.state == UploadEntity.STATE_FAILED || row.state == DownloadEntity.STATE_FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(row.title, style = MaterialTheme.typography.bodyLarge)
                            Text("${row.state} · ${row.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (row.state == UploadEntity.STATE_FAILED || row.state == DownloadEntity.STATE_FAILED || row.state == UploadEntity.STATE_RETRY || row.state == DownloadEntity.STATE_RETRY) {
                            TextButton(onClick = { onAction(row.kind, row.id, "retry") }) { Text("Retry") }
                        } else if (row.state == UploadEntity.STATE_QUEUED || row.state == UploadEntity.STATE_RUNNING || row.state == DownloadEntity.STATE_QUEUED || row.state == DownloadEntity.STATE_RUNNING) {
                            TextButton(onClick = { onAction(row.kind, row.id, "cancel") }) { Text("Cancel") }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}
