package io.github.beyondwin.fixthis.cli.bridge

import io.github.beyondwin.fixthis.cli.AdbFacade
import io.github.beyondwin.fixthis.cli.BridgeSocket

internal data class BridgeRequestScope(
    val selectedDeviceSerial: String?,
    val adb: AdbFacade,
)

internal interface BridgeTransport {
    fun <T> withSocket(
        adb: AdbFacade,
        session: SidekickSession,
        activeRequest: ActiveBridgeRequest,
        block: (BridgeSocket) -> T,
    ): T
}
