package xyz.luna.nextcloudextended.data.network

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.NextcloudContact
import xyz.luna.nextcloudextended.data.model.NextcloudTask
import xyz.luna.nextcloudextended.data.model.TrashedFile

class GroupwareApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: GroupwareApi
    private val requests = mutableListOf<RecordedRequest>()
    private val bodies = mutableListOf<String>()
    private var handler: (RecordedRequest, String) -> MockResponse = { _, _ -> MockResponse().setResponseCode(404) }

    private fun session() = DavSession(server.url("/").toString().trimEnd('/'), "u-alice", "Basic x", allowInsecureHttp = true, sleeper = { })

    @Before fun setUp() {
        server = MockWebServer().also {
            it.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body.readUtf8()
                    requests.add(request); bodies.add(body)
                    return handler(request, body)
                }
            }
            it.start()
        }
        api = GroupwareApi(session())
    }

    @After fun tearDown() { server.shutdown() }

    private val calendars = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/">
      <d:response><d:href>/remote.php/dav/calendars/u-alice/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
      <d:response><d:href>/remote.php/dav/calendars/u-alice/personal/</d:href><d:propstat><d:prop><d:displayname>Personal</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><c:supported-calendar-component-set><c:comp name="VEVENT"/><c:comp name="VTODO"/></c:supported-calendar-component-set><a:calendar-color>#0082c9FF</a:calendar-color></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
      <d:response><d:href>/remote.php/dav/calendars/u-alice/tasks/</d:href><d:propstat><d:prop><d:displayname>Tasks</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
      <d:response><d:href>/remote.php/dav/calendars/u-alice/inbox/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/><c:schedule-inbox/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
    </d:multistatus>"""

    @Test fun calendarsAreSplitIntoEventCalendarsAndTaskLists() {
        handler = { r, _ -> if (r.method == "PROPFIND") MockResponse().setResponseCode(207).setBody(calendars) else MockResponse().setResponseCode(404) }
        val result = api.calendars()
        assertEquals(listOf("/remote.php/dav/calendars/u-alice/personal/"), result.events.map { it.href })
        assertEquals("#0082c9", result.events.single().colorHex)
        assertEquals(listOf("personal", "tasks"), result.taskLists.map { it.first.trimEnd('/').substringAfterLast('/') })
        assertTrue("the DAV home uses the real account id", requests.first().path!!.contains("/calendars/u-alice/"))
    }

    private val storedIcs = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:evt-9\r\nSUMMARY:Dentist\r\nDTSTART:20250601T080000Z\r\nDTEND:20250601T090000Z\r\n" +
        "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT1H\r\nDESCRIPTION:ring\r\nEND:VALARM\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"

    private fun report(href: String) = """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>$href</d:href><d:propstat><d:prop>
        <d:getetag>"e-1"</d:getetag><c:calendar-data><![CDATA[$storedIcs]]></c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""

    @Test fun eventsKeepTheirRealResourcePathAndEtag() {
        handler = { r, _ -> if (r.method == "REPORT") MockResponse().setResponseCode(207).setBody(report("/remote.php/dav/calendars/u-alice/personal/9f8e7d6c-random.ics")) else MockResponse().setResponseCode(404) }
        val event = api.events("/remote.php/dav/calendars/u-alice/personal/").single()
        assertEquals("evt-9", event.id)
        assertEquals("/remote.php/dav/calendars/u-alice/personal/9f8e7d6c-random.ics", event.href)
        assertEquals("e-1", event.etag)
    }

    @Test fun editingWritesBackToTheRealResourceWithIfMatchAndKeepsTheAlarm() {
        val href = "/remote.php/dav/calendars/u-alice/personal/9f8e7d6c-random.ics"
        handler = { r, _ ->
            when (r.method) {
                "GET" -> MockResponse().setBody(storedIcs).setHeader("ETag", "\"e-1\"")
                "PUT" -> MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val event = CalendarEvent("evt-9", "Dentist (moved)", null, "2025-06-01 08:00", "2025-06-01 09:00", null, "/remote.php/dav/calendars/u-alice/personal/", false, href, "e-1")
        api.saveEvent(event.calendarHref, event)
        val put = requests.last { it.method == "PUT" }
        assertEquals(href, put.path)
        assertEquals("\"e-1\"", put.getHeader("If-Match"))
        val sent = bodies[requests.indexOf(put)]
        assertTrue(sent.contains("SUMMARY:Dentist (moved)"))
        assertTrue("reminder survives the edit", sent.contains("TRIGGER:-PT1H"))
        assertFalse("must not have created a duplicate <uid>.ics", requests.any { it.path!!.endsWith("/evt-9.ics") })
    }

    @Test fun concurrentEditIsDetectedThenRetriedAgainstTheNewVersion() {
        val href = "/remote.php/dav/calendars/u-alice/personal/x.ics"
        var puts = 0
        handler = { r, _ ->
            when (r.method) {
                "GET" -> MockResponse().setBody(storedIcs).setHeader("ETag", "\"e-${puts + 1}\"")
                "PUT" -> if (puts++ == 0) MockResponse().setResponseCode(412) else MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val event = CalendarEvent("evt-9", "Dentist", null, "2025-06-01 08:00", null, null, "/c/", false, href, "e-1")
        api.saveEvent("/c/", event)
        assertEquals(2, requests.count { it.method == "PUT" })
        assertEquals("\"e-2\"", requests.last { it.method == "PUT" }.getHeader("If-Match"))
    }

    @Test fun newEventsAreCreatedOnlyIfTheNameIsFree() {
        handler = { r, _ -> if (r.method == "PUT") MockResponse().setResponseCode(201) else MockResponse().setResponseCode(404) }
        api.saveEvent("/remote.php/dav/calendars/u-alice/personal/", CalendarEvent("new-1", "Party", null, "2025-07-01 20:00", "2025-07-01 23:00", null, "/remote.php/dav/calendars/u-alice/personal/"))
        val put = requests.single()
        assertEquals("/remote.php/dav/calendars/u-alice/personal/new-1.ics", put.path)
        assertEquals("*", put.getHeader("If-None-Match"))
        assertEquals("text/calendar; charset=utf-8", put.getHeader("Content-Type"))
    }

    @Test fun deleteUsesTheRealHrefAndToleratesAnAlreadyDeletedEvent() {
        handler = { _, _ -> MockResponse().setResponseCode(404) }
        api.deleteCalendarObject("/remote.php/dav/calendars/u-alice/personal/real-name.ics", "/remote.php/dav/calendars/u-alice/personal/", "evt-9")
        assertEquals("/remote.php/dav/calendars/u-alice/personal/real-name.ics", requests.single().path)
        // Legacy rows without an href fall back to <uid>.ics
        api.deleteCalendarObject("", "/remote.php/dav/calendars/u-alice/personal/", "evt-9")
        assertTrue(requests.last().path!!.endsWith("/personal/evt-9.ics"))
    }

    @Test fun taskCompletionIsWrittenWithCompletedStamp() {
        val todo = "BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\nUID:t1\r\nSUMMARY:Pay rent\r\nSTATUS:NEEDS-ACTION\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        handler = { r, _ -> when (r.method) { "GET" -> MockResponse().setBody(todo).setHeader("ETag", "\"t-1\""); "PUT" -> MockResponse().setResponseCode(204); else -> MockResponse().setResponseCode(404) } }
        api.saveTask(NextcloudTask("t1", "Pay rent", null, "COMPLETED", null, "/c/", "/c/t1.ics", "t-1"))
        val sent = bodies[requests.indexOfLast { it.method == "PUT" }]
        assertTrue(sent.contains("STATUS:COMPLETED") && sent.contains("PERCENT-COMPLETE:100") && Regex("(?m)^COMPLETED:\\d{8}T\\d{6}Z").containsMatchIn(sent))
    }

    // ── Notes / contacts ────────────────────────────────────────────────────────────────────

    @Test fun notesTolerateMissingFields() {
        handler = { _, _ -> MockResponse().setBody("""[{"id":1,"title":"A","content":"x","category":"c","modified":5,"favorite":true},{"id":2},{"nope":true}]""") }
        val notes = api.notes()
        assertEquals(2, notes.size)
        assertEquals("Untitled", notes[1].title)
        assertTrue(notes[0].favorite)
    }

    @Test fun notesNotInstalledIsA404NotACrash() {
        handler = { _, _ -> MockResponse().setResponseCode(404).setBody("<html>Not Found</html>") }
        try { api.notes(); fail() } catch (e: HttpStatusException) { assertEquals(FailureKind.NOT_FOUND, e.failureKind()) }
    }

    @Test fun creatingANoteIsNeverRetried() {
        handler = { _, _ -> MockResponse().setResponseCode(503) }
        try { api.createNote("t", "c", ""); fail() } catch (e: HttpStatusException) { }
        assertEquals("a retried POST could create the note twice", 1, requests.size)
    }

    private fun contact(href: String = "", etag: String = "") = NextcloudContact(
        uid = "c-1", fullName = "Ada Lovelace", phones = emptyList(), emails = emptyList(), organization = null,
        addressBookHref = "/remote.php/dav/addressbooks/users/u-alice/contacts/", href = href, etag = etag
    )

    @Test fun updatingAContactUsesIfMatchAndReportsConflicts() {
        handler = { _, _ -> MockResponse().setResponseCode(412) }
        try {
            api.saveContact(contact("/remote.php/dav/addressbooks/users/u-alice/contacts/c-1.vcf", "old"))
            fail()
        } catch (e: ConflictException) { }
        assertEquals("\"old\"", requests.single().getHeader("If-Match"))
    }

    @Test fun aLostAnswerOnContactCreationDoesNotWedgeTheSync() {
        var puts = 0
        handler = { r, _ -> if (r.method == "PUT") { if (puts++ == 0) MockResponse().setResponseCode(412) else MockResponse().setResponseCode(201).setHeader("ETag", "\"fresh\"") } else MockResponse().setResponseCode(404) }
        assertEquals("fresh", api.saveContact(contact()))
        assertEquals("*", requests.first().getHeader("If-None-Match"))
        assertNull("the retry has no precondition", requests.last().getHeader("If-None-Match"))
    }

    // ── Trash / versions ────────────────────────────────────────────────────────────────────

    @Test fun trashListingAndRestoreUseTheTrashbinEndpoints() {
        val xml = """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
          <d:response><d:href>/remote.php/dav/trashbin/u-alice/trash/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/remote.php/dav/trashbin/u-alice/trash/report.pdf.d1735689600</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>2048</d:getcontentlength><oc:fileid>77</oc:fileid>
            <nc:trashbin-filename>report.pdf</nc:trashbin-filename><nc:trashbin-original-location>Docs/report.pdf</nc:trashbin-original-location><nc:trashbin-deletion-time>1735689600</nc:trashbin-deletion-time></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>"""
        handler = { r, _ -> if (r.method == "PROPFIND") MockResponse().setResponseCode(207).setBody(xml) else MockResponse().setResponseCode(201) }
        val extras = DavExtras(session())
        val item: TrashedFile = extras.listTrash().single()
        assertEquals("report.pdf", item.name)
        assertEquals("Docs/report.pdf", item.originalLocation)
        assertEquals(2048L, item.size)
        extras.restore(item)
        val move = requests.last()
        assertEquals("MOVE", move.method)
        assertTrue(move.getHeader("Destination")!!.endsWith("/remote.php/dav/trashbin/u-alice/restore/report.pdf.d1735689600"))
    }

    @Test fun versionsAreListedNewestFirstAndRestoredThroughTheRestoreEndpoint() {
        val xml = """<d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/remote.php/dav/versions/u-alice/versions/42/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/remote.php/dav/versions/u-alice/versions/42/1700000000</d:href><d:propstat><d:prop><d:getcontentlength>10</d:getcontentlength><d:getcontenttype>text/plain</d:getcontenttype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/remote.php/dav/versions/u-alice/versions/42/1710000000</d:href><d:propstat><d:prop><d:getcontentlength>20</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>"""
        handler = { r, _ -> if (r.method == "PROPFIND") MockResponse().setResponseCode(207).setBody(xml) else MockResponse().setResponseCode(201) }
        val extras = DavExtras(session())
        val versions = extras.listVersions("42")
        assertEquals(listOf(1710000000_000L, 1700000000_000L), versions.map { it.lastModifiedMillis })
        extras.restoreVersion(versions.first())
        assertTrue(requests.last().getHeader("Destination")!!.endsWith("/remote.php/dav/versions/u-alice/restore/target"))
    }
}
