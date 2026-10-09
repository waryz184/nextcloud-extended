package xyz.luna.nextcloudextended.data.model

data class NextcloudShare(
    val id: String,
    val shareType: Int,
    val shareWith: String?,
    val url: String?,
    val permissions: Int,
    val expiration: String?,
    val shareWithDisplayName: String? = null,
    val note: String? = null,
    val label: String? = null,
    val passwordProtected: Boolean = false,
    val hideDownload: Boolean = false,
    val path: String? = null,
    val ownerDisplayName: String? = null
) {
    val isLink: Boolean get() = shareType == TYPE_LINK
    val displayTarget: String get() = shareWithDisplayName?.takeIf { it.isNotBlank() } ?: shareWith.orEmpty()

    companion object {
        const val TYPE_USER = 0
        const val TYPE_GROUP = 1
        const val TYPE_LINK = 3
        const val TYPE_EMAIL = 4
        const val TYPE_FEDERATED = 6
        const val TYPE_CIRCLE = 7
        const val TYPE_TALK = 10

        // OCS permission bits
        const val PERM_READ = 1
        const val PERM_UPDATE = 2
        const val PERM_CREATE = 4
        const val PERM_DELETE = 8
        const val PERM_SHARE = 16
        const val PERM_ALL = 31
        const val PERM_READ_ONLY = PERM_READ
        const val PERM_EDIT = PERM_READ or PERM_UPDATE or PERM_CREATE or PERM_DELETE
    }
}

/** A user/group/email candidate returned by the sharees search. */
data class Sharee(
    val label: String,
    val shareType: Int,
    val shareWith: String,
    val subline: String? = null
)
