package xyz.luna.nextcloudextended.upload

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OfflineFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: OfflineFileEntity)

    @Query("SELECT * FROM offline_files WHERE accountId = :accountId AND remotePath = :remotePath LIMIT 1")
    suspend fun find(accountId: String, remotePath: String): OfflineFileEntity?

    @Query("SELECT * FROM offline_files WHERE accountId = :accountId ORDER BY lastAccess DESC")
    suspend fun list(accountId: String): List<OfflineFileEntity>

    @Query("DELETE FROM offline_files WHERE accountId = :accountId AND remotePath = :remotePath")
    suspend fun delete(accountId: String, remotePath: String)

    @Query("DELETE FROM offline_files WHERE accountId = :accountId")
    suspend fun deleteAccount(accountId: String)
}
