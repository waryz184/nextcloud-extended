package xyz.luna.nextcloudextended.data.network

import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.NextcloudTask
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * iCalendar reading and — more importantly — *merging*. Editing an event or task used to rebuild the
 * whole resource from the five fields the UI knows, silently deleting reminders (VALARM), attendees,
 * categories, sub-task links, time zone definitions and every `X-` property written by other clients.
 * [mergeEvent] / [mergeTask] change only what the user edited and carry everything else over.
 */
internal object Ics {

    private val utcStamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    fun now(): String = utcStamp.format(Instant.now())

    /** RFC 5545 §3.1: a CRLF followed by a space/tab continues the previous line. */
    fun unfold(raw: String): List<String> {
        val lines = ArrayList<String>()
        for (physical in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if (physical.isEmpty()) continue
            if ((physical[0] == ' ' || physical[0] == '\t') && lines.isNotEmpty()) {
                lines[lines.size - 1] = lines.last() + physical.substring(1)
            } else {
                lines.add(physical)
            }
        }
        return lines
    }

    /** Folds at 75 octets without splitting a UTF-8 sequence. */
    fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
        val out = StringBuilder()
        var current = StringBuilder()
        var octets = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chunk = String(Character.toChars(cp))
            val size = chunk.toByteArray(Charsets.UTF_8).size
            if (octets + size > limit) {
                out.append(current).append("\r\n ")
                current = StringBuilder()
                octets = 0
                limit = 74 // the leading space counts
            }
            current.append(chunk)
            octets += size
            i += Character.charCount(cp)
        }
        return out.append(current).toString()
    }

    class Prop(val name: String, val params: String, val value: String, val line: String)

    fun parseLine(line: String): Prop? {
        val colon = indexOfValueColon(line)
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val name = head.substringBefore(';').uppercase()
        val params = if (head.contains(';')) head.substringAfter(';') else ""
        return Prop(name, params, line.substring(colon + 1), line)
    }

    /** The first ':' that is not inside a quoted parameter value. */
    private fun indexOfValueColon(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) return i
            }
        }
        return -1
    }

    /** Lines of the first `BEGIN:<name>` … `END:<name>` block, excluding nested components. */
    private class Component(val start: Int, val end: Int)

    private fun findComponent(lines: List<String>, name: String, skipRecurrenceOverrides: Boolean): Component? {
        var i = 0
        while (i < lines.size) {
            if (lines[i].equals("BEGIN:$name", ignoreCase = true)) {
                var depth = 0
                var j = i
                var hasRecurrenceId = false
                while (j < lines.size) {
                    val upper = lines[j].uppercase()
                    if (upper.startsWith("BEGIN:")) depth++
                    else if (upper.startsWith("END:")) { depth--; if (depth == 0) break }
                    else if (depth == 1 && upper.startsWith("RECURRENCE-ID")) hasRecurrenceId = true
                    j++
                }
                if (!(skipRecurrenceOverrides && hasRecurrenceId)) return Component(i, minOf(j, lines.size - 1))
                i = j
            }
            i++
        }
        return null
    }

    private class OwnProp(val index: Int, val prop: Prop)

    /** Direct properties of a component (nested blocks such as VALARM are skipped), with their line index. */
    private fun ownProps(lines: List<String>, component: Component): List<OwnProp> {
        val result = ArrayList<OwnProp>()
        var depth = 0
        for (idx in component.start..component.end) {
            val line = lines[idx]
            val upper = line.uppercase()
            if (upper.startsWith("BEGIN:")) { depth++; continue }
            if (upper.startsWith("END:")) { depth--; continue }
            if (depth == 1) parseLine(line)?.let { result.add(OwnProp(idx, it)) }
        }
        return result
    }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────

    /** Date-times are converted to [zone] for display (see [formatIcsDate]). */
    fun parseEvents(ics: String, calendarHref: String, href: String = "", etag: String? = null, zone: ZoneId = ZoneOffset.UTC): List<CalendarEvent> {
        val lines = unfold(ics)
        val events = ArrayList<CalendarEvent>()
        var from = 0
        while (from < lines.size) {
            val comp = findComponentFrom(lines, "VEVENT", from) ?: break
            val props = ownProps(lines, comp).map { it.prop }
            fun first(name: String) = props.firstOrNull { it.name == name }
            val uid = first("UID")?.value?.trim().orEmpty().ifEmpty { UUID.randomUUID().toString() }
            val recurring = props.any { it.name == "RECURRENCE-ID" || it.name == "RRULE" }
            events.add(
                CalendarEvent(
                    id = uid,
                    summary = first("SUMMARY")?.value?.let { unescapeIcsText(it).trim() } ?: "No Title",
                    description = first("DESCRIPTION")?.value?.let { unescapeIcsText(it).trim() },
                    startTime = first("DTSTART")?.let { formatIcsDate(it.value.trim(), it.params, zone) },
                    endTime = first("DTEND")?.let { formatIcsDate(it.value.trim(), it.params, zone) },
                    location = first("LOCATION")?.value?.let { unescapeIcsText(it).trim() },
                    calendarHref = calendarHref,
                    isRecurringInstance = recurring,
                    href = href,
                    etag = etag
                )
            )
            from = comp.end + 1
        }
        return events
    }

    fun parseTasks(ics: String, calendarHref: String, href: String = "", etag: String? = null, zone: ZoneId = ZoneOffset.UTC): List<NextcloudTask> {
        val lines = unfold(ics)
        val tasks = ArrayList<NextcloudTask>()
        var from = 0
        while (from < lines.size) {
            val comp = findComponentFrom(lines, "VTODO", from) ?: break
            val props = ownProps(lines, comp).map { it.prop }
            fun first(name: String) = props.firstOrNull { it.name == name }
            tasks.add(
                NextcloudTask(
                    uid = first("UID")?.value?.trim().orEmpty().ifEmpty { UUID.randomUUID().toString() },
                    summary = first("SUMMARY")?.value?.let { unescapeIcsText(it).trim() } ?: "Untitled task",
                    description = first("DESCRIPTION")?.value?.let { unescapeIcsText(it).trim() },
                    status = first("STATUS")?.value?.trim()?.uppercase()
                        ?: if (first("COMPLETED") != null) "COMPLETED" else "NEEDS-ACTION",
                    due = first("DUE")?.let { formatIcsDate(it.value.trim(), it.params, zone) },
                    calendarHref = calendarHref,
                    href = href,
                    etag = etag
                )
            )
            from = comp.end + 1
        }
        return tasks
    }

    private fun findComponentFrom(lines: List<String>, name: String, from: Int): Component? {
        val sub = findComponent(lines.subList(from, lines.size), name, false) ?: return null
        return Component(sub.start + from, sub.end + from)
    }

    // ── Writing ─────────────────────────────────────────────────────────────────────────────

    /**
     * Builds the resource to PUT for [event]. With [original] (the resource as currently stored on
     * the server) only the edited properties change; without it a fresh, valid VCALENDAR is created.
     */
    fun mergeEvent(original: String?, event: CalendarEvent, stamp: String = now(), zone: ZoneId = ZoneOffset.UTC): String {
        val newProps = linkedMapOf<String, String?>(
            "SUMMARY" to "SUMMARY:${escapeIcsText(event.summary)}",
            "DESCRIPTION" to event.description?.takeIf { it.isNotEmpty() }?.let { "DESCRIPTION:${escapeIcsText(it)}" },
            "LOCATION" to event.location?.takeIf { it.isNotEmpty() }?.let { "LOCATION:${escapeIcsText(it)}" }
        )
        val dtStart = icsDateLine("DTSTART", formatToIcsDate(event.startTime, zone))
        val dtEnd = icsDateLine("DTEND", formatToIcsDate(event.endTime, zone))
        return merge(original, "VEVENT", "PRODID:-//Nextcloud Extended//Calendar//EN", event.id, stamp, zone,
            managed = newProps,
            timed = listOf(
                TimedProp("DTSTART", event.startTime, dtStart),
                TimedProp("DTEND", event.endTime, dtEnd)
            ),
            replaceAlso = mapOf("DTEND" to setOf("DURATION"))
        )
    }

    fun mergeTask(original: String?, task: NextcloudTask, stamp: String = now(), zone: ZoneId = ZoneOffset.UTC): String {
        val completed = task.status.equals("COMPLETED", ignoreCase = true)
        val managed = linkedMapOf<String, String?>(
            "SUMMARY" to "SUMMARY:${escapeIcsText(task.summary)}",
            "DESCRIPTION" to task.description?.takeIf { it.isNotEmpty() }?.let { "DESCRIPTION:${escapeIcsText(it)}" },
            "STATUS" to "STATUS:${task.status}",
            // Nextcloud Tasks / DAVx5 read COMPLETED, not just STATUS.
            "COMPLETED" to if (completed) "COMPLETED:$stamp" else null,
            "PERCENT-COMPLETE" to if (completed) "PERCENT-COMPLETE:100" else null
        )
        val due = icsDateLine("DUE", formatToIcsDate(task.due, zone))
        return merge(original, "VTODO", "PRODID:-//Nextcloud Extended//Tasks//EN", task.uid, stamp, zone,
            managed = managed,
            timed = listOf(TimedProp("DUE", task.due, due)),
            replaceAlso = emptyMap(),
            keepIfUnchanged = setOf("COMPLETED", "PERCENT-COMPLETE")
        )
    }

    private class TimedProp(val name: String, val display: String?, val line: String?)

    private fun merge(
        original: String?,
        component: String,
        prodId: String,
        uid: String,
        stamp: String,
        zone: ZoneId,
        managed: Map<String, String?>,
        timed: List<TimedProp>,
        replaceAlso: Map<String, Set<String>>,
        keepIfUnchanged: Set<String> = emptySet()
    ): String {
        val lines = original?.let(::unfold).orEmpty()
        val comp = if (lines.isEmpty()) null else findComponent(lines, component, skipRecurrenceOverrides = true)
        if (comp == null) {
            val out = ArrayList<String>()
            out += "BEGIN:VCALENDAR"; out += "VERSION:2.0"; out += prodId; out += "CALSCALE:GREGORIAN"
            out += "BEGIN:$component"
            out += "UID:$uid"; out += "DTSTAMP:$stamp"; out += "CREATED:$stamp"; out += "LAST-MODIFIED:$stamp"; out += "SEQUENCE:0"
            managed.values.filterNotNull().forEach { out += it }
            timed.mapNotNull { it.line }.forEach { out += it }
            out += "END:$component"; out += "END:VCALENDAR"
            return out.joinToString("\r\n", postfix = "\r\n") { fold(it) }
        }

        val props = ownProps(lines, comp)
        val byName = props.groupBy { it.prop.name }
        val managedNames = managed.keys + timed.map { it.name } + replaceAlso.values.flatten() + setOf("LAST-MODIFIED", "DTSTAMP", "SEQUENCE")
        val dropIndexes = props.filter { it.prop.name in managedNames }.map { it.index }.toHashSet()

        // Time properties the user did not touch keep their exact original line (TZID, VALUE=DATE, …).
        val kept = HashMap<String, String>()
        for (t in timed) {
            val existing = byName[t.name]?.firstOrNull()?.prop
            if (existing != null && formatIcsDate(existing.value.trim(), existing.params, zone) == t.display) kept[t.name] = existing.line
        }
        // State mirrors (COMPLETED …) stay as written while the state they describe is unchanged.
        for (name in keepIfUnchanged) {
            val existing = byName[name]?.firstOrNull()?.prop
            if (existing != null && managed[name] != null) kept[name] = existing.line
        }
        val sequence = (byName["SEQUENCE"]?.firstOrNull()?.prop?.value?.trim()?.toIntOrNull() ?: 0) + 1

        val result = ArrayList<String>(lines.size + 8)
        for ((index, line) in lines.withIndex()) {
            if (index == comp.end) {
                result += "DTSTAMP:$stamp"
                result += "LAST-MODIFIED:$stamp"
                result += "SEQUENCE:$sequence"
                for ((name, value) in managed) {
                    val keptLine = kept[name]
                    if (keptLine != null) result += keptLine else if (value != null) result += value
                }
                for (t in timed) {
                    val keptLine = kept[t.name]
                    if (keptLine != null) result += keptLine else if (t.line != null) result += t.line
                }
            }
            if (index !in dropIndexes) result += line
        }
        return result.joinToString("\r\n", postfix = "\r\n") { fold(it) }
    }
}
