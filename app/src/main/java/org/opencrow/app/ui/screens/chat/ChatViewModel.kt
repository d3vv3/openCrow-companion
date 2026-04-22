package org.opencrow.app.ui.screens.chat

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
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
import org.json.JSONObject

data class Attachment(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val isImage: Boolean = mimeType?.startsWith("image/") == true,
    /** Decoded image bytes for data: URIs (Coil cannot load data: URIs directly). */
    val bytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Attachment) return false
        return id == other.id && uri == other.uri && name == other.name &&
                mimeType == other.mimeType && isImage == other.isImage &&
                bytes.contentEquals(other.bytes ?: ByteArray(0))
    }
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        result = 31 * result + isImage.hashCode()
        result = 31 * result + (bytes?.contentHashCode() ?: 0)
        return result
    }
}

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
    val toolCallsByMessageId: Map<String, List<ToolCallDto>> = emptyMap(),
    val attachments: List<Attachment> = emptyList(),
    val attachmentsByMessageId: Map<String, List<Attachment>> = emptyMap()
)

class ChatViewModel(
    private val repository: ConversationRepository,
    private val appContext: Context
) : ViewModel() {

    companion object {
        private const val TAG = "ChatVM"
    }

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    /**
     * Accumulates streaming tokens without copying the entire messages list
     * on every delta. Flushed to UI state periodically.
     */
    private val streamingBuffer = StringBuilder()
    private var streamingAssistantId: String? = null

    init {
        loadConversations()
        observeRefreshSignal()
        observeActiveStream()
    }

    private fun observeActiveStream() {
        val app = appContext as? org.opencrow.app.OpenCrowApp ?: return
        viewModelScope.launch {
            app.container.activeStream.collect { streamState ->
                val convId = _uiState.value.activeConversationId ?: return@collect
                if (streamState == null || streamState.conversationId != convId) return@collect

                val msgId = streamState.messageId
                val existing = _uiState.value.messages.find { it.id == msgId }
                if (existing != null) {
                    // Update content of existing placeholder
                    _uiState.update { state ->
                        state.copy(
                            streaming = streamState.isStreaming,
                            messages = state.messages.map { msg ->
                                if (msg.id == msgId) msg.copy(content = streamState.content) else msg
                            }
                        )
                    }
                } else if (streamState.isStreaming) {
                    // Add new placeholder (ChatViewModel opened the conversation after streaming started)
                    val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date())
                    val placeholder = org.opencrow.app.data.remote.dto.MessageDto(
                        id = msgId,
                        conversationId = convId,
                        role = "assistant",
                        content = streamState.content,
                        createdAt = now
                    )
                    _uiState.update { state ->
                        state.copy(
                            streaming = true,
                            messages = state.messages + placeholder
                        )
                    }
                }
            }
        }
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
        // Snapshot the active stream messageId at load time so we know what to preserve
        val app = appContext as? org.opencrow.app.OpenCrowApp
        val activeStreamAtLoad = app?.container?.activeStream?.value
            ?.takeIf { it.conversationId == conversationId }

        viewModelScope.launch {
            val (cached, fresh) = repository.loadMessages(conversationId)
            if (cached.isNotEmpty()) {
                val processedCached = processMessages(cached)
                _uiState.update { state ->
                    val freshIds = processedCached.map { it.id }.toSet()
                    // Only preserve the active streaming placeholder, nothing else
                    val streamingPlaceholder = activeStreamAtLoad?.messageId?.let { id ->
                        state.messages.find { it.id == id && it.id !in freshIds }
                    }
                    state.copy(
                        messages = processedCached + listOfNotNull(streamingPlaceholder),
                        loadingMessages = false
                    )
                }
            } else {
                _uiState.update { it.copy(loadingMessages = true) }
            }
            if (fresh != null) {
                val processedFresh = processMessages(fresh)
                _uiState.update { state ->
                    val freshIds = processedFresh.map { it.id }.toSet()
                    val streamingPlaceholder = activeStreamAtLoad?.messageId?.let { id ->
                        state.messages.find { it.id == id && it.id !in freshIds }
                    }
                    state.copy(
                        messages = processedFresh + listOfNotNull(streamingPlaceholder),
                        loadingMessages = false
                    )
                }
            } else {
                _uiState.update { it.copy(loadingMessages = false) }
            }

            // If no streaming placeholder is present yet, inject one now
            val streamState = app?.container?.activeStream?.value
            if (streamState != null && streamState.conversationId == conversationId) {
                val msgId = streamState.messageId
                val alreadyPresent = _uiState.value.messages.any { it.id == msgId }
                if (!alreadyPresent) {
                    val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).format(java.util.Date())
                    val placeholder = MessageDto(
                        id = msgId,
                        conversationId = conversationId,
                        role = "assistant",
                        content = streamState.content,
                        createdAt = now
                    )
                    _uiState.update { state ->
                        state.copy(streaming = streamState.isStreaming, messages = state.messages + placeholder)
                    }
                }
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
     * Extracts attachments from markdown content and populates attachmentsByMessageId.
     * Returns a list of messages with cleaned content.
     */
    private fun processMessages(messages: List<MessageDto>): List<MessageDto> {
        val attachmentsMap = mutableMapOf<String, List<Attachment>>()
        val processed = messages.map { msg ->
            if (msg.role == "user") {
                val (cleanedContent, attachments) = extractAttachmentsFromMarkdown(msg.content)
                if (attachments.isNotEmpty()) {
                    attachmentsMap[msg.id] = attachments
                }
                msg.copy(content = cleanedContent)
            } else {
                msg
            }
        }
        if (attachmentsMap.isNotEmpty()) {
            _uiState.update { it.copy(attachmentsByMessageId = it.attachmentsByMessageId + attachmentsMap) }
        }
        return processed
    }

    private fun extractAttachmentsFromMarkdown(content: String): Pair<String, List<Attachment>> {
        val attachments = mutableListOf<Attachment>()
        // Match ![name](data:image/...)
        val imagePattern = Regex("!\\[(.*?)\\]\\((data:image/[^;]+;base64,[^\\)]+)\\)")
        // Match [Attached file: name]
        val filePattern = Regex("\\[Attached file: (.*?)\\]")
        
        var cleanedContent = content
        
        imagePattern.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val dataUri = match.groupValues[2]
            val mimeType = dataUri.substringAfter("data:").substringBefore(";")
            // Decode base64 bytes so Coil can render them (Coil does not support data: URIs)
            val bytes = try {
                val base64Part = dataUri.substringAfter("base64,")
                Base64.decode(base64Part, Base64.DEFAULT)
            } catch (_: Exception) { null }
            attachments.add(Attachment(
                uri = Uri.parse(dataUri),
                name = name,
                mimeType = mimeType,
                bytes = bytes
            ))
            cleanedContent = cleanedContent.replace(match.value, "")
        }
        
        filePattern.findAll(cleanedContent).forEach { match ->
            // We keep file attachments as placeholders in the text if they were sent that way
            // or we could extract them too. Currently MessageBubble handles them via 'attachments'
        }
        
        return cleanedContent.trim() to attachments
    }

    /**
     * Associates tool calls with the assistant message they belong to.
     * Tool calls that occurred between a user message and the next assistant message
     * are grouped under that assistant message's ID.
     * Uses binary search for O(n log m) instead of O(n*m).
     */
    private fun associateToolCalls(
        messages: List<MessageDto>,
        toolCalls: List<ToolCallRecordDto>
    ): Map<String, List<ToolCallDto>> {
        if (toolCalls.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, MutableList<ToolCallDto>>()
        val assistantMessages = messages.filter { it.role == "assistant" }
        if (assistantMessages.isEmpty()) return emptyMap()

        // Pre-sort assistant messages by createdAt for binary search
        val sortedAssistants = assistantMessages.sortedBy { it.createdAt }
        val timestamps = sortedAssistants.map { it.createdAt }

        for (tc in toolCalls) {
            // Binary search for insertion point of tc.createdAt in sorted assistant timestamps.
            // Tool calls happen mid-stream, so their createdAt is AFTER the owning assistant's
            // createdAt (which is set at stream start). We want the LAST assistant before the
            // tool call, i.e. sortedAssistants[idx - 1].
            var idx = timestamps.binarySearch(tc.createdAt)
            if (idx < 0) idx = -(idx + 1) // convert to insertion point
            val ownerIdx = (idx - 1).coerceAtLeast(0)
            val owner = sortedAssistants[ownerIdx]

            result.getOrPut(owner.id) { mutableListOf() }.add(
                ToolCallDto(
                    name = tc.toolName,
                    arguments = tc.arguments,
                    status = if (tc.error != null) "error" else "success",
                    output = tc.error ?: tc.output
                )
            )
        }
        return result
    }

    fun refreshMessages() {
        val convId = _uiState.value.activeConversationId ?: return
        _uiState.update { it.copy(refreshingMessages = true) }
        viewModelScope.launch {
            val (_, fresh) = repository.loadMessages(convId)
            if (fresh != null) {
                val processedFresh = processMessages(fresh)
                _uiState.update { it.copy(messages = processedFresh, refreshingMessages = false) }
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

    fun addAttachments(newAttachments: List<Attachment>) {
        _uiState.update { it.copy(attachments = it.attachments + newAttachments) }
    }

    fun removeAttachment(id: String) {
        _uiState.update { state ->
            state.copy(attachments = state.attachments.filter { it.id != id })
        }
    }

    fun clearAttachments() {
        _uiState.update { it.copy(attachments = emptyList()) }
    }

    fun toggleHistory(show: Boolean) {
        _uiState.update { it.copy(showHistory = show) }
        if (show) refreshConversations()
    }

    fun toggleSystemChats(show: Boolean) {
        _uiState.update { it.copy(showSystemChats = show) }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            repository.deleteConversation(id)
            _uiState.update { state ->
                val newConversations = state.conversations.filter { it.id != id }
                val wasActive = state.activeConversationId == id
                state.copy(
                    conversations = newConversations,
                    activeConversationId = if (wasActive) null else state.activeConversationId,
                    messages = if (wasActive) emptyList() else state.messages
                )
            }
        }
    }

    fun regenerateMessage(messageId: String) {
        val convId = _uiState.value.activeConversationId ?: return
        if (_uiState.value.sending || _uiState.value.streaming) return

        _uiState.update { state ->
            state.copy(
                streaming = true,
                messages = state.messages.map { msg ->
                    if (msg.id == messageId) msg.copy(content = "") else msg
                },
                toolCallsByMessageId = state.toolCallsByMessageId - messageId
            )
        }
        streamingBuffer.clear()
        streamingAssistantId = messageId

        viewModelScope.launch {
            try {
                val streamToolCalls = mutableListOf<ToolCallDto>()
                var lastFlushTime = System.currentTimeMillis()

                repository.streamRegenerate(convId, messageId).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> {
                            streamingBuffer.append(event.token)
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastFlushTime >= 80) {
                                lastFlushTime = nowMs
                                val currentContent = streamingBuffer.toString()
                                _uiState.update { state ->
                                    state.copy(
                                        messages = state.messages.map { msg ->
                                            if (msg.id == messageId) msg.copy(content = currentContent) else msg
                                        }
                                    )
                                }
                            }
                        }
                        is StreamEvent.ToolCall -> {
                            val dto = ToolCallDto(
                                name = event.name,
                                arguments = parseToolArguments(event.arguments),
                                status = "running",
                                output = null
                            )
                            streamToolCalls.add(dto)
                            _uiState.update { state ->
                                state.copy(toolCallsByMessageId = state.toolCallsByMessageId + (messageId to streamToolCalls.toList()))
                            }
                        }
                        is StreamEvent.ToolResult -> {
                            val idx = streamToolCalls.indexOfLast { it.name == event.name }
                            if (idx >= 0) {
                                val errored = isToolResultError(event.result)
                                streamToolCalls[idx] = streamToolCalls[idx].copy(
                                    status = if (errored) "error" else "success",
                                    output = event.result
                                )
                                _uiState.update { state ->
                                    state.copy(toolCallsByMessageId = state.toolCallsByMessageId + (messageId to streamToolCalls.toList()))
                                }
                            }
                        }
                        is StreamEvent.Done -> {
                            streamingBuffer.clear()
                            streamingAssistantId = null
                            _uiState.update { state ->
                                state.copy(
                                    streaming = false,
                                    messages = state.messages.map { msg ->
                                        if (msg.id == messageId) msg.copy(content = event.output) else msg
                                    }
                                )
                            }
                        }
                        is StreamEvent.Error -> {
                            Log.e(TAG, "Regenerate stream error: ${event.error}")
                            streamingBuffer.clear()
                            streamingAssistantId = null
                            _uiState.update { state ->
                                state.copy(
                                    streaming = false,
                                    messages = state.messages.map { msg ->
                                        if (msg.id == messageId && msg.content.isBlank()) msg.copy(content = ":warning:️ ${event.error}") else msg
                                    }
                                )
                            }
                        }
                    }
                }

                // Cache the regenerated message
                _uiState.value.messages.find { it.id == messageId }?.let {
                    repository.cacheMessage(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Regenerate failed", e)
                _uiState.update { it.copy(streaming = false) }
            }
            _uiState.update { it.copy(sending = false, streaming = false) }
        }
    }

    fun sendMessage(text: String = _uiState.value.composing, isTranscribed: Boolean = false) {
        val trimmed = text.trim()
        val pendingAttachments = _uiState.value.attachments
        if (trimmed.isBlank() && pendingAttachments.isEmpty()) return
        if (_uiState.value.sending) return

        _uiState.update { it.copy(sending = true, composing = "", attachments = emptyList()) }

        viewModelScope.launch {
            try {
                val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
                var convId = _uiState.value.activeConversationId

                // Create conversation if needed
                if (convId == null) {
                    val title = trimmed.take(50).ifBlank {
                        if (pendingAttachments.isNotEmpty()) "Shared ${pendingAttachments.size} file(s)"
                        else "New conversation"
                    }
                    val newConv = repository.createConversation(title)
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

                // Build message content with embedded attachments
                val messageContent = buildMessageWithAttachments(trimmed, pendingAttachments)

                // Display-friendly content for the optimistic message (no base64 blobs)
                // Images are rendered visually via attachmentsByMessageId, so only show file names
                val displayContent = buildString {
                    if (trimmed.isNotBlank()) append(trimmed)
                    for (att in pendingAttachments.filter { !it.isImage }) {
                        if (isNotEmpty()) append("\n")
                        append("📎 ${att.name}")
                    }
                }

                // Optimistic user message
                val tempMsg = MessageDto(
                    id = "temp-${System.currentTimeMillis()}",
                    conversationId = convId,
                    role = "user",
                    content = displayContent,
                    createdAt = now
                )
                if (isTranscribed) {
                    _uiState.update {
                        it.copy(
                            messages = it.messages + tempMsg,
                            transcribedMessageIds = it.transcribedMessageIds + tempMsg.id,
                            attachmentsByMessageId = if (pendingAttachments.isNotEmpty())
                                it.attachmentsByMessageId + (tempMsg.id to pendingAttachments)
                            else it.attachmentsByMessageId
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            messages = it.messages + tempMsg,
                            attachmentsByMessageId = if (pendingAttachments.isNotEmpty())
                                it.attachmentsByMessageId + (tempMsg.id to pendingAttachments)
                            else it.attachmentsByMessageId
                        )
                    }
                }

                // Persist user message on the server (matches web client behaviour)
                val savedMsg = repository.createMessage(convId, "user", messageContent)
                if (savedMsg != null) {
                    _uiState.update { state ->
                        val newAttachmentMap = if (pendingAttachments.isNotEmpty()) {
                            state.attachmentsByMessageId - tempMsg.id + (savedMsg.id to pendingAttachments)
                        } else state.attachmentsByMessageId
                        state.copy(
                            messages = state.messages.map { if (it.id == tempMsg.id) savedMsg.copy(content = displayContent) else it },
                            transcribedMessageIds = if (isTranscribed)
                                state.transcribedMessageIds - tempMsg.id + savedMsg.id
                            else state.transcribedMessageIds,
                            attachmentsByMessageId = newAttachmentMap
                        )
                    }
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
                streamingAssistantId = assistantId
                streamingBuffer.clear()
                var lastFlushTime = System.currentTimeMillis()

                repository.streamMessage(convId, messageContent).collect { event ->
                    when (event) {
                        is StreamEvent.Delta -> {
                            streamingBuffer.append(event.token)
                            // Batch UI updates: flush every 80ms instead of per-token
                            val now3 = System.currentTimeMillis()
                            if (now3 - lastFlushTime >= 80) {
                                lastFlushTime = now3
                                val currentContent = streamingBuffer.toString()
                                _uiState.update { state ->
                                    state.copy(
                                        messages = state.messages.map { msg ->
                                            if (msg.id == assistantId) msg.copy(content = currentContent)
                                            else msg
                                        }
                                    )
                                }
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
                                val errored = isToolResultError(event.result)
                                streamToolCalls[idx] = streamToolCalls[idx].copy(
                                    status = if (errored) "error" else "success",
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
                            // Flush any remaining buffered content, then replace with final output
                            streamingBuffer.clear()
                            streamingAssistantId = null
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
                            streamingBuffer.clear()
                            streamingAssistantId = null
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

    /**
     * Builds the final message content by embedding image attachments as
     * markdown data-URI images. The server's OpenAI provider parses
     * `![name](data:image/...;base64,...)` into multimodal content blocks.
     */
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
                if (dataUri != null) {
                    parts.add("![${attachment.name}]($dataUri)")
                }
            } else {
                parts.add("[Attached file: ${attachment.name}]")
            }
        }

        if (text.isNotBlank()) parts.add(text)

        return parts.joinToString("\n\n")
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

    class Factory(private val repository: ConversationRepository, private val appContext: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(repository, appContext) as T
        }
    }
}

/**
 * Heuristically determines if a tool result string represents an error.
 *
 * The server can return errors in two forms:
 * 1. MCP / built-in tools that return structured JSON: {"success": false, "error": "..."}
 * 2. Go execution errors surfaced as plain strings (e.g. "connection refused")
 *
 * For (1) we parse JSON and check for success:false or a top-level "error" key.
 * For (2) we rely on the persisted ToolCallRecordDto.error field (set by the server
 * after the stream completes) which is correctly mapped in associateToolCalls().
 */
internal fun isToolResultError(result: String): Boolean {
    val trimmed = result.trim()
    // Plain-string error patterns (non-JSON responses)
    if (trimmed.startsWith("MCP error", ignoreCase = true)) return true
    if (trimmed.startsWith("Error:", ignoreCase = true)) return true
    if (trimmed.startsWith("{")) {
        return try {
            val obj = JSONObject(trimmed)
            val successVal = if (obj.has("success")) obj.get("success") else null
            when {
                successVal == false || successVal == "false" -> true
                obj.has("error") && successVal != true && successVal != "true" -> true
                else -> false
            }
        } catch (_: Exception) { false }
    }
    return false
}
