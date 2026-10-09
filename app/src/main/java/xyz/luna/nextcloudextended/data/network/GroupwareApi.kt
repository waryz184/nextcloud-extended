package xyz.luna.nextcloudextended.data.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.CalendarInfo
import xyz.luna.nextcloudextended.data.model.NextcloudContact
import xyz.luna.nextcloudextended.data.model.NextcloudNote
import xyz.luna.nextcloudextended.data.model.NextcloudTask
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/** CalDAV (events + tasks), CardDAV and the Notes API, blocking, on top of a [DavSession]. */
class GroupwareApi(private val session: DavSession) {

    private val xml = "application/xml; charset=utf-8".toMediaType()
    private val ics = "text/calendar; charset=utf-8".toMediaType()
    private val vcf = "text/vcard; charset=utf-8".toMediaType()
    private val json = "application/json; charset=utf-8".toMediaType()

    private val calendarHome get() = "/remote.php/dav/calendars/${session.userId}/"
    private val addressBookHome get() = "/remote.php/dav/addressbooks/users/${session.userId}/"

    private fun parse(response: Response): List<DavResponse> {
        val body = response.body ?: throw UnexpectedResponseException("Empty WebDAV response")
        return DavMultistatus.parse(body.byteStream(), session.basePath)
    }

    private fun dav(path: String, method: String, depth: String?, body: String): Request {
        val builder = session.request(path).method(method, body.toRequestBody(xml))
        depth?.let { builder.header("Depth", it) }
        return builder.build()
    }

    private fun collectionUrl(href: String) = if (href.endsWith("/")) href else "$href/"

    // ── Calendars & task lists ──────────────────────────────────────────────────────────────

    class Calendars(val events: List<CalendarInfo>, val taskLists: List<Pair<String, String>>)

    fun calendars(): Calendars {
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://apple.com/ns/ical/">
  <d:prop><d:displayname /><d:resourcetype /><c:supported-calendar-component-set /><cs:calendar-color /></d:prop>
</d:propfind>"""
        val responses = session.call(dav(calendarHome, "PROPFIND", "1", body), CallOptions(accept = setOf(207))).use(::parse)
        val events = mutableListOf<CalendarInfo>()
        val tasks = mutableListOf<Pair<String, String>>()
        for (r in responses) {
            if (!r.hasChild(DavNs.DAV, "resourcetype", DavNs.CALDAV, "calendar")) continue
            val components = r.prop(DavNs.CALDAV, "supported-calendar-component-set")?.childNames?.get("comp").orEmpty()
            val name = r.text(DavNs.DAV, "displayname")?.takeIf { it.isNotBlank() } ?: "Calendar"
            val color = Regex("#?([0-9a-fA-F]{6})").find(r.text(DavNs.APPLE, "calendar-color").orEmpty())?.groupValues?.get(1)
            if ("VEVENT" in components) events.add(CalendarInfo(r.href, name, color?.let { "#$it" } ?: ""))
            if ("VTODO" in components) tasks.add(r.href to name)
        }
        return Calendars(events, tasks)
    }

    fun events(calendarHref: String): List<CalendarEvent> {
        // Let the server expand recurring events (RRULE/EXDATE/leap years…) inside this window.
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        val today = LocalDate.now()
        val start = today.minusYears(2).atStartOfDay().format(fmt)
        val end = today.plusYears(3).atStartOfDay().format(fmt)
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop><d:getetag /><c:calendar-data><c:expand start="$start" end="$end" /></c:calendar-data></d:prop>
  <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT"><c:time-range start="$start" end="$end" /></c:comp-filter></c:comp-filter></c:filter>
</c:calendar-query>"""
        val responses = session.call(dav(collectionUrl(calendarHref), "REPORT", "1", body), CallOptions(accept = setOf(207))).use(::parse)
        return responses.flatMap { r ->
            val data = r.text(DavNs.CALDAV, "calendar-data").orEmpty()
            if (data.isBlank()) emptyList() else Ics.parseEvents(data, calendarHref, r.href, FileApi.parseEtag(r.text(DavNs.DAV, "getetag")))
        }
    }

