package org.opencrow.app.data.remote.dto

// ─── Devices ───
/**
 * A capability (local tool) that this device can execute on behalf of the server.
 * [parameters] is a JSON Schema object (serialized as Map<String, Any> for Gson).
 */
data class DeviceCapability(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>? = null
)
data class RegisterDeviceRequest(
    val capabilities: List<DeviceCapability>,
    val pushEndpoint: String? = null,
    val pushAuth: String? = null
)
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
    /** If set, the device should execute this local tool instead of passing instruction to LLM. */
    val toolName: String?,
    val toolArguments: Map<String, Any>?,
    val status: String,
    val resultOutput: String?,
    val createdAt: String,
    val updatedAt: String,
    val expiresAt: String?
)
data class CompleteDeviceTaskRequest(val success: Boolean, val output: String)

// ─── Local Tool Results ───
data class ToolResultRequest(val output: String, val isError: Boolean = false)

// ─── Record Tool Call (device -> conversation) ───
data class RecordToolCallRequest(
    val name: String,
    val arguments: Map<String, Any>? = null,
    val output: String = "",
    val error: String = "",
    val durationMs: Long = 0,
    val source: String = "device"
)

// ─── Heartbeat Config ───
data class HeartbeatConfigDto(
    val userId: String?,
    val enabled: Boolean,
    val intervalSeconds: Int,
    val heartbeatPrompt: String?,
    val activeHoursStart: String?,
    val activeHoursEnd: String?,
    val timezone: String?
)
