package xyz.luna.nextcloudextended.upload

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.luna.nextcloudextended.account.SecureStore
import xyz.luna.nextcloudextended.data.network.LocalIoException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object UploadRepository {
    const val UNIQUE_WORK = "nextcloud-upload-queue"

    suspend fun enqueue(
        context: Context, accountId: String, source: String, parent: String, name: String, length: Long?,
        overwrite: Boolean = false, sourceMtime: Long? = null, expectedEtag: String? = null
    ): Long = withContext(Dispatchers.IO) {
        val database = NextcloudDatabase.get(context)
        val id = database.uploads().insert(
            UploadEntity(accountId = accountId, source = source, parent = parent, name = name, length = length,
                overwrite = overwrite, sourceMtime = sourceMtime ?: File(source).lastModified().takeIf { it > 0 }?.div(1000),
                expectedEtag = expectedEtag)
        )
        schedule(context)
        id
    }

    /**
     * Copies [uri] into app storage (a shared content URI grant does not survive a process restart) and
     * queues it. Refuses early when the device cannot hold the copy instead of failing half way.
     */
    suspend fun enqueueUri(
        context: Context, accountId: String, uri: Uri, parent: String, name: String,
        overwrite: Boolean = false, sourceMtime: Long? = null
    ): Long = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "pending-uploads").apply { mkdirs() }
        val suffix = name.substringAfterLast('.', "bin").let { ".${it.take(8)}" }
        val staged = File.createTempFile("upload_", suffix, directory)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val expected = declaredSize(context, uri)
                if (expected != null && StatFs(directory.absolutePath).availableBytes < expected + SAFETY_MARGIN) {
                    throw LocalIoException("Not enough local storage to queue $name")
                }
                staged.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IOException("Upload source is unavailable")
            enqueue(context, accountId, staged.absolutePath, parent, name, staged.length().takeIf { it >= 0L },
                overwrite, sourceMtime ?: lastModifiedSeconds(context, uri))
        } catch (error: Exception) {
            staged.delete()
            throw error
        }
    }

    fun schedule(context: Context) {
        val prefs = SecureStore.prefs(context)
        val wifiOnly = prefs?.getBoolean(MediaAutoUploadReceiver.KEY_WIFI_ONLY, false) ?: false
        val chargingOnly = prefs?.getBoolean(MediaAutoUploadReceiver.KEY_CHARGING_ONLY, false) ?: false
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresCharging(chargingOnly)
            .build()
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE (not KEEP): an item queued just as the running worker finishes would
        // otherwise wait for the next unrelated trigger.
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }

    private const val SAFETY_MARGIN = 50L * 1024 * 1024

    private fun declaredSize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    /** Original modification time (epoch seconds) from MediaStore / SAF, when the provider knows it. */
    fun lastModifiedSeconds(context: Context, uri: Uri): Long? {
        // Providers throw on columns they do not know, so each candidate is probed on its own.
        fun probe(column: String, divisor: Long): Long? = runCatching {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) / divisor else null
            }
        }.getOrNull()
        return (probe(MediaStore.MediaColumns.DATE_MODIFIED, 1) ?: probe(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 1000))
            ?.takeIf { it > 0 }
    }
}
