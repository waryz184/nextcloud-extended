package xyz.luna.nextcloudextended.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import xyz.luna.nextcloudextended.LocalExtra
import xyz.luna.nextcloudextended.LocalStrings
import xyz.luna.nextcloudextended.data.model.ActivityItem
import xyz.luna.nextcloudextended.data.model.FileVersion
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import xyz.luna.nextcloudextended.data.model.NextcloudNotification
import xyz.luna.nextcloudextended.data.model.NextcloudShare
import xyz.luna.nextcloudextended.data.model.NotificationAction
import xyz.luna.nextcloudextended.data.model.Sharee
import xyz.luna.nextcloudextended.data.model.TrashedFile
import java.text.DateFormat
import java.util.Date

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    return String.format("%.1f %s", value, units[unit])
}

fun formatQuota(used: Long, total: Long, x: xyz.luna.nextcloudextended.ExtraStrings): String =
    if (total <= 0) x.storageUnlimited(formatBytes(used)) else x.storageUsed(formatBytes(used), formatBytes(total))

private fun formatDate(millis: Long): String =
    if (millis <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenScaffold(title: String, onDismiss: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val s = LocalStrings.current
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, s.back) } },
            actions = { actions() }
        )
    }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content() } }
}

@Composable
private fun EmptyOrSpinner(loading: Boolean, empty: Boolean, emptyText: String, content: @Composable () -> Unit) {
    when {
        empty && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> content()
    }
}

// ── Trash ───────────────────────────────────────────────────────────────────────────────────

@Composable
fun TrashScreen(
    items: List<TrashedFile>, loading: Boolean, onRefresh: () -> Unit,
    onRestore: (TrashedFile) -> Unit, onDelete: (TrashedFile) -> Unit, onEmpty: () -> Unit, onDismiss: () -> Unit
) {
    val x = LocalExtra.current
    val s = LocalStrings.current
    var confirmEmpty by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { onRefresh() }
    FullScreenScaffold(x.trash, onDismiss, actions = {
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, x.refresh) }
        if (items.isNotEmpty()) IconButton(onClick = { confirmEmpty = true }) { Icon(Icons.Default.DeleteForever, x.trashEmptyAll) }
    }) {
        EmptyOrSpinner(loading, items.isEmpty(), x.trashEmpty) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.path }) { item ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                buildString {
                                    if (item.originalLocation.isNotBlank()) append(item.originalLocation.substringBeforeLast('/', "").ifBlank { "/" }).append(" · ")
                                    append(x.trashDeletedAt(formatDate(item.deletionTimeSeconds * 1000)))
                                    if (!item.isDirectory) append(" · ").append(formatBytes(item.size))
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onRestore(item) }) { Icon(Icons.Default.RestoreFromTrash, x.trashRestore) }
                        IconButton(onClick = { onDelete(item) }) { Icon(Icons.Default.Delete, x.trashDeleteForever, tint = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text(x.trashEmptyAll) },
            text = { Text(x.trashEmptyConfirm) },
            confirmButton = { Button(onClick = { confirmEmpty = false; onEmpty() }) { Text(x.trashEmptyAll) } },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text(s.cancel) } }
        )
    }
}

// ── Activity ────────────────────────────────────────────────────────────────────────────────

