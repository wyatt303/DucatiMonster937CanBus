package pl.linuch.ducatitelemetry

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

enum class RideSessionState {
    RECORDING,
    PAUSED,
    COMPLETED,
    RECOVERED
}

data class RideSession(
    val id: String,
    val startTime: Long,
    var endTime: Long,
    var sampleCount: Long,
    var state: RideSessionState,
    val filePath: String,
    var recovered: Boolean,
    var totalPausedDurationMs: Long,
    var pausedAt: Long? = null
) {
    fun totalDurationMs(now: Long = System.currentTimeMillis()): Long {
        val effectiveEnd = if (state == RideSessionState.RECORDING || state == RideSessionState.PAUSED) {
            now
        } else {
            endTime
        }
        return (effectiveEnd - startTime).coerceAtLeast(0)
    }

    fun pausedDurationMs(now: Long = System.currentTimeMillis()): Long {
        val currentPause = if (state == RideSessionState.PAUSED) {
            (now - (pausedAt ?: now)).coerceAtLeast(0)
        } else {
            0
        }
        return totalPausedDurationMs + currentPause
    }

    fun activeDurationMs(now: Long = System.currentTimeMillis()): Long =
        (totalDurationMs(now) - pausedDurationMs(now)).coerceAtLeast(0)
}

object TelemetryCsv {
    const val HEADER = "utc (ms),sequence,esp time (ms),engine (rpm),gear,speed (km/h),throttle (%),front brake (%),engine temperature (°C),ambient temperature (°C),latitude,longitude,altitude (m),gps speed (km/h),bearing (deg),gps accuracy (m),lean angle (deg),roll rate (deg/s),imu accuracy"

    fun row(t: Telemetry, sensors: PhoneSensorSnapshot = PhoneSensorSnapshot()): String = buildString {
        append(t.phoneTimestampMs); append(',')
        append(t.sequence); append(',')
        append(t.espTimeMs); append(',')
        append(t.rpm); append(',')
        append(t.gear); append(',')
        append(java.lang.String.format(java.util.Locale.US, "%.2f", t.speedKmh)); append(',')
        append(java.lang.String.format(java.util.Locale.US, "%.2f", t.throttlePercent)); append(',')
        append(java.lang.String.format(java.util.Locale.US, "%.2f", t.frontBrakePercent)); append(',')
        append(t.engineTempC); append(',')
        append(t.ambientTempC); append(',')
        appendNumber(sensors.gnss?.latitude, 7); append(',')
        appendNumber(sensors.gnss?.longitude, 7); append(',')
        appendNumber(sensors.gnss?.altitudeM, 2); append(',')
        appendNumber(sensors.gnss?.gpsSpeedKmh, 2); append(',')
        appendNumber(sensors.gnss?.bearingDeg, 2); append(',')
        appendNumber(sensors.gnss?.accuracyM, 2); append(',')
        appendNumber(sensors.imu?.leanAngleDeg, 2); append(',')
        appendNumber(sensors.imu?.rollRateDps, 2); append(',')
        sensors.imu?.accuracy?.let(::append)
    }

    private fun StringBuilder.appendNumber(value: Double?, decimals: Int) {
        if (value != null && value.isFinite()) append(java.lang.String.format(java.util.Locale.US, "%.${decimals}f", value))
    }
}

class CsvExporter {
    fun export(source: File, output: OutputStream) {
        source.inputStream().use { input ->
            output.use { destination -> input.copyTo(destination) }
        }
    }
}

