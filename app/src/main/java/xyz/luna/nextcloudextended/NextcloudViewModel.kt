package xyz.luna.nextcloudextended

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import xyz.luna.nextcloudextended.data.model.NextcloudNote
import xyz.luna.nextcloudextended.data.model.NextcloudTask
import xyz.luna.nextcloudextended.data.network.CalDavClient
import java.time.LocalDate

class NextcloudViewModel : ViewModel() {

    // ── Connection ─────────────────────────────────────────────────────────────
    var isConnected by mutableStateOf(false)
    var client by mutableStateOf<CalDavClient?>(null)

    // ── Loading ────────────────────────────────────────────────────────────────
    var loadingCount by mutableIntStateOf(0)
    val isLoading get() = loadingCount > 0

    // ── Navigation ─────────────────────────────────────────────────────────────
    var currentTab by mutableStateOf(HubTab.CALENDAR)
    var calendarViewMode by mutableStateOf(CalendarViewMode.MONTH)
    var selectedDate by mutableStateOf(LocalDate.now())

    // ── Calendar ───────────────────────────────────────────────────────────────
    var calendars by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var taskLists by mutableStateOf<List<Pair<String, String>>>(emptyList())
    var selectedCalendarHref by mutableStateOf("")
    var selectedCalendarName by mutableStateOf("")
    var events by mutableStateOf<List<CalendarEvent>>(emptyList())

    // ── Tasks ──────────────────────────────────────────────────────────────────
    var selectedTaskListHref by mutableStateOf("")
    var selectedTaskListName by mutableStateOf("")
    var tasks by mutableStateOf<List<NextcloudTask>>(emptyList())

    // ── Notes ──────────────────────────────────────────────────────────────────
    var notes by mutableStateOf<List<NextcloudNote>>(emptyList())

    // ── Files ──────────────────────────────────────────────────────────────────
    var currentFolderPath by mutableStateOf("")
    var files by mutableStateOf<List<NextcloudFile>>(emptyList())

    // ── Errors ─────────────────────────────────────────────────────────────────
    var errorMessage by mutableStateOf<String?>(null)

    // ── Network operations ─────────────────────────────────────────────────────

    fun refreshData() {
        val c = client ?: return
        loadingCount++
        when (currentTab) {
            HubTab.CALENDAR -> {
                if (selectedCalendarHref.isNotEmpty()) {
                    c.getEvents(selectedCalendarHref,
                        onSuccess = { evList ->
                            events = evList.sortedBy { it.startTime ?: "" }
                            if (loadingCount > 0) loadingCount--
                        },
                        onFailure = { err ->
                            errorMessage = "Erreur calendrier: ${err.message}"
                            if (loadingCount > 0) loadingCount--
                        }
                    )
                } else { if (loadingCount > 0) loadingCount-- }
            }
            HubTab.TASKS -> {
                if (selectedTaskListHref.isNotEmpty()) {
                    c.getTasks(selectedTaskListHref,
                        onSuccess = { tList ->
                            tasks = tList.sortedWith(compareBy({ it.status == "COMPLETED" }, { it.due ?: "" }))
                            if (loadingCount > 0) loadingCount--
                        },
                        onFailure = { err ->
                            errorMessage = "Erreur tâches: ${err.message}"
                            if (loadingCount > 0) loadingCount--
                        }
                    )
                } else { if (loadingCount > 0) loadingCount-- }
            }
            HubTab.NOTES -> {
                c.getNotes(
                    onSuccess = { nList ->
                        notes = nList.sortedWith(compareByDescending<NextcloudNote> { it.favorite }.thenByDescending { it.modified })
                        if (loadingCount > 0) loadingCount--
                    },
                    onFailure = { err ->
                        errorMessage = "Erreur notes: ${err.message}"
                        if (loadingCount > 0) loadingCount--
                    }
                )
            }
            HubTab.FILES -> {
                if (currentFolderPath.isNotEmpty()) {
                    c.getFiles(currentFolderPath,
                        onSuccess = { fList ->
                            files = fList.sortedWith(compareByDescending<NextcloudFile> { it.isDirectory }.thenBy { it.name.lowercase() })
                            if (loadingCount > 0) loadingCount--
                        },
                        onFailure = { err ->
                            errorMessage = "Erreur fichiers: ${err.message}"
                            if (loadingCount > 0) loadingCount--
                        }
                    )
                } else { if (loadingCount > 0) loadingCount-- }
            }
        }
    }

