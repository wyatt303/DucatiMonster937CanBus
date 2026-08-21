# Ducati Monster 937 Android App

Native Kotlin Android application for the Ducati Monster 937 CAN/BLE telemetry logger.

## Version

**0.1.0**

- Minimum Android: **12 / API 31**
- Compile SDK: **35**
- Target SDK: **35**
- Java: **17**

## Implemented

- BLE permissions for Android 12+
- Scan for `Ducati-Monster-937`
- BLE connection and service discovery
- Firmware, protocol, and Git build identification from the device-info characteristic
- 19-byte binary telemetry decoding
- Live RPM, gear, speed, throttle, front brake, engine and ambient temperature
- Android wall-clock timestamp on packet reception
- BLE sequence/drop detection
- Persistent ride sessions written incrementally to app-private storage
- Start, Pause, Resume, and Stop recording without interrupting live telemetry
- Saved ride history with per-ride CSV export and deletion
- Recovery of interrupted Recording sessions and restoration of resumable Paused sessions
- Configurable retention of 5, 10, 20, 50, or unlimited saved rides
- Firmware update from a PlatformIO `firmware.bin` file over BLE OTA

## Ride sessions

Starting a ride immediately creates a CSV file and metadata under the app's
private `files/ride_sessions` directory. The existing Telemetry Overlay CSV
header and column order are preserved. Each telemetry row is appended and
flushed as it arrives, so the complete ride is never held in memory.

Pause stops file appends only. BLE remains connected and the live dashboard
continues to update. Resume writes to the same session and CSV file. Session
metadata tracks wall-clock duration, cumulative paused duration, and active
recording duration; no pause-marker rows are added to the CSV.

Stop saves a non-empty session in Saved rides. Export copies its private CSV
through Android's document picker; stopping does not export automatically.
Empty sessions are discarded. Saved rides can also be deleted manually.

If the process ends while a session is Recording, the next startup marks its
valid persisted data as Recovered and makes it available for export. A ride
that the user deliberately Paused remains Paused across application restarts
and can be resumed later. Malformed metadata for one ride is ignored without
preventing other rides from loading.

## Ignition-off and BLE reconnect

Ride state and BLE connection state are independent. Turning the motorcycle
off can power down the ESP32 and disconnect BLE, but it does not finish or split
an active ride. Android automatically scans for the same previously connected
Ducati device using a bounded retry sequence (immediate, 2, 5, 10, 15, then 30
seconds between attempts).

While Recording, a disconnect leaves the same session open. No rows are
generated while telemetry is unavailable; after subscriptions are restored,
real samples append automatically to the same CSV, leaving an accurate
timestamp gap. While Paused, the ride remains Paused through ignition off/on
and reconnection. Pause duration continues accumulating, and the user must
press Resume after the bike is fully connected.

Only Stop/Finish Ride completes a session. An explicit Disconnect cancels
automatic reconnect attempts until the user connects again. Automatic
reconnect restores telemetry notifications, device information, and OTA
availability without creating a Ride Session.

The Recording setting retains the newest 10 completed/recovered sessions by
default. Available limits are 5, 10, 20, 50, and Unlimited. Cleanup is oldest
first and never targets an active or paused session.

## Not yet implemented

- Background recording service
- GPS
- GPX export
- Advanced dashboard

The storage/export separation is intended to support future GPS fields and a
GPX exporter. GPS and GPX are not implemented yet.

## Build

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Protocol:

```text
../protocol/BLE_PROTOCOL.md
```
