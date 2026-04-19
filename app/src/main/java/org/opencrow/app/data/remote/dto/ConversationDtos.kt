package org.opencrow.app.data.remote.dto

// ─── Conversations ───
data class ConversationsResponse(val conversations: List<ConversationDto>?)
data class ConversationDto(
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val isAutomatic: Boolean = false,
    val automationKind: String? = null,
    val channel: String? = null
)
data class CreateConversationRequest(val title: String)

// ─── Messages ───
data class MessagesResponse(val messages: List<MessageDto>?)
data class MessageDto(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: String
)
data class CreateMessageRequest(val role: String, val content: String)

// ─── Orchestrator ───
data class CompleteRequest(
    val conversationId: String,
    val message: String
)
data class CompleteResponse(
    val provider: String?,
    val output: String,
    val attempts: Int?,
    val usage: TokenUsageDto?
)
data class TokenUsageDto(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

// ─── Transcription ───
data class TranscribeResponse(val transcript: String)
