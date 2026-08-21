package pl.linuch.ducatitelemetry

import org.junit.Assert.*
import org.junit.Test

class ReconnectControllerTest {
    @Test
    fun unexpectedDisconnectSchedulesImmediateReconnect() {
        val reconnect = ReconnectController()
        reconnect.setEnabled(true)

        assertEquals(0L, reconnect.nextRetry())
        assertTrue(reconnect.retryScheduled)
    }

    @Test
    fun explicitDisconnectCancelsAndPreventsReconnect() {
        val reconnect = ReconnectController()
        reconnect.setEnabled(true)
        reconnect.nextRetry()
        reconnect.setEnabled(false)

        assertFalse(reconnect.retryScheduled)
        assertNull(reconnect.nextRetry())
    }

    @Test
    fun duplicateRetryAndAttemptRequestsAreRejected() {
        val reconnect = ReconnectController()
        reconnect.setEnabled(true)

        assertEquals(0L, reconnect.nextRetry())
        assertNull(reconnect.nextRetry())
        assertTrue(reconnect.beginAttempt())
        assertFalse(reconnect.beginAttempt())
        assertNull(reconnect.nextRetry())
    }

    @Test
    fun failuresUseBoundedBackoffAndConnectedResetsIt() {
        val reconnect = ReconnectController()
        reconnect.setEnabled(true)
        val delays = mutableListOf<Long>()
        delays += reconnect.nextRetry()!!
        repeat(7) {
            reconnect.beginAttempt()
            delays += reconnect.attemptFailed()!!
        }

        assertEquals(listOf(0L, 2_000L, 5_000L, 10_000L, 15_000L, 30_000L, 30_000L, 30_000L), delays)
        reconnect.connected()
        assertEquals(0L, reconnect.nextRetry())
    }
}
