package io.github.beyondwin.fixthis.compose.core.source

internal data class MarginContext(
    val ranking: Int,
    val scoreMargin: Double,
) {
    val isAmbiguous: Boolean get() = scoreMargin < CLOSE_RACE_MARGIN
    val isMediumCeiling: Boolean get() =
        scoreMargin >= CLOSE_RACE_MARGIN && scoreMargin < CLEAR_MARGIN

    companion object {
        const val CLEAR_MARGIN: Double = 0.20
        const val CLOSE_RACE_MARGIN: Double = 0.15
        private const val SAFE_DENOMINATOR: Double = 0.001

        fun of(scoresHighestFirst: List<Double>, index: Int): MarginContext {
            require(index >= 0 && index < scoresHighestFirst.size) { "index out of range" }
            val ranking = index + 1
            if (scoresHighestFirst.size == 1) {
                return MarginContext(ranking = ranking, scoreMargin = 1.0)
            }
            val top = scoresHighestFirst[0]
            val next = scoresHighestFirst.getOrElse(1) { 0.0 }
            val denominator = if (top <= 0.0) SAFE_DENOMINATOR else top
            val margin = ((top - next) / denominator).coerceAtMost(1.0).coerceAtLeast(0.0)
            return MarginContext(ranking = ranking, scoreMargin = margin)
        }
    }
}