@Composable
fun ActivityScreen(items: List<ActivityItem>, loading: Boolean, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    val x = LocalExtra.current
    LaunchedEffect(Unit) { onRefresh() }
    FullScreenScaffold(x.activity, onDismiss, actions = {
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, x.refresh) }
    }) {
        EmptyOrSpinner(loading, items.isEmpty(), x.activityNone) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(item.subject, fontWeight = FontWeight.Medium)
                        if (item.message.isNotBlank()) Text(item.message, style = MaterialTheme.typography.bodySmall)
                        Text(item.datetime.replace('T', ' ').substringBefore('+').take(16),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ── Notifications ───────────────────────────────────────────────────────────────────────────

@Composable
fun NotificationsScreen(
    items: List<NextcloudNotification>, loading: Boolean, onRefresh: () -> Unit,
    onDismissItem: (NextcloudNotification) -> Unit, onClearAll: () -> Unit,
    onAction: (NextcloudNotification, NotificationAction) -> Unit, onDismiss: () -> Unit
) {
    val x = LocalExtra.current
    LaunchedEffect(Unit) { onRefresh() }
    FullScreenScaffold(x.notifications, onDismiss, actions = {
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, x.refresh) }
        if (items.isNotEmpty()) TextButton(onClick = onClearAll) { Text(x.notificationsClearAll) }
    }) {
        EmptyOrSpinner(loading, items.isEmpty(), x.notificationsNone) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(item.subject, fontWeight = FontWeight.SemiBold)
                        if (item.message.isNotBlank()) Text(item.message, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            item.actions.forEach { action ->
                                if (action.primary) Button(onClick = { onAction(item, action) }) { Text(action.label) }
                                else TextButton(onClick = { onAction(item, action) }) { Text(action.label) }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onDismissItem(item) }) { Text(x.dismiss) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ── Versions ────────────────────────────────────────────────────────────────────────────────

@Composable
fun VersionsDialog(
    file: NextcloudFile, versions: List<FileVersion>, loading: Boolean,
    onRestore: (FileVersion) -> Unit, onDismiss: () -> Unit
) {
    val x = LocalExtra.current
    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${x.versions} — ${file.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    loading && versions.isEmpty() -> CircularProgressIndicator()
                    versions.isEmpty() -> Text(x.versionsNone, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> versions.forEach { version ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(formatDate(version.lastModifiedMillis))
                                Text(formatBytes(version.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onRestore(version) }) { Text(x.versionRestore) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.close) } }
    )
}

// ── Sharing ─────────────────────────────────────────────────────────────────────────────────

@Composable
fun ShareDialog(
    file: NextcloudFile,
    shares: List<NextcloudShare>,
    sharees: List<Sharee>,
    loading: Boolean,
    passwordRequiredForLinks: Boolean,
    onSearch: (String) -> Unit,
    onShareWith: (Sharee, Boolean) -> Unit,
    onCreateLink: (password: String?, expireDate: String?, canEdit: Boolean) -> Unit,
    onUpdate: (NextcloudShare, Int?, String?, String?) -> Unit,
    onRevoke: (NextcloudShare) -> Unit,
    onCopyLink: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val x = LocalExtra.current
    val s = LocalStrings.current
    var query by remember { mutableStateOf("") }
    var canEdit by remember { mutableStateOf(false) }
    var showLinkOptions by remember { mutableStateOf(false) }
    var linkPassword by remember { mutableStateOf("") }
    var linkExpiry by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${s.share} — ${file.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it; onSearch(it) },
                    label = { Text(x.shareWithPeople) }, placeholder = { Text(x.shareSearchHint) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Switch(checked = canEdit, onCheckedChange = { canEdit = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (canEdit) x.shareCanEdit else x.shareReadOnly, style = MaterialTheme.typography.bodyMedium)
                }
                sharees.forEach { sharee ->
                    Column(Modifier.fillMaxWidth().clickable { onShareWith(sharee, canEdit); query = "" }.padding(vertical = 8.dp)) {
                        Text(sharee.label)
                        sharee.subline?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                if (loading && shares.isEmpty()) CircularProgressIndicator()
                else if (shares.isEmpty()) Text(x.shareNoShares, color = MaterialTheme.colorScheme.onSurfaceVariant)
                shares.forEach { share ->
                    ShareRow(share, onUpdate, onRevoke, onCopyLink)
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (showLinkOptions) {
                    OutlinedTextField(
                        value = linkPassword, onValueChange = { linkPassword = it },
                        label = { Text(x.sharePassword) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                    if (passwordRequiredForLinks && linkPassword.isBlank()) {
                        Text(x.sharePasswordRequired, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedTextField(
                        value = linkExpiry, onValueChange = { linkExpiry = it },
                        label = { Text(x.shareExpiry) }, placeholder = { Text(x.shareExpiryHint) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Button(
                        onClick = {
                            onCreateLink(linkPassword.ifBlank { null }, linkExpiry.ifBlank { null }, canEdit)
                            showLinkOptions = false; linkPassword = ""; linkExpiry = ""
                        },
                        enabled = !passwordRequiredForLinks || linkPassword.isNotBlank(),
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text(x.shareCreateLink) }
                } else {
                    TextButton(onClick = { showLinkOptions = true }) { Text(x.shareCreateLink) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.close) } }
    )
}

@Composable
private fun ShareRow(
    share: NextcloudShare,
    onUpdate: (NextcloudShare, Int?, String?, String?) -> Unit,
    onRevoke: (NextcloudShare) -> Unit,
    onCopyLink: (String) -> Unit
) {
    val x = LocalExtra.current
    val s = LocalStrings.current
    val editable = share.permissions and NextcloudShare.PERM_UPDATE != 0
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        share.isLink -> share.label ?: "Public link"
                        else -> share.displayTarget.ifBlank { "Share ${share.id}" }
                    },
                    fontWeight = FontWeight.Medium
                )
                val details = buildList {
                    add(if (editable) x.shareCanEdit else x.shareReadOnly)
                    if (share.passwordProtected) add(x.sharePassword)
                    share.expiration?.let { add("${x.shareExpiry}: ${it.take(10)}") }
                }
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (share.isLink && share.url != null) TextButton(onClick = { onCopyLink(share.url) }) { Text(s.copy) }
            IconButton(onClick = { onRevoke(share) }) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = editable, onCheckedChange = { wantsEdit ->
                onUpdate(share, if (wantsEdit) NextcloudShare.PERM_EDIT else NextcloudShare.PERM_READ_ONLY, null, null)
            })
            Text(x.shareCanEdit, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun FavoriteBadge() {
    Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.width(18.dp).height(18.dp))
}
