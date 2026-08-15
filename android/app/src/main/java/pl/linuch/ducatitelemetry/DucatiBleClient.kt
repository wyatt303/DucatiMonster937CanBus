package pl.linuch.ducatitelemetry

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

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

class DucatiBleClient(
    private val context: Context,
    private val onConnectionChanged: (Boolean, String) -> Unit,
    private val onTelemetry: (Telemetry) -> Unit
) {
    private val adapter =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name == DucatiBle.DEVICE_NAME) {
                scanner?.stopScan(this)
                onConnectionChanged(false, "Ducati found, connecting…")
                gatt = result.device.connectGatt(
                    context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            onConnectionChanged(false, "BLE scan failed: $errorCode")
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
                onConnectionChanged(true, "Connected")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onConnectionChanged(false, "Disconnected")
                g.close()
                if (gatt == g) gatt = null
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                onConnectionChanged(false, "GATT error: $status")
                g.close()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onConnectionChanged(false, "Service discovery failed: $status")
                return
            }

            val c = g.getService(DucatiBle.MAIN_SERVICE)
                ?.getCharacteristic(DucatiBle.TELEMETRY)

            if (c == null) {
                onConnectionChanged(false, "Telemetry characteristic not found")
                return
            }

            if (!g.setCharacteristicNotification(c, true)) {
                onConnectionChanged(false, "Cannot enable notifications")
                return
            }

            val cccd = c.getDescriptor(
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            )

            if (cccd == null) {
                onConnectionChanged(false, "CCCD not found")
                return
            }

            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == DucatiBle.TELEMETRY) {
                TelemetryDecoder.decode(
                    characteristic.value,
                    System.currentTimeMillis()
                )?.let(onTelemetry)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (adapter == null) {
            onConnectionChanged(false, "Bluetooth unavailable")
            return
        }

        if (!adapter.isEnabled) {
            onConnectionChanged(false, "Bluetooth is disabled")
            return
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onConnectionChanged(false, "BLE scanner unavailable")
            return
        }

        onConnectionChanged(false, "Scanning…")

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(DucatiBle.MAIN_SERVICE))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        scanner?.stopScan(scanCallback)
        scanner = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        onConnectionChanged(false, "Disconnected")
    }
}
