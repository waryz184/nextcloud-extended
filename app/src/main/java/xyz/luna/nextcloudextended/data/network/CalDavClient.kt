package xyz.luna.nextcloudextended.data.network

import android.os.Handler
import android.os.Looper
import okhttp3.Credentials
import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.CalendarInfo
import xyz.luna.nextcloudextended.data.model.NextcloudContact
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import xyz.luna.nextcloudextended.data.model.NextcloudNote
import xyz.luna.nextcloudextended.data.model.NextcloudShare
import xyz.luna.nextcloudextended.data.model.NextcloudTask
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Callback style facade used by the UI layer. All protocol work lives in the blocking classes
 * ([FileApi], [OcsApi], [GroupwareApi], [DavExtras]) running on a shared [DavSession]; this class only
 * moves it off the main thread and delivers results back on it.
 *
 * Background code (WorkManager workers, the contacts sync adapter, the DocumentsProvider) should use
 * the blocking APIs directly through [files], [ocs], [groupware] and [extras].
 */
class CalDavClient(
    serverUrl: String,
    private val username: String,
    password: String,
    userId: String = username
) {
    init {
        require(serverUrl.trim().startsWith("https://", ignoreCase = true)) { "HTTPS is required" }
    }

    private val credentials = Credentials.basic(username, password)

    val session = DavSession(serverUrl, userId.ifBlank { username }, credentials)
    val files = FileApi(session)
    val ocs = OcsApi(session)
    val groupware = GroupwareApi(session)
    val extras = DavExtras(session)

    val baseUrl: String get() = session.baseUrl
    val userId: String get() = session.userId

    fun getAuthorizationHeader(): String = credentials
    fun buildFileUrl(fileHref: String) = session.url(fileHref)

    /** Called once the real account id is known (it can differ from the login name). */
    fun useUserId(id: String) = session.setUserId(id)

    fun cancelAll() = session.cancelAll()

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private fun runOnMain(action: () -> Unit) { mainHandler.post(action) }

    /**
     * Runs [work] on the shared I/O pool and reports on the main thread. A request cancelled through
     * [cancelAll] still reports (as a [RequestCancelledException] failure) so the caller can release
     * its spinner — silently dropping it used to leave the "loading" counter stuck above zero.
     */
    fun <T> async(work: () -> T, onSuccess: (T) -> Unit, onFailure: (Exception) -> Unit) {
        NextcloudHttp.ioExecutor.execute {
            val outcome = try {
                Result.success(work())
            } catch (e: Exception) {
                Result.failure(e)
            }
            outcome.fold(
                onSuccess = { value -> runOnMain { onSuccess(value) } },
                onFailure = { error -> runOnMain { onFailure(error as? Exception ?: IOException(error.message, error)) } }
            )
        }
    }

    private fun asyncUnit(work: () -> Unit, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        async(work, { onSuccess() }, onFailure)

    // ── Calendars / tasks ───────────────────────────────────────────────────────────────────

    fun getAllCalendarData(
        onSuccess: (eventCalendars: List<CalendarInfo>, taskLists: List<Pair<String, String>>) -> Unit,
        onFailure: (Exception) -> Unit
    ) = async({ groupware.calendars() }, { onSuccess(it.events, it.taskLists) }, onFailure)

    fun getCalendars(onSuccess: (List<CalendarInfo>) -> Unit, onFailure: (Exception) -> Unit) =
        async({ groupware.calendars().events }, onSuccess, onFailure)

    fun getTaskLists(onSuccess: (List<Pair<String, String>>) -> Unit, onFailure: (Exception) -> Unit) =
        async({ groupware.calendars().taskLists }, onSuccess, onFailure)

    fun getEvents(calendarHref: String, onSuccess: (List<CalendarEvent>) -> Unit, onFailure: (Exception) -> Unit) =
        async({ groupware.events(calendarHref) }, onSuccess, onFailure)

    fun getTasks(calendarHref: String, onSuccess: (List<NextcloudTask>) -> Unit, onFailure: (Exception) -> Unit) =
        async({ groupware.tasks(calendarHref) }, onSuccess, onFailure)

    fun saveTask(task: NextcloudTask, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ groupware.saveTask(task) }, onSuccess, onFailure)

    fun saveEvent(calendarHref: String, event: CalendarEvent, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ groupware.saveEvent(calendarHref, event) }, onSuccess, onFailure)

    fun deleteEvent(event: CalendarEvent, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ groupware.deleteCalendarObject(event.href, event.calendarHref, event.id) }, onSuccess, onFailure)

    fun deleteTask(task: NextcloudTask, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ groupware.deleteCalendarObject(task.href, task.calendarHref, task.uid) }, onSuccess, onFailure)

    fun createTaskList(listName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ groupware.createTaskList(listName) }, onSuccess, onFailure)

    fun deleteTaskList(calendarHref: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ groupware.deleteTaskList(calendarHref) }, onSuccess, onFailure)

    fun renameTaskList(calendarHref: String, newName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ groupware.renameTaskList(calendarHref, newName) }, onSuccess, onFailure)

    // ── Notes ───────────────────────────────────────────────────────────────────────────────

    fun getNotes(onSuccess: (List<NextcloudNote>) -> Unit, onFailure: (Exception) -> Unit) =
        async({ groupware.notes() }, onSuccess, onFailure)

    fun createNote(title: String, content: String, category: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ groupware.createNote(title, content, category) }, onSuccess, onFailure)

    fun updateNote(noteId: Int, title: String, content: String, category: String, favorite: Boolean, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ groupware.updateNote(noteId, title, content, category, favorite) }, onSuccess, onFailure)

    fun deleteNote(noteId: Int, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ groupware.deleteNote(noteId) }, onSuccess, onFailure)

    // ── Files ───────────────────────────────────────────────────────────────────────────────

    fun getFiles(folderPath: String, onSuccess: (List<NextcloudFile>) -> Unit, onFailure: (Exception) -> Unit) =
        async({ files.list(folderPath) }, onSuccess, onFailure)

    fun deleteFile(fileHref: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ files.delete(fileHref) }, onSuccess, onFailure)

    fun createFolder(parentHref: String, folderName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({
            require(isValidDavName(folderName)) { "Invalid folder name" }
            // The UI wants to hear that the name is taken; background replays use FileApi.mkdir directly.
            if (!files.mkdir(parentHref.trimEnd('/') + "/" + folderName, createParents = true)) {
                throw HttpStatusException(405, "Folder already exists")
            }
        }, onSuccess, onFailure)

    fun renameFile(sourceHref: String, newName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ files.rename(sourceHref, newName) }, onSuccess, onFailure)

    fun transferFile(sourceHref: String, destinationHref: String, copy: Boolean, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ if (copy) files.copy(sourceHref, destinationHref) else files.move(sourceHref, destinationHref) }, onSuccess, onFailure)

    fun setFavorite(fileHref: String, favorite: Boolean, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ files.setFavorite(fileHref, favorite) }, onSuccess, onFailure)

    /**
     * Uploads [openStream]'s content. Without [existingEtag] the name is created only if it is free
     * (a taken name raises [ConflictException]); with it, the upload replaces that exact version. Pass
     * [overwrite] to replace whatever is there (used after the user chose "overwrite").
     */
    fun uploadFile(
        parentHref: String,
        fileName: String,
        contentLength: Long?,
        openStream: () -> InputStream,
        onSuccess: () -> Unit,
        onFailure: (Exception) -> Unit,
        existingEtag: String? = null,
        overwrite: Boolean = false
    ) = asyncUnit({
        val length = contentLength?.takeIf { it >= 0 } ?: measure(openStream)
        val source = FileApi.UploadSource(length) { offset -> openStream().also { skipFully(it, offset) } }
        val policy = if (overwrite || existingEtag != null) FileApi.CollisionPolicy.OVERWRITE else FileApi.CollisionPolicy.FAIL
        files.upload(parentHref, fileName, source, FileApi.UploadOptions(policy, expectedEtag = existingEtag))
    }, onSuccess, onFailure)

    /** Small in-memory download for previews (25 MB cap). */
    fun downloadFile(fileHref: String, onSuccess: (ByteArray) -> Unit, onFailure: (Exception) -> Unit) =
        async({ files.readBytes(fileHref, MAX_IN_MEMORY_FILE_BYTES) }, onSuccess, onFailure)

    /** Resumable download straight to disk — no size limit, safe for multi-gigabyte files. */
    fun downloadToFile(fileHref: String, target: File, expectedSize: Long?, onSuccess: (File) -> Unit, onFailure: (Exception) -> Unit) =
        async({ files.download(fileHref, target, expectedSize); target }, onSuccess, onFailure)

    /** Streams into [output] (a pipe handed to another app by the DocumentsProvider). Always closes it. */
    fun downloadFileTo(fileHref: String, output: OutputStream, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({
            try {
                session.call(session.request(fileHref).get().header("Accept-Encoding", "identity").build(),
                    CallOptions(callTimeoutMs = 0, readTimeoutMs = TransferTimeouts.readTimeoutMs(null))).use { response ->
                    val body = response.body ?: throw UnexpectedResponseException("Empty response body")
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.flush()
                    }
                }
                output.close()
            } catch (e: Exception) {
                runCatching { output.close() }
                throw e
            }
        }, onSuccess, onFailure)

    // ── Sharing / editing ───────────────────────────────────────────────────────────────────

    fun createShareLink(fileHref: String, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) =
        async({
            ocs.createShare(fileHref, NextcloudShare.TYPE_LINK).url
                ?: throw UnexpectedResponseException("The server did not return a link")
        }, onSuccess, onFailure)

    fun getShares(fileHref: String, onSuccess: (List<NextcloudShare>) -> Unit, onFailure: (Exception) -> Unit) =
        async({ ocs.shares(fileHref) }, onSuccess, onFailure)

    fun deleteShare(shareId: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ ocs.deleteShare(shareId) }, onSuccess, onFailure)

    fun getOnlineEditorUrl(fileHref: String, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) =
        async({ ocs.directEditingUrl(fileHref) }, onSuccess, onFailure)

    // ── Contacts (CardDAV) ──────────────────────────────────────────────────────────────────

    fun getAddressBooks(onSuccess: (List<Pair<String, String>>) -> Unit, onFailure: (Exception) -> Unit) =
        async({ groupware.addressBooks() }, onSuccess, onFailure)

    fun getContacts(addressBookHref: String, onSuccess: (List<NextcloudContact>) -> Unit, onFailure: (Exception) -> Unit) =
        async({ groupware.contacts(addressBookHref) }, onSuccess, onFailure)

    fun saveContact(contact: NextcloudContact, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ groupware.saveContact(contact) }, onSuccess, onFailure)

    fun deleteContact(contact: NextcloudContact, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) =
        asyncUnit({ groupware.deleteContact(contact) }, onSuccess, onFailure)

    // Synchronous variants for the contacts sync adapter (already on its own thread).
    fun getAddressBooksSync(): List<Pair<String, String>> = groupware.addressBooks()
    fun getContactsSync(addressBookHref: String): List<NextcloudContact> = groupware.contacts(addressBookHref)
    fun saveContactSync(contact: NextcloudContact): String = groupware.saveContact(contact)
    fun deleteContactSync(contact: NextcloudContact) = groupware.deleteContact(contact)

    private fun measure(openStream: () -> InputStream): Long = openStream().use { input ->
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
        }
        total
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (input.read() >= 0) remaining-- else throw IOException("Source is shorter than expected")
        }
    }

    companion object {
        const val MAX_IN_MEMORY_FILE_BYTES = 25L * 1024 * 1024
    }
}

class ConflictException(message: String, val serverEtag: String? = null) : Exception(message)
