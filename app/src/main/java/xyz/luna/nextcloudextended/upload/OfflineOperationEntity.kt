package xyz.luna.nextcloudextended.upload

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_operations")
data class OfflineOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val operationType: String,
    val path: String,
    val extra: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)