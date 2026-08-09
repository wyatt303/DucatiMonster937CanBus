#include <Arduino.h>
#include <ESP32-TWAI-CAN.hpp>

// SN65HVD230 wiring:
//   VCC  -> 3V3 (NEVER the bike's 12V)
//   GND  -> GND, tied to the bike's chassis/battery negative (common ground!)
//   CTX  -> D0 (GPIO1)
//   CRX  -> D1 (GPIO2)
//   CANH/CANL -> bike's CAN bus tap point. No termination resistor - the
//                bike's bus is already terminated at its two ends.
#define CAN_TX D0
#define CAN_RX D1

// Common automotive CAN speeds, tried in order until one shows real traffic.
const uint16_t kCandidateSpeeds[] = {500, 250, 125, 1000};
const size_t kNumSpeeds = sizeof(kCandidateSpeeds) / sizeof(kCandidateSpeeds[0]);

bool locked = false;
uint16_t lockedSpeed = 0;

// Confirmed signals:
#define ID_ENGTEMP 0x180  // byte5 - 40 = engine temp (C)
#define ID_AIRTEMP 0x300  // byte0 - 40 = ambient/air temp (C)
#define ID_GEAR    0x024  // byte4 / 32 = gear (0 = Neutral)
#define ID_RPM     0x100  // (byte2<<8 | byte3) * 4.0 = RPM
                          // Refined across 4 live dash comparisons (idle and a
                          // 28s stable 3100-3400rpm plateau): 3.7->3.81->3.93->4.02,
                          // rounded to a clean 4.0 for the live display.
const float kRpmScale = 4.0f;
#define ID_SPEED   0x018  // byte3: rotation counter. Rising-edge crossing rate
                          // over a 1s window * 0.75 = speed in km/h.
                          // Calibrated with a guided 4-step test (0/2/3/4 km/h
                          // against the dash) - 0.75 fit 3 of 4 points exactly;
                          // the 4km/h step was likely a hand-spin limit, not a
                          // formula error. Confirmed accurate for 0-3 km/h range.
const int kSpeedThreshold = 20;
const float kSpeedScale = 0.75f;

#define ID_THROTTLE 0x080  // byte2: throttle position, but NOT a simple absolute
                            // value - it's an incremental encoder that wraps
                            // around the full 0-255 range multiple times across
                            // the physical throttle travel, so it must be
                            // unwrapped/accumulated frame-to-frame, not read
                            // directly. Closed/rest raw value is highly
                            // repeatable at 188 (confirmed identical across two
                            // independent guided tests, both starting AND ending
                            // exactly at 188). Full twist to the hard mechanical
                            // stop measured 602 and 620 unwrapped counts of total
                            // travel in those two tests - averaged to 610 here.
                            // Assumes the bike is stationary with the throttle at
                            // rest when this board boots, since that's used as
                            // the zero reference for the running accumulator.
const uint8_t kThrottleClosedRaw = 188;
const float kThrottleFullScale = 610.0f;

// Track last printed value per signal so we only print on change.
bool haveEngTemp = false, haveAirTemp = false, haveGear = false, haveRpm = false, haveSpeed = false, haveThrottle = false;
int lastEngTemp = 0, lastAirTemp = 0, lastGear = -1;
int lastRpm = -1;
int lastSpeed = -1;
int lastThrottlePct = -1;

// Throttle: running unwrap accumulator, since the raw byte wraps repeatedly
// across the physical travel range (see ID_THROTTLE comment above).
bool throttleAccumInit = false;
long throttleAccum = 0;
uint8_t lastThrottleRaw = 0;

// Wheel speed: count rising-edge threshold crossings of byte3 in a 1s window.
bool speedAboveThreshold = false;
int speedCrossingsThisWindow = 0;
uint32_t speedWindowStart = 0;

bool tryListenAtSpeed(uint16_t speedKbps, uint32_t windowMs = 800) {
  // LISTEN_ONLY: the controller can never drive the bus - no transmit, no ACK.
  twai_general_config_t gConfig = TWAI_GENERAL_CONFIG_DEFAULT(
      (gpio_num_t)CAN_TX, (gpio_num_t)CAN_RX, TWAI_MODE_LISTEN_ONLY);

  if (!ESP32Can.begin(ESP32Can.convertSpeed(speedKbps), CAN_TX, CAN_RX,
                       20, 20, nullptr, &gConfig)) {
    Serial.printf("  [%4u kbps] driver failed to start\n", speedKbps);
    return false;
  }

  uint32_t start = millis();
  uint32_t frames = 0;
  CanFrame f;
  while (millis() - start < windowMs) {
    if (ESP32Can.readFrame(f, 20)) frames++;
  }

  twai_status_info_t status;
  twai_get_status_info(&status);
  Serial.printf("  [%4u kbps] frames: %u  bus_errors: %u  state: %d\n",
                speedKbps, frames, status.bus_error_count, status.state);

  return frames > 0;
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("Confirmed-signals dashboard (listen-only, prints only on change)");
  Serial.println("Make sure the bike's ignition is ON so modules are actually talking.");
  speedWindowStart = millis();
}

