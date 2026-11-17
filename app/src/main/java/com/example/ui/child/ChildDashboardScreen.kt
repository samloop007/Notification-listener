package com.example.ui.child

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.MonitoredApp
import com.example.oem.BatteryOptimizationHelper
import com.example.oem.OemAutostartHelper
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StatusHealthy
import com.example.ui.theme.StatusWarning
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChildDashboardScreen(
    onNavigateToPairing: () -> Unit,
    onNavigateToAutostart: () -> Unit,
    onNavigateToDisclosure: () -> Unit,
    onSwitchRoleOrSignOut: () -> Unit,
    viewModel: ChildViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    var notifPermGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val postNotifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifPermGranted = granted
        if (granted) {
            viewModel.refreshDiagnostics()
        } else {
            viewModel.postMessage("POST_NOTIFICATIONS denied — Watchdog persistent status notification will be hidden.")
        }
    }

    val companionIntentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onCompanionAssociated()
        } else {
            viewModel.postMessage("Companion pairing cancelled. For dev builds without a wearable, use ADB appops.")
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDiagnostics()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    val oemGuidance = remember(context) { OemAutostartHelper.getGuidanceForCurrentDevice(context) }
    val isPaired = uiState.prefs.familyId.isNotBlank()
    val listenerHealthy = uiState.listenerHealth.permissionGranted && uiState.listenerHealth.activeNotificationsOk
    val trustedExemptionActive = uiState.companionStatus.hasCompanionAssociation || uiState.companionStatus.appOpsSensitiveAllowed

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Child Device Mirror") },
                actions = {
                    IconButton(
                        onClick = onSwitchRoleOrSignOut,
                        modifier = Modifier.testTag("child_switch_role_button")
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Switch Role or Sign Out")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Column(modifier = Modifier.widthIn(max = 640.dp)) {
                    // 1. Live Health Summary Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (listenerHealthy && isPaired) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (listenerHealthy) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (listenerHealthy) StatusHealthy else StatusWarning
                                )
                                Text(
                                    text = when {
                                        !isPaired -> "Not Paired to a Family"
                                        !uiState.listenerHealth.permissionGranted -> "Notification Access Disabled"
                                        !uiState.listenerHealth.activeNotificationsOk -> "Listener Stalled — Rebind Needed"
                                        else -> "Mirroring Active & Watchdog Running"
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }

                            Text(
                                text = if (isPaired) {
                                    "Family: ${uiState.prefs.familyName} (${uiState.prefs.familyId.take(12)}...)"
                                } else {
                                    "Pair with a Parent device using a 6-character code to begin syncing."
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )

                            if (uiState.listenerHealth.permissionGranted && !uiState.listenerHealth.activeNotificationsOk) {
                                Button(
                                    onClick = { viewModel.requestRebind() },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("child_rebind_button")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text("Force Listener Rebind")
                                }
                            }
                        }
                    }
                }
            }

            // 2. Family Pairing Card
            item {
                Column(modifier = Modifier.widthIn(max = 640.dp)) {
                    SetupActionCard(
                        icon = Icons.Default.Link,
                        title = if (isPaired) "Family Link Active" else "1. Pair with Parent Device",
                        description = if (isPaired) {
                            "Linked to ${uiState.prefs.familyName}. Captured notifications batch-sync to Firestore."
                        } else {
                            "Enter the 6-character code generated on the Parent device."
                        },
                        statusLabel = if (isPaired) "Paired" else "Required",
                        isComplete = isPaired,
                        buttonLabel = if (isPaired) "Re-pair Device" else "Enter 6-Char Code",
                        testTag = "child_open_pairing_button",
                        onClick = onNavigateToPairing
                    )
                }
            }

            // 3. Notification Listener Permission (gated behind Prominent Disclosure)
            item {
                Column(modifier = Modifier.widthIn(max = 640.dp)) {
                    SetupActionCard(
                        icon = Icons.Default.NotificationsActive,
                        title = "2. Notification Listener Access",
                        description = "Required for NotifListener to read WhatsApp, Messages, Messenger, and Telegram notifications. Prominent disclosure shown before Settings.",
                        statusLabel = if (uiState.listenerHealth.permissionGranted) "Enabled" else "Action Needed",
                        isComplete = uiState.listenerHealth.permissionGranted,
                        buttonLabel = if (uiState.listenerHealth.permissionGranted) "Review Settings" else "Review Disclosure & Enable",
                        testTag = "child_enable_listener_button",
                        onClick = {
                            if (!uiState.prefs.disclosureAccepted) {
                                onNavigateToDisclosure()
                            } else {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        }
                    )
                }
            }

            // 4. POST_NOTIFICATIONS Runtime Permission (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item {
                    Column(modifier = Modifier.widthIn(max = 640.dp)) {
                        SetupActionCard(
                            icon = Icons.Default.NotificationsActive,
                            title = "3. Foreground Service Notification",
                            description = "Android 13+ requires POST_NOTIFICATIONS so WatchdogService can show its persistent specialUse foreground notification.",
                            statusLabel = if (notifPermGranted) "Granted" else "Needed",
                            isComplete = notifPermGranted,
                            buttonLabel = if (notifPermGranted) "Permission Granted" else "Allow Notifications",
                            testTag = "child_post_notif_button",
                            onClick = {
                                if (!notifPermGranted) {
                                    postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                    }
                }
            }

            // 5. Battery Optimization Exemption (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            item {
                Column(modifier = Modifier.widthIn(max = 640.dp)) {
                    SetupActionCard(
                        icon = Icons.Default.BatteryChargingFull,
                        title = "4. Battery Optimization Exemption",
                        description = "Prevents Doze from stalling SyncWorker and WatchdogService.",
                        statusLabel = if (uiState.ignoringBatteryOptimizations) "Unrestricted" else "Optimized",
                        isComplete = uiState.ignoringBatteryOptimizations,
                        buttonLabel = if (uiState.ignoringBatteryOptimizations) "Exemption Active" else "Allow Unrestricted Battery",
                        testTag = "child_battery_opt_button",
                        onClick = {
                            context.startActivity(
                                BatteryOptimizationHelper.createRequestIgnoreBatteryOptimizationIntent(context)
                            )
                        }
                    )
                }
            }

            // 6. Android 15+ CompanionDeviceManager Trusted Listener Exemption
            item {
                Column(modifier = Modifier.widthIn(max = 640.dp)) {
                    val otpWarn = if (uiState.listenerHealth.otpRedactionDetected) {
                        "\nWarning: OS OTP redaction observed! Complete Companion association or run ADB appops."
                    } else ""
                    SetupActionCard(
                        icon = Icons.Default.Shield,
                        title = "5. Android 15+ Trusted Listener (OTP Exemption)",
                        description = "Android 15+ redacts OTP/2FA notification content unless RECEIVE_SENSITIVE_NOTIFICATIONS is backed by a CompanionDeviceManager association (Associations: ${uiState.companionStatus.associationCount}).$otpWarn",
                        statusLabel = if (trustedExemptionActive) "Trusted" else "Untrusted",
                        isComplete = trustedExemptionActive,
                        buttonLabel = "Associate Companion Device",
                        testTag = "child_companion_assoc_button",
                        onClick = {
                            viewModel.companionAssociationManager.startAssociation(
                                onIntentSenderReady = { sender ->
                                    companionIntentSenderLauncher.launch(
                                        IntentSenderRequest.Builder(sender).build()
                                    )
                                },
                                onAssociated = { viewModel.onCompanionAssociated() },
                                onError = { err -> viewModel.postMessage(err) }
                            )
                        }
                    )
                }
            }

            // 7. Manufacturer Autostart Guidance
            item {
                Column(modifier = Modifier.widthIn(max = 640.dp)) {
                    SetupActionCard(
                        icon = Icons.Default.Build,
                        title = "6. ${oemGuidance.manufacturerName} Autostart Guidance",
                        description = "${oemGuidance.osUiName} (${oemGuidance.riskLevel}). Configure OEM power exceptions so the listener survives screen-off.",
                        statusLabel = oemGuidance.osUiName,
                        isComplete = false,
                        buttonLabel = "Open OEM Guide",
                        testTag = "child_oem_guide_button",
                        onClick = onNavigateToAutostart
                    )
                }
            }

            // 8. Monitored Apps & Offline Room Queue Card
            item {
                Column(modifier = Modifier.widthIn(max = 640.dp)) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Monitored Apps & Room Queue", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "${uiState.pendingQueueCount} pending / ${uiState.totalCapturedCount} total",
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                MonitoredApp.entries.forEach { app ->
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(app.displayName) }
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = { viewModel.triggerSyncNow() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("child_sync_now_button")
                            ) {
                                Icon(Icons.Default.CloudUpload, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("Flush Offline Queue Now")
                            }
                        }
                    }
                }
            }

            // Recent local Room captures preview
            items(uiState.recentCaptures.take(5), key = { it.recordId }) { item ->
                Column(modifier = Modifier.widthIn(max = 640.dp)) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${item.appLabel} • ${item.title}",
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = if (item.synced) "Synced" else "Queued (${item.retryCount})",
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (item.synced) StatusHealthy else StatusWarning
                                )
                            }
                            Text(
                                text = item.text.ifBlank { "(empty)" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Updates: ${item.updateCount} • ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.postTimeMs))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SetupActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    statusLabel: String,
    isComplete: Boolean,
    buttonLabel: String,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isComplete) StatusHealthy else MaterialTheme.colorScheme.primary
                    )
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                Surface(
                    color = if (isComplete) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag(testTag)
            ) {
                Text(buttonLabel)
            }
        }
    }
}
