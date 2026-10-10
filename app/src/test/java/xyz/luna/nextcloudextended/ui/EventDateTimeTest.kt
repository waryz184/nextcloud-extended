package xyz.luna.nextcloudextended.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.network.Ics
import xyz.luna.nextcloudextended.ui.screens.EventDateTime
import java.time.LocalDate
import java.time.ZoneOffset

class EventDateTimeTest {

    @Test fun withDateKeepsTheTime() {
        assertEquals("2025-07-01 14:30", EventDateTime.withDate("2025-06-28 14:30", LocalDate.of(2025, 7, 1)))
    }

    @Test fun withDateOnAnAllDayValueStaysAllDay() {
        assertEquals("2025-07-01", EventDateTime.withDate("2025-06-28", LocalDate.of(2025, 7, 1)))
    }

    @Test fun withDateOnABlankValueUsesTheDefaultTime() {
        assertEquals("2025-07-01 10:00", EventDateTime.withDate("", LocalDate.of(2025, 7, 1)))
    }

    @Test fun withTimeKeepsTheDateAndPadsTheClock() {
        assertEquals("2025-06-28 09:05", EventDateTime.withTime("2025-06-28 14:30", 9, 5))
        assertEquals("2025-06-28 09:05", EventDateTime.withTime("2025-06-28", 9, 5))
    }

    @Test fun movingTheStartMovesTheEndAndKeepsTheDuration() {
        assertEquals("2025-06-29 13:00", EventDateTime.shiftEnd("2025-06-28 10:00", "2025-06-29 11:00", "2025-06-28 12:00"))
        assertEquals("2025-06-28 13:30", EventDateTime.shiftEnd("2025-06-28 10:00", "2025-06-28 12:00", "2025-06-28 11:30"))
        assertEquals("", EventDateTime.shiftEnd("2025-06-28 10:00", "2025-06-28 12:00", ""))
    }

    @Test fun endBeforeStartIsDetectedOnlyWhenBothAreKnown() {
        assertTrue(EventDateTime.endBeforeStart("2025-06-28 10:00", "2025-06-28 09:59"))
        assertFalse(EventDateTime.endBeforeStart("2025-06-28 10:00", "2025-06-28 10:00"))
        assertFalse(EventDateTime.endBeforeStart("2025-06-28 10:00", ""))
        assertFalse(EventDateTime.endBeforeStart("2025-06-28", "2025-06-28"))
    }

    @Test fun parseRejectsGarbage() {
        assertNull(EventDateTime.parse("tomorrow"))
        assertNull(EventDateTime.parse("2025-13-45 99:99"))
    }

    @Test fun anEventWithoutAnEndIsWrittenWithoutDtend() {
        // A blank end used to produce "DTEND:" (no value), which the server rejects with HTTP 415.
        val ics = Ics.mergeEvent(null, CalendarEvent("n", "No end", null, "2025-06-28 10:00", "", null, "/c/"), stamp = "20250101T000000Z", zone = ZoneOffset.UTC)
        assertTrue(ics.contains("DTSTART:20250628T100000Z"))
        assertFalse(Regex("(?m)^DTEND").containsMatchIn(ics))
    }
}
