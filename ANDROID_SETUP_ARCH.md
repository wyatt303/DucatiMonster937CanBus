# Android Development Setup on Arch Linux

This project uses a native Kotlin Android application for the Ducati Monster 937 telemetry system.

The Android application communicates with the ESP32-S3 over Bluetooth Low Energy (BLE).

## Android version requirements

The application supports:

- **Minimum Android version:** Android 12 / API 31
- **Target Android version:** API 35
- **Compile SDK:** API 35
- **Java:** JDK 17
- **Language:** Kotlin
- **Communication:** Bluetooth Low Energy (BLE)

---

## 1. Install Java 17

Install the Java 17 JDK:

```bash
sudo pacman -S jdk17-openjdk
```

Check the installed version:

```bash
java -version
```

It should report Java 17.

If you have multiple Java versions installed, check them with:

```bash
archlinux-java status
```

If necessary, select Java 17:

```bash
sudo archlinux-java set java-17-openjdk
```

Verify again:

```bash
java -version
```

---

## 2. Install Android SDK tools

On Arch Linux, the Android SDK tools used by this project are installed from the AUR.

If you have `yay` installed:

```bash
yay -S android-sdk-cmdline-tools-latest \
       android-sdk-platform-tools \
       android-sdk-build-tools
```

These packages provide:

- `android-sdk-cmdline-tools-latest` — Android SDK command-line tools
- `android-sdk-platform-tools` — `adb`, `fastboot`, etc.
- `android-sdk-build-tools` — Android build tools such as `aapt`, `apksigner`, and `d8`

The SDK is installed under:

```text
/opt/android-sdk
```

Check it:

```bash
ls -la /opt/android-sdk
```

---

## 3. Configure Android SDK environment variables

Add the following to:

```text
~/.bashrc
```

```bash
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk

export PATH="$PATH:$ANDROID_HOME/platform-tools"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin"
```

Reload the shell configuration:

```bash
source ~/.bashrc
```

Check that the tools are available:

```bash
sdkmanager --version
adb version
```

You may see a warning that `sdkmanager` is deprecated in favor of the newer Android CLI. This does not prevent the current setup from working.

---

## 4. Give your user ownership of the Android SDK

Because the SDK is installed under `/opt`, it may initially be owned by `root`.

Give your normal development user ownership:

```bash
sudo chown -R dawid:dawid /opt/android-sdk
```

Replace `dawid:dawid` with your own Linux username and group if necessary.

Check the ownership:

```bash
ls -ld /opt/android-sdk
```

It should show your user as the owner.

For example:

```text
drwxr-xr-x ... dawid dawid ... /opt/android-sdk
```

### Important

After changing ownership, run `sdkmanager` **without `sudo`**.

Use:

```bash
sdkmanager ...
```

Do not use:

```bash
sudo sdkmanager ...
```

This prevents a mixture of root-owned and user-owned SDK files.

---

## 5. Install Android SDK Platform 35

The project uses Android API 35 for compiling and targeting the application.

Install the required SDK components:

```bash
sdkmanager "platforms;android-35" \
           "build-tools;35.0.0" \
           "platform-tools"
```

Accept the Android SDK licenses:

```bash
yes | sdkmanager --licenses
```

Verify the installed packages:

```bash
sdkmanager --list_installed
```

You should see at least:

```text
build-tools;35.0.0
platform-tools
platforms;android-35
```

---

## 6. Android project configuration

The Android application is located in:

```text
android/
```

The project uses:

```text
Minimum Android: Android 12 / API 31
Compile SDK:     API 35
Target SDK:      API 35
Java:            17
Kotlin
```

The Android configuration should contain:

```kotlin
android {
    namespace = "pl.linuch.ducatitelemetry"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.linuch.ducatitelemetry"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
```

### Android compatibility

```text
Android 12                         Android 15
API 31                              API 35
  │                                    │
  └──────── supported versions ────────┘
                 │
                 ▼
           Ducati Telemetry
```

Android 12 / API 31 is the minimum supported version.

---

## 7. Gradle

The project uses the **Gradle Wrapper**.

A system-wide Gradle installation is therefore not required.

The Android project should contain:

```text
android/
├── gradlew
├── gradlew.bat
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── app/
├── build.gradle.kts
└── settings.gradle.kts
```

The Gradle Wrapper automatically downloads and uses the Gradle version specified by the project.

---

## 8. Build the Android application

Enter the Android project:

```bash
cd android
```

Make sure the Gradle wrapper is executable:

```bash
chmod +x gradlew
```

Build the debug APK:

```bash
./gradlew assembleDebug
```

The resulting APK will be located at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Check it:

