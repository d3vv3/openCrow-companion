package org.opencrow.app.heartbeat

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.opencrow.app.OpenCrowApp
import org.opencrow.app.data.remote.dto.*
import java.text.SimpleDateFormat
import java.util.*

class HeartbeatWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "HeartbeatWorker"
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as OpenCrowApp
        try {
            app.apiClient.initialize()
            if (!app.apiClient.isConfigured) return Result.failure()

            val deviceId = app.apiClient.getDeviceId() ?: return Result.failure()

            // Step 0: Register device to stay online
            registerDevice(app, deviceId)

            // Step 1: Check for notifications (heartbeat events)
            val notifications = checkNotifications(app)

            // Step 2: Query local calendar
            val calendarPrompt = queryCalendar()

            // Step 3: Current date and time
            val dateTime = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' HH:mm z", Locale.getDefault())
                .format(Date())

            // Step 4: Poll device tasks
            val tasks = pollDeviceTasks(app, deviceId)
            val taskPrompt = buildTaskPrompt(tasks)

            // Step 5: Compile heartbeat prompt
            val heartbeatMessage = buildHeartbeatPrompt(dateTime, calendarPrompt, taskPrompt)

            // Send to server as system chat
            val response = sendHeartbeatChat(app, heartbeatMessage)

            // Step 6: Process response
            if (response != null && response.trim() != "HEARTBEAT_OK") {
                Log.i(TAG, "Heartbeat action needed: $response")
            }

            // Step 7: Complete tasks
            completeTasks(app, tasks, response)

            // Persist any refreshed tokens
            app.apiClient.persistCurrentTokens()

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat failed", e)
            return Result.retry()
        }
    }

    private suspend fun registerDevice(app: OpenCrowApp, deviceId: String) {
        val capabilities = listOf(
            DeviceCapability("set_alarm", "Set a one-time or recurring alarm"),
            DeviceCapability("create_contact", "Add a contact to the phone's address book"),
            DeviceCapability("make_call", "Initiate a phone call to a number"),
            DeviceCapability("send_sms", "Send an SMS to a number"),
            DeviceCapability("create_calendar_event", "Add an event to the calendar")
        )
        try {
            app.apiClient.api.registerDevice(deviceId, RegisterDeviceRequest(capabilities))
        } catch (e: Exception) {
            Log.w(TAG, "Register failed: ${e.message}")
        }
    }

    private suspend fun checkNotifications(app: OpenCrowApp): List<HeartbeatEventDto> {
        return try {
            val resp = app.apiClient.api.getHeartbeatEvents()
            resp.body()?.events.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun queryCalendar(): String {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALENDAR)
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

    private suspend fun pollDeviceTasks(app: OpenCrowApp, deviceId: String): List<DeviceTaskDto> {
        return try {
            val resp = app.apiClient.api.getDeviceTasks(deviceId)
            resp.body()?.tasks.orEmpty().filter { it.status == "processing" || it.status == "pending" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun buildTaskPrompt(tasks: List<DeviceTaskDto>): String {
        return if (tasks.isEmpty()) {
            "No pending tasks detected."
        } else {
            "Here are the list of tasks that are pending:\n" +
                    tasks.joinToString("\n") { "- [${it.id}] ${it.instruction}" }
        }
    }

    private fun buildHeartbeatPrompt(
        dateTime: String,
        calendarPrompt: String,
        taskPrompt: String
    ): String {
        return """[HEARTBEAT] This is an automatic self-check. Right now is $dateTime

$calendarPrompt

$taskPrompt

Review your memories, pending tasks, and recent context.
Collect important news relevant to the user (their interests, location, ongoing topics, and priorities), then decide if anything needs attention.

If everything looks good and there is nothing noteworthy, respond with exactly:
HEARTBEAT_OK

If anything needs attention, respond concisely with what changed and what action is recommended."""
    }

    private suspend fun sendHeartbeatChat(app: OpenCrowApp, message: String): String? {
        return try {
            // Create a system conversation for heartbeat
            val convResp = app.apiClient.api.createConversation(
                CreateConversationRequest("[Heartbeat] ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}")
            )
            if (!convResp.isSuccessful) return null
            val convId = convResp.body()!!.id

            // Send heartbeat message via orchestrator
            val completeResp = app.apiClient.api.complete(
                CompleteRequest(convId, message)
            )
            if (completeResp.isSuccessful) {
                completeResp.body()?.output
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat chat failed", e)
            null
        }
    }

    private suspend fun completeTasks(app: OpenCrowApp, tasks: List<DeviceTaskDto>, response: String?) {
        for (task in tasks) {
            try {
                // For now, mark tasks as completed with the heartbeat response
                val success = response != null && response.trim() != "HEARTBEAT_OK"
                app.apiClient.api.completeDeviceTask(
                    task.id,
                    CompleteDeviceTaskRequest(
                        success = true,
                        output = response ?: "Processed during heartbeat"
                    )
                )
            } catch (e: Exception) {
                // Mark as failed
                try {
                    app.apiClient.api.completeDeviceTask(
                        task.id,
                        CompleteDeviceTaskRequest(
                            success = false,
                            output = "Failed: ${e.message}"
                        )
                    )
                } catch (_: Exception) {}
            }
        }
    }
}
