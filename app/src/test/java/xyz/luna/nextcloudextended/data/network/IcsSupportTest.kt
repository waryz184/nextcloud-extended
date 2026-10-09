package xyz.luna.nextcloudextended.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.NextcloudTask

class IcsSupportTest {

    private val stored = listOf(
        "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Thunderbird//EN",
        "BEGIN:VTIMEZONE", "TZID:Europe/Paris", "END:VTIMEZONE",
        "BEGIN:VEVENT",
        "UID:evt-1",
        "SUMMARY:Team sync",
        "DTSTART;TZID=Europe/Paris:20250310T090000",
        "DTEND;TZID=Europe/Paris:20250310T100000",
        "ORGANIZER;CN=Boss:mailto:boss@example.com",
        "ATTENDEE;PARTSTAT=ACCEPTED:mailto:me@example.com",
        "CATEGORIES:work",
        "X-CUSTOM:keep-me",
        "SEQUENCE:3",
        "BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:Reminder text", "TRIGGER:-PT15M", "END:VALARM",
        "END:VEVENT", "END:VCALENDAR"
    ).joinToString("\r\n", postfix = "\r\n")

    private fun event(summary: String = "Team sync", start: String? = "2025-03-10 09:00", end: String? = "2025-03-10 10:00") =
        CalendarEvent("evt-1", summary, null, start, end, null, "/cal/", false, "/cal/abc.ics", "etag1")

    @Test fun editingTheTitleKeepsEveryOtherProperty() {
        val merged = Ics.mergeEvent(stored, event(summary = "Team sync (moved)"), stamp = "20250101T000000Z")
        assertTrue(merged.contains("SUMMARY:Team sync (moved)"))
        assertTrue("alarm must survive", merged.contains("BEGIN:VALARM") && merged.contains("TRIGGER:-PT15M"))
        assertTrue(merged.contains("ATTENDEE;PARTSTAT=ACCEPTED:mailto:me@example.com"))
        assertTrue(merged.contains("ORGANIZER;CN=Boss:mailto:boss@example.com"))
        assertTrue(merged.contains("CATEGORIES:work"))
        assertTrue(merged.contains("X-CUSTOM:keep-me"))
        assertTrue("time zone definition must survive", merged.contains("BEGIN:VTIMEZONE"))
        assertTrue("untouched times keep their TZID", merged.contains("DTSTART;TZID=Europe/Paris:20250310T090000"))
        assertTrue(merged.contains("DTEND;TZID=Europe/Paris:20250310T100000"))
        assertEquals("exactly one SEQUENCE, incremented", 1, Regex("(?m)^SEQUENCE:").findAll(merged).count())
        assertTrue(merged.contains("SEQUENCE:4"))
        assertEquals(1, Regex("(?m)^SUMMARY:").findAll(merged).count())
        assertTrue(merged.contains("LAST-MODIFIED:20250101T000000Z"))
    }

    @Test fun changingTheTimeWritesTheNewValue() {
        val merged = Ics.mergeEvent(stored, event(start = "2025-03-10 11:00", end = "2025-03-10 12:00"), stamp = "20250101T000000Z")
        assertTrue(merged.contains("DTSTART:20250310T110000Z"))
        assertFalse(merged.contains("TZID=Europe/Paris:20250310T090000"))
    }

    @Test fun mergedOutputRoundTripsThroughTheParser() {
        val merged = Ics.mergeEvent(stored, event(summary = "Renamed"), stamp = "20250101T000000Z")
        val parsed = Ics.parseEvents(merged, "/cal/", "/cal/abc.ics", "etag1").single()
        assertEquals("Renamed", parsed.summary)
        assertEquals("evt-1", parsed.id)
        assertEquals("2025-03-10 09:00", parsed.startTime)
    }

