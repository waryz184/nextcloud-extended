package xyz.luna.nextcloudextended.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.luna.nextcloudextended.LocalExtra
import xyz.luna.nextcloudextended.LocalStrings
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import xyz.luna.nextcloudextended.data.model.NextcloudFile

@Composable
fun FilesScreen(
    currentFolderPath: String,
    files: List<NextcloudFile>,
    onFileClick: (NextcloudFile) -> Unit,
    onOpenFile: (NextcloudFile) -> Unit,
    canShareFiles: Boolean,
    onShareFile: (NextcloudFile) -> Unit,
    onDownloadFile: (NextcloudFile) -> Unit,
    onMakeOffline: (NextcloudFile) -> Unit,
    onBackClick: () -> Unit,
    onDeleteFile: (NextcloudFile) -> Unit,
    onRenameFile: (NextcloudFile) -> Unit
    , onCopyFile: (NextcloudFile) -> Unit
    , onMoveFile: (NextcloudFile) -> Unit,
    filesRoot: String = "",
    storageLine: String? = null,
    canShowVersions: Boolean = false,
    onToggleFavorite: (NextcloudFile) -> Unit = {},
    onShowVersions: (NextcloudFile) -> Unit = {},
    serverResults: List<NextcloudFile>? = null,
    serverResultsLabel: String? = null,
    onServerSearch: (String) -> Unit = {},
    onClearServerSearch: () -> Unit = {}
) {
    val s = LocalStrings.current
    var searchQuery by remember { mutableStateOf("") }

    val filteredFiles = remember(files, searchQuery, serverResults) {
        serverResults ?: if (searchQuery.isBlank()) files
        else files.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Paths are kept decoded everywhere; decoding them again used to corrupt names containing '+' or '%'.
        val isSubfolder = remember(currentFolderPath, filesRoot) {
            filesRoot.isNotEmpty() && currentFolderPath.trimEnd('/').length > filesRoot.trimEnd('/').length
        }
        if (serverResults != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(serverResultsLabel.orEmpty(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { searchQuery = ""; onClearServerSearch() }) { Icon(Icons.Default.Clear, null) }
            }
        }
        storageLine?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp))
        }

        if (isSubfolder) {
            // Keep the back action and search field in one control for nested folders.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(64.dp)
                        .clickable(onClick = onBackClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, s.back)
                }
                VerticalDivider(modifier = Modifier.height(32.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it; if (it.isEmpty()) onClearServerSearch() },
                    placeholder = { Text(s.searchInFolder) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (searchQuery.length >= 2) onServerSearch(searchQuery) }),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = ""; onClearServerSearch() }) { Icon(Icons.Default.Clear, null) }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    singleLine = true,
                    shape = RoundedCornerShape(0.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
                    )
                )
            }
        } else {
            // At the Drive root, the search field remains full width.
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it; if (it.isEmpty()) onClearServerSearch() },
                placeholder = { Text(s.searchInFolder) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (searchQuery.length >= 2) onServerSearch(searchQuery) }),
                trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = ""; onClearServerSearch() }) { Icon(Icons.Default.Clear, null) } },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                singleLine = true, shape = RoundedCornerShape(12.dp)
            )
        }

        if (filteredFiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (searchQuery.isBlank()) s.emptyFolder else s.noResultsFor(searchQuery),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                items(filteredFiles, key = { it.path }) { file ->
                    FileItem(
                        file = file,
                        onClick = { onFileClick(file) },
                        onOpen = { onOpenFile(file) },
                        canShare = canShareFiles,
                        onShare = { onShareFile(file) },
                        onDownload = { onDownloadFile(file) },
                        onMakeOffline = { onMakeOffline(file) },
                        onRename = { onRenameFile(file) },
                        onCopy = { onCopyFile(file) },
                        onMove = { onMoveFile(file) },
                        onDelete = { onDeleteFile(file) },
                        onToggleFavorite = { onToggleFavorite(file) },
                        canShowVersions = canShowVersions && !file.isDirectory && file.fileId != null,
                        onShowVersions = { onShowVersions(file) }
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
    canShare: Boolean,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onMakeOffline: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit = {},
    canShowVersions: Boolean = false,
    onShowVersions: () -> Unit = {}
) {
    val s = LocalStrings.current
    val x = LocalExtra.current
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(file.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                    if (file.favorite) { Spacer(Modifier.width(6.dp)); FavoriteBadge() }
                }
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
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { menuExpanded = false; onOpen() }
                        )
                        if (canShare) {
                            DropdownMenuItem(
                                text = { Text(s.share) },
                                leadingIcon = { Icon(Icons.Default.Share, null, tint = MaterialTheme.colorScheme.secondary) },
                                onClick = { menuExpanded = false; onShare() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(s.download) },
                            leadingIcon = { Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { menuExpanded = false; onDownload() }
                        )
                        DropdownMenuItem(
                            text = { Text("Available offline") },
                            leadingIcon = { Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.primary) },
                            onClick = { menuExpanded = false; onMakeOffline() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (file.favorite) x.unfavorite else x.favorite) },
                        leadingIcon = { Icon(if (file.favorite) Icons.Default.StarBorder else Icons.Default.Star, null, tint = MaterialTheme.colorScheme.tertiary) },
                        onClick = { menuExpanded = false; onToggleFavorite() }
                    )
                    if (canShowVersions) {
                        DropdownMenuItem(
                            text = { Text(x.versions) },
                            leadingIcon = { Icon(Icons.Default.History, null) },
                            onClick = { menuExpanded = false; onShowVersions() }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(s.rename) },
                        leadingIcon = { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.tertiary) },
                        onClick = { menuExpanded = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Copy to") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = { menuExpanded = false; onCopy() }
                    )
                    DropdownMenuItem(
                        text = { Text("Move to") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                        onClick = { menuExpanded = false; onMove() }
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
