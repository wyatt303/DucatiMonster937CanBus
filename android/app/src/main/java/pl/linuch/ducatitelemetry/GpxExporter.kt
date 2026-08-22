package pl.linuch.ducatitelemetry

import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class NoGpxDataException : IllegalStateException("This ride has no GPS data and cannot be exported as GPX.")

data class GpxPoint(
    val gnssTimestampNanos: Long?, val utcTimestampMs: Long,
    val latitude: Double, val longitude: Double, val altitudeM: Double?,
    val rpm: Int?, val canSpeedKmh: Double?, val gpsSpeedKmh: Double?, val gear: Int?,
    val throttlePct: Double?, val frontBrakePct: Double?, val engineTempC: Int?,
    val ambientTempC: Int?, val leanAngleDeg: Double?, val rollRateDps: Double?,
    val gpsAccuracyM: Double?
)

object GpxPointData {
    const val HEADER = "gnss_timestamp_nanos,utc_timestamp_ms,latitude,longitude,altitude_m,rpm,can_speed_kmh,gps_speed_kmh,gear,throttle_pct,front_brake_pct,engine_temp_c,ambient_temp_c,lean_angle_deg,roll_rate_dps,gps_accuracy_m"

    fun from(telemetry: Telemetry, sensors: PhoneSensorSnapshot): GpxPoint? {
        val gps = sensors.gnss ?: return null
        val canNanos = telemetry.phoneTimestampNanos.takeIf { it > 0 } ?: gps.timestampNanos
        val utc = telemetry.phoneTimestampMs - ((canNanos - gps.timestampNanos) / 1_000_000L)
        return GpxPoint(gps.timestampNanos, utc, gps.latitude, gps.longitude, gps.altitudeM,
            telemetry.rpm, telemetry.speedKmh, gps.gpsSpeedKmh, telemetry.gear,
            telemetry.throttlePercent, telemetry.frontBrakePercent, telemetry.engineTempC,
            telemetry.ambientTempC, sensors.imu?.leanAngleDeg, sensors.imu?.rollRateDps, gps.accuracyM)
    }

    fun row(p: GpxPoint) = listOf(
        p.gnssTimestampNanos, p.utcTimestampMs, number(p.latitude, 7), number(p.longitude, 7), number(p.altitudeM, 2),
        p.rpm, number(p.canSpeedKmh, 2), number(p.gpsSpeedKmh, 2), p.gear,
        number(p.throttlePct, 2), number(p.frontBrakePct, 2), p.engineTempC, p.ambientTempC,
        number(p.leanAngleDeg, 2), number(p.rollRateDps, 2), number(p.gpsAccuracyM, 2)
    ).joinToString(",") { it?.toString().orEmpty() }

    fun parse(line: String): GpxPoint? {
        val f = line.split(',', ignoreCase = false, limit = 16)
        if (f.size < 16) return null
        val lat = f[2].toDoubleOrNull() ?: return null
        val lon = f[3].toDoubleOrNull() ?: return null
        return GpxPoint(f[0].toLongOrNull(), f[1].toLongOrNull() ?: return null, lat, lon,
            f[4].toDoubleOrNull(), f[5].toIntOrNull(), f[6].toDoubleOrNull(), f[7].toDoubleOrNull(),
            f[8].toIntOrNull(), f[9].toDoubleOrNull(), f[10].toDoubleOrNull(), f[11].toIntOrNull(),
            f[12].toIntOrNull(), f[13].toDoubleOrNull(), f[14].toDoubleOrNull(), f[15].toDoubleOrNull())
    }

    private fun number(value: Double?, decimals: Int): String? = value?.takeIf(Double::isFinite)
        ?.let { String.format(Locale.US, "%.${decimals}f", it) }
}

class GpxExporter {
    companion object { const val EXTENSION_NAMESPACE = "https://github.com/wyatt303/DucatiMonster937CanBus/gpx/1" }
    private val iso = DateTimeFormatter.ISO_INSTANT
    private val title = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

    fun export(csv: File, gpxData: File?, session: RideSession, output: OutputStream): Int {
        val points = loadPoints(csv, gpxData)
        if (points.isEmpty()) throw NoGpxDataException()
        val xml = buildXml(points, session)
        output.use { it.write(xml.toByteArray(StandardCharsets.UTF_8)) }
        return points.size
    }

