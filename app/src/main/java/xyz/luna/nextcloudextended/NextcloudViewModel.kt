package xyz.luna.nextcloudextended

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.luna.nextcloudextended.account.SecureStore
import xyz.luna.nextcloudextended.data.network.CertificateInfo
import xyz.luna.nextcloudextended.data.network.LoginFlowV2
import xyz.luna.nextcloudextended.data.network.TlsTrust
import xyz.luna.nextcloudextended.upload.OfflineOperationReplayer
import android.app.Application
import xyz.luna.nextcloudextended.data.model.ActivityItem
import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.CalendarInfo
import xyz.luna.nextcloudextended.data.model.FileVersion
import xyz.luna.nextcloudextended.data.model.NextcloudCapabilities
import xyz.luna.nextcloudextended.data.model.NextcloudContact
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import xyz.luna.nextcloudextended.data.model.NextcloudNote
import xyz.luna.nextcloudextended.data.model.NextcloudNotification
import xyz.luna.nextcloudextended.data.model.NextcloudShare
import xyz.luna.nextcloudextended.data.model.NextcloudTask
import xyz.luna.nextcloudextended.data.model.NotificationAction
import xyz.luna.nextcloudextended.data.model.Sharee
import xyz.luna.nextcloudextended.data.model.TrashedFile
import xyz.luna.nextcloudextended.data.model.UserInfo
import xyz.luna.nextcloudextended.data.network.CalDavClient
import xyz.luna.nextcloudextended.data.network.ConflictException
import xyz.luna.nextcloudextended.data.network.FailureKind
import xyz.luna.nextcloudextended.data.network.HttpStatusException
import xyz.luna.nextcloudextended.data.network.NotNextcloudException
import xyz.luna.nextcloudextended.data.network.RequestCancelledException
import xyz.luna.nextcloudextended.data.network.ServerAccess
import xyz.luna.nextcloudextended.data.network.UnexpectedResponseException
import xyz.luna.nextcloudextended.data.network.failureKind
import xyz.luna.nextcloudextended.data.network.normalizeServerInput
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate

class NextcloudViewModel(application: Application) : AndroidViewModel(application) {

    var isConnected by mutableStateOf(false)
    var client by mutableStateOf<CalDavClient?>(null)
    var serverCapabilities by mutableStateOf(NextcloudCapabilities.unavailable())

    /** Server-side account id used in DAV paths. Resolved at login; may differ from the login name. */
    var userId by mutableStateOf("")
    var userInfo by mutableStateOf<UserInfo?>(null)
    val filesRoot: String get() = "/remote.php/dav/files/$userId/"

    // Set once an automatic login with the stored credentials has been attempted. Living in
    // the ViewModel, it survives configuration changes (no re-trigger on rotation) but resets
    // on process death, so every fresh app start tries to reconnect once.
    var autoLoginAttempted = false

    var loadingCount by mutableIntStateOf(0)
    val isLoading get() = loadingCount > 0
    private var sessionGeneration = 0
    private var filesRequestGeneration = 0

    // Selected UI language — kept in sync by the UI; drives error-message localization.
    var language by mutableStateOf(AppLanguage.EN)
    private val s get() = stringsFor(language)
    private val x get() = extraFor(language)

    var currentTab by mutableStateOf(HubTab.FILES)
    var calendarViewMode by mutableStateOf(CalendarViewMode.MONTH)
    var selectedDate by mutableStateOf(LocalDate.now())

    // Calendar — multi-select support
    var calendarInfos by mutableStateOf<List<CalendarInfo>>(emptyList())
    var activeCalendarHrefs by mutableStateOf<Set<String>>(emptySet())
    var taskLists by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var selectedTaskListHref by mutableStateOf("")
    var selectedTaskListName by mutableStateOf("")
    var events by mutableStateOf<List<CalendarEvent>>(emptyList())
    var tasks by mutableStateOf<List<NextcloudTask>>(emptyList())
    var notes by mutableStateOf<List<NextcloudNote>>(emptyList())
    var currentFolderPath by mutableStateOf("")
    var files by mutableStateOf<List<NextcloudFile>>(emptyList())

    // Contacts — CardDAV
    var addressBooks by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var selectedAddressBookHref by mutableStateOf("")
    var selectedAddressBookName by mutableStateOf("")
    var contacts by mutableStateOf<List<NextcloudContact>>(emptyList())

    var officeViewerPref by mutableStateOf(OfficeViewerType.POI)
    var pinnedTabs by mutableStateOf(DEFAULT_PINNED_TABS)

