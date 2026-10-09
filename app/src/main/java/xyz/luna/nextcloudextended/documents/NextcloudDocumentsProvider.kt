package xyz.luna.nextcloudextended.documents

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.runBlocking
import xyz.luna.nextcloudextended.account.AccountProfile
import xyz.luna.nextcloudextended.account.SecureStore
import xyz.luna.nextcloudextended.account.openSession
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import xyz.luna.nextcloudextended.data.network.CallOptions
import xyz.luna.nextcloudextended.data.network.DavSession
import xyz.luna.nextcloudextended.data.network.FileApi
import xyz.luna.nextcloudextended.data.network.HttpStatusException
import xyz.luna.nextcloudextended.data.network.isValidDavName
import xyz.luna.nextcloudextended.upload.OfflineCacheManager
import xyz.luna.nextcloudextended.upload.UploadRepository
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Exposes the signed-in account to the system file picker / Files app, read and write.
 *
 * Reads of small files are downloaded (resumably) into a validated cache and handed out as a regular,
 * seekable file; big files are served through a seekable proxy descriptor that fetches 512 KB ranges
 * on demand, so a 4 GB video can be opened and scrubbed without downloading it first. Writes go to a
 * staging file and are queued for upload when the other app closes it.
 */
class NextcloudDocumentsProvider : DocumentsProvider() {
    private companion object {
        const val ROOT_ID = "root"
        const val AUTHORITY = "xyz.luna.nextcloudextended.documents"
        const val SMALL_FILE_BYTES = 8L * 1024 * 1024
        const val BLOCK = 512 * 1024
    }

    private class Link(val key: String, val profile: AccountProfile, val session: DavSession, val api: FileApi)

    @Volatile private var link: Link? = null

    private val ioThread by lazy { HandlerThread("nc-documents").apply { start() } }
    private val ioHandler by lazy { Handler(ioThread.looper) }

    override fun onCreate(): Boolean = context != null

    private fun current(): Link? {
        val ctx = context ?: return null
        val profile = SecureStore.activeProfile(ctx) ?: return null
        if (!profile.serverUrl.startsWith("https://", ignoreCase = true)) return null
        val key = "${profile.id}|${profile.serverUrl}|${profile.userId}|${profile.password.hashCode()}"
        link?.takeIf { it.key == key }?.let { return it }
        return synchronized(this) {
            link?.takeIf { it.key == key } ?: run {
                val session = profile.openSession()
                Link(key, profile, session, FileApi(session)).also { link = it }
            }
        }
    }

    private fun require(): Link = current() ?: throw IOException("No authenticated Nextcloud session")

    private fun rootPath(l: Link) = l.session.filesRoot

