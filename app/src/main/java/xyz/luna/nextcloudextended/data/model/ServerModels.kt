package xyz.luna.nextcloudextended.data.model

data class UserInfo(
    /** The internal user id — the one WebDAV paths use. It can differ from the login name. */
    val id: String,
    val displayName: String,
    val email: String? = null,
    val quotaUsed: Long = 0,
    /** Total quota in bytes; -3 = unlimited, -1/-2 = unknown/not computed (as the server reports). */
    val quotaTotal: Long = -3,
    val quotaFree: Long = -3,
    val quotaRelative: Double = 0.0
) {
    val unlimitedQuota: Boolean get() = quotaTotal < 0
}

data class ServerStatus(
    /** Normalised server root (scheme + host + optional sub-folder), possibly after redirects. */
    val baseUrl: String,
    val installed: Boolean,
    val maintenance: Boolean,
    val needsDbUpgrade: Boolean,
    val version: String?,
    val productName: String?
)

data class NextcloudNotification(
    val id: Long,
    val app: String,
    val datetime: String,
    val subject: String,
    val message: String,
    val link: String?,
    val actions: List<NotificationAction>
)

data class NotificationAction(val label: String, val link: String, val method: String, val primary: Boolean)

data class ActivityItem(
    val id: Long,
    val app: String,
    val type: String,
    val datetime: String,
    val subject: String,
    val message: String,
    val objectType: String?,
    val objectId: Long?,
    val objectName: String?,
    val link: String?
)

data class TrashedFile(
    /** Path inside the trash bin (`/remote.php/dav/trashbin/<user>/trash/<name>.dNNN`). */
    val path: String,
    val name: String,
    val originalLocation: String,
    val deletionTimeSeconds: Long,
    val size: Long,
    val isDirectory: Boolean,
    val fileId: String?
)

data class FileVersion(
    /** Path of this version (`/remote.php/dav/versions/<user>/versions/<fileId>/<timestamp>`). */
    val path: String,
    val lastModifiedMillis: Long,
    val size: Long,
    val mimeType: String?
)
