package org.opencrow.app.ui.screens.chat.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.opencrow.app.data.remote.dto.ConversationDto
import org.opencrow.app.ui.theme.LocalSpacing
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@Composable
fun HistorySheet(
    visible: Boolean,
    conversations: List<ConversationDto>,
    activeId: String?,
    showSystemChats: Boolean,
    onToggleSystemChats: (Boolean) -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = LocalSpacing.current

    var shouldRender by remember { mutableStateOf(visible) }
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "historySheet",
        finishedListener = { v -> if (v == 0f) shouldRender = false }
    )
    LaunchedEffect(visible) { if (visible) shouldRender = true }

    if (!shouldRender) return

    val drawerWidthPx = with(LocalDensity.current) {
        LocalConfiguration.current.screenWidthDp.dp.toPx() * 0.82f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss)
    ) {
        // Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress * 0.4f }
                .background(MaterialTheme.colorScheme.scrim)
        )

        // Drawer panel
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.82f)
                .align(Alignment.CenterStart)
                .graphicsLayer { translationX = (progress - 1f) * drawerWidthPx }
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { }
                .pointerInput(Unit) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dx -> drag += dx },
                        onDragEnd = {
                            if (drag < -60f) onDismiss()
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f }
                    )
                },
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = spacing.md, vertical = spacing.md)
                ) {
                    Text(
                        "Chats",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                )

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = spacing.sm, end = spacing.sm,
                        top = spacing.sm, bottom = spacing.sm
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(conversations, key = { _, conv -> conv.id }) { _, conv ->
                        SwipeToDeleteRow(onDelete = { onDeleteConversation(conv.id) }) {
                            ConversationRow(
                                conv = conv,
                                isActive = conv.id == activeId,
                                onSelect = {
                                    onSelectConversation(conv.id)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Show system chats",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = showSystemChats,
                        onCheckedChange = onToggleSystemChats,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .clickable {
                            onDismiss()
                            onNavigateToSettings()
                        }
                        .padding(horizontal = spacing.md, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = "Configuration",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                    Text(
                        "Configuration",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Extra safe space below the last row so thumb doesn't reach the edge
                Spacer(Modifier.height(spacing.md))
            }
        }
    }
}

/** Custom swipe-to-delete with haptic, resistance, spring snap-back and animated collapse. */
@Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val heightDp = remember { Animatable(62f) }
    var thresholdPx by remember { mutableFloatStateOf(80f) }
    var rowWidthPx by remember { mutableFloatStateOf(0f) }
    var crossedThreshold by remember { mutableStateOf(false) }

    val errorContainerColor = MaterialTheme.colorScheme.errorContainer
    val onErrorContainerColor = MaterialTheme.colorScheme.onErrorContainer

    val revealFraction = (offsetX.value.absoluteValue / thresholdPx).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.value.dp)
            .pointerInput(Unit) {
                rowWidthPx = size.width.toFloat()
                thresholdPx = size.width * 0.20f
                detectHorizontalDragGestures(
                    onDragStart = { crossedThreshold = false },
                    onHorizontalDrag = { _, dx ->
                        if (heightDp.value < 62f) return@detectHorizontalDragGestures
                        val current = offsetX.value
                        val absVal = current.absoluteValue
                        val next: Float = if (absVal < thresholdPx) {
                            current + dx
                        } else {
                            val excess = absVal - thresholdPx
                            val resistance = 1f / (1f + excess * 0.018f)
                            current + dx * resistance
                        }
                        scope.launch { offsetX.snapTo(next) }

                        val nowAbove = next.absoluteValue >= thresholdPx
                        if (nowAbove && !crossedThreshold) {
                            crossedThreshold = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else if (!nowAbove && crossedThreshold) {
                            crossedThreshold = false
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onDragEnd = {
                        if (offsetX.value.absoluteValue >= thresholdPx) {
                            // Fly the card off-screen, then collapse the row height, then delete
                            scope.launch {
                                val flyDir = if (offsetX.value < 0) -rowWidthPx else rowWidthPx
                                launch { offsetX.animateTo(flyDir, tween(160)) }
                                kotlinx.coroutines.delay(80)
                                heightDp.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                                onDelete()
                            }
                        } else {
                            scope.launch {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                offsetX.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 1400f))
                            }
                        }
                        crossedThreshold = false
                    },
                    onDragCancel = {
                        scope.launch {
                            offsetX.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 1400f))
                        }
                        crossedThreshold = false
                    }
                )
            }
    ) {
        // Delete background -- only rendered when swiping
        if (revealFraction > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = revealFraction }
                    .background(errorContainerColor, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = if (offsetX.value > 0) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = onErrorContainerColor)
            }
        }

        // Card slides on top
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
        ) {
            content()
        }
    }
}

