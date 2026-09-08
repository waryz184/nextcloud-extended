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
import xyz.luna.nextcloudextended.data.network.CalDavClient
import xyz.luna.nextcloudextended.account.AccountProfiles
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class UploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dao = NextcloudDatabase.get(applicationContext).uploads()
        var retry = false
        while (true) {
            val operation = dao.nextPending() ?: break
            dao.updateState(operation.id, UploadEntity.STATE_RUNNING, operation.attempts + 1)
            setProgress(androidx.work.Data.Builder().putString("state", "Uploading ${operation.name}").build())
            try {
                upload(operation)
                dao.delete(operation.id)
                File(operation.source).takeIf { it.parentFile?.name == "pending-uploads" }?.delete()
            } catch (error: Exception) {
                val attempts = operation.attempts + 1
                if (UploadRetryPolicy.isRetryable(error) && attempts < UploadRetryPolicy.MAX_ATTEMPTS) {
                    dao.updateState(operation.id, UploadEntity.STATE_RETRY, attempts, error.message)
                    retry = true
                    break
                }
                dao.updateState(operation.id, UploadEntity.STATE_FAILED, attempts, error.message)
            }
        }
        if (retry) Result.retry() else Result.success()
    }

    private suspend fun upload(operation: UploadEntity) {
        val prefs = securePrefs() ?: throw IOException("No saved account")
        val accountId = operation.accountId.ifBlank { prefs.getString("active_account_id", "") ?: "" }
        val profile = AccountProfiles.load(prefs).firstOrNull { it.id == accountId }
            ?: throw IOException("The upload account is no longer available")
        val client = CalDavClient(
            profile.serverUrl,
            profile.username,
            profile.password
        )
        val source = operation.source
        val streamFactory = {
            File(source).inputStream()
        }
        withTimeout(TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                client.uploadFile(operation.parent, operation.name, operation.length, streamFactory,
                    onSuccess = { if (continuation.isActive) continuation.resume(Unit) },
                    onFailure = { if (continuation.isActive) continuation.resumeWithException(it) }
                )
                continuation.invokeOnCancellation { client.cancelAll() }
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
        private const val TIMEOUT_MS = 90_000L
    }
}
