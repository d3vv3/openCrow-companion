package org.opencrow.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.opencrow.app.ui.navigation.AppNavHost
import org.opencrow.app.ui.theme.OpenCrowTheme

class MainActivity : ComponentActivity() {

    private var pendingConversationId by mutableStateOf<String?>(null)

    // Permissions that require a runtime grant (dangerous permissions only).
    // Normal permissions (INTERNET, VIBRATE, MODIFY_AUDIO_SETTINGS, etc.) are
    // granted automatically and must not appear in this list.
    private val runtimePermissions: Array<String> = buildList {
        // Microphone (voice input)
        add(Manifest.permission.RECORD_AUDIO)
        // Contacts
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.WRITE_CONTACTS)
        // Calendar
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        // Phone
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CALL_LOG)
        // SMS
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_SMS)
        // Location
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        // Camera (flashlight requires camera)
        add(Manifest.permission.CAMERA)
        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Media (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        }
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Results are handled silently; tools that need a specific permission
            // check it before use and return a meaningful error if denied.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingConversationId = intent?.getStringExtra("conversation_id")

        // Ask for all runtime permissions on first launch.
        permissionLauncher.launch(runtimePermissions)

        setContent {
            OpenCrowTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(
                        pendingConversationId = pendingConversationId,
                        onConversationOpened = { pendingConversationId = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingConversationId = intent.getStringExtra("conversation_id")
    }
}
