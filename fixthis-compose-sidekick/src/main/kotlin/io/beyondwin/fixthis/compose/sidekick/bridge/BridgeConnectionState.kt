package io.beyondwin.fixthis.compose.sidekick.bridge

class BridgeConnectionState(
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectedWindowMillis: Long = DefaultConnectedWindowMillis,
) {
    @Volatile
    private var lastAuthorizedRequestAtMillis: Long = 0L

    fun markAuthorizedRequest() {
        lastAuthorizedRequestAtMillis = clock()
    }

    fun isConnected(): Boolean {
        val lastSeen = lastAuthorizedRequestAtMillis
        return lastSeen > 0L && clock() - lastSeen <= connectedWindowMillis
    }

    private companion object {
        const val DefaultConnectedWindowMillis = 5_000L
    }
}
