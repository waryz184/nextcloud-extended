package xyz.luna.nextcloudextended.data.model

data class NextcloudNote(
    val id: Int,
    val title: String,
    val content: String,
    val category: String,
    val modified: Long,
    val favorite: Boolean
)