    fun refreshAndStop() {
        refreshData()
        if (loadingCount > 0) loadingCount--
    }

    fun connect(
        serverUrl: String, username: String, password: String,
        onSaveCredentials: () -> Unit
    ) {
        if (serverUrl.startsWith("http://")) {
            errorMessage = "Attention : connexion non sécurisée (HTTP). Préférez HTTPS."
        }
        loadingCount++
        val c = CalDavClient(serverUrl, username, password)
        client = c
        c.getAllCalendarData(
            onSuccess = { eventCals, taskListData ->
                onSaveCredentials()
                calendars = eventCals
                taskLists = taskListData
                isConnected = true
                if (eventCals.isNotEmpty()) {
                    selectedCalendarHref = eventCals[0].first
                    selectedCalendarName = eventCals[0].second
                }
                if (taskListData.isNotEmpty()) {
                    val todoListObj = taskListData.find {
                        it.second.lowercase().contains("todo") || it.first.lowercase().contains("todo")
                    } ?: taskListData[0]
                    selectedTaskListHref = todoListObj.first
                    selectedTaskListName = todoListObj.second
                }
                refreshData()
                if (loadingCount > 0) loadingCount--
            },
            onFailure = { err ->
                errorMessage = "Connexion échouée: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun disconnect(onClearPrefs: () -> Unit) {
        client?.cancelAll()
        onClearPrefs()
        isConnected = false
        client = null
        calendars = emptyList()
        taskLists = emptyList()
        events = emptyList()
        tasks = emptyList()
        notes = emptyList()
        files = emptyList()
        currentFolderPath = ""
        selectedCalendarHref = ""
        selectedCalendarName = ""
        selectedTaskListHref = ""
        selectedTaskListName = ""
    }

    fun loadCalendarEvents(href: String, name: String) {
        selectedCalendarHref = href
        selectedCalendarName = name
        loadingCount++
        client?.getEvents(href,
            onSuccess = { evList ->
                events = evList.sortedBy { it.startTime ?: "" }
                if (loadingCount > 0) loadingCount--
            },
            onFailure = { err ->
                errorMessage = err.message
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun loadTaskList(href: String, name: String) {
        selectedTaskListHref = href
        selectedTaskListName = name
        loadingCount++
        client?.getTasks(href,
            onSuccess = { tList ->
                tasks = tList.sortedWith(compareBy({ it.status == "COMPLETED" }, { it.due ?: "" }))
                if (loadingCount > 0) loadingCount--
            },
            onFailure = { err ->
                errorMessage = err.message
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun toggleTaskStatus(task: NextcloudTask) {
        val updatedStatus = if (task.status == "COMPLETED") "NEEDS-ACTION" else "COMPLETED"
        loadingCount++
        client?.saveTask(task.copy(status = updatedStatus),
            onSuccess = { refreshAndStop() },
            onFailure = { err ->
                errorMessage = "Échec mise à jour tâche: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun deleteTask(task: NextcloudTask) {
        loadingCount++
        client?.deleteTask(task,
            onSuccess = { refreshAndStop() },
            onFailure = { err ->
                errorMessage = "Échec suppression tâche: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun createTask(uid: String, summary: String, description: String?) {
        loadingCount++
        val newTask = NextcloudTask(uid, summary, if (description.isNullOrEmpty()) null else description, "NEEDS-ACTION", null, selectedTaskListHref)
        client?.saveTask(newTask,
            onSuccess = { refreshAndStop() },
            onFailure = { err ->
                errorMessage = "Création tâche échouée: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun createEvent(event: CalendarEvent) {
        loadingCount++
        client?.saveEvent(selectedCalendarHref, event,
            onSuccess = { refreshAndStop() },
            onFailure = { err ->
                errorMessage = "Création d'événement échouée: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun createTaskList(name: String) {
        loadingCount++
        client?.createTaskList(name,
            onSuccess = {
                client?.getTaskLists(
                    onSuccess = { list -> taskLists = list; if (loadingCount > 0) loadingCount-- },
                    onFailure = { err -> errorMessage = err.message; if (loadingCount > 0) loadingCount-- }
                )
            },
            onFailure = { err ->
                errorMessage = "Création de liste échouée: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun renameTaskList(newName: String) {
        loadingCount++
        client?.renameTaskList(selectedTaskListHref, newName,
            onSuccess = {
                selectedTaskListName = newName
                client?.getTaskLists(
                    onSuccess = { list -> taskLists = list; if (loadingCount > 0) loadingCount-- },
                    onFailure = { err -> errorMessage = err.message; if (loadingCount > 0) loadingCount-- }
                )
            },
            onFailure = { err ->
                errorMessage = "Renommer liste échoué: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun deleteTaskList() {
        loadingCount++
        client?.deleteTaskList(selectedTaskListHref,
            onSuccess = {
                selectedTaskListHref = ""; selectedTaskListName = ""; tasks = emptyList()
                client?.getTaskLists(
                    onSuccess = { list -> taskLists = list; if (loadingCount > 0) loadingCount-- },
                    onFailure = { err -> errorMessage = err.message; if (loadingCount > 0) loadingCount-- }
                )
            },
            onFailure = { err ->
                errorMessage = "Suppression de liste échouée: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun toggleNoteFavorite(note: NextcloudNote) {
        loadingCount++
        client?.updateNote(note.id, note.title, note.content, note.category, !note.favorite,
            onSuccess = { refreshAndStop() },
            onFailure = { err ->
                errorMessage = "Échec favori note: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun createNote(title: String, content: String, category: String) {
        loadingCount++
        client?.createNote(title, content, category,
            onSuccess = { refreshAndStop() },
            onFailure = { err ->
                errorMessage = "Création note échouée: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun updateNote(note: NextcloudNote, title: String, content: String, category: String) {
        loadingCount++
        client?.updateNote(note.id, title, content, category, note.favorite,
            onSuccess = { refreshAndStop() },
            onFailure = { err ->
                errorMessage = "Mise à jour note échouée: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun deleteNote(noteId: Int) {
        loadingCount++
        client?.deleteNote(noteId,
            onSuccess = { refreshAndStop() },
            onFailure = { err ->
                errorMessage = "Suppression note échouée: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun navigateToFolder(path: String) {
        currentFolderPath = path
        refreshData()
    }

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
            onFailure = { err ->
                errorMessage = "Échec suppression: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun renameFile(path: String, newName: String) {
        loadingCount++
        client?.renameFile(path, newName,
            onSuccess = { refreshAndStop() },
            onFailure = { err ->
                errorMessage = "Renommage échoué: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun createFolder(name: String) {
        loadingCount++
        client?.createFolder(currentFolderPath, name,
            onSuccess = { refreshAndStop() },
            onFailure = { err ->
                errorMessage = "Erreur création dossier: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }

    fun uploadFile(fileName: String, bytes: ByteArray) {
        loadingCount++
        client?.uploadFile(currentFolderPath, fileName, bytes,
            onSuccess = { refreshAndStop() },
            onFailure = { err ->
                errorMessage = "Import échoué: ${err.message}"
                if (loadingCount > 0) loadingCount--
            }
        )
    }
}
