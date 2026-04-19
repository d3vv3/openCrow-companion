package org.opencrow.app.data.repository

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.opencrow.app.data.local.ConversationDao
import org.opencrow.app.data.local.MessageDao
import org.opencrow.app.data.local.ToolCallDao
import org.opencrow.app.data.mapper.toCached
import org.opencrow.app.data.mapper.toDto
import org.opencrow.app.data.mapper.toRecordDto
import org.opencrow.app.data.remote.ApiClient
import org.opencrow.app.data.remote.StreamEvent
import org.opencrow.app.data.remote.StreamingClient
import org.opencrow.app.data.remote.dto.*

class ConversationRepository(
    private val apiClient: ApiClient,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val toolCallDao: ToolCallDao
) {
    companion object {
        private const val TAG = "ConversationRepo"
    }

    private val streamingClient = StreamingClient(apiClient)

    private val _refreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshSignal: SharedFlow<Unit> = _refreshSignal.asSharedFlow()

    fun notifyConversationsChanged() {
        _refreshSignal.tryEmit(Unit)
    }

    /**
     * Returns cached conversations first, then refreshes from network.
     * Returns a pair: (cached, fresh-or-null).
     */
    suspend fun loadConversations(): Pair<List<ConversationDto>, List<ConversationDto>?> {
        val cached = conversationDao.getAll().map { it.toDto() }.sortedByDescending { it.updatedAt }
        val fresh = try {
            val resp = apiClient.api.listConversations()
            if (resp.isSuccessful) {
                val list = resp.body()?.conversations.orEmpty().sortedByDescending { it.updatedAt }
                conversationDao.deleteAll()
                conversationDao.upsertAll(list.map { it.toCached() })
                list
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load conversations", e)
            null
        }
        return cached to fresh
    }

    suspend fun loadMessages(conversationId: String): Pair<List<MessageDto>, List<MessageDto>?> {
        val cached = messageDao.getByConversation(conversationId).map { it.toDto() }
        val fresh = try {
            val resp = apiClient.api.listMessages(conversationId)
            if (resp.isSuccessful) {
                val list = resp.body()?.messages.orEmpty()
                messageDao.deleteByConversation(conversationId)
                messageDao.upsertAll(list.map { it.toCached() })
                list
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load messages", e)
            null
        }
        return cached to fresh
    }

    suspend fun createConversation(title: String): ConversationDto? {
        return try {
            val resp = apiClient.api.createConversation(CreateConversationRequest(title))
            if (resp.isSuccessful) {
                val conv = resp.body()!!
                conversationDao.upsert(conv.toCached())
                conv
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create conversation", e)
            null
        }
    }

    suspend fun sendMessage(conversationId: String, text: String): CompleteResponse? {
        return try {
            val resp = apiClient.api.complete(CompleteRequest(conversationId, text))
            if (resp.isSuccessful) resp.body() else null
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            null
        }
    }

    suspend fun createMessage(conversationId: String, role: String, content: String): MessageDto? {
        return try {
            val resp = apiClient.api.createMessage(conversationId, CreateMessageRequest(role, content))
            if (resp.isSuccessful) resp.body() else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create message", e)
            null
        }
    }

    fun streamMessage(conversationId: String, text: String): kotlinx.coroutines.flow.Flow<StreamEvent> {
        return streamingClient.stream(CompleteRequest(conversationId, text))
    }

    suspend fun cacheMessage(message: MessageDto) {
        messageDao.upsert(message.toCached())
    }

    suspend fun loadToolCalls(conversationId: String): Pair<List<ToolCallRecordDto>, List<ToolCallRecordDto>?> {
        val cached = toolCallDao.getByConversation(conversationId).map { it.toRecordDto() }
        val fresh = try {
            val resp = apiClient.api.listToolCalls(conversationId)
            if (resp.isSuccessful) {
                val list = resp.body()?.toolCalls.orEmpty()
                toolCallDao.deleteByConversation(conversationId)
                toolCallDao.upsertAll(list.map { it.toCached(conversationId) })
                list
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tool calls", e)
            null
        }
        return cached to fresh
    }

    suspend fun cacheToolCalls(conversationId: String, toolCalls: List<ToolCallRecordDto>) {
        toolCallDao.upsertAll(toolCalls.map { it.toCached(conversationId) })
    }

    suspend fun updateCachedConversation(conversation: ConversationDto) {
        conversationDao.upsert(conversation.toCached())
    }

    suspend fun transcribeAudio(audioPart: okhttp3.MultipartBody.Part): String? {
        return try {
            val resp = apiClient.api.transcribe(audioPart)
            if (resp.isSuccessful) resp.body()?.transcript else null
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            null
        }
    }
}
