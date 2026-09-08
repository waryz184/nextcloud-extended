package xyz.luna.nextcloudextended.upload

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "upload_operations")
data class UploadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val source: String,
    val parent: String,
    val name: String,
    val length: Long?,
    val state: String = STATE_QUEUED,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATE_QUEUED = "QUEUED"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_RETRY = "RETRY"
        const val STATE_FAILED = "FAILED_PERMANENT"
    }
}
