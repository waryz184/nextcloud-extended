package xyz.luna.nextcloudextended.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.accounts.NetworkErrorException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import xyz.luna.nextcloudextended.R
import xyz.luna.nextcloudextended.sync.AccountSetupActivity

/**
 * System-level authenticator: lets the phone's "Add account" flow create a
 * Nextcloud Extended account and lets the sync adapter fetch the stored password.
 * Credentials are HTTP Basic (user + app password), so the "auth token" IS the password.
 */
class NextcloudAccountAuthenticator(private val context: Context) :
    AbstractAccountAuthenticator(context) {

    private val accountManager get() = AccountManager.get(context)

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle {
        val intent = Intent(context, AccountSetupActivity::class.java)
            .putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
        return Bundle().apply {
            putParcelable(AccountManager.KEY_INTENT, intent)
        }
    }

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse,
        account: Account,
        options: Bundle?
    ): Bundle {
        return Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true) }
    }

    override fun getAuthToken(
        response: AccountAuthenticatorResponse,
        account: Account,
        authTokenType: String,
        options: Bundle
    ): Bundle {
        val password = accountManager.getPassword(account)
        if (password != null) {
            return Bundle().apply {
                putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
                putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
                putString(AccountManager.KEY_AUTHTOKEN, password)
            }
        }
        // Missing credentials → send the user through account setup.
        val intent = Intent(context, AccountSetupActivity::class.java)
            .putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
            .putExtra(AccountSetupActivity.EXTRA_ACCOUNT, account)
        return Bundle().apply { putParcelable(AccountManager.KEY_INTENT, intent) }
    }

    override fun getAuthTokenLabel(authTokenType: String): String =
        context.getString(R.string.auth_token_label)

    override fun hasFeatures(
        response: AccountAuthenticatorResponse,
        account: Account,
        features: Array<out String>
    ): Bundle = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true) }

    override fun updateCredentials(
        response: AccountAuthenticatorResponse,
        account: Account,
        authTokenType: String?,
        options: Bundle?
    ): Bundle {
        val intent = Intent(context, AccountSetupActivity::class.java)
            .putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
            .putExtra(AccountSetupActivity.EXTRA_ACCOUNT, account)
            .putExtra(AccountSetupActivity.EXTRA_UPDATE_PASSWORD, true)
        return Bundle().apply { putParcelable(AccountManager.KEY_INTENT, intent) }
    }

    override fun editProperties(
        response: AccountAuthenticatorResponse,
        accountType: String
    ): Bundle = Bundle()

    override fun getAccountRemovalAllowed(
        response: AccountAuthenticatorResponse,
        account: Account
    ): Bundle = Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true) }
}
