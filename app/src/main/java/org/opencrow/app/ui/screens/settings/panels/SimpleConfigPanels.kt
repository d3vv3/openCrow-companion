package org.opencrow.app.ui.screens.settings.panels

import androidx.compose.runtime.Composable
import org.opencrow.app.data.remote.dto.UserConfigDto

@Composable
fun EmailConfigPanel(cfg: UserConfigDto) {
    ReadOnlyConfigList("Email Accounts", cfg.integrations?.emailAccounts?.map { "${it.label} (${it.address})" }.orEmpty())
}

@Composable
fun ChannelsConfigPanel(cfg: UserConfigDto) {
    ReadOnlyConfigList("Telegram Bots", cfg.integrations?.telegramBots?.map { it.label }.orEmpty())
}

@Composable
fun DevicesConfigPanel(cfg: UserConfigDto) {
    ReadOnlyConfigList("Companion Apps", cfg.integrations?.companionApps?.map { "${it.label ?: it.name} (${if (it.enabled) "enabled" else "disabled"})" }.orEmpty())
}

@Composable
fun ToolsConfigPanel(cfg: UserConfigDto) {
    ReadOnlyConfigList("Tools", cfg.tools?.definitions?.map { it.name }.orEmpty())
}

@Composable
fun SkillsConfigPanel(cfg: UserConfigDto) {
    ReadOnlyConfigList("Skills", cfg.skills?.entries?.map { it.name }.orEmpty())
}

@Composable
fun McpConfigPanel(cfg: UserConfigDto) {
    ReadOnlyConfigList("MCP Servers", cfg.mcp?.servers?.map { "${it.name} (${it.url})" }.orEmpty())
}

@Composable
fun ServersConfigPanel(cfg: UserConfigDto) {
    ReadOnlyConfigList("SSH Servers", cfg.integrations?.sshServers?.map { "${it.name} (${it.host})" }.orEmpty())
}

@Composable
fun SchedulesConfigPanel(cfg: UserConfigDto) {
    ReadOnlyConfigList("Schedules", cfg.schedules?.entries?.map { it.description }.orEmpty())
}
