package xyz.luna.nextcloudextended.ui.screens

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.luna.nextcloudextended.LocalExtra
import xyz.luna.nextcloudextended.LocalStrings
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import xyz.luna.nextcloudextended.ui.files.FileSort
import xyz.luna.nextcloudextended.ui.files.FileThumbnail
import xyz.luna.nextcloudextended.ui.files.ThumbnailLoader
import xyz.luna.nextcloudextended.ui.files.modifiedMillis

private val FavoriteStar = Color(0xFFFFC107)

/** The files browser, laid out like the official client: sort/view bar, 56 dp rows or a thumbnail grid. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    files: List<NextcloudFile>,
    filter: String,
    sort: FileSort,
    onSortChange: (FileSort) -> Unit,
    gridView: Boolean,
    onGridViewChange: (Boolean) -> Unit,
    loader: ThumbnailLoader?,
    resultsLabel: String?,
    onClearResults: () -> Unit,
    emptyText: String,
    canShareFiles: Boolean,
    canShowVersions: Boolean,
    onFileClick: (NextcloudFile) -> Unit,
    onOpenFile: (NextcloudFile) -> Unit,
    onShareFile: (NextcloudFile) -> Unit,
    onDownloadFile: (NextcloudFile) -> Unit,
    onMakeOffline: (NextcloudFile) -> Unit,
    onToggleFavorite: (NextcloudFile) -> Unit,
    onShowVersions: (NextcloudFile) -> Unit,
    onRenameFile: (NextcloudFile) -> Unit,
    onCopyFile: (NextcloudFile) -> Unit,
    onMoveFile: (NextcloudFile) -> Unit,
    onDeleteFile: (NextcloudFile) -> Unit,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val x = LocalExtra.current
    var sheetFile by remember { mutableStateOf<NextcloudFile?>(null) }

    val shown = remember(files, filter, sort) {
        val filtered = if (filter.isBlank()) files else files.filter { it.name.contains(filter, ignoreCase = true) }
        sort.apply(filtered)
    }

    Column(modifier.fillMaxSize()) {
        SortBar(sort, onSortChange, gridView, onGridViewChange)
        if (resultsLabel != null) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(resultsLabel, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onClearResults) { Icon(Icons.Default.Clear, null) }
            }
        }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (filter.isBlank()) emptyText else s.noResultsFor(filter),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge
                )
            }
        } else if (gridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                gridItems(shown, key = { it.path }) { file ->
                    GridCell(file, loader, onClick = { onFileClick(file) }, onMore = { sheetFile = file })
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(shown, key = { it.path }) { file ->
                    FileRow(file, loader, onClick = { onFileClick(file) }, onMore = { sheetFile = file })
                }
            }
        }
    }

    sheetFile?.let { file ->
        ModalBottomSheet(onDismissRequest = { sheetFile = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FileThumbnail(file, loader, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(detailLine(file), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                fun pick(action: () -> Unit) { sheetFile = null; action() }
                if (!file.isDirectory) SheetAction(Icons.AutoMirrored.Filled.OpenInNew, s.open) { pick { onOpenFile(file) } }
                if (canShareFiles) SheetAction(Icons.Default.Share, s.share) { pick { onShareFile(file) } }
                SheetAction(if (file.favorite) Icons.Default.StarBorder else Icons.Default.Star, if (file.favorite) x.unfavorite else x.favorite) { pick { onToggleFavorite(file) } }
                if (!file.isDirectory) {
                    SheetAction(Icons.Default.Download, s.download) { pick { onDownloadFile(file) } }
                    SheetAction(Icons.Default.CloudDownload, x.availableOffline) { pick { onMakeOffline(file) } }
                    if (canShowVersions && file.fileId != null) SheetAction(Icons.Default.History, x.versions) { pick { onShowVersions(file) } }
                }
                if (file.canRename) SheetAction(Icons.Default.Edit, s.rename) { pick { onRenameFile(file) } }
                SheetAction(Icons.Default.ContentCopy, x.copyTo) { pick { onCopyFile(file) } }
                if (file.canRename) SheetAction(Icons.AutoMirrored.Filled.DriveFileMove, x.moveTo) { pick { onMoveFile(file) } }
                if (file.canDelete) SheetAction(Icons.Default.Delete, s.delete, destructive = true) { pick { onDeleteFile(file) } }
            }
        }
    }
}

@Composable
private fun SortBar(sort: FileSort, onSortChange: (FileSort) -> Unit, gridView: Boolean, onGridViewChange: (Boolean) -> Unit) {
    val x = LocalExtra.current
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { open = true }) {
                Text(sortLabel(sort), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Default.ExpandMore, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                FileSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(sortLabel(option), fontWeight = if (option == sort) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { open = false; onSortChange(option) }
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onGridViewChange(!gridView) }) {
            Icon(if (gridView) Icons.Default.ViewList else Icons.Default.ViewModule, if (gridView) x.listView else x.gridView)
        }
    }
}

@Composable
private fun sortLabel(sort: FileSort): String {
    val x = LocalExtra.current
    return when (sort) {
        FileSort.NAME_ASC -> x.sortNameAsc
        FileSort.NAME_DESC -> x.sortNameDesc
        FileSort.DATE_NEWEST -> x.sortDateNewest
        FileSort.DATE_OLDEST -> x.sortDateOldest
        FileSort.SIZE_BIGGEST -> x.sortSizeBiggest
        FileSort.SIZE_SMALLEST -> x.sortSizeSmallest
    }
}

@Composable
private fun detailLine(file: NextcloudFile): String {
    val context = LocalContext.current
    val parts = mutableListOf<String>()
    if (file.size > 0 || !file.isDirectory) parts.add(Formatter.formatShortFileSize(context, file.size))
    val modified = modifiedMillis(file)
    if (modified > 0) parts.add(DateUtils.getRelativeTimeSpanString(modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString())
    return parts.joinToString(" · ")
}

@Composable
private fun FileRow(file: NextcloudFile, loader: ThumbnailLoader?, onClick: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            FileThumbnail(file, loader, 40.dp)
            if (file.favorite) {
                Icon(Icons.Default.Star, null, tint = FavoriteStar, modifier = Modifier.align(Alignment.TopEnd).size(14.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detailLine(file), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Default.MoreVert, LocalStrings.current.moreOptions, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GridCell(file: NextcloudFile, loader: ThumbnailLoader?, onClick: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))) {
            FileThumbnail(file, loader, 112.dp, modifier = Modifier.fillMaxSize(), iconSize = 44.dp, cornerRadius = 10.dp, sizePx = 320)
            if (file.favorite) {
                Icon(Icons.Default.Star, null, tint = FavoriteStar, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(file.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 2.dp))
            IconButton(onClick = onMore, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(32.dp))
        Text(label, fontSize = 16.sp, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}
