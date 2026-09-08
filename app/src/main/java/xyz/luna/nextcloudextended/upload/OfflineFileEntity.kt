package xyz.luna.nextcloudextended.upload

import androidx.room.Entity

@Entity(tableName = "offline_files", primaryKeys = ["accountId", "remotePath"])
data class OfflineFileEntity(
    val accountId: String,
    val remotePath: String,
    val localPath: String,
    val size: Long,
    val pinned: Boolean = true,
    val lastAccess: Long = System.currentTimeMillis()
)
