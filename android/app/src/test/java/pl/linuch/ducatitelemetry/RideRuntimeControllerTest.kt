package pl.linuch.ducatitelemetry

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RideRuntimeControllerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private var now = 1_000L
    private fun controller(): Pair<RideRuntimeController, RideSessionManager> {
        val manager = RideSessionManager(temporaryFolder.newFolder(), { 10 }, { now })
        return RideRuntimeController(manager) to manager
    }
    private fun telemetry(sequence: Long = 1) = Telemetry(sequence, now, 3_000, 2, 42.0, 10.0, 0.0, 80, 20, now)

    @Test fun startActivatesRuntimeWithoutCreatingDuplicates() {
        val (runtime, _) = controller()
        val first = runtime.start()
        assertTrue(runtime.serviceRequired)
        assertEquals(first.id, runtime.start().id)
    }

    @Test fun runtimeRemainsRequiredWhilePausedAndNotificationCommandsUseSameRide() {
        val (runtime, manager) = controller()
        val id = runtime.start().id
        manager.appendTelemetry(telemetry())
        assertEquals(RideSessionState.PAUSED, runtime.pause()!!.state)
        assertTrue(runtime.serviceRequired)
        assertEquals(id, runtime.resume()!!.id)
        assertEquals(id, runtime.stop()!!.id)
        assertFalse(runtime.serviceRequired)
    }

    @Test fun disconnectAndReconnectDoNotStopOrReplaceRide() {
        val (runtime, _) = controller()
        val id = runtime.start().id
        assertEquals(id, runtime.onBleDisconnected()!!.id)
        assertTrue(runtime.serviceRequired)
        assertEquals(id, runtime.onBleReconnected()!!.id)
    }

    @Test fun attachingAgainObservesExistingSession() {
        val (runtime, _) = controller()
        val id = runtime.start().id
        assertEquals(id, runtime.session!!.id)
        assertEquals(id, runtime.start().id)
    }

    @Test fun persistedPausedRideRestoresWithoutDuplication() {
        val root = temporaryFolder.newFolder()
        val first = RideSessionManager(root, { 10 }, { now })
        val id = first.startSession().id
        first.appendTelemetry(telemetry())
        first.pauseSession()
        val restarted = RideSessionManager(root, { 10 }, { now })
        assertTrue(restarted.recoverSessions().isEmpty())
        val runtime = RideRuntimeController(restarted)
        assertEquals(id, runtime.start().id)
        assertEquals(id, runtime.resume()!!.id)
    }
}