    fun loadPoints(csv: File, gpxData: File?): List<GpxPoint> {
        if (gpxData?.isFile == true) {
            val points = gpxData.useLines { lines -> lines.drop(1).mapNotNull(GpxPointData::parse).toList() }
            if (points.isNotEmpty()) {
                val precise = points.distinctBy { it.gnssTimestampNanos }
                val identities = precise.map(::identity).toSet()
                return (loadLegacyCsv(csv).filter { identity(it) !in identities } + precise).sortedBy { it.utcTimestampMs }
            }
        }
        return loadLegacyCsv(csv)
    }

    private fun identity(point: GpxPoint) = listOf(point.latitude, point.longitude, point.altitudeM,
        point.gpsSpeedKmh, point.gpsAccuracyM)

    private fun loadLegacyCsv(csv: File): List<GpxPoint> = csv.useLines { lines ->
        lines.drop(1).mapNotNull { line ->
            val f = line.split(',')
            if (f.size < 19) return@mapNotNull null
            val lat = f[10].toDoubleOrNull() ?: return@mapNotNull null
            val lon = f[11].toDoubleOrNull() ?: return@mapNotNull null
            GpxPoint(null, f[0].toLongOrNull() ?: return@mapNotNull null, lat, lon, f[12].toDoubleOrNull(),
                f[3].toIntOrNull(), f[5].toDoubleOrNull(), f[13].toDoubleOrNull(), f[4].toIntOrNull(),
                f[6].toDoubleOrNull(), f[7].toDoubleOrNull(), f[8].toIntOrNull(), f[9].toIntOrNull(),
                f[16].toDoubleOrNull(), f[17].toDoubleOrNull(), f[15].toDoubleOrNull())
        }.distinctBy { listOf(it.latitude, it.longitude, it.altitudeM, it.gpsSpeedKmh, it.gpsAccuracyM) }.toList()
    }

    private fun buildXml(points: List<GpxPoint>, session: RideSession): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Ducati Telemetry\" xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:ducati=\"")
        append(EXTENSION_NAMESPACE).append("\">\n")
        val name = "Ducati Ride - ${title.format(Instant.ofEpochMilli(session.startTime))} UTC"
        append("  <metadata><name>").append(xml(name)).append("</name><time>").append(time(session.startTime)).append("</time></metadata>\n")
        append("  <trk><name>").append(xml(name)).append("</name><trkseg>\n")
        points.forEach { p ->
            append("    <trkpt lat=\"").append(number(p.latitude, 7)).append("\" lon=\"").append(number(p.longitude, 7)).append("\">\n")
            p.altitudeM?.takeIf(Double::isFinite)?.let { append("      <ele>").append(number(it, 2)).append("</ele>\n") }
            append("      <time>").append(time(p.utcTimestampMs)).append("</time>\n")
            val extensions = listOf(
                "rpm" to p.rpm, "can_speed_kmh" to p.canSpeedKmh, "gps_speed_kmh" to p.gpsSpeedKmh,
                "gear" to p.gear, "throttle_pct" to p.throttlePct, "front_brake_pct" to p.frontBrakePct,
                "engine_temp_c" to p.engineTempC, "ambient_temp_c" to p.ambientTempC,
                "lean_angle_deg" to p.leanAngleDeg, "roll_rate_dps" to p.rollRateDps,
                "gps_accuracy_m" to p.gpsAccuracyM).filter { (_, value) -> value != null && (value !is Double || value.isFinite()) }
            if (extensions.isNotEmpty()) {
                append("      <extensions>\n")
                extensions.forEach { (key, value) -> append("        <ducati:").append(key).append('>').append(value).append("</ducati:").append(key).append(">\n") }
                append("      </extensions>\n")
            }
            append("    </trkpt>\n")
        }
        append("  </trkseg></trk>\n</gpx>\n")
    }

    private fun time(ms: Long) = iso.format(Instant.ofEpochMilli(ms))
    private fun number(value: Double, decimals: Int) = String.format(Locale.US, "%.${decimals}f", value)
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
