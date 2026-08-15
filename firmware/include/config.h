#pragma once

// Firmware / protocol version
#define DUCATI_FW_VERSION "0.1.0"
#define DUCATI_PROTOCOL_VERSION 1

// XIAO ESP32-S3 -> SN65HVD230
#define CAN_TX D0   // GPIO1
#define CAN_RX D1   // GPIO2

// Ducati Monster 937 CAN bus
#define DUCATI_CAN_SPEED_KBPS 500

// Telemetry is generated at a fixed rate. CAN is decoded continuously.
#define TELEMETRY_INTERVAL_MS 100

// BLE device name
#define BLE_DEVICE_NAME "Ducati-Monster-937"

// -------------------------
// BLE UUIDs
// -------------------------
#define DUCATI_SERVICE_UUID   "7f6d0001-8b7a-4f7b-9d8a-937000000001"
#define TELEMETRY_UUID        "7f6d0002-8b7a-4f7b-9d8a-937000000001"
#define DEVICE_INFO_UUID      "7f6d0003-8b7a-4f7b-9d8a-937000000001"
#define COMMAND_UUID          "7f6d0004-8b7a-4f7b-9d8a-937000000001"
#define OTA_SERVICE_UUID      "7f6d0010-8b7a-4f7b-9d8a-937000000001"
#define OTA_CONTROL_UUID      "7f6d0011-8b7a-4f7b-9d8a-937000000001"
#define OTA_DATA_UUID         "7f6d0012-8b7a-4f7b-9d8a-937000000001"
#define OTA_STATUS_UUID       "7f6d0013-8b7a-4f7b-9d8a-937000000001"

// -------------------------
// Confirmed CAN IDs
// -------------------------
#define ID_RPM       0x024
#define ID_GEAR      0x024
#define ID_SPEED     0x018
#define ID_THROTTLE  0x080
#define ID_BRAKE     0x022
#define ID_ENGTEMP   0x180
#define ID_AIRTEMP   0x300

// Confirmed decoder calibration
constexpr uint8_t THROTTLE_CLOSED_RAW = 188;
constexpr float THROTTLE_FULL_SCALE = 610.0f;
