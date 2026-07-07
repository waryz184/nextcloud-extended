package xyz.luna.nextcloudextended.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.luna.nextcloudextended.CalendarViewMode
import xyz.luna.nextcloudextended.LocalStrings
import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.CalendarInfo
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// ── Color helpers ──────────────────────────────────────────────────────────────

fun parseCalColor(hex: String): Color {
    if (hex.length < 7) return Color(0xFF0082C9)
    return try { Color(("FF${hex.trimStart('#').take(6)}").toLong(16)) }
    catch (e: Exception) { Color(0xFF0082C9) }
}

fun calendarColorFor(calendarHref: String, calendarInfos: List<CalendarInfo>): Color =
    calendarInfos.find { it.href == calendarHref }?.let { parseCalColor(it.colorHex) } ?: Color(0xFF0082C9)

fun calendarNameFor(calendarHref: String, calendarInfos: List<CalendarInfo>): String =
    calendarInfos.find { it.href == calendarHref }?.displayName ?: ""

// ── Time parsing helpers ───────────────────────────────────────────────────────

private fun parseHour(t: String?): Int = t?.takeIf { it.length >= 16 }?.substring(11, 13)?.toIntOrNull() ?: 0
private fun parseMinute(t: String?): Int = t?.takeIf { it.length >= 16 }?.substring(14, 16)?.toIntOrNull() ?: 0
private fun isAllDay(t: String?): Boolean = t == null || t.length == 10
private fun durationMinutes(start: String?, end: String?): Int {
    if (start == null || end == null) return 60
    val diff = (parseHour(end) * 60 + parseMinute(end)) - (parseHour(start) * 60 + parseMinute(start))
    return if (diff <= 0) 60 else diff
}

// ── Root composable ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarMultiViewScreen(
    calendarInfos: List<CalendarInfo>,
    activeCalendarHrefs: Set<String>,
    events: List<CalendarEvent>,
    calendarViewMode: CalendarViewMode,
    selectedDate: LocalDate,
    onToggleCalendar: (String) -> Unit,
    onViewModeChange: (CalendarViewMode) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onEventTap: (CalendarEvent) -> Unit
) {
    val s = LocalStrings.current
    Column(modifier = Modifier.fillMaxWidth()) {
        // Calendar chips (horizontal scroll)
        if (calendarInfos.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                calendarInfos.forEach { cal ->
                    val active = cal.href in activeCalendarHrefs
                    val color = parseCalColor(cal.colorHex)
                    FilterChip(
                        selected = active, onClick = { onToggleCalendar(cal.href) },
                        label = { Text(cal.displayName, style = MaterialTheme.typography.labelMedium) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(if (active) color else color.copy(alpha = 0.3f), CircleShape)) }
                    )
                }
            }
        }

        // View mode selector
        val modes = listOf(CalendarViewMode.DAY to s.viewDay, CalendarViewMode.WEEK to s.viewWeek, CalendarViewMode.MONTH to s.viewMonth, CalendarViewMode.YEAR to s.viewYear)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            modes.forEachIndexed { i, (mode, label) ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                    onClick = { onViewModeChange(mode) }, selected = calendarViewMode == mode,
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        when (calendarViewMode) {
            CalendarViewMode.DAY   -> DayTimeGrid(selectedDate, events, calendarInfos, onDateChange, onEventTap)
            CalendarViewMode.WEEK  -> WeekView(selectedDate, events, calendarInfos, onDateChange, onEventTap)
            CalendarViewMode.MONTH -> MonthView(selectedDate, events, calendarInfos, onDateChange, onEventTap)
            CalendarViewMode.YEAR  -> YearView(selectedDate,
                onDateChange = { onDateChange(it); onViewModeChange(CalendarViewMode.MONTH) },
                onYearChange = { onDateChange(it) })
        }
    }
}

// ── Month view ─────────────────────────────────────────────────────────────────

