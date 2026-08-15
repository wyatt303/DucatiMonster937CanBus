package pl.linuch.ducatitelemetry

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Telemetry(
    val sequence: Long,
    val espTimeMs: Long,
    val rpm: Int,
    val gear: Int,
    val speedKmh: Double,
    val throttlePercent: Double,
    val frontBrakePercent: Double,
    val engineTempC: Int,
    val ambientTempC: Int,
    val phoneTimestampMs: Long
)

object TelemetryDecoder {
    const val PACKET_SIZE = 19

    fun decode(data: ByteArray, phoneTimestampMs: Long): Telemetry? {
        if (data.size != PACKET_SIZE) return null

        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val sequence = b.int.toLong() and 0xffffffffL
        val espTimeMs = b.int.toLong() and 0xffffffffL
        val rpm = b.short.toInt() and 0xffff
        val gear = b.get().toInt()
        val speed = (b.short.toInt() and 0xffff) / 100.0
        val throttle = (b.short.toInt() and 0xffff) / 100.0
        val brake = (b.short.toInt() and 0xffff) / 100.0
        val engineTemp = b.get().toInt()
        val ambientTemp = b.get().toInt()

        return Telemetry(
            sequence, espTimeMs, rpm, gear, speed, throttle, brake,
            engineTemp, ambientTemp, phoneTimestampMs
        )
    }
}
