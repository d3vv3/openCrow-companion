package org.opencrow.app.data.local

import org.opencrow.app.data.remote.dto.DeviceCapability

/**
 * Canonical list of local tool capabilities this device exposes to the server.
 * Names are bare (no prefix) -- the server registers them as-is.
 * LocalToolExecutor also strips any accidental on_device_ prefix before dispatch.
 */
object LocalToolCapabilities {

    val all: List<DeviceCapability> = listOf(
        DeviceCapability(
            name = "set_alarm",
            description = "Set an alarm on the user's phone. Supports one-time or recurring alarms (e.g. every weekday).",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "hour"   to mapOf("type" to "integer", "description" to "Hour in 24h format (0-23)"),
                    "minute" to mapOf("type" to "integer", "description" to "Minute (0-59), defaults to 0"),
                    "label"  to mapOf("type" to "string",  "description" to "Optional alarm label"),
                    "days"   to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "string"),
                        "description" to "Days of the week for a recurring alarm. Values: 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'. Omit for a one-time alarm."
                    )
                ),
                "required" to listOf("hour")
            )
        ),
        DeviceCapability(
            name = "create_contact",
            description = "Open the phone's contact creation screen pre-filled with a name, phone and/or email",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "name"  to mapOf("type" to "string", "description" to "Full name of the contact"),
                    "phone" to mapOf("type" to "string", "description" to "Phone number (optional)"),
                    "email" to mapOf("type" to "string", "description" to "Email address (optional)")
                ),
                "required" to listOf("name")
            )
        ),
        DeviceCapability(
            name = "make_call",
            description = "Place a phone call directly (requires CALL_PHONE permission). Falls back to opening the dialer.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "phone_number" to mapOf("type" to "string", "description" to "Phone number to dial")
                ),
                "required" to listOf("phone_number")
            )
        ),
        DeviceCapability(
            name = "send_sms",
            description = "Open the SMS composer with a number and message pre-filled",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "phone_number" to mapOf("type" to "string", "description" to "Recipient phone number"),
                    "body"         to mapOf("type" to "string", "description" to "Message text (optional)")
                ),
                "required" to listOf("phone_number")
            )
        ),
        DeviceCapability(
            name = "create_calendar_event",
            description = "Create a calendar event silently on the user's device (no UI required)",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "title"       to mapOf("type" to "string", "description" to "Event title"),
                    "date"        to mapOf("type" to "string",  "description" to "Event date in YYYY-MM-DD format (e.g. '2025-07-20')"),
                    "start_time"  to mapOf("type" to "string",  "description" to "Start time in HH:MM 24h format (e.g. '09:30'), defaults to 09:00"),
                    "end_time"    to mapOf("type" to "string",  "description" to "End time in HH:MM 24h format (e.g. '10:30'), defaults to 1h after start"),
                    "location"    to mapOf("type" to "string",  "description" to "Event location (optional)"),
                    "description" to mapOf("type" to "string",  "description" to "Event description (optional)")
                ),
                "required" to listOf("title", "date")
            )
        ),
        DeviceCapability(
            name = "read_contacts",
            description = "Search or list contacts from the phone's address book",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string",  "description" to "Search query (optional, returns all if omitted)"),
                    "limit" to mapOf("type" to "integer", "description" to "Maximum number of results (default 30)")
                ),
                "required" to listOf<String>()
            )
        ),
        DeviceCapability(
            name = "read_call_log",
            description = "Read recent call history from the phone (incoming, outgoing, missed calls)",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "type"  to mapOf("type" to "string",  "description" to "Filter by call type: 'all', 'incoming', 'outgoing', 'missed' (default: 'all')"),
                    "limit" to mapOf("type" to "integer", "description" to "Maximum number of calls to return (default 10)")
                ),
                "required" to listOf<String>()
            )
        ),
        DeviceCapability(
            name = "read_calendar",
            description = "List upcoming calendar events from the phone",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "limit" to mapOf("type" to "integer", "description" to "Maximum number of events to return (default 10)")
                ),
                "required" to listOf<String>()
            )
        ),
        DeviceCapability(
            name = "list_apps",
            description = "List installed apps on the device, optionally filtered by name",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string",  "description" to "Optional name filter (case-insensitive)"),
                    "limit" to mapOf("type" to "integer", "description" to "Maximum number of apps to return (default 20)")
                ),
                "required" to listOf<String>()
            )
        ),
        DeviceCapability(
            name = "open_app",
            description = "Launch an installed app by its package name or display name",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "package_name" to mapOf("type" to "string", "description" to "Exact package name (e.g. com.example.app) -- preferred"),
                    "app_name"     to mapOf("type" to "string", "description" to "Display name to search for if package_name is not known")
                ),
                "required" to listOf<String>()
            )
        ),
        DeviceCapability(
            name = "get_battery",
            description = "Get the current battery level and charging state of the device",
            parameters = mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>(),
                "required" to listOf<String>()
            )
        ),
        DeviceCapability(
            name = "get_location",
            description = "Get the device's current GPS location (latitude, longitude, accuracy). Requires location permission.",
            parameters = mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>(),
                "required" to listOf<String>()
            )
        ),
        DeviceCapability(
            name = "set_volume",
            description = "Set the device volume for a specific audio stream",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "stream" to mapOf("type" to "string", "description" to "Audio stream: 'music', 'ring', 'notification', 'alarm', 'voice_call' (default: 'music')"),
                    "level"  to mapOf("type" to "integer", "description" to "Volume level 0-100 (percentage of max)")
                ),
                "required" to listOf("level")
            )
        ),
        DeviceCapability(
            name = "set_ringer_mode",
            description = "Set the phone ringer mode (normal, silent, vibrate)",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "mode" to mapOf("type" to "string", "description" to "Ringer mode: 'normal', 'silent', or 'vibrate'")
                ),
                "required" to listOf("mode")
            )
        ),
        DeviceCapability(
            name = "read_sms",
            description = "Read recent SMS messages from the phone",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "type"  to mapOf("type" to "string",  "description" to "Filter: 'inbox', 'sent', 'all' (default: 'inbox')"),
                    "limit" to mapOf("type" to "integer", "description" to "Maximum number of messages (default 10)")
                ),
                "required" to listOf<String>()
            )
        ),
        DeviceCapability(
            name = "get_wifi_info",
            description = "Get current WiFi connection info (SSID, signal strength, IP address)",
            parameters = mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>(),
                "required" to listOf<String>()
            )
        ),
        DeviceCapability(
            name = "get_device_info",
            description = "Get basic device information (model, Android version, screen info)",
            parameters = mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>(),
                "required" to listOf<String>()
            )
        ),
        DeviceCapability(
            name = "web_open",
            description = "Open a URL in the device's default browser",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "url" to mapOf("type" to "string", "description" to "URL to open (must include http:// or https://)")
                ),
                "required" to listOf("url")
            )
        ),
        DeviceCapability(
            name = "set_brightness",
            description = "Set the screen brightness (requires WRITE_SETTINGS permission)",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "level" to mapOf("type" to "integer", "description" to "Brightness 0-100 (percentage)")
                ),
                "required" to listOf("level")
            )
        ),
        DeviceCapability(
            name = "toggle_flashlight",
            description = "Turn the device flashlight (torch) on or off",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "on" to mapOf("type" to "boolean", "description" to "true to turn on, false to turn off")
                ),
                "required" to listOf("on")
            )
        ),
        DeviceCapability(
            name = "media_control",
            description = "Control media playback (play, pause, next, previous, stop)",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "action" to mapOf("type" to "string", "description" to "Media action: 'play', 'pause', 'next', 'previous', 'stop'")
                ),
                "required" to listOf("action")
            )
        ),
        DeviceCapability(
            name = "start_timer",
            description = "Start a countdown timer on the user's device for a given number of seconds",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "seconds" to mapOf("type" to "integer", "description" to "Timer duration in seconds (e.g. 300 for 5 minutes)"),
                    "label"   to mapOf("type" to "string",  "description" to "Optional label for the timer")
                ),
                "required" to listOf("seconds")
            )
        ),
        DeviceCapability(
            name = "list_alarms",
            description = "List all alarms currently set on the device",
            parameters = mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>(),
                "required" to listOf<String>()
            )
        ),
        DeviceCapability(
            name = "delete_alarm",
            description = "Delete a specific alarm from the device by matching hour, minute, and optionally label",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "hour"   to mapOf("type" to "integer", "description" to "Hour of the alarm to delete (24h format)"),
                    "minute" to mapOf("type" to "integer", "description" to "Minute of the alarm to delete"),
                    "label"  to mapOf("type" to "string",  "description" to "Optional label to match for more precise deletion")
                ),
                "required" to listOf("hour", "minute")
            )
        ),
        DeviceCapability(
            name = "start_stopwatch",
            description = "Start the stopwatch on the user's device",
            parameters = mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>(),
                "required" to listOf<String>()
            )
        ),
        DeviceCapability(
            name = "list_unified_push_distributors",
            description = "List all available UnifiedPush distributor apps installed on the device and which one is currently active",
            parameters = mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>(),
                "required" to listOf<String>()
            )
        ),
        DeviceCapability(
            name = "configure_unified_push",
            description = "Set the active UnifiedPush distributor on the device and register for push notifications. Use list_unified_push_distributors first to get available distributor package names.",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "distributor" to mapOf("type" to "string", "description" to "Package name of the UnifiedPush distributor app to use (e.g. 'org.unifiedpush.distributor.ntfy')")
                ),
                "required" to listOf("distributor")
            )
        )
    )
}
