# Rainfall Monitor v2

A real-time rainfall monitoring Android app for **Motorola Moto G64 (Android 15)**.

Receives live rainfall data from an ESP32 sensor via **HiveMQ Cloud MQTT** and displays intensity, hourly rate, and historical graphs.

## Tech Stack

- **Language:** Kotlin
- **Min SDK:** 26 (Android 8.0) • **Target SDK:** 35 (Android 15)
- **Database:** Room
- **MQTT:** HiveMQ Client v5 (persistent session, QoS 1)
- **Charting:** MPAndroidChart (stepped line, locked Y-axis)
- **Architecture:** Single-activity, Repository pattern, ViewModel + StateFlow

## Building

```bash
# Debug build
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Configuration

MQTT credentials are stored in `local.properties` (git-ignored) and injected at compile time via `BuildConfig`:

```properties
mqtt.broker.host=your-hivemq-host
mqtt.broker.port=8883
mqtt.username=your-username
mqtt.password=your-password
mqtt.topic.status=rainfall/status
```

## License

Private project — not licensed for redistribution.
