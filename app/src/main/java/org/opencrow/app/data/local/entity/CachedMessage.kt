package org.opencrow.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cached_messages",
    indices = [Index(value = ["conversationId"])]
)
data class CachedMessage(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: String,
    val isTranscribed: Boolean = false
)
