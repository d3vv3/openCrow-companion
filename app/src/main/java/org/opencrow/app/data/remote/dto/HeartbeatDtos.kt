package org.opencrow.app.data.remote.dto

// ─── Notifications / Heartbeat Events ───
data class HeartbeatEventsResponse(val events: List<HeartbeatEventDto>?)
data class HeartbeatEventDto(
    val id: String, val status: String, val message: String?, val createdAt: String
)
