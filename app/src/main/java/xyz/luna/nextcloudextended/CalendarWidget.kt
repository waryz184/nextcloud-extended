package xyz.luna.nextcloudextended

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import xyz.luna.nextcloudextended.data.model.CalendarEvent
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val WIDGET_PREFS = "calendar_widget"
private const val EVENTS_KEY = "events"

object CalendarWidget {
    fun update(context: Context, events: List<CalendarEvent>) {
        val json = JSONArray().apply {
            events.forEach { event ->
                put(JSONObject().apply {
                    put("summary", event.summary)
                    put("start", event.startTime ?: "")
                    put("end", event.endTime ?: "")
                })
            }
        }
        context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            .edit().putString(EVENTS_KEY, json.toString()).apply()
        refreshViews(context)
    }

    fun refreshViews(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, CalendarWidgetProvider::class.java))
        ids.forEach { id -> manager.updateAppWidget(id, views(context)) }
    }

    private fun views(context: Context): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_calendar)
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_calendar_root, pendingIntent)

        val today = LocalDate.now()
        val events = readEvents(context)
            .filter { eventDate(it.end) >= today }
            .sortedBy { it.start }
            .take(5)
        views.removeAllViews(R.id.widget_calendar_events)
        if (events.isEmpty()) {
            views.setTextViewText(R.id.widget_calendar_empty, context.getString(R.string.widget_no_events))
            views.setViewVisibility(R.id.widget_calendar_empty, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_calendar_empty, android.view.View.GONE)
            events.forEach { event ->
                val row = RemoteViews(context.packageName, R.layout.widget_calendar_event)
                row.setTextViewText(R.id.widget_event_title, event.summary)
                row.setTextViewText(R.id.widget_event_date, formatDate(event.start, event.end))
                views.addView(R.id.widget_calendar_events, row)
            }
        }
        return views
    }

    private data class WidgetEvent(val summary: String, val start: String, val end: String)

    private fun readEvents(context: Context): List<WidgetEvent> {
        val raw = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
            .getString(EVENTS_KEY, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).map { index ->
                val item = json.getJSONObject(index)
                WidgetEvent(item.optString("summary"), item.optString("start"), item.optString("end"))
            }
        }.getOrDefault(emptyList())
    }

    private fun eventDate(value: String): LocalDate =
        runCatching { LocalDate.parse(value.take(10)) }.getOrDefault(LocalDate.MAX)

    private fun formatDate(start: String, end: String): String {
        if (start.isBlank()) return ""
        val startDate = eventDate(start)
        val endDate = eventDate(end)
        val dateFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
        if (start.length < 16) return startDate.format(dateFormatter)
        val time = start.substring(11, 16)
        return if (startDate == endDate) "$time · ${end.takeIf { it.length >= 16 }?.substring(11, 16) ?: ""}"
        else "${startDate.format(dateFormatter)} · $time"
    }
}

class CalendarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        CalendarWidget.refreshViews(context)
    }
}
