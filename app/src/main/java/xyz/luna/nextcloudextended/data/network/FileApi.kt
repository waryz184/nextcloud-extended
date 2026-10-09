package xyz.luna.nextcloudextended.data.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/**
 * WebDAV file operations (blocking). Behaviour mirrors what the official Android client does on the
 * wire — see docs/NETWORK_AUDIT.md for the reverse-engineering notes this is based on.
 */
class FileApi(val session: DavSession) {

    // ── Listing / metadata ──────────────────────────────────────────────────────────────────

    fun list(path: String): List<NextcloudFile> {
        val requested = ensureLeadingSlash(path)
        val request = session.request(requested)
            .header("Depth", "1")
            .method("PROPFIND", FILE_PROPFIND.toRequestBody(XML))
            .build()
        val responses = session.call(request, CallOptions(accept = setOf(207))).use { readMultistatus(it) }
        val self = requested.trimEnd('/')
        return responses.filter { it.href.trimEnd('/') != self }.map(::toFile)
    }

    /**
     * Server-side search by file name (WebDAV `SEARCH`, what the official client uses), across the whole
     * account instead of the current folder only. [scope] is an app-internal folder path.
     */
    fun search(query: String, scope: String = session.filesRoot, limit: Int = 50): List<NextcloudFile> {
        if (query.isBlank()) return emptyList()
        val scopePath = "/files/${session.userId}" + ensureLeadingSlash(scope).removePrefix(session.filesRoot.trimEnd('/')).trimEnd('/')
        val body = """<?xml version="1.0" encoding="UTF-8"?>
<d:searchrequest xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
  <d:basicsearch>
    <d:select><d:prop>
      <d:displayname /><d:getcontentlength /><d:getlastmodified /><d:resourcetype /><d:getetag /><d:getcontenttype />
      <oc:fileid /><oc:permissions /><oc:favorite /><oc:size /><nc:has-preview />
    </d:prop></d:select>
    <d:from><d:scope><d:href>${escapeXml(scopePath)}</d:href><d:depth>infinity</d:depth></d:scope></d:from>
    <d:where><d:like><d:prop><d:displayname /></d:prop><d:literal>%${escapeXml(query.trim())}%</d:literal></d:like></d:where>
    <d:orderby />
    <d:limit><d:nresults>$limit</d:nresults></d:limit>
  </d:basicsearch>
</d:searchrequest>"""
        val request = session.request("/remote.php/dav/").method("SEARCH", body.toRequestBody("text/xml; charset=utf-8".toMediaType())).build()
        return session.call(request, CallOptions(accept = setOf(207))).use { readMultistatus(it) }.map(::toFile)
    }

