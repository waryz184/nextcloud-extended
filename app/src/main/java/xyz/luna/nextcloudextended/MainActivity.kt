package xyz.luna.nextcloudextended

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.launch
import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.NextcloudContact
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import xyz.luna.nextcloudextended.data.model.NextcloudNote
import xyz.luna.nextcloudextended.data.model.NextcloudTask
import xyz.luna.nextcloudextended.ui.screens.*
import xyz.luna.nextcloudextended.ui.theme.NextcloudExtendedTheme
import java.io.File
import java.util.Locale
import java.util.UUID

enum class HubTab { CALENDAR, TASKS, NOTES, CONTACTS, FILES }
enum class CalendarViewMode { DAY, WEEK, MONTH, YEAR }
enum class OfficeViewerType { POI, ONLINE }

val DEFAULT_PINNED_TABS = listOf(HubTab.CALENDAR, HubTab.NOTES, HubTab.FILES)

fun HubTab.icon(): ImageVector = when (this) {
    HubTab.CALENDAR -> Icons.Default.DateRange
    HubTab.TASKS -> Icons.Default.List
    HubTab.NOTES -> Icons.Default.Edit
    HubTab.CONTACTS -> Icons.Default.Person
    HubTab.FILES -> Icons.Default.Folder
}

fun HubTab.label(s: Strings): String = when (this) {
    HubTab.CALENDAR -> s.tabCalendar
    HubTab.TASKS -> s.tabTasks
    HubTab.NOTES -> s.tabNotes
    HubTab.CONTACTS -> s.tabContacts
    HubTab.FILES -> s.tabFiles
}

