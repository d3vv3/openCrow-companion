package org.opencrow.app.data.repository

import android.util.Log
import org.opencrow.app.data.local.ConfigDao
import org.opencrow.app.data.local.entity.AppConfig
import org.opencrow.app.data.remote.ApiClient
import org.opencrow.app.data.remote.dto.UserConfigDto

class ConfigRepository(
    private val apiClient: ApiClient,
    private val configDao: ConfigDao
) {
    companion object {
        private const val TAG = "ConfigRepo"
    }

    suspend fun getServerConfig(): UserConfigDto? {
        return try {
            val resp = apiClient.api.getConfig()
            if (resp.isSuccessful) resp.body() else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config", e)
            null
        }
    }

    suspend fun saveServerConfig(config: UserConfigDto): UserConfigDto? {
        return try {
            val resp = apiClient.api.putConfig(config)
            if (resp.isSuccessful) resp.body() else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
            null
        }
    }

    suspend fun getLocalSetting(key: String): String? = configDao.get(key)

    suspend fun setLocalSetting(key: String, value: String) {
        configDao.set(AppConfig(key, value))
    }

    suspend fun validateConnection(): Boolean {
        return try {
            val health = apiClient.api.health()
            if (!health.isSuccessful) return false
            apiClient.api.listConversations().isSuccessful
        } catch (_: Exception) {
            false
        }
    }
}
