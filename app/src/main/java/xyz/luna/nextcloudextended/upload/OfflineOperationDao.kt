package xyz.luna.nextcloudextended.upload

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface OfflineOperationDao {
    @Insert
    suspend fun insert(operation: OfflineOperationEntity): Long

    @Query("SELECT * FROM offline_operations ORDER BY createdAt, id LIMIT 1")
    suspend fun next(): OfflineOperationEntity?

    @Query("DELETE FROM offline_operations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM offline_operations")
    suspend fun count(): Int

    @Query("SELECT * FROM offline_operations ORDER BY createdAt DESC")
    suspend fun all(): List<OfflineOperationEntity>
}