package xyz.luna.nextcloudextended.upload

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

object OfflineCacheManager {
    suspend fun cache(context: Context, accountId: String, remotePath: String, bytes: ByteArray): OfflineFileEntity =
        withContext(Dispatchers.IO) {
            require(accountId.isNotBlank()) { "No active account" }
            val directory = File(context.filesDir, "offline/$accountId").apply { mkdirs() }
            val file = File(directory, sha256(remotePath))
            file.outputStream().use { it.write(bytes) }
            val entity = OfflineFileEntity(accountId, remotePath, file.absolutePath, file.length())
            NextcloudDatabase.get(context).offlineFiles().upsert(entity)
            entity
        }

    suspend fun get(context: Context, accountId: String, remotePath: String): File? =
        withContext(Dispatchers.IO) {
            val entity = NextcloudDatabase.get(context).offlineFiles().find(accountId, remotePath) ?: return@withContext null
            val file = File(entity.localPath)
            if (!file.exists()) {
                NextcloudDatabase.get(context).offlineFiles().delete(accountId, remotePath)
                return@withContext null
            }
            file
        }

    suspend fun remove(context: Context, accountId: String, remotePath: String) {
        withContext(Dispatchers.IO) {
            val entity = NextcloudDatabase.get(context).offlineFiles().find(accountId, remotePath)
            if (entity != null) runCatching { File(entity.localPath).delete() }
            NextcloudDatabase.get(context).offlineFiles().delete(accountId, remotePath)
        }
    }

    suspend fun clear(context: Context, accountId: String) {
        withContext(Dispatchers.IO) {
            val dao = NextcloudDatabase.get(context).offlineFiles()
            dao.list(accountId).forEach { runCatching { File(it.localPath).delete() } }
            dao.deleteAccount(accountId)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
