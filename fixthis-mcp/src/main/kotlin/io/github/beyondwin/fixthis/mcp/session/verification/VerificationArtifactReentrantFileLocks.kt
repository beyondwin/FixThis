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

        return acquire(path, key, heldLocks, block)
    }

    private fun <T> acquire(
        path: Path,
        key: Path,
        heldLocks: MutableMap<Path, HeldFileLock>,
        block: (Path) -> T,
    ): T = try {
        FileChannel.open(path, lockOpenOptions).use { channel ->
            channel.lock().use { fileLock ->
                runWithAcquiredLock(path, key, heldLocks, fileLock, block)
            }
        }
    } finally {
        if (heldLocks.isEmpty()) heldLocksByThread.remove()
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
