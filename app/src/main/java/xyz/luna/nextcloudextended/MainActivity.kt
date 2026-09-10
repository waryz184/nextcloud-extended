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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.NextcloudContact
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import xyz.luna.nextcloudextended.data.model.NextcloudNote
import xyz.luna.nextcloudextended.data.model.NextcloudTask
import xyz.luna.nextcloudextended.account.NextcloudAccountManager
import xyz.luna.nextcloudextended.account.NextcloudAccounts
import xyz.luna.nextcloudextended.account.AccountProfile
import xyz.luna.nextcloudextended.account.AccountProfiles
import xyz.luna.nextcloudextended.sync.AccountSetupActivity
import xyz.luna.nextcloudextended.ui.screens.*
import xyz.luna.nextcloudextended.ui.theme.NextcloudExtendedTheme
import xyz.luna.nextcloudextended.upload.MediaAutoUploadReceiver
import xyz.luna.nextcloudextended.upload.UploadRepository
import xyz.luna.nextcloudextended.upload.DownloadRepository
import xyz.luna.nextcloudextended.upload.OfflineCacheManager
import xyz.luna.nextcloudextended.upload.OfflineOperationReplayer
import xyz.luna.nextcloudextended.upload.NextcloudDatabase
import java.io.File
import java.util.Locale
import java.util.UUID

enum class HubTab { CALENDAR, TASKS, NOTES, CONTACTS, FILES }
enum class CalendarViewMode { DAY, WEEK, MONTH, YEAR }
enum class OfficeViewerType { POI, ONLINE }

val DEFAULT_PINNED_TABS = listOf(HubTab.CALENDAR, HubTab.NOTES, HubTab.FILES)

