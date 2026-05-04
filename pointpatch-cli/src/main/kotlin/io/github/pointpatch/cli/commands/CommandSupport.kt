package io.github.pointpatch.cli.commands

import com.github.ajalt.clikt.core.CliktError

internal fun <T> failAsCliError(block: () -> T): T =
    try {
        block()
    } catch (error: CliktError) {
        throw error
    } catch (error: Throwable) {
        throw CliktError(error.message ?: error::class.java.simpleName)
    }
