package xyz.luna.nextcloudextended.data.model

data class NextcloudShare(
    val id: String,
    val shareType: Int,
    val shareWith: String?,
    val url: String?,
    val permissions: Int,
    val expiration: String?
)