    fun tasks(calendarHref: String): List<NextcloudTask> {
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop><d:getetag /><c:calendar-data /></d:prop>
  <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VTODO" /></c:comp-filter></c:filter>
</c:calendar-query>"""
        val responses = session.call(dav(collectionUrl(calendarHref), "REPORT", "1", body), CallOptions(accept = setOf(207))).use(::parse)
        return responses.flatMap { r ->
            val data = r.text(DavNs.CALDAV, "calendar-data").orEmpty()
            if (data.isBlank()) emptyList() else Ics.parseTasks(data, calendarHref, r.href, FileApi.parseEtag(r.text(DavNs.DAV, "getetag")))
        }
    }

    private class Stored(val body: String, val etag: String?)

    private fun fetch(href: String): Stored? = try {
        session.call(session.request(href).get().build()).use { Stored(it.body?.string().orEmpty(), FileApi.parseEtag(it.header("ETag"))) }
    } catch (e: HttpStatusException) {
        if (e.code == 404) null else throw e
    }

    /**
     * Read-modify-write against the real resource: the stored ICS is fetched, only the edited fields are
     * changed, and the PUT carries `If-Match` so a concurrent edit by another client is reported instead
     * of silently overwritten. A new event gets `If-None-Match: *` so it can never replace one.
     */
    fun saveEvent(calendarHref: String, event: CalendarEvent) {
        val name = event.href.takeIf { it.isNotBlank() } ?: "${collectionUrl(calendarHref)}${event.id}.ics"
        saveCalendarObject(name, isNew = event.href.isBlank()) { stored -> Ics.mergeEvent(stored, event) }
    }

    fun saveTask(task: NextcloudTask) {
        val name = task.href.takeIf { it.isNotBlank() } ?: "${collectionUrl(task.calendarHref)}${task.uid}.ics"
        saveCalendarObject(name, isNew = task.href.isBlank()) { stored -> Ics.mergeTask(stored, task) }
    }

    private fun saveCalendarObject(href: String, isNew: Boolean, build: (String?) -> String) {
        var attempts = 0
        while (true) {
            val stored = if (isNew) null else fetch(href)
            val request = session.request(href).put(build(stored?.body).toRequestBody(ics))
            when {
                stored?.etag != null -> request.header("If-Match", "\"${stored.etag}\"")
                stored == null -> request.header("If-None-Match", "*")
            }
            try {
                session.call(request.build()).close()
                return
            } catch (e: HttpStatusException) {
                // 412: edited elsewhere between our GET and PUT — re-read and merge once more.
                if (e.code == 412 && attempts++ < 2) continue
                throw e
            }
        }
    }

    fun deleteCalendarObject(href: String, fallbackCalendarHref: String, uid: String) {
        val target = href.takeIf { it.isNotBlank() } ?: "${collectionUrl(fallbackCalendarHref)}$uid.ics"
        session.call(session.request(target).delete().build(), CallOptions(accept = setOf(404))).close()
    }

    fun createTaskList(name: String) {
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:set><d:prop>
    <d:displayname>${escapeXml(name)}</d:displayname>
    <c:supported-calendar-component-set><c:comp name="VTODO" /></c:supported-calendar-component-set>
  </d:prop></d:set>
</c:mkcalendar>"""
        session.call(dav("$calendarHome${UUID.randomUUID()}/", "MKCALENDAR", null, body)).close()
    }

    fun deleteTaskList(calendarHref: String) {
        session.call(session.request(calendarHref).delete().build(), CallOptions(accept = setOf(404))).close()
    }

