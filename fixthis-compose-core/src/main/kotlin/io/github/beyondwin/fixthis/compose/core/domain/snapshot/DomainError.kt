package io.github.beyondwin.fixthis.compose.core.domain.snapshot

data class DomainError(
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)
