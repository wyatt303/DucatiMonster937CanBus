# Ducati Monster 937 (2021) --- CAN Bus Reverse Engineering Findings

Reverse engineering notes for the 2021 Ducati Monster 937 CAN bus.

Hardware used during testing: - Seeed XIAO ESP32-S3 - Waveshare
SN65HVD230 CAN transceiver - SavvyCAN for capture and analysis - CAN bus
speed: 500 kbps - 11-bit standard CAN IDs - 8-byte CAN frames

All captures were performed in CAN listen-only mode.

------------------------------------------------------------------------

# Confirmed Signals

  ------------------------------------------------------------------------------------------------
  Signal         CAN ID         Bytes          Decoder                             Status
  -------------- -------------- -------------- ----------------------------------- ---------------
  Engine RPM     `0x024`        D3:D4          `((D3 << 8) \| D4) / 2`             **CONFIRMED**

  Gear           `0x024`        D5             `D5 / 32`                           **CONFIRMED**

  Wheel speed    `0x018`        D3:D4          `((D3 << 8) \| D4 - 0xA000) / 16`   **CONFIRMED**

  Engine         `0x180`        D5             `D5 - 40`                           **CONFIRMED**
  temperature                                                                      

  Ambient        `0x300`        D1             `D1 - 40`                           **CONFIRMED**
  temperature                                                                      

  Throttle       `0x080`        D3             Incremental encoder, see below      **CONFIRMED /
  position                                                                         calibrated**

  Front brake    `0x022`        D6             `(D6 - 3) / 248 * 100`              **CONFIRMED /
  lever                                                                            calibrated**

  Starter / kill `0x080`        D6             State values, see below             **CONFIRMED**
  switch                                                                           

  Side stand     `0x024`        D6             `0x00 = down`, `0x20 = up`          **CONFIRMED**
  ------------------------------------------------------------------------------------------------

> Note: Byte numbering in this document is zero-based: D1 = byte\[0\],
> D2 = byte\[1\], etc.

------------------------------------------------------------------------

# Engine RPM --- `0x024`

## Decoder

``` text
raw = (D3 << 8) | D4
RPM = raw / 2
```

This signal was validated using multiple captures:

-   cold-engine idle: approximately 1960--2100 RPM
-   hot-engine idle: approximately 1280--1450 RPM
-   engine startup
-   engine speed above 4000 RPM
-   engine shutdown

The Ducati dashboard can show values such as `1.960` or `2.100`. These
represent approximately 1960 and 2100 RPM; the dot is a thousands
separator/display formatting and is not part of the CAN encoding.

------------------------------------------------------------------------

# Gear --- `0x024`

## Decoder

Stable values:

``` text
0x00 = Neutral
0x20 = 1st
0x40 = 2nd
0x60 = 3rd
0x80 = 4th
0xA0 = 5th
0xC0 = 6th
```

Therefore:

``` text
gear = D5 / 32
```

The following sequence was observed and matched the physical/dashboard
gear changes:

``` text
N → 1st → N → 2nd → 3rd → 2nd → N → 1st → N
```

During an actual shift, transient intermediate values can appear. These
should not be decoded as stable gears.

------------------------------------------------------------------------

# Wheel Speed --- `0x018 D3:D4`

## Final decoder

The wheel-speed signal is a 16-bit big-endian value formed by D3:D4:

``` text
raw = (D3 << 8) | D4
```

The observed zero-speed baseline is:

``` text
0xA000
```

Current decoder:

``` text
wheel_speed_kmh = (raw - 0xA000) / 16.0
```

or:

``` text
wheel_speed_kmh = ((((D3 << 8) | D4) - 0xA000) / 16.0)
```

Resolution:

``` text
1 count = 0.0625 km/h
```

## Important: D4 alone is NOT the speed

Initial experiments focused on D4 alone because it changed while
manually spinning the rear wheel.

At low speed this produced an apparently useful relationship, but
higher-speed captures showed that D3 changes from values such as:

``` text
0xA0 → 0xA1 → 0xA2 ...
```

while D4 continues to oscillate.

Therefore D4 must not be decoded independently.

The complete D3:D4 16-bit value is required.

## Validation captures

