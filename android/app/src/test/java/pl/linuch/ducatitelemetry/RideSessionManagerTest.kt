package pl.linuch.ducatitelemetry

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class RideSessionManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class TestClock(var now: Long = 1_000) {
        fun read() = now
    }

    private fun manager(
        root: File,
        clock: TestClock,
        retention: () -> Int? = { 10 }
    ) = RideSessionManager(root, retention, clock::read)

    private fun telemetry(timestamp: Long, sequence: Long = 1) = Telemetry(
        sequence = sequence,
        espTimeMs = timestamp,
        rpm = 3_000,
        gear = 2,
        speedKmh = 42.25,
        throttlePercent = 12.5,
        frontBrakePercent = 0.0,
        engineTempC = 80,
        ambientTempC = 20,
        phoneTimestampMs = timestamp
    )

    @Test
    fun startCreatesRecordingSessionAndCsvImmediately() {
        val root = temporaryFolder.newFolder()
        val session = manager(root, TestClock()).startSession()

        assertEquals(RideSessionState.RECORDING, session.state)
        assertTrue(File(root, session.filePath).readText().startsWith(TelemetryCsv.HEADER))
    }

    @Test
    fun recordingCanPauseAndResumeSameSession() {
        val clock = TestClock()
        val manager = manager(temporaryFolder.newFolder(), clock)
        val id = manager.startSession().id
        clock.now = 2_000
        assertEquals(RideSessionState.PAUSED, manager.pauseSession().state)
        clock.now = 3_000
        val resumed = manager.resumeSession()

        assertEquals(id, resumed.id)
        assertEquals(RideSessionState.RECORDING, resumed.state)
    }

    @Test
    fun recordingCanStopAsCompleted() {
        val clock = TestClock()
        val manager = manager(temporaryFolder.newFolder(), clock)
        manager.startSession()
        manager.appendTelemetry(telemetry(clock.now))
        clock.now = 2_000

        assertEquals(RideSessionState.COMPLETED, manager.stopSession()!!.state)
    }

    @Test
    fun pausedCanStopAsCompleted() {
        val clock = TestClock()
        val manager = manager(temporaryFolder.newFolder(), clock)
        manager.startSession()
        manager.appendTelemetry(telemetry(clock.now))
        manager.pauseSession()

        assertEquals(RideSessionState.COMPLETED, manager.stopSession()!!.state)
    }

    @Test
    fun recordingIsRecoveredAfterRestart() {
        val root = temporaryFolder.newFolder()
        val clock = TestClock()
        manager(root, clock).apply {
            startSession()
            clock.now = 3_000
            appendTelemetry(telemetry(clock.now))
        }

        val recovered = manager(root, clock).recoverSessions().single()
        assertEquals(RideSessionState.RECOVERED, recovered.state)
        assertTrue(recovered.recovered)
    }

    @Test
    fun pausedRemainsResumableAfterRestart() {
        val root = temporaryFolder.newFolder()
        val clock = TestClock()
        val originalId = manager(root, clock).run {
            val id = startSession().id
            appendTelemetry(telemetry(clock.now))
            pauseSession()
            id
        }

        val restarted = manager(root, clock)
        assertTrue(restarted.recoverSessions().isEmpty())
        assertEquals(RideSessionState.PAUSED, restarted.activeSession()!!.state)
        assertFalse(restarted.activeSession()!!.recovered)
        clock.now = 5_000
        assertEquals(originalId, restarted.resumeSession().id)
        restarted.appendTelemetry(telemetry(clock.now, sequence = 2))
        assertEquals(2, restarted.activeSession()!!.sampleCount)
    }

    @Test
    fun pausedDisconnectAndReconnectKeepSameSessionWithoutAppending() {
        val root = temporaryFolder.newFolder()
        val clock = TestClock()
        val manager = manager(root, clock)
        val original = manager.startSession()
        manager.appendTelemetry(telemetry(clock.now))
        manager.pauseSession()

        clock.now = 10_000 // BLE is absent; the session manager receives no telemetry.
        assertEquals(original.id, manager.activeSession()!!.id)
        assertEquals(RideSessionState.PAUSED, manager.activeSession()!!.state)
        assertEquals(1, manager.activeSession()!!.sampleCount)

        clock.now = 12_000 // BLE reconnects, but the ride remains paused.
        manager.appendTelemetry(telemetry(clock.now, sequence = 2))
        assertEquals(RideSessionState.PAUSED, manager.activeSession()!!.state)
        assertEquals(1, manager.activeSession()!!.sampleCount)
    }

    @Test
    fun pausedResumeAfterReconnectContinuesOriginalSession() {
        val clock = TestClock()
        val manager = manager(temporaryFolder.newFolder(), clock)
        val original = manager.startSession()
        manager.appendTelemetry(telemetry(clock.now))
        clock.now = 2_000
        manager.pauseSession()
        clock.now = 20_000

        val resumed = manager.resumeSession()
        manager.appendTelemetry(telemetry(clock.now, sequence = 2))

        assertEquals(original.id, resumed.id)
        assertEquals(2, manager.activeSession()!!.sampleCount)
    }

    @Test
    fun recordingDisconnectAndReconnectContinueSameSessionWithTimestampGap() {
        val root = temporaryFolder.newFolder()
        val clock = TestClock(1_000)
        val manager = manager(root, clock)
        val original = manager.startSession()
        manager.appendTelemetry(telemetry(1_000, sequence = 1))

        clock.now = 10_000 // No callback means no synthetic rows during the BLE gap.
        assertEquals(original.id, manager.activeSession()!!.id)
        assertEquals(RideSessionState.RECORDING, manager.activeSession()!!.state)
        manager.appendTelemetry(telemetry(10_000, sequence = 2))
        val completed = manager.stopSession()!!

        assertEquals(original.id, completed.id)
        val rows = File(root, completed.filePath).readLines()
        assertEquals(3, rows.size)
        assertTrue(rows[1].startsWith("1000,1,"))
        assertTrue(rows[2].startsWith("10000,2,"))
    }

    @Test
    fun pauseDurationContinuesAcrossDisconnectedInterval() {
        val clock = TestClock(1_000)
        val manager = manager(temporaryFolder.newFolder(), clock)
        manager.startSession()
        manager.appendTelemetry(telemetry(clock.now))
        clock.now = 2_000
        manager.pauseSession()
        clock.now = 32_000 // ignition off and BLE disconnected
        val paused = manager.activeSession()!!
        assertEquals(31_000, paused.totalDurationMs(clock.now))
        assertEquals(30_000, paused.pausedDurationMs(clock.now))
        assertEquals(1_000, paused.activeDurationMs(clock.now))
        manager.resumeSession()

        assertEquals(30_000, manager.activeSession()!!.totalPausedDurationMs)
    }

    @Test
    fun samplesWithinFlushIntervalRemainBuffered() {
        val root = temporaryFolder.newFolder()
        val clock = TestClock(1_000)
        val manager = manager(root, clock)
        val session = manager.startSession()
        manager.appendTelemetry(telemetry(1_000, sequence = 1))
        clock.now = 2_999
        manager.appendTelemetry(telemetry(2_999, sequence = 2))

        assertEquals(listOf(TelemetryCsv.HEADER), File(root, session.filePath).readLines())
    }

    @Test
    fun reachingFlushIntervalPersistsAllBufferedSamples() {
        val root = temporaryFolder.newFolder()
        val clock = TestClock(1_000)
        val manager = manager(root, clock)
        val session = manager.startSession()
        manager.appendTelemetry(telemetry(1_000, sequence = 1))
        clock.now = 3_000
        manager.appendTelemetry(telemetry(3_000, sequence = 2))

        val rows = File(root, session.filePath).readLines()
        assertEquals(3, rows.size)
        assertTrue(rows[1].startsWith("1000,1,"))
        assertTrue(rows[2].startsWith("3000,2,"))
    }

    @Test
    fun pauseForcesPendingTelemetryToDisk() {
        val root = temporaryFolder.newFolder()
        val clock = TestClock(1_000)
        val manager = manager(root, clock)
        val session = manager.startSession()
        manager.appendTelemetry(telemetry(clock.now))
        clock.now = 1_500

        manager.pauseSession()

        assertEquals(2, File(root, session.filePath).readLines().size)
    }

    @Test
    fun stopForcesPendingTelemetryToDisk() {
        val root = temporaryFolder.newFolder()
        val clock = TestClock(1_000)
        val manager = manager(root, clock)
        val session = manager.startSession()
        manager.appendTelemetry(telemetry(clock.now))
        clock.now = 1_500

        manager.stopSession()

        assertEquals(2, File(root, session.filePath).readLines().size)
    }

    @Test
    fun periodicFlushPersistsRecoverableMetadata() {
        val root = temporaryFolder.newFolder()
        val clock = TestClock(1_000)
        manager(root, clock).apply {
            startSession()
            appendTelemetry(telemetry(clock.now, sequence = 1))
            clock.now = 3_000
            appendTelemetry(telemetry(clock.now, sequence = 2))
        }

        val recovered = manager(root, clock).recoverSessions().single()
        assertEquals(2, recovered.sampleCount)
        assertEquals(RideSessionState.RECOVERED, recovered.state)
    }

    @Test
    fun pausedAndActiveDurationsAreCalculatedSeparately() {
        val clock = TestClock(1_000)
        val manager = manager(temporaryFolder.newFolder(), clock)
        manager.startSession()
        manager.appendTelemetry(telemetry(clock.now))
        clock.now = 2_000
        manager.pauseSession()
        clock.now = 5_000
        manager.resumeSession()
        clock.now = 9_000
        val session = manager.stopSession()!!

        assertEquals(8_000, session.totalDurationMs())
        assertEquals(3_000, session.pausedDurationMs())
        assertEquals(5_000, session.activeDurationMs())
    }

    @Test
    fun stoppingWhilePausedIncludesFinalPauseDuration() {
        val clock = TestClock(1_000)
        val manager = manager(temporaryFolder.newFolder(), clock)
        manager.startSession()
        manager.appendTelemetry(telemetry(clock.now))
        clock.now = 2_000
        manager.pauseSession()
        clock.now = 6_000
        val session = manager.stopSession()!!

        assertEquals(4_000, session.pausedDurationMs())
        assertEquals(1_000, session.activeDurationMs())
    }

    @Test
    fun retentionLimitFiveKeepsFiveNewest() = assertRetention(5, 7)

    @Test
    fun retentionLimitTenKeepsTenNewest() = assertRetention(10, 12)

    @Test
    fun unlimitedRetentionKeepsAllSessions() = assertRetention(null, 12)

    @Test
    fun retentionDeletesOldestFirst() {
        val root = temporaryFolder.newFolder()
        val clock = TestClock()
        var limit: Int? = null
        val manager = manager(root, clock) { limit }
        val ids = (1..4).map {
            val id = completeOne(manager, clock)
            clock.now += 1_000
            id
        }
        limit = 2
        manager.enforceRetentionLimit()

        assertEquals(ids.takeLast(2).toSet(), manager.listSessions().map { it.id }.toSet())
    }

    @Test
    fun recoveredSessionsCountTowardRetention() {
        val root = temporaryFolder.newFolder()
        val clock = TestClock()
        val manager = manager(root, clock) { 2 }
        completeOne(manager, clock)
        clock.now += 1_000
        completeOne(manager, clock)
        clock.now += 1_000
        manager.startSession()
        clock.now += 2_000
        manager.appendTelemetry(telemetry(clock.now))
        manager(root, clock) { 2 }.recoverSessions()

        val saved = manager(root, clock) { 2 }.listSessions()
        assertEquals(2, saved.size)
        assertTrue(saved.any { it.state == RideSessionState.RECOVERED })
    }

    @Test
    fun activeRecordingIsNeverDeletedByRetention() {
        val root = temporaryFolder.newFolder()
        val clock = TestClock()
        val manager = manager(root, clock) { 0 }
        val active = manager.startSession()
        manager.enforceRetentionLimit()

        assertEquals(active.id, manager.activeSession()!!.id)
        assertTrue(File(root, active.filePath).exists())
    }

    @Test
    fun pausedSessionIsNeverDeletedByRetention() {
        val root = temporaryFolder.newFolder()
        val clock = TestClock()
        val manager = manager(root, clock) { 0 }
        val paused = manager.startSession().also { manager.pauseSession() }
        manager.enforceRetentionLimit()

        assertEquals(paused.id, manager.activeSession()!!.id)
        assertEquals(RideSessionState.PAUSED, manager.activeSession()!!.state)
    }

    @Test
    fun emptySessionIsRemovedOnStop() {
        val root = temporaryFolder.newFolder()
        val manager = manager(root, TestClock())
        val session = manager.startSession()

        assertNull(manager.stopSession())
        assertFalse(File(root, session.filePath).exists())
        assertTrue(manager.listSessions().isEmpty())
    }

    @Test
    fun completedSessionRemainsExportableWithOriginalCsvFormat() {
        val root = temporaryFolder.newFolder()
        val clock = TestClock()
        val manager = manager(root, clock)
        val session = manager.startSession()
        manager.appendTelemetry(telemetry(clock.now))
        manager.stopSession()
        val output = ByteArrayOutputStream()

        CsvExporter().export(manager.sessionFile(session.id)!!, output)
        val lines = output.toString(Charsets.UTF_8.name()).lines()
        assertEquals(TelemetryCsv.HEADER, lines[0])
        assertEquals("1000,1,1000,3000,2,42.25,12.50,0.00,80,20", lines[1])
    }

    private fun assertRetention(limit: Int?, created: Int) {
        val clock = TestClock()
        val manager = manager(temporaryFolder.newFolder(), clock) { limit }
        repeat(created) {
            completeOne(manager, clock)
            clock.now += 1_000
        }
        assertEquals(limit ?: created, manager.listSessions().size)
    }

    private fun completeOne(manager: RideSessionManager, clock: TestClock): String {
        val session = manager.startSession()
        manager.appendTelemetry(telemetry(clock.now))
        clock.now += 100
        manager.stopSession()
        return session.id
    }
}
