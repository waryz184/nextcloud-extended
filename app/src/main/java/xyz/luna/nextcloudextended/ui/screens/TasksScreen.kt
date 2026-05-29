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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import xyz.luna.nextcloudextended.LocalStrings
import xyz.luna.nextcloudextended.data.model.NextcloudTask
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    taskLists: List<Pair<String, String>>,
    selectedName: String,
    tasks: List<NextcloudTask>,
    onTaskListSelected: (String, String) -> Unit,
    onToggleStatus: (NextcloudTask) -> Unit,
    onDeleteTask: (NextcloudTask) -> Unit,
    onEditTask: (NextcloudTask) -> Unit,
    onCreateList: () -> Unit,
    onRenameList: () -> Unit,
    onDeleteList: () -> Unit
) {
    val s = LocalStrings.current
    var dropdownExpanded by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredTasks = remember(tasks, searchQuery) {
        if (searchQuery.isBlank()) tasks
        else tasks.filter { it.summary.contains(searchQuery, ignoreCase = true) || it.description?.contains(searchQuery, ignoreCase = true) == true }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Task list selector + settings
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                Card(modifier = Modifier.fillMaxWidth().clickable { dropdownExpanded = true }, shape = RoundedCornerShape(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(selectedName.ifEmpty { s.selectEllipsis }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                DropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                    taskLists.forEach { list ->
                        DropdownMenuItem(text = { Text(list.second) }, onClick = { dropdownExpanded = false; onTaskListSelected(list.first, list.second) })
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box {
                Card(modifier = Modifier.size(54.dp).clickable { settingsExpanded = true }, shape = RoundedCornerShape(8.dp)) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                DropdownMenu(expanded = settingsExpanded, onDismissRequest = { settingsExpanded = false }) {
                    DropdownMenuItem(text = { Text(s.createList) }, leadingIcon = { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { settingsExpanded = false; onCreateList() })
                    if (selectedName.isNotEmpty()) {
                        DropdownMenuItem(text = { Text(s.renameListAction) }, leadingIcon = { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.tertiary) }, onClick = { settingsExpanded = false; onRenameList() })
                        DropdownMenuItem(text = { Text(s.deleteListAction) }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { settingsExpanded = false; onDeleteList() })
                    }
                }
            }
        }

        // Search field
        OutlinedTextField(
            value = searchQuery, onValueChange = { searchQuery = it },
            placeholder = { Text(s.searchTasks) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null) } },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        if (filteredTasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (searchQuery.isBlank()) s.noTaskInList else s.noResultsFor(searchQuery),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                items(filteredTasks) { task ->
                    TaskItem(task, onToggle = { onToggleStatus(task) }, onEdit = { onEditTask(task) }, onDelete = { onDeleteTask(task) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTaskDialog(
    task: NextcloudTask,
    onDismiss: () -> Unit,
    onSave: (summary: String, description: String, dueDate: String) -> Unit
) {
    val s = LocalStrings.current
    var summary by remember { mutableStateOf(task.summary) }
    var description by remember { mutableStateOf(task.description ?: "") }
    var dueDate by remember { mutableStateOf(task.due ?: "") }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = task.due?.let {
            try { LocalDate.parse(it).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() } catch (e: Exception) { null }
        }
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showDatePicker = false
                    val millis = datePickerState.selectedDateMillis
                    if (millis != null) {
                        dueDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                            .format(DateTimeFormatter.ISO_LOCAL_DATE)
                    }
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(s.cancel) } }
        ) { DatePicker(state = datePickerState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.editTaskTitle) },
        text = {
            Column {
                OutlinedTextField(value = summary, onValueChange = { summary = it }, label = { Text(s.title) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text(s.description) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = dueDate, onValueChange = { dueDate = it },
                        label = { Text(s.dueDate) }, placeholder = { Text(s.dueDatePlaceholder) },
                        modifier = Modifier.weight(1f), readOnly = true
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { showDatePicker = true }) { Icon(Icons.Default.DateRange, s.pickDate, tint = MaterialTheme.colorScheme.primary) }
                    if (dueDate.isNotEmpty()) {
                        IconButton(onClick = { dueDate = "" }) { Icon(Icons.Default.Clear, s.clearDate, tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { if (summary.isNotEmpty()) { onDismiss(); onSave(summary, description, dueDate) } }) { Text(s.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } }
    )
}

@Composable
fun TaskItem(task: NextcloudTask, onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val s = LocalStrings.current
    val isCompleted = task.status == "COMPLETED"
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isCompleted, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(task.summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None)
                if (!task.description.isNullOrEmpty()) Text(task.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!task.due.isNullOrEmpty()) Text("⏳ ${task.due}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, s.edit, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, s.delete, tint = MaterialTheme.colorScheme.error) }
        }
    }
}
