package org.opencrow.app.data.remote.dto

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
