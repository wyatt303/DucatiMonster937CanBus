package pl.linuch.ducatitelemetry

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_BLE = 100
        private const val CREATE_CSV = 200
        private const val OPEN_FIRMWARE = 300
    }

    private lateinit var status: TextView
    private lateinit var rpm: TextView
    private lateinit var gear: TextView
    private lateinit var speed: TextView
    private lateinit var throttle: TextView
    private lateinit var brake: TextView
    private lateinit var engine: TextView
    private lateinit var ambient: TextView
    private lateinit var packets: TextView
    private lateinit var deviceInfo: TextView

    private lateinit var connect: Button
    private lateinit var record: Button
    private lateinit var export: Button
    private lateinit var updateFirmware: Button

    private lateinit var ble: DucatiBleClient
    private val recorder = CsvRecorder()

    private var connected = false
    private var recording = false
    private var packetCount = 0L
    private var dropped = 0L
    private var lastSequence: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.connectionStatus)
        rpm = findViewById(R.id.rpmValue)
        gear = findViewById(R.id.gearValue)
        speed = findViewById(R.id.speedValue)
        throttle = findViewById(R.id.throttleValue)
        brake = findViewById(R.id.brakeValue)
        engine = findViewById(R.id.engineTempValue)
        ambient = findViewById(R.id.ambientTempValue)
        packets = findViewById(R.id.packetInfo)
        deviceInfo = findViewById(R.id.deviceInfo)

        connect = findViewById(R.id.connectButton)
        record = findViewById(R.id.recordButton)
        export = findViewById(R.id.exportButton)
        updateFirmware = findViewById(R.id.updateFirmwareButton)

        ble = DucatiBleClient(
            this,
            onConnectionChanged = { isConnected, message ->
                runOnUiThread {
                    connected = isConnected
                    status.text = message
                    connect.text = if (isConnected) "Disconnect" else "Connect"
                    record.isEnabled = isConnected
                    updateFirmware.isEnabled = isConnected
                }
            },
            onDeviceInfo = { info ->
                runOnUiThread {
                    deviceInfo.text = if (info == null) {
                        "Firmware: Unknown"
                    } else {
                        "Firmware: v${info.firmware}\nProtocol: ${info.protocol}\nBuild: ${info.build}"
                    }
                }
            },
            onTelemetry = { telemetry ->
                runOnUiThread {
                    showTelemetry(telemetry)
                }
            },
            onOtaProgress = { sent, total ->
                runOnUiThread {
                    updateFirmware.isEnabled = false
                    status.text = "Updating firmware: $sent / $total bytes"
                }
            },
            onOtaFinished = { message ->
                runOnUiThread {
                    updateFirmware.isEnabled = connected
                    status.text = message
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        )

        connect.setOnClickListener {
            if (connected) {
                ble.disconnect()
            } else if (hasBlePermissions()) {
                ble.startScan()
            } else {
                requestBlePermissions()
            }
        }

        record.setOnClickListener {
            if (recording) {
                recording = false
                record.text = "Start recording"
                export.isEnabled = !recorder.isEmpty()
            } else {
                recorder.start()
                recording = true
                record.text = "Stop recording"
                export.isEnabled = false
            }
        }

        export.setOnClickListener {
            exportCsv()
        }

        updateFirmware.setOnClickListener {
            selectFirmware()
        }

        if (!hasBlePermissions()) {
            requestBlePermissions()
        }
    }

    private fun hasBlePermissions(): Boolean {
        val scanGranted =
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED

        val connectGranted =
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

        return scanGranted && connectGranted
    }

    private fun requestBlePermissions() {
        requestPermissions(
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ),
            REQUEST_BLE
        )
    }

    private fun showTelemetry(t: Telemetry) {
        packetCount++

        val previousSequence = lastSequence

        if (previousSequence != null && t.sequence > previousSequence + 1) {
            dropped += t.sequence - previousSequence - 1
        }

        lastSequence = t.sequence

        rpm.text = t.rpm.toString()

        val gearText = if (t.gear == 0) {
            "N"
        } else {
            t.gear.toString()
        }

        gear.text = "Gear $gearText"

        speed.text =
            "Speed %.2f km/h".format(Locale.US, t.speedKmh)

        throttle.text =
            "Throttle %.2f %%".format(Locale.US, t.throttlePercent)

        brake.text =
            "Front brake %.2f %%".format(Locale.US, t.frontBrakePercent)

        engine.text =
            "Engine ${t.engineTempC} °C"

        ambient.text =
            "Ambient ${t.ambientTempC} °C"

        packets.text =
            "Packets $packetCount   Sequence ${t.sequence}   Dropped $dropped"

        if (recording) {
            recorder.append(t)
        }
    }

    private fun exportCsv() {
        if (recorder.isEmpty()) {
            Toast.makeText(
                this,
                "No telemetry recorded",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val timestamp = SimpleDateFormat(
            "yyyyMMdd-HHmmss",
            Locale.US
        ).format(Date())

        val filename = "ducati-telemetry-$timestamp.csv"

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, filename)
        }

        startActivityForResult(intent, CREATE_CSV)
    }

    private fun selectFirmware() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/octet-stream"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, OPEN_FIRMWARE)
    }

    private fun startFirmwareUpdate(uri: Uri) {
        try {
            val firmware = contentResolver.openInputStream(uri)?.use {
                it.readBytes()
            } ?: throw IllegalStateException("Cannot read firmware file")

            if (firmware.isEmpty()) {
                throw IllegalArgumentException("Firmware file is empty")
            }

            updateFirmware.isEnabled = false
            ble.startOta(firmware)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Cannot open firmware: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Activity Result API can be introduced later.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK) return

        val uri: Uri = data?.data ?: return
        when (requestCode) {
            CREATE_CSV -> {
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        recorder.writeTo(output)
                    }

                    Toast.makeText(
                        this,
                        "CSV exported",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "CSV export failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            OPEN_FIRMWARE -> startFirmwareUpdate(uri)
        }
    }

    override fun onDestroy() {
        ble.disconnect()
        super.onDestroy()
    }
}
