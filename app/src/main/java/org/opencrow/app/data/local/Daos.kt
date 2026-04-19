package org.opencrow.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.opencrow.app.data.local.entity.AppConfig
import org.opencrow.app.data.local.entity.CachedConversation
import org.opencrow.app.data.local.entity.CachedMessage
import org.opencrow.app.data.local.entity.CachedToolCall

@Dao
interface ConfigDao {
    @Query("SELECT value FROM app_config WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(config: AppConfig)

    @Query("DELETE FROM app_config WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM cached_conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CachedConversation>>

    @Query("SELECT * FROM cached_conversations ORDER BY updatedAt DESC")
    suspend fun getAll(): List<CachedConversation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(conversations: List<CachedConversation>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: CachedConversation)

    @Query("DELETE FROM cached_conversations")
    suspend fun deleteAll()

    @Query("DELETE FROM cached_conversations WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM cached_messages WHERE conversationId = :convId ORDER BY createdAt ASC")
    fun observeByConversation(convId: String): Flow<List<CachedMessage>>

    @Query("SELECT * FROM cached_messages WHERE conversationId = :convId ORDER BY createdAt ASC")
    suspend fun getByConversation(convId: String): List<CachedMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<CachedMessage>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: CachedMessage)

    @Query("DELETE FROM cached_messages WHERE conversationId = :convId")
    suspend fun deleteByConversation(convId: String)
}

@Dao
interface ToolCallDao {
    @Query("SELECT * FROM cached_tool_calls WHERE conversationId = :convId ORDER BY createdAt ASC")
    suspend fun getByConversation(convId: String): List<CachedToolCall>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(toolCalls: List<CachedToolCall>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(toolCall: CachedToolCall)

    @Query("DELETE FROM cached_tool_calls WHERE conversationId = :convId")
    suspend fun deleteByConversation(convId: String)
}
