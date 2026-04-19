package org.opencrow.app.ui.screens.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import org.opencrow.app.OpenCrowApp
import org.opencrow.app.ui.screens.chat.components.HistorySheet
import org.opencrow.app.ui.screens.chat.components.MessageBubble
import org.opencrow.app.ui.screens.chat.components.ThinkingBubble
import org.opencrow.app.ui.screens.chat.components.ToolCallBubble
import org.opencrow.app.ui.theme.LocalSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    onUnpaired: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCrowApp
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(app.container.conversationRepository)
    )
    val state by viewModel.uiState.collectAsState()
    val spacing = LocalSpacing.current
    val listState = rememberLazyListState()

    // Auto-scroll on new messages (not on every streaming token)
    val messageCount = state.messages.size
    LaunchedEffect(messageCount, state.sending) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(messageCount - 1)
        }
    }
    // Scroll to bottom during streaming, but only when content grows significantly
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

    val visibleConversations = remember(state.conversations, state.showSystemChats) {
        if (state.showSystemChats) {
            state.conversations
        } else {
            state.conversations.filter { !it.isAutomatic || it.automationKind == "heartbeat" }
        }
    }

    val hasActiveChat = state.activeConversationId != null && state.messages.isNotEmpty()
    val activeConversation = state.conversations.find { it.id == state.activeConversationId }
    val isTelegramChat = activeConversation?.title?.contains("[telegram]", ignoreCase = true) == true

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isTelegramChat) {
                                Icon(
                                    Icons.Outlined.Lock,
                                    contentDescription = "Read-only",
                                    modifier = Modifier
                                        .size(16.dp)
                                        .padding(end = 4.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = activeConversation?.title ?: "openCrow",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        Row {
                            IconButton(onClick = { viewModel.toggleHistory(true) }) {
                                Icon(
                                    Icons.Outlined.History,
                                    contentDescription = "Chat history",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (hasActiveChat) {
                                IconButton(onClick = { viewModel.clearActiveConversation() }) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = "New chat",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                    .imePadding()
            ) {
                // Messages area
                Box(modifier = Modifier.weight(1f)) {
                    if (state.activeConversationId == null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "Chat",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(spacing.sm))
                                Text(
                                    "Start a new conversation",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (state.loadingMessages) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        PullToRefreshBox(
                            isRefreshing = state.refreshingMessages,
                            onRefresh = { viewModel.refreshMessages() },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = spacing.md),
                                contentPadding = PaddingValues(vertical = spacing.md),
                                verticalArrangement = Arrangement.spacedBy(spacing.sm)
                            ) {
                                items(state.messages, key = { it.id }) { msg ->
                                    val toolCalls = state.toolCallsByMessageId[msg.id]
                                    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                        // Show tool calls before the assistant response
                                        if (msg.role == "assistant" && !toolCalls.isNullOrEmpty()) {
                                            ToolCallBubble(toolCalls = toolCalls)
                                        }
                                    MessageBubble(
                                        message = msg,
                                        isTranscribed = msg.id in state.transcribedMessageIds
                                    )
                                }
                            }
                            if (state.sending) {
                                item { ThinkingBubble() }
                            }
                        }
                        }
                    }
                }

                // Input bar
                if (isTelegramChat) {
                    TelegramReadOnlyBar()
                } else {
                    ChatInputBar(
                        composing = state.composing,
                        onComposingChange = viewModel::updateComposing,
                        sending = state.sending || state.streaming,
                        recording = state.recording,
                        transcribing = state.transcribing,
                        activeConversationId = state.activeConversationId,
                        onSend = { viewModel.sendMessage() },
                        onStartRecording = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED
                            ) {
                                viewModel.startRecording(context)
                            }
                        },
                        onStopRecording = { viewModel.stopRecordingAndTranscribe() }
                    )
                }
            }
        }

        // History bottom sheet overlay
        HistorySheet(
            visible = state.showHistory,
            conversations = visibleConversations,
            activeId = state.activeConversationId,
            showSystemChats = state.showSystemChats,
            onToggleSystemChats = viewModel::toggleSystemChats,
            onSelectConversation = viewModel::selectConversation,
            onDismiss = { viewModel.toggleHistory(false) }
        )
    }
}

@Composable
private fun TelegramReadOnlyBar() {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = spacing.md, vertical = spacing.md)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(spacing.sm))
            Text(
                "Telegram conversations are read-only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    composing: String,
    onComposingChange: (String) -> Unit,
    sending: Boolean,
    recording: Boolean,
    transcribing: Boolean,
    activeConversationId: String?,
    onSend: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    val spacing = LocalSpacing.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = spacing.sm, vertical = spacing.sm)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            IconButton(onClick = { /* placeholder */ }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Outlined.AttachFile,
                    contentDescription = "Attach",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = { /* placeholder for model selector */ },
                enabled = false,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Outlined.SmartToy,
                    contentDescription = "Model",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }

            TextField(
                value = composing,
                onValueChange = onComposingChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (activeConversationId != null) "Message openCrow..." else "Start a new conversation...",
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

            IconButton(
                onClick = { if (recording) onStopRecording() else if (!transcribing) onStartRecording() },
                enabled = !transcribing,
                modifier = Modifier.size(40.dp)
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
                Icon(Icons.Filled.Send, contentDescription = "Send", modifier = Modifier.size(18.dp))
            }
        }
    }
}
