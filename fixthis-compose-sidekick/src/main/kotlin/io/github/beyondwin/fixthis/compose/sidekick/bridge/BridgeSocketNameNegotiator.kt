package io.github.beyondwin.fixthis.compose.sidekick.bridge

/**
 * Picks the candidate Linux abstract-namespace socket name to attempt during
 * [BridgeServer.start]. Used to recover from stale bindings left behind by a
 * prior process that died without releasing its `LocalServerSocket`.
 *
 * The retry sequence is bounded to [MaxAttempts] candidates:
 *
 * | attempt | candidate    |
 * |---------|--------------|
 * | 0       | `<base>`     |
 * | 1       | `<base>-1`   |
 * | 2       | `<base>-2`   |
 *
 * Attempts outside `0..MaxAttempts-1` are rejected with [IllegalArgumentException].
 * Three attempts is enough to survive a single zombie process while still failing
 * loudly if the abstract namespace is genuinely exhausted or held by an unrelated
 * package.
 */
internal object BridgeSocketNameNegotiator {
    const val MaxAttempts: Int = 3

    fun nextCandidate(base: String, attempt: Int): String {
        require(attempt in 0 until MaxAttempts) {
            "BridgeSocketNameNegotiator supports attempts 0..${MaxAttempts - 1}, got $attempt"
        }
        return if (attempt == 0) base else "$base-$attempt"
    }
}
