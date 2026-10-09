package xyz.luna.nextcloudextended.data.model

data class CalendarEvent(
    val id: String,
    val summary: String,
    val description: String?,
    val startTime: String?,
    val endTime: String?,
    val location: String?,
    val calendarHref: String = "",
    val isRecurringInstance: Boolean = false,
    /** Real resource path on the server. Other clients name resources freely (not `<uid>.ics`). */
    val href: String = "",
    /** ETag the event was read with; sent back as `If-Match` so a concurrent edit is never overwritten. */
    val etag: String? = null
)