```bash
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

---

## 9. Connect an Android phone

On the Android phone enable Developer Options.

Go to:

```text
Settings
→ About phone
→ Build number
```

Tap **Build number** seven times.

Then open:

```text
Settings
→ Developer options
```

Enable:

```text
USB debugging
```

Connect the phone to the Arch Linux computer using USB.

Check the connection:

```bash
adb devices
```

You should see something similar to:

```text
List of devices attached
XXXXXXXXXXXX    device
```

The first time you connect, Android may display:

```text
Allow USB debugging?
```

Accept the authorization.

---

## 10. Install the debug APK

From the `android/` directory:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The `-r` option updates an existing installation without removing its application data.

After installation, the application should appear on the phone as:

```text
Ducati Telemetry
```

---

## 11. Launch and debug the application

Launch the application normally from the Android launcher.

To view Android logs:

```bash
adb logcat
```

Filter Ducati-related messages:

```bash
adb logcat | grep -i ducati
```

For Bluetooth/GATT debugging:

```bash
adb logcat | grep -Ei "bluetooth|gatt|ducati"
```

---

## 12. Bluetooth permissions

The application targets Android 35 and has a minimum Android version of Android 12 / API 31.

Android 12 introduced the modern Bluetooth permissions:

```text
BLUETOOTH_SCAN
BLUETOOTH_CONNECT
BLUETOOTH_ADVERTISE
```

For the Ducati telemetry application we primarily need:

```text
BLUETOOTH_SCAN
BLUETOOTH_CONNECT
```

The application uses **Bluetooth Low Energy (BLE)**.

Bluetooth Classic is not required.

The ESP32-S3 advertises itself as:

```text
Ducati-Monster-937
```

The Android application scans for this device and connects to its custom BLE services.

---

## 13. Ducati BLE protocol

The ESP32 and Android application use a custom BLE protocol.

The protocol specification is located at:

```text
protocol/BLE_PROTOCOL.md
```

The current protocol version is:

```text
Protocol version: 1
```

The telemetry packet is binary and exactly **19 bytes**.

It contains:

```text
sequence
ESP32 timestamp
RPM
gear
speed
throttle
front brake
engine temperature
ambient temperature
```

The ESP32 sends the monotonic timestamp from `millis()`.

Android adds the real phone wall-clock timestamp when the packet is received.

This allows the Android application to generate the final telemetry CSV.

---

## 14. Android application architecture

The Android application is responsible for:

```text
ESP32-S3
    │
    │ BLE
    ▼
Android BLE layer
    │
    ├── decode binary telemetry
    ├── add phone timestamp
    ├── live dashboard
    ├── recording
    ├── CSV export
    └── firmware OTA
```

The ESP32 is responsible for:

```text
Ducati CAN
    │
    ▼
CAN decoder
    │
    ▼
binary telemetry
    │
    ▼
BLE
```

---

## 15. Recommended development environment

Android Studio is **not required**.

The complete project can be developed using:

```text
Arch Linux
│
├── VS Code
├── JDK 17
├── Android SDK
│   ├── Platform 35
│   ├── Build Tools 35
│   └── Platform Tools
│       └── adb
├── Gradle Wrapper
└── PlatformIO
    └── ESP32-S3 firmware
```

VS Code can be used for both parts:

```text
firmware/
    PlatformIO + C++

android/
    Kotlin + Gradle
```

---

## 16. Repository structure

The project contains two independent applications and a shared protocol definition:

```text
DucatiMonster937CanBus/
│
├── firmware/
│   ├── include/
│   ├── src/
│   ├── platformio.ini
│   └── partitions.csv
│
├── android/
│   ├── app/
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── protocol/
│   └── BLE_PROTOCOL.md
│
├── CANBUS_FINDINGS.md
└── README.md
```

The firmware and Android application are kept in the same repository because they share the BLE telemetry protocol.

---

## 17. ESP32 firmware development

Build the ESP32 firmware:

```bash
cd firmware
pio run
```

Upload it:

```bash
pio run --target upload
```

Open the serial monitor:

```bash
pio device monitor
```

The ESP32 firmware currently uses:

```text
CAN speed:       500 kbit/s
CAN mode:        Listen-only
CAN TX:          GPIO1 / XIAO D0
CAN RX:          GPIO2 / XIAO D1
BLE:             Bluetooth Low Energy
Telemetry:       Binary
OTA:             BLE
```

---

## 18. Android development workflow

Typical development cycle:

### Build ESP32 firmware

```bash
cd firmware
pio run
```

### Upload ESP32 firmware

```bash
pio run --target upload
```

### Build Android application

```bash
cd android
./gradlew assembleDebug
```

### Install Android application

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Monitor Android logs

```bash
adb logcat
```

---

## 19. Quick setup from a fresh Arch Linux installation

### Install Java

```bash
sudo pacman -S jdk17-openjdk
```

### Install Android SDK tools

```bash
yay -S android-sdk-cmdline-tools-latest \
       android-sdk-platform-tools \
       android-sdk-build-tools
```

### Give the current user access to the SDK

Replace `dawid:dawid` with your Linux username/group if necessary:

```bash
sudo chown -R dawid:dawid /opt/android-sdk
```

### Configure `~/.bashrc`

Add:

```bash
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk

export PATH="$PATH:$ANDROID_HOME/platform-tools"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin"
```

Reload:

```bash
source ~/.bashrc
```

### Check the tools

```bash
java -version
sdkmanager --version
adb version
```

### Install Android 35

```bash
sdkmanager "platforms;android-35" \
           "build-tools;35.0.0" \
           "platform-tools"
```

### Accept licenses

```bash
yes | sdkmanager --licenses
```

### Build the Android application

```bash
cd android
chmod +x gradlew
./gradlew assembleDebug
```

### Install the application

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 20. Project Android version policy

The current project policy is:

| Setting | Version |
|---|---:|
| Minimum Android | **12 / API 31** |
| Compile SDK | **35** |
| Target SDK | **35** |
| Java | **17** |
| Language | **Kotlin** |
| Communication | **Bluetooth Low Energy** |

These versions should remain consistent between the Android project configuration and this documentation.

If the minimum Android version or BLE protocol changes, update both the Android project and the documentation.
