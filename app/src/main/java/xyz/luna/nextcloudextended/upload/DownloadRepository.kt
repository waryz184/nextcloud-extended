package xyz.luna.nextcloudextended.upload

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DownloadRepository {
    const val UNIQUE_PREFIX = "nextcloud-download-"

    suspend fun enqueue(context: Context, accountId: String, remotePath: String, fileName: String): Long =
        withContext(Dispatchers.IO) {
            val destination = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val database = NextcloudDatabase.get(context)
            val id = database.downloads().insert(
                DownloadEntity(accountId = accountId, remotePath = remotePath, fileName = fileName, destination = destination.absolutePath)
            )
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(Data.Builder().putLong(DownloadWorker.KEY_ID, id).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_PREFIX + id, ExistingWorkPolicy.KEEP, request)
            id
        }

    fun retry(context: Context, id: Long) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(Data.Builder().putLong(DownloadWorker.KEY_ID, id).build())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_PREFIX + id, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PREFIX + id)
    }
}
