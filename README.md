# Ducati Monster 937 CAN Bus

Reverse-engineering the CAN bus of a 2021 Ducati Monster Plus 937 with a Seeed XIAO ESP32-S3 and a Waveshare SN65HVD230 CAN transceiver.

See **[CANBUS_FINDINGS.md](CANBUS_FINDINGS.md)** for the full write-up: wiring, confirmed signal decodes (engine temp, ambient temp, gear, wheel speed, RPM, throttle position), ruled-out hypotheses from other Ducati platforms, and methodology notes.

## Hardware

- Seeed XIAO ESP32-S3
- Waveshare SN65HVD230 CAN transceiver
- Tapped into the bike's CAN bus (500 kbps, 11-bit IDs)

## Safety

All sniffing is done in `TWAI_MODE_LISTEN_ONLY` — the board can never transmit or ACK on the bus, so it's safe to leave connected to a live vehicle.

## Firmware

- `src/main.cpp` — the decode dashboard: reads confirmed signals off the bus and prints them over serial, one line per change.

## Building

```
pio run
pio run --target upload
```

Requires [PlatformIO](https://platformio.org/).
