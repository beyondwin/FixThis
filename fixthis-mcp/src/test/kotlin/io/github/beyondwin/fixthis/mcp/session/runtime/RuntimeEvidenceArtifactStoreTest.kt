package io.github.beyondwin.fixthis.mcp.session.runtime

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeEvidenceArtifactStoreTest {
    @Test
    fun commitWritesOnlyRedactedBoundedFilesAndManifestLast() = withRoot { root ->
        val operations = mutableListOf<String>()
        val store = FileRuntimeEvidenceArtifactStore(
            projectRoot = root,
            redactor = RuntimeEvidenceRedactor(),
            hooks = RuntimeEvidenceArtifactStoreHooks(
                beforeWrite = { operations += "write:${it.name}" },
                beforeAtomicMove = { _, _ -> operations += "move" },
            ),
        )

        val committed = store.commit(
            sessionId = "session-1",
            captureId = "capture-1",
            inputs = listOf(
                input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "password=hunter2"),
                input(RuntimeEvidenceType.MEMORY_SUMMARY, "memory-summary.txt", "Authorization: Bearer token"),
            ),
        )

        val bundle = File(root, committed.relativeDirectory)
        assertEquals(".fixthis/runtime-evidence/session-1/capture-1", committed.relativeDirectory)
        assertEquals(
            ".fixthis/runtime-evidence/session-1/capture-1/logcat.txt",
            committed.relativeFiles.getValue(RuntimeEvidenceType.LOGCAT_WINDOW),
        )
        assertEquals("password=[REDACTED]", File(bundle, "logcat.txt").readText())
        assertEquals("Authorization: [REDACTED]", File(bundle, "memory-summary.txt").readText())
        assertTrue(File(bundle, "manifest.json").isFile)
        assertEquals(listOf("write:logcat.txt", "write:memory-summary.txt", "write:manifest.json", "move"), operations)
        assertFalse(File(bundle.parentFile, "capture-1.tmp-leftover").exists())
    }

    @Test
    fun commitReRedactsCallerSuppliedSummaryText() = withRoot { root ->
        val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())

        val committed = store.commit(
            "session-1",
            "capture-1",
            listOf(input(RuntimeEvidenceType.FRAME_SUMMARY, "frame-summary.txt", "api_key=still-raw")),
        )

        val persisted = File(root, committed.relativeFiles.getValue(RuntimeEvidenceType.FRAME_SUMMARY)).readText()
        assertEquals("api_key=[REDACTED]", persisted)
    }

    @Test
    fun rejectsUnsafeIdsFileNamesAndTraversal() = withRoot { root ->
        val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())
        val safeInput = input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "ok")

        listOf("../session", "session/child", "..", ".", "session name", "").forEach { id ->
            assertFailsWith<IllegalArgumentException>("unsafe session id: $id") {
                store.commit(id, "capture-1", listOf(safeInput))
            }
            assertFailsWith<IllegalArgumentException>("unsafe capture id: $id") {
                store.commit("session-1", id, listOf(safeInput))
            }
        }
        listOf("../logcat.txt", "nested/logcat.txt", "..", ".", "log cat.txt", "manifest.json").forEach { name ->
            assertFailsWith<IllegalArgumentException>("unsafe file name: $name") {
                store.commit("session-1", "capture-1", listOf(safeInput.copy(fileName = name)))
            }
        }
        assertFalse(File(root, ".fixthis/runtime-evidence/session").exists())
    }

    @Test
    fun rejectsSymlinkedEvidencePathAndCanonicalEscape() = withRoot { root ->
        val outside = Files.createTempDirectory("fixthis-runtime-outside-").toFile()
        try {
            val evidenceRoot = File(root, ".fixthis/runtime-evidence").apply { mkdirs() }
            Files.createSymbolicLink(File(evidenceRoot, "session-1").toPath(), outside.toPath())
            val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())

            assertFailsWith<IllegalArgumentException> {
                store.commit(
                    "session-1",
                    "capture-1",
                    listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "secret=outside")),
                )
            }
            assertEquals(emptyList(), outside.listFiles()?.toList().orEmpty())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun writeRejectsSymlinkInsertedAtArtifactBoundary() = withRoot { root ->
        val outside = Files.createTempDirectory("fixthis-runtime-write-outside-").toFile()
        try {
            val escapedTarget = File(outside, "escaped.txt")
            val store = FileRuntimeEvidenceArtifactStore(
                projectRoot = root,
                redactor = RuntimeEvidenceRedactor(),
                hooks = RuntimeEvidenceArtifactStoreHooks(
                    beforeWrite = { file ->
                        if (file.name == "logcat.txt") Files.createSymbolicLink(file.toPath(), escapedTarget.toPath())
                    },
                ),
            )

            assertFailsWith<IllegalStateException> {
                store.commit(
                    "session-1",
                    "capture-1",
                    listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "password=must-not-escape")),
                )
            }
            assertFalse(escapedTarget.exists())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun writeRejectsTempParentReplacedBySymlinkAfterValidation() = withRoot { root ->
        val outside = Files.createTempDirectory("fixthis-runtime-parent-outside-").toFile()
        try {
            val store = FileRuntimeEvidenceArtifactStore(
                projectRoot = root,
                redactor = RuntimeEvidenceRedactor(),
                hooks = RuntimeEvidenceArtifactStoreHooks(
                    beforeWrite = { file ->
                        if (file.name == "logcat.txt") {
                            assertTrue(file.parentFile.delete())
                            Files.createSymbolicLink(file.parentFile.toPath(), outside.toPath())
                        }
                    },
                ),
            )

            assertFailsWith<IllegalStateException> {
                store.commit(
                    "session-1",
                    "capture-1",
                    listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "password=must-stay-contained")),
                )
            }

            assertFalse(File(outside, "logcat.txt").exists())
            assertFalse(File(root, ".fixthis/runtime-evidence/session-1/capture-1").exists())
            assertTrue(
                File(root, ".fixthis/runtime-evidence/session-1").listFiles().orEmpty()
                    .none { it.name.startsWith("capture-1.tmp-") },
            )
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun deleteBundleUnlinksNestedSymlinkWithoutTraversingIt() = withRoot { root ->
        val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())
        val committed = store.commit(
            "session-1",
            "capture-1",
            listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "safe")),
        )
        val outside = Files.createTempDirectory("fixthis-runtime-delete-outside-").toFile()
        try {
            val sentinel = File(outside, "sentinel.txt").apply { writeText("keep") }
            Files.createSymbolicLink(File(root, "${committed.relativeDirectory}/nested-link").toPath(), outside.toPath())

            store.deleteBundle("session-1", "capture-1")
            assertEquals("keep", sentinel.readText())
            assertFalse(File(root, committed.relativeDirectory).exists())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun enforcesPerFileAndTwoMiBBundleBoundaries() = withRoot { root ->
        val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())
        val calibration = store.commit(
            "session-1",
            "capture-exact",
            listOf(input(RuntimeEvidenceType.TRACE_ARTIFACT, "trace.txt", "x".repeat(1_000_000))),
        )
        val manifestBytes = File(root, "${calibration.relativeDirectory}/manifest.json").length().toInt()
        store.deleteSession("session-1")
        val exactPayloadBytes = 2 * 1024 * 1024 - manifestBytes

        store.commit(
            "session-1",
            "capture-exact",
            listOf(input(RuntimeEvidenceType.TRACE_ARTIFACT, "trace.txt", "x".repeat(exactPayloadBytes))),
        )
        store.deleteBundle("session-1", "capture-exact")

        assertFailsWith<RuntimeEvidenceArtifactLimitException> {
            store.commit(
                "session-1",
                "capture-exact",
                listOf(input(RuntimeEvidenceType.TRACE_ARTIFACT, "trace.txt", "x".repeat(exactPayloadBytes + 1))),
            )
        }
        assertFailsWith<RuntimeEvidenceArtifactLimitException> {
            store.commit(
                "session-1",
                "logcat-over",
                listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "x".repeat(512 * 1024 + 1))),
            )
        }
    }

    @Test
    fun enforcesPerFileLimitAfterRedactionExpansion() = withRoot { root ->
        val store = FileRuntimeEvidenceArtifactStore(
            root,
            RuntimeEvidenceRedactor(additionalPatterns = listOf("x")),
        )

        val failure = assertFailsWith<RuntimeEvidenceArtifactLimitException> {
            store.commit(
                "session-1",
                "capture-expanded",
                listOf(input(RuntimeEvidenceType.MEMORY_SUMMARY, "memory.txt", "x".repeat(64 * 1024))),
            )
        }

        assertTrue("MEMORY_SUMMARY" in failure.message.orEmpty())
        assertFalse(File(root, ".fixthis/runtime-evidence/session-1/capture-expanded").exists())
    }

    @Test
    fun enforcesTwoHundredFiftyMiBProjectQuotaAtBoundary() = withRoot { root ->
        val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())
        val calibration = store.commit(
            "session-1",
            "capture-exact",
            listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "ok")),
        )
        val committedBytes = File(root, calibration.relativeDirectory).walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        store.deleteSession("session-1")
        val evidenceRoot = File(root, ".fixthis/runtime-evidence/existing/capture").apply { mkdirs() }
        val filler = File(evidenceRoot, "filler.bin")
        val fillerBytes = 250L * 1024L * 1024L - committedBytes
        RandomAccessFile(filler, "rw").use { it.setLength(fillerBytes) }
        assertEquals(fillerBytes, filler.length())

        store.commit(
            "session-1",
            "capture-exact",
            listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "ok")),
        )
        val evidenceBytesAtBoundary = File(root, ".fixthis/runtime-evidence").walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        assertEquals(250L * 1024L * 1024L, evidenceBytesAtBoundary)

        assertFailsWith<RuntimeEvidenceArtifactQuotaException> {
            store.commit(
                "session-1",
                "capture-over",
                listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "x")),
            )
        }
    }

    @Test
    fun quotaAccountingOverflowFailsClosedWithoutAllocatingHugeFiles() = withRoot { root ->
        val store = FileRuntimeEvidenceArtifactStore(
            projectRoot = root,
            redactor = RuntimeEvidenceRedactor(),
            hooks = RuntimeEvidenceArtifactStoreHooks(quotaByteCounter = { Long.MAX_VALUE }),
        )

        val failure = assertFailsWith<RuntimeEvidenceArtifactQuotaException> {
            store.commit(
                "session-1",
                "capture-overflow",
                listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "small")),
            )
        }
        assertTrue("overflow" in failure.message.orEmpty())
        assertFalse(File(root, ".fixthis/runtime-evidence/session-1/capture-overflow").exists())
    }

    @Test
    fun quotaTreeScanOverflowFailsClosedWithoutHugeAllocation() = withRoot { root ->
        val existing = File(root, ".fixthis/runtime-evidence/existing").apply { mkdirs() }
        File(existing, "first.bin").writeText("first")
        File(existing, "second.bin").writeText("second")
        val store = FileRuntimeEvidenceArtifactStore(
            projectRoot = root,
            redactor = RuntimeEvidenceRedactor(),
            hooks = RuntimeEvidenceArtifactStoreHooks(
                fileSizeReader = { path ->
                    when (path.fileName.toString()) {
                        "first.bin" -> Long.MAX_VALUE
                        "second.bin" -> 1L
                        else -> 0L
                    }
                },
            ),
        )

        val failure = assertFailsWith<RuntimeEvidenceArtifactQuotaException> {
            store.commit(
                "session-1",
                "capture-scan-overflow",
                listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "small")),
            )
        }
        assertTrue("scan overflow" in failure.message.orEmpty())
        assertFalse(File(root, ".fixthis/runtime-evidence/session-1/capture-scan-overflow").exists())
    }

    @Test
    fun concurrentStoreInstancesCannotBothConsumeLastQuotaSlot() = withRoot { root ->
        val quotaCounter = calibratedQuotaCounter(root)

        val firstAtQuotaCheck = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<Throwable?>())
        val firstStore = concurrentQuotaStore(root, quotaCounter) {
            firstAtQuotaCheck.countDown()
            check(releaseFirst.await(5, TimeUnit.SECONDS))
        }
        val secondStore = concurrentQuotaStore(root, quotaCounter)

        val first = thread(name = "runtime-evidence-first") {
            failures += runCatching {
                firstStore.commit(
                    "session-1",
                    "capture-a",
                    listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "payload")),
                )
            }.exceptionOrNull()
        }
        assertTrue(firstAtQuotaCheck.await(5, TimeUnit.SECONDS))
        val second = thread(name = "runtime-evidence-second") {
            failures += runCatching {
                secondStore.commit(
                    "session-1",
                    "capture-b",
                    listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "payload")),
                )
            }.exceptionOrNull()
            secondFinished.countDown()
        }
        val release = thread(name = "runtime-evidence-release") {
            secondFinished.await(1, TimeUnit.SECONDS)
            releaseFirst.countDown()
        }

        first.join(10_000)
        second.join(10_000)
        release.join(10_000)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertEquals(1, failures.count { it == null })
        assertEquals(1, failures.count { it is RuntimeEvidenceArtifactQuotaException })
        assertEquals(
            250L * 1024L * 1024L,
            quotaCounter(File(root, ".fixthis/runtime-evidence")),
        )
    }

    @Test
    fun writeFailureLeavesNeitherPartialPathNorTempDirectory() = withRoot { root ->
        val store = FileRuntimeEvidenceArtifactStore(
            projectRoot = root,
            redactor = RuntimeEvidenceRedactor(),
            hooks = RuntimeEvidenceArtifactStoreHooks(
                beforeWrite = { file -> if (file.name == "memory-summary.txt") error("disk full") },
            ),
        )

        assertFailsWith<IllegalStateException> {
            store.commit(
                "session-1",
                "capture-1",
                listOf(
                    input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "first"),
                    input(RuntimeEvidenceType.MEMORY_SUMMARY, "memory-summary.txt", "second"),
                ),
            )
        }

        val sessionRoot = File(root, ".fixthis/runtime-evidence/session-1")
        assertFalse(File(sessionRoot, "capture-1").exists())
        assertTrue(sessionRoot.listFiles().orEmpty().none { it.name.startsWith("capture-1.tmp-") })
    }

    @Test
    fun atomicMoveFailureHasNoNonAtomicSuccessFallback() = withRoot { root ->
        var moveAttempts = 0
        val store = FileRuntimeEvidenceArtifactStore(
            projectRoot = root,
            redactor = RuntimeEvidenceRedactor(),
            hooks = RuntimeEvidenceArtifactStoreHooks(
                beforeAtomicMove = { _, _ ->
                    moveAttempts += 1
                    error("atomic move unavailable")
                },
            ),
        )

        assertFailsWith<IllegalStateException> {
            store.commit(
                "session-1",
                "capture-1",
                listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "safe")),
            )
        }

        assertEquals(1, moveAttempts)
        val sessionRoot = File(root, ".fixthis/runtime-evidence/session-1")
        assertFalse(File(sessionRoot, "capture-1").exists())
        assertTrue(sessionRoot.listFiles().orEmpty().none { it.name.startsWith("capture-1.tmp-") })
    }

    @Test
    fun cleanupIncompleteDeletesOnlyTemporaryBundles() = withRoot { root ->
        val sessionRoot = File(root, ".fixthis/runtime-evidence/session-1")
        File(sessionRoot, "capture-1.tmp-${"a".repeat(32)}/logcat.txt").apply {
            parentFile.mkdirs()
            writeText("partial")
        }
        File(sessionRoot, "capture-2.tmp-${"b".repeat(32)}/manifest.json").apply {
            parentFile.mkdirs()
            writeText("partial")
        }
        File(sessionRoot, "capture-kept/manifest.json").apply {
            parentFile.mkdirs()
            writeText("committed")
        }
        File(sessionRoot, "release.tmp-final/manifest.json").apply {
            parentFile.mkdirs()
            writeText("committed")
        }
        val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())

        assertEquals(2, store.cleanupIncomplete())
        assertTrue(File(sessionRoot, "capture-kept").isDirectory)
        assertTrue(File(sessionRoot, "release.tmp-final").isDirectory)
        assertTrue(sessionRoot.listFiles().orEmpty().none { it.name.matches(Regex(".+\\.tmp-[0-9a-f]{32}")) })
    }

    @Test
    fun cleanupIncompleteUnlinksTemporarySymlinkWithoutFollowingIt() = withRoot { root ->
        val outside = Files.createTempDirectory("fixthis-runtime-temp-link-").toFile()
        try {
            val sentinel = File(outside, "sentinel.txt").apply { writeText("keep") }
            val sessionRoot = File(root, ".fixthis/runtime-evidence/session-1").apply { mkdirs() }
            val temporaryLink = File(sessionRoot, "capture.tmp-${"a".repeat(32)}")
            Files.createSymbolicLink(temporaryLink.toPath(), outside.toPath())
            val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())

            assertEquals(1, store.cleanupIncomplete())
            assertFalse(Files.exists(temporaryLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("keep", sentinel.readText())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun cleanupIncompleteUnlinksRootLevelSessionSymlinkPoison() = withRoot { root ->
        val outside = Files.createTempDirectory("fixthis-runtime-session-link-").toFile()
        try {
            val sentinel = File(outside, "sentinel.txt").apply { writeText("keep") }
            val evidenceRoot = File(root, ".fixthis/runtime-evidence").apply { mkdirs() }
            val sessionLink = File(evidenceRoot, "session-poison")
            Files.createSymbolicLink(sessionLink.toPath(), outside.toPath())
            val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())

            assertEquals(1, store.cleanupIncomplete())
            assertFalse(Files.exists(sessionLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("keep", sentinel.readText())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun orphanCleanupPreservesCaptureReferencedByReplayedEvent() = withRoot { root ->
        val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())
        store.commit("session-1", "event-before-snapshot", listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "keep")))
        store.commit("session-1", "bundle-without-event", listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "delete")))
        store.commit("session-2", "unreferenced", listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "delete")))

        val deleted = store.cleanupOrphans(mapOf("session-1" to setOf("event-before-snapshot")))

        assertEquals(2, deleted)
        assertTrue(File(root, ".fixthis/runtime-evidence/session-1/event-before-snapshot/manifest.json").isFile)
        assertFalse(File(root, ".fixthis/runtime-evidence/session-1/bundle-without-event").exists())
        assertFalse(File(root, ".fixthis/runtime-evidence/session-2/unreferenced").exists())
    }

    @Test
    fun cleanupOrphansUnlinksOrphanSymlinkWithoutFollowingIt() = withRoot { root ->
        val outside = Files.createTempDirectory("fixthis-runtime-orphan-link-").toFile()
        try {
            val sentinel = File(outside, "sentinel.txt").apply { writeText("keep") }
            val sessionRoot = File(root, ".fixthis/runtime-evidence/session-1").apply { mkdirs() }
            val orphanLink = File(sessionRoot, "orphan-capture")
            Files.createSymbolicLink(orphanLink.toPath(), outside.toPath())
            val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())

            assertEquals(1, store.cleanupOrphans(emptyMap()))
            assertFalse(Files.exists(orphanLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("keep", sentinel.readText())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun cleanupOrphansUnlinksNestedSymlinkWithoutFollowingIt() = withRoot { root ->
        val outside = Files.createTempDirectory("fixthis-runtime-nested-orphan-").toFile()
        try {
            val sentinel = File(outside, "sentinel.txt").apply { writeText("keep") }
            val orphan = File(root, ".fixthis/runtime-evidence/session-1/orphan").apply { mkdirs() }
            Files.createSymbolicLink(File(orphan, "nested-link").toPath(), outside.toPath())
            val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())

            assertEquals(1, store.cleanupOrphans(emptyMap()))
            assertFalse(orphan.exists())
            assertEquals("keep", sentinel.readText())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun deleteSessionUnlinksNestedSymlinkWithoutFollowingIt() = withRoot { root ->
        val outside = Files.createTempDirectory("fixthis-runtime-nested-session-").toFile()
        try {
            val sentinel = File(outside, "sentinel.txt").apply { writeText("keep") }
            val session = File(root, ".fixthis/runtime-evidence/session-1/capture-1").apply { mkdirs() }
            Files.createSymbolicLink(File(session, "nested-link").toPath(), outside.toPath())
            val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())

            store.deleteSession("session-1")
            assertFalse(File(root, ".fixthis/runtime-evidence/session-1").exists())
            assertEquals("keep", sentinel.readText())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun deleteBundleAndDeleteSessionAreScoped() = withRoot { root ->
        val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())
        store.commit("session-1", "capture-1", listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "one")))
        store.commit("session-1", "capture-2", listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "two")))
        store.commit("session-2", "capture-1", listOf(input(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "three")))

        store.deleteBundle("session-1", "capture-1")
        assertFalse(File(root, ".fixthis/runtime-evidence/session-1/capture-1").exists())
        assertTrue(File(root, ".fixthis/runtime-evidence/session-1/capture-2").isDirectory)
        assertTrue(File(root, ".fixthis/runtime-evidence/session-2/capture-1").isDirectory)

        store.deleteSession("session-1")
        assertFalse(File(root, ".fixthis/runtime-evidence/session-1").exists())
        assertTrue(File(root, ".fixthis/runtime-evidence/session-2/capture-1").isDirectory)
    }

    @Test
    fun duplicateTypesAndFileNamesAreRejected() = withRoot { root ->
        val store = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())

        assertFailsWith<IllegalArgumentException> {
            store.commit(
                "session-1",
                "capture-1",
                listOf(
                    input(RuntimeEvidenceType.LOGCAT_WINDOW, "one.txt", "one"),
                    input(RuntimeEvidenceType.LOGCAT_WINDOW, "two.txt", "two"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            store.commit(
                "session-1",
                "capture-2",
                listOf(
                    input(RuntimeEvidenceType.LOGCAT_WINDOW, "same.txt", "one"),
                    input(RuntimeEvidenceType.MEMORY_SUMMARY, "same.txt", "two"),
                ),
            )
        }
    }

    private fun input(
        type: RuntimeEvidenceType,
        fileName: String,
        text: String,
    ): RuntimeEvidenceArtifactInput = RuntimeEvidenceArtifactInput(type, fileName, text)

    private inline fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("fixthis-runtime-store-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}

private fun calibratedQuotaCounter(root: File): (File) -> Long {
    val calibrationStore = FileRuntimeEvidenceArtifactStore(root, RuntimeEvidenceRedactor())
    val calibration = calibrationStore.commit(
        "session-1",
        "capture-a",
        listOf(runtimeEvidenceInput(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "payload")),
    )
    val bundleBytes = File(root, calibration.relativeDirectory).walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }
    calibrationStore.deleteSession("session-1")
    val simulatedExistingBytes = 250L * 1024L * 1024L - bundleBytes
    return { evidenceRoot ->
        simulatedExistingBytes + evidenceRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

private fun concurrentQuotaStore(
    root: File,
    quotaCounter: (File) -> Long,
    beforeQuotaCheck: () -> Unit = {},
): FileRuntimeEvidenceArtifactStore = FileRuntimeEvidenceArtifactStore(
    root,
    RuntimeEvidenceRedactor(),
    RuntimeEvidenceArtifactStoreHooks(
        quotaByteCounter = quotaCounter,
        beforeQuotaCheck = beforeQuotaCheck,
    ),
)

private fun runtimeEvidenceInput(
    type: RuntimeEvidenceType,
    fileName: String,
    text: String,
): RuntimeEvidenceArtifactInput = RuntimeEvidenceArtifactInput(type, fileName, text)