    var errorMessage by mutableStateOf<String?>(null)
    /** Non-error feedback ("Restored", "Share updated") shown like [errorMessage]. */
    var infoMessage by mutableStateOf<String?>(null)
    var shareLink by mutableStateOf<String?>(null)
    var shares by mutableStateOf<List<NextcloudShare>>(emptyList())
    var sharesFilePath by mutableStateOf<String?>(null)
    var sharees by mutableStateOf<List<Sharee>>(emptyList())
    var conflictFile by mutableStateOf<ConflictFile?>(null)

    // Server-side file search results (null = no search active)
    var serverSearchResults by mutableStateOf<List<NextcloudFile>?>(null)
    var serverResultsLabel by mutableStateOf<String?>(null)
    var favoritesMode by mutableStateOf(false)

    // Certificate the user is asked about (self-signed / private CA), and what to do once they trust it
    var untrustedCertificate by mutableStateOf<CertificateInfo?>(null)
    var retryAfterTrust: (() -> Unit)? = null

    // Browser sign-in (Login flow v2)
    var browserLoginUrl by mutableStateOf<String?>(null)
    var browserLoginWaiting by mutableStateOf(false)
    var browserLoginResult by mutableStateOf<LoginFlowV2.Result?>(null)
    private var browserLoginJob: Job? = null

    // Official-client features
    var trash by mutableStateOf<List<TrashedFile>>(emptyList())
    var versions by mutableStateOf<List<FileVersion>>(emptyList())
    var versionsFile by mutableStateOf<NextcloudFile?>(null)
    var activity by mutableStateOf<List<ActivityItem>>(emptyList())
    var notifications by mutableStateOf<List<NextcloudNotification>>(emptyList())

    data class ConflictFile(
        val fileName: String,
        val localBytes: ByteArray?,
        val serverEtag: String?
    )

    /** Human readable explanation of any failure (never a raw "HTTP Error: 403"). */
    private fun msg(e: Throwable?): String = e?.let { x.describe(it) } ?: ""

    private fun isCancelled(e: Throwable?) = e?.failureKind() == FailureKind.CANCELLED

    // A 404 from the Notes API almost always means the Notes app is not installed or disabled
    // on the server — surface a targeted hint instead of the raw HTTP error.
    private fun notesFailure(err: Throwable?, fallback: (String) -> String): String =
        if (err?.failureKind() == FailureKind.NOT_FOUND) s.notesNotInstalled else fallback(msg(err))

    // Single guarded decrement so a stray double-callback can never drive the spinner negative.
    private fun endLoad() { if (loadingCount > 0) loadingCount-- }

    /**
     * Runs a blocking server call off the main thread and delivers the result here. Results that
     * arrive after a logout / account switch are dropped, and the spinner is always released.
     */
    private fun <T> call(
        work: CalDavClient.() -> T,
        onFailure: (Exception) -> Unit = { err -> errorMessage = msg(err) },
        onSuccess: (T) -> Unit
    ) {
        val c = client ?: return
        val generation = sessionGeneration
        loadingCount++
        c.async({ c.work() }, { value ->
            endLoad()
            if (generation == sessionGeneration) onSuccess(value)
        }, { err ->
            endLoad()
            if (generation == sessionGeneration && !isCancelled(err)) onFailure(err)
        })
    }

    // Cancel any in-flight HTTP calls when the ViewModel is destroyed (process death, etc.)
    // so their callbacks don't fire against a dead scope.
    override fun onCleared() {
        sessionGeneration++
        client?.cancelAll()
        super.onCleared()
    }

    fun toggleCalendar(href: String) {
        activeCalendarHrefs = if (href in activeCalendarHrefs) activeCalendarHrefs - href
                              else activeCalendarHrefs + href
        loadAllActiveCalendarsEvents()
    }

    fun loadAllActiveCalendarsEvents() {
        val c = client ?: return
        val hrefs = activeCalendarHrefs.toList()
        if (hrefs.isEmpty()) { events = emptyList(); return }
        val generation = sessionGeneration
        loadingCount++
        val collected = mutableListOf<CalendarEvent>()
        var done = 0
        var failure: Exception? = null
        fun finishOne() {
            done++
            if (done == hrefs.size) {
                endLoad()
                if (generation != sessionGeneration) return
                events = collected.sortedBy { it.startTime ?: "" }
                failure?.takeIf { !isCancelled(it) }?.let { errorMessage = s.calendarError(msg(it)) }
            }
        }
        hrefs.forEach { href ->
            c.getEvents(href,
                onSuccess = { evList -> collected.addAll(evList); finishOne() },
                onFailure = { err -> failure = failure ?: err; finishOne() }
            )
        }
    }

