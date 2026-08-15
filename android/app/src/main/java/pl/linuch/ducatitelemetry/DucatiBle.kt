package pl.linuch.ducatitelemetry

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

object DucatiBle {
    val MAIN_SERVICE: UUID =
        UUID.fromString("7f6d0001-8b7a-4f7b-9d8a-937000000001")
    val TELEMETRY: UUID =
        UUID.fromString("7f6d0002-8b7a-4f7b-9d8a-937000000001")
    val DEVICE_INFO: UUID =
        UUID.fromString("7f6d0003-8b7a-4f7b-9d8a-937000000001")
    val COMMAND: UUID =
        UUID.fromString("7f6d0004-8b7a-4f7b-9d8a-937000000001")

    val OTA_SERVICE: UUID =
        UUID.fromString("7f6d0010-8b7a-4f7b-9d8a-937000000001")
    val OTA_CONTROL: UUID =
        UUID.fromString("7f6d0011-8b7a-4f7b-9d8a-937000000001")
    val OTA_DATA: UUID =
        UUID.fromString("7f6d0012-8b7a-4f7b-9d8a-937000000001")
    val OTA_STATUS: UUID =
        UUID.fromString("7f6d0013-8b7a-4f7b-9d8a-937000000001")

    const val DEVICE_NAME = "Ducati-Monster-937"
}

class DucatiBleClient(
    private val context: Context,
    private val onTelemetry: (Telemetry) -> Unit,
    private val onConnectionChanged: (Boolean) -> Unit
) {
    private val manager =
        context.getSystemService(BluetoothManager::class.java)

    private val adapter: BluetoothAdapter?
        get() = manager?.adapter

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    @SuppressLint("MissingPermission")
    fun startScan() {
        scanner = adapter?.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(DucatiBle.MAIN_SERVICE))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(
            listOf(filter),
            settings,
            scanCallback
        )
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanner?.stopScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        onConnectionChanged(false)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(type: Int, result: ScanResult) {
            val device = result.device
            if (device.name == DucatiBle.DEVICE_NAME) {
                stopScan()
                gatt = device.connectGatt(
                    context,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            g: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onConnectionChanged(true)
                g.requestMtu(247)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onConnectionChanged(false)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val characteristic =
                g.getService(DucatiBle.MAIN_SERVICE)
                    ?.getCharacteristic(DucatiBle.TELEMETRY)
                    ?: return

            g.setCharacteristicNotification(characteristic, true)

            val cccd =
                characteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                ) ?: return

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
}
