package com.example.ui.disclosure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.AppRole

/**
 * Requirement 9: Prominent disclosure shown BEFORE opening Settings Notification Listener
 * toggle or enabling the Parent read-only feed, clearly stating notification-only scope.
 */
@Composable
fun ProminentDisclosureScreen(
    role: AppRole,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val isChild = role == AppRole.CHILD

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PrivacyTip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "Prominent Data Disclosure",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                Text(
                    text = if (isChild) {
                        "Before enabling Android Notification Access in System Settings, please review exactly what KinEcho captures on this child device."
                    } else {
                        "Before viewing the Parent Feed, please review the strict notification-only scope of KinEcho."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "What KinEcho Accesses",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        DisclosurePoint(
                            positive = true,
                            text = "Notification titles, body text, and MessagingStyle chat replies posted by WhatsApp, Messages, Messenger, and Telegram."
                        )
                        DisclosurePoint(
                            positive = true,
                            text = "Stored locally in an offline Room queue first, then synced to your private familyId collection in Cloud Firestore."
                        )
                        DisclosurePoint(
                            positive = true,
                            text = "FCM push alerts contain only {type, recordId} metadata — never message content."
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "What KinEcho NEVER Accesses",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        DisclosurePoint(
                            positive = false,
                            text = "No SMS or Call Log APIs (READ_SMS / READ_CALL_LOG are not requested)."
                        )
                        DisclosurePoint(
                            positive = false,
                            text = "No GPS or background location tracking."
                        )
                        DisclosurePoint(
                            positive = false,
                            text = "No remote control, screen capture, or ongoing system notifications."
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onAccept,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("disclosure_accept_button")
                ) {
                    Text(
                        text = if (isChild) {
                            "I Understand — Continue to Setup"
                        } else {
                            "I Understand — Open Parent Feed"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                OutlinedButton(
                    onClick = onDecline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("disclosure_decline_button")
                ) {
                    Text("Decline & Switch Role")
                }
            }
        }
    }
}

@Composable
private fun DisclosurePoint(
    positive: Boolean,
    text: String
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = if (positive) Icons.Default.CheckCircle else Icons.Default.Block,
            contentDescription = null,
            tint = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
