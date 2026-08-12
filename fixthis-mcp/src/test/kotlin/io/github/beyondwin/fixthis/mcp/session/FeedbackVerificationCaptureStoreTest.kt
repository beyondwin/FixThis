package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationArtifactException
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationCaptureStore
import io.github.beyondwin.fixthis.mcp.session.verification.VerificationArtifactStoreHooks
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackVerificationCaptureStoreTest {
    @Test
    fun directoryReplacementAfterIdentityCaptureIsPreservedOnReservationFailure() = withRoot { root ->
        val capturePath = capturePath(root)
        val replacement = Files.createDirectory(root.toPath().resolve("replacement"))
        replacement.resolve("sentinel.txt").toFile().writeText("replacement")
        val store = captureStore(
            root = root,
            options = CaptureStoreOptions(
                afterIdentityCaptured = { created ->
                    if (created.fileName.toString() == "verification-$RECEIPT_ID") {
                        Files.delete(created)
                        Files.move(replacement, created)
                    }
                },
            ),
        )

        val failure = assertFailsWith<FeedbackVerificationArtifactException> {
            store.reserve(session(root), RECEIPT_ID)
        }

        assertTrue(failure.message.orEmpty().contains("ownership changed"), failure.message)
        assertEquals("replacement", capturePath.resolve("sentinel.txt").toFile().readText())
    }

    @Test
    fun symlinkReplacementAfterIdentityCaptureIsPreservedOnReservationFailure() = withRoot { root ->
        val capturePath = capturePath(root)
        val target = Files.createTempDirectory("verification-capture-target-").toFile()
        val sentinel = target.resolve("sentinel.txt").apply { writeText("target") }
        try {
            val store = captureStore(
                root = root,
                options = CaptureStoreOptions(
                    afterIdentityCaptured = { created ->
                        if (created.fileName.toString() == "verification-$RECEIPT_ID") {
                            Files.delete(created)
                            Files.createSymbolicLink(created, target.toPath())
                        }
                    },
                ),
            )

            assertFailsWith<FeedbackVerificationArtifactException> {
                store.reserve(session(root), RECEIPT_ID)
            }

            assertTrue(Files.isSymbolicLink(capturePath))
            assertEquals("target", sentinel.readText())
        } finally {
            Files.deleteIfExists(capturePath)
            target.deleteRecursively()
        }
    }

    @Test
    fun rollbackPreservesDirectoryReplacementMadeAfterOwnedIdentityValidation() = withRoot { root ->
        val capturePath = capturePath(root)
        val displaced = root.toPath().resolve("displaced-owned")
        val replacement = Files.createDirectory(root.toPath().resolve("rollback-replacement"))
        replacement.resolve("sentinel.txt").toFile().writeText("replacement")
        var swapped = false
        val store = captureStore(
            root = root,
            options = CaptureStoreOptions(
                afterIdentityCaptured = { created ->
                    if (created.fileName.toString() == "verification-$RECEIPT_ID") {
                        error("force reservation rollback")
                    }
                },
                afterOwnedIdentityValidated = { validated ->
                    if (validated.fileName.toString() == "verification-$RECEIPT_ID") {
                        Files.move(validated, displaced)
                        Files.move(replacement, validated)
                        swapped = true
                    }
                },
            ),
        )

        val failure = assertFailsWith<FeedbackVerificationArtifactException> {
            store.reserve(session(root), RECEIPT_ID)
        }

        assertTrue(swapped)
        assertTrue(failure.message.orEmpty().contains("remains recoverable"), failure.message)
        assertFalse(Files.exists(capturePath))
        assertEquals("replacement", recoveryClaim(root).resolve("sentinel.txt").toFile().readText())
    }

    @Test
    fun cleanupPreservesSymlinkReplacementMadeAfterOwnedIdentityValidation() = withRoot { root ->
        val capturePath = capturePath(root)
        val displaced = root.toPath().resolve("displaced-owned")
        val target = Files.createTempDirectory("verification-cleanup-target-").toFile()
        val sentinel = target.resolve("sentinel.txt").apply { writeText("target") }
        var swapped = false
        try {
            val store = captureStore(
                root = root,
                options = CaptureStoreOptions(
                    afterOwnedIdentityValidated = { validated ->
                        if (validated.fileName.toString() == "verification-$RECEIPT_ID") {
                            Files.move(validated, displaced)
                            Files.createSymbolicLink(validated, target.toPath())
                            swapped = true
                        }
                    },
                ),
            )
            val capture = store.reserve(session(root), RECEIPT_ID)

            val failure = assertFailsWith<FeedbackVerificationArtifactException> {
                store.cleanup(capture)
            }

            assertTrue(swapped)
            assertTrue(failure.message.orEmpty().contains("remains recoverable"), failure.message)
            assertFalse(Files.exists(capturePath))
            assertTrue(Files.isSymbolicLink(recoveryClaim(root)))
            assertEquals("target", sentinel.readText())
        } finally {
            Files.deleteIfExists(capturePath)
            Files.deleteIfExists(recoveryClaim(root))
            target.deleteRecursively()
        }
    }

    @Test
    fun cleanupDeletesGenuinelyOwnedDirectoryRecursively() = withRoot { root ->
        val store = captureStore(root)
        val capture = store.reserve(session(root), RECEIPT_ID)
        capture.directory.resolve("nested").mkdirs()
        capture.directory.resolve("nested/after.png").writeText("owned")

        store.cleanup(capture)

        assertFalse(capture.directory.exists())
    }

    @Test
    fun forcedFallbackCleanupNeverOverwritesCollidingQuarantineSentinel() = withRoot { root ->
        val capturePath = capturePath(root)
        val claim = capturePath.parent.resolve(".capture-cleanup-collision")
        val store = captureStore(
            root = root,
            options = CaptureStoreOptions(
                forceFallback = true,
                cleanupClaimName = { claim.fileName.toString() },
            ),
        )
        val capture = store.reserve(session(root), RECEIPT_ID)
        capture.directory.resolve("nested").mkdirs()
        capture.directory.resolve("nested/after.png").writeText("owned")
        Files.createDirectory(claim)
        claim.resolve("sentinel.txt").toFile().writeText("unrelated")

        assertFailsWith<FeedbackVerificationArtifactException> {
            store.cleanup(capture)
        }

        assertEquals("unrelated", claim.resolve("sentinel.txt").toFile().readText())
        assertTrue(capturePath.resolve("nested/after.png").toFile().isFile)
    }

    @Test
    fun forcedFallbackCleanupPreservesReplacementInstalledAfterPublicNameClaim() = withRoot { root ->
        val capturePath = capturePath(root)
        val replacement = root.toPath().resolve("post-claim-replacement")
        Files.createDirectory(replacement)
        replacement.resolve("sentinel.txt").toFile().writeText("replacement")
        var claimed = false
        val store = captureStore(
            root = root,
            options = CaptureStoreOptions(
                forceFallback = true,
                afterOwnedDirectoryClaimed = { original, _ ->
                    Files.move(replacement, original)
                    claimed = true
                },
            ),
        )
        val capture = store.reserve(session(root), RECEIPT_ID)
        capture.directory.resolve("nested").mkdirs()
        capture.directory.resolve("nested/after.png").writeText("owned")

        store.cleanup(capture)

        assertTrue(claimed)
        assertEquals("replacement", capturePath.resolve("sentinel.txt").toFile().readText())
        assertTrue(capturePath.parent.toFile().listFiles().orEmpty().none { it.name.startsWith(".capture-cleanup-") })
    }

    @Test
    fun forcedFallbackAtomicClaimFailureIsFailClosedWithoutQuarantineLitter() = withRoot { root ->
        val capturePath = capturePath(root)
        val store = FeedbackVerificationCaptureStore(
            projectRoot = root,
            hooks = VerificationArtifactStoreHooks(
                captureCleanupClaimName = { ".capture-cleanup-atomic-failure" },
                beforeFallbackMove = { source, target ->
                    if (target.fileName.toString() == "owned") {
                        throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "forced")
                    }
                },
                rootDirectoryStreamFactory = ::nonSecureDirectoryStream,
            ),
        )
        val capture = store.reserve(session(root), RECEIPT_ID)
        capture.directory.resolve("nested").mkdirs()
        capture.directory.resolve("nested/after.png").writeText("owned")

        assertFailsWith<FeedbackVerificationArtifactException> {
            store.cleanup(capture)
        }

        assertTrue(capturePath.resolve("nested/after.png").toFile().isFile)
        assertFalse(capturePath.parent.resolve(".capture-cleanup-atomic-failure").toFile().exists())
    }

    @Test
    fun secureCleanupPreservesReplacementInstalledAfterPublicNameClaim() = withRoot { root ->
        val capturePath = capturePath(root)
        val replacement = root.toPath().resolve("secure-post-claim-replacement")
        Files.createDirectory(replacement)
        replacement.resolve("sentinel.txt").toFile().writeText("replacement")
        var claimed = false
        val store = captureStore(
            root = root,
            options = CaptureStoreOptions(
                forceSecure = true,
                afterOwnedDirectoryClaimed = { original, _ ->
                    Files.move(replacement, original)
                    claimed = true
                },
            ),
        )
        val capture = store.reserve(session(root), RECEIPT_ID)
        capture.directory.resolve("nested").mkdirs()
        capture.directory.resolve("nested/after.png").writeText("owned")

        store.cleanup(capture)

        assertTrue(claimed)
        assertEquals("replacement", capturePath.resolve("sentinel.txt").toFile().readText())
        assertTrue(capturePath.parent.toFile().listFiles().orEmpty().none { it.name.startsWith(".capture-cleanup-") })
    }

    @Test
    fun captureLockRefusesSymlinkedLockFileWithoutTouchingTarget() = withRoot { root ->
        val outside = Files.createTempDirectory("verification-capture-lock-target-").toFile()
        val target = outside.resolve("target.lock").apply { writeText("unrelated") }
        try {
            val lockRoot = root.resolve(".fixthis/verification-artifact-locks").apply { mkdirs() }
            Files.createSymbolicLink(lockRoot.resolve("root.lock").toPath(), target.toPath())
            val store = captureStore(root)

            assertFailsWith<FeedbackVerificationArtifactException> {
                store.reserve(session(root), RECEIPT_ID)
            }

            assertEquals("unrelated", target.readText())
            assertFalse(capturePath(root).toFile().exists())
        } finally {
            outside.deleteRecursively()
        }
    }

    private fun captureStore(
        root: File,
        options: CaptureStoreOptions = CaptureStoreOptions(),
    ): FeedbackVerificationCaptureStore = FeedbackVerificationCaptureStore(
        projectRoot = root,
        hooks = VerificationArtifactStoreHooks(
            afterDirectoryCreateIdentityCaptured = options.afterIdentityCaptured,
            afterOwnedDirectoryIdentityValidatedBeforeDelete = options.afterOwnedIdentityValidated,
            captureCleanupClaimName = options.cleanupClaimName,
            afterOwnedDirectoryClaimedBeforeTraversal = options.afterOwnedDirectoryClaimed,
            rootDirectoryStreamFactory = when {
                options.forceFallback -> ::nonSecureDirectoryStream
                options.forceSecure -> TestSecureDirectoryStream::open
                else -> Files::newDirectoryStream
            },
        ),
    )

    private data class CaptureStoreOptions(
        val afterIdentityCaptured: (Path) -> Unit = {},
        val afterOwnedIdentityValidated: (Path) -> Unit = {},
        val forceFallback: Boolean = false,
        val forceSecure: Boolean = false,
        val cleanupClaimName: () -> String = { ".capture-cleanup-0123456789abcdef0123456789abcdef" },
        val afterOwnedDirectoryClaimed: (Path, Path) -> Unit = { _, _ -> },
    )

    private fun nonSecureDirectoryStream(path: Path): DirectoryStream<Path> {
        val delegate = Files.newDirectoryStream(path)
        return object : DirectoryStream<Path> {
            override fun iterator(): MutableIterator<Path> = delegate.iterator()

            override fun close() = delegate.close()
        }
    }

    private fun session(root: File): SessionDto = SessionDto(
        sessionId = SESSION_ID,
        packageName = "com.test",
        projectRoot = root.absolutePath,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun capturePath(root: File) = root.toPath()
        .resolve(".fixthis")
        .resolve("preview-cache")
        .resolve(SESSION_ID)
        .resolve("verification-$RECEIPT_ID")

    private fun recoveryClaim(root: File): Path = capturePath(root).parent
        .resolve(".capture-cleanup-0123456789abcdef0123456789abcdef")
        .resolve("owned")

    private fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("verification-capture-store-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        const val SESSION_ID = "session-1"
        const val RECEIPT_ID = "receipt-1"
    }
}
