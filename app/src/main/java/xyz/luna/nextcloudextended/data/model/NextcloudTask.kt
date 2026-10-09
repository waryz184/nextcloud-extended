package xyz.luna.nextcloudextended.data.model

data class NextcloudTask(
    val uid: String,
    val summary: String,
    val description: String?,
    val status: String, // "NEEDS-ACTION", "COMPLETED", etc.
    val due: String?,
    val calendarHref: String,
    /** Real resource path on the server (not necessarily `<uid>.ics`). */
    val href: String = "",
    val etag: String? = null
)
