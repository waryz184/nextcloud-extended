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
import xyz.luna.nextcloudextended.data.network.CalDavClient
import java.time.LocalDate

class NextcloudViewModel : ViewModel() {

    var isConnected by mutableStateOf(false)
    var client by mutableStateOf<CalDavClient?>(null)

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

    var errorMessage by mutableStateOf<String?>(null)
    var shareLink by mutableStateOf<String?>(null)

    private fun msg(e: Exception?): String = e?.message ?: ""

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
                        if (loadingCount > 0) loadingCount--
                    }
                },
                onFailure = { err ->
                    done++
                    if (done == hrefs.size) {
                        events = collected.sortedBy { it.startTime ?: "" }
                        if (loadingCount > 0) loadingCount--
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
                if (loadingCount > 0) loadingCount--
                loadAllActiveCalendarsEvents()
            }
            HubTab.TASKS -> {
                if (selectedTaskListHref.isNotEmpty()) {
                    c.getTasks(selectedTaskListHref,
                        onSuccess = { list ->
                            tasks = list.sortedWith(compareBy({ it.status == "COMPLETED" }, { it.due ?: "" }))
                            if (loadingCount > 0) loadingCount--
                        },
                        onFailure = { err -> errorMessage = s.tasksError(msg(err)); if (loadingCount > 0) loadingCount-- }
                    )
                } else { if (loadingCount > 0) loadingCount-- }
            }
            HubTab.NOTES -> {
                c.getNotes(
                    onSuccess = { list ->
                        notes = list.sortedWith(compareByDescending<NextcloudNote> { it.favorite }.thenByDescending { it.modified })
                        if (loadingCount > 0) loadingCount--
                    },
                    onFailure = { err -> errorMessage = s.notesError(msg(err)); if (loadingCount > 0) loadingCount-- }
                )
            }
            HubTab.FILES -> {
                if (currentFolderPath.isNotEmpty()) {
                    c.getFiles(currentFolderPath,
                        onSuccess = { list ->
                            files = list.sortedWith(compareByDescending<NextcloudFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                            if (loadingCount > 0) loadingCount--
                        },
                        onFailure = { err -> errorMessage = s.filesError(msg(err)); if (loadingCount > 0) loadingCount-- }
                    )
                } else { if (loadingCount > 0) loadingCount-- }
            }
        }
    }

    private fun refreshAndStop() { refreshData(); if (loadingCount > 0) loadingCount-- }

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
                if (loadingCount > 0) loadingCount--
                refreshData()
            },
            onFailure = { err ->
                errorMessage = s.connectionFailed(msg(err))
                if (loadingCount > 0) loadingCount--
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
    }

    fun loadTaskList(href: String, name: String) {
        selectedTaskListHref = href; selectedTaskListName = name
        loadingCount++
        client?.getTasks(href,
            onSuccess = { list ->
                tasks = list.sortedWith(compareBy({ it.status == "COMPLETED" }, { it.due ?: "" }))
                if (loadingCount > 0) loadingCount--
            },
            onFailure = { err -> errorMessage = s.tasksError(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun toggleTaskStatus(task: NextcloudTask) {
        val updated = if (task.status == "COMPLETED") "NEEDS-ACTION" else "COMPLETED"
        loadingCount++
        client?.saveTask(task.copy(status = updated),
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.taskUpdateFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun createTask(uid: String, summary: String, description: String?, dueDate: String? = null) {
        loadingCount++
        client?.saveTask(NextcloudTask(uid, summary, description?.ifEmpty { null }, "NEEDS-ACTION", dueDate, selectedTaskListHref),
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.taskCreateFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun editTask(task: NextcloudTask, summary: String, description: String?, dueDate: String?) {
        loadingCount++
        client?.saveTask(task.copy(summary = summary, description = description?.ifEmpty { null }, due = dueDate?.ifEmpty { null }),
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.taskEditFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun deleteTask(task: NextcloudTask) {
        loadingCount++
        client?.deleteTask(task,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.taskDeleteFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun deleteEvent(event: CalendarEvent) {
        loadingCount++
        client?.deleteEvent(event.calendarHref, event.id,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.eventDeleteFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun createEvent(event: CalendarEvent, calendarHref: String) {
        loadingCount++
        client?.saveEvent(calendarHref, event,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.eventCreateFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun editEvent(event: CalendarEvent, summary: String, description: String?, location: String?, startTime: String, endTime: String) {
        loadingCount++
        val updated = event.copy(summary = summary, description = description?.ifEmpty { null }, location = location?.ifEmpty { null }, startTime = startTime, endTime = endTime)
        client?.saveEvent(event.calendarHref, updated,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.eventEditFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun createTaskList(name: String) {
        loadingCount++
        client?.createTaskList(name,
            onSuccess = { client?.getTaskLists(onSuccess = { list -> taskLists = list; if (loadingCount > 0) loadingCount-- }, onFailure = { err -> errorMessage = s.listCreateFailed(msg(err)); if (loadingCount > 0) loadingCount-- }) },
            onFailure = { err -> errorMessage = s.listCreateFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun renameTaskList(newName: String) {
        loadingCount++
        client?.renameTaskList(selectedTaskListHref, newName,
            onSuccess = { selectedTaskListName = newName; client?.getTaskLists(onSuccess = { list -> taskLists = list; if (loadingCount > 0) loadingCount-- }, onFailure = { err -> errorMessage = s.listRenameFailed(msg(err)); if (loadingCount > 0) loadingCount-- }) },
            onFailure = { err -> errorMessage = s.listRenameFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun deleteTaskList() {
        loadingCount++
        client?.deleteTaskList(selectedTaskListHref,
            onSuccess = { selectedTaskListHref = ""; selectedTaskListName = ""; tasks = emptyList(); client?.getTaskLists(onSuccess = { list -> taskLists = list; if (loadingCount > 0) loadingCount-- }, onFailure = { err -> errorMessage = s.listDeleteFailed(msg(err)); if (loadingCount > 0) loadingCount-- }) },
            onFailure = { err -> errorMessage = s.listDeleteFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun toggleNoteFavorite(note: NextcloudNote) {
        loadingCount++
        client?.updateNote(note.id, note.title, note.content, note.category, !note.favorite,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.noteFavFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun createNote(title: String, content: String, category: String) {
        loadingCount++
        client?.createNote(title, content, category,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.noteCreateFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun updateNote(note: NextcloudNote, title: String, content: String, category: String) {
        loadingCount++
        client?.updateNote(note.id, title, content, category, note.favorite,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.noteUpdateFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun deleteNote(noteId: Int) {
        loadingCount++
        client?.deleteNote(noteId,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.noteDeleteFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
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
            onFailure = { err -> errorMessage = s.fileDeleteFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun renameFile(path: String, newName: String) {
        loadingCount++
        client?.renameFile(path, newName,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.fileRenameFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun createFolder(name: String) {
        loadingCount++
        client?.createFolder(currentFolderPath, name,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.folderCreateError(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun uploadFile(fileName: String, bytes: ByteArray) {
        loadingCount++
        client?.uploadFile(currentFolderPath, fileName, bytes,
            onSuccess = { refreshAndStop() },
            onFailure = { err -> errorMessage = s.uploadFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun createShareLink(file: NextcloudFile) {
        loadingCount++
        client?.createShareLink(file.path,
            onSuccess = { url -> shareLink = url; if (loadingCount > 0) loadingCount-- },
            onFailure = { err -> errorMessage = s.shareLinkFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }

    fun downloadFile(fileHref: String, onSuccess: (ByteArray) -> Unit) {
        loadingCount++
        client?.downloadFile(fileHref,
            onSuccess = { bytes -> if (loadingCount > 0) loadingCount--; onSuccess(bytes) },
            onFailure = { err -> errorMessage = s.downloadFailed(msg(err)); if (loadingCount > 0) loadingCount-- }
        )
    }
}
