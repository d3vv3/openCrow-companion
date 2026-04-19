package org.opencrow.app.ui.screens.assist

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencrow.app.data.remote.StreamEvent
import org.opencrow.app.data.remote.dto.MessageDto
import org.opencrow.app.data.repository.ConfigRepository
import org.opencrow.app.data.repository.ConversationRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class AssistUiState(
    val messages: List<MessageDto> = emptyList(),
    val composing: String = "",
    val sending: Boolean = false,
    val streaming: Boolean = false,
    val conversationId: String? = null,
    val error: String? = null,
    val apiReady: Boolean = false,
    val screenshotPath: String? = null,
    val screenshotAvailable: Boolean = false,
    val attachScreenshot: Boolean = false
)

class AssistViewModel(
    private val repository: ConversationRepository,
    private val configRepository: ConfigRepository,
    private val appContext: Context
) : ViewModel() {

    companion object {
        private const val TAG = "AssistVM"
        private const val PREF_ATTACH_SCREENSHOT = "assist_attach_screenshot"
    }

    private val _uiState = MutableStateFlow(AssistUiState())
    val uiState: StateFlow<AssistUiState> = _uiState.asStateFlow()

    private val streamingBuffer = StringBuilder()

    /** Cached user preference — null until loaded from DB */
    private var savedScreenshotPref: Boolean? = null

    init {
        checkApiReady()
        loadScreenshotPreference()
    }

    private fun loadScreenshotPreference() {
        viewModelScope.launch {
            val saved = configRepository.getLocalSetting(PREF_ATTACH_SCREENSHOT)
            savedScreenshotPref = saved?.toBooleanStrictOrNull() ?: true
            // Apply to current state if a screenshot path is already set
            if (_uiState.value.screenshotPath != null) {
                _uiState.update { it.copy(attachScreenshot = savedScreenshotPref!!) }
            }
        }
    }

    private fun checkApiReady() {
        viewModelScope.launch {
            try {
                val app = appContext as org.opencrow.app.OpenCrowApp
                app.container.apiClient.initialize()
                _uiState.update { it.copy(apiReady = app.container.apiClient.isConfigured) }
            } catch (e: Exception) {
                Log.e(TAG, "API init failed", e)
                _uiState.update { it.copy(apiReady = false, error = "Not paired yet") }
            }
        }
    }

    fun setScreenshotPath(path: String?) {
        val pref = savedScreenshotPref ?: true
        _uiState.update {
            it.copy(
                screenshotPath = path,
                screenshotAvailable = path != null,
                attachScreenshot = path != null && pref
            )
        }
    }

    fun toggleAttachScreenshot(attach: Boolean) {
        _uiState.update { it.copy(attachScreenshot = attach) }
        viewModelScope.launch {
            configRepository.setLocalSetting(PREF_ATTACH_SCREENSHOT, attach.toString())
            savedScreenshotPref = attach
        }
    }

    fun updateComposing(text: String) {
        _uiState.update { it.copy(composing = text) }
    }

    fun sendMessage(text: String = _uiState.value.composing) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (_uiState.value.sending || _uiState.value.streaming) return

        val shouldAttachScreenshot = _uiState.value.attachScreenshot && _uiState.value.screenshotPath != null
        _uiState.update { it.copy(sending = true, composing = "", error = null) }

        viewModelScope.launch {
            try {
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
                var convId = _uiState.value.conversationId

                // Create conversation if needed
                if (convId == null) {
                    val title = trimmed.take(50)
                    val newConv = repository.createConversation(title)
                    if (newConv != null) {
                        convId = newConv.id
                        _uiState.update { it.copy(conversationId = convId) }
                    } else {
                        _uiState.update { it.copy(sending = false, error = "Failed to create conversation") }
                        return@launch
                    }
                }

                // Build message content, optionally embedding the screenshot
                val messageContent = if (shouldAttachScreenshot) {
                    buildMessageWithScreenshot(trimmed, _uiState.value.screenshotPath!!)
                } else {
                    trimmed
                }

                // Display content for the optimistic message (no base64)
                val displayContent = if (shouldAttachScreenshot) {
                    "$trimmed\n📎 Screenshot"
                } else {
                    trimmed
                }

                // Optimistic user message
                val tempMsg = MessageDto(
                    id = "temp-${System.currentTimeMillis()}",
                    conversationId = convId,
                    role = "user",
                    content = displayContent,
                    createdAt = now
                )
                _uiState.update {
                    it.copy(
                        messages = it.messages + tempMsg,
                        // Once sent, disable the screenshot toggle so it's not re-sent
                        attachScreenshot = false
                    )
                }

                // Persist user message
                val savedMsg = repository.createMessage(convId, "user", messageContent)
                if (savedMsg != null) {
                    _uiState.update { state ->
                        state.copy(messages = state.messages.map {
                            if (it.id == tempMsg.id) savedMsg.copy(content = displayContent) else it
                        })
                    }
                }

                // Streaming assistant placeholder
                val assistantId = "asst-${System.currentTimeMillis()}"
                val assistantMsg = MessageDto(
                    id = assistantId,
                    conversationId = convId,
                    role = "assistant",
                    content = "",
                    createdAt = now
                )
                _uiState.update {
                    it.copy(
                        messages = it.messages + assistantMsg,
                        sending = false,
                        streaming = true
                    )
                }

                streamingBuffer.clear()
                var lastFlushTime = System.currentTimeMillis()

                repository.streamMessage(convId, messageContent).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> {
                            streamingBuffer.append(event.token)
                            val now2 = System.currentTimeMillis()
                            if (now2 - lastFlushTime >= 80) {
                                lastFlushTime = now2
                                val content = streamingBuffer.toString()
                                _uiState.update { state ->
                                    state.copy(messages = state.messages.map { msg ->
                                        if (msg.id == assistantId) msg.copy(content = content) else msg
                                    })
                                }
                            }
                        }
                        is StreamEvent.Done -> {
                            streamingBuffer.clear()
                            _uiState.update { state ->
                                state.copy(
                                    streaming = false,
                                    messages = state.messages.map { msg ->
                                        if (msg.id == assistantId) msg.copy(content = event.output) else msg
                                    }
                                )
                            }
                        }
                        is StreamEvent.Error -> {
                            streamingBuffer.clear()
                            _uiState.update { state ->
                                state.copy(
                                    streaming = false,
                                    messages = state.messages.map { msg ->
                                        if (msg.id == assistantId && msg.content.isBlank()) {
                                            msg.copy(content = "⚠ ${event.error}")
                                        } else msg
                                    }
                                )
                            }
                        }
                        is StreamEvent.ToolCall -> {}
                        is StreamEvent.ToolResult -> {}
                    }
                }

                // Cache messages
                repository.cacheMessage(tempMsg)
                _uiState.value.messages.find { it.id == assistantId }?.let {
                    repository.cacheMessage(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                _uiState.update { it.copy(error = "Send failed: ${e.message}") }
            }
            _uiState.update { it.copy(sending = false, streaming = false) }
        }
    }

    /**
     * Reads the screenshot file and embeds it as a data-URI markdown image,
     * matching the format the server expects for multimodal content.
     */
    private suspend fun buildMessageWithScreenshot(text: String, path: String): String {
        val dataUri = withContext(Dispatchers.IO) {
            try {
                val file = File(path)
                if (!file.exists()) return@withContext null
                val bitmap = BitmapFactory.decodeFile(path) ?: return@withContext null
                val baos = ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                bitmap.recycle()
                val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                "data:image/jpeg;base64,$base64"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encode screenshot", e)
                null
            }
        }
        return if (dataUri != null) {
            "$text\n\n![screenshot]($dataUri)"
        } else {
            text
        }
    }

    class Factory(
        private val repository: ConversationRepository,
        private val configRepository: ConfigRepository,
        private val appContext: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AssistViewModel(repository, configRepository, appContext) as T
        }
    }
}
