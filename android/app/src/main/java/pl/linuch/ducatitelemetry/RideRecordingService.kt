package pl.linuch.ducatitelemetry

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

data class RideServiceSnapshot(
    val session: RideSession? = null,
    val bleState: BleConnectionState = BleConnectionState.DISCONNECTED,
    val connectionMessage: String = "Disconnected",
    val telemetry: Telemetry? = null,
    val deviceInfo: DeviceInfo? = null,
    val packetCount: Long = 0,
    val droppedPackets: Long = 0,
    val phoneSensors: PhoneSensorSnapshot = PhoneSensorSnapshot(),
    val gnssStatus: GnssStatus = GnssStatus.DISABLED,
    val leanAvailable: Boolean = false,
    val calibrated: Boolean = false,
    val gnssEnabled: Boolean = true,
    val leanEnabled: Boolean = true,
    val recoveredCount: Int = 0,
    val message: String? = null,
    val otaInProgress: Boolean = false
)

class RideRecordingService : Service() {
    companion object {
        const val ACTION_KEEP_ALIVE = "pl.linuch.ducatitelemetry.KEEP_RIDE_ACTIVE"
        const val ACTION_PAUSE = "pl.linuch.ducatitelemetry.PAUSE_RIDE"
        const val ACTION_RESUME = "pl.linuch.ducatitelemetry.RESUME_RIDE"
        const val ACTION_STOP = "pl.linuch.ducatitelemetry.STOP_RIDE"
        private const val CHANNEL_ID = "ride_recording"
        private const val NOTIFICATION_ID = 937
        private const val SETTINGS = "MainActivity"
        private const val RETENTION_KEY = "ride_session_retention"
        private const val GNSS_ENABLED_KEY = "gnss_enabled"
        private const val LEAN_ENABLED_KEY = "lean_enabled"
        private const val UNLIMITED = -1
    }

