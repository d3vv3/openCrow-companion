package org.opencrow.app.data.local

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.CallLog
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencrow.app.R
import org.opencrow.app.data.remote.ApiClient
import org.opencrow.app.data.remote.dto.ToolResultRequest
import java.util.Calendar
import java.util.TimeZone

/**
 * Executes local device tools and posts results back to the server.
 * Tools are triggered by the server via the `tool_execute_local` SSE event.
 *
 * Tool names arrive with or without the `on_device_` prefix (server uses prefix;
 * background workers use raw names). The prefix is stripped before dispatch.
 *
 * @param requestPermission  Suspend callback that asks the user to grant [permission].
 *                           Returns true if granted, false if denied.
 */
class LocalToolExecutor(
    private val context: Context,
    private val apiClient: ApiClient,
    private val requestPermission: suspend (String) -> Boolean = { false }
) {
    companion object {
        private const val TAG = "LocalToolExecutor"
        private const val NOTIF_CHANNEL_DEFAULT = "opencrow_default"
        private const val NOTIF_CHANNEL_ALERT   = "opencrow_alert"
        private const val NOTIF_ID_NO_CALENDAR  = 9002
        // SharedPreferences key for alarms set via set_alarm.
        // Android has no public API to list alarms, so we track them ourselves.
        private const val ALARM_PREFS_NAME   = "opencrow_alarms"
        private const val PREF_ALARMS        = "alarms"
    }

    /**
     * Execute the named local tool with the given arguments (parsed from JSON),
     * then POST the result to the server via /v1/tool-results/{callId}.
     * Use this for the live SSE path where the server is waiting for the result.
     */
    suspend fun execute(callId: String, toolName: String, args: Map<String, Any>) {
        Log.d(TAG, "▶ execute() callId=$callId toolName=$toolName args=$args")
        val result = executeLocal(toolName, args)
        Log.d(TAG, "◀ execute() result: output=${result.output} isError=${result.isError}")
        try {
            apiClient.api.postToolResult(callId, ToolResultRequest(result.output, result.isError))
            Log.d(TAG, ":white_check_mark: postToolResult succeeded for callId=$callId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post tool result for callId=$callId", e)
        }
    }

    /**
     * Execute the named local tool and return the result without posting to the server.
     * Use this for background device task execution (HeartbeatWorker) where there is
     * no live SSE channel waiting and the caller is responsible for calling completeDeviceTask.
     */
    suspend fun executeLocal(toolName: String, args: Map<String, Any>): ToolResult {
        // Strip on_device_ prefix so implementations don't need to know about it
        val name = toolName.removePrefix("on_device_")
        Log.d(TAG, "▶ executeLocal() toolName=$toolName -> name=$name args=$args")
        val result = try {
            when (name) {
                "set_alarm"             -> setAlarm(args)
                "create_contact"        -> withContext(Dispatchers.Main) { createContact(args) }
                "make_call"             -> makeCall(args)
                "send_sms"              -> withContext(Dispatchers.Main) { sendSms(args) }
                "create_calendar_event" -> createCalendarEvent(args)
                "read_contacts", "search_contacts" -> readContacts(args)
                "read_call_log"         -> readCallLog(args)
                "read_calendar"         -> readCalendar(args)
                "delete_calendar_event" -> deleteCalendarEvent(args)
                "list_apps"             -> listApps(args)                "open_app"              -> openApp(args)
                "get_battery"           -> getBattery()
                "get_location", "get_device_location" -> getLocation()
                "set_volume"            -> setVolume(args)
                "set_ringer_mode"       -> setRingerMode(args)
                "read_sms"              -> readSms(args)
                "get_wifi_info"         -> getWifiInfo()
                "get_device_info"       -> getDeviceInfo()
                "web_open"              -> withContext(Dispatchers.Main) { webOpen(args) }
                "set_brightness"        -> setBrightness(args)
                "toggle_flashlight"     -> toggleFlashlight(args)
                "media_control"         -> mediaControl(args)
                "start_timer"           -> startTimer(args)
                "start_stopwatch"       -> startStopwatch()
                "list_alarms"           -> listAlarms()
                "delete_alarm"          -> deleteAlarm(args)
                "list_unified_push_distributors" -> manageUnifiedPush(emptyMap())
                "configure_unified_push" -> manageUnifiedPush(args)
                "manage_unified_push"    -> manageUnifiedPush(args)
                else                    -> ToolResult(output = "Unknown local tool: $toolName", isError = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing local tool $toolName", e)
            ToolResult(output = e.message ?: "Unexpected error", isError = true)
        }

        Log.d(TAG, "◀ executeLocal() name=$name result: output=${result.output} isError=${result.isError}")
        return result
    }

    // ─── Permission helpers ──────────────────────────────────────────────────

    /** Returns true if already granted, otherwise asks the user and returns the result. */
    private suspend fun ensurePermission(permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return true
        return requestPermission(permission)
    }

    // ─── Tool Implementations ────────────────────────────────────────────────

    private fun setAlarm(args: Map<String, Any>): ToolResult {
        Log.d(TAG, "setAlarm() called with args=$args")

        val hour   = (args["hour"] as? Double)?.toInt() ?: (args["hour"] as? Long)?.toInt()
            ?: return ToolResult(output = "Missing required argument: hour", isError = true).also {
                Log.e(TAG, "setAlarm() ERROR: missing hour. args=$args")
            }
        val minute = (args["minute"] as? Double)?.toInt() ?: (args["minute"] as? Long)?.toInt() ?: 0
        val label  = args["label"] as? String ?: "Alarm"

        // Parse optional days-of-week list for recurring alarms.
        // Accepts a JSON array of strings: ["monday", "tuesday", ...] or ["mon", "tue", ...]
        val dayNameToCalendar = mapOf(
            "sunday"    to Calendar.SUNDAY,    "sun" to Calendar.SUNDAY,
            "monday"    to Calendar.MONDAY,    "mon" to Calendar.MONDAY,
            "tuesday"   to Calendar.TUESDAY,   "tue" to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY, "wed" to Calendar.WEDNESDAY,
            "thursday"  to Calendar.THURSDAY,  "thu" to Calendar.THURSDAY,
            "friday"    to Calendar.FRIDAY,    "fri" to Calendar.FRIDAY,
            "saturday"  to Calendar.SATURDAY,  "sat" to Calendar.SATURDAY,
        )
        @Suppress("UNCHECKED_CAST")
        val rawDays = args["days"] as? List<*>
        val calendarDays: ArrayList<Int>? = rawDays
            ?.mapNotNull { dayNameToCalendar[(it as? String)?.lowercase()] }
            ?.takeIf { it.isNotEmpty() }
            ?.let { ArrayList(it) }

        Log.d(TAG, "setAlarm() parsed: hour=$hour minute=$minute label=$label days=$calendarDays")

        return try {
            // Use AlarmClock.ACTION_SET_ALARM so the system Clock app handles the alarm --
            // proper ringtone, visible in alarm list, survives reboots and app reinstalls.
            // (Same pattern as startTimer which uses ACTION_SET_TIMER.)
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                if (calendarDays != null) {
                    putIntegerArrayListExtra(android.provider.AlarmClock.EXTRA_DAYS, calendarDays)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Log.d(TAG, "setAlarm() firing ACTION_SET_ALARM intent hour=$hour minute=$minute label=$label days=$calendarDays skipUI=true")
            context.startActivity(intent)
            trackAlarm(hour, minute, label, rawDays?.filterIsInstance<String>().orEmpty())
            val timeStr = "%02d:%02d".format(hour, minute)
            val dayNames = rawDays?.filterIsInstance<String>()?.joinToString(", ")
            val recurStr = if (dayNames != null) " every $dayNames" else ""
            val msg = "Alarm set for $timeStr$recurStr${if (label != "Alarm") " -- $label" else ""}"
            Log.d(TAG, "setAlarm() SUCCESS: $msg")
            ToolResult(output = msg)
        } catch (e: Exception) {
            Log.e(TAG, "setAlarm() EXCEPTION", e)
            ToolResult(output = "Failed to set alarm: ${e.message}", isError = true)
        }
    }

    private fun createContact(args: Map<String, Any>): ToolResult {
        val name  = args["name"] as? String ?: return ToolResult(output = "Missing required argument: name", isError = true)
        val phone = args["phone"] as? String
        val email = args["email"] as? String
        val intent = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).apply {
            putExtra(ContactsContract.Intents.Insert.NAME, name)
            phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            email?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult(output = "Contact creation screen opened for '$name'")
    }

    private suspend fun makeCall(args: Map<String, Any>): ToolResult {
        val number = args["phone_number"] as? String ?: args["number"] as? String
            ?: return ToolResult(output = "Missing required argument: phone_number", isError = true)
        val hasPermission = ensurePermission(Manifest.permission.CALL_PHONE)
        return if (hasPermission) {
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolResult(output = "Calling $number")
            }
        } else {
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ToolResult(output = "Dialer opened for $number. Grant CALL_PHONE permission to call directly.")
            }
        }
    }

    private fun sendSms(args: Map<String, Any>): ToolResult {
        val number = args["phone_number"] as? String ?: args["number"] as? String
            ?: return ToolResult(output = "Missing required argument: phone_number", isError = true)
        val body   = args["body"] as? String ?: args["message"] as? String ?: ""
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolResult(output = "SMS composer opened for $number")
    }

    private suspend fun createCalendarEvent(args: Map<String, Any>): ToolResult {
        val title    = args["title"] as? String ?: args["summary"] as? String
            ?: return ToolResult(output = "Missing required argument: title", isError = true)

        val dateStr      = args["date"] as? String
        val startTimeStr = args["start_time"] as? String ?: "09:00"
        val endTimeStr   = args["end_time"] as? String

        val startMs: Long
        val endMs: Long

        if (dateStr != null) {
            val dateParts  = dateStr.trim().split("-")
            val startParts = startTimeStr.trim().split(":")
            val startCal = Calendar.getInstance().apply {
                if (dateParts.size == 3) {
                    set(Calendar.YEAR,         dateParts[0].toIntOrNull() ?: get(Calendar.YEAR))
                    set(Calendar.MONTH,        (dateParts[1].toIntOrNull() ?: (get(Calendar.MONTH)+1)) - 1)
                    set(Calendar.DAY_OF_MONTH, dateParts[2].toIntOrNull() ?: get(Calendar.DAY_OF_MONTH))
                }
                set(Calendar.HOUR_OF_DAY, startParts.getOrNull(0)?.toIntOrNull() ?: 9)
                set(Calendar.MINUTE,      startParts.getOrNull(1)?.toIntOrNull() ?: 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            startMs = startCal.timeInMillis
            endMs = if (endTimeStr != null) {
                val endParts = endTimeStr.trim().split(":")
                Calendar.getInstance().apply {
                    timeInMillis = startMs
                    set(Calendar.HOUR_OF_DAY, endParts.getOrNull(0)?.toIntOrNull() ?: (startCal.get(Calendar.HOUR_OF_DAY)+1))
                    set(Calendar.MINUTE,      endParts.getOrNull(1)?.toIntOrNull() ?: 0)
                }.timeInMillis
            } else {
                startMs + 3_600_000L
            }
        } else {
            startMs = parseEpochMs(args["start_time"] ?: args["startTime"])
                ?: return ToolResult(output = "Missing required argument: start_time", isError = true)
            endMs   = parseEpochMs(args["end_time"] ?: args["endTime"]) ?: (startMs + 3_600_000L)
        }

        if (!ensurePermission(Manifest.permission.READ_CALENDAR)) {
            return ToolResult(output = "READ_CALENDAR permission denied. Ask the user to grant Calendar permission to the openCrow app in Android Settings > Apps > openCrow > Permissions.", isError = true)
        }
        if (!ensurePermission(Manifest.permission.WRITE_CALENDAR)) {
            return ToolResult(output = "WRITE_CALENDAR permission denied. Ask the user to grant Calendar permission to the openCrow app in Android Settings > Apps > openCrow > Permissions.", isError = true)
        }

        // Find the primary/default writable calendar
        data class CalInfo(val id: Long, val name: String)
        val calInfo: CalInfo? = withContext(Dispatchers.IO) {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
            )
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                null,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC"
            )
            var result: CalInfo? = null
            cursor?.use {
                if (it.moveToFirst()) {
                    val id   = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
                    val name = it.getString(it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)) ?: "unknown"
                    result = CalInfo(id, name)
                }
            }
            result
        }

        if (calInfo == null) {
            showNoCalendarNotification()
            return ToolResult(
                output = "No writable calendar found on this device. " +
                         "A notification has been shown asking the user to add or select a calendar account.",
                isError = true
            )
        }

        val location = args["location"] as? String ?: ""
        val desc     = args["description"] as? String ?: ""

        return withContext(Dispatchers.IO) {
            try {
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID,    calInfo.id)
                    put(CalendarContract.Events.TITLE,          title)
                    put(CalendarContract.Events.DTSTART,        startMs)
                    put(CalendarContract.Events.DTEND,          endMs)
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
                    if (desc.isNotBlank())     put(CalendarContract.Events.DESCRIPTION, desc)
                }
                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                if (uri != null) {
                    ToolResult(output = "Calendar event '$title' created on '${calInfo.name}' (id=${uri.lastPathSegment})")
                } else {
                    ToolResult(output = "Failed to create calendar event on '${calInfo.name}': ContentResolver returned null URI", isError = true)
                }
            } catch (e: Exception) {
                ToolResult(output = "Failed to create calendar event: ${e.message}", isError = true)
            }
        }
    }

    private suspend fun readContacts(args: Map<String, Any>): ToolResult {
        if (!ensurePermission(Manifest.permission.READ_CONTACTS)) {
            return ToolResult(output = "READ_CONTACTS permission denied. Ask the user to grant Contacts permission to the openCrow app in Android Settings > Apps > openCrow > Permissions.", isError = true)
        }
        val query  = args["query"] as? String ?: ""
        val limit  = (args["limit"] as? Double)?.toInt() ?: 30
        val format = (args["response_format"] as? String ?: "detailed").lowercase()
        val uri = if (query.isNotBlank()) {
            Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
        } else {
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        }
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )
        data class Contact(val name: String, val phone: String)
        val contacts = mutableListOf<Contact>()
        cursor?.use {
            var count = 0
            while (it.moveToNext() && count < limit) {
                contacts.add(Contact(it.getString(0) ?: "", it.getString(1) ?: ""))
                count++
            }
        }
        if (contacts.isEmpty()) return ToolResult(output = if (query.isNotBlank()) "No contacts found matching '$query'" else "No contacts found")
        return if (format == "concise") {
            ToolResult(output = contacts.joinToString("\n") { "${it.name}: ${it.phone}" })
        } else {
            val arr = org.json.JSONArray(contacts.map { c ->
                org.json.JSONObject().apply { put("name", c.name); put("phone", c.phone) }
            })
            ToolResult(output = arr.toString())
        }
    }

    private suspend fun readCallLog(args: Map<String, Any>): ToolResult {
        if (!ensurePermission(Manifest.permission.READ_CALL_LOG)) {
            return ToolResult(output = "READ_CALL_LOG permission denied. Ask the user to grant Call Log permission to the openCrow app in Android Settings > Apps > openCrow > Permissions.", isError = true)
        }
        val typeFilter = (args["type"] as? String ?: "all").lowercase()
        val limit  = (args["limit"] as? Double)?.toInt() ?: 10
        val format = (args["response_format"] as? String ?: "detailed").lowercase()
        val typeSelection = when (typeFilter) {
            "incoming" -> "${CallLog.Calls.TYPE} = ${CallLog.Calls.INCOMING_TYPE}"
            "outgoing" -> "${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE}"
            "missed"   -> "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}"
            else       -> null
        }
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            typeSelection, null,
            "${CallLog.Calls.DATE} DESC"
        )
        data class CallEntry(val name: String, val number: String, val type: String, val date: String, val durationSec: Long)
        val calls = mutableListOf<CallEntry>()
        cursor?.use {
            var count = 0
            val numIdx  = it.getColumnIndex(CallLog.Calls.NUMBER)
            val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
            val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
            val durIdx  = it.getColumnIndex(CallLog.Calls.DURATION)
            while (it.moveToNext() && count < limit) {
                val number   = it.getString(numIdx) ?: "unknown"
                val name     = it.getString(nameIdx)?.takeIf { n -> n.isNotBlank() } ?: number
                val callType = when (it.getInt(typeIdx)) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE   -> "missed"
                    else                        -> "other"
                }
                calls.add(CallEntry(name, number, callType, formatTime(it.getLong(dateIdx)), it.getLong(durIdx)))
                count++
            }
        }
        if (calls.isEmpty()) return ToolResult(output = "No calls found")
        return if (format == "concise") {
            ToolResult(output = calls.joinToString("\n") { "${it.name} (${it.number}) - ${it.type} - ${it.date} - ${it.durationSec}s" })
        } else {
            val arr = org.json.JSONArray(calls.map { c ->
                org.json.JSONObject().apply {
                    put("name", c.name); put("number", c.number); put("type", c.type)
                    put("date", c.date); put("duration_seconds", c.durationSec)
                }
            })
            ToolResult(output = arr.toString())
        }
    }

    private suspend fun readCalendar(args: Map<String, Any>): ToolResult {
        if (!ensurePermission(Manifest.permission.READ_CALENDAR)) {
            return ToolResult(output = "READ_CALENDAR permission denied. Ask the user to grant Calendar permission to the openCrow app in Android Settings > Apps > openCrow > Permissions.", isError = true)
        }
        val limit = (args["limit"] as? Double)?.toInt() ?: 10
        val now = System.currentTimeMillis()
        val cursor = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND
            ),
            "${CalendarContract.Events.DTSTART} >= ?",
            arrayOf(now.toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        )
        val events = mutableListOf<org.json.JSONObject>()
        cursor?.use {
            var count = 0
            while (it.moveToNext() && count < limit) {
                val id    = it.getLong(0)
                val title = it.getString(1) ?: "Untitled"
                val start = it.getLong(2)
                val end   = it.getLong(3)
                events.add(org.json.JSONObject().apply {
                    put("id", id); put("title", title)
                    put("start", formatTime(start)); put("end", formatTime(end))
                })
                count++
            }
        }
        if (events.isEmpty()) return ToolResult(output = "No upcoming events found")
        return ToolResult(output = org.json.JSONArray(events).toString())
    }

    private suspend fun deleteCalendarEvent(args: Map<String, Any>): ToolResult {
        val eventId = when (val raw = args["event_id"]) {
            is Double -> raw.toLong()
            is Long   -> raw
            is Int    -> raw.toLong()
            is String -> raw.toLongOrNull()
            else      -> null
        } ?: return ToolResult(output = "event_id is required and must be a number", isError = true)

        if (!ensurePermission(Manifest.permission.WRITE_CALENDAR)) {
            return ToolResult(output = "WRITE_CALENDAR permission denied. Ask the user to grant Calendar permission to the openCrow app in Android Settings > Apps > openCrow > Permissions.", isError = true)
        }

        return withContext(Dispatchers.IO) {
            try {
                val uri = android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
                val deleted = context.contentResolver.delete(uri, null, null)
                if (deleted > 0) {
                    ToolResult(output = "Calendar event $eventId deleted successfully")
                } else {
                    ToolResult(output = "No event found with id=$eventId (may already be deleted)", isError = true)
                }
            } catch (e: Exception) {
                ToolResult(output = "Failed to delete calendar event: ${e.message}", isError = true)
            }
        }
    }

    private fun listApps(args: Map<String, Any>): ToolResult {
        val query  = (args["query"] as? String)?.lowercase() ?: ""
        val limit  = (args["limit"] as? Double)?.toInt() ?: 20
        val format = (args["response_format"] as? String ?: "detailed").lowercase()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        @Suppress("QueryPermissionsNeeded")
        val activities = pm.queryIntentActivities(intent, 0)
        if (activities.isEmpty()) {
            return ToolResult(
                output = "No apps found. Package visibility may be restricted by the OS (e.g. GrapheneOS). " +
                         "Grant the 'QUERY_ALL_PACKAGES' permission in Settings if available.",
                isError = true
            )
        }
        data class AppEntry(val name: String, val pkg: String)
        val apps = activities
            .mapNotNull { ri ->
                val label = ri.loadLabel(pm).toString()
                val pkg   = ri.activityInfo.packageName
                if (query.isBlank() || label.lowercase().contains(query) || pkg.lowercase().contains(query))
                    AppEntry(label, pkg)
                else null
            }
            .sortedBy { it.name }
            .take(limit)
        if (apps.isEmpty()) return ToolResult(output = "No apps matched '$query'")
        return if (format == "concise") {
            ToolResult(output = apps.joinToString("\n") { "${it.name} (${it.pkg})" })
        } else {
            val arr = org.json.JSONArray(apps.map { a ->
                org.json.JSONObject().apply { put("name", a.name); put("package", a.pkg) }
            })
            ToolResult(output = arr.toString())
        }
    }

    private suspend fun openApp(args: Map<String, Any>): ToolResult {
        val packageName = args["package_name"] as? String
        val appName     = (args["app_name"] as? String)?.lowercase()
        val pm = context.packageManager
        val launchIntent: Intent? = when {
            packageName != null -> pm.getLaunchIntentForPackage(packageName)
            appName != null -> {
                val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                @Suppress("QueryPermissionsNeeded")
                pm.queryIntentActivities(launcherIntent, 0)
                    .firstOrNull { ri -> ri.loadLabel(pm).toString().lowercase().contains(appName) }
                    ?.let { ri -> pm.getLaunchIntentForPackage(ri.activityInfo.packageName) }
            }
            else -> null
        }
        if (launchIntent == null) {
            return ToolResult(
                output = "App not found: '${packageName ?: appName}'. " +
                         "Package visibility may be restricted (e.g. GrapheneOS). " +
                         "Try providing the exact package name.",
                isError = true
            )
        }
        withContext(Dispatchers.Main) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
        return ToolResult(output = "Launched ${packageName ?: appName}")
    }

    private fun getBattery(): ToolResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level  = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val statusStr = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING     -> "charging"
            BatteryManager.BATTERY_STATUS_FULL         -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING  -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else                                        -> "unknown"
        }
        val json = org.json.JSONObject().apply {
            put("level_percent", level)
            put("status", statusStr)
        }
        return ToolResult(output = json.toString())
    }

    private suspend fun getLocation(): ToolResult {
        if (!ensurePermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (!ensurePermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                return ToolResult(output = "Location permission denied. Ask the user to grant location permission to the openCrow app in Android Settings > Apps > openCrow > Permissions.", isError = true)
            }
        }
        return withContext(Dispatchers.IO) {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val providers = lm.getProviders(true)
                if (providers.isEmpty()) return@withContext ToolResult(output = "No location providers available. Ask the user to enable GPS in device settings.", isError = true)
                var best: android.location.Location? = null
                for (provider in providers) {
                    try {
                        @Suppress("MissingPermission")
                        val loc = lm.getLastKnownLocation(provider)
                        if (loc != null && (best == null || loc.accuracy < best.accuracy)) {
                            best = loc
                        }
                    } catch (_: Exception) {}
                }
                if (best != null) {
                    val json = org.json.JSONObject().apply {
                        put("latitude",  best.latitude)
                        put("longitude", best.longitude)
                        put("accuracy_meters", best.accuracy)
                        put("provider", best.provider ?: "unknown")
                    }
                    ToolResult(output = json.toString())
                } else {
                    ToolResult(output = "Location not available. Ask the user to ensure GPS is enabled and try again.", isError = true)
                }
            } catch (e: Exception) {
                ToolResult(output = "Failed to get location: ${e.message}", isError = true)
            }
        }
    }

    private fun setVolume(args: Map<String, Any>): ToolResult {
        val levelPct = (args["level"] as? Double)?.toInt() ?: (args["level"] as? Long)?.toInt()
            ?: return ToolResult(output = "Missing required argument: level", isError = true)
        val streamName = (args["stream"] as? String ?: "music").lowercase()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when (streamName) {
            "ring"            -> AudioManager.STREAM_RING
            "notification"    -> AudioManager.STREAM_NOTIFICATION
            "alarm"           -> AudioManager.STREAM_ALARM
            "voice_call"      -> AudioManager.STREAM_VOICE_CALL
            else              -> AudioManager.STREAM_MUSIC
        }
        val maxVol = am.getStreamMaxVolume(stream)
        val targetVol = (levelPct.coerceIn(0, 100) * maxVol / 100)
        return try {
            am.setStreamVolume(stream, targetVol, 0)
            ToolResult(output = "Volume set to $levelPct% on stream '$streamName'")
        } catch (e: SecurityException) {
            ToolResult(output = "Cannot change volume: ${e.message}", isError = true)
        }
    }

    private fun setRingerMode(args: Map<String, Any>): ToolResult {
        val mode = (args["mode"] as? String ?: "normal").lowercase()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            when (mode) {
                "silent"  -> am.ringerMode = AudioManager.RINGER_MODE_SILENT
                "vibrate" -> am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                else      -> am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            ToolResult(output = "Ringer mode set to '$mode'")
        } catch (e: SecurityException) {
            ToolResult(output = "Cannot change ringer mode (Do Not Disturb may be blocking this): ${e.message}", isError = true)
        }
    }

    private suspend fun readSms(args: Map<String, Any>): ToolResult {
        if (!ensurePermission(Manifest.permission.READ_SMS)) {
            return ToolResult(output = "READ_SMS permission denied. Ask the user to grant SMS permission to the openCrow app in Android Settings > Apps > openCrow > Permissions.", isError = true)
        }
        val typeFilter = (args["type"] as? String ?: "inbox").lowercase()
        val limit = (args["limit"] as? Double)?.toInt() ?: 10
        val uri = when (typeFilter) {
            "sent" -> Uri.parse("content://sms/sent")
            "all"  -> Uri.parse("content://sms/")
            else   -> Uri.parse("content://sms/inbox")
        }
        val cursor = context.contentResolver.query(
            uri,
            arrayOf("address", "body", "date", "read"),
            null, null,
            "date DESC"
        )
        val messages = mutableListOf<org.json.JSONObject>()
        cursor?.use {
            var count = 0
            val addrIdx = it.getColumnIndex("address")
            val bodyIdx = it.getColumnIndex("body")
            val dateIdx = it.getColumnIndex("date")
            while (it.moveToNext() && count < limit) {
                val addr = it.getString(addrIdx) ?: "unknown"
                val body = it.getString(bodyIdx)?.take(200) ?: ""
                val date = formatTime(it.getLong(dateIdx))
                messages.add(org.json.JSONObject().apply {
                    put("from", addr); put("body", body); put("date", date)
                })
                count++
            }
        }
        if (messages.isEmpty()) return ToolResult(output = "No messages found")
        return ToolResult(output = org.json.JSONArray(messages).toString())
    }

    private fun getWifiInfo(): ToolResult {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wm.isWifiEnabled) {
                val json = org.json.JSONObject().apply { put("connected", false) }
                return ToolResult(output = json.toString())
            }
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            @Suppress("DEPRECATION")
            val ssid = info.ssid?.removeSurrounding("\"") ?: "unknown"
            @Suppress("DEPRECATION")
            val rssi = info.rssi
            val level = WifiManager.calculateSignalLevel(rssi, 5)
            @Suppress("DEPRECATION")
            val ipInt = info.ipAddress
            val ip = "${ipInt and 0xff}.${(ipInt shr 8) and 0xff}.${(ipInt shr 16) and 0xff}.${(ipInt shr 24) and 0xff}"
            val json = org.json.JSONObject().apply {
                put("connected", true)
                put("ssid", ssid)
                put("signal_level", level)
                put("ip", ip)
                put("rssi_dbm", rssi)
            }
            ToolResult(output = json.toString())
        } catch (e: Exception) {
            ToolResult(output = "Failed to get WiFi info: ${e.message}", isError = true)
        }
    }

    private fun getDeviceInfo(): ToolResult {
        val display = context.resources.displayMetrics
        val density = display.density
        val json = org.json.JSONObject().apply {
            put("brand",           Build.BRAND)
            put("model",           Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("api_level",       Build.VERSION.SDK_INT)
            put("screen_width_dp",  (display.widthPixels  / density).toInt())
            put("screen_height_dp", (display.heightPixels / density).toInt())
        }
        return ToolResult(output = json.toString())
    }

    private fun sendNotification(args: Map<String, Any>): ToolResult {
        val title   = args["title"] as? String ?: return ToolResult(output = "Missing required argument: title", isError = true)
        val message = args["message"] as? String ?: return ToolResult(output = "Missing required argument: message", isError = true)
        val channel = (args["channel"] as? String ?: "default").lowercase()
        val channelId = if (channel == "alert") NOTIF_CHANNEL_ALERT else NOTIF_CHANNEL_DEFAULT
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannels(nm)
        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(if (channel == "alert") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notif)
        return ToolResult(output = "Notification sent: '$title'")
    }

    private fun webOpen(args: Map<String, Any>): ToolResult {
        val url = args["url"] as? String ?: return ToolResult(output = "Missing required argument: url", isError = true)
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(output = "Opened $url in browser")
        } catch (e: Exception) {
            ToolResult(output = "Failed to open URL: ${e.message}", isError = true)
        }
    }

    private fun setBrightness(args: Map<String, Any>): ToolResult {
        val levelPct = (args["level"] as? Double)?.toInt() ?: (args["level"] as? Long)?.toInt()
            ?: return ToolResult(output = "Missing required argument: level", isError = true)
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(intent)
                ToolResult(output = "Please grant 'Modify system settings' permission and try again.", isError = true)
            } catch (_: Exception) {
                ToolResult(output = "WRITE_SETTINGS permission required to change brightness.", isError = true)
            }
        }
        return try {
            val brightnessVal = (levelPct.coerceIn(0, 100) * 255 / 100)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightnessVal)
            ToolResult(output = "Brightness set to $levelPct%")
        } catch (e: Exception) {
            ToolResult(output = "Failed to set brightness: ${e.message}", isError = true)
        }
    }

    private fun toggleFlashlight(args: Map<String, Any>): ToolResult {
        val turnOn = args["on"] as? Boolean ?: return ToolResult(output = "Missing required argument: on", isError = true)
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull() ?: return ToolResult(output = "No camera found", isError = true)
            cm.setTorchMode(cameraId, turnOn)
            ToolResult(output = "Flashlight turned ${if (turnOn) "on" else "off"}")
        } catch (e: Exception) {
            ToolResult(output = "Failed to toggle flashlight: ${e.message}", isError = true)
        }
    }

    private fun mediaControl(args: Map<String, Any>): ToolResult {
        val action = (args["action"] as? String ?: "").lowercase()
        val keyCode = when (action) {
            "play"     -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause"    -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next"     -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop"     -> KeyEvent.KEYCODE_MEDIA_STOP
            else       -> return ToolResult(output = "Unknown media action: '$action'. Use play, pause, next, previous, or stop.", isError = true)
        }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up   = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            am.dispatchMediaKeyEvent(down)
            am.dispatchMediaKeyEvent(up)
            ToolResult(output = "Media action '$action' dispatched")
        } catch (e: Exception) {
            ToolResult(output = "Failed to dispatch media action: ${e.message}", isError = true)
        }
    }

    private suspend fun startTimer(args: Map<String, Any>): ToolResult {
        val seconds = (args["seconds"] as? Double)?.toInt() ?: (args["seconds"] as? Long)?.toInt()
            ?: return ToolResult(output = "Missing required argument: seconds", isError = true)
        val label = args["label"] as? String ?: ""
        return try {
            val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds)
                if (label.isNotBlank()) putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) { context.startActivity(intent) }
            val minutes = seconds / 60
            val secs    = seconds % 60
            val timeStr = if (minutes > 0) "${minutes}m ${secs}s" else "${secs}s"
            ToolResult(output = "Timer started for $timeStr${if (label.isNotBlank()) " -- $label" else ""}")
        } catch (e: Exception) {
            ToolResult(output = "Failed to start timer: ${e.message}", isError = true)
        }
    }

    private fun startStopwatch(): ToolResult {
        // Try standard SET_STOPWATCH intent first (supported by some clock apps)
        val intentsToTry = listOf(
            Intent("android.intent.action.SET_STOPWATCH"),
            // Google DeskClock doesn't handle SET_STOPWATCH; fall back to opening the clock app directly
            Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).apply {
                `package` = "com.google.android.deskclock"
            },
            // Last resort: open whatever clock app handles SHOW_ALARMS
            Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
        )
        for (intent in intentsToTry) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return ToolResult(output = "Stopwatch opened")
            } catch (_: android.content.ActivityNotFoundException) {
                // Try next
            }
        }
        return ToolResult(output = "No clock app found to open stopwatch", isError = true)
    }

    private fun listAlarms(): ToolResult {
        val providerUris = listOf(
            Uri.parse("content://com.android.deskclock/alarm"),
            Uri.parse("content://com.google.android.deskclock/alarm"),
            Uri.parse("content://com.android.deskclock/alarms"),
            Uri.parse("content://com.google.android.deskclock/alarms")
        )

        val alarms = mutableListOf<Map<String, Any?>>()
        for (uri in providerUris) {
            alarms += queryAlarmProvider(uri)
        }

        // Merge provider results with locally tracked alarms (provider results take precedence).
        // De-duplicate by hour+minute+label so tracked alarms that also appear in the provider
        // are not listed twice.
        val deduped = linkedMapOf<String, Map<String, Any?>>()
        for (alarm in trackedAlarms() + alarms) {
            val key = "%02d:%02d:%s".format(alarm["hour"], alarm["minute"], alarm["label"] ?: "")
            deduped[key] = alarm
        }

        if (deduped.isEmpty()) return ToolResult(output = "No alarms found")
        val json = org.json.JSONArray(deduped.values.map { entry ->
            org.json.JSONObject(entry.filterValues { it != null })
        }).toString()
        return ToolResult(output = json)
    }

    private fun deleteAlarm(args: Map<String, Any>): ToolResult {
        val hour   = (args["hour"]   as? Number)?.toInt() ?: return ToolResult(output = "Missing hour", isError = true)
        val minute = (args["minute"] as? Number)?.toInt() ?: 0
        val label  = args["label"]  as? String

        // Try to delete via content provider first (permanent removal).
        // We query each known provider URI, find the row matching hour/minute/(label),
        // and delete it by _id.
        val providerUris = listOf(
            Uri.parse("content://com.google.android.deskclock/alarm"),
            Uri.parse("content://com.android.deskclock/alarm"),
            Uri.parse("content://com.google.android.deskclock/alarms"),
            Uri.parse("content://com.android.deskclock/alarms")
        )
        for (baseUri in providerUris) {
            try {
                val cursor = context.contentResolver.query(baseUri, null, null, null, null)
                    ?: continue
                var rowId: String? = null
                cursor.use {
                    while (it.moveToNext()) {
                        val rowHour = it.getColumnIndex("hour").takeIf { i -> i >= 0 }
                            ?.let { i -> it.getInt(i) }
                        val rowMin = (it.getColumnIndex("minutes").takeIf { i -> i >= 0 }
                            ?: it.getColumnIndex("minute").takeIf { i -> i >= 0 })
                            ?.let { i -> it.getInt(i) }
                        if (rowHour != hour || rowMin != minute) continue
                        if (label != null) {
                            val rowLabel = (it.getColumnIndex("message").takeIf { i -> i >= 0 }
                                ?: it.getColumnIndex("label").takeIf { i -> i >= 0 })
                                ?.let { i -> it.getString(i) }
                            if (rowLabel != null && rowLabel != label) continue
                        }
                        rowId = it.getColumnIndex("_id").takeIf { i -> i >= 0 }
                            ?.let { i -> it.getString(i) }
                        break
                    }
                }
                if (rowId != null) {
                    val deleteUri = Uri.withAppendedPath(baseUri, rowId)
                    val deleted = context.contentResolver.delete(deleteUri, null, null)
                    if (deleted > 0) {
                        untrackAlarm(hour, minute, label)
                        Log.d(TAG, "deleteAlarm: deleted via provider $baseUri id=$rowId")
                        return ToolResult(output = "Alarm at %02d:%02d deleted".format(hour, minute))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "deleteAlarm: provider $baseUri failed: ${e.message}")
            }
        }

        // If provider deletion didn't work, fall back to untracking only.
        // The alarm may still exist in the clock app but we can't remove it programmatically.
        untrackAlarm(hour, minute, label)
        Log.w(TAG, "deleteAlarm: could not delete via provider, removed from tracking only")
        return ToolResult(
            output = "Alarm at %02d:%02d removed from tracking. " +
                     "If it still appears in your clock app, please delete it manually.".format(hour, minute)
        )
    }

    private fun queryAlarmProvider(uri: Uri): List<Map<String, Any?>> {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return emptyList()
            val alarms = mutableListOf<Map<String, Any?>>()
            cursor.use {
                while (it.moveToNext()) {
                    val row = mutableMapOf<String, Any?>()
                    for (col in it.columnNames) {
                        row[col] = try {
                            it.getString(it.getColumnIndexOrThrow(col))
                        } catch (_: Exception) {
                            null
                        }
                    }
                    val normalized = normalizeAlarmRow(row, uri.toString())
                    if (normalized != null) alarms += normalized
                }
            }
            alarms
        } catch (e: Exception) {
            Log.w(TAG, "listAlarms: provider $uri unavailable: ${e.message}")
            emptyList()
        }
    }

    private fun normalizeAlarmRow(row: Map<String, Any?>, source: String): Map<String, Any?>? {
        val hour = row["hour"]?.toString()?.toIntOrNull()
            ?: row["alarmtime"]?.toString()?.let { millis ->
                millis.toLongOrNull()?.let {
                    Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.HOUR_OF_DAY)
                }
            }
        val minute = row["minutes"]?.toString()?.toIntOrNull()
            ?: row["minute"]?.toString()?.toIntOrNull()
            ?: row["alarmtime"]?.toString()?.let { millis ->
                millis.toLongOrNull()?.let {
                    Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.MINUTE)
                }
            }

        if (hour == null || minute == null) return null

        return linkedMapOf(
            "hour" to hour,
            "minute" to minute,
            "label" to (row["message"] ?: row["label"] ?: "Alarm"),
            "enabled" to ((row["enabled"] ?: row["is_enabled"] ?: "1").toString() != "0"),
            "days" to (row["daysofweek"] ?: row["days_of_week"] ?: row["days"] ?: ""),
            "source" to source,
            "raw" to org.json.JSONObject(row)
        )
    }

    // ─── Alarm tracking ──────────────────────────────────────────────────────
    // Android has no public API to list alarms, so we persist alarms set via
    // set_alarm in SharedPreferences and merge them with any content-provider
    // results in listAlarms().

    private fun trackAlarm(hour: Int, minute: Int, label: String, days: List<String>) {
        val prefs = context.getSharedPreferences(ALARM_PREFS_NAME, Context.MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString(PREF_ALARMS, "[]") ?: "[]")
        val entry = org.json.JSONObject().apply {
            put("hour", hour)
            put("minute", minute)
            put("label", label)
            put("days", org.json.JSONArray(days))
            put("enabled", true)
            put("source", "opencrow_tracked")
        }
        arr.put(entry)
        prefs.edit().putString(PREF_ALARMS, arr.toString()).apply()
        Log.d(TAG, "trackAlarm: stored $hour:$minute '$label' days=$days")
    }

    private fun trackedAlarms(): List<Map<String, Any?>> {
        val prefs = context.getSharedPreferences(ALARM_PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            val arr = org.json.JSONArray(prefs.getString(PREF_ALARMS, "[]") ?: "[]")
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(linkedMapOf(
                        "hour"    to obj.optInt("hour"),
                        "minute"  to obj.optInt("minute"),
                        "label"   to obj.optString("label", "Alarm"),
                        "enabled" to obj.optBoolean("enabled", true),
                        "days"    to (obj.optJSONArray("days")?.let { d ->
                            buildList { for (j in 0 until d.length()) add(d.optString(j)) }
                        } ?: emptyList<String>()),
                        "source"  to obj.optString("source", "opencrow_tracked")
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "trackedAlarms: parse error: ${e.message}")
            emptyList()
        }
    }

    private fun untrackAlarm(hour: Int, minute: Int, label: String?) {
        val prefs = context.getSharedPreferences(ALARM_PREFS_NAME, Context.MODE_PRIVATE)
        val src = try {
            org.json.JSONArray(prefs.getString(PREF_ALARMS, "[]") ?: "[]")
        } catch (_: Exception) { org.json.JSONArray() }
        val filtered = org.json.JSONArray()
        for (i in 0 until src.length()) {
            val obj = src.optJSONObject(i) ?: continue
            if (obj.optInt("hour") == hour &&
                obj.optInt("minute") == minute &&
                (label == null || obj.optString("label") == label)) continue
            filtered.put(obj)
        }
        prefs.edit().putString(PREF_ALARMS, filtered.toString()).apply()
        Log.d(TAG, "untrackAlarm: removed $hour:$minute '$label'")
    }

    // ─── Notification channels ───────────────────────────────────────────────

    private fun ensureNotificationChannels(nm: NotificationManager) {
        if (nm.getNotificationChannel(NOTIF_CHANNEL_DEFAULT) == null) {
            val ch = NotificationChannel(NOTIF_CHANNEL_DEFAULT, "openCrow Notifications", NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(NOTIF_CHANNEL_ALERT) == null) {
            val ch = NotificationChannel(NOTIF_CHANNEL_ALERT, "openCrow Alerts", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(ch)
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun parseEpochMs(value: Any?): Long? = when (value) {
        is Double -> value.toLong()
        is Long   -> value
        is String -> value.toLongOrNull()
        else      -> null
    }

    private fun formatTime(epochMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        return "%04d-%02d-%02d %02d:%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    }

    private fun showNoCalendarNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannels(nm)

        // Deep-link to account sync settings so the user can add a calendar account
        val settingsIntent = Intent(Settings.ACTION_SYNC_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            context, 0, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, NOTIF_CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Calendar not configured")
            .setContentText("openCrow couldn't find a writable calendar. Tap to add a calendar account.")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("openCrow tried to create a calendar event but found no writable calendar on this device.\n\nTap to open account settings and add a Google (or other) calendar account."))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(NOTIF_ID_NO_CALENDAR, notif)
    }

    /**
     * Show a local Android notification triggered by an incoming UnifiedPush message.
     * Public so UPReceiver can call it without executing a full tool dispatch.
     */
    fun showPushNotification(ctx: Context, title: String, body: String, channel: String, conversationId: String? = null) {
        val channelId = if (channel.lowercase() == "alert") NOTIF_CHANNEL_ALERT else NOTIF_CHANNEL_DEFAULT
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannels(nm)

        val tapIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                if (!conversationId.isNullOrEmpty()) putExtra("conversation_id", conversationId)
            }
        val pendingIntent = tapIntent?.let {
            PendingIntent.getActivity(ctx, conversationId.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val notif = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (channelId == NOTIF_CHANNEL_ALERT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notif)
    }

    data class ToolResult(val output: String, val isError: Boolean = false)

    // ─── UnifiedPush tools ───────────────────────────────────────────────────

    /**
     * Combined list + configure tool.
     * With no 'distributor' arg: lists available distributors and the active one.
     * With 'distributor' arg: activates it and registers with the server.
     */
    private suspend fun manageUnifiedPush(args: Map<String, Any>): ToolResult {
        val distributor = args["distributor"] as? String

        val distributors = org.unifiedpush.android.connector.UnifiedPush.getDistributors(context)
        val active = org.unifiedpush.android.connector.UnifiedPush.getAckDistributor(context)

        if (distributor == null) {
            // List mode
            val json = org.json.JSONObject().apply {
                put("available", org.json.JSONArray(distributors))
                put("active", active ?: org.json.JSONObject.NULL)
            }
            return ToolResult(output = json.toString())
        }

        // Configure mode
        if (distributor !in distributors) {
            return ToolResult(
                output = "Distributor '$distributor' is not installed. Available: ${distributors.joinToString()}",
                isError = true
            )
        }
        org.unifiedpush.android.connector.UnifiedPush.saveDistributor(context, distributor)
        val deviceId = apiClient.getDeviceId() ?: ""
        Log.i(TAG, "manageUnifiedPush: saving distributor='$distributor', calling register with instance='$deviceId'")
        org.unifiedpush.android.connector.UnifiedPush.register(context, instance = deviceId)

        val storedEndpoint = apiClient.getPushEndpoint()
        Log.i(TAG, "manageUnifiedPush: storedEndpoint='$storedEndpoint'")
        if (!storedEndpoint.isNullOrEmpty() && deviceId.isNotEmpty()) {
            return try {
                val resp = apiClient.api.registerDevice(
                    deviceId,
                    org.opencrow.app.data.remote.dto.RegisterDeviceRequest(
                        capabilities = org.opencrow.app.data.local.LocalToolCapabilities.all,
                        pushEndpoint  = storedEndpoint
                    )
                )
                if (resp.isSuccessful) {
                    Log.i(TAG, "manageUnifiedPush: server updated with existing endpoint=$storedEndpoint")
                    ToolResult(output = "UnifiedPush distributor set to '$distributor'. Push endpoint registered with server.")
                } else {
                    Log.w(TAG, "manageUnifiedPush: server rejected endpoint registration: ${resp.code()}")
                    ToolResult(output = "UnifiedPush distributor set to '$distributor' and registration requested. (Server returned ${resp.code()} when registering endpoint.)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "manageUnifiedPush: failed to register endpoint with server: ${e.message}")
                ToolResult(output = "UnifiedPush distributor set to '$distributor' and registration requested. (Could not reach server: ${e.message})")
            }
        }

        return ToolResult(output = "UnifiedPush distributor set to '$distributor' and registration requested. The app will receive push notifications once the distributor confirms the endpoint.")
    }
}
