package io.beyondwin.fixthis.cli.bridge

import io.beyondwin.fixthis.cli.AdbFacade
import io.beyondwin.fixthis.cli.BridgeSocket

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
