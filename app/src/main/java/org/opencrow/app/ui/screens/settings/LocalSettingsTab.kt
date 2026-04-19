package org.opencrow.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.opencrow.app.ui.theme.LocalSpacing

@Composable
fun LocalSettingsTab(
    heartbeatEnabled: Boolean,
    onHeartbeatEnabledChange: (Boolean) -> Unit,
    heartbeatInterval: String,
    onHeartbeatIntervalChange: (String) -> Unit,
    connectionValid: Boolean?,
    validating: Boolean,
    onValidateConnection: () -> Unit,
    onScanNewQr: () -> Unit
) {
    val spacing = LocalSpacing.current
    val intervals = listOf("10", "30", "60")

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

                Button(
                    onClick = onValidateConnection,
                    enabled = !validating,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (validating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Validate Connection")
                    }
                }

                connectionValid?.let { valid ->
                    Text(
                        text = if (valid) "✓ Connection valid" else "✗ Connection failed",
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
    }
}
