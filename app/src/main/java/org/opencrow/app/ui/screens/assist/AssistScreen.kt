package org.opencrow.app.ui.screens.assist

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.opencrow.app.OpenCrowApp
import org.opencrow.app.ui.screens.chat.components.MessageBubble
import org.opencrow.app.ui.screens.chat.components.ThinkingBubble
import org.opencrow.app.ui.theme.LocalSpacing
import java.io.File

@Composable
fun AssistScreen(
    screenshotPath: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCrowApp
    val viewModel: AssistViewModel = viewModel(
        factory = AssistViewModel.Factory(app.container.conversationRepository, app.container.configRepository, app)
    )
    val state by viewModel.uiState.collectAsState()
    val spacing = LocalSpacing.current
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // Pass screenshot path to viewmodel once
    LaunchedEffect(screenshotPath) {
        viewModel.setScreenshotPath(screenshotPath)
    }

    // Auto-scroll on new messages
    val messageCount = state.messages.size
    LaunchedEffect(messageCount, state.sending) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(messageCount - 1)
        }
    }

    // Scroll during streaming
    val lastMessageLength = state.messages.lastOrNull()?.content?.length ?: 0
    val streamingScrollThreshold = remember { mutableIntStateOf(0) }
    LaunchedEffect(state.streaming, lastMessageLength) {
        if (state.streaming && lastMessageLength - streamingScrollThreshold.intValue > 50) {
            streamingScrollThreshold.intValue = lastMessageLength
            if (state.messages.isNotEmpty()) {
                listState.animateScrollToItem(state.messages.size - 1)
            }
        }
        if (!state.streaming) streamingScrollThreshold.intValue = 0
    }

    // Auto-focus the text field to open keyboard
    LaunchedEffect(state.apiReady) {
        if (state.apiReady) {
            focusRequester.requestFocus()
        }
    }

    // Scrim background — tap to dismiss
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onDismiss() }
            .imePadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Chat panel that grows organically from the bottom
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* consume clicks so they don't dismiss */ },
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.navigationBarsPadding()
            ) {
                // Handle bar + close button
                AssistHeader(onDismiss = onDismiss)

                if (!state.apiReady) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.error ?: "Connecting...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Messages list
                    if (state.messages.isNotEmpty()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .weight(1f, fill = false)
                                .padding(horizontal = spacing.md),
                            contentPadding = PaddingValues(vertical = spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            items(state.messages, key = { it.id }) { msg ->
                                MessageBubble(message = msg)
                            }
                            if (state.sending) {
                                item { ThinkingBubble() }
                            }
                        }
                    }

                    // Error message
                    if (state.error != null && state.messages.isNotEmpty()) {
                        Text(
                            text = state.error!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = spacing.md)
                        )
                    }

                    // Screenshot preview when attached
                    if (state.attachScreenshot && state.screenshotPath != null) {
                        ScreenshotPreview(path = state.screenshotPath!!)
                    }

                    // Input bar
                    AssistInputBar(
                        composing = state.composing,
                        onComposingChange = viewModel::updateComposing,
                        sending = state.sending || state.streaming,
                        onSend = { viewModel.sendMessage() },
                        focusRequester = focusRequester,
                        screenshotAvailable = state.screenshotAvailable,
                        attachScreenshot = state.attachScreenshot,
                        onToggleScreenshot = viewModel::toggleAttachScreenshot
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenshotPreview(path: String) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = spacing.xs)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(path))
                .crossfade(true)
                .build(),
            contentDescription = "Screenshot preview",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 120.dp)
                .clip(RoundedCornerShape(spacing.sm))
        )
    }
}

@Composable
private fun AssistHeader(onDismiss: () -> Unit) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .padding(top = spacing.sm)
                .width(32.dp)
                .height(4.dp)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    RoundedCornerShape(2.dp)
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "openCrow",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = spacing.sm)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AssistInputBar(
    composing: String,
    onComposingChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    focusRequester: FocusRequester,
    screenshotAvailable: Boolean,
    attachScreenshot: Boolean,
    onToggleScreenshot: (Boolean) -> Unit
) {
    val spacing = LocalSpacing.current

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Screenshot toggle row
            if (screenshotAvailable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleScreenshot(!attachScreenshot) }
                        .padding(horizontal = spacing.md, vertical = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = attachScreenshot,
                        onCheckedChange = onToggleScreenshot,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Spacer(Modifier.width(spacing.xs))
                    Icon(
                        Icons.Outlined.Screenshot,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (attachScreenshot) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(spacing.xs))
                    Text(
                        text = "Attach screenshot",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (attachScreenshot) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier
                    .padding(horizontal = spacing.sm, vertical = spacing.sm),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                TextField(
                    value = composing,
                    onValueChange = onComposingChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text(
                            "Ask openCrow...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.small
                )

                FilledIconButton(
                    onClick = onSend,
                    enabled = composing.isNotBlank() && !sending,
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
