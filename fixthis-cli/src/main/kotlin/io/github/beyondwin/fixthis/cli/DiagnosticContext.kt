package io.github.beyondwin.fixthis.cli

/**
 * Per-thread diagnostic flag. Wraps a [ThreadLocal] so Gradle's parallel test
 * workers do not contaminate one another. The production CLI is single-shot
 * and single-threaded, so the ThreadLocal collapses to one slot in practice.
 * Tests MUST call [reset] in `@After` to free the value on the worker thread.
 */
internal object DiagnosticContext {
    private val verboseFlag: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    var verbose: Boolean
        get() = verboseFlag.get()
        set(value) {
            verboseFlag.set(value)
        }

    fun reset() {
        verboseFlag.remove()
    }
}
