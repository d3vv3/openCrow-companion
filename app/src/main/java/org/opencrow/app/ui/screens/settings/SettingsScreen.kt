package org.opencrow.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import org.opencrow.app.OpenCrowApp
import org.opencrow.app.ui.theme.LocalSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRepaired: () -> Unit,
    onUnpaired: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCrowApp
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(app.container.configRepository)
    )
    val state by viewModel.uiState.collectAsState()
    val spacing = LocalSpacing.current

    val localTab = "local"
    val serverTabs = listOf(
        "email", "servers", "channels", "devices", "tools", "skills",
        "mcp", "providers", "soul", "memory", "schedules", "heartbeat"
    )
    val allTabs = listOf(localTab) + serverTabs
    val tabLabels = mapOf(
        "local" to "Local", "email" to "Email", "servers" to "Servers",
        "channels" to "Channels", "devices" to "Devices", "tools" to "Tools",
        "skills" to "Skills", "mcp" to "MCP", "providers" to "Providers",
        "soul" to "Soul", "memory" to "Memory", "schedules" to "Schedules",
        "heartbeat" to "Heartbeat"
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = allTabs.indexOf(state.activeTab).coerceAtLeast(0),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                edgePadding = spacing.sm,
                divider = {}
            ) {
                allTabs.forEach { tab ->
                    Tab(
                        selected = state.activeTab == tab,
                        onClick = { viewModel.setActiveTab(tab) },
                        text = {
                            Text(
                                tabLabels[tab] ?: tab,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.activeTab == tab) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

            state.error?.let {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.dismissError() }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(spacing.md)
                ) {
                    when (state.activeTab) {
                        localTab -> LocalSettingsTab(
                            heartbeatEnabled = state.heartbeatEnabled,
                            onHeartbeatEnabledChange = { viewModel.setHeartbeatEnabled(it, context) },
                            heartbeatInterval = state.heartbeatInterval,
                            onHeartbeatIntervalChange = { viewModel.setHeartbeatInterval(it, context) },
                            connectionValid = state.connectionValid,
                            validating = state.validating,
                            onValidateConnection = { viewModel.validateConnection() },
                            onScanNewQr = onUnpaired
                        )
                        else -> ServerConfigTab(
                            tab = state.activeTab,
                            config = state.config,
                            onConfigChange = viewModel::updateConfig,
                            onSave = { viewModel.saveConfig() },
                            saving = state.saving,
                            saveStatus = state.saveStatus
                        )
                    }
                }
            }
        }
    }
}
