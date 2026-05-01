package org.opencrow.app.data.remote

import okhttp3.MultipartBody
import okhttp3.ResponseBody
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

    @POST("v1/auth/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<Unit>

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

    @POST("v1/conversations/{id}/tool-calls")
    suspend fun recordToolCall(
        @Path("id") conversationId: String,
        @Body body: RecordToolCallRequest
    ): Response<Unit>

    // ─── Orchestrator ───
    @POST("v1/orchestrator/complete")
    suspend fun complete(@Body body: CompleteRequest): Response<CompleteResponse>

    // ─── Voice ───
    @Multipart
    @POST("v1/voice/transcribe")
    suspend fun transcribe(@Part audio: MultipartBody.Part): Response<TranscribeResponse>

    @Streaming
    @POST("v1/voice/tts")
    suspend fun tts(@Body body: TtsRequest): Response<ResponseBody>

    @GET("v1/voice/tts/status")
    suspend fun ttsStatus(): Response<Map<String, String>>

    // ─── Devices ───
    @POST("v1/devices/{id}/register")
    suspend fun registerDevice(
        @Path("id") deviceId: String,
        @Body body: RegisterDeviceRequest
    ): Response<RegisterDeviceResponse>

    @DELETE("v1/devices/{id}")
    suspend fun deleteDevice(@Path("id") deviceId: String): Response<Unit>

    @GET("v1/devices/tasks")
    suspend fun getDeviceTasks(@Query("target") target: String): Response<DeviceTasksResponse>

    @GET("v1/devices/tasks/{id}")
    suspend fun getDeviceTask(@Path("id") taskId: String): Response<DeviceTaskDto>

    @POST("v1/devices/tasks/{id}/complete")
    suspend fun completeDeviceTask(
        @Path("id") taskId: String,
        @Body body: CompleteDeviceTaskRequest
    ): Response<Unit>

    // ─── Local Tool Results ───
    @POST("v1/tool-results/{callId}")
    suspend fun postToolResult(
        @Path("callId") callId: String,
        @Body body: ToolResultRequest
    ): Response<Unit>

    // ─── Config ───
    @GET("v1/config")
    suspend fun getConfig(): Response<UserConfigDto>

    @PUT("v1/config")
    suspend fun putConfig(@Body body: UserConfigDto): Response<UserConfigDto>

    // ─── Heartbeat Events ───
    @GET("v1/heartbeat/events")
    suspend fun getHeartbeatEvents(): Response<HeartbeatEventsResponse>

    @GET("v1/heartbeat")
    suspend fun getHeartbeatConfig(): Response<HeartbeatConfigDto>
}
