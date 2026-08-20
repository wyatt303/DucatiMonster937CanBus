# Ducati Monster 937 BLE Protocol

Protocol version: **1**

The firmware and Android application in this repository communicate using this
protocol. Changes to packet layout should increment the protocol version.

## BLE device

Device name:

```text
Ducati-Monster-937
```

## Main service

```text
7f6d0001-8b7a-4f7b-9d8a-937000000001
```

| Characteristic | UUID | Direction |
|---|---|---|
| Telemetry | `7f6d0002-8b7a-4f7b-9d8a-937000000001` | ESP32 → Android NOTIFY |
| Device info | `7f6d0003-8b7a-4f7b-9d8a-937000000001` | Android READ |
| Command | `7f6d0004-8b7a-4f7b-9d8a-937000000001` | Android → ESP32 WRITE |

### Device info

The readable device-info characteristic contains UTF-8 text in this format:

```text
fw=<version>;protocol=<number>;build=<short_git_sha>
```

Example:

```text
fw=0.3.0;protocol=1;build=94c120d
```

`fw` is the semantic firmware version, `protocol` identifies this BLE protocol,
and `build` is the seven-character Git commit SHA. Builds made without available
Git metadata use `unknown` for the build value.

## Telemetry packet

Exactly **19 bytes**, little-endian.

| Offset | Size | Field | Encoding |
|---:|---:|---|---|
| 0 | 4 | sequence | `uint32` |
| 4 | 4 | ESP32 timestamp | `uint32`, milliseconds from boot |
| 8 | 2 | RPM | `uint16` |
| 10 | 1 | gear | `int8` |
| 11 | 2 | speed | `uint16`, km/h × 100 |
| 13 | 2 | throttle | `uint16`, percent × 100 |
| 15 | 2 | front brake | `uint16`, percent × 100 |
| 17 | 1 | engine temperature | `int8`, °C |
| 18 | 1 | ambient temperature | `int8`, °C |

Android conversions:

```text
speed_kmh = speed / 100.0
throttle_percent = throttle / 100.0
front_brake_percent = front_brake / 100.0
```

The ESP32 timestamp is monotonic and is not a wall-clock timestamp.
Android should attach the phone's wall-clock timestamp when the packet is
received.

The sequence number is useful for detecting dropped BLE notifications.

## OTA service

Service:

```text
7f6d0010-8b7a-4f7b-9d8a-937000000001
```

| Characteristic | UUID | Direction |
|---|---|---|
| OTA control | `7f6d0011-8b7a-4f7b-9d8a-937000000001` | Android → ESP32 |
| OTA data | `7f6d0012-8b7a-4f7b-9d8a-937000000001` | Android → ESP32 |
| OTA status | `7f6d0013-8b7a-4f7b-9d8a-937000000001` | ESP32 → Android NOTIFY |

### OTA control

START:

```text
byte 0      0x01
bytes 1..4  firmware size, uint32 LE
bytes 5..8  expected CRC32, uint32 LE
```

END:

```text
byte 0      0x02
```

ABORT:

```text
byte 0      0x03
```

### OTA data

```text
bytes 0..3  absolute firmware offset, uint32 LE
bytes 4..N   firmware data
```

The ESP32 requires each offset to equal the number of bytes already received.

CRC32 uses the standard CRC-32/ISO-HDLC value, equivalent to Java's
`java.util.zip.CRC32`.

### OTA status

Six bytes:

```text
byte 0      status
bytes 1..4  value, uint32 LE
byte 5      reserved
```

Status:

```text
0x01 READY
0x02 PROGRESS
0x03 SUCCESS
0x04 ERROR
```

For PROGRESS, `value` is bytes received.
For ERROR, `value` is the error code.
