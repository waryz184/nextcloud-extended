package xyz.luna.nextcloudextended.documents

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import xyz.luna.nextcloudextended.data.network.CalDavClient
import xyz.luna.nextcloudextended.upload.UploadRepository
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** DocumentsProvider backed by the authenticated user's WebDAV storage. */
class NextcloudDocumentsProvider : DocumentsProvider() {
    private companion object {
        const val ROOT_ID = "root"
        const val PREFS = "secret_shared_prefs"
        const val TIMEOUT_SECONDS = 30L
    }

    override fun onCreate(): Boolean = context != null

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(resolveProjection(projection, DocumentsContract.Root::class.java))
        val account = credentials() ?: return result
        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
        row.add(DocumentsContract.Root.COLUMN_TITLE, "Nextcloud Extended")
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, account.second)
        row.add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_RECENTS)
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*\ntext/*\nimage/*\naudio/*\nvideo/*")
        row.add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_save)
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(resolveProjection(projection, DocumentsContract.Document::class.java))
        if (documentId == ROOT_ID) {
            addDocument(result, ROOT_ID, "Nextcloud", true, 0L, null)
            return result
        }
        val file = findDocument(documentId) ?: throw FileNotFoundException(documentId)
        addDocument(result, documentId, file.name, file.isDirectory, file.size, file.lastModified)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(resolveProjection(projection, DocumentsContract.Document::class.java))
        val parentPath = if (parentDocumentId == ROOT_ID) rootPath() else decodeId(parentDocumentId)
        val files = listFiles(parentPath)
        files.sortedWith(compareByDescending<NextcloudFile> { it.isDirectory }.thenBy { it.name.lowercase() })
            .forEach { file ->
                addDocument(result, encodeId(file.path), file.name, file.isDirectory, file.size, file.lastModified)
            }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = findDocument(documentId)
        if (file != null && file.isDirectory) throw FileNotFoundException("Cannot open a directory")

        val pipe = ParcelFileDescriptor.createPipe()
        if (mode.contains('w')) {
            // Write mode: stream the caller's bytes to durable staging, then queue an upload.
            val remoteClient = client()
            if (remoteClient == null) {
                pipe[1].close()
                throw IOException("No authenticated Nextcloud session")
            }
            val writeTarget = decodeId(documentId)
            val parent = writeTarget.substringBeforeLast('/', "") + "/"
            val name = writeTarget.substringAfterLast('/').ifEmpty { throw IOException("Invalid file name") }
            Thread {
                try {
                    val directory = File(context?.filesDir, "pending-uploads").apply { mkdirs() }
                    val staged = File.createTempFile("upload_", ".bin", directory)
                    ParcelFileDescriptor.AutoCloseInputStream(pipe[1]).use { input ->
                        staged.outputStream().use { output -> input.copyTo(output) }
                    }
                    val accountId = prefsValue("active_account_id") ?: return@Thread
                    runBlocking {
                        UploadRepository.enqueue(context!!, accountId, staged.absolutePath, parent, name, staged.length())
                    }
                } catch (_: Exception) {
                    runCatching { pipe[1].close() }
                }
            }.start()
            return pipe[0]
        }

        if (file == null) throw FileNotFoundException(documentId)
        val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        val remoteClient = client()
        if (remoteClient == null) {
            output.close()
            throw IOException("No authenticated Nextcloud session")
        }
        signal?.setOnCancelListener {
            remoteClient.cancelAll()
            runCatching { output.close() }
        }
        remoteClient.downloadFileTo(file.path, output,
            onSuccess = {},
            onFailure = { runCatching { output.close() } }
        )
        return pipe[0]
    }

    override fun createDocument(parentDocumentId: String, displayName: String, mimeType: String?): String {
        if (!isValidName(displayName)) throw IOException("Invalid file name")
        val parent = if (parentDocumentId == ROOT_ID) rootPath() else decodeId(parentDocumentId)
        val isDir = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        val path = parent.trimEnd('/') + "/" + displayName + (if (isDir) "/" else "")
        return encodeId(path)
    }

    override fun deleteDocument(documentId: String) {
        val file = findDocument(documentId) ?: throw FileNotFoundException(documentId)
        awaitUnit { success, failure -> client()?.deleteFile(file.path, success, failure) ?: failure(IOException("No authenticated Nextcloud session")) }
        val accountId = prefsValue("active_account_id")
        if (accountId != null) {
            runCatching {
                runBlocking { xyz.luna.nextcloudextended.upload.OfflineCacheManager.remove(context!!, accountId, file.path) }
            }
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (!isValidName(displayName)) throw IOException("Invalid file name")
        val path = decodeId(documentId)
        findDocument(documentId) ?: throw FileNotFoundException(documentId)
        awaitUnit { success, failure -> client()?.renameFile(path, displayName, success, failure) ?: failure(IOException("No authenticated Nextcloud session")) }
        val parent = path.substringBeforeLast('/', "") + "/"
        return encodeId(parent + displayName)
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<String>?
    ): Cursor {
        val result = MatrixCursor(resolveProjection(projection, DocumentsContract.Document::class.java))
        if (rootId != ROOT_ID || query.isBlank()) return result
        searchFiles(rootPath(), query).forEach { file ->
            addDocument(result, encodeId(file.path), file.name, file.isDirectory, file.size, file.lastModified)
        }
        return result
    }

    private fun addDocument(
        cursor: MatrixCursor,
        id: String,
        name: String,
        directory: Boolean,
        size: Long,
        modified: String?
    ) {
        val row = cursor.newRow()
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, if (directory) DocumentsContract.Document.MIME_TYPE_DIR else mimeType(name))
        row.add(DocumentsContract.Document.COLUMN_SIZE, if (directory) null else size)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, modified?.let { parseTimestamp(it) })
        val flags = when {
            directory -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            else -> DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL or
                DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        }
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        row.add(DocumentsContract.Document.COLUMN_ICON, android.R.drawable.ic_menu_save)
    }

    private fun listFiles(path: String): List<NextcloudFile> = await { success, failure ->
        client()?.getFiles(path, success, failure) ?: failure(IOException("No authenticated Nextcloud session"))
    } ?: emptyList()

    private fun searchFiles(path: String, query: String): List<NextcloudFile> =
        listFiles(path).filter { it.name.contains(query, ignoreCase = true) }

    private fun findDocument(id: String): NextcloudFile? {
        val path = decodeId(id)
        val parent = path.substringBeforeLast('/', "") + "/"
        return listFiles(parent).firstOrNull { it.path == path }
    }

    private fun <T> await(register: ((T) -> Unit, (Exception) -> Unit) -> Unit): T? {
        var value: T? = null
        var failure: Exception? = null
        val latch = CountDownLatch(1)
        register({ result -> value = result; latch.countDown() }, { error -> failure = error; latch.countDown() })
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw IOException("Nextcloud request timed out")
        failure?.let { throw it }
        return value
    }

    private fun awaitUnit(register: (() -> Unit, (Exception) -> Unit) -> Unit) {
        var failure: Exception? = null
        val latch = CountDownLatch(1)
        register({ latch.countDown() }, { error -> failure = error; latch.countDown() })
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) throw IOException("Nextcloud request timed out")
        failure?.let { throw it }
    }

    private fun client(): CalDavClient? {
        val values = credentials() ?: return null
        return runCatching { CalDavClient(values.first, values.second, values.third) }.getOrNull()
    }

    private fun credentials(): Triple<String, String, String>? {
        val prefs = runCatching {
            val ctx = context ?: return null
            val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(ctx, PREFS, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.getOrNull() ?: return null
        val url = prefs.getString("server_url", "")?.trimEnd('/') ?: ""
        val user = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""
        return if (url.startsWith("https://") && user.isNotEmpty() && password.isNotEmpty()) Triple(url, user, password) else null
    }

    private fun prefsValue(key: String): String? {
        val prefs = runCatching {
            val ctx = context ?: return null
            val k = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(ctx, PREFS, k,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.getOrNull() ?: return null
        return prefs.getString(key, "")
    }

    private fun isValidName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\')

    private fun rootPath(): String = "/remote.php/dav/files/${credentials()?.second}/"

    private fun encodeId(path: String): String = Base64.encodeToString(path.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
    private fun decodeId(id: String): String = String(Base64.decode(id, Base64.URL_SAFE))

    private fun mimeType(name: String): String = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun parseTimestamp(value: String): Long? = runCatching {
        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrNull()

    private fun resolveProjection(requested: Array<String>?, type: Class<*>): Array<String> = requested ?: when (type) {
        DocumentsContract.Root::class.java -> arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_ICON
        )
        else -> arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_ICON
        )
    }
}
