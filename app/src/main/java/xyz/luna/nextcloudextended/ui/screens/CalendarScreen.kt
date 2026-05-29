package xyz.luna.nextcloudextended.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.luna.nextcloudextended.CalendarViewMode
import xyz.luna.nextcloudextended.data.model.CalendarEvent
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
    calendars: List<Pair<String, String>>,
    selectedName: String,
    events: List<CalendarEvent>,
    calendarViewMode: CalendarViewMode,
    selectedDate: LocalDate,
    onViewModeChange: (CalendarViewMode) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onCalendarSelected: (String, String) -> Unit
) {
    var dropdownExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { dropdownExpanded = true },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = selectedName.ifEmpty { "Sélectionner agenda..." },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Choisir", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                DropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }, modifier = Modifier.fillMaxWidth(0.8f)) {
                    calendars.forEach { cal ->
                        DropdownMenuItem(text = { Text(cal.second) }, onClick = { dropdownExpanded = false; onCalendarSelected(cal.first, cal.second) })
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        val modes = listOf(
            CalendarViewMode.DAY to "Jour",
            CalendarViewMode.WEEK to "Sem.",
            CalendarViewMode.MONTH to "Mois",
            CalendarViewMode.YEAR to "Année"
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    onClick = { onViewModeChange(mode) },
                    selected = calendarViewMode == mode,
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (calendarViewMode) {
            CalendarViewMode.DAY   -> CalendarDayView(selectedDate, events, onDateChange)
            CalendarViewMode.WEEK  -> CalendarWeekView(selectedDate, events, onDateChange)
            CalendarViewMode.MONTH -> CalendarMonthView(selectedDate, events, onDateChange)
            CalendarViewMode.YEAR  -> CalendarYearView(
                selectedDate,
                onDateChange = { onDateChange(it); onViewModeChange(CalendarViewMode.MONTH) },
                onYearChange = { onDateChange(it) }
            )
        }
    }
}

@Composable
fun CalendarDayView(selectedDate: LocalDate, events: List<CalendarEvent>, onDateChange: (LocalDate) -> Unit) {
    val formatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
    val dayEvents = events.filter { it.startTime?.startsWith(selectedDate.toString()) == true }
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDateChange(selectedDate.minusDays(1)) }) { Icon(Icons.Default.KeyboardArrowLeft, "Précédent") }
            Text(selectedDate.format(formatter).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onDateChange(selectedDate.plusDays(1)) }) { Icon(Icons.Default.KeyboardArrowRight, "Suivant") }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (dayEvents.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                Text("Aucun événement pour ce jour.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                dayEvents.forEach { EventItem(it) }
            }
        }
    }
}

@Composable
fun CalendarWeekView(selectedDate: LocalDate, events: List<CalendarEvent>, onDateChange: (LocalDate) -> Unit) {
    val startOfWeek = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val endOfWeek = startOfWeek.plusDays(6)
    val weekFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
    val dayNameFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.FRENCH)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDateChange(selectedDate.minusWeeks(1)) }) { Icon(Icons.Default.KeyboardArrowLeft, "Précédent") }
            Text("Semaine du ${startOfWeek.format(weekFormatter)} au ${endOfWeek.format(weekFormatter)}", style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { onDateChange(selectedDate.plusWeeks(1)) }) { Icon(Icons.Default.KeyboardArrowRight, "Suivant") }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            for (i in 0..6) {
                val currentDay = startOfWeek.plusDays(i.toLong())
                val dayEvents = events.filter { it.startTime?.startsWith(currentDay.toString()) == true }
                val isToday = currentDay == LocalDate.now()
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isToday) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            currentDay.format(dayNameFormatter).replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        if (dayEvents.isEmpty()) {
                            Text("Aucun événement", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            dayEvents.forEach { ev ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(modifier = Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("${ev.startTime?.substring(11) ?: ""} - ${ev.summary}", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarMonthView(selectedDate: LocalDate, events: List<CalendarEvent>, onDateChange: (LocalDate) -> Unit) {
    val yearMonth = YearMonth.of(selectedDate.year, selectedDate.month)
    val firstOfMonth = yearMonth.atDay(1)
    val daysInMonth = yearMonth.lengthOfMonth()
    val paddingDays = firstOfMonth.dayOfWeek.value - 1
    val headerFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH)
    val dayOfMonthEvents = events.filter { it.startTime?.startsWith(selectedDate.toString()) == true }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDateChange(selectedDate.minusMonths(1)) }) { Icon(Icons.Default.KeyboardArrowLeft, "Précédent") }
            Text(selectedDate.format(headerFormatter).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onDateChange(selectedDate.plusMonths(1)) }) { Icon(Icons.Default.KeyboardArrowRight, "Suivant") }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim").forEach { day ->
                Text(day, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        val daysList = mutableListOf<LocalDate?>()
        repeat(paddingDays) { daysList.add(null) }
        for (i in 1..daysInMonth) daysList.add(yearMonth.atDay(i))
        Column(modifier = Modifier.fillMaxWidth()) {
            daysList.chunked(7).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { day ->
                        Box(
                            modifier = Modifier.weight(1f).aspectRatio(1.1f).padding(2.dp)
                                .background(
                                    color = when {
                                        day == null -> androidx.compose.ui.graphics.Color.Transparent
                                        day == selectedDate -> MaterialTheme.colorScheme.primary
                                        day == LocalDate.now() -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surface
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable(enabled = day != null) { if (day != null) onDateChange(day) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (day != null) {
                                val hasEvent = events.any { it.startTime?.startsWith(day.toString()) == true }
                                val isSelected = day == selectedDate
                                val isToday = day == LocalDate.now()
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = day.dayOfMonth.toString(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                        color = when {
                                            isSelected -> MaterialTheme.colorScheme.onPrimary
                                            isToday -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                    if (hasEvent) {
                                        Box(modifier = Modifier.size(4.dp).background(
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(2.dp)
                                        ))
                                    }
                                }
                            }
                        }
                    }
                    if (row.size < 7) repeat(7 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Événements du ${selectedDate.dayOfMonth} ${selectedDate.format(DateTimeFormatter.ofPattern("MMMM", Locale.FRENCH))} :",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (dayOfMonthEvents.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Text("Aucun événement pour cette journée.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dayOfMonthEvents.forEach { EventItem(it) }
            }
        }
    }
}

@Composable
fun CalendarYearView(selectedDate: LocalDate, onDateChange: (LocalDate) -> Unit, onYearChange: (LocalDate) -> Unit) {
    val currentYear = selectedDate.year
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onYearChange(selectedDate.withYear(currentYear - 1)) }) { Icon(Icons.Default.KeyboardArrowLeft, "Précédent") }
            Text("Année $currentYear", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { onYearChange(selectedDate.withYear(currentYear + 1)) }) { Icon(Icons.Default.KeyboardArrowRight, "Suivant") }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (1..12).chunked(3).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { month ->
                        val firstOfMonth = LocalDate.of(currentYear, month, 1)
                        val monthName = firstOfMonth.month.getDisplayName(TextStyle.FULL, Locale.FRENCH)
                        Card(modifier = Modifier.weight(1f).aspectRatio(1f).clickable { onDateChange(firstOfMonth) }, shape = RoundedCornerShape(10.dp)) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(monthName.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EventItem(event: CalendarEvent) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(event.summary, style = MaterialTheme.typography.titleSmall)
            if (!event.startTime.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "📅 " + event.startTime + (if (!event.endTime.isNullOrEmpty()) " ➡️ " + event.endTime else ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!event.location.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("📍 " + event.location, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!event.description.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(event.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
