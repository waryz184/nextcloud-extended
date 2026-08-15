package xyz.luna.nextcloudextended.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import xyz.luna.nextcloudextended.AppLanguage
import xyz.luna.nextcloudextended.LocalStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    serverUrl: String,
    username: String,
    password: String,
    isLoading: Boolean,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Language selector
        val languages = listOf(AppLanguage.EN to "English", AppLanguage.FR to "Français")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(bottom = 24.dp)) {
            languages.forEachIndexed { i, (lang, label) ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = languages.size),
                    onClick = { onLanguageChange(lang) },
                    selected = language == lang,
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        Text(
            "Nextcloud Extended",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            s.loginSubtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        OutlinedTextField(
            value = serverUrl, onValueChange = onServerUrlChange,
            label = { Text(s.serverUrlLabel) },
            placeholder = { Text(s.serverUrlPlaceholder) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            singleLine = true
        )
        OutlinedTextField(
            value = username, onValueChange = onUsernameChange,
            label = { Text(s.usernameLabel) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            singleLine = true
        )
        OutlinedTextField(
            value = password, onValueChange = onPasswordChange,
            label = { Text(s.passwordLabel) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            singleLine = true
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(s.connect, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
