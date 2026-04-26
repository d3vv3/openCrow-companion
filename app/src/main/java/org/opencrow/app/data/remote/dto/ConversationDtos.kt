package org.opencrow.app.data.remote.dto

import androidx.compose.runtime.Immutable

// ─── Conversations ───
data class ConversationsResponse(val conversations: List<ConversationDto>?)

@Immutable
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

@Immutable
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
    val message: String,
    val deviceId: String? = null  // sent so server can inject local tool specs
)
data class CompleteResponse(
    val provider: String?,
    val output: String,
    val attempts: Int?,
    val trace: CompletionTraceDto?,
    val usage: TokenUsageDto?
)
data class CompletionTraceDto(
    val providerAttempts: List<ProviderAttemptDto>?,
    val toolCalls: List<ToolCallDto>?,
    val runtimeActions: List<RuntimeActionDto>?
)
data class ProviderAttemptDto(
    val provider: String,
    val attempt: Int,
    val success: Boolean,
    val error: String?
)

@Immutable
data class ToolCallDto(
    val name: String,
    val arguments: Map<String, Any>?,
    val status: String,
    val output: String?
)
data class RuntimeActionDto(
    val kind: String,
    val command: String?,
    val status: String,
    val output: String?
)
data class TokenUsageDto(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

// ─── Tool Calls ───
data class ToolCallsResponse(val toolCalls: List<ToolCallRecordDto>?)
data class ToolCallRecordDto(
    val id: String,
    val messageId: String?,
    val toolName: String,
    val kind: String?,
    val arguments: Map<String, Any>?,
    val output: String?,
    val error: String?,
    val durationMs: Long?,
    val createdAt: String
)

// ─── Transcription ───
data class TranscribeResponse(val transcript: String)

// ─── TTS ───
data class TtsRequest(val text: String, val voice: String? = null)
