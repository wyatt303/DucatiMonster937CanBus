#include "ble_service.h"
#include "config.h"
#include "ota_service.h"

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

static BLEServer *server = nullptr;
static BLECharacteristic *telemetryCharacteristic = nullptr;
static BLECharacteristic *deviceInfoCharacteristic = nullptr;
static bool connected = false;

class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer *s) override
    {
        connected = true;
        Serial.println("BLE: Android connected");
    }

    void onDisconnect(BLEServer *s) override
    {
        connected = false;
        otaServiceOnDisconnect();
        Serial.println("BLE: Android disconnected");
        delay(100);
        BLEDevice::startAdvertising();
    }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *c) override
    {
        std::string value = c->getValue();
        if (value.empty()) {
            return;
        }

        // Reserved for future Android -> ESP32 commands.
        Serial.printf("BLE command received: %u bytes\n", (unsigned)value.size());
    }
};

void bleInit()
{
    BLEDevice::init(BLE_DEVICE_NAME);

    server = BLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());

    BLEService *service = server->createService(DUCATI_SERVICE_UUID);

    telemetryCharacteristic = service->createCharacteristic(
        TELEMETRY_UUID,
        BLECharacteristic::PROPERTY_NOTIFY
    );
    telemetryCharacteristic->addDescriptor(new BLE2902());

    deviceInfoCharacteristic = service->createCharacteristic(
        DEVICE_INFO_UUID,
        BLECharacteristic::PROPERTY_READ
    );

    String info =
        String("fw=") +
        DUCATI_FW_VERSION +
        ";protocol=" +
        String(DUCATI_PROTOCOL_VERSION) +
        ";build=" +
        DUCATI_GIT_SHA;

    deviceInfoCharacteristic->setValue(info.c_str());

    BLECharacteristic *commandCharacteristic = service->createCharacteristic(
        COMMAND_UUID,
        BLECharacteristic::PROPERTY_WRITE |
        BLECharacteristic::PROPERTY_WRITE_NR
    );
    commandCharacteristic->setCallbacks(new CommandCallbacks());

    service->start();

    otaServiceInit(server);

    BLEAdvertising *advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(DUCATI_SERVICE_UUID);
    advertising->addServiceUUID(OTA_SERVICE_UUID);
    advertising->setScanResponse(true);
    advertising->setMinPreferred(0x06);
    advertising->setMaxPreferred(0x12);

    BLEDevice::startAdvertising();

    Serial.println("BLE advertising as Ducati-Monster-937");
}

bool bleIsConnected()
{
    return connected;
}

void bleSendTelemetry(const uint8_t *data, size_t length)
{
    if (!connected || bleIsOtaActive() || !telemetryCharacteristic) {
        return;
    }

    telemetryCharacteristic->setValue(
    const_cast<uint8_t *>(data),
    length
    );
    telemetryCharacteristic->notify();
}
