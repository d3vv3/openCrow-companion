package org.opencrow.app.data.remote.dto

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