private val officeExtensions = setOf("xlsx", "xls", "docx", "pptx", "csv")
private data class OfficeViewData(val fileName: String, val bytes: ByteArray?, val filePath: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { NextcloudExtendedTheme { NextcloudHubApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextcloudHubApp(vm: NextcloudViewModel = viewModel()) {
    val context = LocalContext.current
    val sharedPrefs = remember {
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "secret_shared_prefs", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var allowInsecureHttp by remember { mutableStateOf(false) }
    // Language: stored preference, else device locale (FR for French devices, EN otherwise)
    var language by remember {
        mutableStateOf(
            sharedPrefs.getString("language", null)?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                ?: if (Locale.getDefault().language == "fr") AppLanguage.FR else AppLanguage.EN
        )
    }
    LaunchedEffect(language) { vm.language = language }
    // True once the saved preferences have been read — prevents a one-frame flash of the
    // login form before the auto-login check below runs.
    var prefsLoaded by remember { mutableStateOf(false) }
    // True once an automatic reconnect with the stored credentials has been kicked off.
    var autoLoginStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        serverUrl = sharedPrefs.getString("server_url", "") ?: ""
        username = sharedPrefs.getString("username", "") ?: ""
        password = sharedPrefs.getString("password", "") ?: ""
        allowInsecureHttp = sharedPrefs.getBoolean("allow_insecure_http", false)
        vm.officeViewerPref = sharedPrefs.getString("office_viewer_pref", null)
            ?.let { runCatching { OfficeViewerType.valueOf(it) }.getOrNull() }
            ?: OfficeViewerType.POI
        vm.pinnedTabs = sharedPrefs.getString("pinned_tabs", null)
            ?.split(",")?.mapNotNull { runCatching { HubTab.valueOf(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() && it.size < HubTab.entries.size }
            ?: DEFAULT_PINNED_TABS
        prefsLoaded = true

        // Auto-login: when credentials were stored by a previous session, reconnect right away
        // instead of showing the pre-filled login form. Guarded by vm.autoLoginAttempted so a
        // rotation doesn't retrigger it, while a fresh app start does.
        val url = normalizeServerUrl(serverUrl)
        val httpAllowed = allowInsecureHttp || !url.startsWith("http://")
        if (!vm.isConnected && !vm.autoLoginAttempted &&
            url.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty() && httpAllowed
        ) {
            vm.autoLoginAttempted = true
            autoLoginStarted = true
            if (url != serverUrl) serverUrl = url
            vm.connect(url, username, password) { /* credentials already stored */ }
        }
    }
    val s = stringsFor(language)

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
    var editingEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    var detailEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    var editingTask by remember { mutableStateOf<NextcloudTask?>(null) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<NextcloudContact?>(null) }
    var viewingContact by remember { mutableStateOf<NextcloudContact?>(null) }
    var pdfToView by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var officeToView by remember { mutableStateOf<OfficeViewData?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    vm.errorMessage?.let { msg ->
        LaunchedEffect(msg) { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short); vm.errorMessage = null }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                val fileName = getFileNameFromUri(context, uri) ?: "upload_${System.currentTimeMillis()}"
                if (bytes != null) vm.uploadFile(fileName, bytes)
            } catch (e: Exception) { vm.errorMessage = s.fileReadError(e.message ?: "") }
        }
    }

    // Enqueues a file for download to the device's Downloads folder via the system
    // DownloadManager (notification shown on completion). Used both when tapping a file that
    // has no in-app viewer and for the explicit "Download" action in the file menu.
    fun enqueueFileDownload(file: NextcloudFile) {
        val fileUrl = vm.client?.buildFileUrl(file.path); val auth = vm.client?.getAuthorizationHeader()
        if (fileUrl != null && auth != null) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            dm.enqueue(android.app.DownloadManager.Request(Uri.parse(fileUrl)).addRequestHeader("Authorization", auth).setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, file.name).setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setTitle(file.name).setDescription("Nextcloud Extended"))
            coroutineScope.launch { snackbarHostState.showSnackbar(s.downloadStarted(file.name)) }
        }
    }

    LaunchedEffect(vm.currentTab, vm.isConnected) {
        if (vm.isConnected && vm.currentTab == HubTab.FILES && vm.currentFolderPath.isEmpty() && username.isNotEmpty())
            vm.currentFolderPath = "/remote.php/dav/files/$username/"
    }

    CompositionLocalProvider(LocalStrings provides s) {
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (!vm.isConnected) "Nextcloud Extended" else vm.currentTab.label(s)) },
                actions = {
                    if (vm.isConnected) {
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, s.settings) }
                        IconButton(onClick = {
                            vm.disconnect { sharedPrefs.edit().clear().apply() }
                            coroutineScope.launch { snackbarHostState.showSnackbar(s.loggedOut) }
                        }) { Icon(Icons.Default.ExitToApp, s.logout) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, actionIconContentColor = MaterialTheme.colorScheme.onPrimary, scrolledContainerColor = MaterialTheme.colorScheme.primary),
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            if (vm.isConnected) {
                val uncompletedTasks = vm.tasks.count { it.status != "COMPLETED" }
                val overflowTabs = HubTab.entries.filter { it !in vm.pinnedTabs }
                fun badgeFor(tab: HubTab): Int = if (tab == HubTab.TASKS) uncompletedTasks else 0
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.weight(1f).height(64.dp),
                        shape = RoundedCornerShape(32.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 3.dp,
                        shadowElevation = 4.dp
                    ) {
                        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            vm.pinnedTabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = vm.currentTab == tab,
                                    onClick = { vm.currentTab = tab; vm.refreshData() },
                                    label = null,
                                    alwaysShowLabel = false,
                                    icon = {
                                        BadgedBox(badge = { if (badgeFor(tab) > 0) Badge { Text("${badgeFor(tab)}") } }) {
                                            Icon(tab.icon(), tab.label(s))
                                        }
                                    }
                                )
                            }
                        }
                    }
                    if (overflowTabs.isNotEmpty()) Box {
                        FilledIconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.size(64.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (vm.currentTab in overflowTabs) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = if (vm.currentTab in overflowTabs) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            val overflowBadge = overflowTabs.filter { it != vm.currentTab }.sumOf { badgeFor(it) }
                            BadgedBox(badge = { if (overflowBadge > 0) Badge { Text("$overflowBadge") } }) {
                                Icon(Icons.Default.Add, s.moreOptions)
                            }
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            overflowTabs.forEach { tab ->
                                DropdownMenuItem(
                                    text = { Text(tab.label(s)) },
                                    leadingIcon = {
                                        BadgedBox(badge = { if (badgeFor(tab) > 0) Badge { Text("${badgeFor(tab)}") } }) { Icon(tab.icon(), null) }
                                    },
                                    onClick = { vm.currentTab = tab; vm.refreshData(); showMoreMenu = false }
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (vm.isConnected) when (vm.currentTab) {
                HubTab.TASKS -> FloatingActionButton(onClick = { showAddTaskDialog = true }) { Icon(Icons.Default.Add, s.addTask) }
                HubTab.NOTES -> FloatingActionButton(onClick = { showAddNoteDialog = true }) { Icon(Icons.Default.Add, s.createNote) }
                HubTab.FILES -> FloatingActionButton(onClick = { showDriveBottomSheet = true }) { Icon(Icons.Default.Add, s.add) }
                HubTab.CONTACTS -> FloatingActionButton(onClick = { showAddContactDialog = true }) { Icon(Icons.Default.Add, s.createContact) }
                HubTab.CALENDAR -> FloatingActionButton(onClick = { showAddEventDialog = true }) { Icon(Icons.Default.Add, s.addEvent) }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            if (!vm.isConnected) {
                if (!prefsLoaded || (autoLoginStarted && vm.isLoading)) {
                    AutoConnectSplash()
                } else {
                LoginScreen(serverUrl = serverUrl, username = username, password = password, isLoading = vm.isLoading, allowInsecureHttp = allowInsecureHttp,
                    language = language, onLanguageChange = { language = it; sharedPrefs.edit().putString("language", it.name).apply() },
                    onServerUrlChange = { serverUrl = it }, onUsernameChange = { username = it }, onPasswordChange = { password = it }, onAllowInsecureHttpChange = { allowInsecureHttp = it },
                    onConnect = {
                        // Trim + default to https:// when the user omits the scheme, so OkHttp doesn't
                        // reject a bare host like "cloud.example.com".
                        val url = normalizeServerUrl(serverUrl)
                        if (url != serverUrl) serverUrl = url
                        if (url.isEmpty() || username.isEmpty() || password.isEmpty()) { vm.errorMessage = s.fillAllFields; return@LoginScreen }
                        if (url.startsWith("http://") && !allowInsecureHttp) { vm.errorMessage = s.insecureHttpBlocked; return@LoginScreen }
                        vm.connect(url, username, password) {
                            sharedPrefs.edit().putString("server_url", url).putString("username", username).putString("password", password).putBoolean("allow_insecure_http", allowInsecureHttp).apply()
                        }
                    })
                }
            } else {
                PullToRefreshBox(isRefreshing = vm.isLoading, onRefresh = { vm.refreshData() }, modifier = Modifier.fillMaxSize()) {
                    when (vm.currentTab) {
                        HubTab.CALENDAR -> CalendarMultiViewScreen(calendarInfos = vm.calendarInfos, activeCalendarHrefs = vm.activeCalendarHrefs, events = vm.events, calendarViewMode = vm.calendarViewMode, selectedDate = vm.selectedDate, onToggleCalendar = { vm.toggleCalendar(it) }, onViewModeChange = { vm.calendarViewMode = it }, onDateChange = { vm.selectedDate = it }, onEventTap = { detailEvent = it })
                        HubTab.TASKS -> TasksScreen(taskLists = vm.taskLists, selectedName = vm.selectedTaskListName, tasks = vm.tasks, onTaskListSelected = { href, name -> vm.loadTaskList(href, name) }, onToggleStatus = { vm.toggleTaskStatus(it) }, onDeleteTask = { vm.deleteTask(it) }, onEditTask = { editingTask = it }, onCreateList = { showCreateTaskListDialog = true }, onRenameList = { showRenameTaskListDialog = true }, onDeleteList = { showDeleteTaskListDialog = true })
                        HubTab.NOTES -> NotesScreen(notes = vm.notes, onNoteSelected = { viewingNote = it }, onToggleFavorite = { vm.toggleNoteFavorite(it) })
                        HubTab.CONTACTS -> ContactsScreen(
                            addressBooks = vm.addressBooks,
                            selectedName = vm.selectedAddressBookName,
                            contacts = vm.contacts,
                            onAddressBookSelected = { href, name -> vm.loadContactList(href, name) },
                            onContactSelected = { viewingContact = it }
                        )
                        HubTab.FILES -> FilesScreen(
                            currentFolderPath = vm.currentFolderPath, files = vm.files,
                            onFileClick = { file ->
                                val ext = file.name.substringAfterLast('.', "").lowercase()
                                when {
                                    file.isDirectory -> vm.navigateToFolder(file.path)
                                    file.name.endsWith(".pdf", ignoreCase = true) ->
                                        vm.downloadFile(file.path) { bytes -> pdfToView = Pair(file.name, bytes) }
                                    ext in officeExtensions -> {
                                        if (vm.officeViewerPref == OfficeViewerType.POI) {
                                            vm.downloadFile(file.path) { bytes ->
                                                officeToView = OfficeViewData(file.name, bytes, file.path)
                                            }
                                        } else {
                                            officeToView = OfficeViewData(file.name, null, file.path)
                                        }
                                    }
                                    else -> {
                                        enqueueFileDownload(file)
                                    }
                                }
                            },
                            onOpenFile = { file ->
                                val ext = file.name.substringAfterLast('.', "").lowercase()
                                when {
                                    file.name.endsWith(".pdf", ignoreCase = true) ->
                                        vm.downloadFile(file.path) { bytes -> pdfToView = Pair(file.name, bytes) }
                                    ext in officeExtensions -> {
                                        if (vm.officeViewerPref == OfficeViewerType.POI) {
                                            vm.downloadFile(file.path) { bytes ->
                                                officeToView = OfficeViewData(file.name, bytes, file.path)
                                            }
                                        } else {
                                            officeToView = OfficeViewData(file.name, null, file.path)
                                        }
                                    }
                                    else -> {
                                        vm.downloadFile(file.path) { bytes ->
                                            try {
                                                val cacheFile = File(context.cacheDir, file.name); cacheFile.writeBytes(bytes)
                                                val uri = FileProvider.getUriForFile(context, "xyz.luna.nextcloudextended.provider", cacheFile)
                                                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
                                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), s.openWith))
                                            } catch (e: Exception) { vm.errorMessage = s.cannotOpen(e.message ?: "") }
                                        }
                                    }
                                }
                            },
                            onShareFile = { vm.createShareLink(it) },
                            onDownloadFile = { file -> enqueueFileDownload(file) },
                            onBackClick = { vm.navigateUp() }, onDeleteFile = { vm.deleteFile(it.path) }, onRenameFile = { fileToRename = it; showRenameFileDialog = true }
                        )
                    }
                }
            }
        }
    }

    // Event detail sheet
    detailEvent?.let { event ->
        EventDetailSheet(
            event = event,
            calendarInfos = vm.calendarInfos,
            onDismiss = { detailEvent = null },
            onEdit = { editingEvent = event },
            onDelete = { vm.deleteEvent(event) }
        )
    }

    // Share link
    vm.shareLink?.let { link ->
        AlertDialog(onDismissRequest = { vm.shareLink = null }, title = { Text(s.shareLinkTitle) },
            text = { Column { Text(s.publicLinkCreated, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(8.dp)); Text(link) } },
            confirmButton = { Button(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", link)); vm.shareLink = null; coroutineScope.launch { snackbarHostState.showSnackbar(s.linkCopied) } }) { Text(s.copy) } },
            dismissButton = { Row { TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }) { Text(s.open) }; TextButton(onClick = { vm.shareLink = null }) { Text(s.close) } } })
    }

    // Edit event
    editingEvent?.let { event ->
        var evTitle by remember(event) { mutableStateOf(event.summary) }
        var evDesc by remember(event) { mutableStateOf(event.description ?: "") }
        var evLoc by remember(event) { mutableStateOf(event.location ?: "") }
        var evStart by remember(event) { mutableStateOf(event.startTime ?: "") }
        var evEnd by remember(event) { mutableStateOf(event.endTime ?: "") }
        AlertDialog(onDismissRequest = { editingEvent = null }, title = { Text(s.editEvent) },
            text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = evTitle, onValueChange = { evTitle = it }, label = { Text(s.title) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                OutlinedTextField(value = evDesc, onValueChange = { evDesc = it }, label = { Text(s.description) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                OutlinedTextField(value = evLoc, onValueChange = { evLoc = it }, label = { Text(s.location) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                OutlinedTextField(value = evStart, onValueChange = { evStart = it }, label = { Text(s.startDateTime) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                OutlinedTextField(value = evEnd, onValueChange = { evEnd = it }, label = { Text(s.endDateTime) }, modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = { Button(onClick = { if (evTitle.isNotEmpty()) { editingEvent = null; vm.editEvent(event, evTitle, evDesc, evLoc, evStart, evEnd) } }) { Text(s.save) } },
            dismissButton = { TextButton(onClick = { editingEvent = null }) { Text(s.cancel) } })
    }

    // Edit task
    editingTask?.let { task ->
        EditTaskDialog(task = task, onDismiss = { editingTask = null }, onSave = { summary, desc, due -> vm.editTask(task, summary, desc.ifEmpty { null }, due.ifEmpty { null }) })
    }

    // Contact detail
    viewingContact?.let { contact ->
        ContactDetailSheet(
            contact = contact,
            onDismiss = { viewingContact = null },
            onEdit = { editingContact = contact },
            onDelete = { vm.deleteContact(contact) },
            onDial = { phone -> runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone.replace(" ", "")}"))) } },
            onSendMail = { email -> runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))) } },
            onOpenMap = { address -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(address)))) } }
        )
    }

    // Add contact
    if (showAddContactDialog) {
        ContactDialog(initial = null, onDismiss = { showAddContactDialog = false }, onSave = { vm.createContact(it) })
    }

    // Edit contact
    editingContact?.let { contact ->
        ContactDialog(initial = contact, onDismiss = { editingContact = null }, onSave = { vm.updateContact(it) })
    }

    // Drive bottom sheet
    if (showDriveBottomSheet) {
        ModalBottomSheet(onDismissRequest = { showDriveBottomSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.addToDrive, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
                FilledTonalButton(onClick = { showDriveBottomSheet = false; showAddFolderDialog = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text(s.createFolder) }
                FilledTonalButton(onClick = { showDriveBottomSheet = false; filePickerLauncher.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Publish, null); Spacer(Modifier.width(8.dp)); Text(s.uploadFile) }
            }
        }
    }

    // Add folder
    if (showAddFolderDialog) { var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showAddFolderDialog = false }, title = { Text(s.newFolder) }, text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.folderName) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { if (name.isNotEmpty()) { showAddFolderDialog = false; vm.createFolder(name) } }) { Text(s.create) } }, dismissButton = { TextButton(onClick = { showAddFolderDialog = false }) { Text(s.cancel) } }) }

    // Add task
    if (showAddTaskDialog) { var taskTitle by remember { mutableStateOf("") }; var taskDesc by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showAddTaskDialog = false }, title = { Text(s.newTask) }, text = { Column { OutlinedTextField(value = taskTitle, onValueChange = { taskTitle = it }, label = { Text(s.title) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)); OutlinedTextField(value = taskDesc, onValueChange = { taskDesc = it }, label = { Text(s.description) }, modifier = Modifier.fillMaxWidth()) } },
            confirmButton = { Button(onClick = { if (taskTitle.isNotEmpty()) { showAddTaskDialog = false; vm.createTask(UUID.randomUUID().toString(), taskTitle, taskDesc) } }) { Text(s.add) } }, dismissButton = { TextButton(onClick = { showAddTaskDialog = false }) { Text(s.cancel) } }) }

    // Add event
    if (showAddEventDialog) {
        var evTitle by remember { mutableStateOf("") }; var evDesc by remember { mutableStateOf("") }; var evLoc by remember { mutableStateOf("") }
        var evStart by remember { mutableStateOf(vm.selectedDate.toString() + " 10:00") }; var evEnd by remember { mutableStateOf(vm.selectedDate.toString() + " 11:00") }
        var calDropdown by remember { mutableStateOf(false) }; var selHref by remember { mutableStateOf(vm.calendarInfos.firstOrNull()?.href ?: "") }; var selName by remember { mutableStateOf(vm.calendarInfos.firstOrNull()?.displayName ?: "") }
        AlertDialog(onDismissRequest = { showAddEventDialog = false }, title = { Text(s.newEvent) },
            text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (vm.calendarInfos.size > 1) Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    OutlinedTextField(value = selName, onValueChange = {}, label = { Text(s.calendarLabel) }, readOnly = true, modifier = Modifier.fillMaxWidth(), trailingIcon = { IconButton(onClick = { calDropdown = true }) { Icon(Icons.Default.ArrowDropDown, null) } })
                    DropdownMenu(expanded = calDropdown, onDismissRequest = { calDropdown = false }) { vm.calendarInfos.forEach { cal -> DropdownMenuItem(text = { Text(cal.displayName) }, onClick = { selHref = cal.href; selName = cal.displayName; calDropdown = false }) } }
                }
                OutlinedTextField(value = evTitle, onValueChange = { evTitle = it }, label = { Text(s.title) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                OutlinedTextField(value = evDesc, onValueChange = { evDesc = it }, label = { Text(s.description) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                OutlinedTextField(value = evLoc, onValueChange = { evLoc = it }, label = { Text(s.location) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                OutlinedTextField(value = evStart, onValueChange = { evStart = it }, label = { Text(s.startDateTime) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                OutlinedTextField(value = evEnd, onValueChange = { evEnd = it }, label = { Text(s.endDateTime) }, modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = { Button(onClick = { if (evTitle.isNotEmpty() && selHref.isNotEmpty()) { showAddEventDialog = false; vm.createEvent(CalendarEvent(UUID.randomUUID().toString(), evTitle, evDesc.ifEmpty { null }, evStart, evEnd, evLoc.ifEmpty { null }, selHref), selHref) } else if (selHref.isEmpty()) { vm.errorMessage = s.selectCalendarFirst } }) { Text(s.add) } },
            dismissButton = { TextButton(onClick = { showAddEventDialog = false }) { Text(s.cancel) } })
    }

    // Task list management
    if (showCreateTaskListDialog) { var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showCreateTaskListDialog = false }, title = { Text(s.newList) }, text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.name) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { if (name.isNotEmpty()) { showCreateTaskListDialog = false; vm.createTaskList(name) } }) { Text(s.create) } }, dismissButton = { TextButton(onClick = { showCreateTaskListDialog = false }) { Text(s.cancel) } }) }
    if (showRenameTaskListDialog) { var name by remember { mutableStateOf(vm.selectedTaskListName) }
        AlertDialog(onDismissRequest = { showRenameTaskListDialog = false }, title = { Text(s.renameTitle) }, text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.newName) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { if (name.isNotEmpty()) { showRenameTaskListDialog = false; vm.renameTaskList(name) } }) { Text(s.rename) } }, dismissButton = { TextButton(onClick = { showRenameTaskListDialog = false }) { Text(s.cancel) } }) }
    if (showDeleteTaskListDialog) {
        AlertDialog(onDismissRequest = { showDeleteTaskListDialog = false }, title = { Text(s.deleteListTitle) }, text = { Text(s.deleteListConfirm(vm.selectedTaskListName)) },
            confirmButton = { Button(onClick = { showDeleteTaskListDialog = false; vm.deleteTaskList() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(s.delete) } }, dismissButton = { TextButton(onClick = { showDeleteTaskListDialog = false }) { Text(s.cancel) } }) }

    // Rename file
    if (showRenameFileDialog && fileToRename != null) { var name by remember { mutableStateOf(fileToRename!!.name) }
        AlertDialog(onDismissRequest = { showRenameFileDialog = false }, title = { Text(s.renameTitle) }, text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.newName) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { if (name.isNotEmpty() && name != fileToRename!!.name) { showRenameFileDialog = false; vm.renameFile(fileToRename!!.path, name) } }) { Text(s.rename) } }, dismissButton = { TextButton(onClick = { showRenameFileDialog = false }) { Text(s.cancel) } }) }

    // Add note
    if (showAddNoteDialog) { var noteTitle by remember { mutableStateOf("") }; var noteContent by remember { mutableStateOf("") }; var noteCat by remember { mutableStateOf(s.defaultCategory) }
        AlertDialog(onDismissRequest = { showAddNoteDialog = false }, title = { Text(s.newNote) }, text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { OutlinedTextField(value = noteTitle, onValueChange = { noteTitle = it }, label = { Text(s.title) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)); OutlinedTextField(value = noteCat, onValueChange = { noteCat = it }, label = { Text(s.category) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)); OutlinedTextField(value = noteContent, onValueChange = { noteContent = it }, label = { Text(s.content) }, minLines = 4, modifier = Modifier.fillMaxWidth()) } },
            confirmButton = { Button(onClick = { if (noteTitle.isNotEmpty()) { showAddNoteDialog = false; vm.createNote(noteTitle, noteContent, noteCat) } }) { Text(s.create) } }, dismissButton = { TextButton(onClick = { showAddNoteDialog = false }) { Text(s.cancel) } }) }

    // View note with Markdown rendering
    viewingNote?.let { note ->
        Dialog(onDismissRequest = { viewingNote = null }) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (note.category.isNotEmpty()) note.category.uppercase() else s.noteFallbackLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = { viewingNote = null; editingNote = note }) { Icon(Icons.Default.Edit, s.edit, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Text(note.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                    HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))
                    MarkdownText(note.content, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { viewingNote = null; vm.deleteNote(note.id) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(s.delete) }
                        Button(onClick = { viewingNote = null }) { Text(s.close) }
                    }
                }
            }
        }
    }

    // Edit note
    editingNote?.let { note ->
        var noteTitle by remember { mutableStateOf(note.title) }; var noteContent by remember { mutableStateOf(note.content) }; var noteCat by remember { mutableStateOf(note.category) }
        AlertDialog(onDismissRequest = { editingNote = null }, title = { Text(s.editNote) }, text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) { OutlinedTextField(value = noteTitle, onValueChange = { noteTitle = it }, label = { Text(s.title) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)); OutlinedTextField(value = noteCat, onValueChange = { noteCat = it }, label = { Text(s.category) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)); OutlinedTextField(value = noteContent, onValueChange = { noteContent = it }, label = { Text(s.content) }, minLines = 5, modifier = Modifier.fillMaxWidth()) } },
            confirmButton = { Button(onClick = { if (noteTitle.isNotEmpty()) { editingNote = null; vm.updateNote(note, noteTitle, noteContent, noteCat) } }) { Text(s.save) } }, dismissButton = { TextButton(onClick = { editingNote = null }) { Text(s.cancel) } })
    }

    // PDF Viewer
    pdfToView?.let { (name, bytes) ->
        Dialog(
            onDismissRequest = { pdfToView = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                PdfViewerScreen(fileName = name, pdfBytes = bytes, onDismiss = { pdfToView = null })
            }
        }
    }

    // Office Viewer
    officeToView?.let { data ->
        Dialog(
            onDismissRequest = { officeToView = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                OfficeViewerScreen(
                    fileName = data.fileName,
                    fileBytes = data.bytes,
                    filePath = data.filePath,
                    viewerType = vm.officeViewerPref,
                    onDismiss = { officeToView = null },
                    onGetOnlineEditorUrl = { onSuccess, onFailure ->
                        vm.getOnlineEditorUrl(data.filePath, onSuccess, onFailure)
                    }
                )
            }
        }
    }

    // Settings
    if (showSettings) {
        Dialog(
            onDismissRequest = { showSettings = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                SettingsScreen(
                    officeViewerPref = vm.officeViewerPref,
                    onOfficeViewerPrefChange = { pref ->
                        vm.officeViewerPref = pref
                        sharedPrefs.edit().putString("office_viewer_pref", pref.name).apply()
                    },
                    pinnedTabs = vm.pinnedTabs,
                    onPinnedTabsChange = { tabs ->
                        vm.pinnedTabs = tabs
                        sharedPrefs.edit().putString("pinned_tabs", tabs.joinToString(",") { it.name }).apply()
                    },
                    onDismiss = { showSettings = false }
                )
            }
        }
    }
    }
}

// Branded loading screen shown while reconnecting automatically with the stored credentials,
// so the pre-filled login form doesn't flash for a second on app start.
@Composable
private fun AutoConnectSplash() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Nextcloud Extended",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        CircularProgressIndicator()
    }
}

// Normalizes a user-entered server URL: trims whitespace, prepends https:// when no scheme is
// given, and drops trailing slashes. Returns "" unchanged so the empty-field check still fires.
private fun normalizeServerUrl(raw: String): String {
    val u = raw.trim()
    if (u.isEmpty()) return u
    val withScheme = if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    return withScheme.trimEnd('/')
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use {
        if (it.moveToFirst()) { val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (idx != -1) name = it.getString(idx) }
    }
    if (name == null) { name = uri.path; val cut = name?.lastIndexOf('/') ?: -1; if (cut != -1) name = name?.substring(cut + 1) }
    return name
}