class RideSessionManager(
    private val root: File,
    private val retentionLimit: () -> Int?,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        private const val FLUSH_INTERVAL_MS = 2_000L
    }

    private var active: RideSession? = null
    private var writer: BufferedWriter? = null
    private var gpxDataWriter: BufferedWriter? = null
    private var lastGnssTimestampNanos: Long? = null
    private var lastFlushAt = 0L

    init {
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("Cannot create ride storage")
        }
    }

    @Synchronized
    fun activeSession(): RideSession? = active?.copy()

    @Synchronized
    fun startSession(): RideSession {
        check(active == null) { "A ride session is already active" }
        val now = clock()
        val id = "$now-${UUID.randomUUID()}"
        val session = RideSession(
            id = id,
            startTime = now,
            endTime = now,
            sampleCount = 0,
            state = RideSessionState.RECORDING,
            filePath = "$id.csv",
            recovered = false,
            totalPausedDurationMs = 0
        )
        val csv = csvFile(session)

        try {
            writer = csv.outputStream().bufferedWriter(StandardCharsets.UTF_8).also {
                it.append(TelemetryCsv.HEADER).append('\n')
                it.flush()
            }
            gpxDataWriter = gpxDataFile(session.id).outputStream().bufferedWriter(StandardCharsets.UTF_8).also {
                it.append(GpxPointData.HEADER).append('\n')
                it.flush()
            }
            lastGnssTimestampNanos = null
            lastFlushAt = now
            active = session
            writeMetadata(session)
            return session.copy()
        } catch (error: Exception) {
            writer?.closeQuietly()
            gpxDataWriter?.closeQuietly()
            writer = null
            gpxDataWriter = null
            active = null
            csv.delete()
            gpxDataFile(id).delete()
            metadataFile(id).delete()
            throw IOException("Cannot start local recording", error)
        }
    }

    @Synchronized
    fun pauseSession(): RideSession {
        val session = requireState(RideSessionState.RECORDING)
        val now = clock()
        flushWriter(now)
        session.state = RideSessionState.PAUSED
        session.pausedAt = now
        session.endTime = now
        writeMetadata(session)
        return session.copy()
    }

    @Synchronized
    fun resumeSession(): RideSession {
        val session = requireState(RideSessionState.PAUSED)
        val now = clock()
        session.totalPausedDurationMs += (now - (session.pausedAt ?: now)).coerceAtLeast(0)
        session.pausedAt = null
        session.state = RideSessionState.RECORDING
        session.endTime = now
        writeMetadata(session)
        lastFlushAt = now
        return session.copy()
    }

    @Synchronized
    fun stopSession(): RideSession? {
        val session = active ?: return null
        val now = clock()
        flushWriter(now)
        writer?.close()
        writer = null
        gpxDataWriter?.close()
        gpxDataWriter = null
        lastGnssTimestampNanos = null
        if (session.state == RideSessionState.PAUSED) {
            session.totalPausedDurationMs += (now - (session.pausedAt ?: now)).coerceAtLeast(0)
            session.pausedAt = null
        }
        session.endTime = now
        active = null

        if (session.sampleCount == 0L) {
            csvFile(session).delete()
            gpxDataFile(session.id).delete()
            metadataFile(session.id).delete()
            return null
        }

        session.recovered = false
        session.state = RideSessionState.COMPLETED
        writeMetadata(session)
        enforceRetentionLimit()
        return session.copy()
    }

    @Synchronized
    fun appendTelemetry(telemetry: Telemetry, sensors: PhoneSensorSnapshot = PhoneSensorSnapshot()): String? {
        val session = active ?: return null
        if (session.state != RideSessionState.RECORDING) return null

        return try {
            writer?.append(TelemetryCsv.row(telemetry, sensors))?.append('\n')
                ?: throw IOException("Ride file is closed")
            GpxPointData.from(telemetry, sensors)?.takeIf { it.gnssTimestampNanos != lastGnssTimestampNanos }?.let {
                gpxDataWriter?.append(GpxPointData.row(it))?.append('\n')
                    ?: throw IOException("Ride GPX data file is closed")
                lastGnssTimestampNanos = it.gnssTimestampNanos
            }
            session.sampleCount++
            session.endTime = telemetry.phoneTimestampMs.coerceAtLeast(session.startTime)
            val now = clock()
            if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
                flushWriter(now)
                writeMetadata(session)
            }
            null
        } catch (error: Exception) {
            writer?.closeQuietly()
            gpxDataWriter?.closeQuietly()
            writer = null
            gpxDataWriter = null
            active = null
            session.state = RideSessionState.RECOVERED
            session.recovered = true
            session.endTime = clock().coerceAtLeast(session.endTime)
            runCatching { writeMetadata(session) }
            "Local recording stopped: ${error.message ?: "storage write failed"}"
        }
    }

    @Synchronized
    fun recoverSessions(): List<RideSession> {
        active?.let { session ->
            runCatching {
                val now = clock()
                flushWriter(now)
                writeMetadata(session)
            }
        }
        writer?.closeQuietly()
        gpxDataWriter?.closeQuietly()
        writer = null
        gpxDataWriter = null
        lastGnssTimestampNanos = null
        active = null
        val recovered = mutableListOf<RideSession>()

        val stored = loadSessions()
        val pausedToRestore = stored
            .filter { it.state == RideSessionState.PAUSED && it.sampleCount > 0 }
            .maxWithOrNull(compareBy<RideSession> { it.startTime }.thenBy { it.id })

        stored.forEach { session ->
            if (session.id == pausedToRestore?.id) {
                try {
                    if (!csvFile(session).isFile) throw IOException("Ride CSV is missing")
                    writer = FileOutputStream(csvFile(session), true)
                        .bufferedWriter(StandardCharsets.UTF_8)
                    val data = gpxDataFile(session.id)
                    if (!data.exists()) data.writeText(GpxPointData.HEADER + "\n", StandardCharsets.UTF_8)
                    gpxDataWriter = FileOutputStream(data, true).bufferedWriter(StandardCharsets.UTF_8)
                    lastGnssTimestampNanos = data.useLines { lines -> lines.drop(1).lastOrNull()?.let(GpxPointData::parse)?.gnssTimestampNanos }
                    lastFlushAt = clock()
                    active = session
                } catch (_: Exception) {
                    session.state = RideSessionState.RECOVERED
                    session.recovered = true
                    session.pausedAt = null
                    writer?.closeQuietly()
                    gpxDataWriter?.closeQuietly()
                    writer = null
                    gpxDataWriter = null
                    runCatching { writeMetadata(session) }
                    recovered += session.copy()
                }
            } else if (session.state == RideSessionState.RECORDING ||
                session.state == RideSessionState.PAUSED) {
                session.state = RideSessionState.RECOVERED
                session.recovered = true
                session.pausedAt = null
                if (session.sampleCount == 0L) {
                    csvFile(session).delete()
                    gpxDataFile(session.id).delete()
                    metadataFile(session.id).delete()
                } else {
                    runCatching { writeMetadata(session) }
                    recovered += session.copy()
                }
            }
        }
        enforceRetentionLimit()
        return recovered
    }

    @Synchronized
    fun listSessions(): List<RideSession> = loadSessions()
        .filter { it.state == RideSessionState.COMPLETED || it.state == RideSessionState.RECOVERED }
        .sortedWith(compareByDescending<RideSession> { it.startTime }.thenByDescending { it.id })
        .map { it.copy() }

    @Synchronized
    fun deleteSession(id: String): Boolean {
        if (active?.id == id) return false
        val session = loadSession(metadataFile(id)) ?: return false
        if (session.state == RideSessionState.RECORDING || session.state == RideSessionState.PAUSED) {
            return false
        }
        val csvDeleted = !csvFile(session).exists() || csvFile(session).delete()
        val metadataDeleted = !metadataFile(id).exists() || metadataFile(id).delete()
        val gpxDataDeleted = !gpxDataFile(id).exists() || gpxDataFile(id).delete()
        return csvDeleted && metadataDeleted && gpxDataDeleted
    }

    @Synchronized
    fun sessionFile(id: String): File? {
        val session = loadSession(metadataFile(id)) ?: return null
        if (session.state != RideSessionState.COMPLETED && session.state != RideSessionState.RECOVERED) {
            return null
        }
        return csvFile(session).takeIf { it.isFile }
    }

    @Synchronized
    fun sessionGpxDataFile(id: String): File? = sessionFile(id)?.let { gpxDataFile(id).takeIf(File::isFile) }

    @Synchronized
    fun enforceRetentionLimit() {
        val limit = retentionLimit() ?: return
        val saved = loadSessions()
            .filter { it.state == RideSessionState.COMPLETED || it.state == RideSessionState.RECOVERED }
            .sortedWith(compareBy<RideSession> { it.startTime }.thenBy { it.id })
        saved.take((saved.size - limit).coerceAtLeast(0)).forEach { deleteSession(it.id) }
    }

    private fun requireState(expected: RideSessionState): RideSession {
        val session = active ?: error("No active ride session")
        check(session.state == expected) { "Ride session is ${session.state}" }
        return session
    }

    private fun loadSessions(): List<RideSession> =
        root.listFiles { file -> file.extension == "properties" }
            ?.mapNotNull(::loadSession)
            .orEmpty()

    private fun loadSession(file: File): RideSession? {
        return try {
            val values = Properties().apply { file.inputStream().use(::load) }
            RideSession(
                id = values.getProperty("id") ?: return null,
                startTime = values.getProperty("startTime").toLong(),
                endTime = values.getProperty("endTime").toLong(),
                sampleCount = values.getProperty("sampleCount").toLong(),
                state = RideSessionState.valueOf(values.getProperty("state")),
                filePath = values.getProperty("filePath") ?: return null,
                recovered = values.getProperty("recovered").toBoolean(),
                totalPausedDurationMs = values.getProperty("totalPausedDurationMs").toLong(),
                pausedAt = values.getProperty("pausedAt")?.toLongOrNull()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMetadata(session: RideSession) {
        val values = Properties().apply {
            setProperty("id", session.id)
            setProperty("startTime", session.startTime.toString())
            setProperty("endTime", session.endTime.toString())
            setProperty("sampleCount", session.sampleCount.toString())
            setProperty("state", session.state.name)
            setProperty("filePath", session.filePath)
            setProperty("recovered", session.recovered.toString())
            setProperty("totalPausedDurationMs", session.totalPausedDurationMs.toString())
            session.pausedAt?.let { setProperty("pausedAt", it.toString()) }
        }
        val target = metadataFile(session.id)
        val temporary = File(root, "${session.id}.properties.tmp")
        FileOutputStream(temporary).use { values.store(it, null) }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun metadataFile(id: String) = File(root, "$id.properties")
    private fun gpxDataFile(id: String) = File(root, "$id.gpxdata")
    private fun csvFile(session: RideSession) = File(root, session.filePath)
    private fun flushWriter(now: Long) {
        writer?.flush() ?: throw IOException("Ride file is closed")
        gpxDataWriter?.flush() ?: throw IOException("Ride GPX data file is closed")
        lastFlushAt = now
    }
    private fun BufferedWriter.closeQuietly() = runCatching { close() }.getOrNull()
}
