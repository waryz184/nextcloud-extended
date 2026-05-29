package xyz.luna.nextcloudextended.data.model

data class NextcloudFile(
    val name: String,
    val path: String, // href
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: String
)
