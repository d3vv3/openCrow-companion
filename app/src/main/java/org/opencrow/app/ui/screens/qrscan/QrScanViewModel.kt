package org.opencrow.app.ui.screens.qrscan

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencrow.app.data.remote.ApiClient
import org.opencrow.app.data.remote.dto.DeviceCapability
import org.opencrow.app.data.remote.dto.QrPayload
import org.opencrow.app.data.remote.dto.RegisterDeviceRequest

data class QrScanUiState(
    val hasCameraPermission: Boolean = false,
    val permissionsRequested: Boolean = false,
    val error: String? = null,
    val pairing: Boolean = false,
    val paired: Boolean = false
)

class QrScanViewModel(
    private val apiClient: ApiClient
) : ViewModel() {

    companion object {
        private const val TAG = "QrScanVM"
    }

    private val _uiState = MutableStateFlow(QrScanUiState())
    val uiState: StateFlow<QrScanUiState> = _uiState.asStateFlow()

    fun onPermissionsResult(cameraGranted: Boolean) {
        _uiState.update {
            it.copy(hasCameraPermission = cameraGranted, permissionsRequested = true)
        }
    }

    fun handleQrScanned(raw: String) {
        if (_uiState.value.pairing) return
        _uiState.update { it.copy(pairing = true, error = null) }

        viewModelScope.launch {
            try {
                val payload = Gson().fromJson(raw, QrPayload::class.java)
                if (payload.id.isNullOrBlank() || payload.server.isNullOrBlank() ||
                    payload.accessToken.isNullOrBlank() || payload.refreshToken.isNullOrBlank()
                ) {
                    _uiState.update { it.copy(error = "Invalid QR code format", pairing = false) }
                    return@launch
                }

                Log.d(TAG, "QR payload: server=${payload.server}, id=${payload.id}")

                apiClient.configure(payload.server, payload.accessToken, payload.refreshToken)
                apiClient.saveTokens(
                    payload.server, payload.accessToken, payload.refreshToken, payload.id
                )

                // Check server is reachable
                val healthResp = try {
                    apiClient.api.health()
                } catch (e: Exception) {
                    Log.e(TAG, "Health check failed: ${e.message}", e)
                    _uiState.update {
                        it.copy(error = "Cannot reach server at ${payload.server}: ${e.message}", pairing = false)
                    }
                    return@launch
                }
                if (!healthResp.isSuccessful) {
                    _uiState.update {
                        it.copy(error = "Server returned ${healthResp.code()} on health check", pairing = false)
                    }
                    return@launch
                }

                // Validate auth tokens
                val authResp = try {
                    apiClient.api.listConversations()
                } catch (e: Exception) {
                    Log.e(TAG, "Auth validation failed: ${e.message}", e)
                    _uiState.update {
                        it.copy(error = "Server reachable but auth failed: ${e.message}", pairing = false)
                    }
                    return@launch
                }
                if (!authResp.isSuccessful) {
                    _uiState.update {
                        it.copy(error = "Server reachable but auth failed (${authResp.code()})", pairing = false)
                    }
                    return@launch
                }

                // Register device capabilities (best-effort)
                val capabilities = listOf(
                    DeviceCapability("set_alarm", "Set a one-time or recurring alarm"),
                    DeviceCapability("create_contact", "Add a contact to the phone's address book"),
                    DeviceCapability("make_call", "Initiate a phone call to a number"),
                    DeviceCapability("send_sms", "Send an SMS to a number"),
                    DeviceCapability("create_calendar_event", "Add an event to the calendar")
                )
                try {
                    apiClient.api.registerDevice(payload.id, RegisterDeviceRequest(capabilities))
                } catch (e: Exception) {
                    Log.w(TAG, "Device register failed (non-fatal): ${e.message}")
                }

                _uiState.update { it.copy(paired = true, pairing = false) }
            } catch (e: Exception) {
                Log.e(TAG, "Pairing failed", e)
                _uiState.update { it.copy(error = "Pairing failed: ${e.message}", pairing = false) }
            }
        }
    }

    class Factory(private val apiClient: ApiClient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return QrScanViewModel(apiClient) as T
        }
    }
}