@Composable
private fun ConversationRow(
    conv: ConversationDto,
    isActive: Boolean,
    onSelect: () -> Unit
) {
    val spacing = LocalSpacing.current
    val isDark = isSystemInDarkTheme()
    val isTelegram = conv.channel == "telegram" ||
            conv.title.contains("[telegram]", ignoreCase = true)

    val displayTitle = remember(conv.title) {
        conv.title.replace("[telegram]", "", ignoreCase = true).trim().ifBlank { "Untitled" }
    }
    val relativeTime = remember(conv.updatedAt) { formatRelativeDate(conv.updatedAt) }

    val pillLabel: String? = when {
        isTelegram -> "telegram"
        conv.isAutomatic && conv.automationKind != null -> conv.automationKind
        else -> null
    }

    // Active state colors -- high contrast
    val activeBackground = if (isDark)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    else
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val activeBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.85f else 0.6f)
    val activeTitleColor = if (isDark) Color.White else MaterialTheme.colorScheme.primary
    val activeSubColor = if (isDark) Color.White.copy(alpha = 0.65f)
                         else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)

    Surface(
        onClick = onSelect,
        color = if (isActive) activeBackground else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = if (isActive) 0.dp else 1.dp,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isActive) Modifier.border(1.5.dp, activeBorderColor, RoundedCornerShape(12.dp))
                else Modifier
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.md, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Line 1: title
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                color = if (isActive) activeTitleColor else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            // Line 2: time (left) + pill (right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = relativeTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isActive) activeSubColor
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                )
                if (pillLabel != null) {
                    val resolvedColor = when (pillLabel) {
                        "telegram" -> MaterialTheme.colorScheme.tertiary
                        "scheduled_task" -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.primary
                    }
                    TypePill(pillLabel, resolvedColor)
                }
            }
        }
    }
}

@Composable
private fun TypePill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(50.dp))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

private val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

private fun formatRelativeDate(iso: String): String {
    val date = try { isoParser.parse(iso.take(19)) } catch (_: Exception) { return iso.take(10) }
        ?: return iso.take(10)
    val diffMs = Date().time - date.time
    val diffMin = diffMs / 60_000
    val diffHours = diffMin / 60
    val diffDays = diffHours / 24
    val diffWeeks = diffDays / 7

    return when {
        diffMin < 1 -> "just now"
        diffMin < 60 -> "${diffMin}m ago"
        diffHours < 24 -> "${diffHours}h ago"
        diffDays == 1L -> "yesterday"
        diffDays < 7 -> "${diffDays} days ago"
        diffWeeks == 1L -> "last week"
        diffWeeks == 2L -> "2 weeks ago"
        diffWeeks == 3L -> "3 weeks ago"
        diffDays < 45 -> "last month"
        else -> formatAbsoluteDate(date)
    }
}

private fun formatAbsoluteDate(date: Date): String {
    val cal = Calendar.getInstance().apply { time = date }
    val day = cal.get(Calendar.DAY_OF_MONTH)
    val month = SimpleDateFormat("MMMM", Locale.getDefault()).format(date)
    val year = cal.get(Calendar.YEAR)
    val suffix = when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    return if (year == currentYear) "$day$suffix $month" else "$day$suffix $month $year"
}
