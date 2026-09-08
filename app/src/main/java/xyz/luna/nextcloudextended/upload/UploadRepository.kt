package xyz.luna.nextcloudextended.upload

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object UploadRepository {
    const val UNIQUE_WORK = "nextcloud-upload-queue"

    suspend fun enqueue(context: Context, accountId: String, source: String, parent: String, name: String, length: Long?): Long =
        withContext(Dispatchers.IO) {
            val database = NextcloudDatabase.get(context)
            val id = database.uploads().insert(UploadEntity(accountId = accountId, source = source, parent = parent, name = name, length = length))
            schedule(context)
            id
        }

    suspend fun enqueueUri(context: Context, accountId: String, uri: Uri, parent: String, name: String): Long =
        withContext(Dispatchers.IO) {
            val directory = File(context.filesDir, "pending-uploads").apply { mkdirs() }
            val suffix = name.substringAfterLast('.', "bin").let { ".${it.take(8)}" }
            val staged = File.createTempFile("upload_", suffix, directory)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("Upload source is unavailable")
                enqueue(context, accountId, staged.absolutePath, parent, name, staged.length().takeIf { it >= 0L })
            } catch (error: Exception) {
                staged.delete()
                throw error
            }
        }

    fun schedule(context: Context) {
        val prefs = runCatching {
            val key = androidx.security.crypto.MasterKey.Builder(context).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
            androidx.security.crypto.EncryptedSharedPreferences.create(context, "secret_shared_prefs", key,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.getOrNull()
        val wifiOnly = prefs?.getBoolean(MediaAutoUploadReceiver.KEY_WIFI_ONLY, false) ?: false
        val chargingOnly = prefs?.getBoolean(MediaAutoUploadReceiver.KEY_CHARGING_ONLY, false) ?: false
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(chargingOnly)
            .build()
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }
}
