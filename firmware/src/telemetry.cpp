#include "telemetry.h"
#include "config.h"
#include <ESP32-TWAI-CAN.hpp>

static Telemetry t{};
static uint32_t sequence = 0;

// Throttle incremental encoder state.
static bool throttleInitialized = false;
static int32_t throttleAccum = 0;
static uint8_t lastThrottleRaw = 0;

// Speed signal is an absolute calibrated D3:D4 value.
static float decodeSpeedKmh(const CanFrame &f)
{
    uint16_t raw = ((uint16_t)f.data[2] << 8) | f.data[3];

    if (raw <= 0xA000) {
        return 0.0f;
    }

    return ((float)raw - 0xA000) / 16.0f;
}

static float decodeThrottlePct(const CanFrame &f)
{
    const uint8_t raw = f.data[2];

    if (!throttleInitialized) {
        throttleAccum = raw;
        lastThrottleRaw = raw;
        throttleInitialized = true;
    } else {
        int delta = (int)raw - (int)lastThrottleRaw;

        if (delta > 128) {
            delta -= 256;
        } else if (delta < -128) {
            delta += 256;
        }

        throttleAccum += delta;
        lastThrottleRaw = raw;
    }

    float pct =
        ((float)throttleAccum - THROTTLE_CLOSED_RAW) /
        THROTTLE_FULL_SCALE * 100.0f;

    return constrain(pct, 0.0f, 100.0f);
}

static float decodeBrakePct(const CanFrame &f)
{
    float pct = ((float)f.data[5] - 3.0f) / 248.0f * 100.0f;
    return constrain(pct, 0.0f, 100.0f);
}

void telemetryInit()
{
    memset(&t, 0, sizeof(t));
    t.gear = 0;
}

void telemetryProcessCanFrame(const CanFrame &f)
{
    switch (f.identifier) {
        case ID_RPM: {
            // CANBUS_FINDINGS.md: 0x024 D3:D4, raw / 2.
            uint16_t raw = ((uint16_t)f.data[2] << 8) | f.data[3];
            t.rpm = raw / 2;
            t.gear = f.data[4] / 32;
            break;
        }

        case ID_SPEED: {
            float kmh = decodeSpeedKmh(f);
            t.speedX100 = (uint16_t)constrain(lroundf(kmh * 100.0f), 0L, 65535L);
            break;
        }

        case ID_THROTTLE: {
            float pct = decodeThrottlePct(f);
            t.throttleX100 = (uint16_t)constrain(lroundf(pct * 100.0f), 0L, 10000L);
            break;
        }

        case ID_BRAKE: {
            float pct = decodeBrakePct(f);
            t.frontBrakeX100 = (uint16_t)constrain(lroundf(pct * 100.0f), 0L, 10000L);
            break;
        }

        case ID_ENGTEMP:
            t.engineTempC = (int8_t)((int)f.data[5] - 40);
            break;

        case ID_AIRTEMP:
            t.ambientTempC = (int8_t)((int)f.data[0] - 40);
            break;

        default:
            break;
    }
}

bool telemetryBuildPacket(Telemetry &out)
{
    t.sequence = sequence++;
    t.espTimeMs = millis();
    out = t;
    return true;
}
