package org.opencrow.app.ui.screens.assist

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.opencrow.app.data.remote.StreamEvent
import org.opencrow.app.data.remote.dto.MessageDto
import org.opencrow.app.data.repository.ConfigRepository
import org.opencrow.app.data.repository.ConversationRepository
import org.opencrow.app.di.ActiveStreamState
import org.opencrow.app.ui.screens.chat.Attachment
import org.opencrow.app.ui.screens.chat.ChatViewModel
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
    val attachScreenshot: Boolean = false,
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    val attachmentsByMessageId: Map<String, List<Attachment>> = emptyMap(),
    val pendingPermission: String? = null,
    val ttsEnabled: Boolean = false,
    val ttsAvailable: Boolean = false,
    val attachments: List<Attachment> = emptyList()
)

class AssistViewModel(
    private val repository: ConversationRepository,
    private val configRepository: ConfigRepository,
    private val appContext: Context
) : ViewModel() {

    companion object {
        private const val TAG = "AssistVM"
    }

    private val _uiState = MutableStateFlow(AssistUiState())
    val uiState: StateFlow<AssistUiState> = _uiState.asStateFlow()

    private val streamingBuffer = StringBuilder()
    private var mediaPlayer: MediaPlayer? = null

    // ─── Runtime permission helpers ──────────────────────────────────────────
    private var permissionDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    suspend fun requestPermission(permission: String): Boolean {
        if (androidx.core.content.ContextCompat.checkSelfPermission(appContext, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        permissionDeferred = deferred
        _uiState.update { it.copy(pendingPermission = permission) }
        return deferred.await()
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(pendingPermission = null) }
        permissionDeferred?.complete(granted)
        permissionDeferred = null
    }
    // ────────────────────────────────────────────────────────────────────────

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    init {
        // Restore persisted TTS preference
        val prefs = appContext.getSharedPreferences(ChatViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        _uiState.update { it.copy(ttsEnabled = prefs.getBoolean(ChatViewModel.PREF_TTS_ENABLED, false)) }
        checkApiReady()
        pollTtsStatus()
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

    private fun pollTtsStatus() {
        viewModelScope.launch {
            while (true) {
                try {
                    val available = repository.checkTtsStatus()
                    _uiState.update { it.copy(ttsAvailable = available) }
                } catch (_: Exception) {
                    _uiState.update { it.copy(ttsAvailable = false) }
                }
                delay(10_000)
            }
        }
    }

    fun toggleTts() {
        val wasEnabled = _uiState.value.ttsEnabled
        val newEnabled = !wasEnabled
        _uiState.update { it.copy(ttsEnabled = newEnabled) }
        appContext.getSharedPreferences(ChatViewModel.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(ChatViewModel.PREF_TTS_ENABLED, newEnabled).apply()
        if (wasEnabled) {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
            mediaPlayer = null
        }
    }

    fun speakText(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = repository.synthesizeSpeech(text) ?: return@launch
                val tmpFile = File(appContext.cacheDir, "assist_tts_${System.currentTimeMillis()}.mp3")
                tmpFile.writeBytes(bytes)
                withContext(Dispatchers.Main) {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(tmpFile.absolutePath)
                        prepare()
                        start()
                        setOnCompletionListener { tmpFile.delete() }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS playback failed", e)
            }
        }
    }

    fun resetConversation() {
        // Clear all conversation state so the next message starts fresh -- preserve TTS settings
        _uiState.update { current ->
            AssistUiState(
                apiReady = current.apiReady,
                screenshotPath = current.screenshotPath,
                screenshotAvailable = current.screenshotAvailable,
                ttsEnabled = current.ttsEnabled,
                ttsAvailable = current.ttsAvailable
                // attachments intentionally cleared
            )
        }
        streamingBuffer.clear()
        val app = appContext as org.opencrow.app.OpenCrowApp
        app.container.activeStream.value = null
    }

    fun setScreenshotPath(path: String?) {
        _uiState.update {
            it.copy(
                screenshotPath = path,
                screenshotAvailable = path != null,
                attachScreenshot = false // Always default to false when a new screenshot arrives
            )
        }
    }

    fun toggleAttachScreenshot(attach: Boolean) {
        _uiState.update { it.copy(attachScreenshot = attach) }
    }

    fun updateComposing(text: String) {
        _uiState.update { it.copy(composing = text) }
    }

    fun addAttachments(newAttachments: List<Attachment>) {
        _uiState.update { it.copy(attachments = it.attachments + newAttachments) }
    }

    fun removeAttachment(id: String) {
        _uiState.update { state -> state.copy(attachments = state.attachments.filter { it.id != id }) }
    }

    fun clearAttachments() {
        _uiState.update { it.copy(attachments = emptyList()) }
    }

    /**
     * Pre-fills the composing field and immediately sends the message.
     * Used when the activity is launched with a pre-determined prompt (e.g. Translate action).
     */
    fun setInitialMessage(text: String) {
        _uiState.update { it.copy(composing = text) }
        sendMessage(text)
    }

    /**
     * Pre-fills the composing field WITHOUT sending.
     * Used when the activity is launched via the share menu so the user can
     * optionally type a message before sending.
     */
    fun setInitialComposing(text: String) {
        _uiState.update { it.copy(composing = text) }
    }

    fun sendMessage(text: String = _uiState.value.composing) {
        val trimmed = text.trim()
        val pendingAttachments = _uiState.value.attachments
        if (trimmed.isBlank() && pendingAttachments.isEmpty()) return
        if (_uiState.value.sending || _uiState.value.streaming) return

        val shouldAttachScreenshot = _uiState.value.attachScreenshot && _uiState.value.screenshotPath != null
        _uiState.update { it.copy(sending = true, composing = "", error = null, attachments = emptyList()) }

        viewModelScope.launch {
            try {
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
                var convId = _uiState.value.conversationId

                // Create conversation if needed
                if (convId == null) {
                    val title = trimmed.take(50).ifBlank {
                        if (pendingAttachments.isNotEmpty()) "Shared ${pendingAttachments.size} file(s)"
                        else "New conversation"
                    }
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
                val screenshotBytes: ByteArray? = if (shouldAttachScreenshot) {
                    withContext(Dispatchers.IO) {
                        try {
                            val file = File(_uiState.value.screenshotPath!!)
                            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                            if (bitmap != null) {
                                val baos = ByteArrayOutputStream()
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                                bitmap.recycle()
                                baos.toByteArray()
                            } else null
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to read screenshot bytes", e)
                            null
                        }
                    }
                } else null

                val messageContent = if (shouldAttachScreenshot && pendingAttachments.isEmpty()) {
                    buildMessageWithScreenshot(trimmed, _uiState.value.screenshotPath!!)
                } else if (pendingAttachments.isNotEmpty()) {
                    val allAttachments = if (shouldAttachScreenshot) {
                        val screenshotAtt = Attachment(
                            uri = android.net.Uri.fromFile(File(_uiState.value.screenshotPath!!)),
                            name = "Screenshot",
                            mimeType = "image/jpeg"
                        )
                        listOf(screenshotAtt) + pendingAttachments
                    } else pendingAttachments
                    buildMessageWithAttachments(trimmed, allAttachments)
                } else {
                    trimmed
                }

                // Display content for the optimistic message (no base64 blob in text)
                val displayContent = buildString {
                    if (trimmed.isNotBlank()) append(trimmed)
                    for (att in pendingAttachments.filter { !it.isImage }) {
                        if (isNotEmpty()) append("\n")
                        append("📎 ${att.name}")
                    }
                }.ifBlank { trimmed }

                // Optimistic user message
                val tempMsg = MessageDto(
                    id = "temp-${System.currentTimeMillis()}",
                    conversationId = convId,
                    role = "user",
                    content = displayContent,
                    createdAt = now
                )
                _uiState.update {
                    val screenshotAttachment = if (screenshotBytes != null) {
                        Attachment(
                            uri = android.net.Uri.fromFile(File(_uiState.value.screenshotPath!!)),
                            name = "Screenshot",
                            mimeType = "image/jpeg",
                            bytes = screenshotBytes
                        )
                    } else null
                    val allVisualAttachments = listOfNotNull(screenshotAttachment) + pendingAttachments
                    it.copy(
                        messages = it.messages + tempMsg,
                        // Once sent, disable the screenshot toggle so it's not re-sent
                        attachScreenshot = false,
                        attachmentsByMessageId = if (allVisualAttachments.isNotEmpty())
                            it.attachmentsByMessageId + (tempMsg.id to allVisualAttachments)
                        else it.attachmentsByMessageId
                    )
                }

                // Persist user message
                val savedMsg = repository.createMessage(convId, "user", messageContent)
                if (savedMsg != null) {
                    _uiState.update { state ->
                        val newAttachmentMap = if (state.attachmentsByMessageId.containsKey(tempMsg.id)) {
                            state.attachmentsByMessageId - tempMsg.id + (savedMsg.id to state.attachmentsByMessageId[tempMsg.id]!!)
                        } else state.attachmentsByMessageId
                        state.copy(
                            messages = state.messages.map {
                                if (it.id == tempMsg.id) savedMsg.copy(content = displayContent) else it
                            },
                            attachmentsByMessageId = newAttachmentMap
                        )
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
                val app = appContext as org.opencrow.app.OpenCrowApp

                // Signal streaming start to ChatViewModel (if it's open)
                app.container.activeStream.value = ActiveStreamState(
                    conversationId = convId,
                    messageId = assistantId,
                    content = "",
                    isStreaming = true
                )

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
                                // Bridge to ChatViewModel
                                app.container.activeStream.value = ActiveStreamState(
                                    conversationId = convId,
                                    messageId = assistantId,
                                    content = content,
                                    isStreaming = true
                                )
                            }
                        }
                        is StreamEvent.Done -> {
                            streamingBuffer.clear()
                            val finalMsg = MessageDto(
                                id = assistantId,
                                conversationId = convId,
                                role = "assistant",
                                content = event.output,
                                createdAt = now
                            )
                            _uiState.update { state ->
                                state.copy(
                                    streaming = false,
                                    messages = state.messages.map { msg ->
                                        if (msg.id == assistantId) finalMsg else msg
                                    }
                                )
                            }
                            // Cache eagerly here -- before clearing activeStream, which triggers
                            // AssistActivity.finish() and could cancel the ViewModel coroutine.
                            repository.cacheMessage(finalMsg)
                            // Final bridge update then clear
                            app.container.activeStream.value = ActiveStreamState(
                                conversationId = convId,
                                messageId = assistantId,
                                content = event.output,
                                isStreaming = false
                            )
                            app.container.activeStream.value = null
                            // Speak the response if TTS is enabled
                            if (_uiState.value.ttsEnabled && event.output.isNotBlank()) {
                                speakText(event.output)
                            }
                        }
                        is StreamEvent.Error -> {
                            streamingBuffer.clear()
                            _uiState.update { state ->
                                state.copy(
                                    streaming = false,
                                    messages = state.messages.map { msg ->
                                        if (msg.id == assistantId && msg.content.isBlank()) {
                                            msg.copy(content = ":warning: ${event.error}")
                                        } else msg
                                    }
                                )
                            }
                            app.container.activeStream.value = null
                        }
                        is StreamEvent.ToolCall -> {}
                        is StreamEvent.ToolResult -> {}
                        is StreamEvent.ToolExecuteLocal -> {
                            val app2 = appContext as? org.opencrow.app.OpenCrowApp
                            if (app2 != null) {
                                val executor = org.opencrow.app.data.local.LocalToolExecutor(appContext, app2.container.apiClient, ::requestPermission)
                                val gson = com.google.gson.Gson()
                                val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
                                val argsMap: Map<String, Any> = gson.fromJson(event.arguments, type) ?: emptyMap()
                                viewModelScope.launch { executor.execute(event.callId, event.name, argsMap) }
                            }
                        }
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
        val parts = mutableListOf<String>()
        if (dataUri != null) parts.add("![screenshot]($dataUri)")
        if (text.isNotBlank()) parts.add(text)
        
        return parts.joinToString("\n\n")
    }

    private suspend fun buildMessageWithAttachments(
        text: String,
        attachments: List<Attachment>
    ): String {
        if (attachments.isEmpty()) return text
        val parts = mutableListOf<String>()
        for (attachment in attachments) {
            if (attachment.isImage) {
                val dataUri = withContext(Dispatchers.IO) {
                    try {
                        val bytes = appContext.contentResolver.openInputStream(attachment.uri)?.use {
                            it.readBytes()
                        } ?: return@withContext null
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val mime = attachment.mimeType ?: "image/png"
                        "data:$mime;base64,$base64"
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read attachment ${attachment.name}", e)
                        null
                    }
                }
                if (dataUri != null) parts.add("![${attachment.name}]($dataUri)")
            } else {
                // Non-image: embed as base64 data URI so the server can read it
                val dataUri = withContext(Dispatchers.IO) {
                    try {
                        val bytes = appContext.contentResolver.openInputStream(attachment.uri)?.use {
                            it.readBytes()
                        } ?: return@withContext null
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val mime = attachment.mimeType ?: "application/octet-stream"
                        "data:$mime;base64,$base64"
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read file attachment ${attachment.name}", e)
                        null
                    }
                }
                if (dataUri != null) {
                    parts.add("[${attachment.name}]($dataUri)")
                } else {
                    parts.add("[Attached file: ${attachment.name}]")
                }
            }
        }
        if (text.isNotBlank()) parts.add(text)
        return parts.joinToString("\n\n")
    }

    fun startRecording(context: Context) {
        val file = File(context.cacheDir, "assist_voice_${System.currentTimeMillis()}.m4a")
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
        _uiState.update { it.copy(recording = true) }
    }

    fun stopRecordingAndTranscribe() {
        _uiState.update { it.copy(recording = false) }
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        val file = audioFile ?: return
        _uiState.update { it.copy(transcribing = true) }
        viewModelScope.launch {
            try {
                val requestBody = file.asRequestBody("audio/mp4".toMediaType())
                val part = MultipartBody.Part.createFormData("audio", file.name, requestBody)
                val transcript = repository.transcribeAudio(part)
                if (!transcript.isNullOrBlank()) {
                    _uiState.update { it.copy(composing = transcript.trim()) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
            }
            _uiState.update { it.copy(transcribing = false) }
            file.delete()
        }
    }

    override fun onCleared() {
        super.onCleared()
        try { mediaRecorder?.release() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
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
