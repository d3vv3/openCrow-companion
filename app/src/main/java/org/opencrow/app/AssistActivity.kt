package org.opencrow.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.opencrow.app.ui.screens.assist.AssistScreen
import org.opencrow.app.ui.screens.assist.AssistViewModel
import org.opencrow.app.ui.screens.chat.Attachment
import org.opencrow.app.ui.theme.OpenCrowTheme

class AssistActivity : ComponentActivity() {

    private val assistViewModel: AssistViewModel by viewModels {
        val app = application as OpenCrowApp
        AssistViewModel.Factory(
            app.container.conversationRepository,
            app.container.configRepository,
            applicationContext
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        assistViewModel.resetConversation()
        val screenshotPath = intent?.getStringExtra("screenshot_path")
        val initialMessage = resolveInitialMessage(intent)
        val sharedContent = resolveSharedContent(intent)
        setupContent(screenshotPath, initialMessage, sharedContent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        assistViewModel.resetConversation()
        val screenshotPath = intent.getStringExtra("screenshot_path")
        assistViewModel.setScreenshotPath(screenshotPath)
        val initialMessage = resolveInitialMessage(intent)
        if (initialMessage != null) {
            assistViewModel.setInitialMessage(initialMessage)
        }
        val sharedContent = resolveSharedContent(intent)
        if (sharedContent != null) {
            if (sharedContent.text != null) assistViewModel.setInitialComposing(sharedContent.text)
            if (sharedContent.attachments.isNotEmpty()) assistViewModel.addAttachments(sharedContent.attachments)
        }
    }

    /**
     * Extracts a pre-determined prompt from the intent, if any.
     *
     * Supported sources (in priority order):
     * 1. `ACTION_PROCESS_TEXT` -- Android text-selection menu ("Translate" action).
     *    The selected text arrives in [Intent.EXTRA_PROCESS_TEXT].
     * 2. `translate_text` extra -- internal shortcut for the same flow.
     */
    private fun resolveInitialMessage(intent: Intent?): String? {
        if (intent == null) return null
        // Android text-selection popup (ACTION_PROCESS_TEXT)
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (!selected.isNullOrBlank()) return "Translate this:\n$selected"
        }
        // Internal translate_text extra
        val translateText = intent.getStringExtra("translate_text")
        if (!translateText.isNullOrBlank()) return "Translate this:\n$translateText"
        return null
    }

    data class SharedContent(val text: String?, val attachments: List<Attachment>)

    /**
     * Extracts shared content from ACTION_SEND / ACTION_SEND_MULTIPLE intents.
     * Returns null if the intent is not a share intent.
     * The caller should pre-fill composing with [SharedContent.text] (without auto-sending)
     * and add [SharedContent.attachments] to the ViewModel so the user can review before sending.
     */
    private fun resolveSharedContent(intent: Intent?): SharedContent? {
        if (intent == null) return null
        val action = intent.action ?: return null

        if (action == Intent.ACTION_SEND) {
            // Plain text share
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            val attachments = if (uri != null) listOf(uriToAttachment(uri)) else emptyList()
            if (sharedText.isNullOrBlank() && attachments.isEmpty()) return null
            return SharedContent(text = sharedText, attachments = attachments)
        }

        if (action == Intent.ACTION_SEND_MULTIPLE) {
            val uris: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM) ?: emptyList()
            }
            val attachments = uris.map { uriToAttachment(it) }
            if (attachments.isEmpty()) return null
            return SharedContent(text = null, attachments = attachments)
        }

        return null
    }

    private fun uriToAttachment(uri: Uri): Attachment {
        var name = uri.lastPathSegment ?: "file"
        var mimeType = contentResolver.getType(uri)
        // Try to get a human-readable name from the content resolver
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    name = cursor.getString(nameIndex) ?: name
                }
            }
        } catch (_: Exception) {}
        return Attachment(uri = uri, name = name, mimeType = mimeType)
    }

    private fun setupContent(screenshotPath: String?, initialMessage: String?, sharedContent: SharedContent? = null) {
        setContent {
            OpenCrowTheme {
                val app = application as OpenCrowApp
                val activeStream by app.container.activeStream.collectAsState()

                var wasStreaming by remember { mutableStateOf(false) }
                var navigatedAway by remember { mutableStateOf(false) }

                LaunchedEffect(activeStream) {
                    val s = activeStream
                    if (s != null && s.isStreaming) {
                        wasStreaming = true
                    } else if (wasStreaming && navigatedAway && (s == null || !s.isStreaming)) {
                        finish()
                    }
                }

                // Fire the initial message once after the screen is ready
                LaunchedEffect(Unit) {
                    if (initialMessage != null) {
                        assistViewModel.setInitialMessage(initialMessage)
                    } else if (sharedContent != null) {
                        if (sharedContent.text != null) assistViewModel.setInitialComposing(sharedContent.text)
                        if (sharedContent.attachments.isNotEmpty()) assistViewModel.addAttachments(sharedContent.attachments)
                    }
                }

                AssistScreen(
                    screenshotPath = screenshotPath,
                    externalViewModel = assistViewModel,
                    onDismiss = { finish() },
                    onOpenFullScreen = { conversationId ->
                        navigatedAway = true
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                conversationId?.let { putExtra("conversation_id", it) }
                            }
                        )
                        moveTaskToBack(false)
                    }
                )
            }
        }
    }
}
