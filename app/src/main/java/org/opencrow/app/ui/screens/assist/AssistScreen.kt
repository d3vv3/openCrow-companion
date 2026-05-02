package org.opencrow.app.ui.screens.assist

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.opencrow.app.OpenCrowApp
import org.opencrow.app.ui.screens.chat.Attachment
import org.opencrow.app.ui.screens.chat.components.MessageBubble
import org.opencrow.app.ui.screens.chat.components.ThinkingBubble
import org.opencrow.app.ui.theme.LocalSpacing
import java.io.File

@Composable
fun AssistScreen(
    screenshotPath: String? = null,
    externalViewModel: AssistViewModel? = null,
    onDismiss: () -> Unit,
    onOpenFullScreen: (conversationId: String?) -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCrowApp
    val viewModel: AssistViewModel = externalViewModel ?: viewModel(
        factory = AssistViewModel.Factory(
            app.container.conversationRepository,
            app.container.configRepository,
            app
        )
    )
    val state by viewModel.uiState.collectAsState()
    val spacing = LocalSpacing.current
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(screenshotPath) {
        viewModel.setScreenshotPath(screenshotPath)
    }

    val messageCount = state.messages.size
    LaunchedEffect(messageCount, state.sending) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(messageCount - 1)
    }

    val lastMessageLength = state.messages.lastOrNull()?.content?.length ?: 0
    val streamingScrollThreshold = remember { mutableIntStateOf(0) }
    LaunchedEffect(state.streaming, lastMessageLength) {
        if (state.streaming && lastMessageLength - streamingScrollThreshold.intValue > 50) {
            streamingScrollThreshold.intValue = lastMessageLength
            if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.size - 1)
        }
        if (!state.streaming) streamingScrollThreshold.intValue = 0
    }

    // Typing haptic feedback while the assistant is streaming
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(lastMessageLength) {
        if (state.streaming && lastMessageLength > 0 && kotlin.random.Random.nextFloat() > 0.3f) {
            val hapticType = if (kotlin.random.Random.nextFloat() < 0.1f)
                android.view.HapticFeedbackConstants.KEYBOARD_TAP
            else
                android.view.HapticFeedbackConstants.CLOCK_TICK
            view.performHapticFeedback(hapticType)
        }
    }
    var prevStreaming by remember { mutableStateOf(false) }
    LaunchedEffect(state.streaming) {
        if (prevStreaming && !state.streaming) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        }
        prevStreaming = state.streaming
    }

    LaunchedEffect(state.apiReady) {
        if (state.apiReady) focusRequester.requestFocus()
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val infiniteTransition = rememberInfiniteTransition(label = "assist_glow")
    // No `by` — keep State<Float> so .value read stays in drawing phase → no recomposition per frame
    val glowAlphaState = infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

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
        // Ambient glow — graphicsLayer alpha read in drawing phase, no recomposition per frame
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.BottomCenter)
                .graphicsLayer { alpha = glowAlphaState.value }
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, primaryColor)
                    )
                )
        )

        // Screenshot chip (external, just above popup) + popup in a column
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { }
        ) {
            // External screenshot chip — shown only before conversation starts
            AnimatedVisibility(
                visible = state.screenshotAvailable && state.messages.isEmpty(),
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(140))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = spacing.md, bottom = spacing.xxs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val chipSelected = state.attachScreenshot
                    Surface(
                        onClick = { viewModel.toggleAttachScreenshot(!chipSelected) },
                        color = if (chipSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(50.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (chipSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = spacing.md, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            Icon(
                                Icons.Outlined.Screenshot,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (chipSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Attach screenshot",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (chipSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Popup surface
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(animationSpec = tween(200, easing = FastOutSlowInEasing)),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    AssistHeader(
                        onDismiss = onDismiss,
                        onOpenFullScreen = { onOpenFullScreen(state.conversationId) },
                        showControls = state.messages.isNotEmpty(),
                        ttsEnabled = state.ttsEnabled,
                        ttsAvailable = state.ttsAvailable,
                        onToggleTts = { viewModel.toggleTts() }
                    )

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
                        if (state.messages.isNotEmpty()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp)
                                    .weight(1f, fill = false)
                                    .padding(horizontal = spacing.md),
                                contentPadding = PaddingValues(top = spacing.sm, bottom = spacing.xl),
                                verticalArrangement = Arrangement.spacedBy(spacing.lg)
                            ) {
                                val attachmentsMap = state.attachmentsByMessageId
                                items(state.messages, key = { it.id }) { msg ->
                                    MessageBubble(
                                        message = msg,
                                        attachments = attachmentsMap[msg.id].orEmpty()
                                    )
                                }
                                if (state.sending || (state.streaming && state.messages.lastOrNull()?.role == "assistant" && state.messages.lastOrNull()?.content?.isEmpty() == true)) {
                                    item { ThinkingBubble() }
                                }
                            }
                        }

                        if (state.error != null && state.messages.isNotEmpty()) {
                            Text(
                                text = state.error!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = spacing.md)
                            )
                        }

                        // Screenshot preview inside popup when attached
                        if (state.attachScreenshot && state.screenshotPath != null && state.messages.isEmpty()) {
                            ScreenshotPreview(path = state.screenshotPath!!)
                        }

                        AssistInputBar(
                            composing = state.composing,
                            onComposingChange = viewModel::updateComposing,
                            sending = state.sending || state.streaming,
                            onSend = { viewModel.sendMessage() },
                            focusRequester = focusRequester,
                            recording = state.recording,
                            transcribing = state.transcribing,
                            onStartRecording = {
                                if (ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) viewModel.startRecording(context)
                            },
                            onStopRecording = { viewModel.stopRecordingAndTranscribe() },
                            attachments = state.attachments,
                            onRemoveAttachment = viewModel::removeAttachment
                        )
                    }
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
private fun AssistHeader(
    onDismiss: () -> Unit,
    onOpenFullScreen: () -> Unit,
    showControls: Boolean,
    ttsEnabled: Boolean,
    ttsAvailable: Boolean,
    onToggleTts: () -> Unit
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(Unit) {
                    var dragDistance = 0f
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dy -> dragDistance += dy },
                        onDragEnd = {
                            if (dragDistance < -80f) onOpenFullScreen()
                            dragDistance = 0f
                        },
                        onDragCancel = { dragDistance = 0f }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(4.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(2.dp)
                    )
            )
        }

        // Close and fullscreen only visible when there are messages
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // TTS toggle on the left
                IconButton(
                    onClick = onToggleTts,
                    modifier = Modifier
                        .then(if (!ttsAvailable) Modifier.alpha(0.38f) else Modifier)
                ) {
                    Icon(
                        if (ttsEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        contentDescription = if (ttsEnabled) "Disable TTS" else "Enable TTS",
                        tint = if (ttsEnabled) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Fullscreen + close on the right
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenFullScreen) {
                        Icon(
                            Icons.Filled.Fullscreen,
                            contentDescription = "Open full screen",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
    }
}

@Composable
private fun AssistInputBar(
    composing: String,
    onComposingChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    focusRequester: FocusRequester,
    recording: Boolean,
    transcribing: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    attachments: List<Attachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {}
) {
    val spacing = LocalSpacing.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.md, end = spacing.md, bottom = spacing.md)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                // Attachment preview chips
                if (attachments.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.sm, vertical = spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        items(attachments, key = { it.id }) { att ->
                            AttachmentChip(attachment = att, onRemove = { onRemoveAttachment(att.id) })
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .padding(horizontal = spacing.sm, vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                // Keep as State<Float> (no `by`) so value is read in drawBehind (draw phase)
                // not composition phase -- prevents full recomposition every animation frame
                val recordingPulseAlpha = rememberInfiniteTransition(label = "pulse").animateFloat(
                    initialValue = 0.0f,
                    targetValue = 0.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulse_alpha"
                )
                val errorColor = MaterialTheme.colorScheme.error

                IconButton(
                    onClick = { if (recording) onStopRecording() else if (!transcribing) onStartRecording() },
                    enabled = !transcribing,
                    modifier = Modifier
                        .size(40.dp)
                        .drawBehind {
                            if (recording) {
                                drawCircle(color = errorColor.copy(alpha = recordingPulseAlpha.value))
                            }
                        }
                ) {
                    if (transcribing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = if (recording) "Stop recording" else "Record",
                            tint = if (recording) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

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
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp)
                )

                FilledIconButton(
                    onClick = onSend,
                    enabled = (composing.isNotBlank() || attachments.isNotEmpty()) && !sending,
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(12.dp),
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
}

@Composable
private fun AttachmentChip(attachment: Attachment, onRemove: () -> Unit) {
    val spacing = LocalSpacing.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.height(40.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (attachment.isImage) {
                AsyncImage(
                    model = attachment.uri,
                    contentDescription = null,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                text = attachment.name.take(24),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}
