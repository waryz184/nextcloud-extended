package xyz.luna.nextcloudextended.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.luna.nextcloudextended.account.SecureStore
import xyz.luna.nextcloudextended.account.openSession
import xyz.luna.nextcloudextended.data.network.FileApi
import xyz.luna.nextcloudextended.data.network.HttpStatusException
import xyz.luna.nextcloudextended.data.network.awaitBlocking
import xyz.luna.nextcloudextended.data.network.isTransientFailure
import java.util.concurrent.TimeUnit

/**
 * Deletes, renames and folder creations requested while offline. They are replayed by [Worker] as
 * soon as a connection exists — not only when the app happens to be open — against the account that
 * queued them, and a permanently refused operation is dropped instead of blocking the whole queue.
 */
object OfflineOperationReplayer {
    private const val UNIQUE_WORK = "nextcloud-offline-operations"

    suspend fun enqueueDelete(context: Context, accountId: String, path: String) {
        NextcloudDatabase.get(context).offlineOperations()
            .insert(OfflineOperationEntity(accountId = accountId, operationType = "DELETE", path = path))
        schedule(context)
    }

    suspend fun enqueueRename(context: Context, accountId: String, path: String, newName: String) {
        NextcloudDatabase.get(context).offlineOperations()
            .insert(OfflineOperationEntity(accountId = accountId, operationType = "RENAME", path = path, extra = newName))
        schedule(context)
    }

    suspend fun enqueueCreateFolder(context: Context, accountId: String, parentHref: String, folderName: String) {
        NextcloudDatabase.get(context).offlineOperations()
            .insert(OfflineOperationEntity(accountId = accountId, operationType = "CREATE_FOLDER", path = parentHref, extra = folderName))
        schedule(context)
    }

    suspend fun count(context: Context): Int = withContext(Dispatchers.IO) {
        NextcloudDatabase.get(context).offlineOperations().count()
    }

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<Worker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Replays the oldest operation. Returns true when the queue should continue (done or dropped) and
     * false when it must stop (nothing left, or a transient failure that needs another attempt).
     */
    suspend fun replayNext(context: Context): Boolean = withContext(Dispatchers.IO) {
        val dao = NextcloudDatabase.get(context).offlineOperations()
        val operation = dao.next() ?: return@withContext false
        val profile = SecureStore.profile(context, operation.accountId)
        if (profile == null) {
            dao.delete(operation.id) // the account was removed: nobody can replay this
            return@withContext true
        }
        val session = profile.openSession()
        val api = FileApi(session)
        try {
            session.awaitBlocking {
                when (operation.operationType) {
                    "DELETE" -> api.delete(operation.path)
                    "RENAME" -> operation.extra?.let { api.rename(operation.path, it) }
                    "CREATE_FOLDER" -> operation.extra?.let { api.mkdir(operation.path.trimEnd('/') + "/" + it, createParents = true) }
                    else -> Unit
                }
            }
            dao.delete(operation.id)
            true
        } catch (error: Exception) {
            if (error.isTransientFailure()) return@withContext false
            // Refused for good (target exists, no permission…): drop it so the rest of the queue moves on.
            if (error is HttpStatusException || error is IllegalArgumentException) {
                dao.delete(operation.id)
                true
            } else false
        }
    }

    class Worker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            var guard = 0
            while (guard++ < 500) {
                if (count(applicationContext) == 0) return Result.success()
                if (!replayNext(applicationContext)) {
                    return if (count(applicationContext) == 0) Result.success() else Result.retry()
                }
            }
            return Result.retry()
        }
    }
}
