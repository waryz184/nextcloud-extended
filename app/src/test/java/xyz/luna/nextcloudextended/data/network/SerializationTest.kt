package xyz.luna.nextcloudextended.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// JVM unit tests for the pure (de)serialization + URL helpers. These guard the round-trips that
// have historically regressed (ICS escaping, date formats, WebDAV path encoding).
class SerializationTest {

    // ── encodePath ──────────────────────────────────────────────────────────────

    @Test fun encodePath_isIdentityOnPlainAsciiPaths() {
        assertEquals("/remote.php/dav/files/alice/doc.pdf", encodePath("/remote.php/dav/files/alice/doc.pdf"))
    }

    @Test fun encodePath_encodesSpacesAndReservedChars() {
        assertEquals("/files/My%20Documents/f%231.pdf", encodePath("/files/My Documents/f#1.pdf"))
    }

    @Test fun encodePath_preservesTrailingAndLeadingSlash() {
        assertEquals("/dav/calendars/bob/", encodePath("/dav/calendars/bob/"))
    }

    @Test fun encodePath_preservesLiteralPlus() {
        // A real '+' in a name must survive as %2B, not become a space.
        assertEquals("/files/a%2Bb.txt", encodePath("/files/a+b.txt"))
    }

    // ── ICS text escaping ───────────────────────────────────────────────────────

    @Test fun icsText_escapesSpecialChars() {
        assertEquals("Budget\\, plan\\; review", escapeIcsText("Budget, plan; review"))
    }

    @Test fun icsText_roundTripsCommaSemicolonNewline() {
        val original = "line1\nBudget, plan; review"
        assertEquals(original, unescapeIcsText(escapeIcsText(original)))
    }

    @Test fun icsText_roundTripsBackslashWithoutBecomingNewline() {
        // "C:\new" must not be read back as a carriage/newline.
        val original = "C:\\new"
        assertEquals(original, unescapeIcsText(escapeIcsText(original)))
    }

    // ── XML escaping ────────────────────────────────────────────────────────────

    @Test fun xml_escapeAndUnescapeRoundTrip() {
        val original = "R&D <team> \"quoted\" 'apos'"
        assertEquals(original, unescapeXml(escapeXml(original)))
    }

    @Test fun xml_unescapesNumericEntities() {
        assertEquals("A", unescapeXml("&#65;"))
        assertEquals("A", unescapeXml("&#x41;"))
    }

    // ── Date formats ────────────────────────────────────────────────────────────

    @Test fun formatToIcsDate_dateTime() {
        assertEquals("20250628T143000Z", formatToIcsDate("2025-06-28 14:30"))
    }

    @Test fun formatToIcsDate_dateOnly() {
        assertEquals("20250628", formatToIcsDate("2025-06-28"))
    }

    @Test fun formatIcsDate_dateTime() {
        assertEquals("2025-06-28 14:30", formatIcsDate("20250628T143000Z"))
    }

    @Test fun formatIcsDate_dateOnly() {
        assertEquals("2025-06-28", formatIcsDate("20250628"))
    }

    @Test fun icsDateLine_marksDateOnlyValues() {
        assertEquals("DUE;VALUE=DATE:20250628", icsDateLine("DUE", "20250628"))
        assertEquals("DTSTART:20250628T140000Z", icsDateLine("DTSTART", "20250628T140000Z"))
        assertNull(icsDateLine("DUE", null))
    }

    // ── vCard helpers ───────────────────────────────────────────────────────────

    @Test fun normalizeBday_variousInputs() {
        assertEquals("1990-06-15", normalizeBday("19900615"))
        assertEquals("1990-06-15", normalizeBday("1990-06-15"))
        assertEquals("1990-06-15", normalizeBday("1990-06-15T00:00:00"))
    }

    @Test fun splitVcardComponents_respectsEscapedSeparators() {
        assertEquals(listOf("a", "b", "c"), splitVcardComponents("a;b;c", ';'))
        assertEquals(listOf("a;b", "c"), splitVcardComponents("a\\;b;c", ';'))
    }

    @Test fun extractType_picksMeaningfulTokenSkippingGenerics() {
        assertEquals("HOME", extractType("TYPE=HOME,VOICE"))
        assertEquals("CELL", extractType(";TYPE=CELL"))
        assertEquals("", extractType(";TYPE=PREF"))
        assertEquals("", extractType(""))
    }
}
