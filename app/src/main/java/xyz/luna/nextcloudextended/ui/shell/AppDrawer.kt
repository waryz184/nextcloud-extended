package xyz.luna.nextcloudextended.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.luna.nextcloudextended.LocalExtra
import xyz.luna.nextcloudextended.LocalStrings

/** Entries of the navigation drawer, in the order and grouping of the official client. */
enum class DrawerTarget { FILES, FAVORITES, ACTIVITY, CALENDAR, TASKS, NOTES, CONTACTS, TRANSFERS, OFFLINE, TRASH, SETTINGS, LOGOUT }

@Composable
fun AppDrawerContent(
    displayName: String,
    host: String,
    quotaText: String?,
    quotaFraction: Float?,
    selected: DrawerTarget?,
    showActivity: Boolean,
    showTrash: Boolean,
    onSelect: (DrawerTarget) -> Unit
) {
    val s = LocalStrings.current
    val x = LocalExtra.current
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // Header: avatar, name, server and quota
            Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 40.dp, bottom = 16.dp)) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(displayName.trim().firstOrNull()?.uppercase() ?: "?", color = MaterialTheme.colorScheme.onPrimary, fontSize = 28.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(12.dp))
                Text(displayName, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(host, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (quotaText != null) {
                    Spacer(Modifier.height(12.dp))
                    if (quotaFraction != null) {
                        LinearProgressIndicator(progress = { quotaFraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(quotaText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()

            fun item(target: DrawerTarget, icon: ImageVector, label: String) = @Composable {
                NavigationDrawerItem(
                    icon = { Icon(icon, null) },
                    label = { Text(label, fontSize = 15.sp) },
                    selected = selected == target,
                    onClick = { onSelect(target) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = NavigationDrawerItemDefaults.colors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
            Spacer(Modifier.height(8.dp))
            item(DrawerTarget.FILES, Icons.Default.Folder, x.drawerFiles)()
            item(DrawerTarget.FAVORITES, Icons.Default.Star, x.filesFavorites)()
            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 28.dp))
            if (showActivity) item(DrawerTarget.ACTIVITY, Icons.Default.Bolt, x.activity)()
            item(DrawerTarget.CALENDAR, Icons.Default.DateRange, s.tabCalendar)()
            item(DrawerTarget.TASKS, Icons.AutoMirrored.Filled.List, s.tabTasks)()
            item(DrawerTarget.NOTES, Icons.Default.Edit, s.tabNotes)()
            item(DrawerTarget.CONTACTS, Icons.Default.Person, s.tabContacts)()
            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 28.dp))
            item(DrawerTarget.TRANSFERS, Icons.Default.SwapVert, x.transfers)()
            item(DrawerTarget.OFFLINE, Icons.Default.CloudDone, x.availableOffline)()
            if (showTrash) item(DrawerTarget.TRASH, Icons.Default.DeleteSweep, x.trash)()
            HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 28.dp))
            item(DrawerTarget.SETTINGS, Icons.Default.Settings, s.settings)()
            item(DrawerTarget.LOGOUT, Icons.AutoMirrored.Filled.ExitToApp, s.logout)()
            Spacer(Modifier.height(16.dp))
        }
    }
}
