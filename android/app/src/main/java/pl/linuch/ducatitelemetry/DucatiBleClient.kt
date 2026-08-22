package pl.linuch.ducatitelemetry

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelUuid
import java.util.UUID
import java.util.zip.CRC32

object DucatiBle {
    val MAIN_SERVICE = UUID.fromString("7f6d0001-8b7a-4f7b-9d8a-937000000001")
    val TELEMETRY = UUID.fromString("7f6d0002-8b7a-4f7b-9d8a-937000000001")
    val DEVICE_INFO = UUID.fromString("7f6d0003-8b7a-4f7b-9d8a-937000000001")
    val COMMAND = UUID.fromString("7f6d0004-8b7a-4f7b-9d8a-937000000001")
    val OTA_SERVICE = UUID.fromString("7f6d0010-8b7a-4f7b-9d8a-937000000001")
    val OTA_CONTROL = UUID.fromString("7f6d0011-8b7a-4f7b-9d8a-937000000001")
    val OTA_DATA = UUID.fromString("7f6d0012-8b7a-4f7b-9d8a-937000000001")
    val OTA_STATUS = UUID.fromString("7f6d0013-8b7a-4f7b-9d8a-937000000001")
    const val DEVICE_NAME = "Ducati-Monster-937"
}

data class DeviceInfo(
    val firmware: String,
    val protocol: String,
    val build: String
)

enum class BleConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

class ReconnectController(
    private val delaysMs: List<Long> = listOf(0, 2_000, 5_000, 10_000, 15_000, 30_000)
) {
    var enabled = false
        private set
    var retryScheduled = false
        private set
    var attemptInProgress = false
        private set
    private var delayIndex = 0

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) cancel()
    }

    fun nextRetry(): Long? {
        if (!enabled || retryScheduled || attemptInProgress) return null
        retryScheduled = true
        val delay = delaysMs[delayIndex.coerceAtMost(delaysMs.lastIndex)]
        if (delayIndex < delaysMs.lastIndex) delayIndex++
        return delay
    }

    fun beginAttempt(): Boolean {
        if (!enabled || !retryScheduled || attemptInProgress) return false
        retryScheduled = false
        attemptInProgress = true
        return true
    }

    fun attemptFailed(): Long? {
        attemptInProgress = false
        return nextRetry()
    }

    fun connected() {
        retryScheduled = false
        attemptInProgress = false
        delayIndex = 0
    }

    fun cancel() {
        retryScheduled = false
        attemptInProgress = false
        delayIndex = 0
    }
}

