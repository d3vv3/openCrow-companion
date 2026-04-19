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
import org.opencrow.app.data.remote.dto.ConversationDto
import org.opencrow.app.data.remote.dto.MessageDto
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
    val loadingMessages: Boolean = false,
    val showHistory: Boolean = false,
    val showSystemChats: Boolean = false,
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    val transcribedMessageIds: Set<String> = emptySet()
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
        }
    }

    fun updateComposing(text: String) {
        _uiState.update { it.copy(composing = text) }
    }

    fun toggleHistory(show: Boolean) {
        _uiState.update { it.copy(showHistory = show) }
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
                _uiState.update { it.copy(messages = it.messages + tempMsg) }

                // Call orchestrator
                val response = repository.sendMessage(convId, trimmed)
                if (response != null) {
                    val assistantMsg = MessageDto(
                        id = "asst-${System.currentTimeMillis()}",
                        conversationId = convId,
                        role = "assistant",
                        content = response.output,
                        createdAt = now
                    )

                    _uiState.update { state ->
                        val newTranscribed = if (isTranscribed) {
                            state.transcribedMessageIds + tempMsg.id
                        } else state.transcribedMessageIds

                        state.copy(
                            messages = state.messages + assistantMsg,
                            transcribedMessageIds = newTranscribed,
                            conversations = state.conversations.map { conv ->
                                if (conv.id == convId) conv.copy(updatedAt = now) else conv
                            }.sortedByDescending { it.updatedAt }
                        )
                    }

                    // Cache
                    repository.cacheMessage(tempMsg)
                    repository.cacheMessage(assistantMsg)
                    _uiState.value.conversations.find { it.id == convId }?.let {
                        repository.updateCachedConversation(it)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
            }
            _uiState.update { it.copy(sending = false) }
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
