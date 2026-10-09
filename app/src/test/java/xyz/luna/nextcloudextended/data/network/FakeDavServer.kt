package xyz.luna.nextcloudextended.data.network

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Minimal in-memory WebDAV/Nextcloud server: just enough of Sabre's semantics (preconditions,
 * ranges, MKCOL/MOVE rules, chunked-upload assembly) to exercise [FileApi] end to end.
 */
class FakeDavServer(private val user: String = "alice", private val basePath: String = "") : Dispatcher() {
    val files: MutableMap<String, ByteArray> = ConcurrentHashMap()
    val dirs: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().also {
        it.add("/remote.php/dav/files/$user/")
        it.add("/remote.php/dav/uploads/$user/")
    }
    val mtimes: MutableMap<String, String> = ConcurrentHashMap()
    val log = CopyOnWriteArrayList<String>()
    private val modifiedAt: MutableMap<String, Long> = ConcurrentHashMap()

    /** Hooks for failure injection: return a response to short-circuit a request. */
    var interceptor: (RecordedRequest, String) -> MockResponse? = { _, _ -> null }

    fun etag(path: String): String? = files[path]?.let { hash(it) + (modifiedAt[path] ?: 0) }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }.take(12)

    override fun dispatch(request: RecordedRequest): MockResponse {
        val raw = request.path.orEmpty().substringBefore('?')
        val path = percentDecode(raw).removePrefix(basePath)
        log.add("${request.method} $path")
        interceptor(request, path)?.let { return it }
        return when (request.method) {
            "PUT" -> put(request, path)
            "GET" -> get(request, path)
            "HEAD" -> get(request, path).also { it.setBody(Buffer()) }
            "DELETE" -> delete(path)
            "MKCOL" -> mkcol(path)
            "PROPFIND" -> propfind(request, path)
            "MOVE" -> move(request, path)
            "COPY" -> copy(request, path)
            else -> MockResponse().setResponseCode(405)
        }
    }

    private fun parent(path: String) = path.trimEnd('/').substringBeforeLast('/') + "/"

    private fun put(request: RecordedRequest, path: String): MockResponse {
        if (parent(path) !in dirs) return MockResponse().setResponseCode(409)
        val exists = path in files
        if (request.getHeader("If-None-Match") == "*" && exists) return MockResponse().setResponseCode(412)
        request.getHeader("If-Match")?.let { expected ->
            if (!exists || "\"${etag(path)}\"" != expected) return MockResponse().setResponseCode(412)
        }
        val body = request.body.readByteArray()
        request.getHeader("OC-Total-Length")?.toLongOrNull()?.let { expected ->
            // Chunk uploads announce the *final* size, not the chunk size.
            if (!path.startsWith("/remote.php/dav/uploads/") && expected != body.size.toLong()) {
                return MockResponse().setResponseCode(400).setBody("<s:message>expected filesize $expected got ${body.size}</s:message>")
            }
        }
        files[path] = body
        modifiedAt[path] = System.nanoTime()
        request.getHeader("X-OC-Mtime")?.let { mtimes[path] = it }
        return MockResponse().setResponseCode(if (exists) 204 else 201)
            .setHeader("OC-ETag", "\"${etag(path)}\"").setHeader("OC-FileId", "00000${files.size}ocx")
    }

    private fun get(request: RecordedRequest, path: String): MockResponse {
        val data = files[path] ?: return MockResponse().setResponseCode(404)
        val etag = "\"${etag(path)}\""
        val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toLong() }
        val ifRange = request.getHeader("If-Range")
        if (range != null) log.add("RANGE $path from=$range ifRange=$ifRange")
        if (range != null && (ifRange == null || ifRange == etag)) {
            if (range >= data.size) return MockResponse().setResponseCode(416)
            val slice = data.copyOfRange(range.toInt(), data.size)
            return MockResponse().setResponseCode(206).setHeader("ETag", etag)
                .setHeader("Content-Range", "bytes $range-${data.size - 1}/${data.size}")
                .setBody(Buffer().write(slice))
        }
        return MockResponse().setResponseCode(200).setHeader("ETag", etag).setBody(Buffer().write(data))
    }

    private fun delete(path: String): MockResponse {
        val key = path.trimEnd('/')
        var removed = files.remove(path) != null
        removed = dirs.remove(path.trimEnd('/') + "/") || removed
        files.keys.filter { it.startsWith("$key/") }.forEach { files.remove(it); removed = true }
        dirs.filter { it.startsWith("$key/") }.forEach { dirs.remove(it); removed = true }
        return MockResponse().setResponseCode(if (removed) 204 else 404)
    }

    private fun mkcol(path: String): MockResponse {
        val dir = path.trimEnd('/') + "/"
        if (dir in dirs || path in files) return MockResponse().setResponseCode(405)
        if (parent(dir) !in dirs) return MockResponse().setResponseCode(409)
        dirs.add(dir)
        return MockResponse().setResponseCode(201)
    }

    private fun propfind(request: RecordedRequest, path: String): MockResponse {
        val depth = request.getHeader("Depth") ?: "1"
        val dir = path.trimEnd('/') + "/"
        val entries = StringBuilder()
        fun dirEntry(p: String) = "<d:response><d:href>${enc(p)}</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype><d:displayname>${p.trimEnd('/').substringAfterLast('/')}</d:displayname></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        fun fileEntry(p: String, data: ByteArray) = "<d:response><d:href>${enc(p)}</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>${data.size}</d:getcontentlength><d:getetag>&quot;${etag(p)}&quot;</d:getetag><d:displayname>${p.substringAfterLast('/')}</d:displayname><oc:fileid>1</oc:fileid><oc:permissions>RGDNVW</oc:permissions></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        when {
            dir in dirs -> {
                entries.append(dirEntry(dir))
                if (depth != "0") {
                    dirs.filter { it != dir && parent(it) == dir }.sorted().forEach { entries.append(dirEntry(it)) }
                    files.filter { parent(it.key) == dir }.toSortedMap().forEach { (p, d) -> entries.append(fileEntry(p, d)) }
                }
            }
            path in files -> entries.append(fileEntry(path, files.getValue(path)))
            else -> return MockResponse().setResponseCode(404)
        }
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">$entries</d:multistatus>"""
        return MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml").setBody(xml)
    }

    private fun move(request: RecordedRequest, path: String): MockResponse {
        val destination = destinationPath(request) ?: return MockResponse().setResponseCode(400)
        val overwrite = request.getHeader("Overwrite") != "F"
        // Chunked-upload assembly: <uploads>/<id>/.file → final path
        if (path.endsWith("/.file")) {
            val dir = path.removeSuffix(".file")
            if (dir !in dirs) return MockResponse().setResponseCode(404)
            val chunks = files.filterKeys { it.startsWith(dir) }.toSortedMap()
            val assembled = chunks.values.fold(ByteArray(0)) { acc, b -> acc + b }
            val expected = request.getHeader("OC-Total-Length")?.toLongOrNull()
            if (expected != null && expected != assembled.size.toLong()) return MockResponse().setResponseCode(412)
            if (parent(destination) !in dirs) return MockResponse().setResponseCode(409)
            val exists = destination in files
            if (exists && !overwrite) return MockResponse().setResponseCode(412)
            files[destination] = assembled
            modifiedAt[destination] = System.nanoTime()
            request.getHeader("X-OC-Mtime")?.let { mtimes[destination] = it }
            chunks.keys.forEach { files.remove(it) }
            dirs.remove(dir)
            return MockResponse().setResponseCode(if (exists) 204 else 201).setHeader("OC-ETag", "\"${etag(destination)}\"")
        }
        val data = files[path]
        if (data == null && path.trimEnd('/') + "/" !in dirs) return MockResponse().setResponseCode(404)
        if (destination in files && !overwrite) return MockResponse().setResponseCode(412)
        if (data != null) {
            files[destination] = data; files.remove(path); modifiedAt[destination] = System.nanoTime()
        } else {
            val from = path.trimEnd('/') + "/"; val to = destination.trimEnd('/') + "/"
            dirs.filter { it.startsWith(from) }.forEach { dirs.remove(it); dirs.add(to + it.removePrefix(from)) }
            files.keys.filter { it.startsWith(from) }.forEach { files[to + it.removePrefix(from)] = files.remove(it)!! }
        }
        return MockResponse().setResponseCode(201)
    }

    private fun copy(request: RecordedRequest, path: String): MockResponse {
        val destination = destinationPath(request) ?: return MockResponse().setResponseCode(400)
        val data = files[path] ?: return MockResponse().setResponseCode(404)
        if (destination in files && request.getHeader("Overwrite") == "F") return MockResponse().setResponseCode(412)
        files[destination] = data
        return MockResponse().setResponseCode(201)
    }

    private fun destinationPath(request: RecordedRequest): String? =
        request.getHeader("Destination")?.let { percentDecode(it.substringAfter("://").substringAfter('/').let { p -> "/$p" }) }?.removePrefix(basePath)

    private fun enc(path: String) = basePath + path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}
