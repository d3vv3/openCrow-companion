package org.opencrow.app.ui.screens.chat.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.opencrow.app.data.remote.dto.MessageDto
import org.opencrow.app.ui.components.MarkdownText
import org.opencrow.app.ui.screens.chat.Attachment
import org.opencrow.app.ui.theme.LocalSpacing

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: MessageDto,
    isTranscribed: Boolean = false,
    attachments: List<Attachment> = emptyList(),
    onRegenerate: (() -> Unit)? = null
) {
    val isUser = message.role == "user"
    val isSystem = message.role == "system"
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    if (isUser) {
        val userMessageShape = RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 4.dp,
            bottomStart = 18.dp,
            bottomEnd = 18.dp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = userMessageShape,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(userMessageShape)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("message", message.content))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    )
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (attachments.any { it.isImage }) {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                            for (att in attachments.filter { it.isImage }) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(att.bytes ?: att.uri)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = att.name,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(spacing.sm))
                                )
                            }
                        }
                        if (message.content.isNotBlank()) Spacer(Modifier.height(spacing.sm))
                    }
                    val fileAttachments = attachments.filter { !it.isImage }
                    if (fileAttachments.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                            for (att in fileAttachments) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                                    )
                                    Spacer(Modifier.width(spacing.xs))
                                    Text(
                                        att.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        if (message.content.isNotBlank()) Spacer(Modifier.height(spacing.sm))
                    }
                    if (message.content.isNotBlank()) {
                        Row(verticalAlignment = Alignment.Top) {
                            if (isTranscribed) {
                                Icon(
                                    Icons.Filled.Mic,
                                    contentDescription = "Transcribed",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .padding(end = 4.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                                )
                            }
                            MarkdownText(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    } else {
        // Assistant / system: plain text on background, no bubble
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = spacing.xxl)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("message", message.content))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    )
            ) {
                if (isSystem) {
                    Text(
                        "System",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(spacing.xxs))
                }
                if (message.content.isNotBlank()) {
                    MarkdownText(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                // Regenerate button for assistant messages
                if (!isSystem && onRegenerate != null) {
                    Spacer(Modifier.height(spacing.xxs))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = onRegenerate,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = "Regenerate response",
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}
