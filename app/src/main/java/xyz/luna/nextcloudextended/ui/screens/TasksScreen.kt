package xyz.luna.nextcloudextended.ui.screens

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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import xyz.luna.nextcloudextended.data.model.NextcloudTask

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    taskLists: List<Pair<String, String>>,
    selectedName: String,
    tasks: List<NextcloudTask>,
    onTaskListSelected: (String, String) -> Unit,
    onToggleStatus: (NextcloudTask) -> Unit,
    onDeleteTask: (NextcloudTask) -> Unit,
    onCreateList: () -> Unit,
    onRenameList: () -> Unit,
    onDeleteList: () -> Unit
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Liste de tâches :", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                Card(modifier = Modifier.fillMaxWidth().clickable { dropdownExpanded = true }, shape = RoundedCornerShape(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(selectedName.ifEmpty { "Sélectionnez..." }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Choisir", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                DropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }, modifier = Modifier.fillMaxWidth(0.8f)) {
                    taskLists.forEach { list ->
                        DropdownMenuItem(text = { Text(list.second) }, onClick = { dropdownExpanded = false; onTaskListSelected(list.first, list.second) })
                    }
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box {
                Card(modifier = Modifier.size(54.dp).clickable { settingsExpanded = true }, shape = RoundedCornerShape(8.dp)) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramètres", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                DropdownMenu(expanded = settingsExpanded, onDismissRequest = { settingsExpanded = false }) {
                    DropdownMenuItem(text = { Text("Créer une liste") }, leadingIcon = { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { settingsExpanded = false; onCreateList() })
                    if (selectedName.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("Renommer la liste") }, leadingIcon = { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.tertiary) }, onClick = { settingsExpanded = false; onRenameList() })
                        DropdownMenuItem(text = { Text("Supprimer la liste") }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { settingsExpanded = false; onDeleteList() })
                    }
                }
            }
        }
        Text("Tâches à faire :", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        if (tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune tâche dans cette liste.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                items(tasks) { task ->
                    TaskItem(task, onToggle = { onToggleStatus(task) }, onDelete = { onDeleteTask(task) })
                }
            }
        }
    }
}

@Composable
fun TaskItem(task: NextcloudTask, onToggle: () -> Unit, onDelete: () -> Unit) {
    val isCompleted = task.status == "COMPLETED"
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isCompleted, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
                Text(
                    text = task.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None
                )
                if (!task.description.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(task.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!task.due.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("⏳ Échéance : ${task.due}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            IconButton(onClick = { onDelete() }) {
                Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
