package xyz.luna.nextcloudextended

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.CalendarInfo
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import xyz.luna.nextcloudextended.data.model.NextcloudNote
import xyz.luna.nextcloudextended.data.model.NextcloudTask
import xyz.luna.nextcloudextended.data.model.NextcloudContact
import xyz.luna.nextcloudextended.data.network.CalDavClient
import java.time.LocalDate

class NextcloudViewModel : ViewModel() {

    var isConnected by mutableStateOf(false)
    var client by mutableStateOf<CalDavClient?>(null)

    // Set once an automatic login with the stored credentials has been attempted. Living in
    // the ViewModel, it survives configuration changes (no re-trigger on rotation) but resets
    // on process death, so every fresh app start tries to reconnect once.
    var autoLoginAttempted = false

    var loadingCount by mutableIntStateOf(0)
    val isLoading get() = loadingCount > 0

    // Selected UI language — kept in sync by the UI; drives error-message localization.
    var language by mutableStateOf(AppLanguage.EN)
    private val s get() = stringsFor(language)

    var currentTab by mutableStateOf(HubTab.CALENDAR)
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
    var shareLink by mutableStateOf<String?>(null)

    private fun msg(e: Exception?): String = e?.message ?: ""

    // Single guarded decrement so a stray double-callback can never drive the spinner negative.
    private fun endLoad() { if (loadingCount > 0) loadingCount-- }

    // Cancel any in-flight HTTP calls when the ViewModel is destroyed (process death, etc.)
    // so their callbacks don't fire against a dead scope.
    override fun onCleared() {
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
        loadingCount++
        val collected = mutableListOf<CalendarEvent>()
        var done = 0
        hrefs.forEach { href ->
            c.getEvents(href,
                onSuccess = { evList ->
                    collected.addAll(evList)
                    done++
                    if (done == hrefs.size) {
                        events = collected.sortedBy { it.startTime ?: "" }
                        endLoad()
                    }
                },
                onFailure = { err ->
                    done++
                    if (done == hrefs.size) {
                        events = collected.sortedBy { it.startTime ?: "" }
                        endLoad()
                    }
                    errorMessage = s.calendarError(msg(err))
                }
            )
        }
    }

    fun refreshData() {
        val c = client ?: return
        loadingCount++
        when (currentTab) {
            HubTab.CALENDAR -> {
                c.getAllCalendarData(
                    onSuccess = { eventCals, _ ->
                        val oldHrefs = calendarInfos.map { it.href }.toSet()
                        val newHrefs = eventCals.map { it.href }.toSet()
                        calendarInfos = eventCals
                        activeCalendarHrefs = (activeCalendarHrefs intersect newHrefs) + (newHrefs - oldHrefs)
                        endLoad()
                        loadAllActiveCalendarsEvents()
                    },
                    onFailure = { err ->
                        errorMessage = s.calendarError(msg(err))
                        endLoad()
                        loadAllActiveCalendarsEvents()
                    }
                )
            }
            HubTab.TASKS -> {
                if (selectedTaskListHref.isNotEmpty()) {
                    c.getTasks(selectedTaskListHref,
                        onSuccess = { list ->
                            tasks = list.sortedWith(compareBy({ it.status == "COMPLETED" }, { it.due ?: "" }))
                            endLoad()
                        },
                        onFailure = { err -> errorMessage = s.tasksError(msg(err)); endLoad() }
                    )
                } else { endLoad() }
            }
            HubTab.NOTES -> {
                c.getNotes(
                    onSuccess = { list ->
                        notes = list.sortedWith(compareByDescending<NextcloudNote> { it.favorite }.thenByDescending { it.modified })
                        endLoad()
                    },
                    onFailure = { err -> errorMessage = s.notesError(msg(err)); endLoad() }
                )
            }
            HubTab.FILES -> {
                if (currentFolderPath.isNotEmpty()) {
                    c.getFiles(currentFolderPath,
                        onSuccess = { list ->
                            files = list.sortedWith(compareByDescending<NextcloudFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                            endLoad()
                        },
                        onFailure = { err -> errorMessage = s.filesError(msg(err)); endLoad() }
                    )
                } else { endLoad() }
            }
            HubTab.CONTACTS -> {
                if (selectedAddressBookHref.isEmpty()) {
                    endLoad()
                    loadAddressBooks()
                } else {
                    c.getContacts(selectedAddressBookHref,
                        onSuccess = { list ->
                            contacts = list.sortedBy { it.fullName.lowercase() }
                            endLoad()
                        },
                        onFailure = { err -> errorMessage = s.contactsError(msg(err)); endLoad() }
                    )
                }
            }
        }
    }

    private fun refreshAndStop() { refreshData(); endLoad() }

    fun connect(serverUrl: String, username: String, password: String, onSaveCredentials: () -> Unit) {
        loadingCount++
        val c = CalDavClient(serverUrl, username, password)
        client = c
        c.getAllCalendarData(
            onSuccess = { eventCals, taskListData ->
                onSaveCredentials()
                calendarInfos = eventCals
                activeCalendarHrefs = eventCals.map { it.href }.toSet()
                taskLists = taskListData
                isConnected = true
                if (taskListData.isNotEmpty()) {
                    val todo = taskListData.find { it.second.lowercase().contains("todo") || it.first.lowercase().contains("todo") } ?: taskListData[0]
                    selectedTaskListHref = todo.first
                    selectedTaskListName = todo.second
                }
                endLoad()
                refreshData()
            },
            onFailure = { err ->
                errorMessage = s.connectionFailed(msg(err))
                endLoad()
            }
        )
    }

    fun disconnect(onClearPrefs: () -> Unit) {
        client?.cancelAll()
        onClearPrefs()
        isConnected = false; client = null
        calendarInfos = emptyList(); activeCalendarHrefs = emptySet()
        taskLists = emptyList(); events = emptyList(); tasks = emptyList()
        notes = emptyList(); files = emptyList()
        currentFolderPath = ""; selectedTaskListHref = ""; selectedTaskListName = ""
        addressBooks = emptyList(); contacts = emptyList(); selectedAddressBookHref = ""; selectedAddressBookName = ""
    }

    fun loadTaskList(href: String, name: String) {
        selectedTaskListHref = href; selectedTaskListName = name
        loadingCount++
        client?.getTasks(href,
            onSuccess = { list ->
                tasks = list.sortedWith(compareBy({ it.status == "COMPLETED" }, { it.due ?: "" }))
                endLoad()
            },
            onFailure = { err -> errorMessage = s.tasksError(msg(err)); endLoad() }
        )
    }

    fun toggleTaskStatus(task: NextcloudTask) {
        val updated = if (task.status == "COMPLETED") "NEEDS-ACTION" else "COMPLETED"
        loadingCount++
        client?.saveTask(task.copy(status = updated),
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.taskUpdateFailed(msg(err)); endLoad() }
        )
    }