    /** Every file and folder marked as favourite, wherever it lives (REPORT `oc:filter-files`). */
    fun favorites(): List<NextcloudFile> {
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<oc:filter-files xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
  <d:prop>
    <d:displayname /><d:getcontentlength /><d:getlastmodified /><d:resourcetype /><d:getetag /><d:getcontenttype />
    <oc:fileid /><oc:permissions /><oc:favorite /><oc:size /><nc:has-preview />
  </d:prop>
  <oc:filter-rules><oc:favorite>1</oc:favorite></oc:filter-rules>
</oc:filter-files>"""
        val request = session.request(session.filesRoot).method("REPORT", body.toRequestBody(XML)).build()
        return session.call(request, CallOptions(accept = setOf(207))).use { readMultistatus(it) }
            .filter { it.href.trimEnd('/') != session.filesRoot.trimEnd('/') }
            .map(::toFile)
            .sortedWith(compareByDescending<NextcloudFile> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /** A rendered thumbnail (PNG) for [fileId], written to [target]. Throws 404 when the file has no preview. */
    fun downloadPreview(fileId: String, size: Int, target: File) {
        val url = session.url("/index.php/core/preview.png").toHttpUrl().newBuilder()
            .addQueryParameter("fileId", fileId).addQueryParameter("x", size.toString())
            .addQueryParameter("y", size.toString()).addQueryParameter("a", "true").addQueryParameter("mode", "cover").build()
        val request = Request.Builder().url(url).get().build()
        session.call(request, CallOptions(callTimeoutMs = 0)).use { response ->
            val body = response.body ?: throw UnexpectedResponseException("Empty preview")
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.outputStream().use { out -> body.byteStream().copyTo(out) }
            moveIntoPlace(temp, target)
        }
    }

    /** Metadata of one resource, or null when it does not exist. */
    fun stat(path: String): NextcloudFile? {
        val request = session.request(ensureLeadingSlash(path))
            .header("Depth", "0")
            .method("PROPFIND", FILE_PROPFIND.toRequestBody(XML))
            .build()
        return try {
            session.call(request, CallOptions(accept = setOf(207))).use { readMultistatus(it) }.firstOrNull()?.let(::toFile)
        } catch (e: HttpStatusException) {
            if (e.code == 404) null else throw e
        }
    }

    fun exists(path: String): Boolean = stat(path) != null

    // ── Mutations ───────────────────────────────────────────────────────────────────────────

    /** Idempotent: a resource that is already gone counts as deleted (the official client does the same). */
    fun delete(path: String) {
        val request = session.request(ensureLeadingSlash(path)).delete().build()
        session.call(request, CallOptions(accept = setOf(404))).close()
    }

    /**
     * Creates a folder. Returns false when it already existed (405) so callers can treat a replayed
     * "create folder" as done. [createParents] fills in missing ancestors on 409.
     */
    fun mkdir(path: String, createParents: Boolean = false): Boolean {
        val target = ensureLeadingSlash(path).trimEnd('/') + "/"
        val request = session.request(target).method("MKCOL", null).build()
        return try {
            session.call(request, CallOptions(accept = setOf(405))).use { it.code != 405 }
        } catch (e: HttpStatusException) {
            if (e.code == 409 && createParents) {
                val parent = target.trimEnd('/').substringBeforeLast('/', "") + "/"
                if (parent == "/" || parent.length >= target.length || !isBelowFilesRoot(parent)) throw e
                mkdir(parent, true)
                mkdir(target, false)
            } else throw e
        }
    }

    /** Creates every missing folder of [folderPath] below the user's files root. */
    fun ensureFolder(folderPath: String) {
        val folder = ensureLeadingSlash(folderPath).trimEnd('/') + "/"
        if (!isBelowFilesRoot(folder)) return
        if (exists(folder)) return
        mkdir(folder, createParents = true)
    }

    fun move(source: String, destination: String, overwrite: Boolean = false) = transfer("MOVE", source, destination, overwrite)

    fun copy(source: String, destination: String, overwrite: Boolean = false) = transfer("COPY", source, destination, overwrite)

    private fun transfer(method: String, source: String, destination: String, overwrite: Boolean) {
        val from = ensureLeadingSlash(source)
        val to = ensureLeadingSlash(destination)
        if (from.trimEnd('/') == to.trimEnd('/')) return
        if (to.trimEnd('/').startsWith(from.trimEnd('/') + "/")) {
            throw IllegalArgumentException("Cannot ${method.lowercase(Locale.ROOT)} a folder into itself")
        }
        val request = session.request(from)
            .header("Destination", session.url(to))
            .header("Overwrite", if (overwrite) "T" else "F")
            .method(method, null)
            .build()
        // 207 = the server did part of the job; surface it instead of silently reporting success.
        session.call(request, CallOptions(accept = setOf(207))).use { response ->
            if (response.code == 207) {
                val failed = runCatching { readMultistatus(response) }.getOrDefault(emptyList())
                    .any { (it.status ?: 200) >= 300 }
                if (failed) throw HttpStatusException(207, "Partial $method", davMessage = "Only part of the folder could be processed")
            }
        }
    }

    fun rename(path: String, newName: String) {
        require(isValidDavName(newName)) { "Invalid file name" }
        val isDir = path.endsWith("/")
        val clean = if (isDir) path.dropLast(1) else path
        val parent = clean.substring(0, clean.lastIndexOf('/') + 1)
        move(path, parent + newName + if (isDir) "/" else "")
    }

    fun setFavorite(path: String, favorite: Boolean) {
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<d:propertyupdate xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:set><d:prop><oc:favorite>${if (favorite) 1 else 0}</oc:favorite></d:prop></d:set></d:propertyupdate>"""
        val request = session.request(ensureLeadingSlash(path)).method("PROPPATCH", body.toRequestBody(XML)).build()
        session.call(request, CallOptions(accept = setOf(207))).use { response ->
            val failed = runCatching { readMultistatus(response) }.getOrDefault(emptyList())
                .any { (it.status ?: 200) >= 300 }
            if (failed) throw HttpStatusException(403, "Cannot change favorite")
        }
    }

