package xyz.luna.nextcloudextended

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.launch
import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.NextcloudNote
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import xyz.luna.nextcloudextended.ui.screens.*
import xyz.luna.nextcloudextended.ui.theme.NextcloudExtendedTheme
import java.util.UUID

enum class HubTab { CALENDAR, TASKS, NOTES, FILES }
enum class CalendarViewMode { DAY, WEEK, MONTH, YEAR }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NextcloudExtendedTheme {
                NextcloudHubApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextcloudHubApp(vm: NextcloudViewModel = viewModel()) {
    val context = LocalContext.current

    // Credentials (SharedPreferences stays in the composable — platform concern)
    val sharedPrefs = remember {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "secret_shared_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var allowInsecureHttp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        serverUrl = sharedPrefs.getString("server_url", "") ?: ""
        username = sharedPrefs.getString("username", "") ?: ""
        password = sharedPrefs.getString("password", "") ?: ""
        allowInsecureHttp = sharedPrefs.getBoolean("allow_insecure_http", false)
    }

    // UI-only state (dialogs, transient selections)
    var showAddFolderDialog by remember { mutableStateOf(false) }
    var showDriveBottomSheet by remember { mutableStateOf(false) }
    var showAddTaskDialog by remember { mutableStateOf(false) }
    var showAddEventDialog by remember { mutableStateOf(false) }
    var showCreateTaskListDialog by remember { mutableStateOf(false) }
    var showRenameTaskListDialog by remember { mutableStateOf(false) }
    var showDeleteTaskListDialog by remember { mutableStateOf(false) }
    var showRenameFileDialog by remember { mutableStateOf(false) }
    var fileToRename by remember { mutableStateOf<NextcloudFile?>(null) }
    var showAddNoteDialog by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NextcloudNote?>(null) }
    var viewingNote by remember { mutableStateOf<NextcloudNote?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Show snackbar on error
    vm.errorMessage?.let { msg ->
        LaunchedEffect(msg) {
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
            vm.errorMessage = null
        }
    }

    // File picker for upload
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                val fileName = getFileNameFromUri(context, uri) ?: "upload_${System.currentTimeMillis()}"
                if (bytes != null) vm.uploadFile(fileName, bytes)
            } catch (e: Exception) {
                vm.errorMessage = "Erreur lecture fichier: ${e.message}"
            }
        }
    }

    // Init files path when entering FILES tab
    LaunchedEffect(vm.currentTab, vm.isConnected) {
        if (vm.isConnected && vm.currentTab == HubTab.FILES && vm.currentFolderPath.isEmpty() && username.isNotEmpty()) {
            vm.currentFolderPath = "/remote.php/dav/files/$username/"
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (!vm.isConnected) "Nextcloud Extended" else when (vm.currentTab) {
                            HubTab.CALENDAR -> "Agenda"
                            HubTab.TASKS    -> "Tâches"
                            HubTab.NOTES    -> "Notes"
                            HubTab.FILES    -> "Drive"
                        }
                    )
                },
                actions = {
                    if (vm.isConnected) {
                        IconButton(onClick = {
                            vm.disconnect { sharedPrefs.edit().clear().apply() }
                            coroutineScope.launch { snackbarHostState.showSnackbar("Déconnecté") }
                        }) {
                            Icon(Icons.Default.ExitToApp, contentDescription = "Se déconnecter")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    scrolledContainerColor = MaterialTheme.colorScheme.primary
                ),
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            if (vm.isConnected) {
                val uncompletedTasks = vm.tasks.count { it.status != "COMPLETED" }
                NavigationBar {
                    NavigationBarItem(
                        selected = vm.currentTab == HubTab.CALENDAR,
                        onClick = { vm.currentTab = HubTab.CALENDAR; vm.refreshData() },
                        label = { Text("Agenda") },
                        icon = { Icon(Icons.Default.DateRange, contentDescription = "Agenda") }
                    )
                    NavigationBarItem(
                        selected = vm.currentTab == HubTab.TASKS,
                        onClick = { vm.currentTab = HubTab.TASKS; vm.refreshData() },
                        label = { Text("Tâches") },
                        icon = {
                            BadgedBox(badge = { if (uncompletedTasks > 0) Badge { Text("$uncompletedTasks") } }) {
                                Icon(Icons.Default.List, contentDescription = "Tâches")
                            }
                        }
                    )
                    NavigationBarItem(
                        selected = vm.currentTab == HubTab.NOTES,
                        onClick = { vm.currentTab = HubTab.NOTES; vm.refreshData() },
                        label = { Text("Notes") },
                        icon = { Icon(Icons.Default.Edit, contentDescription = "Notes") }
                    )
                    NavigationBarItem(
                        selected = vm.currentTab == HubTab.FILES,
                        onClick = { vm.currentTab = HubTab.FILES; vm.refreshData() },
                        label = { Text("Drive") },
                        icon = { Icon(Icons.Default.Folder, contentDescription = "Drive") }
                    )
                }
            }
        },
        floatingActionButton = {
            if (vm.isConnected) when (vm.currentTab) {
                HubTab.TASKS    -> FloatingActionButton(onClick = { showAddTaskDialog = true }) { Icon(Icons.Default.Add, "Ajouter tâche") }
                HubTab.NOTES    -> FloatingActionButton(onClick = { showAddNoteDialog = true }) { Icon(Icons.Default.Add, "Créer note") }
                HubTab.FILES    -> FloatingActionButton(onClick = { showDriveBottomSheet = true }) { Icon(Icons.Default.Add, "Ajouter") }
                HubTab.CALENDAR -> FloatingActionButton(onClick = { showAddEventDialog = true }) { Icon(Icons.Default.Add, "Ajouter événement") }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            if (!vm.isConnected) {
                LoginScreen(
                    serverUrl = serverUrl, username = username, password = password,
                    isLoading = vm.isLoading,
                    allowInsecureHttp = allowInsecureHttp,
                    onServerUrlChange = { serverUrl = it },
                    onUsernameChange = { username = it },
                    onPasswordChange = { password = it },
                    onAllowInsecureHttpChange = { allowInsecureHttp = it },
                    onConnect = {
                        if (serverUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
                            vm.errorMessage = "Veuillez remplir tous les champs"; return@LoginScreen
                        }
                        // HTTPS enforced by default — block http:// unless explicitly allowed
                        if (serverUrl.startsWith("http://") && !allowInsecureHttp) {
                            vm.errorMessage = "Connexion HTTP non sécurisée bloquée. Activez l'option dans « Options avancées » si votre serveur est sur un réseau local."
                            return@LoginScreen
                        }
                        vm.connect(serverUrl, username, password) {
                            sharedPrefs.edit()
                                .putString("server_url", serverUrl)
                                .putString("username", username)
                                .putString("password", password)
                                .putBoolean("allow_insecure_http", allowInsecureHttp)
                                .apply()
                        }
                    }
                )
            } else {
                PullToRefreshBox(isRefreshing = vm.isLoading, onRefresh = { vm.refreshData() }, modifier = Modifier.fillMaxSize()) {
                    when (vm.currentTab) {
                        HubTab.CALENDAR -> CalendarMultiViewScreen(
                            calendars = vm.calendars, selectedName = vm.selectedCalendarName,
                            events = vm.events, calendarViewMode = vm.calendarViewMode,
                            selectedDate = vm.selectedDate,
                            onViewModeChange = { vm.calendarViewMode = it },
                            onDateChange = { vm.selectedDate = it },
                            onCalendarSelected = { href, name -> vm.loadCalendarEvents(href, name) }
                        )
                        HubTab.TASKS -> TasksScreen(
                            taskLists = vm.taskLists, selectedName = vm.selectedTaskListName,
                            tasks = vm.tasks,
                            onTaskListSelected = { href, name -> vm.loadTaskList(href, name) },
                            onToggleStatus = { vm.toggleTaskStatus(it) },
                            onDeleteTask = { vm.deleteTask(it) },
                            onCreateList = { showCreateTaskListDialog = true },
                            onRenameList = { showRenameTaskListDialog = true },
                            onDeleteList = { showDeleteTaskListDialog = true }
                        )
                        HubTab.NOTES -> NotesScreen(
                            notes = vm.notes,
                            onNoteSelected = { viewingNote = it },
                            onToggleFavorite = { vm.toggleNoteFavorite(it) }
                        )
                        HubTab.FILES -> FilesScreen(
                            currentFolderPath = vm.currentFolderPath, files = vm.files,
                            onFileClick = { file ->
                                if (file.isDirectory) {
                                    vm.navigateToFolder(file.path)
                                } else {
                                    val fileUrl = vm.client?.buildFileUrl(file.path)
                                    val auth = vm.client?.getAuthorizationHeader()
                                    if (fileUrl != null && auth != null) {
                                        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                        dm.enqueue(DownloadManager.Request(android.net.Uri.parse(fileUrl))
                                            .addRequestHeader("Authorization", auth)
                                            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, file.name)
                                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                            .setTitle(file.name).setDescription("Nextcloud Extended"))
                                        coroutineScope.launch { snackbarHostState.showSnackbar("Téléchargement de ${file.name} démarré") }
                                    }
                                }
                            },
                            onBackClick = { vm.navigateUp() },
                            onDeleteFile = { vm.deleteFile(it.path) },
                            onRenameFile = { fileToRename = it; showRenameFileDialog = true }
                        )
                    }
                }
            }
        }
    }

    // ── Dialogs ────────────────────────────────────────────────────────────────

    if (showDriveBottomSheet) {
        ModalBottomSheet(onDismissRequest = { showDriveBottomSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ajouter au Drive", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                FilledTonalButton(onClick = { showDriveBottomSheet = false; showAddFolderDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Créer un dossier")
                }
                FilledTonalButton(onClick = { showDriveBottomSheet = false; filePickerLauncher.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Publish, null); Spacer(Modifier.width(8.dp)); Text("Importer un fichier")
                }
            }
        }
    }

    if (showAddFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddFolderDialog = false },
            title = { Text("Nouveau dossier") },
            text = { OutlinedTextField(value = folderName, onValueChange = { folderName = it }, label = { Text("Nom du dossier") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { if (folderName.isNotEmpty()) { showAddFolderDialog = false; vm.createFolder(folderName) } }) { Text("Créer") } },
            dismissButton = { TextButton(onClick = { showAddFolderDialog = false }) { Text("Annuler") } }
        )
    }

    if (showAddTaskDialog) {
        var taskTitle by remember { mutableStateOf("") }
        var taskDesc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTaskDialog = false },
            title = { Text("Nouvelle tâche") },
            text = {
                Column {
                    OutlinedTextField(value = taskTitle, onValueChange = { taskTitle = it }, label = { Text("Titre") }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    OutlinedTextField(value = taskDesc, onValueChange = { taskDesc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button(onClick = { if (taskTitle.isNotEmpty()) { showAddTaskDialog = false; vm.createTask(UUID.randomUUID().toString(), taskTitle, taskDesc) } }) { Text("Ajouter") } },
            dismissButton = { TextButton(onClick = { showAddTaskDialog = false }) { Text("Annuler") } }
        )
    }

    if (showAddEventDialog) {
        var eventTitle by remember { mutableStateOf("") }
        var eventDesc by remember { mutableStateOf("") }
        var eventLoc by remember { mutableStateOf("") }
        var eventStart by remember { mutableStateOf(vm.selectedDate.toString() + " 10:00") }
        var eventEnd by remember { mutableStateOf(vm.selectedDate.toString() + " 11:00") }
        AlertDialog(
            onDismissRequest = { showAddEventDialog = false },
            title = { Text("Nouvel événement") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = eventTitle, onValueChange = { eventTitle = it }, label = { Text("Titre") }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    OutlinedTextField(value = eventDesc, onValueChange = { eventDesc = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    OutlinedTextField(value = eventLoc, onValueChange = { eventLoc = it }, label = { Text("Lieu") }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    OutlinedTextField(value = eventStart, onValueChange = { eventStart = it }, label = { Text("Début (AAAA-MM-JJ HH:MM)") }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    OutlinedTextField(value = eventEnd, onValueChange = { eventEnd = it }, label = { Text("Fin (AAAA-MM-JJ HH:MM)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (eventTitle.isNotEmpty() && vm.selectedCalendarHref.isNotEmpty()) {
                        showAddEventDialog = false
                        vm.createEvent(CalendarEvent(UUID.randomUUID().toString(), eventTitle, eventDesc.ifEmpty { null }, eventStart, eventEnd, eventLoc.ifEmpty { null }))
                    } else if (vm.selectedCalendarHref.isEmpty()) {
                        vm.errorMessage = "Veuillez sélectionner un calendrier d'abord"
                    }
                }) { Text("Ajouter") }
            },
            dismissButton = { TextButton(onClick = { showAddEventDialog = false }) { Text("Annuler") } }
        )
    }

    if (showCreateTaskListDialog) {
        var listName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateTaskListDialog = false },
            title = { Text("Nouvelle liste de tâches") },
            text = { OutlinedTextField(value = listName, onValueChange = { listName = it }, label = { Text("Nom de la liste") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { if (listName.isNotEmpty()) { showCreateTaskListDialog = false; vm.createTaskList(listName) } }) { Text("Créer") } },
            dismissButton = { TextButton(onClick = { showCreateTaskListDialog = false }) { Text("Annuler") } }
        )
    }

    if (showRenameTaskListDialog) {
        var newListName by remember { mutableStateOf(vm.selectedTaskListName) }
        AlertDialog(
            onDismissRequest = { showRenameTaskListDialog = false },
            title = { Text("Renommer la liste") },
            text = { OutlinedTextField(value = newListName, onValueChange = { newListName = it }, label = { Text("Nouveau nom") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { if (newListName.isNotEmpty()) { showRenameTaskListDialog = false; vm.renameTaskList(newListName) } }) { Text("Renommer") } },
            dismissButton = { TextButton(onClick = { showRenameTaskListDialog = false }) { Text("Annuler") } }
        )
    }

    if (showDeleteTaskListDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteTaskListDialog = false },
            title = { Text("Supprimer la liste ?") },
            text = { Text("Voulez-vous vraiment supprimer '${vm.selectedTaskListName}' et toutes ses tâches ? Cette action est irréversible.") },
            confirmButton = { Button(onClick = { showDeleteTaskListDialog = false; vm.deleteTaskList() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { showDeleteTaskListDialog = false }) { Text("Annuler") } }
        )
    }

    if (showRenameFileDialog && fileToRename != null) {
        var newFileName by remember { mutableStateOf(fileToRename!!.name) }
        AlertDialog(
            onDismissRequest = { showRenameFileDialog = false },
            title = { Text("Renommer") },
            text = { OutlinedTextField(value = newFileName, onValueChange = { newFileName = it }, label = { Text("Nouveau nom") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { if (newFileName.isNotEmpty() && newFileName != fileToRename!!.name) { showRenameFileDialog = false; vm.renameFile(fileToRename!!.path, newFileName) } }) { Text("Renommer") } },
            dismissButton = { TextButton(onClick = { showRenameFileDialog = false }) { Text("Annuler") } }
        )
    }

    if (showAddNoteDialog) {
        var noteTitle by remember { mutableStateOf("") }
        var noteContent by remember { mutableStateOf("") }
        var noteCategory by remember { mutableStateOf("Général") }
        AlertDialog(
            onDismissRequest = { showAddNoteDialog = false },
            title = { Text("Nouvelle note") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = noteTitle, onValueChange = { noteTitle = it }, label = { Text("Titre") }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    OutlinedTextField(value = noteCategory, onValueChange = { noteCategory = it }, label = { Text("Catégorie") }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    OutlinedTextField(value = noteContent, onValueChange = { noteContent = it }, label = { Text("Contenu") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button(onClick = { if (noteTitle.isNotEmpty()) { showAddNoteDialog = false; vm.createNote(noteTitle, noteContent, noteCategory) } }) { Text("Créer") } },
            dismissButton = { TextButton(onClick = { showAddNoteDialog = false }) { Text("Annuler") } }
        )
    }

    viewingNote?.let { note ->
        Dialog(onDismissRequest = { viewingNote = null }) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (note.category.isNotEmpty()) note.category.uppercase() else "NOTE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { viewingNote = null; editingNote = note }) {
                            Icon(Icons.Default.Edit, contentDescription = "Modifier", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(note.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                    HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))
                    Text(note.content, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { viewingNote = null; vm.deleteNote(note.id) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Supprimer") }
                        Button(onClick = { viewingNote = null }) { Text("Fermer") }
                    }
                }
            }
        }
    }

    editingNote?.let { note ->
        var noteTitle by remember { mutableStateOf(note.title) }
        var noteContent by remember { mutableStateOf(note.content) }
        var noteCategory by remember { mutableStateOf(note.category) }
        AlertDialog(
            onDismissRequest = { editingNote = null },
            title = { Text("Modifier la note") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = noteTitle, onValueChange = { noteTitle = it }, label = { Text("Titre") }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    OutlinedTextField(value = noteCategory, onValueChange = { noteCategory = it }, label = { Text("Catégorie") }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
                    OutlinedTextField(value = noteContent, onValueChange = { noteContent = it }, label = { Text("Contenu") }, minLines = 5, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button(onClick = { if (noteTitle.isNotEmpty()) { editingNote = null; vm.updateNote(note, noteTitle, noteContent, noteCategory) } }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = { editingNote = null }) { Text("Annuler") } }
        )
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index != -1) name = it.getString(index)
        }
    }
    if (name == null) {
        name = uri.path
        val cut = name?.lastIndexOf('/') ?: -1
        if (cut != -1) name = name?.substring(cut + 1)
    }
    return name
}
