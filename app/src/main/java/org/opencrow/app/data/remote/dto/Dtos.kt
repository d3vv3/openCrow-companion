package org.opencrow.app.data.remote.dto

import com.google.gson.annotations.SerializedName

// ─── QR Payload ───
data class QrPayload(
    val id: String,
    val server: String,
    val accessToken: String,
    val refreshToken: String
)

// ─── Health ───
data class HealthResponse(val status: String?, val name: String?, val env: String?)

// ─── Auth ───
data class RefreshRequest(val refreshToken: String)
data class AuthResponse(val user: UserDto?, val tokens: TokensDto)
data class UserDto(val id: String, val username: String)
data class TokensDto(val accessToken: String, val refreshToken: String)

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

// ─── Devices ───
data class DeviceCapability(val name: String, val description: String)
data class RegisterDeviceRequest(val capabilities: List<DeviceCapability>)
data class RegisterDeviceResponse(
    val deviceId: String,
    val capabilities: List<DeviceCapability>,
    val status: String
)

// ─── Device Tasks ───
data class DeviceTasksResponse(val tasks: List<DeviceTaskDto>)
data class DeviceTaskDto(
    val id: String,
    val targetDevice: String,
    val instruction: String,
    val status: String,
    val resultOutput: String?,
    val createdAt: String,
    val updatedAt: String,
    val expiresAt: String?
)
data class CompleteDeviceTaskRequest(val success: Boolean, val output: String)

// ─── Config ───
data class UserConfigDto(
    val integrations: IntegrationsDto?,
    val tools: ToolsDto?,
    val mcp: McpDto?,
    val linuxSandbox: LinuxSandboxDto?,
    val llm: LlmDto?,
    val skills: SkillsDto?,
    val prompts: PromptsDto?,
    val memory: MemoryDto?,
    val schedules: SchedulesDto?,
    val heartbeat: HeartbeatDto?
)

data class IntegrationsDto(
    val emailAccounts: List<EmailAccountDto>?,
    val telegramBots: List<TelegramBotDto>?,
    val sshServers: List<SshServerDto>?,
    val companionApps: List<CompanionAppDto>?,
    val defaultNotificationBotId: String?
)

data class EmailAccountDto(
    val id: String?, val label: String, val address: String,
    val imapHost: String, val imapPort: Int, val imapUsername: String,
    val imapPassword: String?, val smtpHost: String, val smtpPort: Int,
    @SerializedName("useTls") val tls: Boolean, val enabled: Boolean,
    val pollIntervalSeconds: Int?
)

data class TelegramBotDto(
    val id: String?, val label: String, val botToken: String?,
    val allowedChatIds: List<String>?, val notificationChatId: String?,
    val enabled: Boolean, val pollIntervalSeconds: Int?
)

data class SshServerDto(
    val id: String?, val name: String, val host: String, val port: Int?,
    val username: String, val authMode: String, val enabled: Boolean
)

data class CompanionAppDto(
    val id: String?, val name: String, val label: String?, val enabled: Boolean
)

data class ToolsDto(
    val definitions: List<ToolDefinitionDto>?,
    val golangTools: List<GolangToolDto>?,
    val enabledTools: Map<String, Boolean>?,
    val enabled: Map<String, Boolean>?
)

data class ToolDefinitionDto(
    val id: String?, val name: String, val description: String,
    val source: String?, val parameters: List<ToolParameterDto>?
)

data class ToolParameterDto(
    val name: String, val type: String, val description: String, val required: Boolean
)

data class GolangToolDto(
    val id: String?, val name: String, val description: String,
    val sourceCode: String?, val enabled: Boolean
)

data class McpDto(val servers: List<McpServerDto>?)
data class McpServerDto(
    val id: String?, val name: String, val url: String,
    val headers: Map<String, String>?, val enabled: Boolean
)

data class LinuxSandboxDto(val enabled: Boolean?)

data class LlmDto(val providers: List<ProviderDto>?)
data class ProviderDto(
    val id: String?, val kind: String, val name: String,
    val baseUrl: String?, val apiKeyRef: String?, val model: String?,
    val enabled: Boolean, val priority: Int?
)

data class SkillsDto(val entries: List<SkillEntryDto>?)
data class SkillEntryDto(
    val id: String?, val name: String, val description: String,
    val content: String?, val enabled: Boolean
)

data class PromptsDto(val systemPrompt: String?, val heartbeatPrompt: String?)

data class MemoryDto(val entries: List<MemoryEntryDto>?)
data class MemoryEntryDto(
    val id: String?, val category: String, val content: String, val confidence: Int?
)

data class SchedulesDto(val entries: List<ScheduleEntryDto>?)
data class ScheduleEntryDto(
    val id: String?, val description: String, val status: String?,
    val executeAt: String?, val cronExpression: String?, val prompt: String?
)

data class HeartbeatDto(
    val enabled: Boolean?,
    val intervalSeconds: Int?,
    val model: String?,
    val activeHoursStart: String?,
    val activeHoursEnd: String?,
    val timezone: String?
)

// ─── Notifications / Heartbeat Events ───
data class HeartbeatEventsResponse(val events: List<HeartbeatEventDto>?)
data class HeartbeatEventDto(
    val id: String, val status: String, val message: String?, val createdAt: String
)
