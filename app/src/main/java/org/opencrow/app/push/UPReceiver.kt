package org.opencrow.app.push

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import org.opencrow.app.OpenCrowApp
import org.opencrow.app.data.local.LocalToolCapabilities
import org.opencrow.app.data.remote.dto.RegisterDeviceRequest

/**
 * UnifiedPush BroadcastReceiver.
 *
 * Lifecycle:
 * - onNewEndpoint: distributor assigned us a new push endpoint; persist it and re-register with the server.
 * - onMessage: incoming push message; dispatch a local Android notification.
 * - onUnregistered: distributor revoked our registration; clear the stored endpoint.
 */
class UPReceiver : MessagingReceiver() {

    companion object {
        private const val TAG = "UPReceiver"
        const val PREF_PUSH_ENDPOINT = "upPushEndpoint"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewEndpoint(context: Context, endpoint: PushEndpoint, instance: String) {
        Log.i(TAG, "New UP endpoint for instance=$instance: ${endpoint.url}")
        scope.launch {
            try {
                val app = context.applicationContext as OpenCrowApp
                val apiClient = app.container.apiClient
                apiClient.initialize()

                // Always persist locally so QrScanViewModel can pick it up during pairing
                apiClient.savePushEndpoint(endpoint.url)

                if (!apiClient.isConfigured) {
                    Log.w(TAG, "API not configured, endpoint saved locally for use after pairing")
                    return@launch
                }

                // Re-register device capabilities with the new push endpoint
                val deviceId = apiClient.getDeviceId() ?: run {
                    Log.w(TAG, "No deviceId stored, skipping endpoint registration")
                    return@launch
                }
                val caps = LocalToolCapabilities.all
                val resp = apiClient.api.registerDevice(
                    deviceId,
                    RegisterDeviceRequest(capabilities = caps, pushEndpoint = endpoint.url)
                )
                if (resp.isSuccessful) {
                    Log.i(TAG, "Push endpoint registered with server for device $deviceId")
                } else {
                    Log.w(TAG, "Server rejected endpoint registration: ${resp.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register endpoint with server: ${e.message}", e)
            }
        }
    }

    override fun onMessage(context: Context, message: PushMessage, instance: String) {
        try {
            val json = String(message.content, Charsets.UTF_8)
            Log.d(TAG, "UP message received: $json")
            // Parse {title, body, channel} JSON manually to avoid heavyweight deps here
            val title          = extractJsonString(json, "title")          ?: "openCrow"
            val body           = extractJsonString(json, "body")           ?: ""
            val channel        = extractJsonString(json, "channel")        ?: "default"
            val conversationId = extractJsonString(json, "conversation_id")

            val app = context.applicationContext as OpenCrowApp
            val executor = app.container.localToolExecutor
            executor.showPushNotification(context, title, body, channel, conversationId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle UP message: ${e.message}", e)
        }
    }

    override fun onUnregistered(context: Context, instance: String) {
        Log.i(TAG, "UP unregistered for instance=$instance")
        scope.launch {
            try {
                val app = context.applicationContext as OpenCrowApp
                app.container.apiClient.savePushEndpoint("")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear push endpoint: ${e.message}")
            }
        }
    }

    override fun onRegistrationFailed(context: Context, reason: FailedReason, instance: String) {
        val distributor = org.unifiedpush.android.connector.UnifiedPush.getAckDistributor(context)
        Log.e(TAG, "UP registration FAILED for instance=$instance, reason=$reason, distributor='$distributor'")
        Log.e(TAG, "Make sure a UnifiedPush distributor app (e.g. ntfy) is installed and configured.")
    }

    /** Very small JSON string extractor -- avoids importing Gson in a BroadcastReceiver. */
    private fun extractJsonString(json: String, key: String): String? {
        val pattern = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        return pattern.find(json)?.groupValues?.getOrNull(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
    }
}
