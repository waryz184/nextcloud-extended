package xyz.luna.nextcloudextended.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.luna.nextcloudextended.LocalStrings
import xyz.luna.nextcloudextended.data.model.NextcloudFile

@Composable
fun FilesScreen(
    currentFolderPath: String,
    files: List<NextcloudFile>,
    onFileClick: (NextcloudFile) -> Unit,
    onOpenFile: (NextcloudFile) -> Unit,
    onShareFile: (NextcloudFile) -> Unit,
    onDownloadFile: (NextcloudFile) -> Unit,
    onBackClick: () -> Unit,
    onDeleteFile: (NextcloudFile) -> Unit,
    onRenameFile: (NextcloudFile) -> Unit
) {
    val s = LocalStrings.current
    var searchQuery by remember { mutableStateOf("") }

    val filteredFiles = remember(files, searchQuery) {
        if (searchQuery.isBlank()) files
        else files.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val decoded = remember(currentFolderPath) {
            try { java.net.URLDecoder.decode(currentFolderPath, "UTF-8") } catch (e: Exception) { currentFolderPath }
        }
        val isSubfolder = decoded.count { it == '/' } > 5

        // Only show a navigation header inside a folder. The root title is already in the app bar.
        if (isSubfolder) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBackClick) { Icon(Icons.Default.ArrowBack, s.back) }
                val folderName = remember(decoded) { decoded.trimEnd('/').substringAfterLast('/') }
                Text(folderName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
        }

        // Search
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text(s.searchInFolder) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null) } },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            singleLine = true, shape = RoundedCornerShape(12.dp)
        )

        if (filteredFiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (searchQuery.isBlank()) s.emptyFolder else s.noResultsFor(searchQuery),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                items(filteredFiles) { file ->
                    FileItem(
                        file = file,
                        onClick = { onFileClick(file) },
                        onOpen = { onOpenFile(file) },
                        onShare = { onShareFile(file) },
                        onDownload = { onDownloadFile(file) },
                        onRename = { onRenameFile(file) },
                        onDelete = { onDeleteFile(file) }
                    )
                }
            }
        }
    }
}

@Composable
fun FileItem(
    file: NextcloudFile,
    onClick: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val s = LocalStrings.current
    var menuExpanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(40.dp), shape = RoundedCornerShape(8.dp),
                color = if (file.isDirectory) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                        contentDescription = null,
                        tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (!file.isDirectory) {
                    val sizeStr = remember(file.size, s) {
                        val kb = file.size / 1024.0
                        if (kb > 1024) String.format(s.locale, s.sizeMb, kb / 1024.0) else String.format(s.locale, s.sizeKb, kb)
                    }
                    Text(sizeStr, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(s.folder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, s.moreOptions, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (!file.isDirectory) {
                        DropdownMenuItem(
                            text = { Text(s.open) },
                            leadingIcon = { Icon(Icons.Default.OpenInNew, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { menuExpanded = false; onOpen() }
                        )
                        DropdownMenuItem(
                            text = { Text(s.share) },
                            leadingIcon = { Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.secondary) },
                            onClick = { menuExpanded = false; onShare() }
                        )
                        DropdownMenuItem(
                            text = { Text(s.download) },
                            leadingIcon = { Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { menuExpanded = false; onDownload() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(s.rename) },
                        leadingIcon = { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.tertiary) },
                        onClick = { menuExpanded = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text(s.delete) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}
