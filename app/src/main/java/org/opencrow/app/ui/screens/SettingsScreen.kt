package org.opencrow.app.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.opencrow.app.OpenCrowApp
import org.opencrow.app.data.remote.dto.*
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
    val scope = rememberCoroutineScope()
    val spacing = LocalSpacing.current

    // Tabs: local + server config tabs matching the webapp
    val localTab = "local"
    val serverTabs = listOf(
        "email", "servers", "channels", "devices", "tools", "skills",
        "mcp", "providers", "soul", "memory", "schedules", "heartbeat"
    )
    val allTabs = listOf(localTab) + serverTabs
    val tabLabels = mapOf(
        "local" to "Local",
        "email" to "Email",
        "servers" to "Servers",
        "channels" to "Channels",
        "devices" to "Devices",
        "tools" to "Tools",
        "skills" to "Skills",
        "mcp" to "MCP",
        "providers" to "Providers",
        "soul" to "Soul",
        "memory" to "Memory",
        "schedules" to "Schedules",
        "heartbeat" to "Heartbeat"
    )

    var activeTab by remember { mutableStateOf(localTab) }
    var config by remember { mutableStateOf<UserConfigDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveStatus by remember { mutableStateOf<String?>(null) }

    // Local settings state
    var heartbeatEnabled by remember { mutableStateOf(false) }
    var heartbeatInterval by remember { mutableStateOf("30") } // minutes: 10, 30, 60
    var connectionValid by remember { mutableStateOf<Boolean?>(null) }
    var validating by remember { mutableStateOf(false) }

    // Load config
    LaunchedEffect(Unit) {
        try {
            val resp = app.apiClient.api.getConfig()
            if (resp.isSuccessful) {
                config = resp.body()
            }
            // Load local heartbeat settings
            heartbeatEnabled = app.database.configDao().get("heartbeat_enabled") == "true"
            heartbeatInterval = app.database.configDao().get("heartbeat_interval") ?: "30"
        } catch (e: Exception) {
            error = "Failed to load config: ${e.message}"
        }
        loading = false
    }

    fun saveConfig() {
        val cfg = config ?: return
        saving = true
        error = null
        scope.launch {
            try {
                val resp = app.apiClient.api.putConfig(cfg)
                if (resp.isSuccessful) {
                    config = resp.body()
                    saveStatus = "Saved"
                } else {
                    error = "Save failed: ${resp.code()}"
                }
            } catch (e: Exception) {
                error = "Save failed: ${e.message}"
            }
            saving = false
        }
    }

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
            // Tab bar
            ScrollableTabRow(
                selectedTabIndex = allTabs.indexOf(activeTab).coerceAtLeast(0),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                edgePadding = spacing.sm,
                divider = {}
            ) {
                allTabs.forEach { tab ->
                    Tab(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        text = {
                            Text(
                                tabLabels[tab] ?: tab,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (activeTab == tab) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

            // Error banner
            error?.let {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        IconButton(onClick = { error = null }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Content
            if (loading) {
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
                    when (activeTab) {
                        localTab -> LocalSettingsTab(
                            heartbeatEnabled = heartbeatEnabled,
                            onHeartbeatEnabledChange = { enabled ->
                                heartbeatEnabled = enabled
                                scope.launch {
                                    app.database.configDao().set(
                                        org.opencrow.app.data.local.entity.AppConfig("heartbeat_enabled", enabled.toString())
                                    )
                                    if (enabled) {
                                        org.opencrow.app.heartbeat.HeartbeatScheduler.schedule(context, heartbeatInterval.toIntOrNull() ?: 30)
                                    } else {
                                        org.opencrow.app.heartbeat.HeartbeatScheduler.cancel(context)
                                    }
                                }
                            },
                            heartbeatInterval = heartbeatInterval,
                            onHeartbeatIntervalChange = { interval ->
                                heartbeatInterval = interval
                                scope.launch {
                                    app.database.configDao().set(
                                        org.opencrow.app.data.local.entity.AppConfig("heartbeat_interval", interval)
                                    )
                                    if (heartbeatEnabled) {
                                        org.opencrow.app.heartbeat.HeartbeatScheduler.schedule(context, interval.toIntOrNull() ?: 30)
                                    }
                                }
                            },
                            connectionValid = connectionValid,
                            validating = validating,
                            onValidateConnection = {
                                validating = true
                                scope.launch {
                                    connectionValid = try {
                                        val health = app.apiClient.api.health()
                                        if (!health.isSuccessful) false
                                        else app.apiClient.api.listConversations().isSuccessful
                                    } catch (_: Exception) {
                                        false
                                    }
                                    validating = false
                                }
                            },
                            onScanNewQr = onUnpaired
                        )
                        else -> ServerConfigTab(
                            tab = activeTab,
                            config = config,
                            onConfigChange = { config = it },
                            onSave = { saveConfig() },
                            saving = saving,
                            saveStatus = saveStatus
                        )
                    }
                }
            }
        }
    }
}

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
        // Connection section
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

        // Heartbeat section
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

@Composable
fun ServerConfigTab(
    tab: String,
    config: UserConfigDto?,
    onConfigChange: (UserConfigDto) -> Unit,
    onSave: () -> Unit,
    saving: Boolean,
    saveStatus: String?
) {
    val spacing = LocalSpacing.current
    val cfg = config ?: return

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        when (tab) {
            "providers" -> ProvidersConfigPanel(cfg, onConfigChange)
            "soul" -> SoulConfigPanel(cfg, onConfigChange)
            "memory" -> MemoryConfigPanel(cfg, onConfigChange)
            "heartbeat" -> HeartbeatConfigPanel(cfg, onConfigChange)
            "email" -> EmailConfigPanel(cfg)
            "channels" -> ChannelsConfigPanel(cfg)
            "devices" -> DevicesConfigPanel(cfg)
            "tools" -> ToolsConfigPanel(cfg)
            "skills" -> SkillsConfigPanel(cfg)
            "mcp" -> McpConfigPanel(cfg)
            "servers" -> ServersConfigPanel(cfg)
            "schedules" -> SchedulesConfigPanel(cfg)
        }

        Spacer(Modifier.height(spacing.md))

        Button(
            onClick = onSave,
            enabled = !saving,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Save")
            }
        }

        saveStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }

        Spacer(Modifier.height(spacing.xxl))
    }
}

// ─── Server Config Panels ───

@Composable
fun ProvidersConfigPanel(cfg: UserConfigDto, onChange: (UserConfigDto) -> Unit) {
    val providers = cfg.llm?.providers.orEmpty()
    Text("LLM Providers", style = MaterialTheme.typography.titleMedium)
    if (providers.isEmpty()) {
        Text("No providers configured.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    providers.forEachIndexed { i, prov ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
            Column(modifier = Modifier.padding(LocalSpacing.current.md)) {
                Text("${prov.name} (${prov.kind})", style = MaterialTheme.typography.titleSmall)
                Text("Model: ${prov.model.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = prov.enabled,
                        onCheckedChange = { enabled ->
                            val updated = providers.toMutableList()
                            updated[i] = prov.copy(enabled = enabled)
                            onChange(cfg.copy(llm = cfg.llm?.copy(providers = updated) ?: LlmDto(updated)))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SoulConfigPanel(cfg: UserConfigDto, onChange: (UserConfigDto) -> Unit) {
    var systemPrompt by remember { mutableStateOf(cfg.prompts?.systemPrompt.orEmpty()) }
    Text("System Prompt", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = systemPrompt,
        onValueChange = {
            systemPrompt = it
            onChange(cfg.copy(prompts = (cfg.prompts ?: PromptsDto(null, null)).copy(systemPrompt = it)))
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
        textStyle = MaterialTheme.typography.bodySmall,
        maxLines = 20
    )
}

@Composable
fun MemoryConfigPanel(cfg: UserConfigDto, onChange: (UserConfigDto) -> Unit) {
    val entries = cfg.memory?.entries.orEmpty()
    Text("Memory Entries", style = MaterialTheme.typography.titleMedium)
    if (entries.isEmpty()) {
        Text("No memory entries.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    entries.forEach { entry ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
            Column(modifier = Modifier.padding(LocalSpacing.current.md)) {
                Text(entry.category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(entry.content, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun HeartbeatConfigPanel(cfg: UserConfigDto, onChange: (UserConfigDto) -> Unit) {
    val hb = cfg.heartbeat
    Text("Server Heartbeat", style = MaterialTheme.typography.titleMedium)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.padding(LocalSpacing.current.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = hb?.enabled == true,
                    onCheckedChange = { enabled ->
                        onChange(cfg.copy(heartbeat = (hb ?: HeartbeatDto(null, null, null, null, null, null)).copy(enabled = enabled)))
                    }
                )
            }
            Text("Interval: ${hb?.intervalSeconds ?: 0}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Model: ${hb?.model.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    var heartbeatPrompt by remember { mutableStateOf(cfg.prompts?.heartbeatPrompt.orEmpty()) }
    Spacer(Modifier.height(LocalSpacing.current.md))
    Text("Heartbeat Prompt", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = heartbeatPrompt,
        onValueChange = {
            heartbeatPrompt = it
            onChange(cfg.copy(prompts = (cfg.prompts ?: PromptsDto(null, null)).copy(heartbeatPrompt = it)))
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
        textStyle = MaterialTheme.typography.bodySmall,
        maxLines = 15
    )
}

// Simplified read-only panels for other tabs
@Composable fun EmailConfigPanel(cfg: UserConfigDto) { ReadOnlyConfigList("Email Accounts", cfg.integrations?.emailAccounts?.map { "${it.label} (${it.address})" }.orEmpty()) }
@Composable fun ChannelsConfigPanel(cfg: UserConfigDto) { ReadOnlyConfigList("Telegram Bots", cfg.integrations?.telegramBots?.map { it.label }.orEmpty()) }
@Composable fun DevicesConfigPanel(cfg: UserConfigDto) { ReadOnlyConfigList("Companion Apps", cfg.integrations?.companionApps?.map { "${it.label ?: it.name} (${if (it.enabled) "enabled" else "disabled"})" }.orEmpty()) }
@Composable fun ToolsConfigPanel(cfg: UserConfigDto) { ReadOnlyConfigList("Tools", cfg.tools?.definitions?.map { it.name }.orEmpty()) }
@Composable fun SkillsConfigPanel(cfg: UserConfigDto) { ReadOnlyConfigList("Skills", cfg.skills?.entries?.map { it.name }.orEmpty()) }
@Composable fun McpConfigPanel(cfg: UserConfigDto) { ReadOnlyConfigList("MCP Servers", cfg.mcp?.servers?.map { "${it.name} (${it.url})" }.orEmpty()) }
@Composable fun ServersConfigPanel(cfg: UserConfigDto) { ReadOnlyConfigList("SSH Servers", cfg.integrations?.sshServers?.map { "${it.name} (${it.host})" }.orEmpty()) }
@Composable fun SchedulesConfigPanel(cfg: UserConfigDto) { ReadOnlyConfigList("Schedules", cfg.schedules?.entries?.map { it.description }.orEmpty()) }

@Composable
fun ReadOnlyConfigList(title: String, items: List<String>) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (items.isEmpty()) {
        Text("None configured.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items.forEach { item ->
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
            Text(
                item,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(LocalSpacing.current.md).fillMaxWidth()
            )
        }
    }
}
