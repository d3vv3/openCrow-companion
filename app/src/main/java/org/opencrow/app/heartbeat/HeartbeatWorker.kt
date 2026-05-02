package org.opencrow.app.heartbeat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.opencrow.app.OpenCrowApp
import org.opencrow.app.R
import org.opencrow.app.data.local.LocalToolCapabilities
import org.opencrow.app.data.local.LocalToolExecutor
import org.opencrow.app.data.remote.ApiClient
import org.opencrow.app.data.remote.dto.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.TimeZone

class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val NOTIF_CHANNEL_HEARTBEAT = "opencrow_heartbeat"
        private const val NOTIF_ID_HEARTBEAT = 9001
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as OpenCrowApp
        val apiClient = app.container.apiClient
        try {
            apiClient.initialize()
            if (!apiClient.isConfigured) return Result.failure()

            val deviceId = apiClient.getDeviceId() ?: return Result.failure()

            registerDevice(apiClient, deviceId)

            val tasks = pollDeviceTasks(apiClient, deviceId)

            // ── 1. Execute structured local-tool tasks immediately ──────────
            val toolTasks    = tasks.filter { it.toolName != null }
            val promptTasks  = tasks.filter { it.toolName == null }

            // Execute each tool task and collect human-readable outcomes for the LLM
            val executedTaskSummaries = toolTasks.map { task ->
                executeLocalTask(apiClient, task)
            }

            // ── 2. Fetch heartbeat config (prompt + interval) from server ──
            val heartbeatConfig = fetchHeartbeatConfig(apiClient)
            val serverPromptTemplate = heartbeatConfig?.heartbeatPrompt?.takeIf { it.isNotBlank() }

            // Enforce active hours window. If we're outside the configured window, bail out
            // without sending a heartbeat. WorkManager will re-fire at the next interval and
            // the check will pass once the window opens again.
            if (heartbeatConfig != null && !isWithinActiveHours(heartbeatConfig)) {
                Log.i(TAG, "Skipping heartbeat: outside active hours " +
                    "(${heartbeatConfig.activeHoursStart}-${heartbeatConfig.activeHoursEnd} ${heartbeatConfig.timezone})")
                return Result.success()
            }

            // Reschedule with server's interval if it differs from current schedule
            // WorkManager enforces a minimum of 15 minutes regardless.
            heartbeatConfig?.intervalSeconds?.let { secs ->
                val mins = maxOf(15, secs / 60)
                HeartbeatScheduler.schedule(applicationContext, mins)
            }

            // ── 3. Send heartbeat prompt (with remaining instruction tasks) ─
            checkNotifications(apiClient)
            val calendarPrompt = queryCalendar()
            val dateTime = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' HH:mm z", Locale.getDefault())
                .format(Date())

            val taskPrompt = buildTaskPrompt(promptTasks, executedTaskSummaries)
            val heartbeatMessage = buildHeartbeatPrompt(dateTime, calendarPrompt, taskPrompt, serverPromptTemplate)

            val (response, convId) = sendHeartbeatChat(apiClient, heartbeatMessage)

            // ── 4. Record executed tool calls into the heartbeat conversation ─
            if (convId != null) {
                toolTasks.zip(executedTaskSummaries).forEach { (task, summary) ->
                    try {
                        val isError = summary.startsWith(":x:")
                        apiClient.api.recordToolCall(
                            convId,
                            RecordToolCallRequest(
                                name = task.toolName ?: "unknown",
                                arguments = task.toolArguments,
                                output = if (isError) "" else summary,
                                error = if (isError) summary else "",
                                source = "device"
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to record tool call for conversation $convId: ${e.message}")
                    }
                }
            }

            if (response != null && response.trim() != "HEARTBEAT_OK") {
                Log.i(TAG, "Heartbeat action needed: $response")
                showHeartbeatNotification(response.trim())
            }

            completeTasks(apiClient, promptTasks, response)
            apiClient.persistCurrentTokens()

            app.container.conversationRepository.notifyConversationsChanged()

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat failed", e)
            return Result.retry()
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun registerDevice(apiClient: ApiClient, deviceId: String) {
        try {
            apiClient.api.registerDevice(deviceId, RegisterDeviceRequest(LocalToolCapabilities.all))
        } catch (e: Exception) {
            Log.w(TAG, "Register failed: ${e.message}")
        }
    }

    private suspend fun fetchHeartbeatConfig(apiClient: ApiClient): HeartbeatConfigDto? {
        return try {
            apiClient.api.getHeartbeatConfig().body()
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun checkNotifications(apiClient: ApiClient): List<HeartbeatEventDto> {
        return try {
            val resp = apiClient.api.getHeartbeatEvents()
            resp.body()?.events.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Executes a background device task whose [toolName] + [toolArguments] are set on the server.
     * Uses [LocalToolExecutor.executeLocal] to avoid posting a tool-result to the live SSE channel
     * (there is no live session during background heartbeat). Reports success/failure directly via
     * [completeDeviceTask].
     *
     * @return A human-readable description of what was executed and the outcome.
     */
    private suspend fun executeLocalTask(apiClient: ApiClient, task: DeviceTaskDto): String {
        val toolName = task.toolName ?: return ""
        val args     = task.toolArguments ?: emptyMap()

        val executor = LocalToolExecutor(
            context = applicationContext,
            apiClient = apiClient,
            requestPermission = { permission ->
                ContextCompat.checkSelfPermission(applicationContext, permission) ==
                        PackageManager.PERMISSION_GRANTED
            }
        )

        return try {
            val result = executor.executeLocal(toolName, args)
            try {
                apiClient.api.completeDeviceTask(
                    task.id,
                    CompleteDeviceTaskRequest(success = !result.isError, output = result.output)
                )
            } catch (_: Exception) {}
            if (result.isError) ":x: $toolName failed: ${result.output}"
            else ":white_check_mark: $toolName: ${result.output}"
        } catch (e: Exception) {
            Log.e(TAG, "Local tool task ${task.id} failed", e)
            try {
                apiClient.api.completeDeviceTask(
                    task.id,
                    CompleteDeviceTaskRequest(success = false, output = e.message ?: "error")
                )
            } catch (_: Exception) {}
            ":x: $toolName error: ${e.message}"
        }
    }

    private fun queryCalendar(): String {
        if (ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "Calendar permission not granted."
        }

        val resolver: ContentResolver = applicationContext.contentResolver
        val now = Calendar.getInstance()
        val startOfDay = now.clone() as Calendar
        startOfDay.set(Calendar.HOUR_OF_DAY, 0)
        startOfDay.set(Calendar.MINUTE, 0)
        startOfDay.set(Calendar.SECOND, 0)

        val endOfDay = now.clone() as Calendar
        endOfDay.set(Calendar.HOUR_OF_DAY, 23)
        endOfDay.set(Calendar.MINUTE, 59)
        endOfDay.set(Calendar.SECOND, 59)

        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION
        )

        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf(
            startOfDay.timeInMillis.toString(),
            endOfDay.timeInMillis.toString()
        )

        val cursor: Cursor? = resolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Events.DTSTART} ASC"
        )

        val events = mutableListOf<String>()
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        cursor?.use {
            while (it.moveToNext()) {
                val title = it.getString(0) ?: "Untitled"
                val desc = it.getString(1) ?: ""
                val start = timeFmt.format(Date(it.getLong(2)))
                val end = timeFmt.format(Date(it.getLong(3)))
                val location = it.getString(4) ?: ""
                val locationStr = if (location.isNotBlank()) ", $location" else ""
                val descStr = if (desc.isNotBlank()) ": $desc" else ""
                events.add("- $start to $end: $title$descStr$locationStr")
            }
        }

        return if (events.isEmpty()) {
            "No events for today on the user's calendar."
        } else {
            "User today has the following events on their calendar:\n${events.joinToString("\n")}"
        }
    }

    private suspend fun pollDeviceTasks(apiClient: ApiClient, deviceId: String): List<DeviceTaskDto> {
        return try {
            val resp = apiClient.api.getDeviceTasks(deviceId)
            resp.body()?.tasks.orEmpty().filter { it.status == "processing" || it.status == "pending" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildTaskPrompt(tasks: List<DeviceTaskDto>, executedSummaries: List<String> = emptyList()): String {
        val parts = mutableListOf<String>()
        if (executedSummaries.isNotEmpty()) {
            parts.add(
                "The following device actions were automatically executed during this heartbeat:\n" +
                    executedSummaries.joinToString("\n") { "  $it" }
            )
        }
        if (tasks.isNotEmpty()) {
            parts.add(
                "Pending tasks requiring your attention:\n" +
                    tasks.joinToString("\n") { "- [${it.id}] ${it.instruction}" }
            )
        }
        return if (parts.isEmpty()) "No pending tasks detected." else parts.joinToString("\n\n")
    }

    private fun buildHeartbeatPrompt(
        dateTime: String,
        calendarPrompt: String,
        taskPrompt: String,
        customTemplate: String?
    ): String {
        // If a custom template is set on the server, use it with variable substitution
        if (!customTemplate.isNullOrBlank()) {
            return customTemplate
                .replace("{datetime}", dateTime)
                .replace("{calendar}", calendarPrompt)
                .replace("{tasks}", taskPrompt)
        }
        return """[HEARTBEAT] This is an automatic self-check. Right now is $dateTime

$calendarPrompt

$taskPrompt

Review your memories, pending tasks, and recent context.
Collect important news relevant to the user (their interests, location, ongoing topics, and priorities), then decide if anything needs attention.

If everything looks good and there is nothing noteworthy, respond with exactly:
HEARTBEAT_OK

If anything needs attention, respond concisely with what changed and what action is recommended."""
    }

    private suspend fun sendHeartbeatChat(apiClient: ApiClient, message: String): Pair<String?, String?> {
        return try {
            val convResp = apiClient.api.createConversation(
                CreateConversationRequest("[Heartbeat] ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}")
            )
            if (!convResp.isSuccessful) return Pair(null, null)
            val convId = convResp.body()!!.id

            val completeResp = apiClient.api.complete(CompleteRequest(convId, message))
            val output = if (completeResp.isSuccessful) completeResp.body()?.output else null
            Pair(output, convId)
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat chat failed", e)
            Pair(null, null)
        }
    }

    private fun showHeartbeatNotification(message: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIF_CHANNEL_HEARTBEAT) == null) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL_HEARTBEAT,
                "openCrow Heartbeat",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Heartbeat status notifications" }
            nm.createNotificationChannel(ch)
        }
        val notif = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL_HEARTBEAT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("openCrow")
            .setContentText(message.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID_HEARTBEAT, notif)
    }

    /**
     * Returns true if the current local time falls within the configured active hours window.
     * Both [HeartbeatConfigDto.activeHoursStart] and [HeartbeatConfigDto.activeHoursEnd] are "HH:MM" strings
     * (24-hour). [HeartbeatConfigDto.timezone] is an IANA timezone name (e.g. "Europe/Berlin").
     * If either bound is null/blank the window is considered always-open.
     */
    private fun isWithinActiveHours(config: HeartbeatConfigDto): Boolean {
        val startStr = config.activeHoursStart?.takeIf { it.isNotBlank() } ?: return true
        val endStr   = config.activeHoursEnd?.takeIf   { it.isNotBlank() } ?: return true

        val tz = try {
            TimeZone.getTimeZone(config.timezone?.takeIf { it.isNotBlank() } ?: "UTC")
        } catch (_: Exception) {
            TimeZone.getTimeZone("UTC")
        }

        val now = Calendar.getInstance(tz)
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        fun parseMinutes(hhmm: String): Int? {
            val parts = hhmm.split(":")
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            return h * 60 + m
        }

        val startMinutes = parseMinutes(startStr) ?: return true
        val endMinutes   = parseMinutes(endStr)   ?: return true

        return nowMinutes in startMinutes until endMinutes
    }

    private suspend fun completeTasks(apiClient: ApiClient, tasks: List<DeviceTaskDto>, response: String?) {
        for (task in tasks) {
            try {
                apiClient.api.completeDeviceTask(
                    task.id,
                    CompleteDeviceTaskRequest(
                        success = true,
                        output = response ?: "Processed during heartbeat"
                    )
                )
            } catch (e: Exception) {
                try {
                    apiClient.api.completeDeviceTask(
                        task.id,
                        CompleteDeviceTaskRequest(success = false, output = "Failed: ${e.message}")
                    )
                } catch (_: Exception) {}
            }
        }
    }
}
