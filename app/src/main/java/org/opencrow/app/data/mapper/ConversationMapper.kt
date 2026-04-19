package org.opencrow.app.data.mapper

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.opencrow.app.data.local.entity.CachedConversation
import org.opencrow.app.data.local.entity.CachedMessage
import org.opencrow.app.data.local.entity.CachedToolCall
import org.opencrow.app.data.remote.dto.ConversationDto
import org.opencrow.app.data.remote.dto.MessageDto
import org.opencrow.app.data.remote.dto.ToolCallRecordDto

fun ConversationDto.toCached() = CachedConversation(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isAutomatic = isAutomatic,
    automationKind = automationKind,
    channel = channel
)

fun CachedConversation.toDto() = ConversationDto(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isAutomatic = isAutomatic,
    automationKind = automationKind,
    channel = channel
)

fun MessageDto.toCached() = CachedMessage(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    createdAt = createdAt
)

fun CachedMessage.toDto() = MessageDto(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    createdAt = createdAt
)

private val gson = Gson()
private val mapType = object : TypeToken<Map<String, Any>>() {}.type

fun ToolCallRecordDto.toCached(conversationId: String) = CachedToolCall(
    id = id,
    conversationId = conversationId,
    toolName = toolName,
    kind = kind,
    arguments = arguments?.let { gson.toJson(it) },
    output = output,
    error = error,
    durationMs = durationMs,
    createdAt = createdAt
)

fun CachedToolCall.toRecordDto() = ToolCallRecordDto(
    id = id,
    toolName = toolName,
    kind = kind,
    arguments = arguments?.let { runCatching { gson.fromJson<Map<String, Any>>(it, mapType) }.getOrNull() },
    output = output,
    error = error,
    durationMs = durationMs,
    createdAt = createdAt
)
