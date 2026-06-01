package com.claude.buddy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claude.buddy.net.BuddyHttpClient
import com.claude.buddy.ui.theme.LocalBuddyColors
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(onConfigured: (url: String, token: String) -> Unit) {
    val C     = LocalBuddyColors.current
    val scope = rememberCoroutineScope()

    var url      by remember { mutableStateOf("") }
    var status   by remember { mutableStateOf("") }
    var loading  by remember { mutableStateOf(false) }

    fun connect() {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isEmpty()) { status = "Enter the daemon URL"; return }
        loading = true
        status  = "Connecting…"
        scope.launch {
            val client = BuddyHttpClient(trimmed, "")
            val token  = client.fetchAutoToken()
            if (token != null) {
                status  = "Connected ✓"
                loading = false
                onConfigured(trimmed, token)
            } else {
                status  = "Could not connect — check URL and that the daemon is running"
                loading = false
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(C.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Claude Buddy",
                style = MaterialTheme.typography.headlineLarge,
                color = C.coral,
                textAlign = TextAlign.Center,
            )
            Text(
                "Enter your daemon URL\n(Tailscale or local network)",
                style = MaterialTheme.typography.bodyLarge,
                color = C.textSecondary,
                textAlign = TextAlign.Center,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Daemon URL", color = C.textSecondary) },
                placeholder = { Text("https://hostname.ts.net", color = C.textSecondary, fontSize = 12.sp) },
                singleLine = true,
                enabled = !loading,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { connect() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = C.coral,
                    unfocusedBorderColor = C.divider,
                    focusedTextColor     = C.textPrimary,
                    unfocusedTextColor   = C.textPrimary,
                    cursorColor          = C.coral,
                ),
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            )
            Button(
                onClick = ::connect,
                enabled = !loading,
                shape   = RoundedCornerShape(12.dp),
                colors  = ButtonDefaults.buttonColors(containerColor = C.coral),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color    = C.background,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Connect", color = C.background)
                }
            }
            if (status.isNotEmpty()) {
                Text(
                    status,
                    fontSize = 12.sp,
                    color = if (status.startsWith("Could")) C.red else C.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
