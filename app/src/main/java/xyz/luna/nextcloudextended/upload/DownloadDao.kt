package xyz.luna.nextcloudextended.upload

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DownloadDao {
    @Insert
    suspend fun insert(download: DownloadEntity): Long

    @Query("SELECT * FROM download_operations WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): DownloadEntity?

    @Query("UPDATE download_operations SET state = :state, attempts = :attempts, lastError = :error WHERE id = :id")
    suspend fun updateState(id: Long, state: String, attempts: Int, error: String? = null)

    @Query("SELECT * FROM download_operations ORDER BY createdAt DESC")
    suspend fun all(): List<DownloadEntity>

    @Query("UPDATE download_operations SET state = 'QUEUED', lastError = NULL WHERE id = :id")
    suspend fun retry(id: Long)

    @Query("UPDATE download_operations SET state = 'CANCELLED' WHERE id = :id")
    suspend fun cancel(id: Long)
}
