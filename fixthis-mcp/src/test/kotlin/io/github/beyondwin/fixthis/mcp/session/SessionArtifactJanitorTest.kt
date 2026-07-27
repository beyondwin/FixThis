package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPaths
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPersistence
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.SessionArtifactJanitor
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationReceiptDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationVerdict
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionArtifactJanitorTest {
    @Test
    fun deletesScreenArtifactDirectory() {
        val root = Files.createTempDirectory("fixthis-artifacts").toFile()
        try {
            val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root))
            val screenDir = persistence.artifactPaths().screenArtifactDirectory("session-1", "screen-1")
            File(screenDir, "full.png").apply {
                parentFile.mkdirs()
                writeText("png")
            }

            SessionArtifactJanitor(persistence).deleteScreenArtifacts("session-1", "screen-1")

            assertFalse(screenDir.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removesIncompleteAndUnreferencedVerificationArtifactsOnlyWhenReferencesAreComplete() {
        val root = Files.createTempDirectory("fixthis-verification-janitor").toFile()
        try {
            val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root))
            val janitor = SessionArtifactJanitor(persistence)
            val kept = receiptDirectory(root, "session-1", "receipt-kept").apply { mkdirs() }
            val orphan = receiptDirectory(root, "session-1", "receipt-orphan").apply { mkdirs() }
            val incomplete = kept.parentFile.resolve(
                ".receipt-new.tmp-0123456789abcdef0123456789abcdef",
            ).apply { mkdirs() }
            val unlistedSessionReceipt = receiptDirectory(root, "session-unlisted", "receipt-unlisted")
                .apply { mkdirs() }
            val session = sessionWithReceipt(root, "session-1", "receipt-kept")

            janitor.cleanupVerificationArtifacts(listOf(session), referencesComplete = false)

            assertTrue(kept.isDirectory)
            assertTrue(orphan.isDirectory)
            assertFalse(incomplete.exists())
            assertTrue(unlistedSessionReceipt.isDirectory)

            janitor.cleanupVerificationArtifacts(listOf(session), referencesComplete = true)

            assertTrue(kept.isDirectory)
            assertFalse(orphan.exists())
            assertFalse(unlistedSessionReceipt.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preservesReferencesForEveryCanonicalProjectRoot() {
        val primaryRoot = Files.createTempDirectory("fixthis-verification-primary").toFile()
        val secondaryRoot = Files.createTempDirectory("fixthis-verification-secondary").toFile()
        try {
            val janitor = SessionArtifactJanitor(
                FeedbackSessionPersistence(FeedbackSessionPaths(primaryRoot)),
            )
            val primaryReceipt = receiptDirectory(primaryRoot, "session-1", "receipt-primary")
                .apply { mkdirs() }
            val secondaryReceipt = receiptDirectory(secondaryRoot, "session-2", "receipt-secondary")
                .apply { mkdirs() }

            janitor.cleanupVerificationArtifacts(
                listOf(
                    sessionWithReceipt(primaryRoot, "session-1", "receipt-primary"),
                    sessionWithReceipt(secondaryRoot, "session-2", "receipt-secondary"),
                ),
                referencesComplete = true,
            )

            assertTrue(primaryReceipt.isDirectory)
            assertTrue(secondaryReceipt.isDirectory)
        } finally {
            primaryRoot.deleteRecursively()
            secondaryRoot.deleteRecursively()
        }
    }

    private fun sessionWithReceipt(
        root: File,
        sessionId: String,
        receiptId: String,
    ): SessionDto = SessionDto(
        sessionId = sessionId,
        packageName = "sample.app",
        projectRoot = root.canonicalPath,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        verificationReceipts = listOf(
            FeedbackVerificationReceiptDto(
                receiptId = receiptId,
                itemId = "item-1",
                baselineScreenId = "screen-1",
                createdAtEpochMillis = 2L,
                verdict = FeedbackVerificationVerdict.WARN,
                checks = emptyList(),
                assertions = emptyList(),
            ),
        ),
    )

    private fun receiptDirectory(
        root: File,
        sessionId: String,
        receiptId: String,
    ): File = root.resolve(
        ".fixthis/feedback-sessions/$sessionId/verification/$receiptId",
    )
}