    // ── Queries ─────────────────────────────────────────────────────────────────────────────

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val account = current()?.profile ?: return result
        result.newRow()
            .add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            .add(DocumentsContract.Root.COLUMN_TITLE, "Nextcloud")
            .add(DocumentsContract.Root.COLUMN_SUMMARY, account.label)
            .add(DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
            .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            .add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.ic_menu_save)
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (documentId == ROOT_ID) {
            addDocument(result, ROOT_ID, "Nextcloud", true, 0L, null, null, false)
            return result
        }
        val l = require()
        val file = l.api.stat(decodeId(documentId)) ?: throw FileNotFoundException(documentId)
        addDocument(result, documentId, file.name, file.isDirectory, file.size, file.lastModified, file.mimeType, file.hasPreview, file)
        return result
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val l = require()
        val parentPath = if (parentDocumentId == ROOT_ID) rootPath(l) else decodeId(parentDocumentId)
        l.api.list(parentPath)
            .sortedWith(compareByDescending<NextcloudFile> { it.isDirectory }.thenBy { it.name.lowercase() })
            .forEach { addDocument(result, encodeId(it.path), it.name, it.isDirectory, it.size, it.lastModified, it.mimeType, it.hasPreview, it) }
        result.setNotificationUri(context?.contentResolver, DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId))
        return result
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (rootId != ROOT_ID || query.isBlank()) return result
        val l = require()
        l.api.search(query, rootPath(l)).forEach {
            addDocument(result, encodeId(it.path), it.name, it.isDirectory, it.size, it.lastModified, it.mimeType, it.hasPreview, it)
        }
        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == ROOT_ID) return true
        val parent = decodeId(parentDocumentId).trimEnd('/') + "/"
        return decodeId(documentId).startsWith(parent)
    }

    override fun getDocumentType(documentId: String): String {
        if (documentId == ROOT_ID) return DocumentsContract.Document.MIME_TYPE_DIR
        val path = decodeId(documentId)
        return if (path.endsWith("/")) DocumentsContract.Document.MIME_TYPE_DIR else mimeType(path.substringAfterLast('/'))
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val l = require()
        val path = decodeId(documentId)
        signal?.setOnCancelListener { l.session.cancelAll() }
        val writing = mode.contains('w') || mode.contains('a')
        return if (writing) openForWriting(l, documentId, path, mode) else openForReading(l, path)
    }

    private fun openForReading(l: Link, path: String): ParcelFileDescriptor {
        val remote = l.api.stat(path) ?: throw FileNotFoundException(path)
        if (remote.isDirectory) throw FileNotFoundException("Cannot open a directory")
        if (remote.size <= SMALL_FILE_BYTES) {
            val file = cachedCopy(l, remote)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val storage = context!!.getSystemService(StorageManager::class.java)
        return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, RangeReader(l, remote), ioHandler)
    }

    /** Download once per server version; a later open of an unchanged file is served from disk. */
    private fun cachedCopy(l: Link, remote: NextcloudFile): File {
        val dir = File(context!!.cacheDir, "documents/${l.profile.id}").apply { mkdirs() }
        val name = sha(remote.path + "|" + (remote.etag ?: remote.lastModified))
        val file = File(dir, name)
        if (file.isFile && file.length() == remote.size) return file
        l.api.download(remote.path, file, remote.size)
        // keep the cache small: drop the oldest copies beyond ~200 MB
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.let { all ->
            var total = 0L
            all.forEach { f -> total += f.length(); if (total > 200L * 1024 * 1024 && f != file) f.delete() }
        }
        return file
    }

    /** Seekable view of a remote file: ranged GETs with a few cached blocks. */
    private inner class RangeReader(private val l: Link, private val remote: NextcloudFile) : ProxyFileDescriptorCallback() {
        private val cache = LruCache<Long, ByteArray>(6)

        override fun onGetSize(): Long = remote.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            try {
                var copied = 0
                while (copied < size && offset + copied < remote.size) {
                    val position = offset + copied
                    val index = position / BLOCK
                    val block = cache.get(index) ?: fetch(index).also { cache.put(index, it) }
                    val within = (position - index * BLOCK).toInt()
                    val n = minOf(block.size - within, size - copied)
                    if (n <= 0) break
                    System.arraycopy(block, within, data, copied, n)
                    copied += n
                }
                return copied
            } catch (e: ErrnoException) {
                throw e
            } catch (e: Exception) {
                throw ErrnoException("onRead", OsConstants.EIO, e)
            }
        }

        private fun fetch(index: Long): ByteArray {
            val start = index * BLOCK
            val end = minOf(start + BLOCK, remote.size) - 1
            val request = l.session.request(remote.path).get()
                .header("Range", "bytes=$start-$end").header("Accept-Encoding", "identity")
                .also { b -> remote.etag?.let { b.header("If-Range", "\"$it\"") } }.build()
            l.session.call(request, CallOptions(callTimeoutMs = 0)).use { response ->
                if (response.code != 206) throw IOException("Server ignored the range request (HTTP ${response.code})")
                val bytes = response.body!!.bytes()
                if (bytes.size.toLong() != end - start + 1) throw IOException("Short range answer")
                return bytes
            }
        }

        override fun onRelease() { cache.evictAll() }
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor {
        val l = require()
        val remote = l.api.stat(decodeId(documentId)) ?: throw FileNotFoundException(documentId)
        val id = remote.fileId ?: throw FileNotFoundException("No preview")
        val dir = File(context!!.cacheDir, "thumbnails").apply { mkdirs() }
        val file = File(dir, sha("${l.profile.id}|$id|${remote.etag}|${sizeHint.x}"))
        if (!file.isFile) {
            signal?.setOnCancelListener { l.session.cancelAll() }
            try {
                l.api.downloadPreview(id, maxOf(sizeHint.x, sizeHint.y, 64).coerceAtMost(1024), file)
            } catch (e: HttpStatusException) {
                throw FileNotFoundException("No preview available")
            }
        }
        return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────

    private fun openForWriting(l: Link, documentId: String, path: String, mode: String): ParcelFileDescriptor {
        val existing = l.api.stat(path)
        val directory = File(context!!.filesDir, "pending-uploads").apply { mkdirs() }
        val staged = File(directory, "doc_${UUID.randomUUID()}.bin")
        // Opened without truncation (e.g. "rw"): the other app expects the current content to be there.
        if (existing != null && !mode.contains('t') && !existing.isDirectory) l.api.download(path, staged, existing.size)
        val parent = path.substringBeforeLast('/', "") + "/"
        val name = path.substringAfterLast('/')
        val etag = existing?.etag
        val accountId = l.profile.id
        val appContext = context!!.applicationContext
        return ParcelFileDescriptor.open(staged, ParcelFileDescriptor.parseMode(mode), ioHandler) { error ->
            if (error != null) {
                staged.delete()
            } else {
                runCatching {
                    runBlocking {
                        UploadRepository.enqueue(appContext, accountId, staged.absolutePath, parent, name, staged.length(),
                            overwrite = true, expectedEtag = etag)
                    }
                }.onFailure { staged.delete() }
                notifyChildrenChanged(parent)
            }
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String?, displayName: String): String {
        if (!isValidDavName(displayName)) throw IOException("Invalid file name")
        val l = require()
        val parent = (if (parentDocumentId == ROOT_ID) rootPath(l) else decodeId(parentDocumentId)).trimEnd('/') + "/"
        val id = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            var name = displayName
            var n = 1
            // The picker expects a fresh folder: never silently reuse an existing one.
            while (!l.api.mkdir(parent + name)) name = "$displayName (${++n})"
            encodeId("$parent$name/")
        } else {
            // Create the (empty) remote file now so the picker can immediately query it.
            val created = l.api.upload(parent, displayName, FileApi.UploadSource.ofBytes(ByteArray(0)),
                FileApi.UploadOptions(FileApi.CollisionPolicy.RENAME))
            encodeId(created.path)
        }
        notifyChildrenChanged(parent)
        return id
    }

    override fun deleteDocument(documentId: String) {
        val l = require()
        val path = decodeId(documentId)
        l.api.delete(path)
        runCatching { runBlocking { OfflineCacheManager.remove(context!!, l.profile.id, path) } }
        notifyChildrenChanged(path.trimEnd('/').substringBeforeLast('/') + "/")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (!isValidDavName(displayName)) throw IOException("Invalid file name")
        val l = require()
        val path = decodeId(documentId)
        l.api.rename(path, displayName)
        val parent = path.trimEnd('/').substringBeforeLast('/') + "/"
        notifyChildrenChanged(parent)
        return encodeId(parent + displayName + if (path.endsWith("/")) "/" else "")
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val l = require()
        val source = decodeId(sourceDocumentId)
        val targetParent = (if (targetParentDocumentId == ROOT_ID) rootPath(l) else decodeId(targetParentDocumentId)).trimEnd('/') + "/"
        val destination = targetParent + source.trimEnd('/').substringAfterLast('/') + if (source.endsWith("/")) "/" else ""
        l.api.move(source, destination)
        notifyChildrenChanged(targetParent); notifyChildrenChanged(source.trimEnd('/').substringBeforeLast('/') + "/")
        return encodeId(destination)
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val l = require()
        val source = decodeId(sourceDocumentId)
        val targetParent = (if (targetParentDocumentId == ROOT_ID) rootPath(l) else decodeId(targetParentDocumentId)).trimEnd('/') + "/"
        val destination = targetParent + source.trimEnd('/').substringAfterLast('/') + if (source.endsWith("/")) "/" else ""
        l.api.copy(source, destination)
        notifyChildrenChanged(targetParent)
        return encodeId(destination)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private fun notifyChildrenChanged(parentPath: String) {
        val ctx = context ?: return
        val l = current()
        val id = if (l != null && parentPath.trimEnd('/') == rootPath(l).trimEnd('/')) ROOT_ID else encodeId(parentPath.trimEnd('/') + "/")
        ctx.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, id), null)
    }

    private fun addDocument(
        cursor: MatrixCursor, id: String, name: String, directory: Boolean, size: Long,
        modified: String?, mime: String?, hasPreview: Boolean, file: NextcloudFile? = null
    ) {
        var flags = 0
        if (directory) {
            flags = flags or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            if (file?.canWrite != false) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
            if (hasPreview) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
        }
        if (id != ROOT_ID) {
            if (file?.canDelete != false) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            if (file?.canRename != false) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                DocumentsContract.Document.FLAG_SUPPORTS_MOVE or DocumentsContract.Document.FLAG_SUPPORTS_COPY
        }
        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, if (directory) DocumentsContract.Document.MIME_TYPE_DIR else (mime?.takeIf { it.contains('/') } ?: mimeType(name)))
            .add(DocumentsContract.Document.COLUMN_SIZE, if (directory) null else size)
            .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, modified?.let(FileApi::parseHttpDate))
            .add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            .add(DocumentsContract.Document.COLUMN_ICON, android.R.drawable.ic_menu_save)
    }

    private fun encodeId(path: String): String = Base64.encodeToString(path.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
    private fun decodeId(id: String): String = String(Base64.decode(id, Base64.URL_SAFE), Charsets.UTF_8)

    private fun mimeType(name: String): String = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private val DEFAULT_ROOT_PROJECTION = arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_SUMMARY,
        DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.COLUMN_MIME_TYPES,
        DocumentsContract.Root.COLUMN_ICON
    )

    private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_ICON
    )
}
