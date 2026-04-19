package org.opencrow.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.opencrow.app.data.remote.dto.UserConfigDto
import org.opencrow.app.ui.screens.settings.panels.*
import org.opencrow.app.ui.theme.LocalSpacing

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
