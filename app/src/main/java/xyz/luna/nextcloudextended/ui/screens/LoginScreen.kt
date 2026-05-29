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

@Composable
fun LoginScreen(
    serverUrl: String,
    username: String,
    password: String,
    isLoading: Boolean,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Nextcloud Extended",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            "Calendrier, Tâches & Notes",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        OutlinedTextField(
            value = serverUrl, onValueChange = onServerUrlChange,
            label = { Text("URL du serveur") },
            placeholder = { Text("https://votre-nextcloud.com") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            singleLine = true
        )
        OutlinedTextField(
            value = username, onValueChange = onUsernameChange,
            label = { Text("Identifiant") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            singleLine = true
        )
        OutlinedTextField(
            value = password, onValueChange = onPasswordChange,
            label = { Text("Mot de passe") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            singleLine = true
        )
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
                Text("Se connecter", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}
