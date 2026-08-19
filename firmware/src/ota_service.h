#pragma once
#include <Arduino.h>
#include <BLEServer.h>

void otaServiceInit(BLEServer *server);
void otaServiceOnDisconnect();
bool bleIsOtaActive();
