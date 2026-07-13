package io.github.beyondwin.fixthis.mcp.session.runtime

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class RuntimeEvidenceArtifactQuotaGuard(
    private val evidenceRoot: File,
    private val byteCounter: (File) -> Long,
    private val beforeQuotaCheck: () -> Unit,
) {
    fun <T> withReservation(
        newArtifactBytes: Long,
        block: () -> T,
    ): T = withExclusive {
        beforeQuotaCheck()
        enforceQuota(byteCounter(evidenceRoot.canonicalFile), newArtifactBytes)
        block()
    }

    fun <T> withExclusive(block: () -> T): T {
        val canonicalRoot = evidenceRoot.canonicalFile
        require(canonicalRoot.isDirectory && !Files.isSymbolicLink(evidenceRoot.toPath())) {
            "Runtime-evidence quota root must be a real directory"
        }
        val jvmLock = rootLocks.computeIfAbsent(canonicalRoot.toPath()) { ReentrantLock() }
        return jvmLock.withLock {
            withProcessLock(canonicalRoot, block)
        }
    }

    private fun <T> withProcessLock(
        canonicalRoot: File,
        block: () -> T,
    ): T {
        val lockFile = File(canonicalRoot, LOCK_FILE_NAME)
        require(!Files.isSymbolicLink(lockFile.toPath())) { "Runtime-evidence quota lock must not be a symlink" }
        val options: Set<OpenOption> = setOf(
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        return FileChannel.open(lockFile.toPath(), options).use { channel ->
            channel.lock().use { block() }
        }
    }

    private fun enforceQuota(
        currentBytes: Long,
        newArtifactBytes: Long,
    ) {
        require(currentBytes >= 0L) { "Runtime-evidence quota accounting returned a negative size" }
        val totalBytes = try {
            Math.addExact(currentBytes, newArtifactBytes)
        } catch (cause: ArithmeticException) {
            throw RuntimeEvidenceArtifactQuotaException("Runtime-evidence quota accounting overflowed", cause)
        }
        if (totalBytes > PROJECT_QUOTA_BYTES) {
            throw RuntimeEvidenceArtifactQuotaException(
                "Runtime-evidence project quota of $PROJECT_QUOTA_BYTES bytes would be exceeded",
            )
        }
    }

    internal companion object {
        const val LOCK_FILE_NAME = ".quota.lock"
        private const val PROJECT_QUOTA_BYTES = 250L * 1024L * 1024L
        private val rootLocks = ConcurrentHashMap<java.nio.file.Path, ReentrantLock>()
    }
}
