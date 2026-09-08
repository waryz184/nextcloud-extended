package xyz.luna.nextcloudextended.upload

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface UploadDao {
    @Insert
    suspend fun insert(upload: UploadEntity): Long

    @Query("SELECT * FROM upload_operations WHERE state IN ('QUEUED', 'RETRY') ORDER BY createdAt, id LIMIT 1")
    suspend fun nextPending(): UploadEntity?

    @Query("UPDATE upload_operations SET state = :state, attempts = :attempts, lastError = :error WHERE id = :id")
    suspend fun updateState(id: Long, state: String, attempts: Int, error: String? = null)

    @Query("DELETE FROM upload_operations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM upload_operations WHERE state IN ('QUEUED', 'RETRY', 'RUNNING')")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM upload_operations ORDER BY createdAt DESC")
    suspend fun all(): List<UploadEntity>

    @Query("UPDATE upload_operations SET state = 'QUEUED', lastError = NULL WHERE id = :id")
    suspend fun retry(id: Long)

    @Query("UPDATE upload_operations SET state = 'CANCELLED' WHERE id = :id")
    suspend fun cancel(id: Long)
}