fun HubTab.icon(): ImageVector = when (this) {
    HubTab.CALENDAR -> Icons.Default.DateRange
    HubTab.TASKS -> Icons.AutoMirrored.Filled.List
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
class MainActivity : androidx.fragment.app.FragmentActivity() {
var incomingShareIntent by mutableStateOf<Intent?>(null)
    var isAppLocked by mutableStateOf(false)
    private var unlockInProgress = false
    private var fingerprintDialog: androidx.biometric.BiometricPrompt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        incomingShareIntent = intent.takeIf { it.action == Intent.ACTION_SEND || it.action == Intent.ACTION_SEND_MULTIPLE }
        setContent { NextcloudExtendedTheme { NextcloudHubApp() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingShareIntent = intent.takeIf {
            it.action == Intent.ACTION_SEND || it.action == Intent.ACTION_SEND_MULTIPLE
        }
    }

    override fun onResume() {
        super.onResume()
        if (appLockEnabled()) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            isAppLocked = true
            requestUnlock()
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onPause() {
        if (appLockEnabled()) isAppLocked = true
        super.onPause()
    }

fun requestUnlock() {
        if (!isAppLocked || unlockInProgress) return
        unlockInProgress = true
        val authenticators = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val canAuthenticate = androidx.biometric.BiometricManager.from(this).canAuthenticate(authenticators)
        if (canAuthenticate != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            // Fail-closed: no usable authenticator (biometric or device credential). The current
            // session stays locked; the user is offered to disable the lock from settings.
            unlockInProgress = false
            return
        }
        val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Nextcloud Extended")
            .setSubtitle("Authenticate to access your files")
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(false)
            .build()
        val prompt = androidx.biometric.BiometricPrompt(
            this,
            mainExecutor,
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    unlockInProgress = false
                    isAppLocked = false
                }

                override fun onAuthenticationFailed() {
                    unlockInProgress = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    unlockInProgress = false
                }
            }
        )
        fingerprintDialog = prompt
        prompt.authenticate(promptInfo)
    }

    fun hasUsableUnlockMethod(): Boolean =
        androidx.biometric.BiometricManager.from(this).canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS

private fun appLockEnabled(): Boolean = runCatching {
        val key = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(this, "secret_shared_prefs", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        prefs.getBoolean("app_lock_enabled", false)
    }.getOrDefault(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextcloudHubApp(vm: NextcloudViewModel = viewModel()) {
    val context = LocalContext.current
    val hostActivity = context as? MainActivity
    val sharedPrefs = remember {
        val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "secret_shared_prefs", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // Language: stored preference, else device locale (FR for French devices, EN otherwise)
    var language by remember {
        mutableStateOf(
            sharedPrefs.getString("language", null)?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                ?: if (Locale.getDefault().language == "fr") AppLanguage.FR else AppLanguage.EN
        )
    }
    LaunchedEffect(language) { vm.language = language }
    LaunchedEffect(vm.events) { CalendarWidget.update(context, vm.events) }
    var accounts by remember { mutableStateOf(AccountProfiles.load(sharedPrefs)) }

    fun saveAccount(server: String, user: String, secret: String) {
        val existing = accounts.firstOrNull { it.serverUrl == server && it.username == user }
        val profile = AccountProfile(existing?.id ?: UUID.randomUUID().toString(), server, user, secret)
        AccountProfiles.save(sharedPrefs, profile)
        sharedPrefs.edit().putString("active_account_id", profile.id).apply()
        accounts = AccountProfiles.load(sharedPrefs)
    }

    // True once the saved preferences have been read — prevents a one-frame flash of the
    // login form before the auto-login check below runs.
    var prefsLoaded by remember { mutableStateOf(false) }
    // True once an automatic reconnect with the stored credentials has been kicked off.
    var autoLoginStarted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        serverUrl = sharedPrefs.getString("server_url", "") ?: ""
        username = sharedPrefs.getString("username", "") ?: ""
        password = sharedPrefs.getString("password", "") ?: ""
        accounts = AccountProfiles.load(sharedPrefs)
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
        if (!vm.isConnected && !vm.autoLoginAttempted &&
            url.startsWith("https://", ignoreCase = true) && username.isNotEmpty() && password.isNotEmpty()
        ) {
            vm.autoLoginAttempted = true
            autoLoginStarted = true
            if (url != serverUrl) serverUrl = url
            vm.connect(url, username, password) { saveAccount(url, username, password) }
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
    var showTransferFileDialog by remember { mutableStateOf(false) }
    var transferFile by remember { mutableStateOf<NextcloudFile?>(null) }
    var transferIsCopy by remember { mutableStateOf(true) }
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
    var showTransferHistory by remember { mutableStateOf(false) }
    var showOfflineFiles by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSharesDialog by remember { mutableStateOf(false) }
    var shareFileForDialog by remember { mutableStateOf<NextcloudFile?>(null) }
    var capturedPhoto by remember { mutableStateOf<File?>(null) }
    var scannedPhoto by remember { mutableStateOf<File?>(null) }
    var scanPages by remember { mutableStateOf<List<File>>(emptyList()) }
    var showScanReview by remember { mutableStateOf(false) }
var mediaAutoUploadEnabled by remember {
        mutableStateOf(sharedPrefs.getBoolean(MediaAutoUploadReceiver.KEY_ENABLED, false))
    }
    var mediaWifiOnly by remember {
        mutableStateOf(sharedPrefs.getBoolean(MediaAutoUploadReceiver.KEY_WIFI_ONLY, false))
    }
    var mediaChargingOnly by remember {
        mutableStateOf(sharedPrefs.getBoolean(MediaAutoUploadReceiver.KEY_CHARGING_ONLY, false))
    }
    var mediaSubfolder by remember {
        mutableStateOf(sharedPrefs.getString(MediaAutoUploadReceiver.KEY_SUBFOLDER, "InstantUpload") ?: "InstantUpload")
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    vm.errorMessage?.let { msg ->
        LaunchedEffect(msg) { snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short); vm.errorMessage = null }
    }

    fun enqueueBackgroundUpload(uri: Uri, fileName: String) {
        coroutineScope.launch {
            runCatching {
                val accountId = accounts.firstOrNull { it.serverUrl == serverUrl && it.username == username }?.id
                    ?: throw java.io.IOException("No active account")
                UploadRepository.enqueueUri(context, accountId, uri, vm.currentFolderPath, fileName)
            }.onFailure { vm.errorMessage = s.uploadFailed(it.message ?: "") }
        }
    }

    fun enqueueBackgroundFile(file: File) {
        coroutineScope.launch {
            runCatching {
                val accountId = accounts.firstOrNull { it.serverUrl == serverUrl && it.username == username }?.id
                    ?: throw java.io.IOException("No active account")
                UploadRepository.enqueue(context, accountId, file.absolutePath, vm.currentFolderPath, file.name, file.length())
            }.onFailure { vm.errorMessage = s.uploadFailed(it.message ?: "") }
        }
    }

    fun makeFileAvailableOffline(file: NextcloudFile) {
        val accountId = accounts.firstOrNull { it.serverUrl == serverUrl && it.username == username }?.id
        if (accountId == null) {
            vm.errorMessage = s.filesError("No active account")
            return
        }
        vm.downloadFile(file.path) { bytes ->
            coroutineScope.launch {
                runCatching { OfflineCacheManager.cache(context, accountId, file.path, bytes) }
                    .onFailure { vm.errorMessage = s.filesError(it.message ?: "") }
            }
        }
    }

fun loadFileBytes(file: NextcloudFile, onBytes: (ByteArray) -> Unit) {
        val accountId = accounts.firstOrNull { it.serverUrl == serverUrl && it.username == username }?.id
        coroutineScope.launch {
            val cached = accountId?.let { id ->
                withContext(Dispatchers.IO) { OfflineCacheManager.get(context, id, file.path)?.readBytes() }
            }
            if (cached != null) {
                onBytes(cached)
            } else {
                // Stream to disk first so files over the in-memory preview cap still open.
                vm.downloadFileToCache(file.name, file.path,
                    onSuccess = { tmp ->
                        coroutineScope.launch(Dispatchers.IO) {
                            val bytes = runCatching { tmp.readBytes() }.getOrNull()
                            tmp.delete()
                            if (bytes != null) withContext(Dispatchers.Main) { onBytes(bytes) }
                            else vm.errorMessage = s.downloadFailed("Unable to read downloaded preview")
                        }
                    },
                    onFailure = { err -> vm.errorMessage = s.downloadFailed(err.message ?: "") }
                )
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            coroutineScope.launch {
                try {
                    val fileName = getFileNameFromUri(context, uri) ?: "upload_${System.currentTimeMillis()}"
                    enqueueBackgroundUpload(uri, fileName)
                } catch (e: Exception) { vm.errorMessage = s.fileReadError(e.message ?: "") }
            }
        }
    }

    // Enqueues a file for download to the device's Downloads folder via the system
    // DownloadManager (notification shown on completion). Used both when tapping a file that
    // has no in-app viewer and for the explicit "Download" action in the file menu.
    fun enqueueFileDownload(file: NextcloudFile) {
        val accountId = accounts.firstOrNull { it.serverUrl == serverUrl && it.username == username }?.id
        if (accountId == null) {
            vm.errorMessage = s.downloadFailed("No active account")
            return
        }
        coroutineScope.launch {
            runCatching { DownloadRepository.enqueue(context, accountId, file.path, file.name) }
                .onSuccess { snackbarHostState.showSnackbar(s.downloadStarted(file.name)) }
                .onFailure { vm.errorMessage = s.downloadFailed(it.message ?: "") }
        }
    }

LaunchedEffect(vm.currentTab, vm.isConnected) {
        if (vm.isConnected && vm.currentTab == HubTab.FILES && vm.currentFolderPath.isEmpty() && username.isNotEmpty()) {
            vm.currentFolderPath = "/remote.php/dav/files/$username/"
            vm.refreshData()
        }
        if (vm.isConnected) {
            // Replay offline file operations queued while the network was unavailable.
            var hasMore = true
            while (hasMore) {
                hasMore = withContext(Dispatchers.IO) { OfflineOperationReplayer.replayNext(context) }
            }
        }
    }

    // Prompt to create a system account after first login
    LaunchedEffect(vm.isConnected) {
        if (vm.isConnected) {
            val am = NextcloudAccountManager(context)
            if (am.firstAccount() == null) {
                val result = snackbarHostState.showSnackbar(
                    message = "Sync contacts with the phone's Contacts app",
                    actionLabel = "Add account",
                    duration = SnackbarDuration.Indefinite
                )
                if (result == SnackbarResult.ActionPerformed) {
                    val intent = Intent(context, AccountSetupActivity::class.java)
                    context.startActivity(intent)
                }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val photo = capturedPhoto
        capturedPhoto = null
        if (saved && photo != null && photo.exists()) {
            enqueueBackgroundFile(photo)
        } else {
            photo?.delete()
        }
    }

    fun enqueueScannedPages(pages: List<File>) {
        if (pages.isEmpty()) return
        coroutineScope.launch(Dispatchers.IO) {
            runCatching {
                val pdf = File(pendingUploadDirectory(context), "scan_${System.currentTimeMillis()}.pdf")
                val document = android.graphics.pdf.PdfDocument()
                try {
                    pages.forEachIndexed { index, pageFile ->
                        val bitmap = android.graphics.BitmapFactory.decodeFile(pageFile.absolutePath)
                            ?: throw java.io.IOException("Unable to decode scanned page")
                        val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                            bitmap.width.coerceAtLeast(1), bitmap.height.coerceAtLeast(1), index + 1
                        ).create()
                        val page = document.startPage(pageInfo)
                        page.canvas.drawBitmap(bitmap, 0f, 0f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
                        document.finishPage(page)
                        bitmap.recycle()
                    }
                    pdf.outputStream().use { document.writeTo(it) }
                } finally {
                    document.close()
                }
                pages.forEach { it.delete() }
                withContext(Dispatchers.Main) {
                    enqueueBackgroundFile(pdf)
                    scanPages = emptyList()
                    showScanReview = false
                }
            }.onFailure {
                withContext(Dispatchers.Main) { vm.errorMessage = s.fileReadError(it.message ?: "") }
            }
        }
    }

    val documentScanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val photo = scannedPhoto
        scannedPhoto = null
        if (saved && photo != null && photo.exists()) {
            scanPages = scanPages + photo
            showScanReview = true
        } else {
            photo?.delete()
        }
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
val granted = permissions.values.all { it }
        mediaAutoUploadEnabled = granted
        sharedPrefs.edit().putBoolean(MediaAutoUploadReceiver.KEY_ENABLED, granted).apply()
        if (granted) {
            sharedPrefs.edit().putString(MediaAutoUploadReceiver.KEY_SUBFOLDER, mediaSubfolder).apply()
            UploadRepository.schedule(context)
            context.sendBroadcast(Intent(context, MediaAutoUploadReceiver::class.java))
        }
    }

    fun capturePhoto() {
        val photo = File.createTempFile("nextcloud_photo_", ".jpg", pendingUploadDirectory(context))
        capturedPhoto = photo
        val uri = FileProvider.getUriForFile(context, "xyz.luna.nextcloudextended.provider", photo)
        cameraLauncher.launch(uri)
    }

    fun scanDocument() {
        val photo = File.createTempFile("nextcloud_scan_", ".jpg", pendingUploadDirectory(context))
        scannedPhoto = photo
        val uri = FileProvider.getUriForFile(context, "xyz.luna.nextcloudextended.provider", photo)
        documentScanLauncher.launch(uri)
    }

    if (showScanReview) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Scanned pages: ${scanPages.size}") },
            text = { Text("Add another page or upload all pages as one PDF.") },
            confirmButton = {
                Button(onClick = { enqueueScannedPages(scanPages) }, enabled = scanPages.isNotEmpty()) { Text("Upload PDF") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { scanDocument() }) { Text("Add page") }
                    TextButton(onClick = { scanPages.forEach { it.delete() }; scanPages = emptyList(); showScanReview = false }) { Text(s.cancel) }
                }
            }
        )
    }

    // Match the native client's "receive external files" flow. The intent is held until a
    // session and a WebDAV folder are ready, then consumed exactly once.
    LaunchedEffect(hostActivity?.incomingShareIntent, vm.isConnected, vm.currentFolderPath) {
        val shareIntent = hostActivity?.incomingShareIntent ?: return@LaunchedEffect
        if (!vm.isConnected || vm.currentFolderPath.isEmpty()) return@LaunchedEffect

val uris = buildList {
            if (shareIntent.action == Intent.ACTION_SEND) {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    shareIntent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                } else {
                    @Suppress("DEPRECATION")
                    shareIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
                }
            } else {
                val extras: List<Uri>? = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    shareIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    shareIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                extras?.let(::addAll)
                    ?: shareIntent.clipData?.let { clip ->
                        for (index in 0 until clip.itemCount) add(clip.getItemAt(index).uri)
                    }
            }
        }.distinct()
        hostActivity.incomingShareIntent = null

        uris.forEach { uri ->
            val fileName = getFileNameFromUri(context, uri) ?: "shared_${System.currentTimeMillis()}"
            runCatching { enqueueBackgroundUpload(uri, fileName) }
                .onFailure { vm.errorMessage = s.fileReadError(it.message ?: "") }
        }
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
                        IconButton(onClick = { showTransferHistory = true }) { Icon(Icons.Default.History, "Transfer history") }
                        IconButton(onClick = { showOfflineFiles = true }) { Icon(Icons.Default.Download, "Available offline") }
                        IconButton(onClick = {
                            vm.disconnect {
                                sharedPrefs.edit().remove("server_url").remove("username").remove("password").apply()
                            }
                            coroutineScope.launch { snackbarHostState.showSnackbar(s.loggedOut) }
                        }) { Icon(Icons.AutoMirrored.Filled.ExitToApp, s.logout) }
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
                LoginScreen(serverUrl = serverUrl, username = username, password = password, isLoading = vm.isLoading,
                    language = language, onLanguageChange = { language = it; sharedPrefs.edit().putString("language", it.name).apply() },
                    onServerUrlChange = { serverUrl = it }, onUsernameChange = { username = it }, onPasswordChange = { password = it },
                    onConnect = {
                        // Trim + default to https:// when the user omits the scheme, so OkHttp doesn't
                        // reject a bare host like "cloud.example.com".
                        val url = normalizeServerUrl(serverUrl)
                        if (url != serverUrl) serverUrl = url
                        if (url.isEmpty() || username.isEmpty() || password.isEmpty()) { vm.errorMessage = s.fillAllFields; return@LoginScreen }
                        if (!url.startsWith("https://", ignoreCase = true)) { vm.errorMessage = s.insecureHttpBlocked; return@LoginScreen }
                        vm.connect(url, username, password) {
                            saveAccount(url, username, password)
                            sharedPrefs.edit().putString("server_url", url).putString("username", username).putString("password", password).remove("allow_insecure_http").apply()
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
                                        loadFileBytes(file) { bytes -> pdfToView = Pair(file.name, bytes) }
                                    ext in officeExtensions -> {
                                        if (vm.officeViewerPref == OfficeViewerType.POI) {
                                            loadFileBytes(file) { bytes ->
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
                                        loadFileBytes(file) { bytes -> pdfToView = Pair(file.name, bytes) }
                                    ext in officeExtensions -> {
                                        if (vm.officeViewerPref == OfficeViewerType.POI) {
                                            loadFileBytes(file) { bytes ->
                                                officeToView = OfficeViewData(file.name, bytes, file.path)
                                            }
                                        } else {
                                            officeToView = OfficeViewData(file.name, null, file.path)
                                        }
                                    }
                                    else -> {
                                        loadFileBytes(file) { bytes ->
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
                            canShareFiles = !vm.serverCapabilities.discovered || vm.serverCapabilities.has("files_sharing"),
                            onShareFile = {
                                shareFileForDialog = it
                                showSharesDialog = true
                                vm.loadShares(it)
                            },
                            onDownloadFile = { file -> enqueueFileDownload(file) },
                            onMakeOffline = { file -> makeFileAvailableOffline(file) },
                            onBackClick = { vm.navigateUp() }, onDeleteFile = { file ->
                                if (vm.client == null) {
                                    val accountId = accounts.firstOrNull { it.serverUrl == serverUrl && it.username == username }?.id
                                    if (accountId != null) {
                                        coroutineScope.launch { OfflineOperationReplayer.enqueueDelete(context, accountId, file.path) }
                                    }
                                } else {
                                    vm.deleteFile(file.path)
                                }
                            }, onRenameFile = { fileToRename = it; showRenameFileDialog = true },
                            onCopyFile = { transferFile = it; transferIsCopy = true; showTransferFileDialog = true },
                            onMoveFile = { transferFile = it; transferIsCopy = false; showTransferFileDialog = true }
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

    // Conflict resolution
    vm.conflictFile?.let { conflict ->
        AlertDialog(
            onDismissRequest = { vm.dismissConflict() },
            title = { Text("File conflict") },
            text = { Text("${conflict.fileName} was changed on the server since you last viewed it. What do you want to do?") },
            confirmButton = {
                Row {
                    Button(onClick = { vm.resolveConflictOverwrite(conflict.fileName, conflict.localBytes) }) { Text("Overwrite") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { vm.resolveConflictRename(conflict.fileName, conflict.localBytes) }) { Text("Keep both") }
                }
            },
            dismissButton = { TextButton(onClick = { vm.dismissConflict() }) { Text("Skip") } }
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
                FilledTonalButton(onClick = {
                    showDriveBottomSheet = false
                    try {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    } catch (e: Exception) {
                        vm.errorMessage = s.cannotOpen(e.message ?: "No file manager found")
                    }
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Publish, null); Spacer(Modifier.width(8.dp)); Text(s.uploadFile) }
                FilledTonalButton(onClick = { showDriveBottomSheet = false; capturePhoto() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Take a photo") }
                FilledTonalButton(onClick = { showDriveBottomSheet = false; scanDocument() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.DocumentScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan document") }
            }
        }
    }

    // Add folder
    if (showAddFolderDialog) { var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showAddFolderDialog = false }, title = { Text(s.newFolder) }, text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(s.folderName) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { if (name.isNotEmpty()) { showAddFolderDialog = false; if (vm.client == null) { val accountId = accounts.firstOrNull { it.serverUrl == serverUrl && it.username == username }?.id; if (accountId != null) coroutineScope.launch { OfflineOperationReplayer.enqueueCreateFolder(context, accountId, vm.currentFolderPath, name) } } else { vm.createFolder(name) } } }) { Text(s.create) } }, dismissButton = { TextButton(onClick = { showAddFolderDialog = false }) { Text(s.cancel) } }) }

    if (showTransferFileDialog) {
        var destination by remember(transferFile) { mutableStateOf(vm.currentFolderPath) }
        AlertDialog(
            onDismissRequest = { showTransferFileDialog = false },
            title = { Text(if (transferIsCopy) "Copy to" else "Move to") },
            text = {
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    label = { Text("Destination path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val file = transferFile
                    if (file != null && destination.isNotBlank()) {
                        showTransferFileDialog = false
                        vm.transferFile(file.path, destination.trimEnd('/') + "/" + file.name, transferIsCopy)
                    }
                }) { Text(if (transferIsCopy) "Copy" else "Move") }
            },
            dismissButton = { TextButton(onClick = { showTransferFileDialog = false }) { Text(s.cancel) } }
        )
    }

    if (showSharesDialog) {
        AlertDialog(
            onDismissRequest = { showSharesDialog = false },
            title = { Text("Shares") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (vm.isLoading && vm.shares.isEmpty()) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else if (vm.shares.isEmpty()) {
                        Text("No shares for this file", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        vm.shares.forEach { share ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(share.shareWith ?: if (share.shareType == 3) "Public link" else "Share ${share.id}")
                                    share.expiration?.let { Text("Expires: $it", style = MaterialTheme.typography.bodySmall) }
                                }
                                TextButton(onClick = { vm.revokeShare(share) }) { Text("Revoke") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { shareFileForDialog?.let(vm::createShareLink) }) { Text("Create public link") }
            },
            dismissButton = { TextButton(onClick = { showSharesDialog = false }) { Text(s.close) } }
        )
    }

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
            confirmButton = { Button(onClick = { if (name.isNotEmpty() && name != fileToRename!!.name) { showRenameFileDialog = false; if (vm.client == null) { val accountId = accounts.firstOrNull { it.serverUrl == serverUrl && it.username == username }?.id; if (accountId != null) coroutineScope.launch { OfflineOperationReplayer.enqueueRename(context, accountId, fileToRename!!.path, name) } } else { vm.renameFile(fileToRename!!.path, name) } } }) { Text(s.rename) } }, dismissButton = { TextButton(onClick = { showRenameFileDialog = false }) { Text(s.cancel) } }) }

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
appLockEnabled = sharedPrefs.getBoolean("app_lock_enabled", false),
                    onAppLockChange = { enabled ->
                        if (enabled && hostActivity != null && !hostActivity.hasUsableUnlockMethod()) {
                            vm.errorMessage = "No biometric or device lock available to secure the app"
                            return@SettingsScreen
                        }
                        sharedPrefs.edit().putBoolean("app_lock_enabled", enabled).apply()
                        if (!enabled) {
                            hostActivity?.isAppLocked = false
                            hostActivity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                        } else {
                            hostActivity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                            hostActivity?.isAppLocked = true
                            hostActivity?.requestUnlock()
                        }
                    },
mediaAutoUploadEnabled = mediaAutoUploadEnabled,
                    onMediaAutoUploadChange = { enabled ->
                        if (!enabled) {
                            mediaAutoUploadEnabled = false
                            sharedPrefs.edit().putBoolean(MediaAutoUploadReceiver.KEY_ENABLED, false).apply()
                            MediaAutoUploadReceiver.cancelSchedule(context)
                        } else {
                            val permissions = if (android.os.Build.VERSION.SDK_INT >= 33) {
                                arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
                            } else {
                                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                             mediaPermissionLauncher.launch(permissions)
                        }
                    },
                    mediaWifiOnly = mediaWifiOnly,
                    onMediaWifiOnlyChange = { enabled ->
                        mediaWifiOnly = enabled
                        sharedPrefs.edit().putBoolean(MediaAutoUploadReceiver.KEY_WIFI_ONLY, enabled).apply()
                        if (mediaAutoUploadEnabled) UploadRepository.schedule(context)
                    },
                    mediaChargingOnly = mediaChargingOnly,
                    onMediaChargingOnlyChange = { enabled ->
                        mediaChargingOnly = enabled
                        sharedPrefs.edit().putBoolean(MediaAutoUploadReceiver.KEY_CHARGING_ONLY, enabled).apply()
                        if (mediaAutoUploadEnabled) UploadRepository.schedule(context)
                    },
                    mediaSubfolder = mediaSubfolder,
                    onMediaSubfolderChange = { value ->
                        mediaSubfolder = value
                        sharedPrefs.edit().putString(MediaAutoUploadReceiver.KEY_SUBFOLDER, value).apply()
                    },
                    accounts = accounts,
                    activeAccountId = accounts.firstOrNull { it.serverUrl == serverUrl && it.username == username }?.id,
                    onAccountSelected = { profile ->
                        if (profile.serverUrl != serverUrl || profile.username != username) {
                            vm.disconnect { }
                            serverUrl = profile.serverUrl
                            username = profile.username
                            password = profile.password
                            sharedPrefs.edit().putString("server_url", profile.serverUrl)
                                .putString("username", profile.username)
                                .putString("password", profile.password).apply()
                            vm.connect(profile.serverUrl, profile.username, profile.password) {
                                saveAccount(profile.serverUrl, profile.username, profile.password)
                            }
                        }
                        showSettings = false
                    },
                    onDismiss = { showSettings = false }
                )
            }
        }
    }

    if (showTransferHistory) {
        Dialog(
            onDismissRequest = { showTransferHistory = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false)
        ) {
            Box(Modifier.fillMaxSize()) {
                TransferHistoryScreen(
                    onDismiss = { showTransferHistory = false },
                    onAction = { kind, id, action ->
                        coroutineScope.launch(Dispatchers.IO) {
                            val database = NextcloudDatabase.get(context)
                            if (kind == "upload") {
                                if (action == "retry") {
                                    database.uploads().retry(id)
                                    withContext(Dispatchers.Main) { UploadRepository.schedule(context) }
                                } else {
                                    database.uploads().cancel(id)
                                    withContext(Dispatchers.Main) { UploadRepository.cancel(context) }
                                }
                            } else {
                                if (action == "retry") {
                                    database.downloads().retry(id)
                                    withContext(Dispatchers.Main) { DownloadRepository.retry(context, id) }
                                } else {
                                    database.downloads().cancel(id)
                                    withContext(Dispatchers.Main) { DownloadRepository.cancel(context, id) }
                                }
                            }
                            withContext(Dispatchers.Main) { showTransferHistory = false }
                        }
                    }
                )
            }
        }
}

    if (showOfflineFiles) {
        Dialog(
            onDismissRequest = { showOfflineFiles = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false)
        ) {
            Box(Modifier.fillMaxSize()) {
                OfflineFilesScreen(
                    accountId = accounts.firstOrNull { it.serverUrl == serverUrl && it.username == username }?.id,
                    onDismiss = { showOfflineFiles = false }
                )
            }
        }
    }

    if (hostActivity?.isAppLocked == true) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Lock, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(20.dp))
                    Text("Nextcloud Extended is locked", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text("Authenticate to access your account", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { hostActivity.requestUnlock() }) { Text("Unlock") }
                }
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

private fun pendingUploadDirectory(context: Context): File =
    File(context.filesDir, "pending-uploads").apply { mkdirs() }
