package org.opencrow.app.ui.screens.settings.panels

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.opencrow.app.data.remote.dto.*
import org.opencrow.app.ui.theme.LocalSpacing

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