    fun createTask(uid: String, summary: String, description: String?, dueDate: String? = null) {
        loadingCount++
        client?.saveTask(NextcloudTask(uid, summary, description?.ifEmpty { null }, "NEEDS-ACTION", dueDate, selectedTaskListHref),
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.taskCreateFailed(msg(err)); endLoad() }
        )
    }

    fun editTask(task: NextcloudTask, summary: String, description: String?, dueDate: String?) {
        loadingCount++
        client?.saveTask(task.copy(summary = summary, description = description?.ifEmpty { null }, due = dueDate?.ifEmpty { null }),
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.taskEditFailed(msg(err)); endLoad() }
        )
    }

    fun deleteTask(task: NextcloudTask) {
        loadingCount++
        client?.deleteTask(task,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.taskDeleteFailed(msg(err)); endLoad() }
        )
    }

    fun deleteEvent(event: CalendarEvent) {
        loadingCount++
        client?.deleteEvent(event.calendarHref, event.id,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.eventDeleteFailed(msg(err)); endLoad() }
        )
    }

    fun createEvent(event: CalendarEvent, calendarHref: String) {
        loadingCount++
        client?.saveEvent(calendarHref, event,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.eventCreateFailed(msg(err)); endLoad() }
        )
    }

    fun editEvent(event: CalendarEvent, summary: String, description: String?, location: String?, startTime: String, endTime: String) {
        loadingCount++
        val updated = event.copy(summary = summary, description = description?.ifEmpty { null }, location = location?.ifEmpty { null }, startTime = startTime, endTime = endTime)
        client?.saveEvent(event.calendarHref, updated,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.eventEditFailed(msg(err)); endLoad() }
        )
    }

    fun createTaskList(name: String) {
        loadingCount++
        client?.createTaskList(name,
            onSuccess = { client?.getTaskLists(onSuccess = { list -> taskLists = list; endLoad() }, onFailure = { err -> errorMessage = s.listCreateFailed(msg(err)); endLoad() }) },
            onFailure = { err -> errorMessage = s.listCreateFailed(msg(err)); endLoad() }
        )
    }

    fun renameTaskList(newName: String) {
        loadingCount++
        client?.renameTaskList(selectedTaskListHref, newName,
            onSuccess = { selectedTaskListName = newName; client?.getTaskLists(onSuccess = { list -> taskLists = list; endLoad() }, onFailure = { err -> errorMessage = s.listRenameFailed(msg(err)); endLoad() }) },
            onFailure = { err -> errorMessage = s.listRenameFailed(msg(err)); endLoad() }
        )
    }

    fun deleteTaskList() {
        loadingCount++
        client?.deleteTaskList(selectedTaskListHref,
            onSuccess = { selectedTaskListHref = ""; selectedTaskListName = ""; tasks = emptyList(); client?.getTaskLists(onSuccess = { list -> taskLists = list; endLoad() }, onFailure = { err -> errorMessage = s.listDeleteFailed(msg(err)); endLoad() }) },
            onFailure = { err -> errorMessage = s.listDeleteFailed(msg(err)); endLoad() }
        )
    }

    fun toggleNoteFavorite(note: NextcloudNote) {
        loadingCount++
        client?.updateNote(note.id, note.title, note.content, note.category, !note.favorite,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.noteFavFailed(msg(err)); endLoad() }
        )
    }

    fun createNote(title: String, content: String, category: String) {
        loadingCount++
        client?.createNote(title, content, category,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.noteCreateFailed(msg(err)); endLoad() }
        )
    }

    fun updateNote(note: NextcloudNote, title: String, content: String, category: String) {
        loadingCount++
        client?.updateNote(note.id, title, content, category, note.favorite,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.noteUpdateFailed(msg(err)); endLoad() }
        )
    }

    fun deleteNote(noteId: Int) {
        loadingCount++
        client?.deleteNote(noteId,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.noteDeleteFailed(msg(err)); endLoad() }
        )
    }

    fun navigateToFolder(path: String) { currentFolderPath = path; refreshData() }

    fun navigateUp() {
        val decoded = try { java.net.URLDecoder.decode(currentFolderPath, "UTF-8") } catch (e: Exception) { currentFolderPath }
        val parts = decoded.trimEnd('/').split('/')
        if (parts.size > 5) {
            currentFolderPath = parts.take(parts.size - 1).joinToString("/") + "/"
            refreshData()
        }
    }

    fun deleteFile(path: String) {
        loadingCount++
        client?.deleteFile(path,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.fileDeleteFailed(msg(err)); endLoad() }
        )
    }

    fun renameFile(path: String, newName: String) {
        loadingCount++
        client?.renameFile(path, newName,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.fileRenameFailed(msg(err)); endLoad() }
        )
    }

    fun createFolder(name: String) {
        loadingCount++
        client?.createFolder(currentFolderPath, name,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.folderCreateError(msg(err)); endLoad() }
        )
    }

    fun uploadFile(fileName: String, bytes: ByteArray) {
        loadingCount++
        client?.uploadFile(currentFolderPath, fileName, bytes,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.uploadFailed(msg(err)); endLoad() }
        )
    }

    fun createShareLink(file: NextcloudFile) {
        loadingCount++
        client?.createShareLink(file.path,
            onSuccess = { url -> shareLink = url; endLoad() },
            onFailure = { err -> errorMessage = s.shareLinkFailed(msg(err)); endLoad() }
        )
    }

    fun getOnlineEditorUrl(fileHref: String, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        loadingCount++
        client?.getOnlineEditorUrl(fileHref,
            onSuccess = { url -> endLoad(); onSuccess(url) },
            onFailure = { err -> endLoad(); onFailure(err) }
        )
    }

    fun downloadFile(fileHref: String, onSuccess: (ByteArray) -> Unit) {
        loadingCount++
        client?.downloadFile(fileHref,
            onSuccess = { bytes -> endLoad(); onSuccess(bytes) },
            onFailure = { err -> errorMessage = s.downloadFailed(msg(err)); endLoad() }
        )
    }

    // ── Contacts (CardDAV) ──────────────────────────────────────────────────────

    fun loadAddressBooks() {
        val c = client ?: return
        loadingCount++
        c.getAddressBooks(
            onSuccess = { books ->
                addressBooks = books
                if (books.isNotEmpty() && selectedAddressBookHref.isEmpty()) {
                    val default = books.find { it.second.lowercase().contains("contact") || it.first.lowercase().contains("contact") } ?: books[0]
                    endLoad()
                    loadContactList(default.first, default.second)
                } else {
                    endLoad()
                }
            },
            onFailure = { err -> errorMessage = s.contactsError(msg(err)); endLoad() }
        )
    }

    fun loadContactList(href: String, name: String) {
        selectedAddressBookHref = href; selectedAddressBookName = name
        loadingCount++
        client?.getContacts(href,
            onSuccess = { list -> contacts = list.sortedBy { it.fullName.lowercase() }; endLoad() },
            onFailure = { err -> errorMessage = s.contactsError(msg(err)); endLoad() }
        )
    }

    fun createContact(draft: NextcloudContact) {
        val ab = selectedAddressBookHref
        if (ab.isEmpty()) { errorMessage = s.contactsError(""); return }
        loadingCount++
        val contact = draft.copy(uid = java.util.UUID.randomUUID().toString(), addressBookHref = ab, href = "", rawVcard = null)
        client?.saveContact(contact,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.contactCreateFailed(msg(err)); endLoad() }
        )
    }

    fun updateContact(contact: NextcloudContact) {
        loadingCount++
        client?.saveContact(contact,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.contactEditFailed(msg(err)); endLoad() }
        )
    }

    fun deleteContact(contact: NextcloudContact) {
        loadingCount++
        client?.deleteContact(contact,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.contactDeleteFailed(msg(err)); endLoad() }
        )
    }
}
