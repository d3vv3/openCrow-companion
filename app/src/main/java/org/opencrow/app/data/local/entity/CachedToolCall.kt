package org.opencrow.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_tool_calls")
data class CachedToolCall(
    @PrimaryKey val id: String,
    val conversationId: String,
    val toolName: String,
    val kind: String?,
    val arguments: String?,
    val output: String?,
    val error: String?,
    val durationMs: Long?,
    val createdAt: String
)