    // ── Upload ──────────────────────────────────────────────────────────────────────────────

    enum class CollisionPolicy {
        /** Fail with [ConflictException] when the name is already taken. */
        FAIL,
        /** Replace whatever is there (optionally guarded by the ETag the caller last saw). */
        OVERWRITE,
        /** Keep both: store under the next free "name (2).ext". */
        RENAME,
        /**
         * Replace the version the caller edited ([UploadOptions.expectedEtag]); if somebody else changed
         * it meanwhile, keep both instead of failing or clobbering their work.
         */
        OVERWRITE_OR_KEEP_BOTH
    }

    class UploadSource(
        val length: Long,
        val lastModifiedSeconds: Long? = null,
        val mimeType: String? = null,
        /** Opens the content positioned at [offset] bytes from the start. */
        val open: (offset: Long) -> InputStream
    ) {
        companion object {
            fun ofFile(file: File, mimeType: String? = null) = UploadSource(
                length = file.length(),
                lastModifiedSeconds = file.lastModified().takeIf { it > 0 }?.div(1000),
                mimeType = mimeType
            ) { offset ->
                val stream = java.io.FileInputStream(file)
                if (offset > 0) stream.channel.position(offset)
                stream
            }

            fun ofBytes(bytes: ByteArray, mimeType: String? = null) = UploadSource(bytes.size.toLong(), null, mimeType) { offset ->
                java.io.ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
            }
        }
    }

    class UploadOptions(
        val policy: CollisionPolicy = CollisionPolicy.OVERWRITE,
        /** ETag of the version the caller is replacing; guards against lost updates when overwriting. */
        val expectedEtag: String? = null,
        /** Files larger than this go through the chunked upload endpoint (and use chunks of this size). */
        val chunkSize: Long = DEFAULT_CHUNK_SIZE,
        val chunkedEnabled: Boolean = true,
        val createParents: Boolean = true,
        val progress: ((done: Long, total: Long) -> Unit)? = null
    )

    class UploadResult(val path: String, val etag: String?, val fileId: String?, val renamed: Boolean)

    /** Uploads [source] to [parent]/[name]. Returns the final path (it differs after a RENAME collision). */
    fun upload(parent: String, name: String, source: UploadSource, options: UploadOptions = UploadOptions()): UploadResult {
        require(isValidDavName(name)) { "Invalid file name" }
        val folder = ensureLeadingSlash(parent).trimEnd('/') + "/"
        var targetName = name
        var attempt = 0
        var effective = options
        while (true) {
            val target = folder + targetName
            try {
                val result = if (effective.chunkedEnabled && source.length > effective.chunkSize) {
                    uploadChunked(target, source, effective)
                } else {
                    uploadSimple(target, source, effective)
                }
                return UploadResult(target, result.first, result.second, targetName != name)
            } catch (e: HttpStatusException) {
                val keepBoth = options.policy == CollisionPolicy.OVERWRITE_OR_KEEP_BOTH
                if (e.code == 412 && (options.policy == CollisionPolicy.RENAME || keepBoth) && attempt++ < MAX_RENAME_ATTEMPTS) {
                    if (keepBoth) effective = UploadOptions(CollisionPolicy.RENAME, null, options.chunkSize, options.chunkedEnabled, options.createParents, options.progress)
                    targetName = nextAvailableName(folder, name, attempt)
                    continue
                }
                if (e.code == 412) {
                    val serverEtag = if (options.policy == CollisionPolicy.FAIL) stat(target)?.etag else options.expectedEtag
                    throw ConflictException("File was modified on the server", serverEtag)
                }
                throw e
            }
        }
    }

