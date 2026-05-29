package xyz.luna.nextcloudextended.data.model

data class CalendarEvent(
    val id: String,
    val summary: String,
    val description: String?,
    val startTime: String?,
    val endTime: String?,
    val location: String?
)
