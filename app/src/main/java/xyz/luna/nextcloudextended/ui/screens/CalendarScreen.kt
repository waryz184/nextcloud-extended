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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.luna.nextcloudextended.CalendarViewMode
import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.CalendarInfo
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

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
    onEditEvent: (CalendarEvent) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        if (calendarInfos.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                calendarInfos.forEach { cal ->
                    val isActive = cal.href in activeCalendarHrefs
                    val calColor = parseCalColor(cal.colorHex)
                    FilterChip(
                        selected = isActive,
                        onClick = { onToggleCalendar(cal.href) },
                        label = { Text(cal.displayName, style = MaterialTheme.typography.labelMedium) },
                        leadingIcon = {
                            Box(modifier = Modifier.size(10.dp).background(
                                if (isActive) calColor else calColor.copy(alpha = 0.3f), CircleShape
                            ))
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        val modes = listOf(
            CalendarViewMode.DAY to "Jour", CalendarViewMode.WEEK to "Sem.",
            CalendarViewMode.MONTH to "Mois", CalendarViewMode.YEAR to "Année"
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    onClick = { onViewModeChange(mode) }, selected = calendarViewMode == mode,
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when (calendarViewMode) {
            CalendarViewMode.DAY   -> CalendarDayView(selectedDate, events, calendarInfos, onDateChange, onEditEvent)
            CalendarViewMode.WEEK  -> CalendarWeekView(selectedDate, events, onDateChange)
            CalendarViewMode.MONTH -> CalendarMonthView(selectedDate, events, calendarInfos, onDateChange, onEditEvent)
            CalendarViewMode.YEAR  -> CalendarYearView(
                selectedDate,
                onDateChange = { onDateChange(it); onViewModeChange(CalendarViewMode.MONTH) },
                onYearChange = { onDateChange(it) }
            )
        }
    }
}

fun parseCalColor(hex: String): Color {
    if (hex.length < 7) return Color(0xFF0082C9)
    return try { Color(("FF${hex.trimStart('#').take(6)}").toLong(16)) }
    catch (e: Exception) { Color(0xFF0082C9) }
}

fun calendarColorFor(calendarHref: String, calendarInfos: List<CalendarInfo>): Color =
    calendarInfos.find { it.href == calendarHref }?.let { parseCalColor(it.colorHex) } ?: Color(0xFF0082C9)

@Composable
fun CalendarDayView(
    selectedDate: LocalDate, events: List<CalendarEvent>,
    calendarInfos: List<CalendarInfo>, onDateChange: (LocalDate) -> Unit,
    onEditEvent: (CalendarEvent) -> Unit
) {
    val formatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
    val dayEvents = events.filter { it.startTime?.startsWith(selectedDate.toString()) == true }
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDateChange(selectedDate.minusDays(1)) }) { Icon(Icons.Default.KeyboardArrowLeft, "Précédent") }
            Text(selectedDate.format(formatter).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onDateChange(selectedDate.plusDays(1)) }) { Icon(Icons.Default.KeyboardArrowRight, "Suivant") }
        }
        Spacer(Modifier.height(12.dp))
        if (dayEvents.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                Text("Aucun événement pour ce jour.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                dayEvents.forEach { EventItem(it, calendarInfos, onEditEvent) }
            }
        }
    }
}

