# Ducati Monster 937 Android App

Android application for the Ducati Monster 937 BLE telemetry logger.

## Current state

- BLE scan for `Ducati-Monster-937`
- Connect over BLE
- Discover the Ducati BLE service
- Subscribe to telemetry notifications
- Decode the 19-byte binary telemetry packet
- Add Android wall-clock timestamps
- Display live telemetry

## Planned

- Recording start/stop
- CSV export
- OTA firmware selection/upload
- Firmware version display
- Connection diagnostics
- Dropped-packet detection
- Improved dashboard UI
