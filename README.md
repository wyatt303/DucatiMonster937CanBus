# Ducati Monster 937 CAN Bus

CAN-bus reverse engineering and telemetry logger for the 2021 Ducati Monster 937.

## Repository structure

```text
DucatiMonster937CanBus/
├── firmware/          ESP32-S3 + CAN + BLE + OTA
├── android/           Android BLE telemetry application
├── protocol/          Shared BLE protocol specification
├── CANBUS_FINDINGS.md CAN reverse-engineering findings
└── README.md
```

The firmware and Android application deliberately live in the same repository,
but remain independent buildable projects.

## System architecture

```text
Ducati CAN
    │
    ▼
SN65HVD230
    │
    ▼
XIAO ESP32-S3
    │
    ├── CAN decoder
    ├── binary telemetry
    └── BLE OTA
    │
    ▼
Android phone
    │
    ├── live dashboard
    ├── phone timestamps
    ├── recording
    └── CSV export
```

## Current telemetry

The current protocol contains:

- RPM
- Gear
- Wheel speed
- Throttle
- Front brake
- Engine temperature
- Ambient temperature

Engine state and side stand are intentionally omitted for now.

## Build firmware

```bash
cd firmware
pio run
pio run --target upload
pio device monitor
```

## Build Android

Open the `android/` directory in Android Studio.

The Android project currently contains the BLE scanner/connection layer and
19-byte telemetry decoder. OTA and CSV recording will be added on top of the
same protocol.

## Protocol

The single source of truth for communication between the two applications is:

```text
protocol/BLE_PROTOCOL.md
```

When the packet format changes, increment the protocol version and update both
implementations.

## Hardware

XIAO ESP32-S3 → SN65HVD230:

| SN65HVD230 | XIAO ESP32-S3 |
|---|---|
| VCC | 3V3 |
| GND | GND |
| CTX | D0 / GPIO1 |
| CRX | D1 / GPIO2 |
| CANH | Ducati CAN-H |
| CANL | Ducati CAN-L |

The ESP32 operates the CAN controller in listen-only mode.
