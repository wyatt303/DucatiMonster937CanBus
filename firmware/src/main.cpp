#include <Arduino.h>
#include <ESP32-TWAI-CAN.hpp>

#include "config.h"
#include "telemetry.h"
#include "ble_service.h"

static bool canStarted = false;

static void startCan()
{
    twai_general_config_t generalConfig =
        TWAI_GENERAL_CONFIG_DEFAULT(
            (gpio_num_t)CAN_TX,
            (gpio_num_t)CAN_RX,
            TWAI_MODE_LISTEN_ONLY
        );

    if (!ESP32Can.begin(
            ESP32Can.convertSpeed(DUCATI_CAN_SPEED_KBPS),
            CAN_TX,
            CAN_RX,
            20,
            20,
            nullptr,
            &generalConfig)) {
        Serial.println("CAN: failed to start");
        return;
    }

    canStarted = true;

    Serial.println("CAN: 500 kbps");
    Serial.println("CAN: listen-only");
    Serial.println("CAN: GPIO1 TX / GPIO2 RX");
}

void setup()
{
    Serial.begin(115200);
    delay(1000);

    Serial.println();
    Serial.println("========================================");
    Serial.println("Ducati Monster 937 BLE Telemetry Logger");
    Serial.printf("Firmware: %s\n", DUCATI_FW_VERSION);
    Serial.printf("Protocol: %u\n", DUCATI_PROTOCOL_VERSION);
    Serial.println("========================================");

    telemetryInit();
    startCan();
    bleInit();

    if (!canStarted) {
        Serial.println("WARNING: CAN is not running");
    }

    Serial.println("Ready.");
}

void loop()
{
    // Drain all currently queued CAN frames.
    if (canStarted) {
        CanFrame frame;

        while (ESP32Can.readFrame(frame, 0)) {
            telemetryProcessCanFrame(frame);
        }
    }

    // Generate a fixed-rate binary telemetry packet.
    static uint32_t lastTelemetryMs = 0;
    const uint32_t now = millis();

    if (now - lastTelemetryMs >= TELEMETRY_INTERVAL_MS) {
        lastTelemetryMs = now;

        Telemetry packet{};

        if (telemetryBuildPacket(packet)) {
            // Packet is exactly 19 bytes and is already packed as little-endian
            // primitive fields on ESP32.
            bleSendTelemetry(
                reinterpret_cast<const uint8_t *>(&packet),
                sizeof(packet)
            );

            // Keep serial output useful for bench testing.
            if (bleIsConnected()) {
                Serial.printf(
                    "TEL seq=%lu rpm=%u gear=%d speed=%.2f throttle=%.2f brake=%.2f eng=%d air=%d\n",
                    (unsigned long)packet.sequence,
                    packet.rpm,
                    packet.gear,
                    packet.speedX100 / 100.0f,
                    packet.throttleX100 / 100.0f,
                    packet.frontBrakeX100 / 100.0f,
                    packet.engineTempC,
                    packet.ambientTempC
                );
            }
        }
    }

    delay(1);
}
