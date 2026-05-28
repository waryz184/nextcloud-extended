package com.example.nextcloudcalendar.data.model

data class NextcloudTask(
    val uid: String,
    val summary: String,
    val description: String?,
    val status: String, // "NEEDS-ACTION", "COMPLETED", etc.
    val due: String?,
    val calendarHref: String
)