The following controlled tests were used:

  -----------------------------------------------------------------------
  Test                            Approx. speed       Typical raw value /
                                                                   offset
  ------------------- ------------------------- -------------------------
  Rear wheel manually                  \~3 km/h           `0xA036` / \~54
  spun                                          

  Rear wheel manually                  \~4 km/h           `0xA048` / \~72
  spun                                          

  1st gear                \~12--14 km/h, mostly   around `0xA0DB` / \~219
                                           \~13 

  2nd gear                        \~16--22 km/h             approximately
                                                       `0xA0E4`--`0xA162`

  1st-gear speed               increasing speed       up to approximately
  sweep                                           `0xA2C6` / \~710 offset
  -----------------------------------------------------------------------

The low-speed tests initially suggested:

``` text
D4 / 18
```

but this was rejected after the 2nd-gear test because D4 alone no longer
represented the speed.

## Independent RPM + gearing validation

The strongest validation used the already-confirmed RPM and gear
signals.

Theoretical rear-wheel speed can be calculated from:

``` text
wheel_speed =
    RPM
    / (primary_ratio × gear_ratio × final_ratio)
    × rear_tyre_circumference
    × 60 / 1000
```

For the nominal 180/55 ZR17 rear tyre, circumference is approximately
1.98 m.

The 1st-gear speed-sweep capture showed approximately:

``` text
correlation R ≈ 0.994
```

between theoretical rear-wheel speed and:

``` text
0x018 D3:D4 - 0xA000
```

Candidate scaling was also compared:

``` text
offset / 16 → best fit
offset / 17 → significantly worse
offset / 18 → significantly worse
```

This strongly supports:

``` text
wheel_speed_kmh = (raw - 0xA000) / 16
```

The 1st-vs-2nd gear comparison is also consistent with wheel speed: at
the same engine RPM, the rear wheel rotates faster in 2nd gear and the
`0x018 D3:D4` value increases accordingly.

## Current status

**`0x018 D3:D4` is considered CONFIRMED as the wheel-speed signal for
the tested Monster 937.**

A future GPS comparison at normal road speed would still be useful as an
independent final calibration, but the signal is already sufficiently
validated for the planned telemetry logger.

------------------------------------------------------------------------

# Throttle Position --- `0x080 D3`

D3 is not a simple absolute 0--255 percentage.

It behaves as an incremental/wrapping encoder.

## Decoder

``` text
delta = raw - lastRaw

if delta > 127:
    delta -= 256

if delta < -127:
    delta += 256

accum += delta

pct = clamp((accum - 188) / 610 * 100, 0, 100)
```

Calibration obtained from repeated tests:

``` text
closed/rest ≈ 188 (0xBC)
full travel ≈ 610 counts
```

The reported value should always be clamped to 0--100%.

------------------------------------------------------------------------

# Front Brake Lever --- `0x022 D6`

Current calibrated decoder:

``` text
pct = clamp((D6 - 3) / 248 * 100, 0, 100)
```

Observed calibration points:

``` text
0x03 = 0% / lever released
0x83 ≈ 50%
0xFB = 100% / maximum squeeze
```

------------------------------------------------------------------------

# Starter / Kill Switch --- `0x080 D6`

Confirmed states:

``` text
0x00 = killed / kill switch off
0x20 = run / ready
0x60 = starting / cranking
```

This signal should be used to determine engine state rather than relying
only on RPM reaching zero.

------------------------------------------------------------------------

# Engine Temperature --- `0x180 D6`

Decoder:

``` text
temperature_C = D6 - 40
```

Validated during engine warm-up and temperature changes.

------------------------------------------------------------------------

# Ambient Temperature --- `0x300 D1`

Decoder:

``` text
temperature_C = D1 - 40
```

Validated against the dashboard/environmental temperature.

------------------------------------------------------------------------

# Side Stand --- `0x024 D6`

Current mapping:

``` text
0x00 = side stand down
0x20 = side stand up
```

Observed directly during live testing.

A dedicated repeat test would still be useful.

------------------------------------------------------------------------

# CAN Frame Structure

Observed frames are 8 bytes long.

A 2-byte trailer is present on observed messages:

-   second-to-last byte: rolling counter, typically `0x00`--`0x0F`
-   last byte: checksum/CRC

The exact CRC algorithm has not yet been fully documented.

This is important for future active CAN communication, but it is not
required for listen-only telemetry capture.

