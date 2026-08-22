package pl.linuch.ducatitelemetry

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    companion object {
        private const val REQUEST_BLE = 100
        private const val REQUEST_LOCATION = 101
        private const val CREATE_CSV = 200
        private const val OPEN_FIRMWARE = 300
        private const val RETENTION_KEY = "ride_session_retention"
        private const val UNLIMITED = -1
        private const val GNSS_ENABLED_KEY = "gnss_enabled"
        private const val LEAN_ENABLED_KEY = "lean_enabled"
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
    private lateinit var sessionStatus: TextView
    private lateinit var connect: Button
    private lateinit var record: Button
    private lateinit var stopRecording: Button
    private lateinit var updateFirmware: Button
    private lateinit var savedRides: LinearLayout
    private lateinit var retention: Spinner
    private lateinit var gnssSwitch: Switch
    private lateinit var leanSwitch: Switch
    private lateinit var gnssStatusView: TextView
    private lateinit var leanStatusView: TextView
    private lateinit var calibrateLean: Button
    private lateinit var resetLean: Button
    private lateinit var ble: DucatiBleClient
    private lateinit var sessions: RideSessionManager
    private lateinit var phoneSensors: PhoneSensorManager
    private val csvExporter = CsvExporter()
    private var connected = false
    private var bleState = BleConnectionState.DISCONNECTED
    private var packetCount = 0L
    private var dropped = 0L
    private var lastSequence: Long? = null
    private var pendingExportSessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        sessions = RideSessionManager(File(filesDir, "ride_sessions"), ::retentionLimit)
        val recovered = sessions.recoverSessions()
        configureRetention()
        configurePhoneSensors()
        configureBle()
        configureActions()
        renderSessionState()
        renderSavedRides()
        if (recovered.isNotEmpty()) {
            Toast.makeText(this, "Recovered ${recovered.size} interrupted ride${if (recovered.size == 1) "" else "s"}", Toast.LENGTH_LONG).show()
        }
        if (!hasBlePermissions()) {
            requestBlePermissions()
        } else if (sessions.activeSession() != null) {
            ble.reconnectForActiveRide()
        }
    }

    private fun bindViews() {
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
        sessionStatus = findViewById(R.id.sessionStatus)
        connect = findViewById(R.id.connectButton)
        record = findViewById(R.id.recordButton)
        stopRecording = findViewById(R.id.stopRecordingButton)
        updateFirmware = findViewById(R.id.updateFirmwareButton)
        savedRides = findViewById(R.id.savedRidesContainer)
        retention = findViewById(R.id.retentionSpinner)
        gnssSwitch = findViewById(R.id.gnssSwitch)
        leanSwitch = findViewById(R.id.leanSwitch)
        gnssStatusView = findViewById(R.id.gnssStatus)
        leanStatusView = findViewById(R.id.leanStatus)
        calibrateLean = findViewById(R.id.calibrateLeanButton)
        resetLean = findViewById(R.id.resetLeanButton)
    }

    private fun configurePhoneSensors() {
        val preferences = getPreferences(MODE_PRIVATE)
        phoneSensors = PhoneSensorManager(this, PreferenceLeanCalibrationStore(this)) { snapshot, gpsStatus ->
            runOnUiThread {
                gnssStatusView.text = buildString {
                    append("GPS: ").append(gpsStatus.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase))
                    snapshot.gnss?.accuracyM?.let { append("\nAccuracy: %.1f m".format(Locale.US, it)) }
                }
                leanStatusView.text = when {
                    !leanSwitch.isChecked -> "Lean: Disabled"
                    !phoneSensors.leanAvailable -> "Lean: unavailable (orientation sensor missing)"
                    !phoneSensors.calibrated -> "Lean calibration: Required"
                    snapshot.imu != null -> "Lean: %+.1f°\nIMU: %s".format(Locale.US, snapshot.imu.leanAngleDeg,
                        accuracyLabel(snapshot.imu.accuracy))
                    else -> "Lean calibration: Calibrated · Waiting for IMU"
                }
            }
        }
        val gnssEnabled = preferences.getBoolean(GNSS_ENABLED_KEY, true)
        val leanEnabled = preferences.getBoolean(LEAN_ENABLED_KEY, true)
        gnssSwitch.isChecked = gnssEnabled
        leanSwitch.isChecked = leanEnabled && phoneSensors.leanAvailable
        leanSwitch.isEnabled = phoneSensors.leanAvailable
        calibrateLean.isEnabled = phoneSensors.leanAvailable && leanSwitch.isChecked
        resetLean.isEnabled = phoneSensors.calibrated
        gnssSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(GNSS_ENABLED_KEY, checked).apply()
            if (checked && !hasLocationPermission()) requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
            phoneSensors.setEnabled(checked, leanSwitch.isChecked)
        }
        leanSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(LEAN_ENABLED_KEY, checked).apply()
            calibrateLean.isEnabled = checked && phoneSensors.leanAvailable
            phoneSensors.setEnabled(gnssSwitch.isChecked, checked)
        }
        calibrateLean.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Lean Angle Calibration")
                .setMessage("Place the motorcycle upright and stationary with the phone mounted in its normal riding position.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Calibrate") { _, _ ->
                    val success = phoneSensors.calibrate()
                    resetLean.isEnabled = success
                    Toast.makeText(this, if (success) "Lean angle calibrated: upright is 0°" else "Waiting for orientation sensor", Toast.LENGTH_LONG).show()
                }.show()
        }
        resetLean.setOnClickListener {
            phoneSensors.resetCalibration()
            resetLean.isEnabled = false
            Toast.makeText(this, "Lean calibration reset", Toast.LENGTH_SHORT).show()
        }
        phoneSensors.setEnabled(gnssEnabled, leanEnabled)
        if (gnssEnabled && !hasLocationPermission()) requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQUEST_LOCATION)
    }

    private fun accuracyLabel(accuracy: Int?): String = when (accuracy) {
        android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "High"
        android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Medium"
        android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Low"
        android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE -> "Unreliable"
        else -> "Available"
    }

    private fun configureBle() {
        ble = DucatiBleClient(
            this,
            onConnectionChanged = { connectionState, message ->
                runOnUiThread {
                    bleState = connectionState
                    connected = connectionState == BleConnectionState.CONNECTED
                    status.text = message
                    connect.text = when (connectionState) {
                        BleConnectionState.CONNECTED -> "Disconnect"
                        BleConnectionState.RECONNECTING -> "Cancel reconnect"
                        BleConnectionState.CONNECTING -> "Cancel"
                        BleConnectionState.DISCONNECTED -> "Connect"
                    }
                    updateFirmware.isEnabled = connected
                    renderSessionState()
                }
            },
            onDeviceInfo = { info ->
                runOnUiThread {
                    deviceInfo.text = if (info == null) "Firmware: Unknown" else
                        "Firmware: v${info.firmware}\nProtocol: ${info.protocol}\nBuild: ${info.build}"
                }
            },
            onTelemetry = { telemetry -> runOnUiThread { showTelemetry(telemetry) } },
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
    }

    private fun configureActions() {
        connect.setOnClickListener {
            if (bleState != BleConnectionState.DISCONNECTED) ble.disconnect()
            else if (hasBlePermissions()) {
                ble.setAutoReconnectEnabled(sessions.activeSession() != null)
                ble.startScan()
            }
            else requestBlePermissions()
        }
        record.setOnClickListener {
            try {
                when (sessions.activeSession()?.state) {
                    null -> {
                        sessions.startSession()
                        ble.setAutoReconnectEnabled(true)
                    }
                    RideSessionState.RECORDING -> sessions.pauseSession()
                    RideSessionState.PAUSED -> if (connected) sessions.resumeSession() else Unit
                    else -> Unit
                }
                renderSessionState()
            } catch (error: Exception) { showStorageError(error) }
        }
        stopRecording.setOnClickListener {
            try {
                if (sessions.stopSession() == null) {
                    Toast.makeText(this, "Empty ride discarded", Toast.LENGTH_SHORT).show()
                }
                ble.setAutoReconnectEnabled(false)
                renderSessionState()
                renderSavedRides()
            } catch (error: Exception) { showStorageError(error) }
        }
        updateFirmware.setOnClickListener { selectFirmware() }
    }

    private fun configureRetention() {
        retention.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("5", "10", "20", "50", "Unlimited")
        )
        retention.setSelection(when (retentionLimit()) { 5 -> 0; 20 -> 2; 50 -> 3; null -> 4; else -> 1 })
        retention.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = when (position) { 0 -> 5; 1 -> 10; 2 -> 20; 3 -> 50; else -> UNLIMITED }
                getPreferences(MODE_PRIVATE).edit().putInt(RETENTION_KEY, value).apply()
                sessions.enforceRetentionLimit()
                renderSavedRides()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun retentionLimit(): Int? = getPreferences(MODE_PRIVATE)
        .getInt(RETENTION_KEY, 10).takeUnless { it == UNLIMITED }

    private fun showTelemetry(t: Telemetry) {
        packetCount++
        lastSequence?.let { if (t.sequence > it + 1) dropped += t.sequence - it - 1 }
        lastSequence = t.sequence
        rpm.text = t.rpm.toString()
        gear.text = "Gear ${if (t.gear == 0) "N" else t.gear}"
        speed.text = "Speed %.2f km/h".format(Locale.US, t.speedKmh)
        throttle.text = "Throttle %.2f %%".format(Locale.US, t.throttlePercent)
        brake.text = "Front brake %.2f %%".format(Locale.US, t.frontBrakePercent)
        engine.text = "Engine ${t.engineTempC} °C"
        ambient.text = "Ambient ${t.ambientTempC} °C"
        packets.text = "Packets $packetCount   Sequence ${t.sequence}   Dropped $dropped"
        val sensorTimestamp = t.phoneTimestampNanos.takeIf { it > 0 } ?: SystemClock.elapsedRealtimeNanos()
        sessions.appendTelemetry(t, phoneSensors.snapshotAt(sensorTimestamp))?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            renderSessionState()
            renderSavedRides()
        }
        if (sessions.activeSession()?.state == RideSessionState.RECORDING) renderSessionStatus()
    }

    private fun renderSessionState() {
        when (sessions.activeSession()?.state) {
            RideSessionState.RECORDING -> {
                record.text = "Pause"; record.isEnabled = true; stopRecording.visibility = View.VISIBLE
            }
            RideSessionState.PAUSED -> {
                record.text = "Resume"; record.isEnabled = connected; stopRecording.visibility = View.VISIBLE
            }
            else -> {
                record.text = "Start"; record.isEnabled = connected; stopRecording.visibility = View.GONE
            }
        }
        renderSessionStatus()
    }

    private fun renderSessionStatus() {
        val session = sessions.activeSession()
        sessionStatus.text = if (session == null) "Recording: Idle" else {
            val label = if (session.state == RideSessionState.PAUSED) "Paused" else "Recording"
            val bike = when (bleState) {
                BleConnectionState.CONNECTED -> "Bike connected"
                BleConnectionState.RECONNECTING -> "Bike disconnected · Waiting for Ducati…"
                BleConnectionState.CONNECTING -> "Connecting to bike…"
                BleConnectionState.DISCONNECTED -> "Bike disconnected"
            }
            "$label · ${session.sampleCount} samples\n$bike\n" +
                "Total ${formatDuration(session.totalDurationMs())} · " +
                "Paused ${formatDuration(session.pausedDurationMs())} · " +
                "Recorded ${formatDuration(session.activeDurationMs())}"
        }
    }

    private fun renderSavedRides() {
        savedRides.removeAllViews()
        val rides = sessions.listSessions()
        if (rides.isEmpty()) {
            savedRides.addView(TextView(this).apply {
                text = "No saved rides"; setTextColor(getColor(R.color.text_secondary))
            })
            return
        }
        val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
        rides.forEach { ride ->
            savedRides.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
                addView(TextView(context).apply {
                    text = "${if (ride.recovered) "Recovered" else "Completed"}\n" +
                        "${dateFormat.format(Date(ride.startTime))}\n" +
                        "Total ${formatDuration(ride.totalDurationMs())}\n" +
                        "Paused ${formatDuration(ride.pausedDurationMs())}\n" +
                        "Recorded ${formatDuration(ride.activeDurationMs())}\n" +
                        "${ride.sampleCount} samples"
                    setTextColor(getColor(R.color.text_primary)); textSize = 16f
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(context).apply { text = "Export"; setOnClickListener { exportSession(ride) } })
                    addView(Button(context).apply {
                        text = "Delete"; setOnClickListener { if (sessions.deleteSession(ride.id)) renderSavedRides() }
                    })
                })
            })
        }
    }

    private fun exportSession(session: RideSession) {
        pendingExportSessionId = session.id
        val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(session.startTime))
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_TITLE, "ducati-telemetry-$date.csv")
        }, CREATE_CSV)
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remaining = seconds % 60
        return if (hours > 0) "%dh %02dm %02ds".format(hours, minutes, remaining)
        else "%dm %02ds".format(minutes, remaining)
    }

    private fun showStorageError(error: Exception) {
        Toast.makeText(this, error.message ?: "Ride storage failed", Toast.LENGTH_LONG).show()
        renderSessionState(); renderSavedRides()
    }

    private fun hasBlePermissions() =
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestBlePermissions() = requestPermissions(
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLE
    )

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLE && hasBlePermissions() && sessions.activeSession() != null) {
            ble.reconnectForActiveRide()
        } else if (requestCode == REQUEST_LOCATION) {
            phoneSensors.setEnabled(gnssSwitch.isChecked, leanSwitch.isChecked)
        }
    }

    private fun selectFirmware() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "application/octet-stream"; addCategory(Intent.CATEGORY_OPENABLE)
    }, OPEN_FIRMWARE)

    private fun startFirmwareUpdate(uri: Uri) {
        try {
            val firmware = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Cannot read firmware file")
            require(firmware.isNotEmpty()) { "Firmware file is empty" }
            updateFirmware.isEnabled = false
            ble.startOta(firmware)
        } catch (error: Exception) {
            Toast.makeText(this, "Cannot open firmware: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Activity Result API can be introduced later.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            CREATE_CSV -> {
                val id = pendingExportSessionId ?: return
                try {
                    val source = sessions.sessionFile(id) ?: error("Saved ride is unavailable")
                    val output = contentResolver.openOutputStream(uri) ?: error("Cannot create export file")
                    csvExporter.export(source, output)
                    Toast.makeText(this, "Ride exported", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    Toast.makeText(this, "CSV export failed: ${error.message}", Toast.LENGTH_LONG).show()
                } finally { pendingExportSessionId = null }
            }
            OPEN_FIRMWARE -> startFirmwareUpdate(uri)
        }
    }

    override fun onDestroy() {
        phoneSensors.stop()
        ble.disconnect()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        phoneSensors.setEnabled(gnssSwitch.isChecked, leanSwitch.isChecked)
    }
}
