#pragma once
#include <Arduino.h>
#include <ESP32-TWAI-CAN.hpp>

struct Telemetry {
    uint32_t sequence;
    uint32_t espTimeMs;
    uint16_t rpm;
    int8_t gear;
    uint16_t speedX100;
    uint16_t throttleX100;
    uint16_t frontBrakeX100;
    int8_t engineTempC;
    int8_t ambientTempC;
};

// Exactly 19 bytes, little-endian.
static_assert(sizeof(Telemetry) == 19, "Telemetry packet must be exactly 19 bytes");

void telemetryInit();
void telemetryProcessCanFrame(const CanFrame &frame);
bool telemetryBuildPacket(Telemetry &out);
