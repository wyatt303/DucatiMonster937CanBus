package pl.linuch.ducatitelemetry

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import kotlin.math.atan2
import kotlin.math.sqrt

data class GnssSample(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val gpsSpeedKmh: Double?,
    val bearingDeg: Double?,
    val accuracyM: Double?,
    val timestampNanos: Long
)

data class ImuSample(
    val leanAngleDeg: Double,
    val rollRateDps: Double?,
    val accuracy: Int?,
    val timestampNanos: Long
)

data class PhoneSensorSnapshot(val gnss: GnssSample? = null, val imu: ImuSample? = null) {
    fun freshAt(timestampNanos: Long) = PhoneSensorSnapshot(
        gnss = gnss?.takeIf { timestampNanos >= it.timestampNanos && timestampNanos - it.timestampNanos <= PhoneSensorManager.GNSS_FRESH_NANOS },
        imu = imu?.takeIf { timestampNanos >= it.timestampNanos && timestampNanos - it.timestampNanos <= PhoneSensorManager.IMU_FRESH_NANOS }
    )
}

data class Quaternion(val w: Double, val x: Double, val y: Double, val z: Double) {
    fun normalized(): Quaternion {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n == 0.0) Quaternion(1.0, 0.0, 0.0, 0.0)
        else Quaternion(w / n, x / n, y / n, z / n)
    }
    fun inverse() = Quaternion(w, -x, -y, -z).normalized()
    operator fun times(o: Quaternion) = Quaternion(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w
    ).normalized()
}

/** Roll is the twist about the display-normalized phone Y (motorcycle fore/aft) axis. */
object LeanAngleMath {
    fun leanDegrees(calibration: Quaternion, current: Quaternion): Double {
        val relative = calibration.inverse() * current
        return Math.toDegrees(2.0 * atan2(relative.y, relative.w))
            .let { if (it > 180.0) it - 360.0 else if (it < -180.0) it + 360.0 else it }
    }
}