    @Test fun freshEventIsAValidCalendarObject() {
        val ics = Ics.mergeEvent(null, CalendarEvent("new-1", "Lunch, with; friends", "Line1\nLine2", "2025-05-01 12:00", "2025-05-01 13:00", "Café", "/cal/"), stamp = "20250101T000000Z")
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.contains("\r\nDTSTAMP:20250101T000000Z\r\n"))
        assertTrue(ics.contains("UID:new-1"))
        assertTrue(ics.contains("SUMMARY:Lunch\\, with\\; friends"))
        assertTrue(ics.contains("DESCRIPTION:Line1\\nLine2"))
        assertTrue(ics.trimEnd().endsWith("END:VCALENDAR"))
        val parsed = Ics.parseEvents(ics, "/cal/").single()
        assertEquals("Lunch, with; friends", parsed.summary)
        assertEquals("Line1\nLine2", parsed.description)
    }

    @Test fun parserIgnoresAlarmDescriptionAndHandlesParameterisedSummary() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:u\r\nSUMMARY;LANGUAGE=fr:Réunion\r\nDTSTART:20250101T100000Z\r\n" +
            "BEGIN:VALARM\r\nDESCRIPTION:Default Mozilla Description\r\nEND:VALARM\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val event = Ics.parseEvents(ics, "/c/").single()
        assertEquals("Réunion", event.summary)
        assertEquals("the alarm's text is not the event description", null, event.description)
    }

    @Test fun parserFlagsRecurrenceInstances() {
        val ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:u\r\nRECURRENCE-ID:20250101T100000Z\r\nSUMMARY:x\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:u\r\nRECURRENCE-ID:20250108T100000Z\r\nSUMMARY:x\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val events = Ics.parseEvents(ics, "/c/")
        assertEquals(2, events.size)
        assertTrue(events.all { it.isRecurringInstance })
    }

    @Test fun foldingKeepsLinesUnder75OctetsAndRoundTrips() {
        val long = "é".repeat(100) + " end"
        val folded = Ics.fold("DESCRIPTION:$long")
        assertTrue(folded.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertEquals("DESCRIPTION:$long", Ics.unfold(folded).single())
    }

    @Test fun completingATaskSetsCompletedAndReopeningClearsIt() {
        val todo = listOf("BEGIN:VCALENDAR", "BEGIN:VTODO", "UID:t1", "SUMMARY:Buy milk", "RELATED-TO:parent-1", "PRIORITY:1", "STATUS:NEEDS-ACTION", "END:VTODO", "END:VCALENDAR")
            .joinToString("\r\n", postfix = "\r\n")
        val task = NextcloudTask("t1", "Buy milk", null, "COMPLETED", null, "/cal/", "/cal/t1.ics", "e")
        val done = Ics.mergeTask(todo, task, stamp = "20250202T000000Z")
        assertTrue(done.contains("STATUS:COMPLETED"))
        assertTrue(done.contains("COMPLETED:20250202T000000Z"))
        assertTrue(done.contains("PERCENT-COMPLETE:100"))
        assertTrue("sub-task link and priority survive", done.contains("RELATED-TO:parent-1") && done.contains("PRIORITY:1"))

        val reopened = Ics.mergeTask(done, task.copy(status = "NEEDS-ACTION"), stamp = "20250203T000000Z")
        assertTrue(reopened.contains("STATUS:NEEDS-ACTION"))
        assertFalse(Regex("(?m)^COMPLETED:").containsMatchIn(reopened))
        assertFalse(reopened.contains("PERCENT-COMPLETE"))
        assertTrue(reopened.contains("RELATED-TO:parent-1"))
    }

    @Test fun mergeTargetsTheMasterEventNotAnOverride() {
        val series = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:s\r\nSUMMARY:Series\r\nRRULE:FREQ=WEEKLY\r\nDTSTART:20250101T100000Z\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:s\r\nRECURRENCE-ID:20250108T100000Z\r\nSUMMARY:Override\r\nDTSTART:20250108T120000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val merged = Ics.mergeEvent(series, CalendarEvent("s", "Series v2", null, "2025-01-01 10:00", null, null, "/c/"), stamp = "20250101T000000Z")
        assertTrue(merged.contains("SUMMARY:Series v2"))
        assertTrue("override untouched", merged.contains("SUMMARY:Override"))
        assertTrue(merged.contains("RRULE:FREQ=WEEKLY"))
    }
}
