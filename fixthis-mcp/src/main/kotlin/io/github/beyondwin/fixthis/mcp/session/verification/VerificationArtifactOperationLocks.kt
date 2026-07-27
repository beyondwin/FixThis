package io.github.beyondwin.fixthis.mcp.session.verification

import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

internal class VerificationArtifactOperationLocks(
    private val paths: VerificationArtifactPaths,
    private val hooks: VerificationArtifactStoreHooks,
) {
    private val canonicalRoot = paths.projectRoot.toPath()

    fun <T> withReceiptLock(
        sessionId: String,
        receiptId: String,
        block: () -> T,
    ): T {
        VerificationArtifactNaming.validateSessionId(sessionId)
        VerificationArtifactNaming.validateReceiptId(receiptId)
        return withJvmRootLock {
            val lockRoot = ensureLockRoot()
            withFileLock(lockRoot.resolve(ROOT_LOCK_FILE)) { rootLock ->
                val receiptLockPath = lockRoot.resolve(receiptLockName(sessionId, receiptId))
                withFileLock(receiptLockPath) { receiptLock ->
                    hooks.insideOperationLocks(rootLock, receiptLock)
                    block()
                }
            }
        }
    }

    fun <T> withRootLock(block: () -> T): T = withJvmRootLock {
        val lockRoot = ensureLockRoot()
        withFileLock(lockRoot.resolve(ROOT_LOCK_FILE)) { rootLock ->
            hooks.insideOperationLocks(rootLock, null)
            block()
        }
    }

    private fun <T> withJvmRootLock(block: () -> T): T {
        val lock = rootLocks.computeIfAbsent(canonicalRoot) { ReentrantLock() }
        lock.lockInterruptibly()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private fun ensureLockRoot(): Path {
        artifactRequire(
            Files.isDirectory(canonicalRoot, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(canonicalRoot),
        ) {
            "Verification artifact project root must be a real directory"
        }
        val fixThisRoot = createRealDirectory(canonicalRoot.resolve(".fixthis"))
        return createRealDirectory(fixThisRoot.resolve(LOCK_DIRECTORY))
    }

    private fun createRealDirectory(path: Path): Path {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(path)
            } catch (_: FileAlreadyExistsException) {
                // A cooperating process may have created the same lock directory.
            }
        }
        artifactRequire(
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path) &&
                path.toFile().canonicalFile.toPath().startsWith(canonicalRoot),
        ) {
            "Verification artifact lock path must be a real directory inside the project root"
        }
        return path
    }

    private fun <T> withFileLock(
        path: Path,
        block: (Path) -> T,
    ): T {
        val options: Set<OpenOption> = setOf(
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        return FileChannel.open(path, options).use { channel ->
            channel.lock().use {
                block(path)
            }
        }
    }

    private fun receiptLockName(sessionId: String, receiptId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$sessionId\u0000$receiptId".toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "$digest.lock"
    }

    private companion object {
        const val LOCK_DIRECTORY = "verification-artifact-locks"
        const val ROOT_LOCK_FILE = "root.lock"
        val rootLocks = ConcurrentHashMap<Path, ReentrantLock>()
    }
}
