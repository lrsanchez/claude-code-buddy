package com.claude.buddy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.claude.buddy.ui.theme.LocalBuddyColors

@Composable
fun UnsupportedScreen(reason: String) {
    val C = LocalBuddyColors.current
    Box(
        modifier = Modifier.fillMaxSize().background(C.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = "BLE Peripheral Not Supported",
                style = MaterialTheme.typography.headlineLarge,
                color = C.red,
                textAlign = TextAlign.Center,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyLarge,
                color = C.textSecondary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "This device cannot act as a BLE peripheral.\nVerify using nRF Connect → Advertiser tab.",
                style = MaterialTheme.typography.bodyLarge,
                color = C.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
