package xyz.luna.nextcloudextended.upload

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Scans only media created since the previous scan and adds it to the upload queue. */
class MediaAutoUploadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                scan(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun scan(context: Context) {
        val prefs = securePrefs(context) ?: return
        if (!prefs.getBoolean(KEY_ENABLED, false)) return

        val now = System.currentTimeMillis() / 1000L
        val previous = prefs.getLong(KEY_LAST_SCAN, now)
        val username = prefs.getString("username", "") ?: return
        val accountId = prefs.getString("active_account_id", "")?.takeIf { it.isNotBlank() } ?: return
        val subfolder = prefs.getString(KEY_SUBFOLDER, "InstantUpload")?.trim('/') ?: "InstantUpload"
        val parent = if (subfolder.isEmpty()) "/remote.php/dav/files/$username/" else "/remote.php/dav/files/$username/$subfolder/"
        scanCollection(context, accountId, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, previous, parent)
        scanCollection(context, accountId, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, previous, parent)
        prefs.edit().putLong(KEY_LAST_SCAN, now).apply()
        scheduleNext(context)
    }

    private suspend fun scanCollection(context: Context, accountId: String, collection: android.net.Uri, since: Long, parent: String) {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.DATE_ADDED} > ?"
        runCatching {
            context.contentResolver.query(collection, projection, selection, arrayOf(since.toString()), null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                    val name = cursor.getString(nameIndex) ?: "media_${System.currentTimeMillis()}"
                    UploadRepository.enqueueUri(context, accountId, uri, parent, name)
                }
            }
        }
    }

    private fun scheduleNext(context: Context) {
        val pending = PendingIntent.getBroadcast(context, REQUEST_CODE,
            Intent(context, MediaAutoUploadReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setInexactRepeating(
            AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + INTERVAL_MS, INTERVAL_MS, pending
        )
    }

    companion object {
        const val KEY_ENABLED = "media_auto_upload_enabled"
        const val KEY_LAST_SCAN = "media_auto_upload_last_scan"
        const val KEY_SUBFOLDER = "media_auto_upload_subfolder"
        const val KEY_WIFI_ONLY = "media_auto_upload_wifi_only"
        const val KEY_CHARGING_ONLY = "media_auto_upload_charging_only"

        fun cancelSchedule(context: Context) {
            val pending = PendingIntent.getBroadcast(context, REQUEST_CODE,
                Intent(context, MediaAutoUploadReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            context.getSystemService(AlarmManager::class.java).cancel(pending)
        }

        private const val REQUEST_CODE = 2403
        private const val INTERVAL_MS = 6 * 60 * 60 * 1000L
    }

    private fun securePrefs(context: Context) = runCatching {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "secret_shared_prefs", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }.getOrNull()

}
