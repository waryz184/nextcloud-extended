package xyz.luna.nextcloudextended.sync

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.accounts.NetworkErrorException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.luna.nextcloudextended.AppLanguage
import xyz.luna.nextcloudextended.LocalStrings
import xyz.luna.nextcloudextended.Strings
import xyz.luna.nextcloudextended.account.NextcloudAccountManager
import xyz.luna.nextcloudextended.account.NextcloudAccounts
import xyz.luna.nextcloudextended.account.normalizeServerUrl
import xyz.luna.nextcloudextended.data.network.CalDavClient
import xyz.luna.nextcloudextended.ui.theme.NextcloudExtendedTheme
import java.util.Locale

/**
 * Launched from the system Account Settings (via the Authenticator) or from inside the app
 * to add/update a Nextcloud account. Collects credentials, verifies via CardDAV, lets the user
 * pick an address book, and persists the account into [AccountManager].
 */
class AccountSetupActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ACCOUNT = "account"
        const val EXTRA_UPDATE_PASSWORD = "update_password"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val response = intent.getParcelableExtra<AccountAuthenticatorResponse>(
            AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE
        )
        @Suppress("DEPRECATION")
        val existingAccount = intent.getParcelableExtra<Account>(EXTRA_ACCOUNT)
        val isUpdate = intent.getBooleanExtra(EXTRA_UPDATE_PASSWORD, false)

        setContent {
            NextcloudExtendedTheme {
                AccountSetupScreen(
                    response = response,
                    existingAccount = existingAccount,
                    isUpdate = isUpdate,
                    onFinished = { finish() }
                )
            }
        }
    }
}

// ── UI state machine ─────────────────────────────────────────────────────────────────

private sealed class SetupStep {
    data object Credentials : SetupStep()
    data class AddressBook(val books: List<Pair<String, String>>) : SetupStep()
    data object Done : SetupStep()
}