    private fun uploadSimple(target: String, source: UploadSource, options: UploadOptions): Pair<String?, String?> {
        var createdParents = false
        while (true) {
            val builder = session.request(target)
                .header("OC-Total-Length", source.length.toString())
                .put(StreamBody(source.mimeType.toMediaTypeOrOctet(), source.length, source.open, 0, source.length, options.progress))
            source.lastModifiedSeconds?.let { builder.header("X-OC-Mtime", it.toString()) }
            applyPreconditions(builder, options)
            val callOptions = CallOptions(
                readTimeoutMs = TransferTimeouts.readTimeoutMs(source.length),
                callTimeoutMs = 0
            )
            try {
                return session.call(builder.build(), callOptions).use { etagOf(it) to it.header("OC-FileId") }
            } catch (e: HttpStatusException) {
                // 409 on PUT = the parent folder is missing (e.g. first auto-upload into "InstantUpload").
                if (e.code == 409 && options.createParents && !createdParents) {
                    createdParents = true
                    ensureFolder(target.substringBeforeLast('/') + "/")
                    continue
                }
                throw e
            }
        }
    }

    private fun applyPreconditions(builder: Request.Builder, options: UploadOptions) {
        when (options.policy) {
            CollisionPolicy.FAIL, CollisionPolicy.RENAME -> builder.header("If-None-Match", "*")
            CollisionPolicy.OVERWRITE, CollisionPolicy.OVERWRITE_OR_KEEP_BOTH ->
                options.expectedEtag?.takeIf { it.isNotBlank() }?.let { builder.header("If-Match", "\"${it.trim('"')}\"") }
        }
    }

    private fun uploadChunked(target: String, source: UploadSource, options: UploadOptions): Pair<String?, String?> {
        val total = source.length
        val chunkSize = options.chunkSize.coerceAtLeast(1)
        val uploadId = uploadIdFor(target, source)
        val uploadDir = "/remote.php/dav/uploads/${session.userId}/$uploadId"
        val destinationUrl = session.url(target)
        val baseHeaders = { b: Request.Builder -> b.header("Destination", destinationUrl).header("OC-Total-Length", total.toString()) }

        if (options.createParents) ensureFolder(target.substringBeforeLast('/') + "/")

        // MKCOL answers 405 when the folder exists from an earlier attempt: that is the resume case.
        session.call(baseHeaders(session.request("$uploadDir/").method("MKCOL", null)).build(), CallOptions(accept = setOf(405))).close()

        var next = resumablePrefix(uploadDir, total, chunkSize)
        var done = next.second
        options.progress?.invoke(done, total)

        var chunkId = next.first
        while (done < total) {
            chunkId++
            val length = minOf(chunkSize, total - done)
            val body = StreamBody(OCTET, length, source.open, done, length) { sent, _ -> options.progress?.invoke(done + sent, total) }
            val put = baseHeaders(session.request("$uploadDir/${"%06d".format(Locale.ROOT, chunkId)}").put(body))
            session.call(put.build(), CallOptions(readTimeoutMs = TransferTimeouts.readTimeoutMs(length), callTimeoutMs = 0)).close()
            done += length
            options.progress?.invoke(done, total)
        }

        val move = baseHeaders(session.request("$uploadDir/.file").method("MOVE", null))
            .header("Overwrite", if (options.policy == CollisionPolicy.OVERWRITE || options.policy == CollisionPolicy.OVERWRITE_OR_KEEP_BOTH) "T" else "F")
        source.lastModifiedSeconds?.let { move.header("X-OC-Mtime", it.toString()) }
        val assemble = CallOptions(readTimeoutMs = TransferTimeouts.readTimeoutMs(total), callTimeoutMs = 0)
        try {
            val assembled = session.call(move.build(), assemble).use { etagOf(it) to it.header("OC-FileId") }
            if (assembled.first != null) return assembled
            val remote = stat(target)
            return (remote?.etag ?: assembled.first) to (remote?.fileId ?: assembled.second)
        } catch (e: HttpStatusException) {
            // 404 on ".file": a previous MOVE already assembled the file and we only lost the answer.
            if (e.code == 404) completedAs(target, total)?.let { return it }
            if (!e.isTransient && e.code != 412) runCatching { deleteQuietly(uploadDir) }
            throw e
        } catch (e: IOException) {
            // The connection died while the server was still assembling: it usually completes.
            completedAs(target, total, waitMs = ASSEMBLE_RECHECK_MS)?.let { return it }
            throw e
        }
    }

    /** Returns (last contiguous chunk id, bytes already stored) for a half finished upload. */
    private fun resumablePrefix(uploadDir: String, total: Long, chunkSize: Long): Pair<Int, Long> {
        val request = session.request("$uploadDir/").header("Depth", "1")
            .method("PROPFIND", CHUNK_PROPFIND.toRequestBody(XML)).build()
        val chunks = session.call(request, CallOptions(accept = setOf(207))).use { readMultistatus(it) }
            .filter { !it.isCollection }
            .mapNotNull { r ->
                val name = r.href.substringAfterLast('/')
                if (name.length in 1..6 && name.all(Char::isDigit)) name.toInt() to (r.text(DavNs.DAV, "getcontentlength")?.toLongOrNull() ?: -1L)
                else null
            }.sortedBy { it.first }
        var expectedId = 1
        var stored = 0L
        for ((id, length) in chunks) {
            val remaining = total - stored
            val validLength = length == minOf(chunkSize, remaining)
            if (id != expectedId || !validLength) {
                // Gap or a chunk written with another chunk size: start clean rather than assemble garbage.
                chunks.forEach { (staleId, _) -> deleteQuietly("$uploadDir/${"%06d".format(Locale.ROOT, staleId)}") }
                return 0 to 0L
            }
            stored += length
            expectedId++
        }
        return (expectedId - 1) to stored
    }

    private fun completedAs(target: String, total: Long, waitMs: Long = 0): Pair<String?, String?>? {
        val deadline = System.currentTimeMillis() + waitMs
        do {
            val remote = runCatching { stat(target) }.getOrNull()
            if (remote != null && remote.size == total) return remote.etag to remote.fileId
            if (waitMs <= 0) break
            try { Thread.sleep(1_500) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    private fun deleteQuietly(path: String) { runCatching { delete(path) } }

    internal fun uploadIdFor(target: String, source: UploadSource): String {
        val seed = "${session.userId}|$target|${source.length}|${source.lastModifiedSeconds ?: 0}"
        return MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    /** "name.ext" → "name (2).ext", skipping names that exist in [folder]. */
    private fun nextAvailableName(folder: String, name: String, hint: Int): String {
        val taken = runCatching { list(folder).map { it.name }.toSet() }.getOrDefault(emptySet())
        val stem = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.') && !name.startsWith('.')) "." + name.substringAfterLast('.') else ""
        val baseStem = if (ext.isEmpty()) name else stem
        var n = maxOf(2, hint + 1)
        while (true) {
            val candidate = "$baseStem ($n)$ext"
            if (candidate !in taken) return candidate
            n++
        }
    }

    // ── Download ────────────────────────────────────────────────────────────────────────────

    class DownloadResult(val bytes: Long, val etag: String?, val lastModifiedMillis: Long?)

    /**
     * Streams [remotePath] into [target] through a `.part` file that is resumed with `Range`/`If-Range`
     * after a dropped connection, verified against `Content-Length`, then moved into place atomically.
     */
    fun download(
        remotePath: String,
        target: File,
        expectedSize: Long? = null,
        progress: ((done: Long, total: Long) -> Unit)? = null
    ): DownloadResult {
        val parent = target.absoluteFile.parentFile ?: throw LocalIoException("No target directory")
        if (!parent.exists() && !parent.mkdirs()) throw LocalIoException("Cannot create ${parent.path}")
        val part = File(parent, target.name + ".part")
        val meta = File(parent, target.name + ".part.etag")
        var attempts = 0
        var lastError: IOException? = null
        while (attempts < DOWNLOAD_ATTEMPTS) {
            attempts++
            try {
                return downloadOnce(remotePath, target, part, meta, expectedSize, progress)
            } catch (e: HttpStatusException) {
                if (e.code == 416) { part.delete(); meta.delete(); lastError = e; continue }
                throw e
            } catch (e: LocalIoException) {
                throw e
            } catch (e: RequestCancelledException) {
                throw e
            } catch (e: IOException) {
                if (!e.isTransientFailure()) throw e
                lastError = e
                try { Thread.sleep(minOf(500L * attempts, 3_000L)) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw e }
            }
        }
        throw lastError ?: IOException("Download failed")
    }

    private fun downloadOnce(
        remotePath: String, target: File, part: File, meta: File, expectedSize: Long?,
        progress: ((Long, Long) -> Unit)?
    ): DownloadResult {
        var offset = if (part.exists()) part.length() else 0L
        val savedEtag = runCatching { meta.takeIf { it.exists() }?.readText()?.trim() }.getOrNull()
        if (offset > 0 && savedEtag.isNullOrBlank()) { part.delete(); offset = 0 }

        val builder = session.request(ensureLeadingSlash(remotePath)).get()
            .header("Accept-Encoding", "identity") // Range offsets and Content-Length must refer to the raw bytes
        if (offset > 0) builder.header("Range", "bytes=$offset-").header("If-Range", "\"${savedEtag!!.trim('"')}\"")

        val options = CallOptions(readTimeoutMs = TransferTimeouts.readTimeoutMs(expectedSize), callTimeoutMs = 0, accept = setOf(416))
        session.call(builder.build(), options).use { response ->
            if (response.code == 416) throw HttpStatusException(416, "Range not satisfiable")
            val etag = etagOf(response)
            val resumed = response.code == 206 && offset > 0 && contentRangeStart(response) == offset
            if (!resumed) offset = 0
            val body = response.body ?: throw UnexpectedResponseException("Empty response body")
            val remaining = body.contentLength()
            val total = when {
                remaining >= 0 -> offset + remaining
                expectedSize != null -> expectedSize
                else -> -1L
            }
            val needed = if (total >= 0) total - offset else 0L
            if (needed > 0 && parent(target).usableSpace in 0 until needed) {
                throw LocalIoException("Not enough local storage for ${target.name}")
            }
            etag?.let { runCatching { meta.writeText(it) } }
            try {
                RandomAccessFile(part, "rw").use { out ->
                    out.setLength(offset)
                    out.seek(offset)
                    var written = offset
                    val buffer = ByteArray(BUFFER)
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            progress?.invoke(written, total)
                        }
                    }
                }
            } catch (e: java.io.FileNotFoundException) {
                throw LocalIoException("Cannot write ${part.path}", e)
            } catch (e: IOException) {
                if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space left") == true) {
                    throw LocalIoException("Not enough local storage for ${target.name}", e)
                }
                throw e
            }
            if (total >= 0 && part.length() != total) {
                throw IOException("Incomplete download: ${part.length()} of $total bytes")
            }
            moveIntoPlace(part, target)
            meta.delete()
            val modified = response.header("Last-Modified")?.let { parseHttpDate(it) }
            return DownloadResult(target.length(), etag, modified)
        }
    }

    /** In-memory download for previews. Bounded so a huge file cannot exhaust the heap. */
    fun readBytes(remotePath: String, maxBytes: Long): ByteArray {
        val request = session.request(ensureLeadingSlash(remotePath)).get().build()
        session.call(request, CallOptions(callTimeoutMs = 0, readTimeoutMs = TransferTimeouts.readTimeoutMs(maxBytes))).use { response ->
            val body = response.body ?: throw UnexpectedResponseException("Empty response body")
            if (body.contentLength() > maxBytes) throw LocalIoException("File is too large for an in-app preview (${maxBytes / (1024 * 1024)} MB maximum)")
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw LocalIoException("File is too large for an in-app preview (${maxBytes / (1024 * 1024)} MB maximum)")
                    out.write(buffer, 0, n)
                }
            }
            return out.toByteArray()
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private fun parent(file: File): File = file.absoluteFile.parentFile ?: file

    private fun moveIntoPlace(part: File, target: File) {
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw LocalIoException("Cannot save ${target.name}", e)
        }
    }

    private fun contentRangeStart(response: Response): Long? =
        Regex("""bytes\s+(\d+)-""").find(response.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()

    private fun isBelowFilesRoot(path: String): Boolean {
        val root = session.filesRoot.trimEnd('/')
        return path.trimEnd('/').length > root.length && path.startsWith("$root/")
    }

    private fun readMultistatus(response: Response): List<DavResponse> {
        val body = response.body ?: throw UnexpectedResponseException("Empty WebDAV response")
        return DavMultistatus.parse(body.byteStream(), session.basePath)
    }

    private fun toFile(r: DavResponse): NextcloudFile {
        val isDir = r.isCollection
        val name = r.text(DavNs.DAV, "displayname")?.takeIf { it.isNotBlank() }
            ?: r.href.trimEnd('/').substringAfterLast('/').ifEmpty { "?" }
        val size = if (isDir) r.text(DavNs.OC, "size")?.toLongOrNull() else r.text(DavNs.DAV, "getcontentlength")?.toLongOrNull()
        return NextcloudFile(
            name = name,
            path = r.href,
            isDirectory = isDir,
            size = size ?: 0L,
            lastModified = r.text(DavNs.DAV, "getlastmodified").orEmpty(),
            etag = parseEtag(r.text(DavNs.DAV, "getetag")),
            fileId = r.text(DavNs.OC, "fileid")?.takeIf { it.isNotBlank() },
            permissions = r.text(DavNs.OC, "permissions")?.takeIf { it.isNotBlank() },
            favorite = r.text(DavNs.OC, "favorite") == "1",
            mimeType = r.text(DavNs.DAV, "getcontenttype")?.takeIf { it.isNotBlank() },
            hasPreview = r.text(DavNs.NC, "has-preview") == "true",
            ownerName = r.text(DavNs.OC, "owner-display-name")?.takeIf { it.isNotBlank() },
            locked = r.text(DavNs.NC, "lock") == "1"
        )
    }

    private fun etagOf(response: Response): String? =
        parseEtag(response.header("OC-ETag") ?: response.header("ETag"))

    private class StreamBody(
        private val type: MediaType,
        private val length: Long,
        private val open: (Long) -> InputStream,
        private val offset: Long,
        private val count: Long,
        private val progress: ((Long, Long) -> Unit)? = null
    ) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = count
        override fun isOneShot() = false
        override fun writeTo(sink: BufferedSink) {
            open(offset).use { input ->
                var sent = 0L
                val buffer = ByteArray(BUFFER)
                while (sent < count) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), count - sent).toInt())
                    if (n < 0) throw IOException("Source ended after $sent of $count bytes")
                    sink.write(buffer, 0, n)
                    sent += n
                    progress?.invoke(sent, length)
                }
            }
        }
    }

    private fun String?.toMediaTypeOrOctet(): MediaType = this?.toMediaTypeOrNull() ?: OCTET

    companion object {
        const val DEFAULT_CHUNK_SIZE = 40_960_000L
        const val MOBILE_CHUNK_SIZE = 10_240_000L
        const val MIN_CHUNK_SIZE = 10_240_000L
        private const val MAX_RENAME_ATTEMPTS = 5
        private const val DOWNLOAD_ATTEMPTS = 4
        private const val ASSEMBLE_RECHECK_MS = 20_000L
        private const val BUFFER = 64 * 1024

        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()

        private const val FILE_PROPFIND = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
  <d:prop>
    <d:displayname /><d:getcontentlength /><d:getlastmodified /><d:resourcetype /><d:getetag /><d:getcontenttype />
    <oc:fileid /><oc:permissions /><oc:favorite /><oc:size /><oc:owner-display-name />
    <nc:has-preview /><nc:lock />
  </d:prop>
</d:propfind>"""

        private const val CHUNK_PROPFIND = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:"><d:prop><d:getcontentlength /><d:resourcetype /></d:prop></d:propfind>"""

        fun ensureLeadingSlash(path: String) = if (path.startsWith("/")) path else "/$path"

        /**
         * Chunk size the official client picks: the server's advertised maximum (never below 10 MB),
         * otherwise 40 MB on Wi-Fi and 10 MB on a metered/mobile link.
         */
        fun chunkSizeFor(onWifi: Boolean, serverMaxChunkSize: Long?): Long = when {
            serverMaxChunkSize == null || serverMaxChunkSize <= 0 -> if (onWifi) DEFAULT_CHUNK_SIZE else MOBILE_CHUNK_SIZE
            else -> maxOf(serverMaxChunkSize, MIN_CHUNK_SIZE)
        }

        /** `"abc123-gzip"` → `abc123`: Apache's mod_deflate appends the suffix, which breaks comparisons. */
        fun parseEtag(raw: String?): String? {
            var v = raw?.trim() ?: return null
            if (v.startsWith("W/")) v = v.removePrefix("W/")
            if (v.endsWith("-gzip\"")) v = v.removeSuffix("-gzip\"") + "\""
            else if (v.endsWith("-gzip")) v = v.removeSuffix("-gzip")
            v = v.trim('"')
            return v.ifEmpty { null }
        }

        fun parseHttpDate(value: String): Long? = runCatching {
            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time
        }.getOrNull()
    }
}
