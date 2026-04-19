# openCrow Companion App

Android companion app for the [openCrow](../openCrow/) self-hosted AI assistant platform.

## Architecture

- **Kotlin** + **Jetpack Compose** with Material Design 3
- **Room** SQLite for local config, chat cache, and settings
- **Retrofit** + **OkHttp** for server communication
- **CameraX** + **ML Kit** for QR code scanning
- **WorkManager** for background heartbeat scheduling

## Setup

1. Open in Android Studio (Hedgehog or newer)
2. Optionally run `scripts/download-fonts.sh` to get Space Grotesk, Inter, and JetBrains Mono fonts
3. Build and run on a device or emulator (API 29+)

## Pairing

The app requires scanning a QR code from the openCrow web UI:
- Go to **Settings → Devices** in the web UI
- Click **Add Device** or **Re-Pair** on an existing companion app
- Scan the QR code with the app

The QR encodes: `{ id, server, accessToken, refreshToken }`

## Features

- **Chat** — full conversation interface mirroring the web UI
- **Voice input** — hold mic to record, auto-transcribes via Whisper
- **Chat history** — bottom sheet with system chat toggle
- **Settings** — local config + all server config tabs
- **Heartbeat** — periodic background wake-up that checks calendar, tasks, and notifications
- **Device tasks** — polls and executes tasks assigned by the AI (alarms, contacts, calls, SMS, calendar events)

## Project Structure

```
app/src/main/java/org/opencrow/app/
├── data/
│   ├── local/          # Room database, DAOs, entities
│   └── remote/         # Retrofit API, DTOs, ApiClient with token refresh
├── heartbeat/          # WorkManager heartbeat worker + scheduler
├── ui/
│   ├── navigation/     # Compose Navigation host
│   ├── screens/        # Chat, QR Scan, Settings screens
│   └── theme/          # M3 theme (Color, Type, Shape, Spacing)
├── MainActivity.kt
└── OpenCrowApp.kt
```
