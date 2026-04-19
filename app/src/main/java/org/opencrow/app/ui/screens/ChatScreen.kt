package org.opencrow.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.opencrow.app.OpenCrowApp
import org.opencrow.app.data.remote.dto.*
import org.opencrow.app.ui.theme.LocalSpacing
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    onUnpaired: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCrowApp
    val scope = rememberCoroutineScope()
    val spacing = LocalSpacing.current

    var conversations by remember { mutableStateOf<List<ConversationDto>>(emptyList()) }
    var activeConversationId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<MessageDto>>(emptyList()) }
    var composing by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var loadingMessages by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showSystemChats by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var transcribedMessageIds by remember { mutableStateOf(setOf<String>()) }

    val listState = rememberLazyListState()
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }

    // Load conversations on mount
    LaunchedEffect(Unit) {
        try {
            val resp = app.apiClient.api.listConversations()
            if (resp.isSuccessful) {
                conversations = resp.body()?.conversations.orEmpty()
                    .sortedByDescending { it.updatedAt }
            }
        } catch (e: Exception) {
            Log.e("Chat", "Failed to load conversations", e)
        }
    }

    // Load messages when conversation changes
    LaunchedEffect(activeConversationId) {
        val convId = activeConversationId ?: run {
            messages = emptyList()
            return@LaunchedEffect
        }
        loadingMessages = true
        try {
            val resp = app.apiClient.api.listMessages(convId)
            if (resp.isSuccessful) {
                messages = resp.body()?.messages.orEmpty()
            }
        } catch (e: Exception) {
            Log.e("Chat", "Failed to load messages", e)
        }
        loadingMessages = false
    }

    // Auto-scroll on new messages
    LaunchedEffect(messages.size, sending) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun sendMessage(text: String, isTranscribed: Boolean = false) {
        if (text.isBlank() || sending) return
        sending = true
        composing = ""
        scope.launch {
            try {
                var convId = activeConversationId
                // Create conversation if needed
                if (convId == null) {
                    val createResp = app.apiClient.api.createConversation(
                        CreateConversationRequest(text.take(50))
                    )
                    if (createResp.isSuccessful) {
                        val newConv = createResp.body()!!
                        convId = newConv.id
                        activeConversationId = convId
                        conversations = listOf(
                            ConversationDto(newConv.id, newConv.title, newConv.createdAt, newConv.updatedAt)
                        ) + conversations
                    } else {
                        sending = false
                        return@launch
                    }
                }

                // Show optimistic user message in UI while orchestrator runs
                val tempMsg = MessageDto(
                    id = "temp-${System.currentTimeMillis()}",
                    conversationId = convId!!,
                    role = "user",
                    content = text,
                    createdAt = ""
                )
                messages = messages + tempMsg

                // Call orchestrator (it saves the user message + generates the reply)
                val completeResp = app.apiClient.api.complete(
                    CompleteRequest(convId, text)
                )
                if (completeResp.isSuccessful) {
                    val output = completeResp.body()!!.output
                    // Reload messages to get the assistant message with proper ID
                    val msgsResp = app.apiClient.api.listMessages(convId)
                    if (msgsResp.isSuccessful) {
                        val reloaded = msgsResp.body()?.messages.orEmpty()
                        if (isTranscribed) {
                            // Find the real user message that replaced our temp one
                            val realUserMsg = reloaded.lastOrNull { it.role == "user" }
                            if (realUserMsg != null) {
                                transcribedMessageIds = transcribedMessageIds + realUserMsg.id
                            }
                        }
                        messages = reloaded
                    }
                }

                // Refresh conversation list
                val convResp = app.apiClient.api.listConversations()
                if (convResp.isSuccessful) {
                    conversations = convResp.body()?.conversations.orEmpty()
                        .sortedByDescending { it.updatedAt }
                }
            } catch (e: Exception) {
                Log.e("Chat", "Send failed", e)
            }
            sending = false
        }
    }

    fun startRecording() {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        audioFile = file
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        mediaRecorder = recorder
        recording = true
    }

    fun stopRecordingAndTranscribe() {
        recording = false
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null

        val file = audioFile ?: return
        transcribing = true
        scope.launch {
            try {
                val requestBody = file.asRequestBody("audio/mp4".toMediaType())
                val part = MultipartBody.Part.createFormData("audio", file.name, requestBody)
                val resp = app.apiClient.api.transcribe(part)
                if (resp.isSuccessful) {
                    val transcript = resp.body()?.transcript.orEmpty()
                    if (transcript.isNotBlank()) {
                        sendMessage(transcript, isTranscribed = true)
                    }
                }
            } catch (e: Exception) {
                Log.e("Chat", "Transcription failed", e)
            }
            transcribing = false
            file.delete()
        }
    }

    val visibleConversations = if (showSystemChats) {
        conversations
    } else {
        conversations.filter { !it.isAutomatic || it.automationKind == "heartbeat" }
    }

    val hasActiveChat = activeConversationId != null && messages.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = conversations.find { it.id == activeConversationId }?.title ?: "openCrow",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        Row {
                            IconButton(onClick = { showHistory = true }) {
                                Icon(
                                    Icons.Outlined.History,
                                    contentDescription = "Chat history",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (hasActiveChat) {
                                IconButton(onClick = {
                                    activeConversationId = null
                                    messages = emptyList()
                                }) {
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
                    if (activeConversationId == null) {
                        // Empty state
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
                    } else if (loadingMessages) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = spacing.md),
                            contentPadding = PaddingValues(vertical = spacing.md),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            items(messages, key = { it.id }) { msg ->
                                MessageBubble(
                                    message = msg,
                                    isTranscribed = msg.id in transcribedMessageIds
                                )
                            }
                            // Thinking indicator
                            if (sending) {
                                item {
                                    ThinkingBubble()
                                }
                            }
                        }
                    }
                }

                // Input bar
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
                        // Attach button
                        IconButton(
                            onClick = { /* placeholder */ },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Outlined.AttachFile,
                                contentDescription = "Attach",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Model selector (disabled placeholder)
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

                        // Text input
                        TextField(
                            value = composing,
                            onValueChange = { composing = it },
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

                        // Mic button
                        IconButton(
                            onClick = {
                                if (recording) {
                                    stopRecordingAndTranscribe()
                                } else if (!transcribing) {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                        == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        startRecording()
                                    }
                                }
                            },
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

                        // Send button
                        FilledIconButton(
                            onClick = { sendMessage(composing) },
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
        }

        // History bottom sheet overlay
        AnimatedVisibility(
            visible = showHistory,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            HistorySheet(
                conversations = visibleConversations,
                activeId = activeConversationId,
                showSystemChats = showSystemChats,
                onToggleSystemChats = { showSystemChats = it },
                onSelectConversation = { id ->
                    activeConversationId = id
                    showHistory = false
                },
                onDismiss = { showHistory = false }
            )
        }
    }
}

@Composable
fun MessageBubble(message: MessageDto, isTranscribed: Boolean = false) {
    val isUser = message.role == "user"
    val isSystem = message.role == "system"
    val spacing = LocalSpacing.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // Agent indicator dot
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, end = spacing.sm)
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.secondary)
            )
        }

        Surface(
            color = when {
                isUser -> MaterialTheme.colorScheme.primaryContainer
                isSystem -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isSystem) {
                    Text(
                        "System",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(spacing.xs))
                }
                Row(verticalAlignment = Alignment.Top) {
                    if (isUser && isTranscribed) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = "Transcribed",
                            modifier = Modifier
                                .size(14.dp)
                                .padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                    }
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun ThinkingBubble() {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .padding(top = 12.dp, end = spacing.sm)
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.secondary)
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                repeat(3) { i ->
                    val alpha by animateThinkingDot(i)
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun animateThinkingDot(index: Int): State<Float> {
    val alpha = remember { mutableFloatStateOf(0.3f) }
    LaunchedEffect(index) {
        while (true) {
            delay(index * 150L)
            alpha.floatValue = 0.8f
            delay(300)
            alpha.floatValue = 0.3f
            delay(600 - index * 150L)
        }
    }
    return alpha
}

@Composable
fun HistorySheet(
    conversations: List<ConversationDto>,
    activeId: String?,
    showSystemChats: Boolean,
    onToggleSystemChats: (Boolean) -> Unit,
    onSelectConversation: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = LocalSpacing.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            .clickable(onClick = onDismiss)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .align(Alignment.BottomCenter)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount > 50) onDismiss()
                    }
                },
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
        ) {
            Column {
                // Drag handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDismiss)
                        .padding(vertical = spacing.sm),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    )
                }

                // System chats toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Previous chats",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        Text(
                            "System chats",
                            style = MaterialTheme.typography.labelSmall,
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
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(spacing.sm)
                ) {
                    items(conversations, key = { it.id }) { conv ->
                        val isActive = conv.id == activeId
                        Surface(
                            onClick = { onSelectConversation(conv.id) },
                            color = if (isActive) MaterialTheme.colorScheme.surfaceContainerHigh
                            else Color.Transparent,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(
                                    horizontal = spacing.md,
                                    vertical = spacing.sm
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                                ) {
                                    Text(
                                        text = conv.title.ifBlank { "Untitled" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isActive) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (conv.isAutomatic) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = MaterialTheme.shapes.extraSmall
                                        ) {
                                            Text(
                                                text = conv.automationKind ?: "auto",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = formatDate(conv.updatedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
private val displayFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

private fun formatDate(iso: String): String {
    return try {
        val date = isoParser.parse(iso.take(19))
        displayFormat.format(date!!)
    } catch (_: Exception) {
        iso.take(10)
    }
}
