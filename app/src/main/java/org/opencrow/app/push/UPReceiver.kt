package org.opencrow.app.push

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
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
import org.opencrow.app.data.local.LocalToolExecutor
import org.opencrow.app.data.remote.dto.CompleteDeviceTaskRequest
import org.opencrow.app.data.remote.dto.RegisterDeviceRequest

/**
 * UnifiedPush BroadcastReceiver.
 *
 * Lifecycle:
 * - onNewEndpoint: distributor assigned us a new push endpoint; persist it and re-register with the server.
 * - onMessage: incoming push message; dispatch a local Android notification or execute a device task.
 * - onUnregistered: distributor revoked our registration; clear the stored endpoint.
 */
class UPReceiver : MessagingReceiver() {

    companion object {
        private const val TAG = "UPReceiver"
        const val PREF_PUSH_ENDPOINT = "upPushEndpoint"
        private const val WAKE_LOCK_TAG = "opencrow:device_task"
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L
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

            val type = extractJsonString(json, "type")

            if (type == "device_task") {
                val taskId = extractJsonString(json, "task_id") ?: run {
                    Log.w(TAG, "device_task push missing task_id, ignoring")
                    return
                }
                executeDeviceTaskPush(context, taskId)
                return
            }

            // Default: show push notification
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

    /**
     * Handles a `device_task` push: acquires a WakeLock, fetches the task by ID,
     * executes it locally, posts the result, then releases the lock.
     * If the task cannot be fetched or executed, it remains in the DB for the next heartbeat poll.
     */
    private fun executeDeviceTaskPush(context: Context, taskId: String) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }

        scope.launch {
            try {
                val app = context.applicationContext as OpenCrowApp
                val apiClient = app.container.apiClient
                apiClient.initialize()

                if (!apiClient.isConfigured) {
                    Log.w(TAG, "API not configured, skipping device_task push execution")
                    return@launch
                }

                // Fetch the specific task
                val taskResp = try {
                    apiClient.api.getDeviceTask(taskId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch device task $taskId: ${e.message}")
                    return@launch
                }

                if (!taskResp.isSuccessful) {
                    Log.w(TAG, "Server returned ${taskResp.code()} for task $taskId, skipping")
                    return@launch
                }

                val task = taskResp.body() ?: run {
                    Log.w(TAG, "Empty body for task $taskId, skipping")
                    return@launch
                }

                if (task.toolName == null) {
                    Log.d(TAG, "Task $taskId has no toolName (prompt task), skipping UP execution")
                    return@launch
                }

                Log.i(TAG, "Executing device task $taskId: ${task.toolName}")

                val executor = LocalToolExecutor(
                    context = context.applicationContext,
                    apiClient = apiClient,
                    requestPermission = { permission ->
                        ContextCompat.checkSelfPermission(context.applicationContext, permission) ==
                                PackageManager.PERMISSION_GRANTED
                    }
                )

                try {
                    val result = executor.executeLocal(task.toolName, task.toolArguments ?: emptyMap())
                    apiClient.api.completeDeviceTask(
                        taskId,
                        CompleteDeviceTaskRequest(success = !result.isError, output = result.output)
                    )
                    Log.i(TAG, "Task $taskId completed via UP push: isError=${result.isError}")
                } catch (e: Exception) {
                    Log.e(TAG, "Task $taskId execution failed: ${e.message}", e)
                    try {
                        apiClient.api.completeDeviceTask(
                            taskId,
                            CompleteDeviceTaskRequest(success = false, output = e.message ?: "error")
                        )
                    } catch (_: Exception) {}
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
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