@Composable
fun CalendarWeekView(selectedDate: LocalDate, events: List<CalendarEvent>, onDateChange: (LocalDate) -> Unit) {
    val startOfWeek = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val endOfWeek = startOfWeek.plusDays(6)
    val weekFmt = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
    val dayFmt = DateTimeFormatter.ofPattern("EEE d", Locale.FRENCH)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDateChange(selectedDate.minusWeeks(1)) }) { Icon(Icons.Default.KeyboardArrowLeft, "Précédent") }
            Text("Semaine du ${startOfWeek.format(weekFmt)} au ${endOfWeek.format(weekFmt)}", style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { onDateChange(selectedDate.plusWeeks(1)) }) { Icon(Icons.Default.KeyboardArrowRight, "Suivant") }
        }
        Spacer(Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            for (i in 0..6) {
                val day = startOfWeek.plusDays(i.toLong())
                val dayEvents = events.filter { it.startTime?.startsWith(day.toString()) == true }
                val isToday = day == LocalDate.now()
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isToday) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(day.format(dayFmt).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall,
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(6.dp))
                        if (dayEvents.isEmpty()) Text("Aucun événement", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else dayEvents.forEach { ev ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                                Spacer(Modifier.width(8.dp))
                                Text("${ev.startTime?.substring(11) ?: ""} - ${ev.summary}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarMonthView(
    selectedDate: LocalDate, events: List<CalendarEvent>,
    calendarInfos: List<CalendarInfo>, onDateChange: (LocalDate) -> Unit,
    onEditEvent: (CalendarEvent) -> Unit
) {
    val yearMonth = YearMonth.of(selectedDate.year, selectedDate.month)
    val paddingDays = yearMonth.atDay(1).dayOfWeek.value - 1
    val headerFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH)
    val dayOfMonthEvents = events.filter { it.startTime?.startsWith(selectedDate.toString()) == true }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDateChange(selectedDate.minusMonths(1)) }) { Icon(Icons.Default.KeyboardArrowLeft, "Précédent") }
            Text(selectedDate.format(headerFmt).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onDateChange(selectedDate.plusMonths(1)) }) { Icon(Icons.Default.KeyboardArrowRight, "Suivant") }
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim").forEach { day ->
                Text(day, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        val daysList = mutableListOf<LocalDate?>()
        repeat(paddingDays) { daysList.add(null) }
        for (i in 1..yearMonth.lengthOfMonth()) daysList.add(yearMonth.atDay(i))
        Column(modifier = Modifier.fillMaxWidth()) {
            daysList.chunked(7).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { day ->
                        Box(
                            modifier = Modifier.weight(1f).aspectRatio(1.1f).padding(2.dp)
                                .background(when {
                                    day == null -> Color.Transparent
                                    day == selectedDate -> MaterialTheme.colorScheme.primary
                                    day == LocalDate.now() -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surface
                                }, RoundedCornerShape(8.dp))
                                .clickable(enabled = day != null) { if (day != null) onDateChange(day) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (day != null) {
                                val dayEvs = events.filter { it.startTime?.startsWith(day.toString()) == true }
                                val isSelected = day == selectedDate; val isToday = day == LocalDate.now()
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                        color = when { isSelected -> MaterialTheme.colorScheme.onPrimary; isToday -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurface })
                                    if (dayEvs.isNotEmpty()) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            dayEvs.take(3).forEach { ev ->
                                                Box(modifier = Modifier.size(4.dp).background(
                                                    if (isSelected) MaterialTheme.colorScheme.onPrimary else calendarColorFor(ev.calendarHref, calendarInfos),
                                                    CircleShape
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
        Spacer(Modifier.height(16.dp))
        Text("Événements du ${selectedDate.dayOfMonth} ${selectedDate.format(DateTimeFormatter.ofPattern("MMMM", Locale.FRENCH))} :",
            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
        if (dayOfMonthEvents.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Text("Aucun événement pour cette journée.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dayOfMonthEvents.forEach { EventItem(it, calendarInfos, onEditEvent) }
            }
        }
    }
}

@Composable
fun CalendarYearView(selectedDate: LocalDate, onDateChange: (LocalDate) -> Unit, onYearChange: (LocalDate) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onYearChange(selectedDate.withYear(selectedDate.year - 1)) }) { Icon(Icons.Default.KeyboardArrowLeft, "Précédent") }
            Text("Année ${selectedDate.year}", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { onYearChange(selectedDate.withYear(selectedDate.year + 1)) }) { Icon(Icons.Default.KeyboardArrowRight, "Suivant") }
        }
        Spacer(Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (1..12).chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { month ->
                        val firstOfMonth = LocalDate.of(selectedDate.year, month, 1)
                        Card(modifier = Modifier.weight(1f).aspectRatio(1f).clickable { onDateChange(firstOfMonth) }, shape = RoundedCornerShape(10.dp)) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(firstOfMonth.month.getDisplayName(TextStyle.FULL, Locale.FRENCH).replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EventItem(
    event: CalendarEvent,
    calendarInfos: List<CalendarInfo> = emptyList(),
    onEdit: (CalendarEvent) -> Unit = {}
) {
    val calColor = calendarColorFor(event.calendarHref, calendarInfos)
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(calColor,
                RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)))
            Column(modifier = Modifier.weight(1f).padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(event.summary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onEdit(event) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, "Modifier", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
                if (!event.startTime.isNullOrEmpty()) {
                    Text("📅 ${event.startTime}" + if (!event.endTime.isNullOrEmpty()) " → ${event.endTime}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!event.location.isNullOrEmpty()) Text("📍 ${event.location}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!event.description.isNullOrEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Text(event.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
