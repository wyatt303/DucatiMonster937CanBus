package pl.linuch.ducatitelemetry

/** Unit-testable lifecycle gate shared by UI and notification commands. */
class RideRuntimeController(private val sessions: RideSessionManager) {
    val session get() = sessions.activeSession()
    val serviceRequired get() = session?.state == RideSessionState.RECORDING || session?.state == RideSessionState.PAUSED

    fun start(): RideSession = session ?: sessions.startSession()

    fun pause(): RideSession? = when (session?.state) {
        RideSessionState.RECORDING -> sessions.pauseSession()
        else -> session
    }

    fun resume(): RideSession? = when (session?.state) {
        RideSessionState.PAUSED -> sessions.resumeSession()
        else -> session
    }

    fun stop(): RideSession? = if (serviceRequired) sessions.stopSession() else null

    fun onBleDisconnected() = session
    fun onBleReconnected() = session
}
