package xyz.luna.nextcloudextended.upload

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OfflineOperationReplayer {
    suspend fun enqueueDelete(context: Context, accountId: String, path: String) {
        val db = NextcloudDatabase.get(context)
        db.offlineOperations().insert(OfflineOperationEntity(accountId = accountId, operationType = "DELETE", path = path))
    }

    suspend fun enqueueRename(context: Context, accountId: String, path: String, newName: String) {
        val db = NextcloudDatabase.get(context)
        db.offlineOperations().insert(OfflineOperationEntity(accountId = accountId, operationType = "RENAME", path = path, extra = newName))
    }

    suspend fun enqueueCreateFolder(context: Context, accountId: String, parentHref: String, folderName: String) {
        val db = NextcloudDatabase.get(context)
        db.offlineOperations().insert(OfflineOperationEntity(accountId = accountId, operationType = "CREATE_FOLDER", path = parentHref, extra = folderName))
    }

    suspend fun count(context: Context): Int = withContext(Dispatchers.IO) {
        NextcloudDatabase.get(context).offlineOperations().count()
    }

    suspend fun replayNext(context: Context): Boolean = withContext(Dispatchers.IO) {
        val db = NextcloudDatabase.get(context)
        val operation = db.offlineOperations().next() ?: return@withContext false

        val prefs = runCatching {
            val key = androidx.security.crypto.MasterKey.Builder(context).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
            androidx.security.crypto.EncryptedSharedPreferences.create(context, "secret_shared_prefs", key,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.getOrNull() ?: return@withContext false

        val serverUrl = prefs.getString("server_url", "") ?: return@withContext false
        val username = prefs.getString("username", "") ?: return@withContext false
        val password = prefs.getString("password", "") ?: return@withContext false

        val client = xyz.luna.nextcloudextended.data.network.CalDavClient(serverUrl, username, password)
        try {
            when (operation.operationType) {
                "DELETE" -> {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        client.deleteFile(operation.path,
                            onSuccess = { if (continuation.isActive) continuation.resume(Unit) },
                            onFailure = { if (continuation.isActive) continuation.resumeWithException(it) }
                        )
                    }
                }
                "RENAME" -> {
                    val newName = operation.extra ?: return@withContext false
                    suspendCancellableCoroutine<Unit> { continuation ->
                        client.renameFile(operation.path, newName,
                            onSuccess = { if (continuation.isActive) continuation.resume(Unit) },
                            onFailure = { if (continuation.isActive) continuation.resumeWithException(it) }
                        )
                    }
                }
                "CREATE_FOLDER" -> {
                    val folderName = operation.extra ?: return@withContext false
                    suspendCancellableCoroutine<Unit> { continuation ->
                        client.createFolder(operation.path, folderName,
                            onSuccess = { if (continuation.isActive) continuation.resume(Unit) },
                            onFailure = { if (continuation.isActive) continuation.resumeWithException(it) }
                        )
                    }
                }
                else -> return@withContext false
            }
            db.offlineOperations().delete(operation.id)
            true
        } catch (error: Exception) {
            false
        }
    }
}