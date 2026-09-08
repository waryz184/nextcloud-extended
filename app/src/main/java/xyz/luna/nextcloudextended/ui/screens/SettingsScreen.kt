package xyz.luna.nextcloudextended.ui.screens

import android.content.ContentResolver
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import xyz.luna.nextcloudextended.HubTab
import xyz.luna.nextcloudextended.LocalStrings
import xyz.luna.nextcloudextended.OfficeViewerType
import xyz.luna.nextcloudextended.account.NextcloudAccountManager
import xyz.luna.nextcloudextended.account.NextcloudAccounts
import xyz.luna.nextcloudextended.account.AccountProfile
import xyz.luna.nextcloudextended.icon
import xyz.luna.nextcloudextended.label

private const val MIN_PINNED_TABS = 1
private const val MAX_PINNED_TABS = 4

private enum class SettingsCategory { ACCOUNTS, OFFICE_VIEWER, NAVIGATION_BAR, CONTACTS_SYNC, AUTO_UPLOAD }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    officeViewerPref: OfficeViewerType,
    onOfficeViewerPrefChange: (OfficeViewerType) -> Unit,
    pinnedTabs: List<HubTab>,
    onPinnedTabsChange: (List<HubTab>) -> Unit,
    appLockEnabled: Boolean,
    onAppLockChange: (Boolean) -> Unit,
    mediaAutoUploadEnabled: Boolean,
    onMediaAutoUploadChange: (Boolean) -> Unit,
    mediaWifiOnly: Boolean = false,
    onMediaWifiOnlyChange: (Boolean) -> Unit = {},
    mediaChargingOnly: Boolean = false,
    onMediaChargingOnlyChange: (Boolean) -> Unit = {},
    mediaSubfolder: String = "InstantUpload",
    onMediaSubfolderChange: (String) -> Unit = {},
    accounts: List<AccountProfile>,
    activeAccountId: String?,
    onAccountSelected: (AccountProfile) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    var category by remember { mutableStateOf<SettingsCategory?>(null) }

    BackHandler(enabled = category != null) { category = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (category) {
                            null -> s.settings
                            SettingsCategory.ACCOUNTS -> "Accounts"
                            SettingsCategory.AUTO_UPLOAD -> "Automatic media upload"
                            SettingsCategory.OFFICE_VIEWER -> s.officeViewerSection
                            SettingsCategory.NAVIGATION_BAR -> s.navBarSection
                            SettingsCategory.CONTACTS_SYNC -> s.contactsSyncSection
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (category != null) category = null else onDismiss() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, s.back)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        when (category) {
            null -> SettingsRoot(
                modifier = Modifier.padding(padding),
                officeViewerPref = officeViewerPref,
                onCategoryClick = { category = it },
                appLockEnabled = appLockEnabled,
                onAppLockChange = onAppLockChange,
                mediaAutoUploadEnabled = mediaAutoUploadEnabled,
                mediaSubfolder = mediaSubfolder,
                accounts = accounts
            )
            SettingsCategory.ACCOUNTS -> AccountsSettings(
                modifier = Modifier.padding(padding),
                accounts = accounts,
                activeAccountId = activeAccountId,
                onAccountSelected = onAccountSelected
            )
            SettingsCategory.AUTO_UPLOAD -> AutoUploadSettings(
                modifier = Modifier.padding(padding),
                mediaAutoUploadEnabled = mediaAutoUploadEnabled,
                onMediaAutoUploadChange = onMediaAutoUploadChange,
                mediaWifiOnly = mediaWifiOnly,
                onMediaWifiOnlyChange = onMediaWifiOnlyChange,
                mediaChargingOnly = mediaChargingOnly,
                onMediaChargingOnlyChange = onMediaChargingOnlyChange,
                mediaSubfolder = mediaSubfolder,
                onMediaSubfolderChange = onMediaSubfolderChange
            )
            SettingsCategory.OFFICE_VIEWER -> OfficeViewerSettings(
                modifier = Modifier.padding(padding),
                officeViewerPref = officeViewerPref,
                onOfficeViewerPrefChange = onOfficeViewerPrefChange
            )
            SettingsCategory.NAVIGATION_BAR -> NavigationBarSettings(
                modifier = Modifier.padding(padding),
                pinnedTabs = pinnedTabs,
                onPinnedTabsChange = onPinnedTabsChange
            )
            SettingsCategory.CONTACTS_SYNC -> ContactsSyncSettings(
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun SettingsRoot(
    modifier: Modifier = Modifier,
    officeViewerPref: OfficeViewerType,
    onCategoryClick: (SettingsCategory) -> Unit,
    appLockEnabled: Boolean,
    onAppLockChange: (Boolean) -> Unit,
    mediaAutoUploadEnabled: Boolean,
    mediaSubfolder: String,
    accounts: List<AccountProfile>
) {
    val s = LocalStrings.current
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategoryRow(
            title = "Accounts",
            subtitle = if (accounts.size == 1) accounts.first().label else "${accounts.size} configured accounts",
            onClick = { onCategoryClick(SettingsCategory.ACCOUNTS) }
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SettingsCategoryRow(
            title = "Automatic media upload",
            subtitle = if (mediaAutoUploadEnabled) "Active · Destination: /$mediaSubfolder" else "Disabled",
            onClick = { onCategoryClick(SettingsCategory.AUTO_UPLOAD) }
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SettingsCategoryRow(
            title = s.officeViewerSection,
            subtitle = if (officeViewerPref == OfficeViewerType.POI) s.officeViewerPoi else s.officeViewerOnline,
            onClick = { onCategoryClick(SettingsCategory.OFFICE_VIEWER) }
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("App lock", style = MaterialTheme.typography.bodyLarge)
                Text("Require biometrics or the device lock to open the app", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = appLockEnabled, onCheckedChange = onAppLockChange)
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SettingsCategoryRow(
            title = s.navBarSection,
            subtitle = s.navBarSectionDesc,
            onClick = { onCategoryClick(SettingsCategory.NAVIGATION_BAR) }
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SettingsCategoryRow(
            title = s.contactsSyncSection,
            subtitle = s.contactsSyncSectionDesc,
            onClick = { onCategoryClick(SettingsCategory.CONTACTS_SYNC) }
        )
    }
}

@Composable
private fun AccountsSettings(
    modifier: Modifier,
    accounts: List<AccountProfile>,
    activeAccountId: String?,
    onAccountSelected: (AccountProfile) -> Unit
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (accounts.isEmpty()) {
            Text("No saved accounts", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            accounts.forEach { account ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onAccountSelected(account) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = account.id == activeAccountId, onClick = { onAccountSelected(account) })
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(account.label, style = MaterialTheme.typography.bodyLarge)
                        Text(account.serverUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoUploadSettings(
    modifier: Modifier,
    mediaAutoUploadEnabled: Boolean,
    onMediaAutoUploadChange: (Boolean) -> Unit,
    mediaWifiOnly: Boolean,
    onMediaWifiOnlyChange: (Boolean) -> Unit,
    mediaChargingOnly: Boolean,
    onMediaChargingOnlyChange: (Boolean) -> Unit,
    mediaSubfolder: String,
    onMediaSubfolderChange: (String) -> Unit
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Automatic media upload", style = MaterialTheme.typography.bodyLarge)
                Text("Scan and upload new photos and videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = mediaAutoUploadEnabled, onCheckedChange = onMediaAutoUploadChange)
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Upload on Wi-Fi only", style = MaterialTheme.typography.bodyLarge)
                Text("Pause uploads when using mobile data", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = mediaWifiOnly, onCheckedChange = onMediaWifiOnlyChange, enabled = mediaAutoUploadEnabled)
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Only while charging", style = MaterialTheme.typography.bodyLarge)
                Text("Conserve battery by uploading only when plugged in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = mediaChargingOnly, onCheckedChange = onMediaChargingOnlyChange, enabled = mediaAutoUploadEnabled)
        }
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Destination folder", style = MaterialTheme.typography.bodyLarge)
            Text("Subfolder in your Nextcloud Files root", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = mediaSubfolder,
                onValueChange = onMediaSubfolderChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = mediaAutoUploadEnabled
            )
        }
    }
}

@Composable
private fun SettingsCategoryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OfficeViewerSettings(
    modifier: Modifier = Modifier,
    officeViewerPref: OfficeViewerType,
    onOfficeViewerPrefChange: (OfficeViewerType) -> Unit
) {
    val s = LocalStrings.current
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            s.officeViewerSectionDesc, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
        ViewerOption(
            selected = officeViewerPref == OfficeViewerType.POI,
            title = s.officeViewerPoi,
            description = s.officeViewerPoiDesc,
            onClick = { onOfficeViewerPrefChange(OfficeViewerType.POI) }
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        ViewerOption(
            selected = officeViewerPref == OfficeViewerType.ONLINE,
            title = s.officeViewerOnline,
            description = s.officeViewerOnlineDesc,
            onClick = { onOfficeViewerPrefChange(OfficeViewerType.ONLINE) }
        )
    }
}

@Composable
private fun ViewerOption(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun NavigationBarSettings(
    modifier: Modifier = Modifier,
    pinnedTabs: List<HubTab>,
    onPinnedTabsChange: (List<HubTab>) -> Unit
) {
    val s = LocalStrings.current
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            s.navBarHint, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
        HubTab.entries.forEach { tab ->
            val isPinned = tab in pinnedTabs
            val canToggle = if (isPinned) pinnedTabs.size > MIN_PINNED_TABS else pinnedTabs.size < MAX_PINNED_TABS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (canToggle) 1f else 0.5f)
                    .clickable(enabled = canToggle) {
                        val updated = if (isPinned) pinnedTabs - tab else pinnedTabs + tab
                        onPinnedTabsChange(HubTab.entries.filter { it in updated })
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(tab.icon(), null)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(tab.label(s), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (isPinned) s.navBarShownInBar else s.navBarInMoreMenu,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Checkbox(checked = isPinned, onCheckedChange = null, enabled = canToggle)
            }
        }
    }
}

@Composable
private fun ContactsSyncSettings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val am = remember { NextcloudAccountManager(context) }
    var hasAccount by remember { mutableStateOf(am.firstAccount() != null) }

    // Refresh on every composition
    LaunchedEffect(Unit) {
        hasAccount = am.firstAccount() != null
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            s.contactsSyncSectionDesc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )

        if (hasAccount) {
            val account = am.firstAccount()!!
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.syncAccountStatus, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        account.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Sync now button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val a = am.firstAccount() ?: return@clickable
                        val bundle = Bundle().apply {
                            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                        }
                        ContentResolver.requestSync(a, NextcloudAccounts.CONTACTS_AUTHORITY, bundle)
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(s.syncNow, style = MaterialTheme.typography.bodyLarge)
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Remove account
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        am.firstAccount()?.let { am.removeAccount(it) }
                        hasAccount = false
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.PersonOff, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Text(
                    s.removeSystemAccount,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }
        } else {
            // Show "Add system account" button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(context, xyz.luna.nextcloudextended.sync.AccountSetupActivity::class.java)
                        context.startActivity(intent)
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(s.addSystemAccount, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
