package io.github.beyondwin.fixthis.compose.sidekick.bridge

/**
 * Observable lifecycle of [BridgeServer]. Exposed via [BridgeServer.state] so
 * tests and future console / UI consumers can subscribe instead of polling
 * [BridgeServer.resolvedSocketName].
 *
 * Transitions are strictly serialised by [BridgeServer]'s lifecycle mutex:
 * `Idle -> Starting -> Running -> Stopping -> Idle`. `Starting -> Idle`
 * is the only short-circuit (occurs when all bind attempts fail).
 */
sealed interface BridgeServerState {
    data object Idle : BridgeServerState
    data object Starting : BridgeServerState
    data class Running(val socketName: String) : BridgeServerState
    data object Stopping : BridgeServerState
}
