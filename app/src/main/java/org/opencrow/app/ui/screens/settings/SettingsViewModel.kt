package org.opencrow.app.ui.screens.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencrow.app.data.remote.dto.UserConfigDto
import org.opencrow.app.data.repository.ConfigRepository
import org.opencrow.app.heartbeat.HeartbeatScheduler

data class SettingsUiState(
    val activeTab: String = "local",
    val config: UserConfigDto? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val saveStatus: String? = null,
    val heartbeatEnabled: Boolean = false,
    val heartbeatInterval: String = "30",
    val connectionValid: Boolean? = null,
    val validating: Boolean = false
)

class SettingsViewModel(
    private val configRepository: ConfigRepository
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsVM"
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadConfig()
    }

    private fun loadConfig() {
        viewModelScope.launch {
            try {
                val serverConfig = configRepository.getServerConfig()
                val hbEnabled = configRepository.getLocalSetting("heartbeat_enabled") == "true"
                val hbInterval = configRepository.getLocalSetting("heartbeat_interval") ?: "30"
                _uiState.update {
                    it.copy(
                        config = serverConfig,
                        heartbeatEnabled = hbEnabled,
                        heartbeatInterval = hbInterval,
                        loading = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load config", e)
                _uiState.update {
                    it.copy(error = "Failed to load config: ${e.message}", loading = false)
                }
            }
        }
    }

    fun setActiveTab(tab: String) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun updateConfig(config: UserConfigDto) {
        _uiState.update { it.copy(config = config) }
    }

    fun saveConfig() {
        val cfg = _uiState.value.config ?: return
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = configRepository.saveServerConfig(cfg)
            if (result != null) {
                _uiState.update { it.copy(config = result, saving = false, saveStatus = "Saved") }
            } else {
                _uiState.update { it.copy(saving = false, error = "Save failed") }
            }
        }
    }

    fun setHeartbeatEnabled(enabled: Boolean, context: Context) {
        _uiState.update { it.copy(heartbeatEnabled = enabled) }
        viewModelScope.launch {
            configRepository.setLocalSetting("heartbeat_enabled", enabled.toString())
            if (enabled) {
                val interval = _uiState.value.heartbeatInterval.toIntOrNull() ?: 30
                HeartbeatScheduler.schedule(context, interval)
            } else {
                HeartbeatScheduler.cancel(context)
            }
        }
    }

    fun setHeartbeatInterval(interval: String, context: Context) {
        _uiState.update { it.copy(heartbeatInterval = interval) }
        viewModelScope.launch {
            configRepository.setLocalSetting("heartbeat_interval", interval)
            if (_uiState.value.heartbeatEnabled) {
                HeartbeatScheduler.schedule(context, interval.toIntOrNull() ?: 30)
            }
        }
    }

    fun validateConnection() {
        _uiState.update { it.copy(validating = true) }
        viewModelScope.launch {
            val valid = configRepository.validateConnection()
            _uiState.update { it.copy(connectionValid = valid, validating = false) }
        }
    }

    class Factory(private val configRepository: ConfigRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(configRepository) as T
        }
    }
}
