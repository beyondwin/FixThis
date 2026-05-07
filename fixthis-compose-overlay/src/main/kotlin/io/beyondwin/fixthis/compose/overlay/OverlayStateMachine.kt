package io.beyondwin.fixthis.compose.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OverlayStateMachine(initial: OverlayMode = OverlayMode.Idle) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<OverlayMode> = mutableState.asStateFlow()

    fun transition(next: OverlayMode) {
        val current = mutableState.value
        check(current.canTransitionTo(next)) {
            "invalid overlay transition: $current -> $next"
        }
        mutableState.value = next
    }
}

fun OverlayMode.canTransitionTo(next: OverlayMode): Boolean =
    when (this) {
        OverlayMode.Idle -> next is OverlayMode.MenuOpen || next is OverlayMode.Select
        OverlayMode.MenuOpen -> next is OverlayMode.Idle || next is OverlayMode.Select
        is OverlayMode.Select -> next is OverlayMode.Idle ||
            next is OverlayMode.Select ||
            next is OverlayMode.Loading ||
            next is OverlayMode.ReviewingSelection ||
            next is OverlayMode.Commenting ||
            next is OverlayMode.Error
        is OverlayMode.Loading -> next is OverlayMode.ReviewingSelection ||
            next is OverlayMode.Commenting ||
            next is OverlayMode.Error
        is OverlayMode.ReviewingSelection -> next is OverlayMode.Commenting ||
            next is OverlayMode.ReviewingSelection ||
            next is OverlayMode.Select ||
            next is OverlayMode.Idle ||
            next is OverlayMode.Error
        is OverlayMode.Commenting -> next is OverlayMode.Idle ||
            next is OverlayMode.Commenting ||
            next is OverlayMode.Select ||
            next is OverlayMode.Exported ||
            next is OverlayMode.Error
        is OverlayMode.Exported -> next is OverlayMode.Idle || next is OverlayMode.Select
        is OverlayMode.Error -> next is OverlayMode.Idle || (recoverable && next !is OverlayMode.Error)
    }
