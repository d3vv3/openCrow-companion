package org.opencrow.app.ui.screens.onboarding

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencrow.app.OpenCrowApp
import org.unifiedpush.android.connector.UnifiedPush

// ── Data model ───────────────────────────────────────────────────────────────

sealed interface OnboardingMessage {
    data class Ai(val text: String) : OnboardingMessage
    data class User(val text: String) : OnboardingMessage
}

data class OnboardingAction(val label: String, val tag: String)

data class OnboardingUiState(
    val messages: List<OnboardingMessage> = emptyList(),
    /** null = idle, "" = thinking, non-empty = streaming chars so far */
    val streamingText: String? = null,
    val actions: List<OnboardingAction>? = null,
    /** Non-null when the Composable should launch the system permission dialog. */
    val pendingPermissions: List<String>? = null,
    val done: Boolean = false
)

// ── Permission step descriptors ───────────────────────────────────────────────

private data class PermStep(
    val permissions: List<String>,
    val rationale: String,
    val userLabel: String          // text shown in the user-side bubble when allowed
)

private val PERM_STEPS: List<PermStep> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(PermStep(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            "To send you alerts and reminders, I need permission to show **notifications**.",
            "Allow notifications"
        ))
    }
    add(PermStep(
        listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
        "With access to your **contacts** I can look people up, create new entries, and help you stay in touch.",
        "Allow contacts"
    ))
    add(PermStep(
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        "**Calendar** access lets me read your schedule and create events on your behalf.",
        "Allow calendar"
    ))
    add(PermStep(
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        "**Location** access enables me to answer questions like \"where am I?\" and give context-aware help.",
        "Allow location"
    ))
    add(PermStep(
        listOf(Manifest.permission.RECORD_AUDIO),
        "**Microphone** access lets you talk to me using voice input.",
        "Allow microphone"
    ))
    add(PermStep(
        listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG),
        "**Phone** access lets me place calls and check your recent call history for you.",
        "Allow phone"
    ))
    add(PermStep(
        listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS),
        "**SMS** access lets me read messages and help you compose or send texts.",
        "Allow messages"
    ))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(PermStep(
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO),
            "**Media** access lets me read photos and videos when you ask me to work with them.",
            "Allow media"
        ))
    } else {
        add(PermStep(
            @Suppress("DEPRECATION")
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE),
            "**Storage** access lets me read photos and files when you ask me to work with them.",
            "Allow storage"
        ))
    }
    add(PermStep(
        listOf(Manifest.permission.CAMERA),
        "**Camera** access is required to use the flashlight and to take photos.",
        "Allow camera"
    ))
}

