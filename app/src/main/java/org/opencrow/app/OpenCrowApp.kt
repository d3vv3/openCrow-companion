package org.opencrow.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencrow.app.di.AppContainer
import org.opencrow.app.heartbeat.HeartbeatScheduler
import org.unifiedpush.android.connector.UnifiedPush

class OpenCrowApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Re-schedule heartbeat on startup using the server config.
        // WorkManager survives process restarts once enqueued, but a fresh
        // install or data-cleared scenario would never fire without this.
        // NOTE: the Android device heartbeat (task polling + check-in) runs
        // whenever the device is paired, regardless of whether the server-side
        // heartbeat automation is enabled.
        appScope.launch {
            try {
                container.apiClient.initialize()
                if (!container.apiClient.isConfigured) return@launch

                val resp = container.apiClient.api.getHeartbeatConfig()
                val cfg = (if (resp.isSuccessful) resp.body() else null)

                // Always schedule when paired; use server interval if available (min 15 min).
                val intervalMins = if (cfg != null && cfg.intervalSeconds > 0)
                    maxOf(15, cfg.intervalSeconds / 60)
                else
                    15
                HeartbeatScheduler.schedule(this@OpenCrowApp, intervalMins)

                // Register with UnifiedPush distributor so the device can receive push notifications.
                // instance = deviceId ensures the server can correlate the endpoint back to this device.
                val deviceId = container.apiClient.getDeviceId()
                if (deviceId != null) {
                    val activeDistributor = UnifiedPush.getAckDistributor(this@OpenCrowApp)
                    val allDistributors = UnifiedPush.getDistributors(this@OpenCrowApp)
                    Log.i("OpenCrowApp", "UP distributor state: active='$activeDistributor', available=$allDistributors")
                    UnifiedPush.register(
                        context  = this@OpenCrowApp,
                        instance = deviceId
                    )
                    Log.i("OpenCrowApp", "UnifiedPush.register() called with instance=$deviceId")
                } else {
                    Log.w("OpenCrowApp", "No deviceId stored, skipping UP registration")
                }
            } catch (e: Exception) {
                Log.w("OpenCrowApp", "Could not fetch heartbeat config at startup: ${e.message}")
                // Still schedule with default interval if fetch fails
                if (container.apiClient.isConfigured) {
                    HeartbeatScheduler.schedule(this@OpenCrowApp, 15)
                    val deviceId = container.apiClient.getDeviceId()
                    if (deviceId != null) {
                        val dist = UnifiedPush.getAckDistributor(this@OpenCrowApp)
                        Log.i("OpenCrowApp", "[fallback] UP distributor='$dist', calling register for instance=$deviceId")
                        UnifiedPush.register(this@OpenCrowApp, instance = deviceId)
                    }
                }
            }
        }
    }
}
