package xyz.luna.nextcloudextended.account

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Credentials
import xyz.luna.nextcloudextended.data.network.DavSession

/**
 * Single access point to the encrypted preferences shared by the UI, WorkManager workers, the
 * DocumentsProvider and the contacts sync adapter (previously each re-implemented it).
 */
object SecureStore {
    private const val FILE = "secret_shared_prefs"

    @Volatile private var cached: SharedPreferences? = null

    fun prefs(context: Context): SharedPreferences? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: runCatching {
                val app = context.applicationContext
                val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    app, FILE, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrNull()?.also { cached = it }
        }
    }

    /** The profile with [accountId], or — for legacy rows without one — the active account. */
    fun profile(context: Context, accountId: String?): AccountProfile? {
        val prefs = prefs(context) ?: return null
        val profiles = AccountProfiles.load(prefs)
        val wanted = accountId?.takeIf { it.isNotBlank() } ?: prefs.getString("active_account_id", null)
        return profiles.firstOrNull { it.id == wanted } ?: if (accountId.isNullOrBlank()) profiles.firstOrNull() else null
    }

    fun activeProfile(context: Context): AccountProfile? = profile(context, null)
}

fun AccountProfile.openSession(): DavSession =
    DavSession(serverUrl, userId, Credentials.basic(username, password))
