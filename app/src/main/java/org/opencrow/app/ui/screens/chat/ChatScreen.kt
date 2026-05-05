package org.opencrow.app.ui.screens.chat

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import org.opencrow.app.OpenCrowApp
import org.opencrow.app.ui.screens.chat.components.AttachmentPreviewRow
import org.opencrow.app.ui.screens.chat.components.HistorySheet
import org.opencrow.app.ui.screens.chat.components.MessageBubble
import org.opencrow.app.ui.screens.chat.components.ThinkingBubble
import org.opencrow.app.ui.screens.chat.components.ToolCallBubble
import org.opencrow.app.ui.theme.LocalSpacing
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    pendingConversationId: String? = null,
    onConversationOpened: () -> Unit = {},
    onNavigateToSettings: () -> Unit,
    onUnpaired: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as OpenCrowApp
    val keyboardController = LocalSoftwareKeyboardController.current
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(app.container.conversationRepository, app.container.configRepository, app)
    )
    val state by viewModel.uiState.collectAsState()
    val spacing = LocalSpacing.current
    val listState = rememberLazyListState()

    // Open a specific conversation when arriving from AssistScreen
    LaunchedEffect(pendingConversationId) {
        if (pendingConversationId != null) {
            viewModel.selectConversation(pendingConversationId)
            onConversationOpened()
        }
    }

    // Permission request launcher wired to ViewModel
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.onPermissionResult(granted) }

    LaunchedEffect(state.pendingPermission) {
        state.pendingPermission?.let { permissionLauncher.launch(it) }
    }

    // Auto-scroll on new messages
    val messageCount = state.messages.size
    LaunchedEffect(messageCount, state.sending) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(messageCount - 1)
        }
    }
    // Instant scroll during streaming to avoid competing animations
    val lastMessageLength = state.messages.lastOrNull()?.content?.length ?: 0
    val streamingScrollThreshold = remember { mutableIntStateOf(0) }
    LaunchedEffect(state.streaming, lastMessageLength) {
        if (state.streaming && lastMessageLength - streamingScrollThreshold.intValue > 50) {
            streamingScrollThreshold.intValue = lastMessageLength
            if (state.messages.isNotEmpty()) {
                listState.scrollToItem(state.messages.size - 1)
            }
        }
        if (!state.streaming) streamingScrollThreshold.intValue = 0
    }

    // Typing haptic feedback while the assistant is streaming
    val view = androidx.compose.ui.platform.LocalView.current
    // Soft tap on each token flush; occasionally (10%) a stronger tap for variety
    LaunchedEffect(lastMessageLength) {
        if (state.streaming && lastMessageLength > 0 && Random.nextFloat() > 0.3f) {
            val hapticType = if (Random.nextFloat() < 0.1f)
                android.view.HapticFeedbackConstants.KEYBOARD_TAP
            else
                android.view.HapticFeedbackConstants.CLOCK_TICK
            view.performHapticFeedback(hapticType)
        }
    }
    // Strong confirmation tap when streaming finishes
    var prevStreaming by remember { mutableStateOf(false) }
    LaunchedEffect(state.streaming) {
        if (prevStreaming && !state.streaming) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        }
        prevStreaming = state.streaming
    }

    val visibleConversations = remember(state.conversations, state.showSystemChats) {
        if (state.showSystemChats) {
            state.conversations
        } else {
            state.conversations.filter { !it.isAutomatic }
        }
    }

    val hasActiveChat = state.activeConversationId != null && state.messages.isNotEmpty()
    val activeConversation = remember(state.conversations, state.activeConversationId) {
        state.conversations.find { it.id == state.activeConversationId }
    }
    val isTelegramChat = remember(activeConversation?.title) {
        activeConversation?.title?.contains("[telegram]", ignoreCase = true) == true
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            var totalDrag = 0f
            detectHorizontalDragGestures(
                onDragStart = { totalDrag = 0f },
                onHorizontalDrag = { _, dx -> totalDrag += dx },
                onDragEnd = {
                    if (totalDrag > 80f && !state.showHistory) {
                        keyboardController?.hide()
                        viewModel.toggleHistory(true)
                    }
                    totalDrag = 0f
                },
                onDragCancel = { totalDrag = 0f }
            )
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
            ) {
            // ── Messages area fills the full box; bars float on top ──
            if (state.activeConversationId == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 72.dp, bottom = 96.dp),
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 72.dp, bottom = 96.dp),
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
                        // top: clears floating buttons; bottom: extra room so last message clears input bar
                        contentPadding = PaddingValues(top = 72.dp, bottom = 128.dp),
                        verticalArrangement = Arrangement.spacedBy(spacing.lg)
                    ) {
                        // Snapshot maps once per composition to avoid re-reading inside each item lambda
                        val toolCallsMap = state.toolCallsByMessageId
                        val attachmentsMap = state.attachmentsByMessageId
                        val transcribedIds = state.transcribedMessageIds

                        items(state.messages, key = { it.id }) { msg ->
                            val toolCalls = toolCallsMap[msg.id]
                            val msgAttachments = attachmentsMap[msg.id].orEmpty()
                            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                if (msg.role == "assistant" && !toolCalls.isNullOrEmpty()) {
                                    ToolCallBubble(toolCalls = toolCalls)
                                }
                                MessageBubble(
                                    message = msg,
                                    isTranscribed = msg.id in transcribedIds,
                                    attachments = msgAttachments,
                                    onRegenerate = if (msg.role == "assistant" && !state.streaming && !state.sending) {
                                        { viewModel.regenerateMessage(msg.id) }
                                    } else null
                                )
                            }
                        }
                        if (state.sending || (state.streaming && state.messages.lastOrNull()?.role == "assistant" && state.messages.lastOrNull()?.content?.isEmpty() == true)) {
                            item { ThinkingBubble() }
                        }
                    }
                }
            }

            // ── Top gradient: smoothly fades messages into the status bar area ──
            val bgColor = MaterialTheme.colorScheme.background
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(bgColor, Color.Transparent)
                        )
                    )
            )

            // ── Floating navigation buttons -- history (left) and new chat (right) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = { viewModel.toggleHistory(true) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shadowElevation = 6.dp,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "Chat history",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Surface(
                        onClick = { viewModel.toggleTts() },
                        shape = CircleShape,
                        color = if (state.ttsEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .size(44.dp)
                            .then(if (!state.ttsAvailable) Modifier.alpha(0.38f) else Modifier)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                if (state.ttsEnabled) Icons.Filled.VolumeUp else Icons.Outlined.VolumeOff,
                                contentDescription = if (state.ttsEnabled) "Disable TTS" else "Enable TTS",
                                tint = if (state.ttsEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                if (hasActiveChat) {
                    Surface(
                        onClick = { viewModel.clearActiveConversation() },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shadowElevation = 6.dp,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.AutoAwesome,
                                contentDescription = "New chat",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.size(44.dp))
                }
            }

            // ── Floating bottom bar -- attachment preview + input ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                if (!isTelegramChat) {
                    // Attachment thumbnails float above the input bar, no container
                    AnimatedVisibility(
                        visible = state.attachments.isNotEmpty(),
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                    ) {
                        AttachmentPreviewRow(
                            attachments = state.attachments,
                            onRemove = viewModel::removeAttachment,
                            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.xs)
                        )
                    }
                }

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
                        hasAttachments = state.attachments.isNotEmpty(),
                        onSend = { viewModel.sendMessage() },
                        onAddAttachments = viewModel::addAttachments,
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

            } // closes inner Box

        // History bottom sheet overlay
        HistorySheet(
            visible = state.showHistory,
            conversations = visibleConversations,
            activeId = state.activeConversationId,
            showSystemChats = state.showSystemChats,
            isRefreshing = state.refreshingConversations,
            onRefresh = viewModel::refreshConversations,
            onToggleSystemChats = viewModel::toggleSystemChats,
            onSelectConversation = viewModel::selectConversation,
            onDeleteConversation = viewModel::deleteConversation,
            onLogout = { viewModel.logout { onUnpaired() } },
            onDismiss = { viewModel.toggleHistory(false) }
        )
    }
}
}

