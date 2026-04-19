package org.opencrow.app.ui.screens.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.opencrow.app.data.remote.StreamEvent
import org.opencrow.app.data.remote.dto.ConversationDto
import org.opencrow.app.data.remote.dto.MessageDto
import org.opencrow.app.data.remote.dto.ToolCallDto
import org.opencrow.app.data.remote.dto.ToolCallRecordDto
import org.opencrow.app.data.repository.ConversationRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class ChatUiState(
    val conversations: List<ConversationDto> = emptyList(),
    val activeConversationId: String? = null,
    val messages: List<MessageDto> = emptyList(),
    val composing: String = "",
    val sending: Boolean = false,
    val streaming: Boolean = false,
    val refreshingMessages: Boolean = false,
    val loadingMessages: Boolean = false,
    val showHistory: Boolean = false,
    val showSystemChats: Boolean = false,
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    val transcribedMessageIds: Set<String> = emptySet(),
    val toolCallsByMessageId: Map<String, List<ToolCallDto>> = emptyMap()
)

class ChatViewModel(
    private val repository: ConversationRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ChatVM"
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    init {
        loadConversations()
        observeRefreshSignal()
    }

    private fun observeRefreshSignal() {
        viewModelScope.launch {
            repository.refreshSignal.collect {
                refreshConversations()
            }
        }
    }

    fun refreshConversations() {
        viewModelScope.launch {
            val (_, fresh) = repository.loadConversations()
            if (fresh != null) {
                _uiState.update { it.copy(conversations = fresh) }
            }
        }
    }

    private fun loadConversations() {
        viewModelScope.launch {
            val (cached, fresh) = repository.loadConversations()
            if (cached.isNotEmpty()) {
                _uiState.update { it.copy(conversations = cached) }
            }
            if (fresh != null) {
                _uiState.update { it.copy(conversations = fresh) }
            }
        }
    }

    fun selectConversation(id: String?) {
        _uiState.update { it.copy(activeConversationId = id, showHistory = false) }
        if (id != null) loadMessages(id)
        else _uiState.update { it.copy(messages = emptyList()) }
    }

    fun clearActiveConversation() {
        _uiState.update {
            it.copy(activeConversationId = null, messages = emptyList())
        }
    }

    private fun loadMessages(conversationId: String) {
        viewModelScope.launch {
            val (cached, fresh) = repository.loadMessages(conversationId)
            if (cached.isNotEmpty()) {
                _uiState.update { it.copy(messages = cached, loadingMessages = false) }
            } else {
                _uiState.update { it.copy(loadingMessages = true) }
            }
            if (fresh != null) {
                _uiState.update { it.copy(messages = fresh, loadingMessages = false) }
            } else {
                _uiState.update { it.copy(loadingMessages = false) }
            }

            // Load tool calls and associate with assistant messages
            val messages = _uiState.value.messages
            val (cachedCalls, freshCalls) = repository.loadToolCalls(conversationId)
            val toolCalls = freshCalls ?: cachedCalls
            if (toolCalls.isNotEmpty()) {
                _uiState.update { it.copy(toolCallsByMessageId = associateToolCalls(messages, toolCalls)) }
            }
        }
    }

    /**
     * Associates tool calls with the assistant message they belong to.
     * Tool calls that occurred between a user message and the next assistant message
     * are grouped under that assistant message's ID.
     */
    private fun associateToolCalls(
        messages: List<MessageDto>,
        toolCalls: List<ToolCallRecordDto>
    ): Map<String, List<ToolCallDto>> {
        if (toolCalls.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, MutableList<ToolCallDto>>()
        val assistantMessages = messages.filter { it.role == "assistant" }

        for (tc in toolCalls) {
            // Find the first assistant message whose createdAt >= tool call's createdAt
            val owner = assistantMessages.firstOrNull { it.createdAt >= tc.createdAt }
                ?: assistantMessages.lastOrNull()
            if (owner != null) {
                result.getOrPut(owner.id) { mutableListOf() }.add(
                    ToolCallDto(
                        name = tc.toolName,
                        arguments = tc.arguments,
                        status = if (tc.error != null) "error" else "success",
                        output = tc.error ?: tc.output
                    )
                )
            }
        }
        return result
    }

    fun refreshMessages() {
        val convId = _uiState.value.activeConversationId ?: return
        _uiState.update { it.copy(refreshingMessages = true) }
        viewModelScope.launch {
            val (_, fresh) = repository.loadMessages(convId)
            if (fresh != null) {
                _uiState.update { it.copy(messages = fresh, refreshingMessages = false) }
            } else {
                _uiState.update { it.copy(refreshingMessages = false) }
            }

            // Reload tool calls
            val messages = _uiState.value.messages
            val (_, freshCalls) = repository.loadToolCalls(convId)
            if (freshCalls != null) {
                _uiState.update { it.copy(toolCallsByMessageId = associateToolCalls(messages, freshCalls)) }
            }
        }
    }

    fun updateComposing(text: String) {
        _uiState.update { it.copy(composing = text) }
    }

    fun toggleHistory(show: Boolean) {
        _uiState.update { it.copy(showHistory = show) }
        if (show) refreshConversations()
    }

    fun toggleSystemChats(show: Boolean) {
        _uiState.update { it.copy(showSystemChats = show) }
    }

    fun sendMessage(text: String = _uiState.value.composing, isTranscribed: Boolean = false) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || _uiState.value.sending) return

        _uiState.update { it.copy(sending = true, composing = "") }

        viewModelScope.launch {
            try {
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
                var convId = _uiState.value.activeConversationId

                // Create conversation if needed
                if (convId == null) {
                    val newConv = repository.createConversation(trimmed.take(50))
                    if (newConv != null) {
                        convId = newConv.id
                        _uiState.update {
                            it.copy(
                                activeConversationId = convId,
                                conversations = listOf(newConv) + it.conversations
                            )
                        }
                    } else {
                        _uiState.update { it.copy(sending = false) }
                        return@launch
                    }
                }

                // Optimistic user message
                val tempMsg = MessageDto(
                    id = "temp-${System.currentTimeMillis()}",
                    conversationId = convId,
                    role = "user",
                    content = trimmed,
                    createdAt = now
                )
                if (isTranscribed) {
                    _uiState.update {
                        it.copy(
                            messages = it.messages + tempMsg,
                            transcribedMessageIds = it.transcribedMessageIds + tempMsg.id
                        )
                    }
                } else {
                    _uiState.update { it.copy(messages = it.messages + tempMsg) }
                }

                // Streaming assistant message placeholder
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

                // Collect tool calls during streaming
                val streamToolCalls = mutableListOf<ToolCallDto>()
                var pendingToolCall: String? = null

                repository.streamMessage(convId, trimmed).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> {
                            _uiState.update { state ->
                                state.copy(
                                    messages = state.messages.map { msg ->
                                        if (msg.id == assistantId) msg.copy(content = msg.content + event.token)
                                        else msg
                                    }
                                )
                            }
                        }
                        is StreamEvent.ToolCall -> {
                            pendingToolCall = event.name
                            val dto = ToolCallDto(
                                name = event.name,
                                arguments = parseToolArguments(event.arguments),
                                status = "running",
                                output = null
                            )
                            streamToolCalls.add(dto)
                            _uiState.update { state ->
                                state.copy(
                                    toolCallsByMessageId = state.toolCallsByMessageId +
                                        (assistantId to streamToolCalls.toList())
                                )
                            }
                        }
                        is StreamEvent.ToolResult -> {
                            val idx = streamToolCalls.indexOfLast { it.name == event.name }
                            if (idx >= 0) {
                                streamToolCalls[idx] = streamToolCalls[idx].copy(
                                    status = "success",
                                    output = event.result
                                )
                                _uiState.update { state ->
                                    state.copy(
                                        toolCallsByMessageId = state.toolCallsByMessageId +
                                            (assistantId to streamToolCalls.toList())
                                    )
                                }
                            }
                            pendingToolCall = null
                        }
                        is StreamEvent.Done -> {
                            // Replace content with final output
                            _uiState.update { state ->
                                state.copy(
                                    streaming = false,
                                    messages = state.messages.map { msg ->
                                        if (msg.id == assistantId) msg.copy(content = event.output)
                                        else msg
                                    },
                                    conversations = state.conversations.map { conv ->
                                        if (conv.id == convId) conv.copy(updatedAt = now) else conv
                                    }.sortedByDescending { it.updatedAt }
                                )
                            }
                        }
                        is StreamEvent.Error -> {
                            Log.e(TAG, "Stream error: ${event.error}")
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
                    }
                }

                // Cache final messages and tool calls
                repository.cacheMessage(tempMsg)
                _uiState.value.messages.find { it.id == assistantId }?.let {
                    repository.cacheMessage(it)
                }
                if (streamToolCalls.isNotEmpty()) {
                    val now2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
                    val recordDtos = streamToolCalls.mapIndexed { index, tc ->
                        ToolCallRecordDto(
                            id = "tc-${System.currentTimeMillis()}-$index",
                            toolName = tc.name,
                            kind = null,
                            arguments = tc.arguments,
                            output = tc.output,
                            error = if (tc.status == "error") tc.output else null,
                            durationMs = null,
                            createdAt = now2
                        )
                    }
                    repository.cacheToolCalls(convId, recordDtos)
                }
                _uiState.value.conversations.find { it.id == convId }?.let {
                    repository.updateCachedConversation(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                _uiState.update { it.copy(streaming = false) }
            }
            _uiState.update { it.copy(sending = false, streaming = false) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseToolArguments(json: String): Map<String, Any>? {
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type
            com.google.gson.Gson().fromJson<Map<String, Any>>(json, type)
        } catch (_: Exception) {
            null
        }
    }

    fun startRecording(context: Context) {
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
                    sendMessage(transcript, isTranscribed = true)
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
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {}
    }

    class Factory(private val repository: ConversationRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(repository) as T
        }
    }
}
