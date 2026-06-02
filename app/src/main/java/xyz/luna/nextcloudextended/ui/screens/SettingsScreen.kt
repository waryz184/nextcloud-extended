package xyz.luna.nextcloudextended.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.luna.nextcloudextended.LocalStrings
import xyz.luna.nextcloudextended.OfficeViewerType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    officeViewerPref: OfficeViewerType,
    onOfficeViewerPrefChange: (OfficeViewerType) -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.settings) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, s.back) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = s.officeViewerSection.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp, end = 16.dp)
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