@Composable
private fun TelegramReadOnlyBar() {
    val spacing = LocalSpacing.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = spacing.md, end = spacing.md, bottom = spacing.md)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = spacing.md, vertical = spacing.md),
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
}

@Composable
private fun ChatInputBar(
    composing: String,
    onComposingChange: (String) -> Unit,
    sending: Boolean,
    recording: Boolean,
    transcribing: Boolean,
    activeConversationId: String?,
    hasAttachments: Boolean,
    onSend: () -> Unit,
    onAddAttachments: (List<Attachment>) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showAttachPopup by remember { mutableStateOf(false) }
    // rememberSaveable so the URI survives process death while the camera app is open
    var cameraImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pendingCameraCapture by rememberSaveable { mutableStateOf(false) }

    // Manual camera launcher so we can use an explicit component fallback on Pixels.
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        pendingCameraCapture = false
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = cameraImageUri ?: return@rememberLauncherForActivityResult
            val name = "photo_${System.currentTimeMillis()}.jpg"
            onAddAttachments(listOf(Attachment(uri = uri, name = name, mimeType = "image/jpeg")))
            cameraImageUri = null
        }
    }

    DisposableEffect(lifecycleOwner, cameraImageUri, pendingCameraCapture) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingCameraCapture) {
                val uri = cameraImageUri
                if (uri != null && uriHasContent(context, uri)) {
                    pendingCameraCapture = false
                    val name = "photo_${System.currentTimeMillis()}.jpg"
                    onAddAttachments(listOf(Attachment(uri = uri, name = name, mimeType = "image/jpeg")))
                    cameraImageUri = null
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) doLaunchCamera(context, cameraLauncher, onPending = { pendingCameraCapture = it }) { cameraImageUri = it }
    }

    fun launchCamera() {
        showAttachPopup = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            doLaunchCamera(context, cameraLauncher, onPending = { pendingCameraCapture = it }) { cameraImageUri = it }
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // File picker (any file type, multiple)
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val attachments = uris.map { uri ->
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                val name = cursor?.use {
                    if (it.moveToFirst()) {
                        val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) it.getString(idx) else null
                    } else null
                } ?: uri.lastPathSegment ?: "file"
                val mime = context.contentResolver.getType(uri)
                Attachment(uri = uri, name = name, mimeType = mime)
            }
            onAddAttachments(attachments)
        }
    }

    // Image picker (multiple)
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val attachments = uris.map { uri ->
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                val name = cursor?.use {
                    if (it.moveToFirst()) {
                        val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) it.getString(idx) else null
                    } else null
                } ?: uri.lastPathSegment ?: "image"
                val mime = context.contentResolver.getType(uri)
                Attachment(uri = uri, name = name, mimeType = mime)
            }
            onAddAttachments(attachments)
        }
    }

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
            Row(
                modifier = Modifier
                    .padding(horizontal = spacing.sm, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                Box {
                    IconButton(
                        onClick = { showAttachPopup = !showAttachPopup },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Outlined.AttachFile,
                            contentDescription = "Attach",
                            tint = if (showAttachPopup) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    DropdownMenu(
                        expanded = showAttachPopup,
                        onDismissRequest = { showAttachPopup = false },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Photos & Videos") },
                            onClick = {
                                showAttachPopup = false
                                imagePicker.launch(arrayOf("image/*", "video/*"))
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Camera") },
                            onClick = { launchCamera() },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.CameraAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Files") },
                            onClick = {
                                showAttachPopup = false
                                filePicker.launch(arrayOf("*/*"))
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        )
                    }
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
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp)
                )

                // Keep as State<Float> (no `by`) so the value is read in drawBehind (draw phase)
                // instead of composition phase -- prevents full recomposition every animation frame
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

                FilledIconButton(
                    onClick = onSend,
                    enabled = (composing.isNotBlank() || hasAttachments) && !sending,
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(12.dp),
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
}

/**
 * Creates a temp file via FileProvider and launches the camera using an explicit component
 * when available. Some Pixel builds expose IMAGE_CAPTURE in the manifest but do not resolve
 * it implicitly; explicit component fallback avoids that.
 */
private fun doLaunchCamera(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
    onPending: (Boolean) -> Unit,
    onUriReady: (Uri) -> Unit
) {
    val photoFile = try {
        File(
            context.cacheDir.resolve("camera").also { it.mkdirs() },
            "photo_${System.currentTimeMillis()}.jpg"
        )
    } catch (e: Exception) {
        android.util.Log.e("Camera", "Could not create photo file", e)
        android.widget.Toast.makeText(context, "Could not create photo file", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
    val captureIntent = buildCameraIntent(context, uri)
    val grantIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
    context.packageManager.queryIntentActivities(grantIntent, PackageManager.MATCH_DEFAULT_ONLY)
        .plus(context.packageManager.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), PackageManager.MATCH_DEFAULT_ONLY))
        .map { it.activityInfo.packageName }
        .distinct()
        .forEach { packageName ->
            context.grantUriPermission(
                packageName,
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    onUriReady(uri)
    try {
        onPending(true)
        launcher.launch(captureIntent)
    } catch (e: android.content.ActivityNotFoundException) {
        onPending(false)
        android.util.Log.e("Camera", "No camera app found", e)
        android.widget.Toast.makeText(context, "No camera app found", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun buildCameraIntent(context: Context, uri: Uri): Intent {
    val packageManager = context.packageManager
    val implicitCapture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
        putExtra(MediaStore.EXTRA_OUTPUT, uri)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val captureHandler = packageManager.resolveActivity(
        implicitCapture,
        PackageManager.MATCH_DEFAULT_ONLY
    )
    if (captureHandler != null) return implicitCapture

    val explicitCapture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
        component = ComponentName(
            "com.google.android.GoogleCamera",
            "com.android.camera.activity.CaptureActivity"
        )
        putExtra(MediaStore.EXTRA_OUTPUT, uri)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    if (explicitCapture.resolveActivity(packageManager) != null) return explicitCapture

    return Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
        component = ComponentName(
            "com.google.android.GoogleCamera",
            "com.android.camera.CameraImageActivity"
        )
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun uriHasContent(context: Context, uri: Uri): Boolean {
    return try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length > 0 } == true
    } catch (_: Exception) {
        false
    }
}