    inner class LocalBinder : Binder() {
        fun service() = this@RideRecordingService
    }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<(RideServiceSnapshot) -> Unit>()
    private lateinit var sessions: RideSessionManager
    private lateinit var runtime: RideRuntimeController
    private lateinit var phoneSensors: PhoneSensorManager
    private lateinit var ble: DucatiBleClient
    private var state = RideServiceSnapshot()
    private var lastSequence: Long? = null
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sessions = RideSessionManager(File(filesDir, "ride_sessions"), ::retentionLimit)
        val recovered = sessions.recoverSessions()
        runtime = RideRuntimeController(sessions)
        val prefs = settings()
        phoneSensors = PhoneSensorManager(this, PreferenceLeanCalibrationStore(this)) { snapshot, gnss ->
            val changedStatus = gnss != state.gnssStatus
            state = state.copy(phoneSensors = snapshot, gnssStatus = gnss,
                leanAvailable = phoneSensors.leanAvailable, calibrated = phoneSensors.calibrated)
            publish(changedStatus)
        }
        val gnssEnabled = prefs.getBoolean(GNSS_ENABLED_KEY, true)
        val leanEnabled = prefs.getBoolean(LEAN_ENABLED_KEY, true)
        state = state.copy(session = runtime.session, recoveredCount = recovered.size,
            gnssEnabled = gnssEnabled, leanEnabled = leanEnabled)
        configureBle()
        phoneSensors.setEnabled(gnssEnabled, leanEnabled)
        if (runtime.serviceRequired) {
            ble.setAutoReconnectEnabled(true)
            if (hasBlePermissions()) {
                keepStartedAndForeground()
                ble.reconnectForActiveRide()
            }
        }
    }

    private fun configureBle() {
        ble = DucatiBleClient(this,
            onConnectionChanged = { connection, message ->
                state = state.copy(bleState = connection, connectionMessage = message)
                if (connection == BleConnectionState.CONNECTED) runtime.onBleReconnected()
                else if (connection == BleConnectionState.RECONNECTING) runtime.onBleDisconnected()
                publish(true)
            },
            onDeviceInfo = { state = state.copy(deviceInfo = it); publish() },
            onTelemetry = ::handleTelemetry,
            onOtaProgress = { sent, total ->
                state = state.copy(message = "Updating firmware: $sent / $total bytes", otaInProgress = true)
                publish()
            },
            onOtaFinished = { message -> state = state.copy(message = message, otaInProgress = false); publish() }
        )
    }

    private fun handleTelemetry(telemetry: Telemetry) {
        var dropped = state.droppedPackets
        lastSequence?.let { if (telemetry.sequence > it + 1) dropped += telemetry.sequence - it - 1 }
        lastSequence = telemetry.sequence
        val timestamp = telemetry.phoneTimestampNanos.takeIf { it > 0 } ?: SystemClock.elapsedRealtimeNanos()
        val storageError = sessions.appendTelemetry(telemetry, phoneSensors.snapshotAt(timestamp))
        state = state.copy(session = runtime.session, telemetry = telemetry,
            packetCount = state.packetCount + 1, droppedPackets = dropped,
            message = storageError ?: state.message)
        publish(storageError != null)
    }

    override fun onBind(intent: Intent?): IBinder {
        publish()
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseRide()
            ACTION_RESUME -> resumeRide()
            ACTION_STOP -> stopRide()
            ACTION_KEEP_ALIVE, null -> if (runtime.serviceRequired) ensureForeground()
        }
        return START_NOT_STICKY
    }

    fun observe(listener: (RideServiceSnapshot) -> Unit) { listeners += listener; listener(state.copy(session = runtime.session)) }
    fun removeObserver(listener: (RideServiceSnapshot) -> Unit) { listeners -= listener }
    fun snapshot() = state.copy(session = runtime.session)

    fun startRide(): RideSession {
        val session = runtime.start()
        ble.setAutoReconnectEnabled(true)
        state = state.copy(session = session, message = null)
        keepStartedAndForeground()
        publish(true)
        return session
    }

    fun pauseRide(): RideSession? {
        val result = runtime.pause()
        state = state.copy(session = result)
        if (runtime.serviceRequired) ensureForeground()
        publish(true)
        return result
    }

    fun resumeRide(): RideSession? {
        val result = runtime.resume()
        state = state.copy(session = result)
        if (runtime.serviceRequired) keepStartedAndForeground()
        publish(true)
        return result
    }

    fun stopRide(): RideSession? {
        val result = runtime.stop()
        ble.setAutoReconnectEnabled(false)
        state = state.copy(session = null)
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
        publish()
        return result
    }

    fun connect() { ble.setAutoReconnectEnabled(runtime.serviceRequired); ble.startScan() }
    fun permissionsChanged() {
        phoneSensors.setEnabled(state.gnssEnabled, state.leanEnabled)
        if (runtime.serviceRequired && hasBlePermissions() && state.bleState == BleConnectionState.DISCONNECTED) {
            keepStartedAndForeground()
            ble.reconnectForActiveRide()
        }
        if (foreground) ensureForeground()
    }
    fun disconnect() = ble.disconnect()
    fun startOta(firmware: ByteArray) = ble.startOta(firmware)
    fun calibrateLean() = phoneSensors.calibrate().also { state = state.copy(calibrated = phoneSensors.calibrated); publish() }
    fun resetLean() { phoneSensors.resetCalibration(); state = state.copy(calibrated = false); publish() }
    fun setPhoneSensors(gnss: Boolean, lean: Boolean) {
        settings().edit().putBoolean(GNSS_ENABLED_KEY, gnss).putBoolean(LEAN_ENABLED_KEY, lean).apply()
        phoneSensors.setEnabled(gnss, lean)
        state = state.copy(gnssEnabled = gnss, leanEnabled = lean)
        if (foreground) ensureForeground()
        publish()
    }

    fun listSessions() = sessions.listSessions()
    fun deleteSession(id: String) = sessions.deleteSession(id)
    fun sessionFile(id: String) = sessions.sessionFile(id)
    fun setRetention(value: Int?) {
        settings().edit().putInt(RETENTION_KEY, value ?: UNLIMITED).apply()
        sessions.enforceRetentionLimit()
        publish()
    }
    fun retentionLimit(): Int? = settings().getInt(RETENTION_KEY, 10).takeUnless { it == UNLIMITED }

    private fun keepStartedAndForeground() {
        startService(Intent(this, RideRecordingService::class.java).setAction(ACTION_KEEP_ALIVE))
        ensureForeground()
    }

    private fun ensureForeground() {
        if (!runtime.serviceRequired) return
        val notification = buildNotification()
        var types = 0
        if (hasBlePermissions()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (state.gnssEnabled && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (types == 0) return
        startForeground(NOTIFICATION_ID, notification, types)
        foreground = true
    }

    private fun buildNotification(): Notification {
        val session = runtime.session
        val paused = session?.state == RideSessionState.PAUSED
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (paused) "Ducati Telemetry · Paused" else "Ducati Telemetry · Recording")
            .setContentText("${bikeLabel()} · GPS ${state.gnssStatus.name.lowercase().replace('_', ' ')}")
            .setSubText(session?.let { "Ride ${formatDuration(it.totalDurationMs())}" })
            .setContentIntent(openApp).setOngoing(true).setOnlyAlertOnce(true)
        builder.addAction(Notification.Action.Builder(null, if (paused) "Resume" else "Pause",
            serviceAction(if (paused) ACTION_RESUME else ACTION_PAUSE, 1)).build())
        builder.addAction(Notification.Action.Builder(null, "Stop", serviceAction(ACTION_STOP, 2)).build())
        return builder.build()
    }

    private fun serviceAction(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, RideRecordingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun bikeLabel() = when (state.bleState) {
        BleConnectionState.CONNECTED -> "Bike connected"
        BleConnectionState.CONNECTING -> "Bike connecting"
        BleConnectionState.RECONNECTING -> "Waiting for Ducati"
        BleConnectionState.DISCONNECTED -> "Bike disconnected"
    }

    private fun publish(updateNotification: Boolean = false) {
        state = state.copy(session = runtime.session)
        listeners.forEach { it(state) }
        if (updateNotification && foreground) ensureForeground()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ride recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent status and controls for an active ride"
                setSound(null, null); enableVibration(false)
            })
    }
    private fun settings() = getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
    private fun hasBlePermissions() =
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun formatDuration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }

    override fun onDestroy() {
        phoneSensors.stop()
        ble.disconnect()
        listeners.clear()
        super.onDestroy()
    }
}
