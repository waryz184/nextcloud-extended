package xyz.luna.nextcloudextended.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.MimeTypeMap
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.luna.nextcloudextended.account.SecureStore
import xyz.luna.nextcloudextended.account.openSession
import xyz.luna.nextcloudextended.data.network.FailureKind
import xyz.luna.nextcloudextended.data.network.FileApi
import xyz.luna.nextcloudextended.data.network.LocalIoException
import xyz.luna.nextcloudextended.data.network.OcsApi
import xyz.luna.nextcloudextended.data.network.awaitBlocking
import xyz.luna.nextcloudextended.data.network.failureKind
import java.io.File
import java.io.IOException

/**
 * Drains the persistent upload queue. Every item is handled on its own: a permanent failure (quota,
 * missing source…) never blocks the ones behind it, transient failures are retried with WorkManager's
 * back-off, and an interrupted worker resumes chunked uploads where the server left off.
 */
class UploadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dao = NextcloudDatabase.get(applicationContext).uploads()
        // A previous run killed with RUNNING rows would otherwise leave them stuck forever.
        dao.resetRunning()
        var retry = false
        val deferred = HashSet<Long>()
        while (true) {
            val operation = dao.nextPending() ?: break
            if (!deferred.add(operation.id)) break // already tried (and deferred) in this run
            dao.updateState(operation.id, UploadEntity.STATE_RUNNING, operation.attempts)
            setProgress(Data.Builder().putString("state", "Uploading ${operation.name}").build())
            try {
                upload(operation)
                dao.delete(operation.id)
                deleteStaged(operation)
            } catch (cancel: CancellationException) {
                // Stopped by the system (constraints lost, time limit): keep the item for the next run.
                withContext(kotlinx.coroutines.NonCancellable) { dao.updateState(operation.id, UploadEntity.STATE_RETRY, operation.attempts) }
                throw cancel
            } catch (error: Exception) {
                val connectivity = error.failureKind().let {
                    it == FailureKind.NO_NETWORK || it == FailureKind.HOST_UNREACHABLE || it == FailureKind.TIMEOUT
                }
                val attempts = operation.attempts + 1
                if (UploadRetryPolicy.isRetryable(error) && attempts < UploadRetryPolicy.maxAttempts(error)) {
                    dao.updateState(operation.id, UploadEntity.STATE_RETRY, attempts, describe(error))
                    retry = true
                    // Offline: every other item would fail the same way — stop and let WorkManager wait.
                    if (connectivity) break
                } else {
                    dao.updateState(operation.id, UploadEntity.STATE_FAILED, attempts, describe(error))
                }
            }
        }
        if (retry) Result.retry() else Result.success()
    }

    private fun describe(error: Throwable): String = error.message ?: error.javaClass.simpleName

    private fun deleteStaged(operation: UploadEntity) {
        File(operation.source).takeIf { it.parentFile?.name == "pending-uploads" }?.delete()
    }

    private suspend fun upload(operation: UploadEntity) {
        val profile = SecureStore.profile(applicationContext, operation.accountId)
            ?: throw LocalIoException("The upload account is no longer available")
        val file = File(operation.source)
        if (!file.isFile) throw LocalIoException("The file to upload is gone: ${operation.name}")

        val session = profile.openSession()
        val api = FileApi(session)
        session.awaitBlocking {
            val capabilities = runCatching { OcsApi(session).capabilities() }.getOrNull()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(operation.name.substringAfterLast('.', "").lowercase())
            val source = FileApi.UploadSource.ofFile(file, mime).let { base ->
                FileApi.UploadSource(base.length, operation.sourceMtime ?: base.lastModifiedSeconds, mime, base.open)
            }
            var lastReport = 0L
            val options = FileApi.UploadOptions(
                policy = when {
                    operation.overwrite && operation.expectedEtag != null -> FileApi.CollisionPolicy.OVERWRITE_OR_KEEP_BOTH
                    operation.overwrite -> FileApi.CollisionPolicy.OVERWRITE
                    else -> FileApi.CollisionPolicy.RENAME
                },
                expectedEtag = operation.expectedEtag,
                chunkSize = FileApi.chunkSizeFor(isUnmetered(), capabilities?.chunkedUploadMaxSize),
                chunkedEnabled = capabilities?.bigFileChunking != false,
                progress = { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 1_000) {
                        lastReport = now
                        setProgressAsync(Data.Builder().putString("state", "Uploading ${operation.name}")
                            .putLong("done", done).putLong("total", total).build())
                    }
                }
            )
            api.upload(operation.parent, operation.name, source, options)
        }
    }

    private fun isUnmetered(): Boolean = runCatching {
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }.getOrDefault(false)
}
