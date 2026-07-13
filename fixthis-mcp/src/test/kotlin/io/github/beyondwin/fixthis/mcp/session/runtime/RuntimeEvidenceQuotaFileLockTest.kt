package io.github.beyondwin.fixthis.mcp.session.runtime

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeEvidenceQuotaFileLockTest {
    @Test
    fun concurrentRecoveryKeepsOnePrimaryInodeAndSerializesReservations() = withRoot { root ->
        ConcurrentQuotaRecoveryRace(root).verify()
    }

    @Test
    fun recoveryRepairsPrimaryOnlyWhileHoldingRecoveryOsLock() = withRoot { root ->
        QuotaRecoveryOsLockProof(root).verify()
    }

    private inline fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("fixthis-runtime-quota-lock-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

private class ConcurrentQuotaRecoveryRace(root: File) {
    private val evidenceRoot = File(root, ".fixthis/runtime-evidence").apply { mkdirs() }
    private val primaryLock = File(evidenceRoot, RuntimeEvidenceArtifactQuotaGuard.LOCK_FILE_NAME)
    private val poisonTarget = File(root, "quota-lock-poison-target").apply { writeText("keep") }
    private val firstRecovered = CountDownLatch(1)
    private val allowFirstPrimaryOpen = CountDownLatch(1)
    private val firstAttemptingPrimaryLock = CountDownLatch(1)
    private val firstEntered = CountDownLatch(1)
    private val secondEntered = CountDownLatch(1)
    private val releaseSecond = CountDownLatch(1)
    private val activeReservations = AtomicInteger()
    private val maximumReservations = AtomicInteger()
    private val recreatedFileKey = AtomicReference<Any>()
    private val secondFileKey = AtomicReference<Any>()
    private val failures = Collections.synchronizedList(mutableListOf<Throwable>())

    init {
        Files.createSymbolicLink(primaryLock.toPath(), poisonTarget.toPath())
    }

    fun verify() {
        val first = firstThread().apply { start() }
        check(firstRecovered.await(5, TimeUnit.SECONDS))
        val second = secondThread().apply { start() }
        check(secondEntered.await(5, TimeUnit.SECONDS))
        allowFirstPrimaryOpen.countDown()
        check(firstAttemptingPrimaryLock.await(5, TimeUnit.SECONDS))
        val overlapped = firstEntered.await(500, TimeUnit.MILLISECONDS)
        releaseSecond.countDown()
        first.join(10_000)
        second.join(10_000)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertFalse(overlapped, "primary reservations overlapped across recovered lock instances")
        assertEquals(emptyList(), failures)
        assertEquals(recreatedFileKey.get(), secondFileKey.get())
        assertEquals(recreatedFileKey.get(), fileKey(primaryLock))
        assertEquals(1, maximumReservations.get())
        assertEquals("keep", poisonTarget.readText())
    }

    private fun firstThread() = thread(start = false, name = "runtime-evidence-recovery-first") {
        val lock = RuntimeEvidenceQuotaFileLock(
            evidenceRoot,
            RuntimeEvidenceQuotaFileLockHooks(
                afterRecoveryBeforePrimaryOpen = { file ->
                    recreatedFileKey.set(fileKey(file))
                    firstRecovered.countDown()
                    check(allowFirstPrimaryOpen.await(5, TimeUnit.SECONDS))
                },
                beforePrimaryLock = { firstAttemptingPrimaryLock.countDown() },
            ),
        )
        runCatching {
            lock.withLock { trackReservation { firstEntered.countDown() } }
        }.exceptionOrNull()?.let(failures::add)
    }

    private fun secondThread() = thread(start = false, name = "runtime-evidence-recovery-second") {
        val lock = RuntimeEvidenceQuotaFileLock(evidenceRoot)
        runCatching {
            lock.withLock {
                secondFileKey.set(fileKey(primaryLock))
                trackReservation {
                    secondEntered.countDown()
                    check(releaseSecond.await(5, TimeUnit.SECONDS))
                }
            }
        }.exceptionOrNull()?.let(failures::add)
    }

    private fun trackReservation(block: () -> Unit) {
        val active = activeReservations.incrementAndGet()
        maximumReservations.updateAndGet { maximum -> maxOf(maximum, active) }
        try {
            block()
        } finally {
            activeReservations.decrementAndGet()
        }
    }

    private fun fileKey(file: File): Any = requireNotNull(
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey(),
    )
}

private class QuotaRecoveryOsLockProof(root: File) {
    private val evidenceRoot = File(root, ".fixthis/runtime-evidence").apply { mkdirs() }
    private val primaryLock = File(evidenceRoot, RuntimeEvidenceArtifactQuotaGuard.LOCK_FILE_NAME)
    private val recoveryLock = File(evidenceRoot, RuntimeEvidenceQuotaFileLock.RECOVERY_LOCK_FILE_NAME)
    private val poisonTarget = File(root, "quota-recovery-proof-target").apply { writeText("keep") }
    private val insideRecoveryCriticalSection = CountDownLatch(1)
    private val releaseRecoveryCriticalSection = CountDownLatch(1)
    private val failures = Collections.synchronizedList(mutableListOf<Throwable>())

    init {
        Files.createSymbolicLink(primaryLock.toPath(), poisonTarget.toPath())
    }

    fun verify() {
        val worker = thread(name = "runtime-evidence-recovery-proof") {
            val lock = RuntimeEvidenceQuotaFileLock(
                evidenceRoot,
                RuntimeEvidenceQuotaFileLockHooks(
                    insideRecoveryLockBeforePrimaryInspection = {
                        insideRecoveryCriticalSection.countDown()
                        check(releaseRecoveryCriticalSection.await(5, TimeUnit.SECONDS))
                    },
                ),
            )
            runCatching { lock.withLock {} }.exceptionOrNull()?.let(failures::add)
        }
        check(insideRecoveryCriticalSection.await(5, TimeUnit.SECONDS))
        val acquiredRecoveryLock = try {
            assertTrue(Files.isSymbolicLink(primaryLock.toPath()))
            canAcquireRecoveryLock()
        } finally {
            releaseRecoveryCriticalSection.countDown()
        }
        worker.join(10_000)

        assertFalse(worker.isAlive)
        assertFalse(acquiredRecoveryLock, "recovery OS lock was not held inside the repair critical section")
        assertEquals(emptyList(), failures)
        assertFalse(Files.isSymbolicLink(primaryLock.toPath()))
        assertTrue(primaryLock.isFile)
        val stableFileKey = fileKey(primaryLock)
        RuntimeEvidenceQuotaFileLock(evidenceRoot).withLock {}
        assertEquals(stableFileKey, fileKey(primaryLock))
        assertEquals("keep", poisonTarget.readText())
    }

    private fun canAcquireRecoveryLock(): Boolean {
        val options: Set<OpenOption> = setOf(
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        return FileChannel.open(recoveryLock.toPath(), options).use { channel ->
            try {
                channel.tryLock()?.use { true } ?: false
            } catch (_: OverlappingFileLockException) {
                false
            }
        }
    }

    private fun fileKey(file: File): Any = requireNotNull(
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey(),
    )
}
