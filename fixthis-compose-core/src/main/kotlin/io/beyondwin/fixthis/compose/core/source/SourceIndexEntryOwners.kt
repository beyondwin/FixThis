package io.beyondwin.fixthis.compose.core.source

internal val SourceIndexEntry.ownerComposable: String?
    get() = signals.firstOrNull { it.kind == SourceSignalKind.LAMBDA_OWNER_FUNCTION }?.value
