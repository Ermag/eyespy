package com.eyespy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.eyespy.app.Api
import com.eyespy.app.Prefs
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(prefs: Prefs, onDone: () -> Unit) {
    var role by remember { mutableStateOf(prefs.role ?: "viewer") }
    var url by remember { mutableStateOf(prefs.serverUrl.ifEmpty { "http://192.168.1.10:8080" }) }
    var token by remember { mutableStateOf(prefs.token) }
    var name by remember { mutableStateOf(prefs.deviceName) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { Bar("eyeSpy setup") }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("What is this phone?") {
                RoleOption("Camera", "Stays at home, records and detects motion.", role == "camera") { role = "camera" }
                RoleOption("Viewer", "Your phone: notifications, footage, live view, home/away presence.", role == "viewer") { role = "viewer" }
            }
            Section("Server") {
                OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("API token (EYESPY_TOKEN)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                if (role == "viewer") OutlinedTextField(name, { name = it }, label = { Text("Your name (for presence)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Connect while on your home Wi-Fi or VPN.", style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !busy && url.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val cfg = Api(url.trim().trimEnd('/'), token.trim()).obj("/api/client-config")
                            prefs.serverUrl = url; prefs.token = token
                            if (name.isNotBlank()) prefs.deviceName = name
                            prefs.ntfyServer = cfg.optString("ntfy_server", "https://ntfy.sh")
                            prefs.ntfyTopic = cfg.optString("ntfy_topic")
                            prefs.presenceTopic = cfg.optString("presence_topic")
                            prefs.ntfyToken = cfg.optString("ntfy_token")
                            prefs.role = role
                            onDone()
                        } catch (e: Exception) {
                            error = "Could not connect: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Connecting…" else "Connect") }
        }
    }
}

@Composable
private fun RoleOption(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}
