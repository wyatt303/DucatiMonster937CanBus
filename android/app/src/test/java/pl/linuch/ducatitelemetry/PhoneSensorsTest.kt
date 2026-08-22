package pl.linuch.ducatitelemetry

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PhoneSensorsTest {
    private class MemoryStore(var value: Quaternion? = null) : LeanCalibrationStore {
        override fun load() = value
        override fun save(value: Quaternion) { this.value = value }
        override fun reset() { value = null }
    }

    private fun roll(degrees: Double): Quaternion {
        val half = Math.toRadians(degrees) / 2.0
        return Quaternion(cos(half), 0.0, sin(half), 0.0)
    }

    @Test fun calibrationMakesCurrentOrientationZero() {
        val calibration = LeanCalibration(MemoryStore())
        calibration.calibrate(roll(27.0))
        assertEquals(0.0, calibration.lean(roll(27.0))!!, 0.0001)
    }

    @Test fun leftIsNegativeAndRightIsPositive() {
        val calibration = LeanCalibration(MemoryStore()).apply { calibrate(roll(10.0)) }
        assertEquals(-30.0, calibration.lean(roll(-20.0))!!, 0.0001)
        assertEquals(35.0, calibration.lean(roll(45.0))!!, 0.0001)
    }

    @Test fun calibrationPersistsReloadsAndResets() {
        val store = MemoryStore()
        LeanCalibration(store).calibrate(roll(12.0))
        val reloaded = LeanCalibration(store)
        assertTrue(reloaded.calibrated)
        assertEquals(0.0, reloaded.lean(roll(12.0))!!, 0.0001)
        reloaded.reset()
        assertFalse(LeanCalibration(store).calibrated)
    }

    @Test fun staleAndFutureSamplesAreNotJoined() {
        val current = 10_000_000_000L
        val snapshot = PhoneSensorSnapshot(
            GnssSample(0.0, 0.0, null, null, null, null, current - PhoneSensorManager.GNSS_FRESH_NANOS - 1),
            ImuSample(12.0, null, null, current + 1)
        ).freshAt(current)
        assertNull(snapshot.gnss)
        assertNull(snapshot.imu)
    }

    @Test fun zeroZeroIsRetainedAsAValidCoordinate() {
        val now = 10_000L
        val sample = PhoneSensorSnapshot(gnss = GnssSample(0.0, 0.0, null, null, null, null, now)).freshAt(now)
        assertEquals(0.0, sample.gnss!!.latitude, 0.0)
        assertEquals(0.0, sample.gnss!!.longitude, 0.0)
    }

    @Test fun telemetryKeepsUtcAndMonotonicReceptionTimesSeparate() {
        val decoded = TelemetryDecoder.decode(ByteArray(TelemetryDecoder.PACKET_SIZE), 1_700_000_000_000L, 9_876_543_210L)!!
        assertEquals(1_700_000_000_000L, decoded.phoneTimestampMs)
        assertEquals(9_876_543_210L, decoded.phoneTimestampNanos)
        assertEquals(0L, decoded.espTimeMs)
    }
}
