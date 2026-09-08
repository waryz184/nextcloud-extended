package xyz.luna.nextcloudextended.upload

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import xyz.luna.nextcloudextended.account.AccountProfiles
import xyz.luna.nextcloudextended.data.network.CalDavClient
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong(KEY_ID, -1L)
        val dao = NextcloudDatabase.get(applicationContext).downloads()
        val operation = dao.get(id) ?: return@withContext Result.failure()
        dao.updateState(id, DownloadEntity.STATE_RUNNING, operation.attempts + 1)
        try {
            val prefs = securePrefs() ?: throw IOException("No saved account")
            val profile = AccountProfiles.load(prefs).firstOrNull { it.id == operation.accountId }
                ?: throw IOException("The download account is no longer available")
            val client = CalDavClient(profile.serverUrl, profile.username, profile.password)
            setProgress(androidx.work.Data.Builder().putString("state", "Downloading ${operation.fileName}").build())
            val bytes = withTimeout(TIMEOUT_MS) {
                suspendCancellableCoroutine<ByteArray> { continuation ->
                    client.downloadFile(operation.remotePath,
                        onSuccess = { if (continuation.isActive) continuation.resume(it) },
                        onFailure = { if (continuation.isActive) continuation.resumeWithException(it) }
                    )
                    continuation.invokeOnCancellation { client.cancelAll() }
                }
            }
            val output = File(operation.destination, operation.fileName)
            output.parentFile?.mkdirs()
            output.outputStream().use { it.write(bytes) }
            dao.updateState(id, DownloadEntity.STATE_COMPLETED, operation.attempts + 1)
            Result.success()
        } catch (error: Exception) {
            val attempts = operation.attempts + 1
            if (attempts < MAX_ATTEMPTS) {
                dao.updateState(id, DownloadEntity.STATE_RETRY, attempts, error.message)
                Result.retry()
            } else {
                dao.updateState(id, DownloadEntity.STATE_FAILED, attempts, error.message)
                Result.failure()
            }
        }
    }

    private fun securePrefs() = runCatching {
        val key = MasterKey.Builder(applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(applicationContext, "secret_shared_prefs", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }.getOrNull()

    companion object {
        const val KEY_ID = "download_id"
        private const val TIMEOUT_MS = 90_000L
        private const val MAX_ATTEMPTS = 5
    }
}
