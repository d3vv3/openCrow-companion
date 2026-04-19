package org.opencrow.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_conversations")
data class CachedConversation(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val isAutomatic: Boolean = false,
    val automationKind: String? = null,
    val channel: String? = null
)
