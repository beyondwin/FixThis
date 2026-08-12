package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationArtifactException
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationCaptureStore
import io.github.beyondwin.fixthis.mcp.session.verification.VerificationArtifactStoreHooks
import java.io.File
import java.nio.file.Files
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
            afterIdentityCaptured = { created ->
                if (created.fileName.toString() == "verification-$RECEIPT_ID") {
                    Files.delete(created)
                    Files.move(replacement, created)
                }
            },
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
                afterIdentityCaptured = { created ->
                    if (created.fileName.toString() == "verification-$RECEIPT_ID") {
                        Files.delete(created)
                        Files.createSymbolicLink(created, target.toPath())
                    }
                },
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
        )

        assertFailsWith<FeedbackVerificationArtifactException> {
            store.reserve(session(root), RECEIPT_ID)
        }

        assertTrue(swapped)
        assertEquals("replacement", capturePath.resolve("sentinel.txt").toFile().readText())
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
                afterOwnedIdentityValidated = { validated ->
                    if (validated.fileName.toString() == "verification-$RECEIPT_ID") {
                        Files.move(validated, displaced)
                        Files.createSymbolicLink(validated, target.toPath())
                        swapped = true
                    }
                },
            )
            val capture = store.reserve(session(root), RECEIPT_ID)

            assertFailsWith<FeedbackVerificationArtifactException> {
                store.cleanup(capture)
            }

            assertTrue(swapped)
            assertTrue(Files.isSymbolicLink(capturePath))
            assertEquals("target", sentinel.readText())
        } finally {
            Files.deleteIfExists(capturePath)
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

    private fun captureStore(
        root: File,
        afterIdentityCaptured: (java.nio.file.Path) -> Unit = {},
        afterOwnedIdentityValidated: (java.nio.file.Path) -> Unit = {},
    ): FeedbackVerificationCaptureStore = FeedbackVerificationCaptureStore(
        projectRoot = root,
        hooks = VerificationArtifactStoreHooks(
            afterDirectoryCreateIdentityCaptured = afterIdentityCaptured,
            afterOwnedDirectoryIdentityValidatedBeforeDelete = afterOwnedIdentityValidated,
        ),
    )

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
