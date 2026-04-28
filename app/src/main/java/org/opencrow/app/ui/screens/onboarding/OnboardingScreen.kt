package org.opencrow.app.ui.screens.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.opencrow.app.ui.components.MarkdownText
import org.opencrow.app.ui.screens.chat.components.ThinkingBubble
import org.opencrow.app.ui.theme.LocalSpacing

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    vm: OnboardingViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val spacing = LocalSpacing.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    // Permission launcher -- fires when state.pendingPermissions becomes non-null
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        vm.onPermissionResult(results)
    }

    // Launch system permission dialog whenever pendingPermissions is set
    LaunchedEffect(state.pendingPermissions) {
        val perms = state.pendingPermissions ?: return@LaunchedEffect
        permLauncher.launch(perms.toTypedArray())
    }

    // Navigate away when done
    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }

    // Kick off the onboarding flow
    LaunchedEffect(Unit) {
        vm.start()
    }

    // Haptic feedback on each new streamed word: soft 90%, stronger 10%
    val streamWordCount = state.streamingText?.count { it == ' ' }?.plus(
        if ((state.streamingText?.isNotEmpty()) == true) 1 else 0
    ) ?: -1
    LaunchedEffect(streamWordCount) {
        if (streamWordCount > 0) {
            val hapticType = if (kotlin.random.Random.nextFloat() < 0.1f)
                android.view.HapticFeedbackConstants.KEYBOARD_TAP
            else
                android.view.HapticFeedbackConstants.CLOCK_TICK
            view.performHapticFeedback(hapticType)
        }
    }

    // Auto-scroll to bottom whenever messages or streaming state changes
    val streamLen = state.streamingText?.length ?: -1
    val itemCount = state.messages.size + if (state.streamingText != null) 1 else 0
    LaunchedEffect(itemCount, streamLen) {
        if (itemCount > 0) {
            scope.launch { listState.animateScrollToItem(itemCount - 1) }
        }
    }

    Scaffold { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Message list with bottom padding so content clears floating buttons
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
                contentPadding = PaddingValues(
                    top = 72.dp,
                    bottom = 120.dp
                )
            ) {
                items(state.messages) { message ->
                    when (message) {
                        is OnboardingMessage.Ai -> AiBubble(message.text)
                        is OnboardingMessage.User -> UserBubble(message.text)
                    }
                }
                // Streaming / thinking bubble
                val streaming = state.streamingText
                if (streaming != null) {
                    item {
                        if (streaming.isEmpty()) {
                            ThinkingBubble()
                        } else {
                            AiBubble("$streaming▋")
                        }
                    }
                }
            }

            // Floating action buttons
            val actions = state.actions
            if (actions != null) {
                val skipAction = actions.find { it.tag == "skip" || it.tag == "up_skip" }
                val primaryActions = actions.filter { it.tag != "skip" && it.tag != "up_skip" }

                // Skip -- bottom start
                if (skipAction != null) {
                    TextButton(
                        onClick = { vm.onAction(skipAction.tag) },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = spacing.md, vertical = spacing.lg)
                    ) {
                        Text(skipAction.label)
                    }
                }

                // Primary actions -- bottom end
                if (primaryActions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(horizontal = spacing.md, vertical = spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        horizontalAlignment = Alignment.End
                    ) {
                        primaryActions.forEach { action ->
                            Button(onClick = { vm.onAction(action.tag) }) {
                                Text(action.label)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Message bubble composables ────────────────────────────────────────────────

@Composable
private fun AiBubble(text: String) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        MarkdownText(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = spacing.xxl)
        )
    }
}

@Composable
private fun UserBubble(text: String) {
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
            color = MaterialTheme.colorScheme.primary,
            shape = userMessageShape
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}