// ── ViewModel ────────────────────────────────────────────────────────────────

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx: Context get() = getApplication<OpenCrowApp>()
    private val apiClient get() = (getApplication<OpenCrowApp>()).container.apiClient

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    // Remaining permission steps to process
    private val pendingSteps = ArrayDeque<PermStep>()
    private var currentStep: PermStep? = null

    fun start() {
        viewModelScope.launch {
            // Initial wave from user
            addUserMessage("👋")
            delay(300)

            // Build the list of steps that still need granting
            val needed = PERM_STEPS.filter { step ->
                step.permissions.any { perm ->
                    ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED
                }
            }
            pendingSteps.addAll(needed)

            // Welcome
            typeMessage("Hi! I'm setting up a few things before we get started.")
            delay(400)

            if (pendingSteps.isEmpty()) {
                // Check UnifiedPush even if permissions are all granted
                continueToUnifiedPush()
            } else {
                typeMessage("I'd like to request some permissions so I can help you fully. You can skip any you don't want to grant.")
                delay(300)
                advanceToNextPermStep()
            }
        }
    }

    /** Called by the Composable when the user taps an action button. */
    fun onAction(tag: String) {
        _state.update { it.copy(actions = null) }
        viewModelScope.launch {
            when {
                tag == "allow" -> {
                    // Trigger the system permission dialog via pendingPermissions
                    val step = currentStep ?: return@launch
                    _state.update { it.copy(pendingPermissions = step.permissions) }
                    // The Composable will call onPermissionResult when done
                }
                tag == "skip" -> {
                    addUserMessage("Skip")
                    delay(200)
                    advanceToNextPermStep()
                }
                tag.startsWith("up_setup:") -> {
                    val pkg = tag.removePrefix("up_setup:")
                    addUserMessage("Set up push notifications")
                    delay(200)
                    setupUnifiedPush(pkg)
                }
                tag == "up_skip" -> {
                    addUserMessage("Skip")
                    delay(200)
                    finish()
                }
            }
        }
    }

    /** Called by the Composable after the system permission dialog returns. */
    fun onPermissionResult(results: Map<String, Boolean>) {
        _state.update { it.copy(pendingPermissions = null) }
        viewModelScope.launch {
            val allGranted = results.values.all { it }
            val step = currentStep
            if (step != null) {
                addUserMessage(if (allGranted) step.userLabel else "Skip")
            }
            delay(200)
            advanceToNextPermStep()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun advanceToNextPermStep() {
        if (pendingSteps.isEmpty()) {
            continueToUnifiedPush()
            return
        }
        currentStep = pendingSteps.removeFirst()
        val step = currentStep!!
        typeMessage(step.rationale)
        _state.update {
            it.copy(
                actions = listOf(
                    OnboardingAction("Allow", "allow"),
                    OnboardingAction("Skip", "skip")
                )
            )
        }
    }

    private suspend fun continueToUnifiedPush() {
        val distributors = UnifiedPush.getDistributors(ctx)
        if (distributors.isEmpty()) {
            typeMessage("You're all set! Let's get started.")
            delay(300)
            markDoneAndFinish()
            return
        }

        val active = UnifiedPush.getAckDistributor(ctx)
        if (!active.isNullOrBlank()) {
            // Already configured
            typeMessage("You're all set! Let's get started.")
            delay(300)
            markDoneAndFinish()
            return
        }

        typeMessage("I noticed you have a push notification app installed. Would you like to set it up so I can reach you even when the app is closed?")

        val actions = buildList {
            distributors.forEach { pkg ->
                val label = friendlyDistributorName(pkg)
                add(OnboardingAction("Use $label", "up_setup:$pkg"))
            }
            add(OnboardingAction("Skip", "up_skip"))
        }
        _state.update { it.copy(actions = actions) }
    }

    private suspend fun setupUnifiedPush(pkg: String) {
        UnifiedPush.saveDistributor(ctx, pkg)
        val deviceId = apiClient.getDeviceId() ?: ""
        if (deviceId.isNotBlank()) {
            UnifiedPush.register(ctx, instance = deviceId)
        }
        typeMessage("Push notifications configured! You're all set.")
        delay(300)
        markDoneAndFinish()
    }

    private suspend fun finish() {
        typeMessage("No problem. You're all set! Let's get started.")
        delay(300)
        markDoneAndFinish()
    }

    private suspend fun markDoneAndFinish() {
        apiClient.setOnboardingDone()
        _state.update { it.copy(done = true) }
    }

    private suspend fun typeMessage(text: String) {
        // Show thinking indicator first
        _state.update { it.copy(streamingText = "") }
        delay(400)

        // Stream word by word (skip empty tokens from multiple spaces)
        val words = text.split(" ").filter { it.isNotEmpty() }
        val sb = StringBuilder()
        for ((index, word) in words.withIndex()) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(word)
            _state.update { it.copy(streamingText = sb.toString()) }
            // Vary delay slightly: shorter for common short words, longer for longer words
            val delayMs = when {
                word.length <= 2 -> 40L
                word.length <= 4 -> 60L
                else -> 80L + (word.length * 3L).coerceAtMost(60L)
            }
            delay(delayMs)
        }

        // Commit the full message
        _state.update { s ->
            s.copy(
                streamingText = null,
                messages = s.messages + OnboardingMessage.Ai(text)
            )
        }
    }

    private fun addUserMessage(text: String) {
        _state.update { s ->
            s.copy(messages = s.messages + OnboardingMessage.User(text))
        }
    }

    private fun friendlyDistributorName(pkg: String): String {
        return when {
            pkg.contains("ntfy", ignoreCase = true) -> "ntfy"
            pkg.contains("gotify", ignoreCase = true) -> "Gotify"
            pkg.contains("nextpush", ignoreCase = true) -> "NextPush"
            pkg.contains("nopush", ignoreCase = true) -> "NoPush"
            pkg.contains("up-example", ignoreCase = true) -> "UP Example"
            else -> pkg.substringAfterLast('.')
        }
    }
}
