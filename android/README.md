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
- Start/stop recording
- CSV export through the Android document picker
- Firmware update from a PlatformIO `firmware.bin` file over BLE OTA

## Not yet implemented

- Background recording service
- GPS
- GoPro telemetry export
- Settings
- Advanced dashboard

## Build

```bash
./gradlew assembleDebug
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Protocol:

```text
../protocol/BLE_PROTOCOL.md
```
