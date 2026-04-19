package org.opencrow.app.data.remote

import okhttp3.MultipartBody
import org.opencrow.app.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface OpenCrowApi {

    // ─── Health ───
    @GET("healthz")
    suspend fun health(): Response<HealthResponse>

    // ─── Auth ───
    @POST("v1/auth/refresh")
    suspend fun refreshToken(@Body body: RefreshRequest): Response<AuthResponse>

    // ─── Conversations ───
    @GET("v1/conversations")
    suspend fun listConversations(): Response<ConversationsResponse>

    @POST("v1/conversations")
    suspend fun createConversation(@Body body: CreateConversationRequest): Response<ConversationDto>

    @DELETE("v1/conversations/{id}")
    suspend fun deleteConversation(@Path("id") id: String): Response<Unit>

    // ─── Messages ───
    @GET("v1/conversations/{id}/messages")
    suspend fun listMessages(@Path("id") conversationId: String): Response<MessagesResponse>

    @POST("v1/conversations/{id}/messages")
    suspend fun createMessage(
        @Path("id") conversationId: String,
        @Body body: CreateMessageRequest
    ): Response<MessageDto>

    // ─── Tool Calls ───
    @GET("v1/conversations/{id}/tool-calls")
    suspend fun listToolCalls(@Path("id") conversationId: String): Response<ToolCallsResponse>

    // ─── Orchestrator ───
    @POST("v1/orchestrator/complete")
    suspend fun complete(@Body body: CompleteRequest): Response<CompleteResponse>

    // ─── Voice ───
    @Multipart
    @POST("v1/voice/transcribe")
    suspend fun transcribe(@Part audio: MultipartBody.Part): Response<TranscribeResponse>

    // ─── Devices ───
    @POST("v1/devices/{id}/register")
    suspend fun registerDevice(
        @Path("id") deviceId: String,
        @Body body: RegisterDeviceRequest
    ): Response<RegisterDeviceResponse>

    @GET("v1/devices/tasks")
    suspend fun getDeviceTasks(@Query("target") target: String): Response<DeviceTasksResponse>

    @POST("v1/devices/tasks/{id}/complete")
    suspend fun completeDeviceTask(
        @Path("id") taskId: String,
        @Body body: CompleteDeviceTaskRequest
    ): Response<Unit>

    // ─── Config ───
    @GET("v1/config")
    suspend fun getConfig(): Response<UserConfigDto>

    @PUT("v1/config")
    suspend fun putConfig(@Body body: UserConfigDto): Response<UserConfigDto>

    // ─── Heartbeat Events ───
    @GET("v1/heartbeat/events")
    suspend fun getHeartbeatEvents(): Response<HeartbeatEventsResponse>
}