------------------------------------------------------------------------

# Ruled Out / Rejected Hypotheses

## `0x018 D4` as direct wheel speed

Rejected.

D4 alone appeared promising during low-speed manual wheel-spin tests but
failed at higher speeds because D3 participates in the value.

The correct signal is:

``` text
0x018 D3:D4
```

## `0x018 D4` frequency / crossing-rate decoder

An early hypothesis was:

``` text
count rising edges above threshold
frequency × 0.75 = km/h
```

This was useful for investigating the low-speed waveform but does not
describe the higher-speed captures and is no longer considered the
wheel-speed decoder.

## `0x230` as battery voltage

An apparent `28 / 2 = 14 V` match was observed, but the value remained
frozen and did not track battery voltage.

Rejected.

## `0x100` as RPM

Several candidate byte combinations produced plausible-looking values in
isolated frames but failed against real engine RPM.

Rejected.

## `0x080 D5` as throttle

Rejected after real throttle-blip tests.

The useful throttle signal is D3.

## `0x210` as RPM

The values behave like a repeating counter/phase signal and do not track
RPM.

Rejected.

## Older Ducati CAN formulas

Mappings from older Ducati models were tested where applicable but did
not transfer reliably to the 2021 Monster 937.

------------------------------------------------------------------------

# Still Unsolved

## Clutch switch

No reliable CAN signal identified.

The clutch switch may be directly wired into the starter/interlock
circuit rather than broadcast over CAN.

## Battery voltage

No reliable CAN voltage signal identified.

## Odometer / remaining range

No reliable mapping identified.

## Trip computer

Trip 1, Trip 2, average consumption, average speed and trip time remain
unresolved.

## Rear brake switch

Brake-light operation was confirmed physically, but no corresponding CAN
signal has been reliably identified.

------------------------------------------------------------------------

# Current Telemetry Decoder

The currently usable signals for the planned ESP32 → Bluetooth → Android
→ CSV → GoPro Telemetry Overlay pipeline are:

``` text
RPM:
    CAN ID 0x024
    raw = (D3 << 8) | D4
    rpm = raw / 2

Gear:
    CAN ID 0x024
    gear = D5 / 32

Wheel speed:
    CAN ID 0x018
    raw = (D3 << 8) | D4
    speed_kmh = (raw - 0xA000) / 16.0

Throttle:
    CAN ID 0x080
    incremental encoder on D3
    unwrap + accumulate
    pct = clamp((accum - 188) / 610 * 100, 0, 100)

Front brake:
    CAN ID 0x022
    pct = clamp((D6 - 3) / 248 * 100, 0, 100)

Engine temperature:
    CAN ID 0x180
    temp_C = D6 - 40

Ambient temperature:
    CAN ID 0x300
    temp_C = D1 - 40

Engine state:
    CAN ID 0x080
    D6:
        0x00 = killed
        0x20 = run
        0x60 = starting
```

------------------------------------------------------------------------

# Confidence Summary

``` text
RPM                  CONFIRMED
Gear                 CONFIRMED
Wheel speed           CONFIRMED
Throttle             CONFIRMED / calibrated
Front brake          CONFIRMED / calibrated
Engine temperature   CONFIRMED
Ambient temperature  CONFIRMED
Kill/start state     CONFIRMED
Side stand           CONFIRMED, repeat test recommended

Clutch switch        UNSOLVED
Rear brake switch    UNSOLVED
Battery voltage      UNSOLVED
Odometer             UNSOLVED
Trip computer        UNSOLVED
CRC algorithm        UNSOLVED
```

------------------------------------------------------------------------

# Telemetry Logger Notes

The planned architecture is:

``` text
Ducati CAN bus
      ↓
ESP32 + CAN transceiver
      ↓
decode confirmed CAN signals
      ↓
Bluetooth
      ↓
Android phone
      ↓
phone timestamp
      ↓
CSV export
      ↓
GoPro Telemetry Overlay
```

For the first implementation, a telemetry transmission rate of
approximately **2 samples/second** is a reasonable starting point.

The ESP32 should continue receiving and decoding CAN frames at the
native CAN message rate, while the Bluetooth telemetry packet can be
generated at a lower fixed rate.

The firmware should retain access to the raw CAN frames/debug data so
additional signals can be added later without changing the CAN capture
hardware.