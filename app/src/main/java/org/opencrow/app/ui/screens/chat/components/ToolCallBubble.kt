package org.opencrow.app.ui.screens.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import org.opencrow.app.data.remote.dto.ToolCallDto
import org.opencrow.app.ui.theme.LocalSpacing

/** Renders each tool call as its own card with a glowing status dot. */
@Composable
fun ToolCallBubble(toolCalls: List<ToolCallDto>) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        toolCalls.forEach { call ->
            ToolCallCard(call)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolCallCard(call: ToolCallDto) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val isSuccess = call.status == "success" || call.status == "ok"
    val isError = call.status == "error" || call.status == "failed"

    val dotColor = when {
        isSuccess -> Color(0xFF4CAF50)
        isError -> Color(0xFFE53935)
        else -> Color(0xFF2196F3)
    }

    val hasContent = !call.output.isNullOrBlank() || !call.arguments.isNullOrEmpty()
    var expanded by remember(call.name, call.status) { mutableStateOf(false) }

    val titleText = remember(call.name) { formatToolName(call.name) }

    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(tween(200, easing = FastOutSlowInEasing))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                onClick = { if (hasContent) expanded = !expanded },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val text = "[${call.status}] ${call.name}\n${call.output.orEmpty()}"
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("tool_call", text))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.md, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GlowDot(color = dotColor)
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (hasContent && expanded) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Collapse",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expanded code block
            if (expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                        .background(Color(0xFF0D0D0D), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    val codeText = remember(call) { buildCodeBlock(call) }
                    Text(
                        text = codeText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        ),
                        color = Color(0xFFD4D4D4)
                    )
                }
            }
        }
    }
}

/** Three concentric circles to produce a glow effect around the dot. */
@Composable
private fun GlowDot(color: Color, dotSize: Dp = 12.dp) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(dotSize + 10.dp)
                .background(color.copy(alpha = 0.12f), CircleShape)
        )
        Box(
            Modifier
                .size(dotSize + 4.dp)
                .background(color.copy(alpha = 0.28f), CircleShape)
        )
        Box(
            Modifier
                .size(dotSize)
                .background(color, CircleShape)
        )
    }
}

/**
 * "list_dav_integrations" -> "List dav integrations"
 * Only the very first letter is uppercased; the rest are lowercase.
 */
private fun formatToolName(name: String): String {
    val words = name.replace('_', ' ').lowercase()
    return words.replaceFirstChar { it.uppercaseChar() }
}

private fun buildCodeBlock(call: ToolCallDto): String {
    val sb = StringBuilder()
    // Args line
    sb.appendLine("> ${call.name}")
    if (!call.arguments.isNullOrEmpty()) {
        val argsJson = try {
            JSONObject(call.arguments as Map<*, *>).toString(2)
        } catch (_: Exception) {
            call.arguments.entries.joinToString("\n") { (k, v) -> "  $k: $v" }
        }
        sb.appendLine(argsJson)
    }
    // Output JSON
    if (!call.output.isNullOrBlank()) {
        sb.append(prettyJsonOrRaw(call.output))
    }
    return sb.toString().trimEnd()
}

private fun prettyJsonOrRaw(text: String): String = try {
    JSONObject(text).toString(2)
} catch (_: Exception) {
    try { JSONArray(text).toString(2) } catch (_: Exception) { text }
}
