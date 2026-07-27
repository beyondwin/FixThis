package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationArtifactException
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationArtifactStore
import io.github.beyondwin.fixthis.mcp.session.verification.PreparedVerificationArtifact
import io.github.beyondwin.fixthis.mcp.session.verification.PreparedVerificationArtifactData
import io.github.beyondwin.fixthis.mcp.session.verification.VerificationArtifactStoreHooks
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass")
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
    fun discardAfterSuccessfulPromotionPreservesFinalArtifact() {
        withFixture { root, store, session ->
            val prepared = store.prepare(
                session,
                "receipt-1",
                SnapshotScreenshotDto(
                    desktopFullPath = pngFile(root, "capture/source.png").absolutePath,
                ),
            )
            val promoted = store.promote(prepared)

            store.discard(prepared)

            val finalFile = File(promoted?.desktopFullPath.orEmpty())
            assertTrue(finalFile.isFile)
            assertEquals(PNG_BYTES.toList(), finalFile.readBytes().toList())
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
                    assertFailsWith<FeedbackVerificationArtifactException> {
                        store.prepare(session, receiptId, source)
                    }
                }
                assertFailsWith<FeedbackVerificationArtifactException> {
                    store.prepare(session.copy(sessionId = "../session"), "receipt-1", source)
                }
                assertFailsWith<FeedbackVerificationArtifactException> {
                    store.prepare(
                        session.copy(projectRoot = outsideRoot.canonicalPath),
                        "receipt-1",
                        source,
                    )
                }
                assertFailsWith<FeedbackVerificationArtifactException> {
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
    fun rejectsSessionAndReceiptIdsInReservedArtifactNamespaces() {
        withFixture { root, store, session ->
            val token = "0123456789abcdef0123456789abcdef"
            listOf(".x.reserve", ".x.tmp-$token").forEach { receiptId ->
                assertFailsWith<FeedbackVerificationArtifactException> {
                    store.prepare(session, receiptId, null)
                }
            }
            listOf(".x.reserve", ".x.tmp-$token").forEach { sessionId ->
                assertFailsWith<FeedbackVerificationArtifactException> {
                    store.prepare(session.copy(sessionId = sessionId), "receipt-1", null)
                }
            }

            assertFalse(root.resolve(".fixthis/feedback-sessions/.x.reserve").exists())
            assertFalse(root.resolve(".fixthis/feedback-sessions/.x.tmp-$token").exists())
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
                assertFailsWith<FeedbackVerificationArtifactException> {
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

            assertFailsWith<FeedbackVerificationArtifactException> {
                store.promote(prepared)
            }

            assertFalse(prepared.temporaryDirectory.exists())
            assertTrue(Files.isSymbolicLink(prepared.finalDirectory.toPath()))
            assertTrue(outsideSentinel.isFile)
            Files.delete(prepared.finalDirectory.toPath())
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
            val malformed = PreparedVerificationArtifact.issue(
                storeCapability = Any(),
                data = PreparedVerificationArtifactData(
                    sessionId = "session-1",
                    receiptId = "receipt-1",
                    ownershipToken = "0123456789abcdef0123456789abcdef",
                    temporaryDirectory = unrelatedTemporary,
                    finalDirectory = unrelatedFinal,
                    stagedFile = null,
                    sourceMetadata = null,
                ),
            )

            assertFailsWith<FeedbackVerificationArtifactException> {
                store.promote(malformed)
            }

            assertTrue(unrelatedTemporary.resolve("keep.txt").isFile)
            assertTrue(unrelatedFinal.resolve("keep.txt").isFile)
        }
    }

    @Test
    fun separateStoresCannotReserveTheSameReceipt() {
        withFixture { root, firstStore, session ->
            val secondStore = FeedbackVerificationArtifactStore(root)
            val first = firstStore.prepare(session, "receipt-1", null)

            assertFailsWith<FeedbackVerificationArtifactException> {
                secondStore.prepare(session, "receipt-1", null)
            }

            assertTrue(first.temporaryDirectory.isDirectory)
            firstStore.discard(first)
        }
    }

    @Test
    fun promotionCollisionDoesNotDeleteTheFirstStoresArtifact() {
        withFixture { root, firstStore, session ->
            val secondStore = FeedbackVerificationArtifactStore(root)
            val sourcePng = pngFile(root, "capture/source.png")
            val screenshot = SnapshotScreenshotDto(desktopFullPath = sourcePng.absolutePath)
            val first = firstStore.prepare(session, "receipt-1", screenshot)
            val firstResult = firstStore.promote(first)
            val second = secondStore.prepare(session, "receipt-1", screenshot)

            assertFailsWith<FeedbackVerificationArtifactException> {
                secondStore.promote(second)
            }

            val promoted = File(firstResult?.desktopFullPath.orEmpty())
            assertTrue(promoted.isFile)
            assertEquals(PNG_BYTES.toList(), promoted.readBytes().toList())
        }
    }

    @Test
    fun forgedOwnerMarkerCannotAuthorizeCollisionRollbackOfAnotherArtifact() {
        withFixture { root, firstStore, session ->
            val sourcePng = pngFile(root, "capture/source.png")
            val screenshot = SnapshotScreenshotDto(desktopFullPath = sourcePng.absolutePath)
            val first = firstStore.prepare(session, "receipt-1", screenshot)
            val firstResult = firstStore.promote(first)
            val secondStore = FeedbackVerificationArtifactStore(root)
            val second = secondStore.prepare(session, "receipt-1", screenshot)
            val secondToken = second.temporaryDirectory.name.substringAfter(".tmp-")
            second.finalDirectory.resolve(".owner-$secondToken").writeText(secondToken)

            assertFailsWith<FeedbackVerificationArtifactException> {
                secondStore.promote(second)
            }

            val promoted = File(firstResult?.desktopFullPath.orEmpty())
            assertTrue(promoted.isFile)
            assertEquals(PNG_BYTES.toList(), promoted.readBytes().toList())
        }
    }

    @Test
    fun preparedTemporaryDirectoryMustBelongToItsReceiptId() {
        withFixture { root, store, session ->
            val sourcePng = pngFile(root, "capture/source.png")
            val original = store.prepare(
                session,
                "receipt-original",
                SnapshotScreenshotDto(desktopFullPath = sourcePng.absolutePath),
            )
            val sibling = PreparedVerificationArtifact.issue(
                storeCapability = Any(),
                data = PreparedVerificationArtifactData(
                    sessionId = original.sessionId,
                    receiptId = "receipt-sibling",
                    ownershipToken = original.ownershipToken,
                    temporaryDirectory = original.temporaryDirectory,
                    finalDirectory = receiptDirectory(root.canonicalFile, "session-1", "receipt-sibling"),
                    stagedFile = original.stagedFile,
                    sourceMetadata = original.sourceMetadata,
                ),
            )

            assertFailsWith<FeedbackVerificationArtifactException> {
                store.promote(sibling)
            }

            assertFalse(receiptDirectory(root, "session-1", "receipt-sibling").exists())
            assertTrue(original.temporaryDirectory.exists())
            store.discard(original)
        }
    }

    @Test
    fun sourceParentSwapAfterSecureOpenFailsClosedWithoutReadingOutside() {
        val root = Files.createTempDirectory("fixthis-verification-source-swap").toFile().canonicalFile
        val outside = Files.createTempDirectory("fixthis-verification-source-outside").toFile().canonicalFile
        val sourceDirectory = root.resolve("capture").apply { mkdirs() }
        val displacedSourceDirectory = root.resolve("capture-displaced")
        val originalSource = pngFile(root, "capture/source.png")
        val outsideSource = pngFile(outside, "source.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var swapped = false
        val store = FeedbackVerificationArtifactStore(
            root,
            hooks = VerificationArtifactStoreHooks(
                afterSourceParentOpened = {
                    Files.move(sourceDirectory.toPath(), displacedSourceDirectory.toPath())
                    Files.createSymbolicLink(sourceDirectory.toPath(), outside.toPath())
                    swapped = true
                },
            ),
        )
        val session = session(root)
        try {
            assertFailsWith<FeedbackVerificationArtifactException> {
                store.prepare(
                    session,
                    "receipt-1",
                    SnapshotScreenshotDto(desktopFullPath = originalSource.absolutePath),
                )
            }

            assertTrue(swapped)
            assertEquals(listOf(1, 2, 3), outsideSource.readBytes().map(Byte::toInt))
            assertFalse(receiptDirectory(root, "session-1", "receipt-1").exists())
        } finally {
            if (Files.isSymbolicLink(sourceDirectory.toPath())) Files.delete(sourceDirectory.toPath())
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun temporaryDirectorySwapAfterSecureOpenFailsClosedWithoutWritingOutside() {
        val root = Files.createTempDirectory("fixthis-verification-temp-swap").toFile().canonicalFile
        val outside = Files.createTempDirectory("fixthis-verification-temp-outside").toFile().canonicalFile
        val displaced = root.resolve("displaced-temp")
        var swapped = false
        val store = FeedbackVerificationArtifactStore(
            root,
            hooks = VerificationArtifactStoreHooks(
                afterTemporaryDirectoryOpened = { temporary ->
                    Files.move(temporary, displaced.toPath())
                    Files.createSymbolicLink(temporary, outside.toPath())
                    swapped = true
                },
            ),
        )
        val session = session(root)
        val source = pngFile(root, "capture/source.png")
        try {
            assertFailsWith<FeedbackVerificationArtifactException> {
                store.prepare(
                    session,
                    "receipt-1",
                    SnapshotScreenshotDto(desktopFullPath = source.absolutePath),
                )
            }

            assertTrue(swapped)
            assertFalse(outside.resolve("after.png").exists())
            assertFalse(receiptDirectory(root, "session-1", "receipt-1").exists())
        } finally {
            val verificationRoot = root.resolve(".fixthis/feedback-sessions/session-1/verification")
            verificationRoot.listFiles().orEmpty()
                .filter { Files.isSymbolicLink(it.toPath()) }
                .forEach { Files.deleteIfExists(it.toPath()) }
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun verificationRootSwapAfterSecureOpenFailsClosedWithoutPromotingOutside() {
        val root = Files.createTempDirectory("fixthis-verification-root-swap").toFile().canonicalFile
        val outside = Files.createTempDirectory("fixthis-verification-root-outside").toFile().canonicalFile
        val verificationRoot = root.resolve(".fixthis/feedback-sessions/session-1/verification")
        val displacedVerificationRoot = verificationRoot.parentFile.resolve("verification-displaced")
        val outsideSentinel = outside.resolve("keep.txt").apply { writeText("keep") }
        var swapped = false
        val store = FeedbackVerificationArtifactStore(
            root,
            hooks = VerificationArtifactStoreHooks(
                beforePromotion = {
                    Files.move(verificationRoot.toPath(), displacedVerificationRoot.toPath())
                    Files.createSymbolicLink(verificationRoot.toPath(), outside.toPath())
                    swapped = true
                },
            ),
        )
        val session = session(root)
        val source = pngFile(root, "capture/source.png")
        val prepared = store.prepare(
            session,
            "receipt-1",
            SnapshotScreenshotDto(desktopFullPath = source.absolutePath),
        )
        try {
            assertFailsWith<FeedbackVerificationArtifactException> {
                store.promote(prepared)
            }

            assertTrue(swapped)
            assertTrue(outsideSentinel.isFile)
            assertFalse(outside.resolve("receipt-1").exists())
        } finally {
            if (Files.isSymbolicLink(verificationRoot.toPath())) Files.delete(verificationRoot.toPath())
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun fallbackSourceOpenGapFailsBeforeReadingFromSubstitutedParent() {
        val root = Files.createTempDirectory("fixthis-verification-source-open-gap").toFile().canonicalFile
        val outside = Files.createTempDirectory("fixthis-verification-source-open-outside").toFile().canonicalFile
        val sourceDirectory = root.resolve("capture").apply { mkdirs() }
        val displaced = root.resolve("capture-displaced")
        val source = pngFile(root, "capture/source.png")
        val outsideSource = pngFile(outside, "source.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var swapped = false
        val store = FeedbackVerificationArtifactStore(
            root,
            hooks = VerificationArtifactStoreHooks(
                afterFallbackFileOpenedBeforeValidation = { opened ->
                    if (!swapped && opened.fileName.toString() == "source.png") {
                        Files.move(sourceDirectory.toPath(), displaced.toPath())
                        Files.createSymbolicLink(sourceDirectory.toPath(), outside.toPath())
                        swapped = true
                    }
                },
                rootDirectoryStreamFactory = ::nonSecureDirectoryStream,
            ),
        )
        try {
            assertFailsWith<FeedbackVerificationArtifactException> {
                store.prepare(
                    session(root),
                    "receipt-1",
                    SnapshotScreenshotDto(desktopFullPath = source.absolutePath),
                )
            }

            assertTrue(swapped)
            assertEquals(listOf(1, 2, 3), outsideSource.readBytes().map(Byte::toInt))
            assertFalse(receiptDirectory(root, "session-1", "receipt-1").exists())
        } finally {
            if (Files.isSymbolicLink(sourceDirectory.toPath())) Files.delete(sourceDirectory.toPath())
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun fallbackCreateGapFailsBeforeWritingIntoSubstitutedTemporaryDirectory() {
        val root = Files.createTempDirectory("fixthis-verification-create-gap").toFile().canonicalFile
        val outside = Files.createTempDirectory("fixthis-verification-create-outside").toFile().canonicalFile
        val displaced = root.resolve("displaced-temp")
        var swapped = false
        val store = FeedbackVerificationArtifactStore(
            root,
            hooks = VerificationArtifactStoreHooks(
                beforeFallbackFileCreate = { target ->
                    if (!swapped && target.fileName.toString() == "after.png") {
                        val temporary = target.parent
                        Files.move(temporary, displaced.toPath())
                        Files.createSymbolicLink(temporary, outside.toPath())
                        swapped = true
                    }
                },
                rootDirectoryStreamFactory = ::nonSecureDirectoryStream,
            ),
        )
        try {
            assertFailsWith<FeedbackVerificationArtifactException> {
                store.prepare(
                    session(root),
                    "receipt-1",
                    SnapshotScreenshotDto(desktopFullPath = pngFile(root, "capture/source.png").absolutePath),
                )
            }

            assertTrue(swapped)
            assertFalse(outside.resolve("after.png").exists())
            assertFalse(receiptDirectory(root, "session-1", "receipt-1").exists())
        } finally {
            val verification = root.resolve(".fixthis/feedback-sessions/session-1/verification")
            verification.listFiles().orEmpty()
                .filter { Files.isSymbolicLink(it.toPath()) }
                .forEach { Files.deleteIfExists(it.toPath()) }
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun fallbackDirectoryCreateGapFailsBeforeCreatingUnderSubstitutedVerificationRoot() {
        val root = Files.createTempDirectory("fixthis-verification-directory-create-gap").toFile().canonicalFile
        val outside = Files.createTempDirectory("fixthis-verification-directory-create-outside").toFile().canonicalFile
        val verification = root.resolve(".fixthis/feedback-sessions/session-1/verification")
        val displaced = verification.parentFile.resolve("verification-displaced")
        var swapped = false
        val store = FeedbackVerificationArtifactStore(
            root,
            hooks = VerificationArtifactStoreHooks(
                beforeFallbackDirectoryCreate = { target ->
                    if (!swapped && target.fileName.toString().startsWith(".receipt-1.tmp-")) {
                        Files.move(verification.toPath(), displaced.toPath())
                        Files.createSymbolicLink(verification.toPath(), outside.toPath())
                        swapped = true
                    }
                },
                rootDirectoryStreamFactory = ::nonSecureDirectoryStream,
            ),
        )
        try {
            assertFailsWith<FeedbackVerificationArtifactException> {
                store.prepare(session(root), "receipt-1", null)
            }

            assertTrue(swapped)
            assertTrue(outside.listFiles().orEmpty().isEmpty())
        } finally {
            if (Files.isSymbolicLink(verification.toPath())) Files.delete(verification.toPath())
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun fallbackDeleteGapFailsBeforeDeletingThroughSubstitutedTemporaryDirectory() {
        val root = Files.createTempDirectory("fixthis-verification-delete-gap").toFile().canonicalFile
        val outside = Files.createTempDirectory("fixthis-verification-delete-outside").toFile().canonicalFile
        val outsideSentinel = outside.resolve("after.png").apply { writeText("keep") }
        val displaced = root.resolve("displaced-temp")
        var prepared: PreparedVerificationArtifact? = null
        var swapped = false
        val store = FeedbackVerificationArtifactStore(
            root,
            hooks = VerificationArtifactStoreHooks(
                beforeFallbackDelete = { target ->
                    val current = prepared
                    if (!swapped && current != null && target.fileName.toString() == "after.png") {
                        Files.move(current.temporaryDirectory.toPath(), displaced.toPath())
                        Files.createSymbolicLink(current.temporaryDirectory.toPath(), outside.toPath())
                        swapped = true
                    }
                },
                rootDirectoryStreamFactory = ::nonSecureDirectoryStream,
            ),
        )
        try {
            prepared = store.prepare(
                session(root),
                "receipt-1",
                SnapshotScreenshotDto(desktopFullPath = pngFile(root, "capture/source.png").absolutePath),
            )

            assertFailsWith<FeedbackVerificationArtifactException> {
                store.discard(checkNotNull(prepared))
            }

            assertTrue(swapped)
            assertEquals("keep", outsideSentinel.readText())
        } finally {
            prepared?.temporaryDirectory?.toPath()?.takeIf(Files::isSymbolicLink)?.let(Files::delete)
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun fallbackMoveGapFailsBeforeMovingIntoSubstitutedVerificationRoot() {
        val root = Files.createTempDirectory("fixthis-verification-move-gap").toFile().canonicalFile
        val outside = Files.createTempDirectory("fixthis-verification-move-outside").toFile().canonicalFile
        val outsideSentinel = outside.resolve("keep.txt").apply { writeText("keep") }
        val verification = root.resolve(".fixthis/feedback-sessions/session-1/verification")
        val displaced = verification.parentFile.resolve("verification-displaced")
        var swapped = false
        val store = FeedbackVerificationArtifactStore(
            root,
            hooks = VerificationArtifactStoreHooks(
                beforeFallbackMove = { _, _ ->
                    if (!swapped) {
                        Files.move(verification.toPath(), displaced.toPath())
                        Files.createSymbolicLink(verification.toPath(), outside.toPath())
                        swapped = true
                    }
                },
                rootDirectoryStreamFactory = ::nonSecureDirectoryStream,
            ),
        )
        val prepared = store.prepare(
            session(root),
            "receipt-1",
            SnapshotScreenshotDto(desktopFullPath = pngFile(root, "capture/source.png").absolutePath),
        )
        try {
            assertFailsWith<FeedbackVerificationArtifactException> {
                store.promote(prepared)
            }

            assertTrue(swapped)
            assertEquals("keep", outsideSentinel.readText())
            assertFalse(outside.resolve("receipt-1").exists())
        } finally {
            if (Files.isSymbolicLink(verification.toPath())) Files.delete(verification.toPath())
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun canonicalRootAndReceiptLocksSerializeStoresAndHoldOsFileLocks() {
        val root = Files.createTempDirectory("fixthis-verification-operation-lock").toFile().canonicalFile
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val rootLockPath = AtomicReference<Path>()
        val receiptLockPath = AtomicReference<Path>()
        val firstPrepared = AtomicReference<PreparedVerificationArtifact>()
        val secondPrepared = AtomicReference<PreparedVerificationArtifact>()
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val firstStore = FeedbackVerificationArtifactStore(
            root,
            hooks = VerificationArtifactStoreHooks(
                insideOperationLocks = { rootLock, receiptLock ->
                    rootLockPath.set(rootLock)
                    receiptLockPath.set(checkNotNull(receiptLock))
                    firstEntered.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                },
            ),
        )
        val secondStore = FeedbackVerificationArtifactStore(
            root,
            hooks = VerificationArtifactStoreHooks(
                insideOperationLocks = { _, _ -> secondEntered.countDown() },
            ),
        )
        try {
            val first = thread(name = "verification-lock-first") {
                runCatching {
                    firstPrepared.set(firstStore.prepare(session(root), "receipt-a", null))
                }.exceptionOrNull()?.let(failures::add)
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = thread(name = "verification-lock-second") {
                runCatching {
                    secondPrepared.set(secondStore.prepare(session(root), "receipt-b", null))
                }.exceptionOrNull()?.let(failures::add)
            }

            assertFalse(secondEntered.await(300, TimeUnit.MILLISECONDS))
            assertFalse(canAcquireFileLock(rootLockPath.get()))
            assertFalse(canAcquireFileLock(receiptLockPath.get()))

            releaseFirst.countDown()
            first.join(10_000)
            second.join(10_000)
            assertFalse(first.isAlive)
            assertFalse(second.isAlive)
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
            assertEquals(emptyList(), failures)
            firstStore.discard(firstPrepared.get())
            secondStore.discard(secondPrepared.get())
        } finally {
            releaseFirst.countDown()
            root.deleteRecursively()
        }
    }

    @Test
    fun atomicMoveFallbackRevalidatesAfterFallbackHookBeforeMoving() {
        val root = Files.createTempDirectory("fixthis-verification-atomic-fallback").toFile().canonicalFile
        val outside = Files.createTempDirectory("fixthis-verification-atomic-fallback-outside").toFile().canonicalFile
        val verification = root.resolve(".fixthis/feedback-sessions/session-1/verification")
        val displaced = verification.parentFile.resolve("verification-displaced")
        var fallbackReached = false
        val store = FeedbackVerificationArtifactStore(
            root,
            hooks = VerificationArtifactStoreHooks(
                atomicDirectoryMove = { source, target ->
                    throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "forced")
                },
                beforeAtomicMoveFallback = { _, _ ->
                    Files.move(verification.toPath(), displaced.toPath())
                    Files.createSymbolicLink(verification.toPath(), outside.toPath())
                    fallbackReached = true
                },
                rootDirectoryStreamFactory = ::nonSecureDirectoryStream,
            ),
        )
        val prepared = store.prepare(
            session(root),
            "receipt-1",
            SnapshotScreenshotDto(desktopFullPath = pngFile(root, "capture/source.png").absolutePath),
        )
        try {
            assertFailsWith<FeedbackVerificationArtifactException> {
                store.promote(prepared)
            }

            assertTrue(fallbackReached)
            assertFalse(outside.resolve("receipt-1").exists())
            assertTrue(outside.listFiles().orEmpty().isEmpty())
        } finally {
            if (Files.isSymbolicLink(verification.toPath())) Files.delete(verification.toPath())
            root.deleteRecursively()
            outside.deleteRecursively()
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

    @Test
    fun cleanupIncompleteNeverDeletesFinalReceiptBecauseItContainsOwnerLikeFiles() {
        withFixture { root, store, _ ->
            val finalReceipt = receiptDirectory(root, "session-1", "receipt-kept").apply {
                mkdirs()
                resolve("after.png").writeBytes(PNG_BYTES)
                resolve(".owner-0123456789abcdef0123456789abcdef")
                    .writeText("0123456789abcdef0123456789abcdef")
            }

            assertEquals(0, store.cleanupIncomplete())

            assertTrue(finalReceipt.resolve("after.png").isFile)
            assertTrue(finalReceipt.resolve(".owner-0123456789abcdef0123456789abcdef").isFile)
        }
    }

    @Test
    fun cleanupPreservesReferencedDottedReceiptOutsideReservedNamespaces() {
        withFixture { root, store, _ ->
            val dotted = receiptDirectory(root, "session-1", "x.reserve").apply {
                mkdirs()
                resolve("after.png").writeBytes(PNG_BYTES)
            }

            assertEquals(0, store.cleanupIncomplete())
            assertEquals(
                0,
                store.cleanupOrphans(mapOf("session-1" to setOf("x.reserve"))),
            )

            assertTrue(dotted.resolve("after.png").isFile)
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

    private fun session(root: File): SessionDto = SessionDto(
        sessionId = "session-1",
        packageName = "sample.app",
        projectRoot = root.canonicalPath,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
    )

    private fun nonSecureDirectoryStream(path: Path): DirectoryStream<Path> {
        val delegate = Files.newDirectoryStream(path)
        return object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> = delegate.iterator()

            override fun close() = delegate.close()
        }
    }

    private fun canAcquireFileLock(path: Path): Boolean {
        val options: Set<OpenOption> = setOf(
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
        return FileChannel.open(path, options).use { channel ->
            try {
                channel.tryLock()?.use { true } ?: false
            } catch (_: OverlappingFileLockException) {
                false
            }
        }
    }

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
