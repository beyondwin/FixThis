package io.github.beyondwin.fixthis.mcp.session.runtime

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

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
        jvmLock.lockInterruptibly()
        return try {
            withProcessLock(canonicalRoot, block)
        } finally {
            jvmLock.unlock()
        }
    }

    private fun <T> withProcessLock(
        canonicalRoot: File,
        block: () -> T,
    ): T = RuntimeEvidenceQuotaFileLock(canonicalRoot).withLock(block)

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

internal data class RuntimeEvidenceQuotaFileLockHooks(
    val insideRecoveryLockBeforePrimaryInspection: () -> Unit = {},
    val afterRecoveryBeforePrimaryOpen: (File) -> Unit = {},
    val beforePrimaryLock: () -> Unit = {},
)

internal class RuntimeEvidenceQuotaFileLock(
    private val evidenceRoot: File,
    private val hooks: RuntimeEvidenceQuotaFileLockHooks = RuntimeEvidenceQuotaFileLockHooks(),
) {
    fun <T> withLock(block: () -> T): T {
        val primaryLock = recoverPrimaryLock()
        hooks.afterRecoveryBeforePrimaryOpen(primaryLock)
        hooks.beforePrimaryLock()
        val localPrimaryLock = primaryLocks.computeIfAbsent(evidenceRoot.canonicalFile.toPath()) { ReentrantLock() }
        localPrimaryLock.lockInterruptibly()
        return try {
            openLockFile(primaryLock).use { channel ->
                channel.lock().use { block() }
            }
        } finally {
            localPrimaryLock.unlock()
        }
    }

    private fun recoverPrimaryLock(): File {
        val recoveryLock = File(evidenceRoot, RECOVERY_LOCK_FILE_NAME)
        openLockFile(recoveryLock).use { recoveryChannel ->
            recoveryChannel.lock().use {
                hooks.insideRecoveryLockBeforePrimaryInspection()
                val primaryLock = File(evidenceRoot, RuntimeEvidenceArtifactQuotaGuard.LOCK_FILE_NAME)
                if (Files.isSymbolicLink(primaryLock.toPath())) Files.delete(primaryLock.toPath())
                openLockFile(primaryLock).use { }
                return primaryLock
            }
        }
    }

    private fun openLockFile(file: File): FileChannel {
        require(evidenceRoot.isDirectory && !Files.isSymbolicLink(evidenceRoot.toPath())) {
            "Runtime-evidence quota root must be a real directory"
        }
        RuntimeEvidencePrivatePermissions.tightenDirectory(evidenceRoot.toPath())
        val options: Set<OpenOption> = setOf(
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        return RuntimeEvidencePrivatePermissions.openFile(file.toPath(), options)
    }

    internal companion object {
        const val RECOVERY_LOCK_FILE_NAME = ".quota.recovery.lock"
        private val primaryLocks = ConcurrentHashMap<java.nio.file.Path, ReentrantLock>()
    }
}
