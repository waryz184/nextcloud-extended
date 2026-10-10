package xyz.luna.nextcloudextended.data.network

import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Pure, stateless (de)serialization + URL helpers extracted from CalDavClient so they can be
// unit-tested on the JVM without an Android runtime. Kept `internal` to the module.

// Encodes each path segment of a WebDAV href for safe inclusion in a request URL. Hrefs are stored
// decoded throughout the app; this re-encodes reserved characters (space, #, ?, accents, …) per
// segment while preserving the '/' separators and any leading/trailing slash. Identity on plain
// ASCII paths, so existing setups are unaffected. Round-trips a literal '+' correctly (→ %2B).
internal fun encodePath(path: String): String =
    path.split("/").joinToString("/") { segment ->
        if (segment.isEmpty()) segment
        else URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

// WebDAV rename and folder creation accept a single resource name, never a relative path.
internal fun isValidDavName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name

// Escapes a value for inclusion in a WebDAV/CalDAV XML body (e.g. <d:displayname>).
// Without this, a list named "R&D" or "A < B" produces malformed XML and the request fails.
internal fun escapeXml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

internal fun unescapeXml(xml: String): String {
    var r = xml.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
    r = Regex("&#([0-9]+);").replace(r) { it.groupValues[1].toInt().toChar().toString() }
    r = Regex("&#x([0-9a-fA-F]+);").replace(r) { it.groupValues[1].toInt(16).toChar().toString() }
    return r
}

// Escapes an iCalendar TEXT value per RFC 5545 §3.3.11: backslash, semicolon, comma and
// newlines must be escaped, otherwise a summary like "Budget, plan; review" is mis-parsed.
internal fun escapeIcsText(s: String): String =
    s.replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\n", "\\n")

// Inverse of escapeIcsText. Single pass so "C:\\new" (escaped backslash) is not read as a
// newline. Also fixes display of events written by other clients that escape ,/;/\ correctly.
internal fun unescapeIcsText(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (val n = s[i + 1]) {
                'n', 'N' -> sb.append('\n')
                '\\' -> sb.append('\\')
                ',' -> sb.append(',')
                ';' -> sb.append(';')
                else -> sb.append(n)
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

// Builds a date property line, marking 8-digit (date-only) values with VALUE=DATE so the
// server doesn't reject e.g. "DUE:20250628Z" (a date with a UTC suffix is invalid iCal).
internal fun icsDateLine(name: String, value: String?): String? {
    if (value == null) return null
    return if (value.length == 8) "$name;VALUE=DATE:$value" else "$name:$value"
}

/**
 * The time zone events are shown and edited in (Settings → Time zone). The app sets it at start-up and
 * whenever the user changes it; a blank or unknown id means "follow the device".
 */
object CalendarTimeZone {
    @Volatile var current: ZoneId = ZoneId.systemDefault()

    fun resolve(id: String?): ZoneId =
        id?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
}

private val ICS_LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
private val ICS_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
private val DISPLAY_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val TZID_PARAM = Regex("TZID=\"?([^\";:]+)\"?", RegexOption.IGNORE_CASE)

// Parses an ICS date/date-time ("20250628", "20250628T140000Z") into the app's display form
// ("YYYY-MM-DD" or "YYYY-MM-DD HH:MM"), without any time zone conversion.
internal fun formatIcsDate(dateStr: String?): String? = formatIcsDate(dateStr, "", ZoneOffset.UTC)

/**
 * Same, but a date-time is expressed in [zone]: a UTC value ("…Z") and a value carrying a `TZID`
 * parameter (given in [params], e.g. "TZID=Europe/Paris") are converted; a floating value (no zone) and
 * date-only values are shown as written.
 */
internal fun formatIcsDate(dateStr: String?, params: String, zone: ZoneId): String? {
    if (dateStr == null) return null
    val clean = dateStr.trim()
    if (clean.length >= 8) {
        val y = clean.substring(0, 4); val m = clean.substring(4, 6); val d = clean.substring(6, 8)
        if (clean.length >= 15 && clean.contains("T")) {
            val local = runCatching { LocalDateTime.parse(clean.substring(0, 15), ICS_LOCAL) }.getOrNull()
            if (local != null) {
                val source: ZoneId? = if (clean.endsWith("Z", ignoreCase = true)) ZoneOffset.UTC
                    else TZID_PARAM.find(params)?.groupValues?.get(1)?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                val shown = if (source == null) local else local.atZone(source).withZoneSameInstant(zone).toLocalDateTime()
                return DISPLAY_LOCAL.format(shown)
            }
            val h = clean.substring(9, 11); val min = clean.substring(11, 13)
            return "$y-$m-$d $h:$min"
        }
        return "$y-$m-$d"
    }
    return dateStr
}

// Inverse of formatIcsDate: the app's display form back into an ICS value (UTC, no zone conversion).
internal fun formatToIcsDate(dateTimeStr: String?): String? = formatToIcsDate(dateTimeStr, ZoneOffset.UTC)

// Inverse of the zone-aware formatIcsDate: a display date-time typed in [zone] becomes a UTC ICS value.
internal fun formatToIcsDate(dateTimeStr: String?, zone: ZoneId): String? {
    if (dateTimeStr == null) return null
    val clean = dateTimeStr.trim()
    if (clean.length == 16 && clean[4] == '-' && clean[7] == '-' && clean[10] == ' ' && clean[13] == ':') {
        val local = runCatching { LocalDateTime.parse(clean, DISPLAY_LOCAL) }.getOrNull()
        if (local != null) return ICS_UTC.format(local.atZone(zone).withZoneSameInstant(ZoneOffset.UTC))
    }
    if (clean.length == 10 && clean[4] == '-' && clean[7] == '-') {
        return "${clean.substring(0,4)}${clean.substring(5,7)}${clean.substring(8,10)}"
    }
    return clean.replace("-", "").replace(":", "").replace(" ", "T")
}

// Normalises a vCard BDAY ("19900615", "1990-06-15", "1990-06-15T…") to "YYYY-MM-DD".
internal fun normalizeBday(raw: String): String? {
    val v = raw.substringBefore("T")
    return when {
        Regex("^\\d{8}$").matches(v) -> "${v.substring(0, 4)}-${v.substring(4, 6)}-${v.substring(6, 8)}"
        Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(v) -> v
        else -> v.takeIf { it.isNotBlank() }
    }
}

// Splits a vCard value on UNescaped separators (';' for structured, ',' for lists), unescaping each part.
internal fun splitVcardComponents(value: String, sep: Char): List<String> {
    val parts = mutableListOf<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c == '\\' && i + 1 < value.length -> { sb.append(c); sb.append(value[i + 1]); i += 2 }
            c == sep -> { parts.add(sb.toString()); sb.clear(); i++ }
            else -> { sb.append(c); i++ }
        }
    }
    parts.add(sb.toString())
    return parts.map { unescapeIcsText(it).trim() }
}

// Pulls the meaningful TYPE token out of a vCard parameter string, skipping generic markers.
internal fun extractType(params: String): String =
    Regex("TYPE=([^;:]*)", RegexOption.IGNORE_CASE).findAll(params)
        .flatMap { it.groupValues[1].split(",").asSequence() }
        .map { it.trim().uppercase() }
        .firstOrNull { it.isNotBlank() && it !in setOf("VOICE", "INTERNET", "PREF") } ?: ""
