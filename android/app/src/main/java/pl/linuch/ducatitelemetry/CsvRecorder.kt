package pl.linuch.ducatitelemetry

import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

class CsvRecorder {
    private val rows = mutableListOf<String>()

    val count: Int
        get() = (rows.size - 1).coerceAtLeast(0)

    fun start() {
        rows.clear()
        rows += "utc (ms),sequence,esp time (ms),engine (rpm),gear,speed (km/h),throttle (%),front brake (%),engine temperature (°C),ambient temperature (°C)"
    }

    fun append(t: Telemetry) {
        if (rows.isEmpty()) start()
        rows += buildString {
            append(t.phoneTimestampMs); append(',')
            append(t.sequence); append(',')
            append(t.espTimeMs); append(',')
            append(t.rpm); append(',')
            append(t.gear); append(',')
            append("%.2f".format(Locale.US, t.speedKmh)); append(',')
            append("%.2f".format(Locale.US, t.throttlePercent)); append(',')
            append("%.2f".format(Locale.US, t.frontBrakePercent)); append(',')
            append(t.engineTempC); append(',')
            append(t.ambientTempC)
        }
    }

    fun writeTo(output: OutputStream) {
        output.writer(StandardCharsets.UTF_8).use { writer ->
            rows.forEach { writer.append(it).append('\n') }
        }
    }

    fun isEmpty(): Boolean = count == 0
}
