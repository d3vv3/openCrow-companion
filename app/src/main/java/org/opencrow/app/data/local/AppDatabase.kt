package org.opencrow.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import org.opencrow.app.data.local.entity.AppConfig
import org.opencrow.app.data.local.entity.CachedConversation
import org.opencrow.app.data.local.entity.CachedMessage
import org.opencrow.app.data.local.entity.CachedToolCall

@Database(
    entities = [AppConfig::class, CachedConversation::class, CachedMessage::class, CachedToolCall::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun configDao(): ConfigDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun toolCallDao(): ToolCallDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "opencrow.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
