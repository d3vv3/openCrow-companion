package org.opencrow.app.data.remote

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.opencrow.app.data.remote.dto.CompleteRequest
import java.util.concurrent.TimeUnit

/**
 * SSE events emitted by the server's /v1/orchestrator/stream endpoint.
 */
sealed class StreamEvent {
    data class Delta(val token: String) : StreamEvent()
    data class ToolCall(val name: String, val arguments: String, val kind: String) : StreamEvent()
    data class ToolResult(val name: String, val result: String, val isError: Boolean = false) : StreamEvent()
    /** Server wants the device to execute a local tool and return the result via POST /v1/tool-results/{callId} */
    data class ToolExecuteLocal(val callId: String, val name: String, val arguments: String) : StreamEvent()
    data class Done(val output: String, val messageId: String? = null) : StreamEvent()
    data class Error(val error: String) : StreamEvent()
}

private const val MAX_RETRIES = 3
private const val RETRY_BASE_DELAY_MS = 1_500L

class StreamingClient(private val apiClient: ApiClient) {

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for SSE
        .build()

    fun stream(request: CompleteRequest): Flow<StreamEvent> = callbackFlow {
        val baseUrl = apiClient.getBaseUrl()
            ?: throw IllegalStateException("API not configured")

        val body = gson.toJson(request).toRequestBody("application/json".toMediaType())

        var attempt = 0
        var eventSource: EventSource? = null

        fun connect() {
            // Always read the latest token so a refresh that happened between retries is used.
            val accessToken = apiClient.getAccessToken().orEmpty()
            val httpRequest = Request.Builder()
                .url("$baseUrl/v1/orchestrator/stream")
                .post(body)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Accept", "text/event-stream")
                .build()

            val listener = object : EventSourceListener() {
                override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
                    attempt = 0 // reset retry counter on successful data
                    val event = parseEvent(type, data) ?: return
                    trySend(event)
                    if (event is StreamEvent.Done || event is StreamEvent.Error) {
                        source.cancel()
                        close()
                    }
                }

                override fun onFailure(source: EventSource, t: Throwable?, response: Response?) {
                    val code = response?.code
                    val bodyText = try { response?.peekBody(4096)?.string() } catch (_: Exception) { null }
                    android.util.Log.e("StreamingClient", "stream failure $code: $bodyText / ${t?.message}")

                    // Only retry on network-level failures (no HTTP response), not on 4xx/5xx errors.
                    val isNetworkError = code == null
                    if (isNetworkError && attempt < MAX_RETRIES) {
                        attempt++
                        val delayMs = RETRY_BASE_DELAY_MS * attempt
                        android.util.Log.i("StreamingClient", "Retrying stream (attempt $attempt) in ${delayMs}ms")
                        launch {
                            delay(delayMs)
                            if (!isClosedForSend) connect()
                        }
                    } else {
                        val msg = bodyText?.takeIf { it.isNotBlank() } ?: t?.message ?: response?.message ?: "Stream failed"
                        trySend(StreamEvent.Error(msg))
                        close()
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }
            }

            val factory = EventSources.createFactory(client)
            eventSource = factory.newEventSource(httpRequest, listener)
        }

        connect()
        awaitClose { eventSource?.cancel() }
    }

    fun streamRegenerate(conversationId: String, messageId: String): Flow<StreamEvent> = callbackFlow {
        val baseUrl = apiClient.getBaseUrl()
            ?: throw IllegalStateException("API not configured")

        var attempt = 0
        var eventSource: EventSource? = null

        fun connect() {
            val accessToken = apiClient.getAccessToken().orEmpty()
            val emptyBody = ByteArray(0).toRequestBody("application/json".toMediaType())
            val httpRequest = Request.Builder()
                .url("$baseUrl/v1/conversations/$conversationId/messages/$messageId/regenerate")
                .post(emptyBody)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Accept", "text/event-stream")
                .build()

            val listener = object : EventSourceListener() {
                override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
                    attempt = 0
                    val event = parseEvent(type, data) ?: return
                    trySend(event)
                    if (event is StreamEvent.Done || event is StreamEvent.Error) {
                        source.cancel()
                        close()
                    }
                }

                override fun onFailure(source: EventSource, t: Throwable?, response: Response?) {
                    val code = response?.code
                    val bodyText = try { response?.peekBody(4096)?.string() } catch (_: Exception) { null }
                    android.util.Log.e("StreamingClient", "regenerate failure $code: $bodyText / ${t?.message}")

                    val isNetworkError = code == null
                    if (isNetworkError && attempt < MAX_RETRIES) {
                        attempt++
                        val delayMs = RETRY_BASE_DELAY_MS * attempt
                        android.util.Log.i("StreamingClient", "Retrying regenerate (attempt $attempt) in ${delayMs}ms")
                        launch {
                            delay(delayMs)
                            if (!isClosedForSend) connect()
                        }
                    } else {
                        val msg = bodyText?.takeIf { it.isNotBlank() } ?: t?.message ?: response?.message ?: "Stream failed"
                        trySend(StreamEvent.Error(msg))
                        close()
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }
            }

            val factory = EventSources.createFactory(client)
            eventSource = factory.newEventSource(httpRequest, listener)
        }

        connect()
        awaitClose { eventSource?.cancel() }
    }

    private fun parseEvent(type: String?, data: String): StreamEvent? {
        return try {
            when (type) {
                "delta" -> {
                    val map = parseMap(data)
                    StreamEvent.Delta(map["token"] as? String ?: "")
                }
                "tool_call" -> {
                    val map = parseMap(data)
                    StreamEvent.ToolCall(
                        name = map["name"] as? String ?: "",
                        arguments = map["arguments"] as? String ?: "{}",
                        kind = map["kind"] as? String ?: ""
                    )
                }
                "tool_result" -> {
                    val map = parseMap(data)
                    StreamEvent.ToolResult(
                        name = map["name"] as? String ?: "",
                        result = map["result"] as? String ?: "",
                        isError = map["isError"] == "true"
                    )
                }
                "tool_execute_local" -> {
                    val map = parseMap(data)
                    StreamEvent.ToolExecuteLocal(
                        callId = map["callId"] as? String ?: "",
                        name = map["name"] as? String ?: "",
                        arguments = map["arguments"] as? String ?: "{}"
                    )
                }
                "done" -> {
                    val map = parseMap(data)
                    StreamEvent.Done(
                        output = map["output"] as? String ?: "",
                        messageId = map["messageId"] as? String ?: map["message_id"] as? String
                    )
                }
                "error" -> {
                    val map = parseMap(data)
                    StreamEvent.Error(map["error"] as? String ?: "Unknown error")
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMap(json: String): Map<String, Any> {
        val type = object : TypeToken<Map<String, Any>>() {}.type
        return gson.fromJson(json, type) ?: emptyMap()
    }
}