class DucatiBleClient(
    private val context: Context,
    private val onConnectionChanged: (BleConnectionState, String) -> Unit,
    private val onDeviceInfo: (DeviceInfo?) -> Unit,
    private val onTelemetry: (Telemetry) -> Unit,
    private val onOtaProgress: (Int, Int) -> Unit,
    private val onOtaFinished: (String) -> Unit
) {
    companion object {
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val OTA_START = 0x01
        private const val OTA_END = 0x02
        private const val OTA_ABORT = 0x03
        private const val OTA_READY = 0x01
        private const val OTA_PROGRESS = 0x02
        private const val OTA_SUCCESS = 0x03
        private const val OTA_ERROR = 0x04
        private const val SCAN_TIMEOUT_MS = 8_000L
        private const val BLE_PREFS = "ducati_ble"
        private const val DEVICE_ADDRESS_KEY = "device_address"
    }

    private val adapter =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null
    private var otaControlCharacteristic: BluetoothGattCharacteristic? = null
    private var otaDataCharacteristic: BluetoothGattCharacteristic? = null
    private var otaStatusCharacteristic: BluetoothGattCharacteristic? = null
    private var notificationSetup = mutableListOf<BluetoothGattDescriptor>()
    private var mtu = 23
    private var otaReady = false
    private val handler = Handler(Looper.getMainLooper())
    private val reconnect = ReconnectController()
    private val preferences = context.getSharedPreferences(BLE_PREFS, Context.MODE_PRIVATE)
    private var lastDeviceAddress: String? = preferences.getString(DEVICE_ADDRESS_KEY, null)
    private var scanInProgress = false
    private var connecting = false
    private var explicitDisconnect = false

    private var otaFirmware: ByteArray? = null
    private var otaOffset = 0
    private var otaAwaitingReady = false
    private var otaSending = false

    private val reconnectAttempt = Runnable {
        if (reconnect.beginAttempt()) startScanInternal(reconnecting = true)
    }

    private val scanTimeout = Runnable {
        stopScan()
        connecting = false
        if (reconnect.enabled) {
            onConnectionChanged(BleConnectionState.RECONNECTING, "Ducati not found; retrying…")
            scheduleReconnect(reconnect.attemptFailed())
        } else {
            onConnectionChanged(BleConnectionState.DISCONNECTED, "Ducati not found")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val expectedAddress = lastDeviceAddress
            if ((expectedAddress != null && result.device.address == expectedAddress) ||
                (expectedAddress == null && result.device.name == DucatiBle.DEVICE_NAME)) {
                stopScan()
                lastDeviceAddress = result.device.address
                preferences.edit().putString(DEVICE_ADDRESS_KEY, result.device.address).apply()
                connecting = true
                onConnectionChanged(
                    if (reconnect.enabled) BleConnectionState.RECONNECTING else BleConnectionState.CONNECTING,
                    "Ducati found, connecting…"
                )
                gatt = result.device.connectGatt(
                    context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            connecting = false
            if (reconnect.enabled) {
                onConnectionChanged(BleConnectionState.RECONNECTING, "BLE scan failed: $errorCode; retrying…")
                scheduleReconnect(reconnect.attemptFailed())
            } else {
                onConnectionChanged(BleConnectionState.DISCONNECTED, "BLE scan failed: $errorCode")
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            g: BluetoothGatt, status: Int, newState: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS &&
                newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                connecting = false
                otaReady = false
                onConnectionChanged(BleConnectionState.CONNECTING, "Connected, setting up…")
                if (!g.requestMtu(247)) {
                    g.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handleConnectionFailure(g, "Bike disconnected")
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                handleConnectionFailure(g, "GATT error: $status")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleConnectionFailure(g, "Service discovery failed: $status")
                return
            }

            val telemetry = g.getService(DucatiBle.MAIN_SERVICE)
                ?.getCharacteristic(DucatiBle.TELEMETRY)

            if (telemetry == null) {
                handleConnectionFailure(g, "Telemetry characteristic not found")
                return
            }

            val otaService = g.getService(DucatiBle.OTA_SERVICE)
            otaControlCharacteristic = otaService?.getCharacteristic(DucatiBle.OTA_CONTROL)
            otaDataCharacteristic = otaService?.getCharacteristic(DucatiBle.OTA_DATA)
            otaStatusCharacteristic = otaService?.getCharacteristic(DucatiBle.OTA_STATUS)

            if (otaControlCharacteristic == null || otaDataCharacteristic == null ||
                otaStatusCharacteristic == null) {
                handleConnectionFailure(g, "OTA service not found")
                return
            }

            telemetryCharacteristic = telemetry
            notificationSetup = listOf(telemetry, otaStatusCharacteristic!!).mapNotNull {
                if (g.setCharacteristicNotification(it, true)) {
                    it.getDescriptor(UUID.fromString(CCCD_UUID))
                } else {
                    null
                }
            }.toMutableList()

            if (notificationSetup.size != 2) {
                handleConnectionFailure(g, "Cannot enable notifications")
                return
            }

            writeNextNotificationDescriptor(g)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != DucatiBle.DEVICE_INFO) return

            val info = if (status == BluetoothGatt.GATT_SUCCESS) {
                parseDeviceInfo(characteristic.value.toString(Charsets.UTF_8))
            } else {
                null
            }
            onDeviceInfo(info)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleConnectionFailure(g, "Notification setup failed: $status")
                return
            }

            writeNextNotificationDescriptor(g)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failOta("Firmware transfer failed: $status")
                return
            }

            if (characteristic.uuid == DucatiBle.OTA_DATA && otaSending) {
                sendNextOtaChunk()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                this@DucatiBleClient.mtu = mtu
            }
            g.discoverServices()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == DucatiBle.TELEMETRY) {
                TelemetryDecoder.decode(
                    characteristic.value,
                    System.currentTimeMillis(),
                    SystemClock.elapsedRealtimeNanos()
                )?.let(onTelemetry)
            } else if (characteristic.uuid == DucatiBle.OTA_STATUS) {
                handleOtaStatus(characteristic.value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextNotificationDescriptor(g: BluetoothGatt) {
        if (notificationSetup.isEmpty()) {
            otaReady = true
            explicitDisconnect = false
            reconnect.connected()
            onConnectionChanged(BleConnectionState.CONNECTED, "Connected")
            val deviceInfo = g.getService(DucatiBle.MAIN_SERVICE)
                ?.getCharacteristic(DucatiBle.DEVICE_INFO)
            if (deviceInfo == null || !g.readCharacteristic(deviceInfo)) {
                onDeviceInfo(null)
            }
            return
        }

        val descriptor = notificationSetup.removeAt(0)
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(descriptor)
    }

    private fun parseDeviceInfo(payload: String): DeviceInfo? {
        val fields = payload.split(';').mapNotNull { entry ->
            val parts = entry.split('=', limit = 2)
            if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
        }.toMap()

        val firmware = fields["fw"]?.takeIf { it.isNotEmpty() } ?: return null
        val protocol = fields["protocol"]?.takeIf { it.isNotEmpty() } ?: return null
        val build = fields["build"]?.takeIf { it.isNotEmpty() } ?: return null
        return DeviceInfo(firmware, protocol, build)
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectionFailure(failedGatt: BluetoothGatt, message: String) {
        stopScan()
        connecting = false
        failedGatt.close()
        if (gatt == failedGatt) gatt = null
        telemetryCharacteristic = null
        otaControlCharacteristic = null
        otaDataCharacteristic = null
        otaStatusCharacteristic = null
        otaReady = false
        onDeviceInfo(null)
        finishOta("Firmware update interrupted")

        if (!explicitDisconnect && reconnect.enabled) {
            onConnectionChanged(BleConnectionState.RECONNECTING, "$message; reconnecting…")
            scheduleReconnect(reconnect.attemptFailed())
        } else {
            onConnectionChanged(BleConnectionState.DISCONNECTED, message)
        }
    }

    private fun scheduleReconnect(delayMs: Long?) {
        if (delayMs == null || explicitDisconnect) return
        handler.removeCallbacks(reconnectAttempt)
        handler.postDelayed(reconnectAttempt, delayMs)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (scanInProgress) scanner?.stopScan(scanCallback)
        scanInProgress = false
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        explicitDisconnect = false
        reconnect.cancel()
        startScanInternal(reconnecting = false)
    }

    @SuppressLint("MissingPermission")
    fun setAutoReconnectEnabled(enabled: Boolean) {
        val wasRetrying = reconnect.retryScheduled || reconnect.attemptInProgress ||
            scanInProgress || connecting
        reconnect.setEnabled(enabled)
        if (!enabled) {
            handler.removeCallbacks(reconnectAttempt)
            stopScan()
            if (connecting) {
                explicitDisconnect = true
                gatt?.disconnect()
                gatt?.close()
                gatt = null
                connecting = false
            }
            if (wasRetrying && !otaReady) {
                onConnectionChanged(BleConnectionState.DISCONNECTED, "Reconnect cancelled")
            }
        }
    }

    fun reconnectForActiveRide() {
        explicitDisconnect = false
        reconnect.setEnabled(true)
        if (gatt == null && !scanInProgress && !connecting) {
            scheduleReconnect(reconnect.nextRetry())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanInternal(reconnecting: Boolean) {
        if (scanInProgress || connecting || gatt != null) return
        if (adapter == null) {
            onConnectionChanged(BleConnectionState.DISCONNECTED, "Bluetooth unavailable")
            if (reconnecting) scheduleReconnect(reconnect.attemptFailed())
            return
        }

        if (!adapter.isEnabled) {
            onConnectionChanged(BleConnectionState.DISCONNECTED, "Bluetooth is disabled")
            if (reconnecting) scheduleReconnect(reconnect.attemptFailed())
            return
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onConnectionChanged(BleConnectionState.DISCONNECTED, "BLE scanner unavailable")
            if (reconnecting) scheduleReconnect(reconnect.attemptFailed())
            return
        }

        onConnectionChanged(
            if (reconnecting) BleConnectionState.RECONNECTING else BleConnectionState.CONNECTING,
            if (reconnecting) "Waiting for Ducati…" else "Scanning…"
        )

        val filter = ScanFilter.Builder().apply {
            lastDeviceAddress?.let(::setDeviceAddress)
                ?: setServiceUuid(ParcelUuid(DucatiBle.MAIN_SERVICE))
        }.build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanInProgress = true
        scanner?.startScan(listOf(filter), settings, scanCallback)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    fun startOta(firmware: ByteArray) {
        val control = otaControlCharacteristic
        if (gatt == null || control == null || !otaReady || otaSending || otaAwaitingReady) {
            onOtaFinished("Finish connecting to the Ducati before updating firmware")
            return
        }

        if (firmware.isEmpty()) {
            onOtaFinished("Firmware file is empty")
            return
        }

        val crc = CRC32().apply { update(firmware) }.value.toInt()
        val start = ByteArray(9)
        start[0] = OTA_START.toByte()
        writeUInt32Le(start, 1, firmware.size.toLong())
        writeUInt32Le(start, 5, crc.toLong() and 0xffffffffL)

        otaFirmware = firmware
        otaOffset = 0
        otaAwaitingReady = true
        onOtaProgress(0, firmware.size)

        control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        control.value = start
        if (!gatt!!.writeCharacteristic(control)) {
            failOta("Could not start firmware update")
        }
    }

    @SuppressLint("MissingPermission")
    fun cancelOta() {
        if (otaFirmware == null) return

        otaControlCharacteristic?.let { control ->
            control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            control.value = byteArrayOf(OTA_ABORT.toByte())
            gatt?.writeCharacteristic(control)
        }
        finishOta("Firmware update cancelled")
    }

    private fun handleOtaStatus(data: ByteArray) {
        if (data.size != 6 || otaFirmware == null) return

        val type = data[0].toInt() and 0xff
        val value = readUInt32Le(data, 1)
        when (type) {
            OTA_READY -> if (otaAwaitingReady) {
                otaAwaitingReady = false
                otaSending = true
                sendNextOtaChunk()
            }

            OTA_PROGRESS -> onOtaProgress(value.coerceAtMost(otaFirmware!!.size), otaFirmware!!.size)
            OTA_SUCCESS -> finishOta("Firmware update complete. Ducati is restarting…")
            OTA_ERROR -> failOta("Ducati rejected the update (error $value)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNextOtaChunk() {
        val firmware = otaFirmware ?: return
        val dataCharacteristic = otaDataCharacteristic ?: run {
            failOta("OTA data channel is unavailable")
            return
        }

        if (otaOffset >= firmware.size) {
            otaSending = false
            otaControlCharacteristic?.let { control ->
                control.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                control.value = byteArrayOf(OTA_END.toByte())
                if (gatt?.writeCharacteristic(control) != true) {
                    failOta("Could not finish firmware update")
                }
            }
            return
        }

        val payloadSize = (mtu - 7).coerceIn(1, 180)
        val size = minOf(payloadSize, firmware.size - otaOffset)
        val packet = ByteArray(size + 4)
        writeUInt32Le(packet, 0, otaOffset.toLong())
        firmware.copyInto(packet, 4, otaOffset, otaOffset + size)
        otaOffset += size
        onOtaProgress(otaOffset, firmware.size)

        dataCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        dataCharacteristic.value = packet
        if (gatt?.writeCharacteristic(dataCharacteristic) != true) {
            failOta("Could not send firmware data")
        }
    }

    private fun failOta(message: String) {
        otaSending = false
        otaAwaitingReady = false
        otaFirmware = null
        onOtaFinished(message)
    }

    private fun finishOta(message: String) {
        if (otaFirmware == null && !otaSending && !otaAwaitingReady) return
        otaSending = false
        otaAwaitingReady = false
        otaFirmware = null
        onOtaFinished(message)
    }

    private fun writeUInt32Le(target: ByteArray, offset: Int, value: Long) {
        for (index in 0..3) {
            target[offset + index] = (value shr (index * 8)).toByte()
        }
    }

    private fun readUInt32Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    @SuppressLint("MissingPermission")
    fun disconnect() {
        explicitDisconnect = true
        reconnect.setEnabled(false)
        handler.removeCallbacks(reconnectAttempt)
        stopScan()
        scanner = null
        connecting = false
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        otaReady = false
        finishOta("Firmware update interrupted")
        onDeviceInfo(null)
        onConnectionChanged(BleConnectionState.DISCONNECTED, "Disconnected")
    }
}
