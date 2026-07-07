package xyz.luna.nextcloudextended.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import xyz.luna.nextcloudextended.HubTab
import xyz.luna.nextcloudextended.LocalStrings
import xyz.luna.nextcloudextended.OfficeViewerType
import xyz.luna.nextcloudextended.icon
import xyz.luna.nextcloudextended.label

private const val MIN_PINNED_TABS = 1
private const val MAX_PINNED_TABS = 4

private enum class SettingsCategory { OFFICE_VIEWER, NAVIGATION_BAR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    officeViewerPref: OfficeViewerType,
    onOfficeViewerPrefChange: (OfficeViewerType) -> Unit,
    pinnedTabs: List<HubTab>,
    onPinnedTabsChange: (List<HubTab>) -> Unit,
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
                            SettingsCategory.OFFICE_VIEWER -> s.officeViewerSection
                            SettingsCategory.NAVIGATION_BAR -> s.navBarSection
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (category != null) category = null else onDismiss() }) {
                        Icon(Icons.Default.ArrowBack, s.back)
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
                onCategoryClick = { category = it }
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
        }
    }
}

@Composable
private fun SettingsRoot(
    modifier: Modifier = Modifier,
    officeViewerPref: OfficeViewerType,
    onCategoryClick: (SettingsCategory) -> Unit
) {
    val s = LocalStrings.current
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsCategoryRow(
            title = s.officeViewerSection,
            subtitle = if (officeViewerPref == OfficeViewerType.POI) s.officeViewerPoi else s.officeViewerOnline,
            onClick = { onCategoryClick(SettingsCategory.OFFICE_VIEWER) }
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        SettingsCategoryRow(
            title = s.navBarSection,
            subtitle = s.navBarSectionDesc,
            onClick = { onCategoryClick(SettingsCategory.NAVIGATION_BAR) }
        )
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