@Composable
fun MonthView(
    selectedDate: LocalDate, events: List<CalendarEvent>,
    calendarInfos: List<CalendarInfo>, onDateChange: (LocalDate) -> Unit,
    onEventTap: (CalendarEvent) -> Unit
) {
    val s = LocalStrings.current
    val yearMonth = YearMonth.of(selectedDate.year, selectedDate.month)
    val paddingDays = yearMonth.atDay(1).dayOfWeek.value - 1
    val headerFmt = DateTimeFormatter.ofPattern("MMMM yyyy", s.locale)
    val dayOfMonthEvents = events.filter { it.startTime?.startsWith(selectedDate.toString()) == true }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        // Month nav
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDateChange(selectedDate.minusMonths(1)) }) { Icon(Icons.Default.KeyboardArrowLeft, null) }
            Text(selectedDate.format(headerFmt).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { onDateChange(selectedDate.plusMonths(1)) }) { Icon(Icons.Default.KeyboardArrowRight, null) }
        }

        // Day-of-week header
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            s.dayInitials.forEach { d ->
                Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))

        // Calendar grid
        val daysList = mutableListOf<LocalDate?>()
        repeat(paddingDays) { daysList.add(null) }
        for (i in 1..yearMonth.lengthOfMonth()) daysList.add(yearMonth.atDay(i))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            daysList.chunked(7).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { day ->
                        val isSelected = day == selectedDate
                        val isToday = day == LocalDate.now()
                        val dayEvs = if (day != null) events.filter { it.startTime?.startsWith(day.toString()) == true } else emptyList()
                        Box(
                            modifier = Modifier.weight(1f).aspectRatio(0.9f).padding(2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(when {
                                    day == null -> Color.Transparent
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    else -> Color.Transparent
                                })
                                .clickable(enabled = day != null) { if (day != null) onDateChange(day) },
                            contentAlignment = Alignment.TopCenter
                        ) {
                            if (day != null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 4.dp)) {
                                    // Date number
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.size(26.dp).clip(CircleShape).background(
                                            when { isToday && !isSelected -> MaterialTheme.colorScheme.primaryContainer; else -> Color.Transparent }
                                        )
                                    ) {
                                        Text(
                                            day.dayOfMonth.toString(),
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = when {
                                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                                isToday -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    }
                                    // Event dots (up to 3)
                                    if (dayEvs.isNotEmpty()) {
                                        Spacer(Modifier.height(2.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            dayEvs.take(3).forEach { ev ->
                                                Box(Modifier.size(5.dp).clip(CircleShape).background(
                                                    if (isSelected) MaterialTheme.colorScheme.onPrimary else calendarColorFor(ev.calendarHref, calendarInfos)
                                                ))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (row.size < 7) repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp))

        // Events for selected day
        val dayFmt = DateTimeFormatter.ofPattern("EEE d MMMM", s.locale)
        Text(
            selectedDate.format(dayFmt).replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (dayOfMonthEvents.isEmpty()) {
            Text(s.noEvent, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                dayOfMonthEvents.forEach { EventChip(it, calendarInfos, onEventTap) }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ── Day view — time grid ───────────────────────────────────────────────────────

private val HOUR_HEIGHT = 64.dp

@Composable
fun DayTimeGrid(
    selectedDate: LocalDate, events: List<CalendarEvent>,
    calendarInfos: List<CalendarInfo>, onDateChange: (LocalDate) -> Unit,
    onEventTap: (CalendarEvent) -> Unit
) {
    val s = LocalStrings.current
    val dayFmt = DateTimeFormatter.ofPattern("EEE d MMMM yyyy", s.locale)
    val dayEvents = events.filter { it.startTime?.startsWith(selectedDate.toString()) == true }
    val timedEvents = dayEvents.filter { !isAllDay(it.startTime) }
    val allDayEvents = dayEvents.filter { isAllDay(it.startTime) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Day navigation
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDateChange(selectedDate.minusDays(1)) }) { Icon(Icons.Default.KeyboardArrowLeft, null) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(selectedDate.format(dayFmt).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = { onDateChange(selectedDate.plusDays(1)) }) { Icon(Icons.Default.KeyboardArrowRight, null) }
        }

        // All-day events strip
        if (allDayEvents.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                allDayEvents.forEach { EventChip(it, calendarInfos, onEventTap) }
            }
            HorizontalDivider()
        }

        // Time grid
        val scrollState = rememberScrollState(initial = (8 * HOUR_HEIGHT.value).toInt())
        Row(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
            // Hour labels
            Column(modifier = Modifier.width(48.dp)) {
                Spacer(Modifier.height(HOUR_HEIGHT / 2))
                for (h in 0..23) {
                    Box(modifier = Modifier.height(HOUR_HEIGHT), contentAlignment = Alignment.TopEnd) {
                        Text(
                            if (h == 0) "" else "$h${s.hourSuffix}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp, top = 0.dp)
                        )
                    }
                }
            }

            // Grid + events
            Box(modifier = Modifier.weight(1f).height(HOUR_HEIGHT * 24)) {
                // Hour lines
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(HOUR_HEIGHT / 2))
                    for (h in 0..23) {
                        HorizontalDivider(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(HOUR_HEIGHT - 1.dp))
                    }
                }
                // Current time indicator
                if (selectedDate == LocalDate.now()) {
                    val now = java.time.LocalTime.now()
                    val topOffset = HOUR_HEIGHT * (now.hour + now.minute / 60f)
                    Box(modifier = Modifier.fillMaxWidth().offset(y = topOffset)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                            HorizontalDivider(color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        }
                    }
                }
                // Events
                timedEvents.forEach { event ->
                    val startH = parseHour(event.startTime)
                    val startM = parseMinute(event.startTime)
                    val durMin = durationMinutes(event.startTime, event.endTime)
                    val topDp = HOUR_HEIGHT * (startH + startM / 60f)
                    val heightDp = (HOUR_HEIGHT * durMin / 60f).coerceAtLeast(28.dp)
                    val color = calendarColorFor(event.calendarHref, calendarInfos)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = topDp)
                            .height(heightDp)
                            .padding(horizontal = 2.dp, vertical = 1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color.copy(alpha = 0.85f))
                            .clickable { onEventTap(event) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Column {
                            Text(event.summary, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            if (durMin >= 45 && !event.startTime.isNullOrEmpty()) {
                                Text("${event.startTime!!.substring(11)}" + if (!event.endTime.isNullOrEmpty()) " – ${event.endTime!!.substring(11)}" else "",
                                    style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
                            }
                        }
                    }
                }
                // Empty day placeholder
                if (timedEvents.isEmpty() && allDayEvents.isEmpty()) {
                    Box(Modifier.fillMaxSize().offset(y = HOUR_HEIGHT * 9), contentAlignment = Alignment.Center) {
                        Text(s.noEventThatDay, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
        }
    }
}

// ── Week view ──────────────────────────────────────────────────────────────────

@Composable
fun WeekView(
    selectedDate: LocalDate, events: List<CalendarEvent>,
    calendarInfos: List<CalendarInfo>, onDateChange: (LocalDate) -> Unit,
    onEventTap: (CalendarEvent) -> Unit
) {
    val s = LocalStrings.current
    val startOfWeek = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekFmt = DateTimeFormatter.ofPattern("d MMM", s.locale)
    val dayFmt = DateTimeFormatter.ofPattern("EEE\nd", s.locale)

    Column(modifier = Modifier.fillMaxWidth()) {
        // Week nav header
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDateChange(selectedDate.minusWeeks(1)) }) { Icon(Icons.Default.KeyboardArrowLeft, null) }
            Text("${startOfWeek.format(weekFmt)} – ${startOfWeek.plusDays(6).format(weekFmt)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { onDateChange(selectedDate.plusWeeks(1)) }) { Icon(Icons.Default.KeyboardArrowRight, null) }
        }

        // Day column headers (tappable to navigate to day view)
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            repeat(7) { i ->
                val day = startOfWeek.plusDays(i.toLong())
                val isSelected = day == selectedDate
                val isToday = day == LocalDate.now()
                val dayEvCount = events.count { it.startTime?.startsWith(day.toString()) == true }
                Box(
                    modifier = Modifier.weight(1f).clickable { onDateChange(day) }.padding(2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            day.format(dayFmt).uppercase(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = when {
                                isSelected -> MaterialTheme.colorScheme.primary
                                isToday -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal
                        )
                        if (dayEvCount > 0) {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            ))
                        }
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // Scrollable day rows
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            for (i in 0..6) {
                val day = startOfWeek.plusDays(i.toLong())
                val dayEvs = events.filter { it.startTime?.startsWith(day.toString()) == true }
                val isToday = day == LocalDate.now()
                val isSelected = day == selectedDate
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onDateChange(day) }
                        .background(when {
                            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            isToday -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
                            else -> Color.Transparent
                        })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Day number
                    Column(modifier = Modifier.width(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            day.dayOfWeek.getDisplayName(TextStyle.SHORT, s.locale).replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Box(
                            Modifier.size(28.dp).clip(CircleShape)
                                .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                day.dayOfMonth.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    // Events
                    if (dayEvs.isEmpty()) {
                        Text("—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                            dayEvs.forEach { EventChip(it, calendarInfos, onEventTap) }
                        }
                    }
                }
                if (i < 6) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

// ── Year view ──────────────────────────────────────────────────────────────────

@Composable
fun YearView(selectedDate: LocalDate, onDateChange: (LocalDate) -> Unit, onYearChange: (LocalDate) -> Unit) {
    val s = LocalStrings.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onYearChange(selectedDate.withYear(selectedDate.year - 1)) }) { Icon(Icons.Default.KeyboardArrowLeft, null) }
            Text("${selectedDate.year}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { onYearChange(selectedDate.withYear(selectedDate.year + 1)) }) { Icon(Icons.Default.KeyboardArrowRight, null) }
        }
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (1..12).chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { month ->
                        val firstOfMonth = LocalDate.of(selectedDate.year, month, 1)
                        Card(modifier = Modifier.weight(1f).aspectRatio(1f).clickable { onDateChange(firstOfMonth) }, shape = RoundedCornerShape(12.dp)) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(firstOfMonth.month.getDisplayName(TextStyle.FULL, s.locale).replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Event chip — compact, Google Calendar style ────────────────────────────────

@Composable
fun EventChip(event: CalendarEvent, calendarInfos: List<CalendarInfo>, onTap: (CalendarEvent) -> Unit) {
    val s = LocalStrings.current
    val color = calendarColorFor(event.calendarHref, calendarInfos)
    val timeStr = if (!isAllDay(event.startTime)) event.startTime?.substring(11) ?: "" else s.allDay
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .clickable { onTap(event) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).height(32.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(event.summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(timeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Event detail bottom sheet ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailSheet(
    event: CalendarEvent,
    calendarInfos: List<CalendarInfo>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val s = LocalStrings.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val color = calendarColorFor(event.calendarHref, calendarInfos)
    val calName = calendarNameFor(event.calendarHref, calendarInfos)

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(s.deleteEventTitle) },
            text = { Text(s.deleteEventConfirm(event.summary)) },
            confirmButton = { Button(onClick = { showDeleteConfirm = false; onDismiss(); onDelete() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(s.delete) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(s.cancel) } }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            // Color bar + title
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(12.dp))
                Text(event.summary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (event.isRecurringInstance) Icon(Icons.Default.Repeat, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))

            // Calendar name
            if (calName.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    Icon(Icons.Default.Circle, null, tint = color, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(calName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Date / time
            val dateTimeStr = buildString {
                if (!event.startTime.isNullOrEmpty()) append(event.startTime)
                if (!event.endTime.isNullOrEmpty()) append(" → ${event.endTime}")
            }
            if (dateTimeStr.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(dateTimeStr, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Location
            if (!event.location.isNullOrEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    Icon(Icons.Default.Place, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(event.location, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Description
            if (!event.description.isNullOrEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(event.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // Actions
            if (event.isRecurringInstance) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Repeat, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(s.recurringEventNotice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showDeleteConfirm = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(s.delete)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onDismiss(); onEdit() }) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(s.edit)
                    }
                }
            }
        }
    }
}
