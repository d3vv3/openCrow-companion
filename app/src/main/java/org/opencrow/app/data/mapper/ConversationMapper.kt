package org.opencrow.app.data.mapper

import org.opencrow.app.data.local.entity.CachedConversation
import org.opencrow.app.data.local.entity.CachedMessage
import org.opencrow.app.data.remote.dto.ConversationDto
import org.opencrow.app.data.remote.dto.MessageDto

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
