package xyz.luna.nextcloudextended.upload

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import xyz.luna.nextcloudextended.account.SecureStore
import xyz.luna.nextcloudextended.account.openSession
import xyz.luna.nextcloudextended.data.network.FailureKind
import xyz.luna.nextcloudextended.data.network.FileApi
import xyz.luna.nextcloudextended.data.network.LocalIoException
import xyz.luna.nextcloudextended.data.network.awaitBlocking
import xyz.luna.nextcloudextended.data.network.failureKind
import java.io.File

/**
 * Downloads straight to disk through a resumable `.part` file (no in-memory copy, so the old 25 MB
 * ceiling is gone), then publishes the result to the public Downloads folder on Android 10+.
 */
class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getLong(KEY_ID, -1L)
        val dao = NextcloudDatabase.get(applicationContext).downloads()
        val operation = dao.get(id) ?: return@withContext Result.failure()
        if (operation.state == DownloadEntity.STATE_COMPLETED || operation.state == DownloadEntity.STATE_CANCELLED) {
            return@withContext Result.success()
        }
        dao.updateState(id, DownloadEntity.STATE_RUNNING, operation.attempts)
        try {
            val profile = SecureStore.profile(applicationContext, operation.accountId)
                ?: throw LocalIoException("The download account is no longer available")
            val session = profile.openSession()
            val api = FileApi(session)
            setProgress(Data.Builder().putString("state", "Downloading ${operation.fileName}").build())
            val safeName = operation.fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download" }
            val target = File(operation.destination, safeName)
            var lastReport = 0L
            session.awaitBlocking {
                api.download(operation.remotePath, target) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 1_000) {
                        lastReport = now
                        setProgressAsync(
                            Data.Builder().putString("state", "Downloading ${operation.fileName}")
                                .putLong("done", done).putLong("total", total).build()
                        )
                    }
                }
            }
            val published = publishToDownloads(target, safeName)
            dao.updateState(id, DownloadEntity.STATE_COMPLETED, operation.attempts + 1, published)
            Result.success()
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) { dao.updateState(id, DownloadEntity.STATE_RETRY, operation.attempts) }
            throw cancel
        } catch (error: Exception) {
            val attempts = operation.attempts + 1
            val connectivity = error.failureKind().let {
                it == FailureKind.NO_NETWORK || it == FailureKind.HOST_UNREACHABLE || it == FailureKind.TIMEOUT
            }
            val limit = if (connectivity) UploadRetryPolicy.MAX_CONNECTIVITY_ATTEMPTS else MAX_ATTEMPTS
            if (UploadRetryPolicy.isRetryable(error) && attempts < limit) {
                dao.updateState(id, DownloadEntity.STATE_RETRY, attempts, error.message)
                Result.retry()
            } else {
                dao.updateState(id, DownloadEntity.STATE_FAILED, attempts, error.message)
                Result.failure()
            }
        }
    }

    /** Copies into Downloads/Nextcloud so the file is visible to the user. Returns a note, or null. */
    private fun publishToDownloads(file: File, displayName: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val resolver = applicationContext.contentResolver
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(displayName.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Nextcloud")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching null
            try {
                val out = resolver.openOutputStream(uri) ?: error("No output stream")
                out.use { stream -> file.inputStream().use { it.copyTo(stream) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                file.delete()
                "Downloads/Nextcloud/$displayName"
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                null
            }
        }.getOrNull()
    }

    companion object {
        const val KEY_ID = "download_id"
        private const val MAX_ATTEMPTS = 6
    }
}