    /** Reloads calendars and task lists (one PROPFIND) and then the events of the visible calendars. */
    private fun loadCalendarData() {
        call({ groupware.calendars() }, onFailure = { err -> errorMessage = s.calendarError(msg(err)); loadAllActiveCalendarsEvents() }) { data ->
            val oldHrefs = calendarInfos.map { it.href }.toSet()
            val newHrefs = data.events.map { it.href }.toSet()
            calendarInfos = data.events
            activeCalendarHrefs = if (oldHrefs.isEmpty()) newHrefs else (activeCalendarHrefs intersect newHrefs) + (newHrefs - oldHrefs)
            taskLists = data.taskLists
            if (data.taskLists.none { it.first == selectedTaskListHref }) {
                val todo = data.taskLists.find { it.second.lowercase().contains("todo") || it.first.lowercase().contains("todo") } ?: data.taskLists.firstOrNull()
                selectedTaskListHref = todo?.first ?: ""
                selectedTaskListName = todo?.second ?: ""
            }
            loadAllActiveCalendarsEvents()
        }
    }

    fun refreshData() {
        if (client == null) return
        when (currentTab) {
            HubTab.CALENDAR -> loadCalendarData()
            HubTab.TASKS -> {
                if (selectedTaskListHref.isNotEmpty()) {
                    call({ groupware.tasks(selectedTaskListHref) }, onFailure = { err -> errorMessage = s.tasksError(msg(err)) }) { list ->
                        tasks = list.sortedWith(compareBy({ it.status == "COMPLETED" }, { it.due ?: "" }))
                    }
                } else loadCalendarData()
            }
            HubTab.NOTES -> {
                if (serverCapabilities.discovered && !serverCapabilities.has("notes")) {
                    notes = emptyList()
                    return
                }
                call({ groupware.notes() }, onFailure = { err -> errorMessage = notesFailure(err, s.notesError) }) { list ->
                    notes = list.sortedWith(compareByDescending<NextcloudNote> { it.favorite }.thenByDescending { it.modified })
                }
            }
            HubTab.FILES -> {
                if (favoritesMode) { showFavorites(); return }
                if (currentFolderPath.isEmpty() && userId.isNotEmpty()) currentFolderPath = filesRoot
                if (currentFolderPath.isNotEmpty()) {
                    val requestedPath = currentFolderPath
                    val requestGeneration = ++filesRequestGeneration
                    call({ files.list(requestedPath) }, onFailure = { err ->
                        if (requestGeneration == filesRequestGeneration && requestedPath == currentFolderPath) {
                            errorMessage = s.filesError(msg(err))
                        }
                    }) { list ->
                        if (requestGeneration == filesRequestGeneration && requestedPath == currentFolderPath) {
                            files = list.sortedWith(compareByDescending<NextcloudFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                        }
                    }
                }
            }
            HubTab.CONTACTS -> {
                if (selectedAddressBookHref.isEmpty()) loadAddressBooks()
                else call({ groupware.contacts(selectedAddressBookHref) }, onFailure = { err -> errorMessage = s.contactsError(msg(err)) }) { list ->
                    contacts = list.sortedBy { it.fullName.lowercase() }
                }
            }
        }
    }

    private fun refreshAndStop() { refreshData() }

    private class Handshake(val client: CalDavClient, val userId: String, val user: UserInfo?, val capabilities: NextcloudCapabilities?)

    /**
     * Validates the server and the credentials, resolves the real account id and capabilities, then
     * opens the session. Everything that can fail does so here, with a message that says why.
     */
    fun connect(serverUrl: String, username: String, password: String, onConnected: (resolvedServerUrl: String, userId: String) -> Unit) {
        val generation = ++sessionGeneration
        client?.cancelAll()
        serverCapabilities = NextcloudCapabilities.unavailable()
        // A malformed address must produce a message, not an exception on the main thread.
        val first = try { CalDavClient(serverUrl, username, password) } catch (e: IllegalArgumentException) {
            errorMessage = s.connectionFailed(e.message ?: x.errHostUnreachable)
            return
        }
        loadingCount++
        client = first
        first.async({ handshake(first, serverUrl, username, password) }, { result ->
            endLoad()
            if (generation != sessionGeneration) return@async
            client = result.client
            userId = result.userId
            userInfo = result.user
            serverCapabilities = result.capabilities ?: NextcloudCapabilities.unavailable()
            isConnected = true
            if (currentFolderPath.isEmpty() || !currentFolderPath.startsWith(filesRoot)) currentFolderPath = filesRoot
            onConnected(result.client.baseUrl, result.userId)
            refreshData()
        }, { err ->
            endLoad()
            if (generation == sessionGeneration && !isCancelled(err)) {
                if (!askToTrustCertificate(err, serverUrl) { connect(serverUrl, username, password, onConnected) }) {
                    errorMessage = s.connectionFailed(msg(err))
                }
            }
        })
    }

    /** When a TLS failure comes from an untrusted certificate, show it to the user instead of an error. */
    private fun askToTrustCertificate(error: Throwable, serverUrl: String, retry: () -> Unit): Boolean {
        if (error.failureKind() != FailureKind.TLS) return false
        val host = runCatching { java.net.URI(normalizeServerInput(serverUrl)).host }.getOrNull() ?: return false
        val info = TlsTrust.describeRejected(host) ?: return false
        retryAfterTrust = retry
        untrustedCertificate = info
        return true
    }

    fun trustCertificate(info: CertificateInfo) {
        TlsTrust.trust(info)
        untrustedCertificate = null
        retryAfterTrust?.invoke()
        retryAfterTrust = null
    }

    fun declineCertificate() { untrustedCertificate = null; retryAfterTrust = null }

    private fun handshake(c: CalDavClient, serverUrl: String, username: String, password: String): Handshake {
        var active = c
        try {
            val status = ServerAccess.probe(serverUrl)
            if (status.maintenance) throw HttpStatusException(503, maintenance = true)
            if (!status.installed) throw NotNextcloudException("Nextcloud is not installed on this server")
            val moved = normalizeServerInput(status.baseUrl)
            if (moved.startsWith("https://", ignoreCase = true) && !moved.equals(c.baseUrl, ignoreCase = true)) {
                // The address redirects (www., sub-folder move…): talk to the final location directly.
                active = CalDavClient(moved, username, password)
            }
        } catch (e: HttpStatusException) {
            if (e.failureKind() == FailureKind.MAINTENANCE) throw e
            // status.php hidden or blocked by a reverse proxy: not fatal, the credential check decides.
        } catch (e: NotNextcloudException) {
            if (e.message?.contains("not installed") == true) throw e
        } catch (e: UnexpectedResponseException) {
            // not fatal, see above
        }

        var user: UserInfo? = null
        try {
            user = active.ocs.currentUser()
        } catch (e: HttpStatusException) {
            if (e.code == 401) throw e
        } catch (e: UnexpectedResponseException) {
            // OCS filtered by a proxy: fall back to the login name and verify through WebDAV below.
        }
        val id = user?.id ?: username
        active.useUserId(id)
        if (user == null && active.files.stat(active.session.filesRoot) == null) {
            throw HttpStatusException(404, "The account folder was not found for user \"$id\"")
        }
        val capabilities = runCatching { active.ocs.capabilities() }.getOrNull()
        return Handshake(active, id, user, capabilities)
    }

    fun disconnect(onClearPrefs: () -> Unit) {
        sessionGeneration++
        filesRequestGeneration++
        client?.cancelAll()
        onClearPrefs()
        isConnected = false; client = null
        userId = ""; userInfo = null
        serverCapabilities = NextcloudCapabilities.unavailable()
        calendarInfos = emptyList(); activeCalendarHrefs = emptySet()
        taskLists = emptyList(); events = emptyList(); tasks = emptyList()
        notes = emptyList(); files = emptyList()
        currentFolderPath = ""; selectedTaskListHref = ""; selectedTaskListName = ""
        addressBooks = emptyList(); contacts = emptyList(); selectedAddressBookHref = ""; selectedAddressBookName = ""
        trash = emptyList(); versions = emptyList(); versionsFile = null; activity = emptyList(); notifications = emptyList()
        shares = emptyList(); sharees = emptyList(); shareLink = null; sharesFilePath = null
        serverSearchResults = null
        loadingCount = 0
    }

    // ── Tasks ───────────────────────────────────────────────────────────────────────────────

    fun loadTaskList(href: String, name: String) {
        selectedTaskListHref = href; selectedTaskListName = name
        call({ groupware.tasks(href) }, onFailure = { err -> errorMessage = s.tasksError(msg(err)) }) { list ->
            tasks = list.sortedWith(compareBy({ it.status == "COMPLETED" }, { it.due ?: "" }))
        }
    }

    /** Event and task times are converted when read, so a time zone change needs a fresh read. */
    fun reloadForTimeZoneChange() {
        loadAllActiveCalendarsEvents()
        if (selectedTaskListHref.isNotEmpty()) loadTaskList(selectedTaskListHref, selectedTaskListName)
    }

    fun toggleTaskStatus(task: NextcloudTask) {
        val updated = if (task.status == "COMPLETED") "NEEDS-ACTION" else "COMPLETED"
        call({ groupware.saveTask(task.copy(status = updated)) }, onFailure = { err -> errorMessage = s.taskUpdateFailed(msg(err)); refreshAndStop() }) { refreshAndStop() }
    }

    fun createTask(uid: String, summary: String, description: String?, dueDate: String? = null) {
        val task = NextcloudTask(uid, summary, description?.ifEmpty { null }, "NEEDS-ACTION", dueDate, selectedTaskListHref)
        call({ groupware.saveTask(task) }, onFailure = { err -> errorMessage = s.taskCreateFailed(msg(err)) }) { refreshAndStop() }
    }

    fun editTask(task: NextcloudTask, summary: String, description: String?, dueDate: String?) {
        val updated = task.copy(summary = summary, description = description?.ifEmpty { null }, due = dueDate?.ifEmpty { null })
        call({ groupware.saveTask(updated) }, onFailure = { err -> errorMessage = s.taskEditFailed(msg(err)) }) { refreshAndStop() }
    }

    fun deleteTask(task: NextcloudTask) {
        call({ groupware.deleteCalendarObject(task.href, task.calendarHref, task.uid) }, onFailure = { err -> errorMessage = s.taskDeleteFailed(msg(err)) }) { refreshAndStop() }
    }

    fun deleteEvent(event: CalendarEvent) {
        call({ groupware.deleteCalendarObject(event.href, event.calendarHref, event.id) }, onFailure = { err -> errorMessage = s.eventDeleteFailed(msg(err)) }) { refreshAndStop() }
    }

    fun createEvent(event: CalendarEvent, calendarHref: String) {
        call({ groupware.saveEvent(calendarHref, event) }, onFailure = { err -> errorMessage = s.eventCreateFailed(msg(err)) }) { refreshAndStop() }
    }

    fun editEvent(event: CalendarEvent, summary: String, description: String?, location: String?, startTime: String, endTime: String) {
        val updated = event.copy(summary = summary, description = description?.ifEmpty { null }, location = location?.ifEmpty { null }, startTime = startTime, endTime = endTime)
        call({ groupware.saveEvent(event.calendarHref, updated) }, onFailure = { err -> errorMessage = s.eventEditFailed(msg(err)) }) { refreshAndStop() }
    }

    private fun reloadTaskLists(onFailure: (Exception) -> Unit) {
        call({ groupware.calendars().taskLists }, onFailure = onFailure) { list -> taskLists = list }
    }

    fun createTaskList(name: String) {
        val fail = { err: Exception -> errorMessage = s.listCreateFailed(msg(err)) }
        call({ groupware.createTaskList(name) }, onFailure = fail) { reloadTaskLists(fail) }
    }

    fun renameTaskList(newName: String) {
        val fail = { err: Exception -> errorMessage = s.listRenameFailed(msg(err)) }
        val href = selectedTaskListHref
        call({ groupware.renameTaskList(href, newName) }, onFailure = fail) { selectedTaskListName = newName; reloadTaskLists(fail) }
    }

    fun deleteTaskList() {
        val fail = { err: Exception -> errorMessage = s.listDeleteFailed(msg(err)) }
        val href = selectedTaskListHref
        call({ groupware.deleteTaskList(href) }, onFailure = fail) {
            selectedTaskListHref = ""; selectedTaskListName = ""; tasks = emptyList(); reloadTaskLists(fail)
        }
    }

    // ── Notes ───────────────────────────────────────────────────────────────────────────────

    fun toggleNoteFavorite(note: NextcloudNote) {
        call({ groupware.updateNote(note.id, note.title, note.content, note.category, !note.favorite) },
            onFailure = { err -> errorMessage = notesFailure(err, s.noteFavFailed) }) { refreshAndStop() }
    }

    fun createNote(title: String, content: String, category: String) {
        call({ groupware.createNote(title, content, category) },
            onFailure = { err -> errorMessage = notesFailure(err, s.noteCreateFailed) }) { refreshAndStop() }
    }

    fun updateNote(note: NextcloudNote, title: String, content: String, category: String) {
        call({ groupware.updateNote(note.id, title, content, category, note.favorite) },
            onFailure = { err -> errorMessage = notesFailure(err, s.noteUpdateFailed) }) { refreshAndStop() }
    }

    fun deleteNote(noteId: Int) {
        call({ groupware.deleteNote(noteId) }, onFailure = { err -> errorMessage = notesFailure(err, s.noteDeleteFailed) }) { refreshAndStop() }
    }

    // ── Files ───────────────────────────────────────────────────────────────────────────────

    fun navigateToFolder(path: String) {
        clearServerSearch()
        filesRequestGeneration++
        currentFolderPath = path
        refreshData()
    }

    fun navigateUp() {
        // Paths are stored decoded. Decoding again (URLDecoder) turned a literal '+' into a space.
        val root = filesRoot.trimEnd('/')
        val current = currentFolderPath.trimEnd('/')
        if (favoritesMode || serverSearchResults != null) { clearServerSearch(); return }
        if (current.length > root.length && current.startsWith("$root/")) {
            filesRequestGeneration++
            currentFolderPath = current.substringBeforeLast('/') + "/"
            refreshData()
        }
    }

    private fun isConnectivityFailure(error: Throwable) = error.failureKind().let {
        it == FailureKind.NO_NETWORK || it == FailureKind.HOST_UNREACHABLE || it == FailureKind.TIMEOUT
    }

    /** Keeps an action the user asked for while the connection was down, to be replayed by WorkManager. */
    private fun queueOffline(enqueue: suspend (android.content.Context, String) -> Unit): Boolean {
        val app = getApplication<Application>()
        val accountId = SecureStore.activeProfile(app)?.id ?: return false
        viewModelScope.launch {
            withContext(Dispatchers.IO) { enqueue(app, accountId) }
            infoMessage = x.queuedOffline
        }
        return true
    }

    fun deleteFile(path: String) {
        call({ files.delete(path) }, onFailure = { err ->
            if (!(isConnectivityFailure(err) && queueOffline { ctx, id -> OfflineOperationReplayer.enqueueDelete(ctx, id, path) })) {
                errorMessage = s.fileDeleteFailed(msg(err))
            } else files = files.filterNot { it.path == path }
        }) { refreshAndStop() }
    }

    fun renameFile(path: String, newName: String) {
        call({ files.rename(path, newName) }, onFailure = { err ->
            if (!(isConnectivityFailure(err) && queueOffline { ctx, id -> OfflineOperationReplayer.enqueueRename(ctx, id, path, newName) })) {
                errorMessage = s.fileRenameFailed(msg(err))
            }
        }) { refreshAndStop() }
    }

    fun transferFile(path: String, destination: String, copy: Boolean) {
        call({ if (copy) files.copy(path, destination) else files.move(path, destination) },
            onFailure = { err -> errorMessage = s.fileRenameFailed(msg(err)) }) { refreshAndStop() }
    }

    fun createFolder(name: String) {
        val parent = currentFolderPath
        call({
            require(xyz.luna.nextcloudextended.data.network.isValidDavName(name)) { "Invalid folder name" }
            if (!files.mkdir(parent.trimEnd('/') + "/" + name, createParents = true)) throw HttpStatusException(405, "Folder already exists")
        }, onFailure = { err ->
            if (!(isConnectivityFailure(err) && queueOffline { ctx, id -> OfflineOperationReplayer.enqueueCreateFolder(ctx, id, parent, name) })) {
                errorMessage = s.folderCreateError(msg(err))
            }
        }) { refreshAndStop() }
    }

    fun searchFiles(query: String) {
        val scope = currentFolderPath.ifEmpty { filesRoot }
        call({ files.search(query, scope) }, onFailure = { err -> errorMessage = s.filesError(msg(err)) }) {
            serverResultsLabel = query
            serverSearchResults = it
        }
    }

    fun clearServerSearch() { serverSearchResults = null; serverResultsLabel = null; favoritesMode = false }

    fun showFavorites() {
        call({ files.favorites() }, onFailure = { err -> errorMessage = s.filesError(msg(err)) }) {
            favoritesMode = true
            serverResultsLabel = null
            serverSearchResults = it
        }
    }

    /**
     * Sign in through the system browser (Login flow v2) so two-factor authentication, SSO and passkeys
     * work. The UI opens [browserLoginUrl], then consumes [browserLoginResult] to finish the login.
     */
    fun startBrowserLogin(rawUrl: String) {
        val url = normalizeServerInput(rawUrl)
        if (url.isEmpty()) { errorMessage = s.fillAllFields; return }
        if (!url.startsWith("https://", ignoreCase = true)) { errorMessage = s.insecureHttpBlocked; return }
        browserLoginJob?.cancel()
        browserLoginWaiting = true
        browserLoginJob = viewModelScope.launch(Dispatchers.IO) {
            val self = coroutineContext[Job]
            try {
                val status = ServerAccess.probe(url)
                if (status.maintenance) throw HttpStatusException(503, maintenance = true)
                val flow = LoginFlowV2(status.baseUrl)
                val start = flow.start()
                withContext(Dispatchers.Main) { browserLoginUrl = start.loginUrl }
                val result = flow.awaitResult(start, isCancelled = { self?.isActive != true })
                withContext(Dispatchers.Main) {
                    browserLoginWaiting = false
                    if (result != null) browserLoginResult = result else if (self?.isActive == true) errorMessage = x.loginBrowserFailed
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    browserLoginWaiting = false
                    if (!askToTrustCertificate(e, url) { startBrowserLogin(rawUrl) }) errorMessage = msg(e)
                }
            }
        }
    }

    fun cancelBrowserLogin() {
        browserLoginJob?.cancel()
        browserLoginWaiting = false
        browserLoginUrl = null
    }

    fun toggleFavorite(file: NextcloudFile) {
        val target = !file.favorite
        call({ files.setFavorite(file.path, target) }) {
            files = files.map { if (it.path == file.path) it.copy(favorite = target) else it }
        }
    }

    fun uploadFile(fileName: String, contentLength: Long?, openStream: () -> InputStream) {
        val c = client ?: return
        val generation = sessionGeneration
        loadingCount++
        c.uploadFile(currentFolderPath, fileName, contentLength, openStream,
            onSuccess = { endLoad(); refreshAndStop() },
            onFailure = { err ->
                endLoad()
                if (generation != sessionGeneration) return@uploadFile
                if (err is ConflictException) {
                    val bytes = runCatching {
                        val buf = java.io.ByteArrayOutputStream()
                        openStream().use { input -> input.copyTo(buf) }
                        buf.toByteArray()
                    }.getOrNull()
                    conflictFile = ConflictFile(fileName, bytes, err.serverEtag)
                } else if (!isCancelled(err)) {
                    errorMessage = s.uploadFailed(msg(err))
                }
            }
        )
    }

    fun resolveConflictOverwrite(fileName: String, localBytes: ByteArray?) {
        if (localBytes == null) { conflictFile = null; return }
        val c = client ?: return
        loadingCount++
        c.uploadFile(currentFolderPath, fileName, localBytes.size.toLong(), { localBytes.inputStream() },
            onSuccess = { endLoad(); conflictFile = null; refreshAndStop() },
            onFailure = { err -> endLoad(); if (!isCancelled(err)) errorMessage = s.uploadFailed(msg(err)) },
            overwrite = true
        )
    }

    fun resolveConflictRename(fileName: String, localBytes: ByteArray?) {
        if (localBytes == null) { conflictFile = null; return }
        val c = client ?: return
        val renamed = "${fileName.substringBeforeLast('.')} (conflict ${System.currentTimeMillis()}).${fileName.substringAfterLast('.', "bin")}"
        loadingCount++
        c.uploadFile(currentFolderPath, renamed, localBytes.size.toLong(), { localBytes.inputStream() },
            onSuccess = { endLoad(); conflictFile = null; refreshAndStop() },
            onFailure = { err -> endLoad(); if (!isCancelled(err)) errorMessage = s.uploadFailed(msg(err)) }
        )
    }

    fun dismissConflict() { conflictFile = null }

    // ── Sharing ─────────────────────────────────────────────────────────────────────────────

    fun createShareLink(file: NextcloudFile) {
        call({
            ocs.createShare(file.path, NextcloudShare.TYPE_LINK).url ?: throw UnexpectedResponseException("The server did not return a link")
        }, onFailure = { err -> errorMessage = s.shareLinkFailed(msg(err)) }) { url -> shareLink = url }
    }

    fun loadShares(file: NextcloudFile) {
        sharesFilePath = file.path
        call({ ocs.shares(file.path) }, onFailure = { err -> errorMessage = s.shareLinkFailed(msg(err)) }) { result -> shares = result }
    }

    fun revokeShare(share: NextcloudShare) {
        call({ ocs.deleteShare(share.id) }, onFailure = { err -> errorMessage = s.shareLinkFailed(msg(err)) }) {
            shares = shares.filterNot { it.id == share.id }
        }
    }

    fun searchSharees(query: String) {
        if (query.length < 2) { sharees = emptyList(); return }
        call({ ocs.searchSharees(query) }, onFailure = { sharees = emptyList() }) { sharees = it }
    }

    fun shareWith(file: NextcloudFile, sharee: Sharee, canEdit: Boolean) {
        val permissions = if (canEdit) NextcloudShare.PERM_EDIT else NextcloudShare.PERM_READ_ONLY
        call({ ocs.createShare(file.path, sharee.shareType, sharee.shareWith, permissions) },
            onFailure = { err -> errorMessage = s.shareLinkFailed(msg(err)) }) { created ->
            shares = shares + created
            sharees = emptyList()
        }
    }

    fun createProtectedLink(file: NextcloudFile, password: String?, expireDate: String?, canEdit: Boolean) {
        val permissions = if (canEdit) NextcloudShare.PERM_EDIT else NextcloudShare.PERM_READ_ONLY
        call({ ocs.createShare(file.path, NextcloudShare.TYPE_LINK, permissions = permissions, password = password, expireDate = expireDate) },
            onFailure = { err -> errorMessage = s.shareLinkFailed(msg(err)) }) { created ->
            shares = shares + created
            created.url?.let { shareLink = it }
        }
    }

    fun updateShare(share: NextcloudShare, permissions: Int? = null, password: String? = null, expireDate: String? = null, note: String? = null, hideDownload: Boolean? = null) {
        call({ ocs.updateShare(share.id, permissions, password, expireDate, note, hideDownload = hideDownload) },
            onFailure = { err -> errorMessage = s.shareLinkFailed(msg(err)) }) { updated ->
            if (updated != null) shares = shares.map { if (it.id == share.id) updated else it }
            infoMessage = x.shareUpdated
        }
    }

    fun getOnlineEditorUrl(fileHref: String, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        call({ ocs.directEditingUrl(fileHref) }, onFailure = onFailure, onSuccess = onSuccess)
    }

    fun downloadFile(fileHref: String, onSuccess: (ByteArray) -> Unit) {
        call({ files.readBytes(fileHref, CalDavClient.MAX_IN_MEMORY_FILE_BYTES) },
            onFailure = { err -> errorMessage = s.downloadFailed(msg(err)) }, onSuccess = onSuccess)
    }

    /**
     * Resumable download into the cache directory (no in-memory size limit). Used by the PDF/Office
     * previews and anywhere a real file is needed.
     */
    fun downloadFileToCache(
        fileName: String,
        fileHref: String,
        onSuccess: (File) -> Unit,
        onFailure: (Exception) -> Unit = { err -> errorMessage = s.downloadFailed(msg(err)) }
    ) {
        val dir = File(getApplication<Application>().cacheDir, "previews").apply { mkdirs() }
        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        val target = File(dir, safeName)
        call({ files.download(fileHref, target); target }, onFailure = onFailure, onSuccess = onSuccess)
    }

    // ── Trash bin, versions, activity, notifications, account info ──────────────────────────

    fun loadTrash() {
        call({ extras.listTrash() }) { trash = it }
    }

    fun restoreFromTrash(item: TrashedFile) {
        call({ extras.restore(item) }) { trash = trash.filterNot { it.path == item.path }; infoMessage = x.trashRestored }
    }

    fun deleteFromTrash(item: TrashedFile) {
        call({ extras.deletePermanently(item) }) { trash = trash.filterNot { it.path == item.path } }
    }

    fun emptyTrash() {
        call({ extras.emptyTrash() }) { trash = emptyList() }
    }

    fun loadVersions(file: NextcloudFile) {
        val id = file.fileId ?: return
        versionsFile = file
        call({ extras.listVersions(id) }) { versions = it }
    }

    fun restoreVersion(version: FileVersion) {
        call({ extras.restoreVersion(version) }) { infoMessage = x.versionRestored; versionsFile = null; versions = emptyList(); refreshAndStop() }
    }

    fun loadActivity() {
        call({ ocs.activity(60) }) { activity = it }
    }

    fun loadNotifications() {
        call({ ocs.notifications() }, onFailure = { notifications = emptyList() }) { notifications = it }
    }

    fun dismissNotification(n: NextcloudNotification) {
        call({ ocs.deleteNotification(n.id) }) { notifications = notifications.filterNot { it.id == n.id } }
    }

    fun clearNotifications() {
        call({ ocs.deleteAllNotifications() }) { notifications = emptyList() }
    }

    fun runNotificationAction(n: NextcloudNotification, action: NotificationAction) {
        call({ ocs.runNotificationAction(action) }) { notifications = notifications.filterNot { it.id == n.id } }
    }

    fun refreshUserInfo() {
        call({ ocs.currentUser() }, onFailure = { }) { userInfo = it }
    }

    // ── Contacts (CardDAV) ──────────────────────────────────────────────────────────────────

    fun loadAddressBooks() {
        call({ groupware.addressBooks() }, onFailure = { err -> errorMessage = s.contactsError(msg(err)) }) { books ->
            addressBooks = books
            if (books.isNotEmpty() && selectedAddressBookHref.isEmpty()) {
                val default = books.find { it.second.lowercase().contains("contact") || it.first.lowercase().contains("contact") } ?: books[0]
                loadContactList(default.first, default.second)
            }
        }
    }

    fun loadContactList(href: String, name: String) {
        selectedAddressBookHref = href; selectedAddressBookName = name
        call({ groupware.contacts(href) }, onFailure = { err -> errorMessage = s.contactsError(msg(err)) }) { list ->
            contacts = list.sortedBy { it.fullName.lowercase() }
        }
    }

    fun createContact(draft: NextcloudContact) {
        val ab = selectedAddressBookHref
        if (ab.isEmpty()) { errorMessage = s.contactsError(""); return }
        val contact = draft.copy(uid = java.util.UUID.randomUUID().toString(), addressBookHref = ab, href = "", rawVcard = null, etag = "")
        call({ groupware.saveContact(contact) }, onFailure = { err -> errorMessage = s.contactCreateFailed(msg(err)) }) { refreshAndStop() }
    }

    fun updateContact(contact: NextcloudContact) {
        call({ groupware.saveContact(contact) }, onFailure = { err ->
            errorMessage = if (err is ConflictException) x.errPrecondition else s.contactEditFailed(msg(err))
            if (err is ConflictException) refreshAndStop()
        }) { refreshAndStop() }
    }

    fun deleteContact(contact: NextcloudContact) {
        call({ groupware.deleteContact(contact) }, onFailure = { err -> errorMessage = s.contactDeleteFailed(msg(err)) }) { refreshAndStop() }
    }
}
