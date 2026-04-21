package org.opencrow.app.data.remote

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.opencrow.app.data.remote.dto.CompleteRequest
import org.opencrow.app.data.remote.dto.ToolCallDto
import java.util.concurrent.TimeUnit

/**
 * SSE events emitted by the server's /v1/orchestrator/stream endpoint.
 */
sealed class StreamEvent {
    data class Delta(val token: String) : StreamEvent()
    data class ToolCall(val name: String, val arguments: String, val kind: String) : StreamEvent()
    data class ToolResult(val name: String, val result: String) : StreamEvent()
    data class Done(val output: String) : StreamEvent()
    data class Error(val error: String) : StreamEvent()
}

class StreamingClient(private val apiClient: ApiClient) {

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout for SSE
        .build()

    fun stream(request: CompleteRequest): Flow<StreamEvent> = callbackFlow {
        val baseUrl = apiClient.getBaseUrl()
            ?: throw IllegalStateException("API not configured")
        val accessToken = apiClient.getAccessToken().orEmpty()

        val body = gson.toJson(request).toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/orchestrator/stream")
            .post(body)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Accept", "text/event-stream")
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val event = parseEvent(type, data) ?: return
                trySend(event)
                if (event is StreamEvent.Done || event is StreamEvent.Error) {
                    eventSource.cancel()
                    close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val msg = t?.message ?: response?.message ?: "Stream failed"
                trySend(StreamEvent.Error(msg))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val factory = EventSources.createFactory(client)
        val eventSource = factory.newEventSource(httpRequest, listener)

        awaitClose { eventSource.cancel() }
    }

    fun streamRegenerate(conversationId: String, messageId: String): Flow<StreamEvent> = callbackFlow {
        val baseUrl = apiClient.getBaseUrl()
            ?: throw IllegalStateException("API not configured")
        val accessToken = apiClient.getAccessToken().orEmpty()

        val emptyBody = ByteArray(0).toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/conversations/$conversationId/messages/$messageId/regenerate")
            .post(emptyBody)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Accept", "text/event-stream")
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val event = parseEvent(type, data) ?: return
                trySend(event)
                if (event is StreamEvent.Done || event is StreamEvent.Error) {
                    eventSource.cancel()
                    close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val msg = t?.message ?: response?.message ?: "Stream failed"
                trySend(StreamEvent.Error(msg))
                close()
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val factory = EventSources.createFactory(client)
        val eventSource = factory.newEventSource(httpRequest, listener)

        awaitClose { eventSource.cancel() }
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
                        result = map["result"] as? String ?: ""
                    )
                }
                "done" -> {
                    val map = parseMap(data)
                    StreamEvent.Done(map["output"] as? String ?: "")
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