    fun renameTaskList(calendarHref: String, newName: String) {
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<d:propertyupdate xmlns:d="DAV:"><d:set><d:prop><d:displayname>${escapeXml(newName)}</d:displayname></d:prop></d:set></d:propertyupdate>"""
        session.call(dav(calendarHref, "PROPPATCH", null, body), CallOptions(accept = setOf(207))).close()
    }

    // ── Notes ───────────────────────────────────────────────────────────────────────────────

    private val notesBase = "/index.php/apps/notes/api/v1/notes"

    private fun notesRequest(path: String) = session.request(path)
        .header("OCS-APIRequest", "true").header("Accept", "application/json")

    fun notes(): List<NextcloudNote> {
        val body = session.call(notesRequest(notesBase).get().build()).use { it.body?.string().orEmpty() }
        val array = try { JSONArray(body) } catch (e: JSONException) {
            throw UnexpectedResponseException("The Notes app did not answer with a list", e)
        }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            if (!o.has("id")) return@mapNotNull null
            NextcloudNote(
                id = o.getInt("id"),
                title = o.optString("title").ifBlank { "Untitled" },
                content = o.optString("content", ""),
                category = o.optString("category", ""),
                modified = o.optLong("modified", 0L),
                favorite = o.optBoolean("favorite", false)
            )
        }
    }

    private fun noteJson(title: String, content: String, category: String, favorite: Boolean? = null) =
        JSONObject().apply {
            put("title", title); put("content", content); put("category", category)
            favorite?.let { put("favorite", it) }
        }.toString().toRequestBody(json)

    fun createNote(title: String, content: String, category: String) {
        // POST is not retried: a lost answer must not create the note twice.
        session.call(notesRequest(notesBase).post(noteJson(title, content, category)).build(), CallOptions(maxAttempts = 1)).close()
    }

    fun updateNote(id: Int, title: String, content: String, category: String, favorite: Boolean) {
        session.call(notesRequest("$notesBase/$id").put(noteJson(title, content, category, favorite)).build()).close()
    }

    fun deleteNote(id: Int) {
        session.call(notesRequest("$notesBase/$id").delete().build(), CallOptions(accept = setOf(404))).close()
    }

    // ── Contacts (CardDAV) ──────────────────────────────────────────────────────────────────

    fun addressBooks(): List<Pair<String, String>> {
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav"><d:prop><d:displayname /><d:resourcetype /></d:prop></d:propfind>"""
        return session.call(dav(addressBookHome, "PROPFIND", "1", body), CallOptions(accept = setOf(207))).use(::parse)
            .filter { it.hasChild(DavNs.DAV, "resourcetype", DavNs.CARDDAV, "addressbook") }
            .map { it.href to (it.text(DavNs.DAV, "displayname")?.takeIf { n -> n.isNotBlank() } ?: "Contacts") }
    }

    fun contacts(addressBookHref: String): List<NextcloudContact> {
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<card:addressbook-query xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
  <d:prop><d:getetag /><card:address-data /></d:prop>
</card:addressbook-query>"""
        val responses = session.call(dav(collectionUrl(addressBookHref), "REPORT", "1", body), CallOptions(accept = setOf(207))).use(::parse)
        return responses.mapNotNull { r ->
            val vcard = r.text(DavNs.CARDDAV, "address-data")?.trim().orEmpty()
            if (vcard.isEmpty()) null
            else Vcard.parse(vcard, addressBookHref, r.href, FileApi.parseEtag(r.text(DavNs.DAV, "getetag")).orEmpty())
        }
    }

    /** Creates or updates a contact and returns the new server ETag ("" when the server sent none). */
    fun saveContact(contact: NextcloudContact): String {
        val isNew = contact.href.isEmpty()
        val href = if (isNew) "${collectionUrl(contact.addressBookHref)}${contact.uid}.vcf" else contact.href
        val request = session.request(href).put(Vcard.build(contact).toRequestBody(vcf))
        if (isNew) request.header("If-None-Match", "*")
        else if (contact.etag.isNotBlank()) request.header("If-Match", "\"${contact.etag}\"")
        return try {
            session.call(request.build()).use { FileApi.parseEtag(it.header("ETag")).orEmpty() }
        } catch (e: HttpStatusException) {
            if (e.code != 412) throw e
            if (!isNew) throw ConflictException("The contact was changed on the server", null)
            // A locally generated UID that already exists is our own earlier upload whose answer got lost.
            session.call(session.request(href).put(Vcard.build(contact).toRequestBody(vcf)).build())
                .use { FileApi.parseEtag(it.header("ETag")).orEmpty() }
        }
    }

    fun deleteContact(contact: NextcloudContact) {
        if (contact.href.isEmpty()) return
        session.call(session.request(contact.href).delete().build(), CallOptions(accept = setOf(404))).close()
    }
}
