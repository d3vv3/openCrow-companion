package org.opencrow.app.ui.screens.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.opencrow.app.data.remote.dto.ToolCallDto
import org.opencrow.app.ui.theme.LocalSpacing

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolCallBubble(toolCalls: List<ToolCallDto>) {
    val spacing = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        // Dot indicator aligned with assistant messages
        Box(
            modifier = Modifier
                .padding(top = 10.dp, end = spacing.sm)
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f))
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val text = toolCalls.joinToString("\n\n") { call ->
                            buildString {
                                append("[${call.status}] ${call.name}")
                                call.arguments?.let { args ->
                                    append("\n  args: ${args.entries.joinToString(", ") { (k, v) -> "$k: $v" }}")
                                }
                                call.output?.let { append("\n  output: $it") }
                            }
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("tool_calls", text))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                )
        ) {
            Column(
                modifier = Modifier
                    .animateContentSize()
                    .padding(10.dp)
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Filled.Build,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        "${toolCalls.size} tool call${if (toolCalls.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Collapsed: show tool names inline
                if (!expanded) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        toolCalls.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Expanded: show each tool call with details
                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    toolCalls.forEachIndexed { index, call ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 6.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                        ToolCallItem(call)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCallItem(call: ToolCallDto) {
    val isSuccess = call.status == "success" || call.status == "ok"
    val isRunning = call.status == "running"
    val statusIcon = when {
        isRunning -> Icons.Filled.Build
        isSuccess -> Icons.Filled.CheckCircle
        else -> Icons.Filled.Error
    }
    val statusColor = when {
        isRunning -> MaterialTheme.colorScheme.onSurfaceVariant
        isSuccess -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // Tool name + status
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                statusIcon,
                contentDescription = call.status,
                modifier = Modifier.size(12.dp),
                tint = statusColor
            )
            Text(
                call.name,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Arguments (compact)
        if (!call.arguments.isNullOrEmpty()) {
            val argsText = call.arguments.entries.joinToString(", ") { (k, v) ->
                "$k: ${formatArgValue(v)}"
            }
            Text(
                argsText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Output (truncated)
        if (!call.output.isNullOrBlank()) {
            val truncated = if (call.output.length > 200) {
                call.output.take(200) + "…"
            } else call.output
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    truncated,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatArgValue(value: Any?): String = when (value) {
    is String -> "\"${value.take(50)}${if (value.length > 50) "…" else ""}\""
    is Map<*, *> -> "{…}"
    is List<*> -> "[${value.size}]"
    null -> "null"
    else -> value.toString().take(30)
}
