#include "ota_service.h"
#include "config.h"

#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#include "esp_ota_ops.h"
#include "esp_partition.h"
#include "esp_system.h"
#include "esp_app_desc.h"
#include "esp_rom_crc.h"

static BLECharacteristic *otaControl = nullptr;
static BLECharacteristic *otaData = nullptr;
static BLECharacteristic *otaStatus = nullptr;

static const esp_partition_t *updatePartition = nullptr;
static esp_ota_handle_t otaHandle = 0;
static bool otaActive = false;
static size_t otaExpectedSize = 0;
static size_t otaReceived = 0;
static uint32_t otaExpectedCrc = 0;
static uint32_t otaRunningCrc = 0;

enum : uint8_t {
    OTA_START  = 0x01,
    OTA_END    = 0x02,
    OTA_ABORT  = 0x03
};

enum : uint8_t {
    OTA_READY    = 0x01,
    OTA_PROGRESS = 0x02,
    OTA_SUCCESS  = 0x03,
    OTA_ERROR    = 0x04
};

static void sendStatus(uint8_t type, uint32_t value = 0)
{
    if (!otaStatus) {
        return;
    }

    uint8_t packet[6];
    packet[0] = type;
    packet[1] = (uint8_t)(value & 0xff);
    packet[2] = (uint8_t)((value >> 8) & 0xff);
    packet[3] = (uint8_t)((value >> 16) & 0xff);
    packet[4] = (uint8_t)((value >> 24) & 0xff);
    packet[5] = 0;

    otaStatus->setValue(packet, sizeof(packet));
    otaStatus->notify();
}

static void abortOta(uint32_t errorCode)
{
    if (otaActive) {
        esp_ota_abort(otaHandle);
    }

    otaActive = false;
    otaHandle = 0;
    otaExpectedSize = 0;
    otaReceived = 0;
    otaExpectedCrc = 0;
    otaRunningCrc = 0;

    sendStatus(OTA_ERROR, errorCode);
}

class OtaControlCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *c) override
    {
        std::string v = c->getValue();
        if (v.empty()) {
            return;
        }

        const uint8_t *p = reinterpret_cast<const uint8_t *>(v.data());

        switch (p[0]) {
            case OTA_START: {
                if (v.size() < 9) {
                    sendStatus(OTA_ERROR, 1);
                    return;
                }

                if (otaActive) {
                    sendStatus(OTA_ERROR, 2);
                    return;
                }

                uint32_t size =
                    (uint32_t)p[1] |
                    ((uint32_t)p[2] << 8) |
                    ((uint32_t)p[3] << 16) |
                    ((uint32_t)p[4] << 24);

                uint32_t crc =
                    (uint32_t)p[5] |
                    ((uint32_t)p[6] << 8) |
                    ((uint32_t)p[7] << 16) |
                    ((uint32_t)p[8] << 24);

                if (size == 0) {
                    sendStatus(OTA_ERROR, 3);
                    return;
                }

                updatePartition = esp_ota_get_next_update_partition(nullptr);
                if (!updatePartition) {
                    sendStatus(OTA_ERROR, 4);
                    return;
                }

                if (size > updatePartition->size) {
                    sendStatus(OTA_ERROR, 5);
                    return;
                }

                esp_err_t err = esp_ota_begin(
                    updatePartition,
                    size,
                    &otaHandle
                );

                if (err != ESP_OK) {
                    sendStatus(OTA_ERROR, 6);
                    return;
                }

                otaActive = true;
                otaExpectedSize = size;
                otaReceived = 0;
                otaExpectedCrc = crc;
                otaRunningCrc = 0;

                Serial.printf(
                    "OTA started: %u bytes, expected CRC32 0x%08lX\n",
                    (unsigned)size,
                    (unsigned long)crc
                );

                sendStatus(OTA_READY, 0);
                break;
            }

            case OTA_END: {
                if (!otaActive) {
                    sendStatus(OTA_ERROR, 7);
                    return;
                }

                if (otaReceived != otaExpectedSize) {
                    abortOta(8);
                    return;
                }

                if (otaExpectedCrc != 0 && otaRunningCrc != otaExpectedCrc) {
                    abortOta(9);
                    return;
                }

                esp_err_t err = esp_ota_end(otaHandle);
                otaHandle = 0;

                if (err != ESP_OK) {
                    otaActive = false;
                    sendStatus(OTA_ERROR, 10);
                    return;
                }

                err = esp_ota_set_boot_partition(updatePartition);
                if (err != ESP_OK) {
                    otaActive = false;
                    sendStatus(OTA_ERROR, 11);
                    return;
                }

                otaActive = false;
                sendStatus(OTA_SUCCESS, otaExpectedSize);

                Serial.println("OTA successful. Rebooting...");
                delay(500);
                ESP.restart();
                break;
            }

            case OTA_ABORT:
                abortOta(12);
                break;

            default:
                sendStatus(OTA_ERROR, 13);
                break;
        }
    }
};

class OtaDataCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *c) override
    {
        if (!otaActive) {
            return;
        }

        std::string v = c->getValue();

        // Data packet:
        // [0..3] little-endian absolute firmware offset
        // [4..]  firmware bytes
        if (v.size() < 5) {
            abortOta(20);
            return;
        }

        const uint8_t *p =
            reinterpret_cast<const uint8_t *>(v.data());

        uint32_t offset =
            (uint32_t)p[0] |
            ((uint32_t)p[1] << 8) |
            ((uint32_t)p[2] << 16) |
            ((uint32_t)p[3] << 24);

        const size_t payloadSize = v.size() - 4;

        if (offset != otaReceived) {
            abortOta(21);
            return;
        }

        if (otaReceived + payloadSize > otaExpectedSize) {
            abortOta(22);
            return;
        }

        esp_err_t err = esp_ota_write(
            otaHandle,
            p + 4,
            payloadSize
        );

        if (err != ESP_OK) {
            abortOta(23);
            return;
        }

        otaReceived += payloadSize;

        // ESP-ROM CRC32 helper is incremental when called with previous CRC.
        otaRunningCrc = esp_rom_crc32_le(
            otaRunningCrc,
            p + 4,
            payloadSize
        );

        // Notify progress approximately every 16 KB and at completion.
        static size_t lastReport = 0;
        if (otaReceived == otaExpectedSize ||
            otaReceived - lastReport >= 16384) {
            lastReport = otaReceived;
            sendStatus(OTA_PROGRESS, (uint32_t)otaReceived);
        }
    }
};

void otaServiceInit(BLEServer *server)
{
    BLEService *service = server->createService(OTA_SERVICE_UUID);

    otaControl = service->createCharacteristic(
        OTA_CONTROL_UUID,
        BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_WRITE_NR
    );
    otaControl->setCallbacks(new OtaControlCallbacks());

    otaData = service->createCharacteristic(
        OTA_DATA_UUID,
        BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_WRITE_NR
    );
    otaData->setCallbacks(new OtaDataCallbacks());

    otaStatus = service->createCharacteristic(
        OTA_STATUS_UUID,
        BLECharacteristic::PROPERTY_NOTIFY
    );
    otaStatus->addDescriptor(new BLE2902());

    service->start();
}

bool bleIsOtaActive()
{
    return otaActive;
}
