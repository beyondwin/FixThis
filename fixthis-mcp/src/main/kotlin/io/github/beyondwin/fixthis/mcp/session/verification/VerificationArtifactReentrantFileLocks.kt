package io.github.beyondwin.fixthis.mcp.session.verification

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal object VerificationArtifactReentrantFileLocks {
    private val heldLocksByThread = ThreadLocal.withInitial {
        mutableMapOf<Path, HeldFileLock>()
    }

    fun <T> withLock(
        path: Path,
        hooks: VerificationArtifactStoreHooks,
        block: (Path) -> T,
    ): T {
        val key = path.toAbsolutePath().normalize()
        val heldLocks = heldLocksByThread.get()
        val held = heldLocks[key]
        if (held != null) {
            return held.reenter {
                block(path)
            }
        }

        return acquire(path, key, heldLocks, hooks, block)
    }

    private fun <T> acquire(
        path: Path,
        key: Path,
        heldLocks: MutableMap<Path, HeldFileLock>,
        hooks: VerificationArtifactStoreHooks,
        block: (Path) -> T,
    ): T {
        val channel = FileChannel.open(path, lockOpenOptions)
        val fileLock = acquireFileLock(channel, hooks.closeFileChannel)
        val outcome = runCatching {
            runWithAcquiredLock(path, key, heldLocks, fileLock, block)
        }
        val cleanupFailures = releaseAndClose(
            fileLock = fileLock,
            channel = channel,
            releaseFileLock = hooks.releaseFileLock,
            closeFileChannel = hooks.closeFileChannel,
        )
        outcome.exceptionOrNull()?.let { primaryFailure ->
            cleanupFailures.forEach { cleanupFailure ->
                if (cleanupFailure !== primaryFailure) {
                    runCatching { primaryFailure.addSuppressed(cleanupFailure) }
                }
            }
        }
        if (heldLocks.isEmpty()) heldLocksByThread.remove()
        return outcome.getOrThrow()
    }

    private fun acquireFileLock(
        channel: FileChannel,
        closeFileChannel: (FileChannel) -> Unit,
    ): FileLock {
        val outcome = runCatching { channel.lock() }
        outcome.exceptionOrNull()?.let { primaryFailure ->
            runCatching { closeFileChannel(channel) }.exceptionOrNull()?.let { cleanupFailure ->
                if (cleanupFailure !== primaryFailure) {
                    runCatching { primaryFailure.addSuppressed(cleanupFailure) }
                }
            }
        }
        return outcome.getOrThrow()
    }

    private fun releaseAndClose(
        fileLock: FileLock,
        channel: FileChannel,
        releaseFileLock: (FileLock) -> Unit,
        closeFileChannel: (FileChannel) -> Unit,
    ): List<Throwable> = buildList {
        runCatching { releaseFileLock(fileLock) }.exceptionOrNull()?.let(::add)
        runCatching { closeFileChannel(channel) }.exceptionOrNull()?.let(::add)
    }

    private fun <T> runWithAcquiredLock(
        path: Path,
        key: Path,
        heldLocks: MutableMap<Path, HeldFileLock>,
        fileLock: FileLock,
        block: (Path) -> T,
    ): T {
        val acquired = HeldFileLock(fileLock)
        check(heldLocks.put(key, acquired) == null)
        return try {
            block(path)
        } finally {
            check(acquired.references == 1)
            check(heldLocks.remove(key) === acquired)
        }
    }

    private class HeldFileLock(
        private val fileLock: FileLock,
        var references: Int = 1,
    ) {
        fun <T> reenter(block: () -> T): T {
            check(fileLock.isValid)
            references += 1
            return try {
                block()
            } finally {
                references -= 1
            }
        }
    }

    private val lockOpenOptions: Set<OpenOption> = setOf(
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS,
    )
}
