package org.opencrow.app

import android.content.Intent
import android.os.Bundle
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
        // Always start fresh when the activity is created
        assistViewModel.resetConversation()
        setupContent(intent?.getStringExtra("screenshot_path"))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Fresh OS trigger: reset conversation and update screenshot path
        assistViewModel.resetConversation()
        val screenshotPath = intent.getStringExtra("screenshot_path")
        assistViewModel.setScreenshotPath(screenshotPath)
    }

    private fun setupContent(screenshotPath: String?) {
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
