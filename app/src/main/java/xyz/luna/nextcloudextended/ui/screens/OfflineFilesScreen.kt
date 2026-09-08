package xyz.luna.nextcloudextended.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.luna.nextcloudextended.upload.NextcloudDatabase
import xyz.luna.nextcloudextended.upload.OfflineCacheManager
import xyz.luna.nextcloudextended.upload.OfflineFileEntity
import java.io.File

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun OfflineFilesScreen(accountId: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<OfflineFileEntity>?>(null) }

    fun refresh() {
        val id = accountId ?: return
        scope.launch(Dispatchers.IO) {
            val dao = NextcloudDatabase.get(context).offlineFiles()
            files = dao.list(id).filter { File(it.localPath).exists() }.orEmpty()
        }
    }

    LaunchedEffect(accountId) { refresh() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Available offline") },
            navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } },
            actions = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val id = accountId ?: return@launch
                        OfflineCacheManager.clear(context, id)
                        files = emptyList()
                    }
                }) { Text("Clear all") }
            }
        )
    }) { padding ->
        val current = files
        if (current == null) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        } else if (current.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("No files available offline", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(current, key = { it.remotePath }) { file ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(file.remotePath.substringAfterLast('/').ifEmpty { file.remotePath }, style = MaterialTheme.typography.bodyLarge)
                            Text(file.remotePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                OfflineCacheManager.remove(context, accountId ?: return@launch, file.remotePath)
                                val dao = NextcloudDatabase.get(context).offlineFiles()
                                files = dao.list(accountId ?: return@launch).filter { File(it.localPath).exists() }.orEmpty()
                            }
                        }) {
                            Icon(Icons.Default.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}