private fun quaternionFromRotationMatrix(m: FloatArray): Quaternion {
    val trace = m[0] + m[4] + m[8]
    val q = if (trace > 0f) {
        val s = sqrt((trace + 1f).toDouble()) * 2.0
        Quaternion(0.25 * s, (m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s)
    } else if (m[0] > m[4] && m[0] > m[8]) {
        val s = sqrt((1f + m[0] - m[4] - m[8]).toDouble()) * 2.0
        Quaternion((m[7] - m[5]) / s, 0.25 * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s)
    } else if (m[4] > m[8]) {
        val s = sqrt((1f + m[4] - m[0] - m[8]).toDouble()) * 2.0
        Quaternion((m[2] - m[6]) / s, (m[1] + m[3]) / s, 0.25 * s, (m[5] + m[7]) / s)
    } else {
        val s = sqrt((1f + m[8] - m[0] - m[4]).toDouble()) * 2.0
        Quaternion((m[3] - m[1]) / s, (m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25 * s)
    }
    return q.normalized()
}

interface LeanCalibrationStore {
    fun load(): Quaternion?
    fun save(value: Quaternion)
    fun reset()
}

class LeanCalibration(private val store: LeanCalibrationStore) {
    var orientation: Quaternion? = store.load()
        private set
    val calibrated get() = orientation != null
    fun calibrate(current: Quaternion) { orientation = current.normalized(); store.save(orientation!!) }
    fun reset() { orientation = null; store.reset() }
    fun lean(current: Quaternion): Double? = orientation?.let { LeanAngleMath.leanDegrees(it, current) }
}

class PreferenceLeanCalibrationStore(context: Context) : LeanCalibrationStore {
    private val prefs = context.getSharedPreferences("phone_sensors", Context.MODE_PRIVATE)
    override fun load(): Quaternion? = if (!prefs.contains("cal_w")) null else Quaternion(
        prefs.getFloat("cal_w", 1f).toDouble(), prefs.getFloat("cal_x", 0f).toDouble(),
        prefs.getFloat("cal_y", 0f).toDouble(), prefs.getFloat("cal_z", 0f).toDouble()
    ).normalized()
    override fun save(value: Quaternion) {
        prefs.edit().putFloat("cal_w", value.w.toFloat()).putFloat("cal_x", value.x.toFloat())
            .putFloat("cal_y", value.y.toFloat()).putFloat("cal_z", value.z.toFloat()).apply()
    }
    override fun reset() { prefs.edit().remove("cal_w").remove("cal_x").remove("cal_y").remove("cal_z").apply() }
}

enum class GnssStatus { AVAILABLE, WAITING_FOR_FIX, ACTIVE, PERMISSION_DENIED, DISABLED }

class PhoneSensorManager(
    private val activity: Activity,
    private val calibrationStore: LeanCalibrationStore,
    private val onChanged: (PhoneSensorSnapshot, GnssStatus) -> Unit
) : SensorEventListener, LocationListener {
    companion object {
        const val GNSS_FRESH_NANOS = 3_000_000_000L
        const val IMU_FRESH_NANOS = 500_000_000L
        const val LOCATION_INTERVAL_MS = 200L
    }
    private val locations = activity.getSystemService(LocationManager::class.java)
    private val sensors = activity.getSystemService(SensorManager::class.java)
    private val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var gnss: GnssSample? = null
    private var imu: ImuSample? = null
    private var currentOrientation: Quaternion? = null
    private val calibration = LeanCalibration(calibrationStore)
    private var latestRollRate: Double? = null
    private var rotationAccuracy: Int? = null
    var gnssEnabled = true
        private set
    var leanEnabled = true
        private set
    var gnssStatus = GnssStatus.DISABLED
        private set
    val leanAvailable get() = rotation != null
    val calibrated get() = calibration.calibrated

    fun setEnabled(gnssEnabled: Boolean, leanEnabled: Boolean) {
        stop()
        this.gnssEnabled = gnssEnabled
        this.leanEnabled = leanEnabled
        start()
    }

    fun start() {
        startGnss()
        if (leanEnabled && rotation != null) {
            sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME)
            gyro?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        publish()
    }

    fun stop() {
        runCatching { locations.removeUpdates(this) }
        sensors.unregisterListener(this)
    }

    fun calibrate(): Boolean {
        val q = currentOrientation ?: return false
        calibration.calibrate(q)
        return true
    }

    fun resetCalibration() { calibration.reset(); imu = null; publish() }

    fun snapshotAt(timestampNanos: Long) = PhoneSensorSnapshot(gnss, imu).freshAt(timestampNanos)

    @SuppressLint("MissingPermission")
    private fun startGnss() {
        if (!gnssEnabled) { gnssStatus = GnssStatus.DISABLED; return }
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            gnssStatus = GnssStatus.PERMISSION_DENIED; return
        }
        if (!locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            gnssStatus = GnssStatus.AVAILABLE; return
        }
        gnssStatus = GnssStatus.WAITING_FOR_FIX
        locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_INTERVAL_MS, 0f, this)
    }

    override fun onLocationChanged(location: Location) {
        val timestamp = location.elapsedRealtimeNanos.takeIf { it > 0 } ?: SystemClock.elapsedRealtimeNanos()
        gnss = GnssSample(location.latitude, location.longitude,
            location.altitude.takeIf { location.hasAltitude() },
            (location.speed * 3.6).toDouble().takeIf { location.hasSpeed() },
            location.bearing.toDouble().takeIf { location.hasBearing() },
            location.accuracy.toDouble().takeIf { location.hasAccuracy() }, timestamp)
        gnssStatus = GnssStatus.ACTIVE
        publish()
    }
    override fun onProviderEnabled(provider: String) { if (provider == LocationManager.GPS_PROVIDER) startGnss() }
    override fun onProviderDisabled(provider: String) { if (provider == LocationManager.GPS_PROVIDER) { gnssStatus = GnssStatus.AVAILABLE; publish() } }
    @Deprecated("Deprecated by Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val rotation = activity.display?.rotation ?: Surface.ROTATION_0
            val rate = when (rotation) {
                Surface.ROTATION_90 -> -event.values[0]
                Surface.ROTATION_180 -> -event.values[1]
                Surface.ROTATION_270 -> event.values[0]
                else -> event.values[1]
            }
            latestRollRate = Math.toDegrees(rate.toDouble())
            return
        }
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val raw = FloatArray(9)
        val displayAdjusted = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(raw, event.values)
        val axes = when (activity.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(raw, axes.first, axes.second, displayAdjusted)
        val q = quaternionFromRotationMatrix(displayAdjusted)
        currentOrientation = q
        calibration.lean(q)?.let { imu = ImuSample(it, latestRollRate, rotationAccuracy, event.timestamp) }
        publish()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            rotationAccuracy = accuracy
            imu = imu?.copy(accuracy = accuracy)
        }
    }
    private fun publish() = onChanged(PhoneSensorSnapshot(gnss, imu), gnssStatus)
}
