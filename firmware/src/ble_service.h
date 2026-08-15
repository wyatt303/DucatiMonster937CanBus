#pragma once
#include <Arduino.h>

void bleInit();
bool bleIsConnected();
void bleSendTelemetry(const uint8_t *data, size_t length);
bool bleIsOtaActive();
