package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionException
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationArtifactStore
import io.github.beyondwin.fixthis.mcp.session.verification.PreparedVerificationArtifact
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackVerificationArtifactStoreTest {
    @Test
    fun promotesExactlyOnePngIntoReceiptDirectoryAndSanitizesHostPaths() {
        withFixture { root, store, session ->
            val sourcePng = pngFile(root, "capture/source.png")

            val prepared = store.prepare(
                session,
                "receipt-1",
                SnapshotScreenshotDto(
                    fullPath = "/device/full.png",
                    cropPath = "/device/crop.png",
                    desktopFullPath = sourcePng.absolutePath,
                    desktopCropPath = root.resolve("capture/crop.png").absolutePath,
                    width = 720,
                    height = 1600,
                ),
            )
            val promoted = store.promote(prepared)
            val finalFile = root.resolve(
                ".fixthis/feedback-sessions/session-1/verification/receipt-1/after.png",
            ).canonicalFile

            assertEquals(finalFile.path, promoted?.desktopFullPath)
            assertEquals(720, promoted?.width)
            assertEquals(1600, promoted?.height)
            assertNull(promoted?.fullPath)
            assertNull(promoted?.cropPath)
            assertNull(promoted?.desktopCropPath)
            assertEquals(PNG_BYTES.toList(), finalFile.readBytes().toList())
            assertEquals(listOf("after.png"), finalFile.parentFile.list().orEmpty().sorted())
            assertFalse(prepared.temporaryDirectory.exists())
        }
    }

    @Test
    fun promotesReceiptWithoutScreenshotAsEmptyArtifactDirectory() {
        withFixture { root, store, session ->
            val prepared = store.prepare(session, "receipt-1", null)

            assertNull(store.promote(prepared))

            val finalDirectory = receiptDirectory(root, "session-1", "receipt-1")
            assertTrue(finalDirectory.isDirectory)
            assertTrue(finalDirectory.list().orEmpty().isEmpty())
            assertFalse(prepared.temporaryDirectory.exists())
        }
    }

    @Test
    fun rejectsUnsafeSegmentsAndMismatchedCanonicalProjectRoot() {
        withFixture { root, store, session ->
            val sourcePng = pngFile(root, "capture/source.png")
            val source = SnapshotScreenshotDto(desktopFullPath = sourcePng.absolutePath)
            val outsideRoot = Files.createTempDirectory("fixthis-verification-outside").toFile()
            try {
                val outsidePng = pngFile(outsideRoot, "outside.png")
                listOf("", ".", "..", "../escape", "nested/receipt", "nested\\receipt").forEach { receiptId ->
                    assertFailsWith<FeedbackSessionException> {
                        store.prepare(session, receiptId, source)
                    }
                }
                assertFailsWith<FeedbackSessionException> {
                    store.prepare(session.copy(sessionId = "../session"), "receipt-1", source)
                }
                assertFailsWith<FeedbackSessionException> {
                    store.prepare(
                        session.copy(projectRoot = outsideRoot.canonicalPath),
                        "receipt-1",
                        source,
                    )
                }
                assertFailsWith<FeedbackSessionException> {
                    store.prepare(
                        session,
                        "receipt-outside-source",
                        SnapshotScreenshotDto(desktopFullPath = outsidePng.absolutePath),
                    )
                }

                assertFalse(root.resolve(".fixthis/feedback-sessions/escape").exists())
                assertFalse(outsideRoot.resolve(".fixthis").exists())
            } finally {
                outsideRoot.deleteRecursively()
            }
        }
    }

    @Test
    fun rejectsSymlinkDirectoryAndNonPngSourcesWithoutStagingResidue() {
        withFixture { root, store, session ->
            val captureRoot = root.resolve("capture").apply { mkdirs() }
            val sourcePng = pngFile(root, "outside/source.png")
            val symlink = captureRoot.resolve("linked.png")
            Files.createSymbolicLink(symlink.toPath(), sourcePng.toPath())
            val directoryNamedPng = captureRoot.resolve("directory.png").apply { mkdirs() }
            val textFile = captureRoot.resolve("source.txt").apply { writeText("not png") }

            listOf(symlink, directoryNamedPng, textFile).forEachIndexed { index, source ->
                assertFailsWith<FeedbackSessionException> {
                    store.prepare(
                        session,
                        "receipt-$index",
                        SnapshotScreenshotDto(desktopFullPath = source.absolutePath),
                    )
                }
            }

            val verificationRoot = root.resolve(
                ".fixthis/feedback-sessions/session-1/verification",
            )
            assertTrue(
                verificationRoot.listFiles().orEmpty().none {
                    it.name.startsWith(".receipt-") || it.name.startsWith("receipt-")
                },
            )
            assertTrue(sourcePng.isFile)
        }
    }

    @Test
    fun promotionFailureDeletesOnlyPreparedAndFinalReceiptEntriesWithoutFollowingLinks() {
        withFixture { root, store, session ->
            val sourcePng = pngFile(root, "capture/source.png")
            val prepared = store.prepare(
                session,
                "receipt-1",
                SnapshotScreenshotDto(desktopFullPath = sourcePng.absolutePath),
            )
            val outsideDirectory = root.resolve("outside-target").apply { mkdirs() }
            val outsideSentinel = outsideDirectory.resolve("keep.txt").apply { writeText("keep") }
            Files.createSymbolicLink(prepared.finalDirectory.toPath(), outsideDirectory.toPath())

            assertFailsWith<FeedbackSessionException> {
                store.promote(prepared)
            }

            assertFalse(prepared.temporaryDirectory.exists())
            assertFalse(Files.exists(prepared.finalDirectory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue(outsideSentinel.isFile)
        }
    }

    @Test
    fun discardAndReceiptDeletionUnlinkOnlyTheirExactEntries() {
        withFixture { root, store, session ->
            val prepared = store.prepare(session, "receipt-temp", null)
            val sibling = prepared.temporaryDirectory.parentFile.resolve("receipt-kept").apply {
                mkdirs()
                resolve("after.png").writeBytes(PNG_BYTES)
            }

            store.discard(prepared)

            assertFalse(prepared.temporaryDirectory.exists())
            assertTrue(sibling.isDirectory)

            val outsideDirectory = root.resolve("outside-target").apply { mkdirs() }
            val outsideSentinel = outsideDirectory.resolve("keep.txt").apply { writeText("keep") }
            val receiptLink = receiptDirectory(root, "session-1", "receipt-link")
            Files.createSymbolicLink(receiptLink.toPath(), outsideDirectory.toPath())

            store.deleteReceiptArtifact(session, "receipt-link")

            assertFalse(Files.exists(receiptLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue(outsideSentinel.isFile)
            assertTrue(sibling.isDirectory)
        }
    }

    @Test
    fun malformedPreparedArtifactCannotExpandRollbackDeletionScope() {
        withFixture { root, store, _ ->
            val unrelatedTemporary = root.canonicalFile.resolve("unrelated-temporary").apply {
                mkdirs()
                resolve("keep.txt").writeText("keep")
            }
            val unrelatedFinal = root.canonicalFile.resolve("unrelated-final").apply {
                mkdirs()
                resolve("keep.txt").writeText("keep")
            }
            val malformed = PreparedVerificationArtifact(
                sessionId = "session-1",
                receiptId = "receipt-1",
                temporaryDirectory = unrelatedTemporary,
                finalDirectory = unrelatedFinal,
                stagedFile = null,
                sourceMetadata = null,
            )

            assertFailsWith<FeedbackSessionException> {
                store.promote(malformed)
            }

            assertTrue(unrelatedTemporary.resolve("keep.txt").isFile)
            assertTrue(unrelatedFinal.resolve("keep.txt").isFile)
        }
    }

    @Test
    fun cleanupRemovesIncompleteAndOrphansWhileReferencedReceiptsSurvive() {
        withFixture { root, store, _ ->
            val verificationRoot = root.resolve(
                ".fixthis/feedback-sessions/session-1/verification",
            ).apply { mkdirs() }
            val kept = verificationRoot.resolve("receipt-kept").apply {
                mkdirs()
                resolve("after.png").writeBytes(PNG_BYTES)
            }
            val orphan = verificationRoot.resolve("receipt-orphan").apply { mkdirs() }
            val incomplete = verificationRoot.resolve(".receipt-new.tmp-0123456789abcdef0123456789abcdef").apply {
                mkdirs()
                resolve("after.png").writeBytes(PNG_BYTES)
            }
            val otherSessionKept = receiptDirectory(root, "session-2", "receipt-other").apply { mkdirs() }

            assertEquals(1, store.cleanupIncomplete())
            assertEquals(
                1,
                store.cleanupOrphans(
                    mapOf(
                        "session-1" to setOf("receipt-kept"),
                        "session-2" to setOf("receipt-other"),
                    ),
                ),
            )

            assertTrue(kept.isDirectory)
            assertTrue(otherSessionKept.isDirectory)
            assertFalse(orphan.exists())
            assertFalse(incomplete.exists())
        }
    }

    private fun withFixture(
        block: (File, FeedbackVerificationArtifactStore, SessionDto) -> Unit,
    ) {
        val root = Files.createTempDirectory("fixthis-verification-artifacts").toFile()
        try {
            val store = FeedbackVerificationArtifactStore(root)
            val session = SessionDto(
                sessionId = "session-1",
                packageName = "sample.app",
                projectRoot = root.canonicalPath,
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 2L,
            )
            block(root, store, session)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun pngFile(root: File, relativePath: String): File = root.resolve(relativePath).apply {
        parentFile.mkdirs()
        writeBytes(PNG_BYTES)
    }

    private fun receiptDirectory(root: File, sessionId: String, receiptId: String): File = root.resolve(
        ".fixthis/feedback-sessions/$sessionId/verification/$receiptId",
    )

    private companion object {
        val PNG_BYTES = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4e,
            0x47,
            0x0d,
            0x0a,
            0x1a,
            0x0a,
        )
    }
}
