package xyz.luna.nextcloudextended.sync

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import android.app.Service
import android.util.Log

/**
 * Bound service that exposes the contacts sync adapter to the Android sync framework.
 * The system binds to this service when a sync is scheduled or triggered manually.
 */
class NextcloudContactsSyncService : Service() {

    private lateinit var syncAdapter: NextcloudContactsSyncAdapter

    override fun onCreate() {
        super.onCreate()
        syncAdapter = NextcloudContactsSyncAdapter(applicationContext, true)
    }

    override fun onBind(intent: Intent?): IBinder = syncAdapter.syncAdapterBinder
}

/**
 * [AbstractThreadedSyncAdapter] that performs a bidirectional sync of the Nextcloud
 * CardDAV address book with the phone's Contacts provider.
 */
class NextcloudContactsSyncAdapter(
    context: Context,
    autoInitialize: Boolean
) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient?,
        syncResult: SyncResult
    ) {
        try {
            Log.d("NextcloudContactsSync", "Starting sync for account: ${account.name}")
            ContactSyncEngine(context).sync(account, syncResult)
            Log.d("NextcloudContactsSync", "Sync completed: " +
                    "inserts=${syncResult.stats.numInserts}, " +
                    "updates=${syncResult.stats.numUpdates}, " +
                    "deletes=${syncResult.stats.numDeletes}, " +
                    "errors=${syncResult.stats.numIoExceptions + syncResult.stats.numParseExceptions}")
        } catch (e: Exception) {
            Log.e("NextcloudContactsSync", "Sync failed", e)
            syncResult.stats.numIoExceptions++
        }
    }
}