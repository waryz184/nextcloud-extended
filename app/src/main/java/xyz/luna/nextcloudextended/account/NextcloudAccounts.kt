package xyz.luna.nextcloudextended.account

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import java.net.URI

/** Constants shared by the app, the authenticator and the sync adapter. */
object NextcloudAccounts {
    /** Android account type — appears under Settings → Accounts. */
    const val ACCOUNT_TYPE = "xyz.luna.nextcloudextended"

    /** The system Contacts provider authority this sync adapter feeds. */
    const val CONTACTS_AUTHORITY = "com.android.contacts"

    // AccountManager user-data keys (must match keys used by MainActivity login prefs).
    const val KEY_SERVER_URL = "server_url"
    const val KEY_USERNAME = "username"
    const val KEY_ADDRESS_BOOK_HREF = "address_book_href"
    const val KEY_ADDRESS_BOOK_NAME = "address_book_name"

    /** Sync period used when scheduling periodic syncs. */
    const val SYNC_INTERVAL_SECONDS = 6 * 60 * 60L
}

/** Trims, prepends https:// when needed and drops trailing slashes. "" is kept as-is. */
fun normalizeServerUrl(raw: String): String {
    val u = raw.trim()
    if (u.isEmpty()) return u
    val withScheme = if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    return withScheme.trimEnd('/')
}

/** Thin wrapper over [AccountManager] for the Nextcloud Extended account type. */
class NextcloudAccountManager(context: Context) {
    private val manager = AccountManager.get(context.applicationContext)

    /** Human-readable account name, e.g. "alice@cloud.example.com". */
    fun accountName(serverUrl: String, username: String): String {
        val host = runCatching { URI(normalizeServerUrl(serverUrl)).host }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        return if (host != null) "$username@$host"
        else "$username@${normalizeServerUrl(serverUrl).substringAfter("://").trimEnd('/')}"
    }

    /**
     * Persists a new system account. Returns null when the account already exists
     * (callers can then update its credentials instead).
     */
    fun createAccount(
        serverUrl: String,
        username: String,
        password: String,
        addressBookHref: String,
        addressBookName: String
    ): Account? {
        val account = Account(accountName(serverUrl, username), NextcloudAccounts.ACCOUNT_TYPE)
        if (!manager.addAccountExplicitly(account, password, null)) return null
        manager.setUserData(account, NextcloudAccounts.KEY_SERVER_URL, normalizeServerUrl(serverUrl))
        manager.setUserData(account, NextcloudAccounts.KEY_USERNAME, username)
        setAddressBook(account, addressBookHref, addressBookName)
        return account
    }

    fun getAccounts(): List<Account> =
        manager.getAccountsByType(NextcloudAccounts.ACCOUNT_TYPE).toList()

    fun firstAccount(): Account? = getAccounts().firstOrNull()

    fun serverUrlOf(account: Account): String =
        manager.getUserData(account, NextcloudAccounts.KEY_SERVER_URL) ?: ""

    fun usernameOf(account: Account): String =
        manager.getUserData(account, NextcloudAccounts.KEY_USERNAME) ?: ""

    fun passwordOf(account: Account): String = manager.getPassword(account) ?: ""

    fun addressBookHrefOf(account: Account): String =
        manager.getUserData(account, NextcloudAccounts.KEY_ADDRESS_BOOK_HREF) ?: ""

    fun addressBookNameOf(account: Account): String =
        manager.getUserData(account, NextcloudAccounts.KEY_ADDRESS_BOOK_NAME) ?: ""

    fun setAddressBook(account: Account, href: String, name: String) {
        manager.setUserData(account, NextcloudAccounts.KEY_ADDRESS_BOOK_HREF, href)
        manager.setUserData(account, NextcloudAccounts.KEY_ADDRESS_BOOK_NAME, name)
    }

    fun updatePassword(account: Account, password: String) {
        manager.setPassword(account, password)
    }

    /** Removes the account and its local contacts are removed by the platform automatically. */
    fun removeAccount(account: Account) {
        manager.removeAccountExplicitly(account)
    }
}
