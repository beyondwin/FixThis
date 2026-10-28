@file:Suppress("FunctionNaming")

package io.github.beyondwin.fixthis.compose.sidekick.bridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Adapts only Android's native socket boundary to JVM loopback sockets.
 * BridgeServer still accepts clients, reads frames, dispatches and cancels its
 * own handler jobs. This fixture does not prove Android AF_UNIX behaviour.
 */
@Implements(LocalServerSocket::class)
class JvmLoopbackServerSocketShadow {
    private lateinit var socket: ServerSocket

    val port: Int get() = socket.localPort

    @Implementation
    fun __constructor__(name: String) {
        require(name.isNotBlank())
        socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    }

    @Implementation
    fun accept(): LocalSocket {
        val accepted = socket.accept()
        return LocalSocket().also { client ->
            Shadow.extract<JvmLoopbackClientSocketShadow>(client).socket = accepted
        }
    }

    @Implementation
    fun close() {
        socket.close()
    }
}

@Implements(LocalSocket::class)
class JvmLoopbackClientSocketShadow {
    internal lateinit var socket: Socket

    @Implementation
    fun __constructor__() = Unit

    @Implementation
    fun getInputStream(): InputStream = socket.getInputStream()

    @Implementation
    fun getOutputStream(): OutputStream = socket.getOutputStream()

    @Implementation
    fun close() {
        socket.close()
    }
}
