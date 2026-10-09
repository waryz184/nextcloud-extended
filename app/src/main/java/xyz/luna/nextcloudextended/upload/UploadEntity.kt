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
    val createdAt: Long = System.currentTimeMillis(),
    /** true: replace a same-named remote file (saving an edited document); false: keep both ("name (2).ext"). */
    val overwrite: Boolean = false,
    /** Original modification time (epoch seconds) sent as `X-OC-Mtime`, so photos keep their real date. */
    val sourceMtime: Long? = null,
    /** ETag of the server version an edited document was based on: if it moved, keep both instead of clobbering. */
    val expectedEtag: String? = null
) {
    companion object {
        const val STATE_QUEUED = "QUEUED"
        const val STATE_RUNNING = "RUNNING"
        const val STATE_RETRY = "RETRY"
        const val STATE_FAILED = "FAILED_PERMANENT"
    }
}