void loop() {
  if (!locked) {
    Serial.println("Scanning for bus speed...");
    for (size_t i = 0; i < kNumSpeeds; i++) {
      if (tryListenAtSpeed(kCandidateSpeeds[i])) {
        lockedSpeed = kCandidateSpeeds[i];
        locked = true;
        Serial.printf(">>> Locked at %u kbps, capturing...\n\n", lockedSpeed);
        speedWindowStart = millis();
        break;
      }
    }
    if (!locked) {
      Serial.println("No traffic detected at any speed. Check ignition/wiring/ground, retrying in 3s...\n");
      delay(3000);
    }
    return;
  }

  // Close out the speed window every 1s regardless of whether a frame just arrived.
  uint32_t now = millis();
  if (now - speedWindowStart >= 1000) {
    float freqHz = speedCrossingsThisWindow * 1000.0f / (now - speedWindowStart);
    int speedKmh = (int)(freqHz * kSpeedScale + 0.5f);  // round to nearest whole km/h
    if (!haveSpeed || speedKmh != lastSpeed) {
      Serial.printf("[%8lu ms] Speed:        %d km/h\n", now, speedKmh);
      lastSpeed = speedKmh;
      haveSpeed = true;
    }
    speedCrossingsThisWindow = 0;
    speedWindowStart = now;
  }

  CanFrame f;
  if (ESP32Can.readFrame(f, 100)) {
    if (f.identifier == ID_ENGTEMP) {
      int engTemp = (int)f.data[5] - 40;
      if (!haveEngTemp || engTemp != lastEngTemp) {
        Serial.printf("[%8lu ms] Engine Temp:  %d C\n", millis(), engTemp);
        lastEngTemp = engTemp;
        haveEngTemp = true;
      }
    } else if (f.identifier == ID_AIRTEMP) {
      int airTemp = (int)f.data[0] - 40;
      if (!haveAirTemp || airTemp != lastAirTemp) {
        Serial.printf("[%8lu ms] Air Temp:     %d C\n", millis(), airTemp);
        lastAirTemp = airTemp;
        haveAirTemp = true;
      }
    } else if (f.identifier == ID_GEAR) {
      int gear = f.data[4] / 32;  // 0 = Neutral
      if (!haveGear || gear != lastGear) {
        if (gear == 0) {
          Serial.printf("[%8lu ms] Gear:         N\n", millis());
        } else {
          Serial.printf("[%8lu ms] Gear:         %d\n", millis(), gear);
        }
        lastGear = gear;
        haveGear = true;
      }
    } else if (f.identifier == ID_SPEED) {
      // Wheel rotation byte - track rising-edge threshold crossings.
      bool above = f.data[3] > kSpeedThreshold;
      if (above && !speedAboveThreshold) {
        speedCrossingsThisWindow++;
      }
      speedAboveThreshold = above;
    } else if (f.identifier == ID_RPM) {
      uint16_t combined = ((uint16_t)f.data[2] << 8) | f.data[3];
      int rpm = (int)(combined * kRpmScale);
      // Only print on a meaningfully large change to avoid flooding from
      // count-level jitter.
      if (!haveRpm || abs(rpm - lastRpm) >= 50) {
        Serial.printf("[%8lu ms] RPM:          %d\n", millis(), rpm);
        lastRpm = rpm;
        haveRpm = true;
      }
    } else if (f.identifier == ID_THROTTLE) {
      uint8_t raw = f.data[2];
      if (!throttleAccumInit) {
        throttleAccum = raw;
        lastThrottleRaw = raw;
        throttleAccumInit = true;
      } else {
        int delta = (int)raw - (int)lastThrottleRaw;
        if (delta > 128) delta -= 256;
        else if (delta < -128) delta += 256;
        throttleAccum += delta;
        lastThrottleRaw = raw;
      }
      float pct = (throttleAccum - kThrottleClosedRaw) / kThrottleFullScale * 100.0f;
      if (pct < 0) pct = 0;
      if (pct > 100) pct = 100;
      int throttlePct = (int)(pct + 0.5f);
      if (!haveThrottle || throttlePct != lastThrottlePct) {
        Serial.printf("[%8lu ms] Throttle:     %d%%\n", millis(), throttlePct);
        lastThrottlePct = throttlePct;
        haveThrottle = true;
      }
    }
  }
}