@Composable
private fun AccountSetupScreen(
    response: AccountAuthenticatorResponse?,
    existingAccount: Account?,
    isUpdate: Boolean,
    onFinished: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val language = remember {
        if (Locale.getDefault().language == "fr") AppLanguage.FR else AppLanguage.EN
    }
    val s = stringsFor(language) // local helper

    val am = remember { NextcloudAccountManager(context) }

    // Pre-fill from existing account.
    var serverUrl by remember {
        mutableStateOf(
            if (existingAccount != null) am.serverUrlOf(existingAccount) else ""
        )
    }
    var username by remember {
        mutableStateOf(
            if (existingAccount != null) am.usernameOf(existingAccount) else ""
        )
    }
    var password by remember { mutableStateOf("") }

    var step by remember { mutableStateOf<SetupStep>(SetupStep.Credentials) }
    var selectedBookIndex by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    fun doCreateAccount() {
        creating = true
        val (href, name) = (step as SetupStep.AddressBook).books[selectedBookIndex]
        scope.launch {
            try {
                val url = normalizeServerUrl(serverUrl)
                val account = am.createAccount(url, username, password, href, name)
                if (account != null) {
                    ContentResolver.setIsSyncable(account, NextcloudAccounts.CONTACTS_AUTHORITY, 1)
                    ContentResolver.setSyncAutomatically(account, NextcloudAccounts.CONTACTS_AUTHORITY, true)
                    ContentResolver.addPeriodicSync(
                        account, NextcloudAccounts.CONTACTS_AUTHORITY,
                        Bundle.EMPTY,
                        NextcloudAccounts.SYNC_INTERVAL_SECONDS
                    )
                    Bundle().also { extras ->
                        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                        ContentResolver.requestSync(account, NextcloudAccounts.CONTACTS_AUTHORITY, extras)
                    }
                } else if (existingAccount != null) {
                    am.updatePassword(existingAccount, password)
                    ContentResolver.requestSync(existingAccount, NextcloudAccounts.CONTACTS_AUTHORITY, Bundle().apply {
                        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    })
                }
                response?.onResult(Bundle().apply {
                    putString(AccountManager.KEY_ACCOUNT_NAME, account?.name ?: existingAccount?.name)
                    putString(AccountManager.KEY_ACCOUNT_TYPE, NextcloudAccounts.ACCOUNT_TYPE)
                    putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true)
                })
                step = SetupStep.Done
                onFinished()
            } catch (e: Exception) {
                creating = false
                errorMessage = s.accountSetupFailed(e.message ?: "")
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = result.values.all { it }
        if (allGranted && step is SetupStep.AddressBook) {
            doCreateAccount()
        } else if (!allGranted) {
            creating = false
            errorMessage = "Contacts permission is required to sync"
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (step is SetupStep.Credentials) {
                Text(
                    s.accountSetupTitle,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    s.accountSetupSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                if (isUpdate) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = {},
                        label = { Text(s.serverUrlLabel) },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = {},
                        label = { Text(s.usernameLabel) },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        singleLine = true
                    )
                } else {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text(s.serverUrlLabel) },
                        placeholder = { Text("https://your-nextcloud.com") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(s.usernameLabel) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(s.passwordLabel) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    singleLine = true
                )

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val url = normalizeServerUrl(serverUrl)
                        if (url.isEmpty() || username.isEmpty() || password.isEmpty()) {
                            errorMessage = "Please fill all fields"
                            return@Button
                        }
                        if (!url.startsWith("https://", ignoreCase = true)) {
                            errorMessage = "HTTPS is required"
                            return@Button
                        }
                        verifying = true
                        errorMessage = null
                        scope.launch {
                            val books = withContext(Dispatchers.IO) {
                                try {
                                    val client = CalDavClient(url, username, password)
                                    client.getAddressBooksSync()
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            verifying = false
                            if (books != null) {
                                if (books.isEmpty()) {
                                    errorMessage = s.noAddressBookAvailable
                                } else {
                                    step = SetupStep.AddressBook(books)
                                }
                            } else {
                                errorMessage = s.accountSetupFailed("Connection failed")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = !verifying
                ) {
                    if (verifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(s.connect)
                    }
                }
            }

            if (step is SetupStep.AddressBook) {
                val books = (step as SetupStep.AddressBook).books
                Text(
                    s.selectAddressBook,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Column(Modifier.selectableGroup()) {
                    books.forEachIndexed { i, (_, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = i == selectedBookIndex,
                                    onClick = { selectedBookIndex = i },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = i == selectedBookIndex,
                                onClick = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        val hasRead = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                        val hasWrite = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
                        if (hasRead && hasWrite) {
                            doCreateAccount()
                        } else {
                            permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = !creating
                ) {
                    if (creating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(s.createAccount)
                    }
                }
            }
        }
    }
}

// Quick inline strings helper rather than polluting the shared Strings class further.
private fun stringsFor(language: AppLanguage): AccountSetupStrings = when (language) {
    AppLanguage.EN -> AccountSetupStrings(
        accountSetupTitle = "Add Nextcloud account",
        accountSetupSubtitle = "Sign in to sync your Nextcloud contacts with the phone's Contacts app",
        serverUrlLabel = "Server URL",
        usernameLabel = "Username",
        passwordLabel = "Password",
        connect = "Connect",
        noAddressBookAvailable = "No address book found on this server",
        selectAddressBook = "Select an address book to sync",
        createAccount = "Create account",
        accountSetupFailed = { "Could not verify the connection: $it" },
        accountCreated = "Account created",
    )
    AppLanguage.FR -> AccountSetupStrings(
        accountSetupTitle = "Ajouter un compte Nextcloud",
        accountSetupSubtitle = "Connectez-vous pour synchroniser vos contacts Nextcloud avec l'application Contacts du téléphone",
        serverUrlLabel = "URL du serveur",
        usernameLabel = "Identifiant",
        passwordLabel = "Mot de passe",
        connect = "Connecter",
        noAddressBookAvailable = "Aucun carnet d'adresses trouvé sur ce serveur",
        selectAddressBook = "Choisissez un carnet d'adresses à synchroniser",
        createAccount = "Créer le compte",
        accountSetupFailed = { "Impossible de vérifier la connexion : $it" },
        accountCreated = "Compte créé",
    )
}

private data class AccountSetupStrings(
    val accountSetupTitle: String,
    val accountSetupSubtitle: String,
    val serverUrlLabel: String,
    val usernameLabel: String,
    val passwordLabel: String,
    val connect: String,
    val noAddressBookAvailable: String,
    val selectAddressBook: String,
    val createAccount: String,
    val accountSetupFailed: (String) -> String,
    val accountCreated: String,
)