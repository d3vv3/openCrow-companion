package org.opencrow.app.ui.screens.qrscan

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencrow.app.data.local.LocalToolCapabilities
import org.opencrow.app.data.remote.ApiClient
import org.opencrow.app.data.remote.dto.QrPayload
import org.opencrow.app.data.remote.dto.RegisterDeviceRequest
import org.unifiedpush.android.connector.UnifiedPush

data class QrScanUiState(
    val hasCameraPermission: Boolean = false,
    val permissionsRequested: Boolean = false,
    val error: String? = null,
    val status: String? = null,
    val pairing: Boolean = false,
    val paired: Boolean = false
)

class QrScanViewModel(
    application: Application,
    private val apiClient: ApiClient
) : AndroidViewModel(application) {

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
        _uiState.update { it.copy(pairing = true, error = null, status = "Validating pairing...") }

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

                _uiState.update { it.copy(status = "Checking server connection...") }

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

                _uiState.update { it.copy(status = "Registering device...") }

                // Register device capabilities, including any already-stored push endpoint
                // (endpoint may have been assigned by UP during onboarding, before pairing)
                val storedPushEndpoint = apiClient.getPushEndpoint() ?: ""
                val registerResp = try {
                    registerDevice(payload.id, storedPushEndpoint.ifEmpty { null })
                } catch (e: Exception) {
                    Log.e(TAG, "Device register failed: ${e.message}", e)
                    _uiState.update {
                        it.copy(error = "Device registration failed: ${e.message}", pairing = false)
                    }
                    return@launch
                }
                if (!registerResp.isSuccessful) {
                    _uiState.update {
                        it.copy(error = "Device registration failed (${registerResp.code()})", pairing = false)
                    }
                    return@launch
                }

                // If UP is already configured, re-register with the new device ID so
                // onNewEndpoint fires and the push endpoint is recorded for this device.
                val savedDistributor = UnifiedPush.getSavedDistributor(getApplication())
                if (!savedDistributor.isNullOrEmpty()) {
                    if (storedPushEndpoint.isNotEmpty()) {
                        Log.i(TAG, "Using stored UP endpoint during pairing for device ${payload.id}")
                    } else {
                        _uiState.update {
                            it.copy(status = "Waiting for push endpoint from $savedDistributor...")
                        }
                        Log.i(TAG, "Waiting for UP endpoint for new device ID ${payload.id} (distributor=$savedDistributor)")
                        UnifiedPush.register(getApplication(), payload.id)

                        val pushEndpoint = waitForPushEndpoint(timeoutMillis = 15000L)
                        if (pushEndpoint.isNullOrEmpty()) {
                            Log.w(TAG, "Timed out waiting for UP endpoint for device ${payload.id}")
                            _uiState.update {
                                it.copy(
                                    paired = true,
                                    pairing = false,
                                    error = "Device paired, but push setup timed out. Open your push distributor and try again, or ask the assistant to configure push later.",
                                    status = null
                                )
                            }
                            return@launch
                        }

                        _uiState.update { it.copy(status = "Saving push endpoint...") }
                        val pushRegisterResp = try {
                            registerDevice(payload.id, pushEndpoint)
                        } catch (e: Exception) {
                            Log.e(TAG, "Push endpoint register failed: ${e.message}", e)
                            _uiState.update {
                                it.copy(
                                    paired = true,
                                    pairing = false,
                                    error = "Device paired, but saving the push endpoint failed: ${e.message}",
                                    status = null
                                )
                            }
                            return@launch
                        }
                        if (!pushRegisterResp.isSuccessful) {
                            _uiState.update {
                                it.copy(
                                    paired = true,
                                    pairing = false,
                                    error = "Device paired, but saving the push endpoint failed (${pushRegisterResp.code()})",
                                    status = null
                                )
                            }
                            return@launch
                        }
                    }
                }

                _uiState.update { it.copy(paired = true, pairing = false, status = null) }
            } catch (e: Exception) {
                Log.e(TAG, "Pairing failed", e)
                _uiState.update { it.copy(error = "Pairing failed: ${e.message}", pairing = false, status = null) }
            }
        }
    }

    private suspend fun registerDevice(deviceId: String, pushEndpoint: String?) =
        apiClient.api.registerDevice(
            deviceId,
            RegisterDeviceRequest(
                capabilities = LocalToolCapabilities.all,
                pushEndpoint = pushEndpoint
            )
        )

    private suspend fun waitForPushEndpoint(timeoutMillis: Long): String? {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < timeoutMillis) {
            val endpoint = apiClient.getPushEndpoint()
            if (!endpoint.isNullOrEmpty()) {
                return endpoint
            }
            delay(500)
        }
        return null
    }

    class Factory(private val application: Application, private val apiClient: ApiClient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return QrScanViewModel(application, apiClient) as T
        }
    }
}
