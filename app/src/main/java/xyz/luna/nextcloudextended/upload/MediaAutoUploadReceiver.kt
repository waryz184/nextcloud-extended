package xyz.luna.nextcloudextended.upload

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.luna.nextcloudextended.account.SecureStore
import java.util.concurrent.TimeUnit

/**
 * Entry point for the optional photo/video auto-upload: (re)arms the scanner after boot, an app
 * update, or when the user switches the feature on. The scan itself runs in [MediaScanWorker].
 */
class MediaAutoUploadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (SecureStore.prefs(context)?.getBoolean(KEY_ENABLED, false) == true) {
                    MediaAutoUpload.arm(context)
                    MediaAutoUpload.scan(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val KEY_ENABLED = "media_auto_upload_enabled"
        const val KEY_LAST_SCAN = "media_auto_upload_last_scan"
        const val KEY_SUBFOLDER = "media_auto_upload_subfolder"
        const val KEY_WIFI_ONLY = "media_auto_upload_wifi_only"
        const val KEY_CHARGING_ONLY = "media_auto_upload_charging_only"

        fun cancelSchedule(context: Context) = MediaAutoUpload.disarm(context)
    }
}

object MediaAutoUpload {
    private const val OBSERVER_WORK = "nextcloud-media-observer"
    private const val PERIODIC_WORK = "nextcloud-media-periodic"
    private const val KEY_CURSOR_PREFIX = "media_auto_upload_cursor_"

    /**
     * Wakes up when MediaStore changes (a photo was taken) instead of polling every six hours, and
     * keeps a periodic pass as a safety net for changes missed while the device dozed.
     */
    fun arm(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val trigger = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniqueWork(
            OBSERVER_WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<MediaScanWorker>().setConstraints(trigger)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
        )
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MediaScanWorker>(6, TimeUnit.HOURS).build()
        )
    }

    fun disarm(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        manager.cancelUniqueWork(OBSERVER_WORK)
        manager.cancelUniqueWork(PERIODIC_WORK)
    }

    private class Cursor(var dateAdded: Long, var id: Long)

    /** Forget where the last scan stopped, so the next one starts from the moment the feature is enabled. */
    fun resetCursors(prefs: android.content.SharedPreferences) {
        val editor = prefs.edit().remove(MediaAutoUploadReceiver.KEY_LAST_SCAN)
        prefs.all.keys.filter { it.startsWith(KEY_CURSOR_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /**
     * Queues media added since the last successful pass. The cursor only advances past items that were
     * really queued, so a failure (disk full, revoked permission) never silently skips photos; ties on
     * `DATE_ADDED` are broken by `_ID`, so nothing is queued twice either.
     * @return false when something should be retried later.
     */
    suspend fun scan(context: Context): Boolean {
        val prefs = SecureStore.prefs(context) ?: return true
        if (!prefs.getBoolean(MediaAutoUploadReceiver.KEY_ENABLED, false)) return true
        val profile = SecureStore.activeProfile(context) ?: return true
        val subfolder = prefs.getString(MediaAutoUploadReceiver.KEY_SUBFOLDER, "InstantUpload")?.trim('/') ?: "InstantUpload"
        val root = "/remote.php/dav/files/${profile.userId}/"
        val parent = if (subfolder.isEmpty()) root else "$root$subfolder/"

        val now = System.currentTimeMillis() / 1000L
        val legacy = prefs.getLong(MediaAutoUploadReceiver.KEY_LAST_SCAN, now)
        var complete = true
        for ((key, collection) in listOf(
            "images" to MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "video" to MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            val cursor = Cursor(prefs.getLong("${KEY_CURSOR_PREFIX}${key}_date", legacy), prefs.getLong("${KEY_CURSOR_PREFIX}${key}_id", 0))
            if (!scanCollection(context, profile.id, collection, cursor, parent)) complete = false
            prefs.edit().putLong("${KEY_CURSOR_PREFIX}${key}_date", cursor.dateAdded).putLong("${KEY_CURSOR_PREFIX}${key}_id", cursor.id).apply()
        }
        // The very first pass after enabling must not upload the whole existing gallery.
        if (!prefs.contains(MediaAutoUploadReceiver.KEY_LAST_SCAN)) prefs.edit().putLong(MediaAutoUploadReceiver.KEY_LAST_SCAN, legacy).apply()
        return complete
    }

    private suspend fun scanCollection(context: Context, accountId: String, collection: Uri, cursor: Cursor, parent: String): Boolean {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_TAKEN
        )
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} > ? OR (${MediaStore.MediaColumns.DATE_ADDED} = ? AND ${MediaStore.MediaColumns._ID} > ?)"
        val args = arrayOf(cursor.dateAdded.toString(), cursor.dateAdded.toString(), cursor.id.toString())
        val order = "${MediaStore.MediaColumns.DATE_ADDED} ASC, ${MediaStore.MediaColumns._ID} ASC"
        return try {
            context.contentResolver.query(collection, projection, selection, args, order)?.use { rows ->
                val idIndex = rows.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val addedIndex = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val modifiedIndex = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val takenIndex = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                while (rows.moveToNext()) {
                    val id = rows.getLong(idIndex)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val name = rows.getString(nameIndex) ?: "media_$id"
                    val taken = if (rows.isNull(takenIndex)) 0L else rows.getLong(takenIndex) / 1000L
                    val modified = if (rows.isNull(modifiedIndex)) 0L else rows.getLong(modifiedIndex)
                    try {
                        UploadRepository.enqueueUri(context, accountId, uri, parent, name, overwrite = false,
                            sourceMtime = (taken.takeIf { it > 0 } ?: modified).takeIf { it > 0 })
                    } catch (e: Exception) {
                        return@use false
                    }
                    cursor.dateAdded = rows.getLong(addedIndex)
                    cursor.id = id
                }
                true
            } ?: true
        } catch (e: SecurityException) {
            true // media permission was revoked: nothing to retry until the user grants it again
        }
    }
}

class MediaScanWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val complete = MediaAutoUpload.scan(applicationContext)
        val prefs = SecureStore.prefs(applicationContext)
        // Re-arm the content trigger: a triggered one-time request only fires once.
        if (prefs?.getBoolean(MediaAutoUploadReceiver.KEY_ENABLED, false) == true) MediaAutoUpload.arm(applicationContext)
        return if (complete) Result.success() else Result.retry()
    }
}
