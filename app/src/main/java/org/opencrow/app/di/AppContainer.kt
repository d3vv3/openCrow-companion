package org.opencrow.app.di

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.opencrow.app.data.local.AppDatabase
import org.opencrow.app.data.local.LocalToolCapabilities
import org.opencrow.app.data.local.LocalToolExecutor
import org.opencrow.app.data.remote.ApiClient
import org.opencrow.app.data.repository.ConfigRepository
import org.opencrow.app.data.repository.ConversationRepository

/**
 * Carries the in-flight streaming state from AssistViewModel to ChatViewModel
 * across the Activity boundary. Lives in the Application so it is never
 * destroyed during activity transitions.
 */
data class ActiveStreamState(
    val conversationId: String,
    val messageId: String,
    val content: String,
    val isStreaming: Boolean
)

class AppContainer(context: Context) {
    /** Shared stream bridge: AssistViewModel writes, ChatViewModel reads. */
    val activeStream = MutableStateFlow<ActiveStreamState?>(null)
    val database: AppDatabase = AppDatabase.getInstance(context)
    val apiClient: ApiClient = ApiClient(database.configDao())
    val localToolCapabilities = LocalToolCapabilities
    val localToolExecutor: LocalToolExecutor = LocalToolExecutor(context, apiClient)

    val conversationRepository: ConversationRepository by lazy {
        ConversationRepository(apiClient, database.conversationDao(), database.messageDao(), database.toolCallDao())
    }

    val configRepository: ConfigRepository by lazy {
        ConfigRepository(apiClient, database.configDao())
    }
}
