package io.beyondwin.fixthis.cli.bridge

import io.beyondwin.fixthis.cli.AdbFacade
import io.beyondwin.fixthis.cli.BridgeConnectionException
import io.beyondwin.fixthis.cli.BridgeSocket
import kotlinx.coroutines.CancellationException
import java.io.IOException

private const val BRIDGE_SOCKET_NAME_MAX_ATTEMPTS = 3

internal class AdbForwardingBridgeTransport(
    private val portAllocator: () -> Int,
    private val socketConnector: (Int) -> BridgeSocket,
) : BridgeTransport {
    override fun <T> withSocket(
        adb: AdbFacade,
        session: SidekickSession,
        activeRequest: ActiveBridgeRequest,
        block: (BridgeSocket) -> T,
    ): T {
        val localPort = portAllocator()
        activeRequest.registerForwardPort(localPort)
        return try {
            val socket = openSocketWithSocketNameFallback(adb, localPort, session.socketName, activeRequest)
            activeRequest.throwIfCancelled()
            activeRequest.registerSocket(socket)
            activeRequest.throwIfCancelled()
            socket.use(block)
        } finally {
            activeRequest.cleanup()
        }
    }

    private fun openSocketWithSocketNameFallback(
        adb: AdbFacade,
        localPort: Int,
        sessionSocketName: String,
        activeRequest: ActiveBridgeRequest,
    ): BridgeSocket {
        var lastError: Throwable? = null
        for (attempt in 0 until BRIDGE_SOCKET_NAME_MAX_ATTEMPTS) {
            val candidate = if (attempt == 0) sessionSocketName else "$sessionSocketName-$attempt"
            val address = "localabstract:$candidate"
            val socket = try {
                adb.forward(localPort, address)
                activeRequest.markForwardEstablished()
                socketConnector(localPort)
            } catch (error: IOException) {
                lastError = error
                null
            }
            if (socket != null) {
                return socket
            }
        }
        throw BridgeConnectionException(
            "Could not connect to FixThis bridge socket $sessionSocketName " +
                "(also tried $sessionSocketName-1, $sessionSocketName-2): " +
                (lastError?.message ?: "unknown error"),
        )
    }
}

internal class ActiveBridgeRequest(private val adb: AdbFacade) {
    private val lock = Object()
    private var localPort: Int? = null
    private var forwardEstablished = false
    private var forwardRemoved = false
    private var socket: BridgeSocket? = null
    private var cancelled = false

    fun registerForwardPort(port: Int) {
        synchronized(lock) {
            localPort = port
            forwardEstablished = false
            forwardRemoved = false
            socket = null
        }
    }

    fun markForwardEstablished() {
        val shouldRemove = synchronized(lock) {
            forwardEstablished = true
            cancelled
        }
        if (shouldRemove) removeForwardOnce()
    }

    fun registerSocket(socket: BridgeSocket) {
        val shouldClose = synchronized(lock) {
            this.socket = socket
            cancelled
        }
        if (shouldClose) runCatching { socket.close() }
    }

    fun cancel() {
        val socketToClose = synchronized(lock) {
            cancelled = true
            socket
        }
        runCatching { socketToClose?.close() }
        removeForwardOnce()
    }

    fun cleanup() {
        removeForwardOnce()
    }

    fun throwIfCancelled() {
        if (synchronized(lock) { cancelled }) {
            throw CancellationException("Bridge request cancelled")
        }
    }

    private fun removeForwardOnce() {
        val port = synchronized(lock) {
            if (!forwardEstablished || forwardRemoved) return
            val registeredPort = localPort ?: return
            forwardRemoved = true
            registeredPort
        }
        runCatching { adb.removeForward(port) }
    }
}
