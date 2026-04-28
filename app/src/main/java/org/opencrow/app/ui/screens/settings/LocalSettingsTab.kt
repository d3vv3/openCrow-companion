package org.opencrow.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.opencrow.app.OpenCrowApp
import org.opencrow.app.ui.theme.LocalSpacing
import org.unifiedpush.android.connector.UnifiedPush

@Composable
fun LocalSettingsTab(
    heartbeatEnabled: Boolean,
    onHeartbeatEnabledChange: (Boolean) -> Unit,
    heartbeatInterval: String,
    onHeartbeatIntervalChange: (String) -> Unit,
    connectionValid: Boolean?,
    validating: Boolean,
    onValidateConnection: () -> Unit,
    onScanNewQr: () -> Unit,
    onLogout: () -> Unit
) {
    val spacing = LocalSpacing.current
    val intervals = listOf("10", "30", "60")
    val context = LocalContext.current
    val app = context.applicationContext as OpenCrowApp
    val scope = rememberCoroutineScope()

    // Push endpoint status
    var pushEndpoint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        pushEndpoint = app.container.apiClient.getPushEndpoint()
    }
    val distributorName = remember(pushEndpoint) {
        if (pushEndpoint.isNullOrEmpty()) null
        else UnifiedPush.getAckDistributor(context)?.takeIf { it.isNotEmpty() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
        Text("Connection", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Button(
                    onClick = onScanNewQr,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(spacing.sm))
                    Text("Scan New Pairing QR")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Button(
                        onClick = onValidateConnection,
                        enabled = !validating,
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (validating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Validate Connection")
                        }
                    }

                    Button(
                        onClick = onLogout,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(Icons.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(spacing.xs))
                        Text("Logout")
                    }
                }

                connectionValid?.let { valid ->
                    Text(
                        text = if (valid) ":white_check_mark: Connection valid" else ":x: Connection failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (valid) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Text("Heartbeat", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable heartbeat", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = heartbeatEnabled,
                        onCheckedChange = onHeartbeatEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }

                if (heartbeatEnabled) {
                    Text("Interval", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        intervals.forEach { interval ->
                            FilterChip(
                                selected = heartbeatInterval == interval,
                                onClick = { onHeartbeatIntervalChange(interval) },
                                label = { Text("${interval}m") },
                                shape = MaterialTheme.shapes.extraSmall,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                    Text(
                        "When enabled, the app wakes up at set intervals to check tasks, calendar, and notifications.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ── Push Notifications ────────────────────────────────────────────
        Text("Push Notifications", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Icon(
                        imageVector = if (pushEndpoint.isNullOrEmpty()) Icons.Outlined.NotificationsOff
                                      else Icons.Outlined.NotificationsActive,
                        contentDescription = null,
                        tint = if (pushEndpoint.isNullOrEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                               else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = when {
                            !pushEndpoint.isNullOrEmpty() && distributorName != null ->
                                "Active via $distributorName"
                            !pushEndpoint.isNullOrEmpty() ->
                                "Active"
                            else -> "Not registered"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Text(
                    text = "UnifiedPush lets openCrow send instant notifications even when the app is in the background. Requires a distributor app (e.g. ntfy, Gotify UP).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val deviceId = app.container.apiClient.getDeviceId()
                                if (deviceId != null) {
                                    UnifiedPush.register(context, instance = deviceId)
                                }
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(if (pushEndpoint.isNullOrEmpty()) "Register" else "Change Distributor")
                    }

                    if (!pushEndpoint.isNullOrEmpty()) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val deviceId = app.container.apiClient.getDeviceId()
                                    if (deviceId != null) {
                                        UnifiedPush.unregister(context, instance = deviceId)
                                    }
                                    app.container.apiClient.savePushEndpoint("")
                                    pushEndpoint = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text("Unregister")
                        }
                    }
                }
            }
        }
    }
}
