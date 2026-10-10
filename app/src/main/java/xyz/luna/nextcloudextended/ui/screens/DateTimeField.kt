package xyz.luna.nextcloudextended.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.luna.nextcloudextended.LocalStrings
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Event times travel through the UI as "YYYY-MM-DD HH:MM" (or "YYYY-MM-DD" for all-day events, or blank
 * for "no end"). These helpers build and read that text so the pickers can never produce an invalid one.
 */
internal object EventDateTime {
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    val defaultTime: LocalTime = LocalTime.of(10, 0)

    fun isDateOnly(value: String): Boolean = value.trim().length == 10

    fun parse(value: String): LocalDateTime? {
        val v = value.trim()
        return runCatching {
            if (v.length == 10) LocalDate.parse(v).atStartOfDay() else LocalDateTime.parse(v, dateTime)
        }.getOrNull()
    }

    fun format(value: LocalDateTime): String = dateTime.format(value)

    /** [value] moved to [date]; an all-day value stays all-day, a blank one gets the default time. */
    fun withDate(value: String, date: LocalDate): String {
        if (isDateOnly(value)) return date.toString()
        val time = parse(value)?.toLocalTime() ?: defaultTime
        return format(date.atTime(time))
    }

    /** [value] with its time set to [hour]:[minute] (an all-day value becomes a timed one). */
    fun withTime(value: String, hour: Int, minute: Int): String {
        val date = parse(value)?.toLocalDate() ?: LocalDate.now()
        return format(date.atTime(hour, minute))
    }

    /** The end that keeps the same duration when the start moves from [oldStart] to [newStart]. */
    fun shiftEnd(oldStart: String, newStart: String, end: String): String {
        val a = parse(oldStart) ?: return end
        val b = parse(newStart) ?: return end
        val e = parse(end) ?: return end
        val shifted = e.plus(Duration.between(a, b))
        return if (isDateOnly(end)) shifted.toLocalDate().toString() else format(shifted)
    }

    /** [value] reduced to its date (all-day); a blank or unreadable value is left as is. */
    fun toAllDay(value: String): String = parse(value)?.toLocalDate()?.toString() ?: value

    /** An all-day [value] given the time [at]; a value that already has a time is left as is. */
    fun toTimed(value: String, at: LocalTime = defaultTime): String {
        if (!isDateOnly(value)) return value
        val date = parse(value)?.toLocalDate() ?: return value
        return format(date.atTime(at))
    }

    /** Start and end after the "all day" switch is turned [on] or off. A blank end stays blank. */
    fun setAllDay(start: String, end: String, on: Boolean): Pair<String, String> =
        if (on) toAllDay(start) to toAllDay(end)
        else toTimed(start) to toTimed(end, defaultTime.plusHours(1))

    /** The end to offer for a [start] that has none: the same day if all-day, else one hour later. */
    fun defaultEnd(start: String): String {
        val s = parse(start) ?: return ""
        return if (isDateOnly(start)) s.toLocalDate().toString() else format(s.plusHours(1))
    }

    /** True when both times are known and the end is before the start. */
    fun endBeforeStart(start: String, end: String): Boolean {
        val s = parse(start) ?: return false
        val e = parse(end) ?: return false
        return e.isBefore(s)
    }
}

/** A date button and a time button that open a calendar and a clock, instead of a free-text field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val parsed = EventDateTime.parse(value)
    val allDay = EventDateTime.isDateOnly(value)
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(if (allDay) 1f else 3f), contentPadding = PaddingValues(horizontal = 10.dp)) {
                Icon(Icons.Default.DateRange, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(parsed?.toLocalDate()?.toString() ?: "—", maxLines = 1, softWrap = false)
            }
            // An all-day value has no time: the "all day" switch above decides that, not this button.
            if (!allDay) OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(2f), contentPadding = PaddingValues(horizontal = 10.dp)) {
                Icon(Icons.Default.Schedule, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (parsed == null) "--:--" else "%02d:%02d".format(parsed.hour, parsed.minute), maxLines = 1, softWrap = false)
            }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (parsed?.toLocalDate() ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    showDate = false
                    state.selectedDateMillis?.let { millis ->
                        onValueChange(EventDateTime.withDate(value, Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()))
                    }
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text(s.cancel) } }
        ) { DatePicker(state = state) }
    }

    if (showTime) {
        val initial = parsed?.toLocalTime() ?: EventDateTime.defaultTime
        val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    showTime = false
                    onValueChange(EventDateTime.withTime(value, state.hour, state.minute))
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text(s.cancel) } }
        )
    }
}

/** "All day" switch shown above the start and end fields. */
@Composable
fun AllDayRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(s.allDay, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
