package org.opencrow.app.di

import android.content.Context
import org.opencrow.app.data.local.AppDatabase
import org.opencrow.app.data.remote.ApiClient
import org.opencrow.app.data.repository.ConfigRepository
import org.opencrow.app.data.repository.ConversationRepository

class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabase.getInstance(context)
    val apiClient: ApiClient = ApiClient(database.configDao())

    val conversationRepository: ConversationRepository by lazy {
        ConversationRepository(apiClient, database.conversationDao(), database.messageDao(), database.toolCallDao())
    }

    val configRepository: ConfigRepository by lazy {
        ConfigRepository(apiClient, database.configDao())
    }
}
