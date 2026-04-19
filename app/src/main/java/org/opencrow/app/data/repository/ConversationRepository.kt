package org.opencrow.app.data.repository

import android.util.Log
import org.opencrow.app.data.local.ConversationDao
import org.opencrow.app.data.local.MessageDao
import org.opencrow.app.data.mapper.toCached
import org.opencrow.app.data.mapper.toDto
import org.opencrow.app.data.remote.ApiClient
import org.opencrow.app.data.remote.dto.*

class ConversationRepository(
    private val apiClient: ApiClient,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    companion object {
        private const val TAG = "ConversationRepo"
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

    suspend fun cacheMessage(message: MessageDto) {
        messageDao.upsert(message.toCached())
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
