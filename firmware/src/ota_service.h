#pragma once
#include <Arduino.h>
#include <BLEServer.h>

void otaServiceInit(BLEServer *server);
bool bleIsOtaActive();
