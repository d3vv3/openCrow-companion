package org.opencrow.app.data.remote.dto

import com.google.gson.annotations.SerializedName

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
