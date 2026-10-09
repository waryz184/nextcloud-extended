package xyz.luna.nextcloudextended.ui.shell

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import xyz.luna.nextcloudextended.LocalExtra
import xyz.luna.nextcloudextended.LocalStrings

/**
 * The official client's white app bar: hamburger (or back arrow inside a folder), the folder or section
 * title, a search action that turns the title into a search field, and the notifications bell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NcTopBar(
    title: String,
    connected: Boolean,
    showBack: Boolean,
    onNavigation: () -> Unit,
    searchEnabled: Boolean,
    searchOpen: Boolean,
    searchText: String,
    onSearchOpen: () -> Unit,
    onSearchTextChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSearchClose: () -> Unit,
    notificationCount: Int,
    notificationsEnabled: Boolean,
    onNotifications: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?
) {
    val s = LocalStrings.current
    val x = LocalExtra.current
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surface,
        scrolledContainerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
        actionIconContentColor = MaterialTheme.colorScheme.onSurface
    )
    TopAppBar(
        title = {
            if (connected && searchOpen) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                TextField(
                    value = searchText,
                    onValueChange = onSearchTextChange,
                    placeholder = { Text(x.searchHint) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            } else {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            if (connected) {
                if (searchOpen) {
                    IconButton(onClick = onSearchClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, s.back) }
                } else if (showBack) {
                    IconButton(onClick = onNavigation) { Icon(Icons.AutoMirrored.Filled.ArrowBack, s.back) }
                } else {
                    IconButton(onClick = onNavigation) { Icon(Icons.Default.Menu, x.drawerOthers) }
                }
            }
        },
        actions = {
            if (connected) {
                if (searchOpen) {
                    if (searchText.isNotEmpty()) IconButton(onClick = { onSearchTextChange("") }) { Icon(Icons.Default.Clear, null) }
                } else {
                    if (searchEnabled) IconButton(onClick = onSearchOpen) { Icon(Icons.Default.Search, x.searchHint) }
                    if (notificationsEnabled) {
                        IconButton(onClick = onNotifications) {
                            BadgedBox(badge = { if (notificationCount > 0) Badge { Text("$notificationCount") } }) {
                                Icon(Icons.Default.Notifications, x.notifications)
                            }
                        }
                    }
                }
            }
        },
        colors = colors,
        scrollBehavior = scrollBehavior
    )
}

