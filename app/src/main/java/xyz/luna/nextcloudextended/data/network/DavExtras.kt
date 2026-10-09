package xyz.luna.nextcloudextended.data.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.luna.nextcloudextended.data.model.FileVersion
import xyz.luna.nextcloudextended.data.model.TrashedFile

/** Trash bin (`files_trashbin`) and file versions (`files_versions`) over their DAV endpoints. */
class DavExtras(private val session: DavSession) {

    private val xml = "application/xml; charset=utf-8".toMediaType()

    // ── Trash bin ───────────────────────────────────────────────────────────────────────────

    private val trashRoot get() = "/remote.php/dav/trashbin/${session.userId}/trash"
    private val trashRestore get() = "/remote.php/dav/trashbin/${session.userId}/restore"

    fun listTrash(): List<TrashedFile> {
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
  <d:prop><d:resourcetype /><d:getcontentlength /><oc:size /><oc:fileid />
    <nc:trashbin-filename /><nc:trashbin-original-location /><nc:trashbin-deletion-time /></d:prop>
</d:propfind>"""
        val request = session.request("$trashRoot/").header("Depth", "1")
            .method("PROPFIND", body.toRequestBody(xml)).build()
        val responses = session.call(request, CallOptions(accept = setOf(207))).use {
            DavMultistatus.parse(it.body!!.byteStream(), session.basePath)
        }
        return responses.filter { it.href.trimEnd('/') != trashRoot }.map { r ->
            val isDir = r.isCollection
            TrashedFile(
                path = r.href,
                name = r.text(DavNs.NC, "trashbin-filename")?.takeIf { it.isNotBlank() } ?: r.href.trimEnd('/').substringAfterLast('/'),
                originalLocation = r.text(DavNs.NC, "trashbin-original-location").orEmpty(),
                deletionTimeSeconds = r.text(DavNs.NC, "trashbin-deletion-time")?.toLongOrNull() ?: 0L,
                size = (if (isDir) r.text(DavNs.OC, "size") else r.text(DavNs.DAV, "getcontentlength"))?.toLongOrNull() ?: 0L,
                isDirectory = isDir,
                fileId = r.text(DavNs.OC, "fileid")?.takeIf { it.isNotBlank() }
            )
        }.sortedByDescending { it.deletionTimeSeconds }
    }

    /** Moves a trashed item back to where it was deleted from. */
    fun restore(item: TrashedFile) {
        val name = item.path.trimEnd('/').substringAfterLast('/')
        val request = session.request(item.path)
            .header("Destination", session.url("$trashRestore/$name"))
            .header("Overwrite", "F")
            .method("MOVE", null).build()
        session.call(request).close()
    }

    fun deletePermanently(item: TrashedFile) {
        session.call(session.request(item.path).delete().build(), CallOptions(accept = setOf(404))).close()
    }

    fun emptyTrash() {
        session.call(session.request("$trashRoot/").delete().build(), CallOptions(accept = setOf(404))).close()
    }

    // ── Versions ────────────────────────────────────────────────────────────────────────────

    private fun versionsRoot(fileId: String) = "/remote.php/dav/versions/${session.userId}/versions/$fileId"

    fun listVersions(fileId: String): List<FileVersion> {
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:"><d:prop><d:getlastmodified /><d:getcontentlength /><d:getcontenttype /><d:resourcetype /></d:prop></d:propfind>"""
        val request = session.request("${versionsRoot(fileId)}/").header("Depth", "1")
            .method("PROPFIND", body.toRequestBody(xml)).build()
        val responses = try {
            session.call(request, CallOptions(accept = setOf(207))).use { DavMultistatus.parse(it.body!!.byteStream(), session.basePath) }
        } catch (e: HttpStatusException) {
            if (e.code == 404) return emptyList() else throw e
        }
        return responses.filter { it.href.trimEnd('/') != versionsRoot(fileId) }.map { r ->
            val timestamp = r.href.trimEnd('/').substringAfterLast('/').toLongOrNull()
            FileVersion(
                path = r.href,
                lastModifiedMillis = timestamp?.times(1000)
                    ?: r.text(DavNs.DAV, "getlastmodified")?.let { FileApi.parseHttpDate(it) } ?: 0L,
                size = r.text(DavNs.DAV, "getcontentlength")?.toLongOrNull() ?: 0L,
                mimeType = r.text(DavNs.DAV, "getcontenttype")?.takeIf { it.isNotBlank() }
            )
        }.sortedByDescending { it.lastModifiedMillis }
    }

    /** Makes [version] the current content of its file (the current content becomes a version itself). */
    fun restoreVersion(version: FileVersion) {
        val request = session.request(version.path)
            .header("Destination", session.url("/remote.php/dav/versions/${session.userId}/restore/target"))
            .method("MOVE", null).build()
        session.call(request).close()
    }
}
