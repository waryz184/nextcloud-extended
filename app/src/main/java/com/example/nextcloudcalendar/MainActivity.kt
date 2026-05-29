package com.example.nextcloudcalendar

import android.os.Bundle
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import com.example.nextcloudcalendar.data.model.CalendarEvent
import com.example.nextcloudcalendar.data.model.NextcloudTask
import com.example.nextcloudcalendar.data.model.NextcloudNote
import com.example.nextcloudcalendar.data.model.NextcloudFile
import com.example.nextcloudcalendar.data.network.CalDavClient
import java.time.LocalDate
import java.time.YearMonth
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                NextcloudHubApp()
            }
        }
    }
}

enum class HubTab {
    CALENDAR, TASKS, NOTES, FILES
}

enum class CalendarViewMode {
    DAY, WEEK, MONTH, YEAR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextcloudHubApp() {
    val context = LocalContext.current
    val sharedPrefs = remember {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secret_shared_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var isConnected by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    
    // Credentials (initialized empty to protect secrets, loaded from SharedPreferences)
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    
    // Load saved credentials on start
    LaunchedEffect(Unit) {
        serverUrl = sharedPrefs.getString("server_url", "") ?: ""
        username = sharedPrefs.getString("username", "") ?: ""
        password = sharedPrefs.getString("password", "") ?: ""
    }

    var client by remember { mutableStateOf<CalDavClient?>(null) }
    var currentTab by remember { mutableStateOf(HubTab.CALENDAR) }
    
    // Calendar States
    var calendars by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedCalendarHref by remember { mutableStateOf("") }
    var selectedCalendarName by remember { mutableStateOf("") }
    var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    
    // Multi-view states
    var calendarViewMode by remember { mutableStateOf(CalendarViewMode.MONTH) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    // Task States
    var selectedTaskListHref by remember { mutableStateOf("") }
    var selectedTaskListName by remember { mutableStateOf("") }
    var tasks by remember { mutableStateOf<List<NextcloudTask>>(emptyList()) }
    
    // Note States
    var notes by remember { mutableStateOf<List<NextcloudNote>>(emptyList()) }

    // Files States
    var currentFolderPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<NextcloudFile>>(emptyList()) }
    var showAddFolderDialog by remember { mutableStateOf(false) }

    // Dialog States
    var showAddTaskDialog by remember { mutableStateOf(false) }
    var showAddEventDialog by remember { mutableStateOf(false) }
    var showCreateTaskListDialog by remember { mutableStateOf(false) }
    var showRenameTaskListDialog by remember { mutableStateOf(false) }
    var showDeleteTaskListDialog by remember { mutableStateOf(false) }
    var showRenameFileDialog by remember { mutableStateOf(false) }
    var fileToRename by remember { mutableStateOf<NextcloudFile?>(null) }
    var showAddFilesOptionDialog by remember { mutableStateOf(false) }
    var showAddNoteDialog by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NextcloudNote?>(null) }
    var viewingNote by remember { mutableStateOf<NextcloudNote?>(null) }

    // Error helper
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun refreshData() {
        val c = client ?: return
        isLoading = true
        when (currentTab) {
            HubTab.CALENDAR -> {
                if (selectedCalendarHref.isNotEmpty()) {
                    c.getEvents(selectedCalendarHref,
                        onSuccess = { evList ->
                            events = evList.sortedBy { it.startTime ?: "" }
                            isLoading = false
                        },
                        onFailure = { err ->
                            errorMessage = "Erreur calendrier: ${err.message}"
                            isLoading = false
                        }
                    )
                } else {
                    isLoading = false
                }
            }
            HubTab.TASKS -> {
                if (selectedTaskListHref.isNotEmpty()) {
                    c.getTasks(selectedTaskListHref,
                        onSuccess = { tList ->
                            tasks = tList.sortedWith(compareBy({ it.status == "COMPLETED" }, { it.due ?: "" }))
                            isLoading = false
                        },
                        onFailure = { err ->
                            errorMessage = "Erreur tâches: ${err.message}"
                            isLoading = false
                        }
                    )
                } else {
                    isLoading = false
                }
            }
            HubTab.NOTES -> {
                c.getNotes(
                    onSuccess = { nList ->
                        notes = nList.sortedWith(compareByDescending<NextcloudNote> { it.favorite }.thenByDescending { it.modified })
                        isLoading = false
                    },
                    onFailure = { err ->
                        errorMessage = "Erreur notes: ${err.message}"
                        isLoading = false
                    }
                )
            }
            HubTab.FILES -> {
                if (currentFolderPath.isEmpty() && username.isNotEmpty()) {
                    currentFolderPath = "/remote.php/dav/files/$username/"
                }
                if (currentFolderPath.isNotEmpty()) {
                    c.getFiles(currentFolderPath,
                        onSuccess = { fList ->
                            files = fList.sortedWith(compareByDescending<NextcloudFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                            isLoading = false
                        },
                        onFailure = { err ->
                            errorMessage = "Erreur fichiers: ${err.message}"
                            isLoading = false
                        }
                    )
                } else {
                    isLoading = false
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                
                val fileName = getFileNameFromUri(context, uri) ?: "upload_${System.currentTimeMillis()}"
                
                if (bytes != null) {
                    isLoading = true
                    client?.uploadFile(currentFolderPath, fileName, bytes,
                        onSuccess = {
                            refreshData()
                        },
                        onFailure = { err ->
                            errorMessage = "Import échoué: ${err.message}"
                            isLoading = false
                        }
                    )
                }
            } catch (e: Exception) {
                errorMessage = "Erreur lecture fichier: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = if (!isConnected) {
                            "Nextcloud Extended"
                        } else {
                            when (currentTab) {
                                HubTab.CALENDAR -> "Nextcloud Calendrier"
                                HubTab.TASKS -> "Nextcloud Tâches"
                                HubTab.NOTES -> "Nextcloud Notes"
                                HubTab.FILES -> "Nextcloud Drive"
                            }
                        }, 
                        fontWeight = FontWeight.Bold, 
                        color = Color.White
                    ) 
                },
                actions = {
                    if (isConnected) {
                        IconButton(onClick = { refreshData() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rafraîchir", tint = Color.White)
                        }
                        IconButton(onClick = {
                            // Deconnexion and clear SharedPreferences
                            sharedPrefs.edit().clear().apply()
                            isConnected = false
                            client = null
                            calendars = emptyList()
                            events = emptyList()
                            tasks = emptyList()
                            notes = emptyList()
                            Toast.makeText(context, "Déconnecté avec succès", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ExitToApp, contentDescription = "Se déconnecter", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0082C9))
            )
        },
        bottomBar = {
            if (isConnected) {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(
                        selected = currentTab == HubTab.CALENDAR,
                        onClick = { 
                            currentTab = HubTab.CALENDAR
                            refreshData()
                        },
                        label = { Text("Agenda") },
                        icon = { Icon(Icons.Default.DateRange, contentDescription = "Calendrier") }
                    )
                    NavigationBarItem(
                        selected = currentTab == HubTab.TASKS,
                        onClick = { 
                            currentTab = HubTab.TASKS
                            refreshData()
                        },
                        label = { Text("Tâches") },
                        icon = { Icon(Icons.Default.List, contentDescription = "Tâches") }
                    )
                    NavigationBarItem(
                        selected = currentTab == HubTab.NOTES,
                        onClick = { 
                            currentTab = HubTab.NOTES
                            refreshData()
                        },
                        label = { Text("Notes") },
                        icon = { Icon(Icons.Default.Edit, contentDescription = "Notes") }
                    )
                    NavigationBarItem(
                        selected = currentTab == HubTab.FILES,
                        onClick = { 
                            currentTab = HubTab.FILES
                            refreshData()
                        },
                        label = { Text("Drive") },
                        icon = { Icon(Icons.Default.Folder, contentDescription = "Drive") }
                    )
                }
            }
        },
        floatingActionButton = {
            if (isConnected) {
                when (currentTab) {
                    HubTab.TASKS -> {
                        FloatingActionButton(
                            onClick = { showAddTaskDialog = true },
                            containerColor = Color(0xFF0082C9),
                            contentColor = Color.White
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Ajouter tâche")
                        }
                    }
                    HubTab.NOTES -> {
                        FloatingActionButton(
                            onClick = { showAddNoteDialog = true },
                            containerColor = Color(0xFF0082C9),
                            contentColor = Color.White
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Créer une note")
                        }
                    }
                    HubTab.FILES -> {
                        FloatingActionButton(
                            onClick = { showAddFilesOptionDialog = true },
                            containerColor = Color(0xFF0082C9),
                            contentColor = Color.White
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Ajouter élément")
                        }
                    }
                    HubTab.CALENDAR -> {
                        FloatingActionButton(
                            onClick = { showAddEventDialog = true },
                            containerColor = Color(0xFF0082C9),
                            contentColor = Color.White
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Ajouter événement")
                        }
                    }
                    else -> {}
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF4F6F9))
        ) {
            if (!isConnected) {
                // Connection Screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Nextcloud Extended", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0082C9), modifier = Modifier.padding(bottom = 8.dp))
                    Text("Calendrier, Tâches & Notes", fontSize = 16.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 32.dp))
                    
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("URL du serveur") },
                        placeholder = { Text("https://votre-nextcloud.com") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Identifiant") },
                        placeholder = { Text("votre-identifiant") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Mot de passe") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                    )
                    
                    Button(
                        onClick = {
                            if (serverUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
                                errorMessage = "Veuillez remplir tous les champs"
                                return@Button
                            }
                            isLoading = true
                            errorMessage = null
                            val c = CalDavClient(serverUrl, username, password)
                            client = c
                            c.getCalendars(
                                onSuccess = { calList ->
                                    // Save credentials to SharedPreferences
                                    sharedPrefs.edit()
                                        .putString("server_url", serverUrl)
                                        .putString("username", username)
                                        .putString("password", password)
                                        .apply()

                                    calendars = calList
                                    isConnected = true
                                    
                                    if (calList.isNotEmpty()) {
                                        selectedCalendarHref = calList[0].first
                                        selectedCalendarName = calList[0].second
                                        
                                        val todoListObj = calList.find { it.second.lowercase().contains("todo") || it.first.lowercase().contains("todo") }
                                            ?: calList[0]
                                        selectedTaskListHref = todoListObj.first
                                        selectedTaskListName = todoListObj.second
                                    }
                                    
                                    refreshData()
                                },
                                onFailure = { err ->
                                    errorMessage = "Connexion échouée: ${err.message}"
                                    isLoading = false
                                }
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0082C9)),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Text("Se connecter", fontSize = 16.sp, color = Color.White)
                    }
                }
            } else {
                // Main screens depending on tab
                when (currentTab) {
                    HubTab.CALENDAR -> {
                        CalendarMultiViewScreen(
                            calendars = calendars,
                            selectedName = selectedCalendarName,
                            events = events,
                            calendarViewMode = calendarViewMode,
                            selectedDate = selectedDate,
                            onViewModeChange = { calendarViewMode = it },
                            onDateChange = { selectedDate = it },
                            onCalendarSelected = { href, name ->
                                selectedCalendarHref = href
                                selectedCalendarName = name
                                isLoading = true
                                client?.getEvents(href,
                                    onSuccess = { evList ->
                                        events = evList.sortedBy { it.startTime ?: "" }
                                        isLoading = false
                                    },
                                    onFailure = { err ->
                                        errorMessage = err.message
                                        isLoading = false
                                    }
                                )
                            }
                        )
                    }
                    HubTab.TASKS -> {
                        TasksScreen(
                            taskLists = calendars,
                            selectedName = selectedTaskListName,
                            tasks = tasks,
                            onTaskListSelected = { href, name ->
                                selectedTaskListHref = href
                                selectedTaskListName = name
                                isLoading = true
                                client?.getTasks(href,
                                    onSuccess = { tList ->
                                        tasks = tList.sortedWith(compareBy({ it.status == "COMPLETED" }, { it.due ?: "" }))
                                        isLoading = false
                                    },
                                    onFailure = { err ->
                                        errorMessage = err.message
                                        isLoading = false
                                    }
                                )
                            },
                            onToggleStatus = { task ->
                                val updatedStatus = if (task.status == "COMPLETED") "NEEDS-ACTION" else "COMPLETED"
                                val updatedTask = task.copy(status = updatedStatus)
                                isLoading = true
                                client?.saveTask(updatedTask,
                                    onSuccess = {
                                        refreshData()
                                    },
                                    onFailure = { err ->
                                        errorMessage = "Échec mise à jour tâche: ${err.message}"
                                        isLoading = false
                                    }
                                )
                            },
                            onDeleteTask = { task ->
                                isLoading = true
                                client?.deleteTask(task,
                                    onSuccess = {
                                        refreshData()
                                    },
                                    onFailure = { err ->
                                        errorMessage = "Échec suppression tâche: ${err.message}"
                                        isLoading = false
                                    }
                                )
                            },
                            onCreateList = { showCreateTaskListDialog = true },
                            onRenameList = { showRenameTaskListDialog = true },
                            onDeleteList = { showDeleteTaskListDialog = true }
                        )
                    }
                    HubTab.NOTES -> {
                        NotesScreen(
                            notes = notes,
                            onNoteSelected = { viewingNote = it },
                            onToggleFavorite = { note ->
                                isLoading = true
                                client?.updateNote(note.id, note.title, note.content, note.category, !note.favorite,
                                    onSuccess = {
                                        refreshData()
                                    },
                                    onFailure = { err ->
                                        errorMessage = "Échec favori note: ${err.message}"
                                        isLoading = false
                                    }
                                )
                            }
                        )
                    }
                    HubTab.FILES -> {
                        FilesScreen(
                            currentFolderPath = currentFolderPath,
                            files = files,
                            onFileClick = { file ->
                                if (file.isDirectory) {
                                    currentFolderPath = file.path
                                    refreshData()
                                } else {
                                    Toast.makeText(context, "Téléchargement de ${file.name}...", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onBackClick = {
                                val decoded = java.net.URLDecoder.decode(currentFolderPath, "UTF-8")
                                val parts = decoded.trimEnd('/').split('/')
                                if (parts.size > 5) {
                                    val parentParts = parts.take(parts.size - 1)
                                    currentFolderPath = parentParts.joinToString("/") + "/"
                                    refreshData()
                                }
                            },
                            onDeleteFile = { file ->
                                isLoading = true
                                client?.deleteFile(file.path,
                                    onSuccess = {
                                        refreshData()
                                    },
                                    onFailure = { err ->
                                        errorMessage = "Échec suppression: ${err.message}"
                                        isLoading = false
                                    }
                                )
                            },
                            onRenameFile = { file ->
                                fileToRename = file
                                showRenameFileDialog = true
                            }
                        )
                    }
                }
            }
            
            // Loading Overlay
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x60FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF0082C9))
                }
            }
        }
    }

    // Add Folder Dialog
    if (showAddFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddFolderDialog = false },
            title = { Text("Nouveau dossier", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Nom du dossier") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (folderName.isNotEmpty()) {
                            showAddFolderDialog = false
                            isLoading = true
                            client?.createFolder(currentFolderPath, folderName,
                                onSuccess = {
                                    refreshData()
                                },
                                onFailure = { err ->
                                    errorMessage = "Erreur création dossier: ${err.message}"
                                    isLoading = false
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0082C9))
                ) {
                    Text("Créer", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddFolderDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    // Add Files / Options Dialog
    if (showAddFilesOptionDialog) {
        AlertDialog(
            onDismissRequest = { showAddFilesOptionDialog = false },
            title = { Text("Ajouter au Drive", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            showAddFilesOptionDialog = false
                            showAddFolderDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0082C9)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Créer un dossier", color = Color.White)
                    }
                    Button(
                        onClick = {
                            showAddFilesOptionDialog = false
                            filePickerLauncher.launch("*/*")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0082C9)),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Publish, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Importer un fichier", color = Color.White)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddFilesOptionDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    // Task Creation Dialog
    if (showAddTaskDialog) {
        var taskTitle by remember { mutableStateOf("") }
        var taskDesc by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { showAddTaskDialog = false },
            title = { Text("Nouvelle tâche", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = taskTitle,
                        onValueChange = { taskTitle = it },
                        label = { Text("Titre de la tâche") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = taskDesc,
                        onValueChange = { taskDesc = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (taskTitle.isNotEmpty()) {
                            showAddTaskDialog = false
                            isLoading = true
                            val newTask = NextcloudTask(
                                uid = UUID.randomUUID().toString(),
                                summary = taskTitle,
                                description = if (taskDesc.isEmpty()) null else taskDesc,
                                status = "NEEDS-ACTION",
                                due = null,
                                calendarHref = selectedTaskListHref
                            )
                            client?.saveTask(newTask,
                                onSuccess = {
                                    refreshData()
                                },
                                onFailure = { err ->
                                    errorMessage = "Création tâche échouée: ${err.message}"
                                    isLoading = false
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0082C9))
                ) {
                    Text("Ajouter", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTaskDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    // Event Creation Dialog
    if (showAddEventDialog) {
        var eventTitle by remember { mutableStateOf("") }
        var eventDesc by remember { mutableStateOf("") }
        var eventLoc by remember { mutableStateOf("") }
        var eventStart by remember { mutableStateOf(selectedDate.toString() + " 10:00") }
        var eventEnd by remember { mutableStateOf(selectedDate.toString() + " 11:00") }
        
        AlertDialog(
            onDismissRequest = { showAddEventDialog = false },
            title = { Text("Nouvel événement", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = eventTitle,
                        onValueChange = { eventTitle = it },
                        label = { Text("Titre de l'événement") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = eventDesc,
                        onValueChange = { eventDesc = it },
                        label = { Text("Description") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = eventLoc,
                        onValueChange = { eventLoc = it },
                        label = { Text("Lieu") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = eventStart,
                        onValueChange = { eventStart = it },
                        label = { Text("Début (AAAA-MM-JJ HH:MM)") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = eventEnd,
                        onValueChange = { eventEnd = it },
                        label = { Text("Fin (AAAA-MM-JJ HH:MM)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (eventTitle.isNotEmpty() && selectedCalendarHref.isNotEmpty()) {
                            showAddEventDialog = false
                            isLoading = true
                            val newEvent = CalendarEvent(
                                id = UUID.randomUUID().toString(),
                                summary = eventTitle,
                                description = if (eventDesc.isEmpty()) null else eventDesc,
                                startTime = eventStart,
                                endTime = eventEnd,
                                location = if (eventLoc.isEmpty()) null else eventLoc
                            )
                            client?.saveEvent(selectedCalendarHref, newEvent,
                                onSuccess = {
                                    refreshData()
                                },
                                onFailure = { err ->
                                    errorMessage = "Création d'événement échouée: ${err.message}"
                                    isLoading = false
                                }
                            )
                        } else if (selectedCalendarHref.isEmpty()) {
                            errorMessage = "Veuillez sélectionner un calendrier d'abord"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0082C9))
                ) {
                    Text("Ajouter", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddEventDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    // Task List Creation Dialog
    if (showCreateTaskListDialog) {
        var listName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateTaskListDialog = false },
            title = { Text("Nouvelle liste de tâches", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = listName,
                    onValueChange = { listName = it },
                    label = { Text("Nom de la liste") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (listName.isNotEmpty()) {
                            showCreateTaskListDialog = false
                            isLoading = true
                            client?.createTaskList(listName,
                                onSuccess = {
                                    client?.getCalendars(
                                        onSuccess = { calList ->
                                            calendars = calList
                                            isLoading = false
                                        },
                                        onFailure = { err ->
                                            errorMessage = err.message
                                            isLoading = false
                                        }
                                    )
                                },
                                onFailure = { err ->
                                    errorMessage = "Création de liste échouée: ${err.message}"
                                    isLoading = false
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0082C9))
                ) {
                    Text("Créer", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateTaskListDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    // Task List Rename Dialog
    if (showRenameTaskListDialog) {
        var newListName by remember { mutableStateOf(selectedTaskListName) }
        AlertDialog(
            onDismissRequest = { showRenameTaskListDialog = false },
            title = { Text("Renommer la liste", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    label = { Text("Nouveau nom") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newListName.isNotEmpty() && selectedTaskListHref.isNotEmpty()) {
                            showRenameTaskListDialog = false
                            isLoading = true
                            client?.renameTaskList(selectedTaskListHref, newListName,
                                onSuccess = {
                                    selectedTaskListName = newListName
                                    client?.getCalendars(
                                        onSuccess = { calList ->
                                            calendars = calList
                                            isLoading = false
                                        },
                                        onFailure = { err ->
                                            errorMessage = err.message
                                            isLoading = false
                                        }
                                    )
                                },
                                onFailure = { err ->
                                    errorMessage = "Renommer liste échoué: ${err.message}"
                                    isLoading = false
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0082C9))
                ) {
                    Text("Renommer", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameTaskListDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    // Task List Delete Dialog
    if (showDeleteTaskListDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteTaskListDialog = false },
            title = { Text("Supprimer la liste ?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Voulez-vous vraiment supprimer la liste '$selectedTaskListName' et toutes ses tâches ? Cette action est irréversible.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedTaskListHref.isNotEmpty()) {
                            showDeleteTaskListDialog = false
                            isLoading = true
                            client?.deleteTaskList(selectedTaskListHref,
                                onSuccess = {
                                    selectedTaskListHref = ""
                                    selectedTaskListName = ""
                                    tasks = emptyList()
                                    client?.getCalendars(
                                        onSuccess = { calList ->
                                            calendars = calList
                                            isLoading = false
                                        },
                                        onFailure = { err ->
                                            errorMessage = err.message
                                            isLoading = false
                                        }
                                    )
                                },
                                onFailure = { err ->
                                    errorMessage = "Suppression de liste échouée: ${err.message}"
                                    isLoading = false
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Supprimer", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteTaskListDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    // File Rename Dialog
    if (showRenameFileDialog && fileToRename != null) {
        var newFileName by remember { mutableStateOf(fileToRename!!.name) }
        AlertDialog(
            onDismissRequest = { showRenameFileDialog = false },
            title = { Text("Renommer", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text("Nouveau nom") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFileName.isNotEmpty() && newFileName != fileToRename!!.name) {
                            showRenameFileDialog = false
                            isLoading = true
                            client?.renameFile(fileToRename!!.path, newFileName,
                                onSuccess = {
                                    refreshData()
                                },
                                onFailure = { err ->
                                    errorMessage = "Renommage échoué: ${err.message}"
                                    isLoading = false
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0082C9))
                ) {
                    Text("Renommer", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameFileDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    // Note Creation Dialog
    if (showAddNoteDialog) {
        var noteTitle by remember { mutableStateOf("") }
        var noteContent by remember { mutableStateOf("") }
        var noteCategory by remember { mutableStateOf("Général") }

        AlertDialog(
            onDismissRequest = { showAddNoteDialog = false },
            title = { Text("Nouvelle note", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = noteTitle,
                        onValueChange = { noteTitle = it },
                        label = { Text("Titre") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = noteCategory,
                        onValueChange = { noteCategory = it },
                        label = { Text("Catégorie") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = noteContent,
                        onValueChange = { noteContent = it },
                        label = { Text("Contenu de la note") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (noteTitle.isNotEmpty()) {
                            showAddNoteDialog = false
                            isLoading = true
                            client?.createNote(noteTitle, noteContent, noteCategory,
                                onSuccess = {
                                    refreshData()
                                },
                                onFailure = { err ->
                                    errorMessage = "Création note échouée: ${err.message}"
                                    isLoading = false
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0082C9))
                ) {
                    Text("Créer", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNoteDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    // View Note Dialog
    viewingNote?.let { note ->
        Dialog(onDismissRequest = { viewingNote = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (note.category.isNotEmpty()) note.category.uppercase() else "NOTE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0082C9)
                        )
                        IconButton(onClick = {
                            viewingNote = null
                            editingNote = note
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Modifier", tint = Color.Gray)
                        }
                    }
                    
                    Text(
                        text = note.title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF333333),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    
                    Divider(color = Color(0xFFEEEEEE), modifier = Modifier.padding(bottom = 16.dp))
                    
                    Text(
                        text = note.content,
                        fontSize = 16.sp,
                        color = Color(0xFF444444),
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = {
                                viewingNote = null
                                isLoading = true
                                client?.deleteNote(note.id,
                                    onSuccess = {
                                        refreshData()
                                    },
                                    onFailure = { err ->
                                        errorMessage = "Suppression note échouée: ${err.message}"
                                        isLoading = false
                                    }
                                )
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                        ) {
                            Text("Supprimer")
                        }
                        
                        Button(
                            onClick = { viewingNote = null },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0082C9))
                        ) {
                            Text("Fermer", color = Color.White)
                        }
                    }
                }
            }
        }
    }

    // Edit Note Dialog
    editingNote?.let { note ->
        var noteTitle by remember { mutableStateOf(note.title) }
        var noteContent by remember { mutableStateOf(note.content) }
        var noteCategory by remember { mutableStateOf(note.category) }

        AlertDialog(
            onDismissRequest = { editingNote = null },
            title = { Text("Modifier la note", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = noteTitle,
                        onValueChange = { noteTitle = it },
                        label = { Text("Titre") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = noteCategory,
                        onValueChange = { noteCategory = it },
                        label = { Text("Catégorie") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = noteContent,
                        onValueChange = { noteContent = it },
                        label = { Text("Contenu de la note") },
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (noteTitle.isNotEmpty()) {
                            editingNote = null
                            isLoading = true
                            client?.updateNote(note.id, noteTitle, noteContent, noteCategory, note.favorite,
                                onSuccess = {
                                    refreshData()
                                },
                                onFailure = { err ->
                                    errorMessage = "Mise à jour note échouée: ${err.message}"
                                    isLoading = false
                                }
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0082C9))
                ) {
                    Text("Enregistrer", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingNote = null }) {
                    Text("Annuler")
                }
            }
        )
    }

    // Error Toast
    errorMessage?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            errorMessage = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarMultiViewScreen(
    calendars: List<Pair<String, String>>,
    selectedName: String,
    events: List<CalendarEvent>,
    calendarViewMode: CalendarViewMode,
    selectedDate: LocalDate,
    onViewModeChange: (CalendarViewMode) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onCalendarSelected: (String, String) -> Unit
) {
    var dropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Dropdown Calendar Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dropdownExpanded = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedName.ifEmpty { "Sélectionner agenda..." },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0082C9)
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Choisir")
                    }
                }
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    calendars.forEach { cal ->
                        DropdownMenuItem(
                            text = { Text(cal.second) },
                            onClick = {
                                dropdownExpanded = false
                                onCalendarSelected(cal.first, cal.second)
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // View Mode Selector Segmented Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val modes = listOf(
                CalendarViewMode.DAY to "Jour",
                CalendarViewMode.WEEK to "Sem.",
                CalendarViewMode.MONTH to "Mois",
                CalendarViewMode.YEAR to "Année"
            )
            modes.forEach { (mode, label) ->
                val isSelected = calendarViewMode == mode
                ElevatedFilterChip(
                    selected = isSelected,
                    onClick = { onViewModeChange(mode) },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.elevatedFilterChipColors(
                        selectedContainerColor = Color(0xFF0082C9),
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Dynamic multi-view depending on view mode
        when (calendarViewMode) {
            CalendarViewMode.DAY -> {
                CalendarDayView(
                    selectedDate = selectedDate,
                    events = events,
                    onDateChange = onDateChange
                )
            }
            CalendarViewMode.WEEK -> {
                CalendarWeekView(
                    selectedDate = selectedDate,
                    events = events,
                    onDateChange = onDateChange
                )
            }
            CalendarViewMode.MONTH -> {
                CalendarMonthView(
                    selectedDate = selectedDate,
                    events = events,
                    onDateChange = onDateChange
                )
            }
            CalendarViewMode.YEAR -> {
                CalendarYearView(
                    selectedDate = selectedDate,
                    onDateChange = { 
                        onDateChange(it)
                        onViewModeChange(CalendarViewMode.MONTH) // Navigate to month when clicked
                    },
                    onYearChange = { onDateChange(it) }
                )
            }
        }
    }
}

@Composable
fun CalendarDayView(
    selectedDate: LocalDate,
    events: List<CalendarEvent>,
    onDateChange: (LocalDate) -> Unit
) {
    val formatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
    val dateStr = selectedDate.toString() // "yyyy-MM-dd"
    val dayEvents = events.filter { it.startTime?.startsWith(dateStr) == true }

    Column(modifier = Modifier.fillMaxSize()) {
        // Day Navigation Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onDateChange(selectedDate.minusDays(1)) }) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Précédent")
            }
            Text(
                text = selectedDate.format(formatter).replaceFirstChar { it.uppercase() },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF333333)
            )
            IconButton(onClick = { onDateChange(selectedDate.plusDays(1)) }) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Suivant")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (dayEvents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("Aucun événement pour ce jour.", color = Color.Gray, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(dayEvents) { event ->
                    EventItem(event)
                }
            }
        }
    }
}

@Composable
fun CalendarWeekView(
    selectedDate: LocalDate,
    events: List<CalendarEvent>,
    onDateChange: (LocalDate) -> Unit
) {
    val startOfWeek = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val endOfWeek = startOfWeek.plusDays(6)
    
    val weekFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
    val dayNameFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.FRENCH)

    Column(modifier = Modifier.fillMaxSize()) {
        // Week Navigation Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onDateChange(selectedDate.minusWeeks(1)) }) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Précédent")
            }
            Text(
                text = "Semaine du ${startOfWeek.format(weekFormatter)} au ${endOfWeek.format(weekFormatter)}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF333333)
            )
            IconButton(onClick = { onDateChange(selectedDate.plusWeeks(1)) }) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Suivant")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Render days of the current week (scrollable column)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            for (i in 0..6) {
                val currentDay = startOfWeek.plusDays(i.toLong())
                val currentDayStr = currentDay.toString()
                val dayEvents = events.filter { it.startTime?.startsWith(currentDayStr) == true }
                val isToday = currentDay == LocalDate.now()

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isToday) Color(0xFFE3F2FD) else Color.White
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = currentDay.format(dayNameFormatter).replaceFirstChar { it.uppercase() },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isToday) Color(0xFF0082C9) else Color(0xFF333333)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        if (dayEvents.isEmpty()) {
                            Text("Aucun événement", fontSize = 13.sp, color = Color.Gray)
                        } else {
                            dayEvents.forEach { ev ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(Color(0xFF0082C9), RoundedCornerShape(3.dp))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${ev.startTime?.substring(11) ?: ""} - ${ev.summary}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF444444)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarMonthView(
    selectedDate: LocalDate,
    events: List<CalendarEvent>,
    onDateChange: (LocalDate) -> Unit
) {
    val yearMonth = YearMonth.of(selectedDate.year, selectedDate.month)
    val firstOfMonth = yearMonth.atDay(1)
    val daysInMonth = yearMonth.lengthOfMonth()
    
    // Day of week for 1st day of month (1 = Mon, 7 = Sun)
    val firstDayOfWeek = firstOfMonth.dayOfWeek.value
    val paddingDays = firstDayOfWeek - 1

    val headerFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH)
    val dayOfMonthEvents = events.filter { it.startTime?.startsWith(selectedDate.toString()) == true }

    Column(modifier = Modifier.fillMaxSize()) {
        // Month Navigation Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onDateChange(selectedDate.minusMonths(1)) }) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Précédent")
            }
            Text(
                text = selectedDate.format(headerFormatter).replaceFirstChar { it.uppercase() },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF333333)
            )
            IconButton(onClick = { onDateChange(selectedDate.plusMonths(1)) }) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Suivant")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Weekdays Headers Row
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val weekdays = listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")
            weekdays.forEach { day ->
                Text(
                    text = day,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Build days grid
        val daysList = mutableListOf<LocalDate?>()
        for (i in 0 until paddingDays) {
            daysList.add(null)
        }
        for (i in 1..daysInMonth) {
            daysList.add(yearMonth.atDay(i))
        }

        val rows = daysList.chunked(7)

        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (day in row) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1.1f)
                                .padding(2.dp)
                                .background(
                                    color = when {
                                        day == null -> Color.Transparent
                                        day == selectedDate -> Color(0xFF0082C9)
                                        day == LocalDate.now() -> Color(0xFFE3F2FD)
                                        else -> Color.White
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable(enabled = day != null) {
                                    if (day != null) onDateChange(day)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (day != null) {
                                val dayStr = day.toString()
                                val hasEvent = events.any { it.startTime?.startsWith(dayStr) == true }
                                val isSelected = day == selectedDate
                                val isToday = day == LocalDate.now()

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = day.dayOfMonth.toString(),
                                        fontSize = 15.sp,
                                        fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                        color = when {
                                            isSelected -> Color.White
                                            isToday -> Color(0xFF0082C9)
                                            else -> Color(0xFF333333)
                                        }
                                    )
                                    if (hasEvent) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(4.dp)
                                                .background(
                                                    color = if (isSelected) Color.White else Color(0xFF0082C9),
                                                    shape = RoundedCornerShape(2.dp)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // Pad incomplete row to keep alignment
                    if (row.size < 7) {
                        for (k in 0 until (7 - row.size)) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Events for selected day in month
        Text(
            text = "Événements du ${selectedDate.dayOfMonth} ${selectedDate.format(DateTimeFormatter.ofPattern("MMMM", Locale.FRENCH))}:",
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = Color(0xFF333333),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (dayOfMonthEvents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("Aucun événement pour cette journée.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(dayOfMonthEvents) { event ->
                    EventItem(event)
                }
            }
        }
    }
}

@Composable
fun CalendarYearView(
    selectedDate: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    onYearChange: (LocalDate) -> Unit
) {
    val currentYear = selectedDate.year

    Column(modifier = Modifier.fillMaxSize()) {
        // Year Navigation Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onYearChange(selectedDate.withYear(currentYear - 1)) }) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Précédent")
            }
            Text(
                text = "Année $currentYear",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF333333)
            )
            IconButton(onClick = { onYearChange(selectedDate.withYear(currentYear + 1)) }) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Suivant")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 12 Months Cards (scrollable list / column of rows)
        val monthsList = (1..12).chunked(3) // 4 rows of 3 columns

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            monthsList.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { month ->
                        val firstOfMonth = LocalDate.of(currentYear, month, 1)
                        val monthName = firstOfMonth.month.getDisplayName(TextStyle.FULL, Locale.FRENCH)

                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clickable { onDateChange(firstOfMonth) },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = monthName.replaceFirstChar { it.uppercase() },
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0082C9),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Liste de tâches :", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dropdownExpanded = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(selectedName.ifEmpty { "Sélectionnez..." }, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0082C9))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Choisir")
                    }
                }
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    taskLists.forEach { list ->
                        DropdownMenuItem(
                            text = { Text(list.second) },
                            onClick = {
                                dropdownExpanded = false
                                onTaskListSelected(list.first, list.second)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box {
                Card(
                    modifier = Modifier
                        .size(54.dp)
                        .clickable { settingsExpanded = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Paramètres de la liste",
                            tint = Color(0xFF0082C9)
                        )
                    }
                }

                DropdownMenu(
                    expanded = settingsExpanded,
                    onDismissRequest = { settingsExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Créer une liste") },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF0082C9)) },
                        onClick = {
                            settingsExpanded = false
                            onCreateList()
                        }
                    )
                    if (selectedName.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Renommer la liste") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFFEAA000)) },
                            onClick = {
                                settingsExpanded = false
                                onRenameList()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Supprimer la liste") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFD32F2F)) },
                            onClick = {
                                settingsExpanded = false
                                onDeleteList()
                            }
                        )
                    }
                }
            }
        }

        Text("Tâches à faire :", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        
        if (tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune tâche dans cette liste.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(tasks) { task ->
                    TaskItem(
                        task = task,
                        onToggle = { onToggleStatus(task) },
                        onDelete = { onDeleteTask(task) }
                    )
                }
            }
        }
    }
}

@Composable
fun TaskItem(
    task: NextcloudTask,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val isCompleted = task.status == "COMPLETED"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isCompleted,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF0082C9))
            )
            
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 12.dp)
            ) {
                Text(
                    text = task.summary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isCompleted) Color.Gray else Color(0xFF333333),
                    textDecoration = if (isCompleted) TextDecoration.LineThrough else TextDecoration.None
                )
                
                if (!task.description.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = task.description,
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }
                
                if (!task.due.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⏳ Échéance : ${task.due}",
                        fontSize = 12.sp,
                        color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            IconButton(onClick = { onDelete() }) {
                Icon(Icons.Default.Delete, contentDescription = "Supprimer", tint = Color(0xFFD32F2F))
            }
        }
    }
}

@Composable
fun NotesScreen(
    notes: List<NextcloudNote>,
    onNoteSelected: (NextcloudNote) -> Unit,
    onToggleFavorite: (NextcloudNote) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Toutes vos notes :", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
        
        if (notes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune note trouvée.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(notes) { note ->
                    NoteItem(
                        note = note,
                        onClick = { onNoteSelected(note) },
                        onToggleFav = { onToggleFavorite(note) }
                    )
                }
            }
        }
    }
}

@Composable
fun NoteItem(
    note: NextcloudNote,
    onClick: () -> Unit,
    onToggleFav: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (note.category.isNotEmpty()) {
                        Text(
                            text = note.category.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0082C9),
                            modifier = Modifier
                                .background(Color(0xFFE3F2FD), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Text(
                    text = note.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF333333)
                )
                
                if (note.content.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (note.content.length > 80) note.content.take(80) + "..." else note.content,
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }
            }

            IconButton(onClick = { onToggleFav() }) {
                Icon(
                    imageVector = if (note.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favori",
                    tint = if (note.favorite) Color(0xFFE91E63) else Color.Gray
                )
            }
        }
    }
}

@Composable
fun EventItem(event: CalendarEvent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(event.summary, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF333333))
            
            if (!event.startTime.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "📅 " + event.startTime + (if (!event.endTime.isNullOrEmpty()) " ➡️ " + event.endTime else ""),
                    fontSize = 14.sp,
                    color = Color(0xFF666666)
                )
            }
            
            if (!event.location.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("📍 " + event.location, fontSize = 14.sp, color = Color(0xFF666666))
            }

            if (!event.description.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = Color(0xFFEEEEEE))
                Spacer(modifier = Modifier.height(8.dp))
                Text(event.description, fontSize = 14.sp, color = Color(0xFF555555))
            }
        }
    }
}


@Composable
fun FilesScreen(
    currentFolderPath: String,
    files: List<NextcloudFile>,
    onFileClick: (NextcloudFile) -> Unit,
    onBackClick: () -> Unit,
    onDeleteFile: (NextcloudFile) -> Unit,
    onRenameFile: (NextcloudFile) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val decoded = remember(currentFolderPath) {
                try {
                    java.net.URLDecoder.decode(currentFolderPath, "UTF-8")
                } catch (e: Exception) {
                    currentFolderPath
                }
            }
            val slashCount = decoded.count { it == '/' }
            if (slashCount > 5) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Retour")
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            
            val folderDisplayName = remember(decoded) {
                val parts = decoded.trimEnd('/').split('/')
                if (parts.size <= 5) "Fichiers / Drive" else parts.last()
            }
            
            Text(
                text = folderDisplayName,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color(0xFF333333),
                modifier = Modifier.weight(1f)
            )
        }

        if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Dossier vide.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(files) { file ->
                    FileItem(
                        file = file,
                        onClick = { onFileClick(file) },
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
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(if (file.isDirectory) Color(0xFFE3F2FD) else Color(0xFFF5F5F5), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    contentDescription = null,
                    tint = if (file.isDirectory) Color(0xFF0082C9) else Color.Gray,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = file.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF333333)
                )
                
                if (!file.isDirectory) {
                    val sizeKb = remember(file.size) {
                        val kb = file.size / 1024.0
                        if (kb > 1024) {
                            String.format("%.1f Mo", kb / 1024.0)
                        } else {
                            String.format("%.1f Ko", kb)
                        }
                    }
                    Text(
                        text = "$sizeKb - ${file.lastModified.take(20)}",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                } else {
                    Text(
                        text = "Dossier",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
            
            IconButton(onClick = onRename) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Renommer",
                    tint = Color.Gray
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Supprimer",
                    tint = Color.Gray
                )
            }
        }
    }
}

private fun getFileNameFromUri(context: android.content.Context, uri: Uri): String? {
    var name: String? = null
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index != -1) {
                name = it.getString(index)
            }
        }
    }
    if (name == null) {
        name = uri.path
        val cut = name?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            name = name?.substring(cut + 1)
        }
    }
    return name
}
