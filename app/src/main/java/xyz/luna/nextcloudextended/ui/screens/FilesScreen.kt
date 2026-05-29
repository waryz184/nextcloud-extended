package xyz.luna.nextcloudextended.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.luna.nextcloudextended.data.model.NextcloudFile

@Composable
fun FilesScreen(
    currentFolderPath: String,
    files: List<NextcloudFile>,
    onFileClick: (NextcloudFile) -> Unit,
    onBackClick: () -> Unit,
    onDeleteFile: (NextcloudFile) -> Unit,
    onRenameFile: (NextcloudFile) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val decoded = remember(currentFolderPath) {
                try { java.net.URLDecoder.decode(currentFolderPath, "UTF-8") } catch (e: Exception) { currentFolderPath }
            }
            val slashCount = decoded.count { it == '/' }
            if (slashCount > 5) {
                IconButton(onClick = onBackClick) { Icon(Icons.Default.ArrowBack, contentDescription = "Retour") }
                Spacer(modifier = Modifier.width(8.dp))
            }
            val folderDisplayName = remember(decoded) {
                val parts = decoded.trimEnd('/').split('/')
                if (parts.size <= 5) "Fichiers / Drive" else parts.last()
            }
            Text(folderDisplayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Dossier vide.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                items(files) { file ->
                    FileItem(file, onClick = { onFileClick(file) }, onRename = { onRenameFile(file) }, onDelete = { onDeleteFile(file) })
                }
            }
        }
    }
}

@Composable
fun FileItem(file: NextcloudFile, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }, shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(8.dp),
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
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (!file.isDirectory) {
                    val sizeKb = remember(file.size) {
                        val kb = file.size / 1024.0
                        if (kb > 1024) String.format("%.1f Mo", kb / 1024.0) else String.format("%.1f Ko", kb)
                    }
                    Text("$sizeKb • ${file.lastModified.take(20)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Dossier", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onRename) { Icon(Icons.Default.Edit, contentDescription = "Renommer", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error) }
        }
    }
}
