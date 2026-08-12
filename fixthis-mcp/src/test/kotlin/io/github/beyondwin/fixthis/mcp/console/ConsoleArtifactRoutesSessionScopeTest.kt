package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.github.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.github.beyondwin.fixthis.mcp.fixtures.FakeLongs
import io.github.beyondwin.fixthis.mcp.fixtures.SessionScreenshotBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationReceiptDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationVerdict
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleArtifactRoutesSessionScopeTest {
    @Test
    fun previewScreenshotUsesExplicitSessionIdWhenCurrentSessionChanged() {
        val root = Files.createTempDirectory("fixthis-artifact-scope").toFile()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val service = FeedbackSessionService(
            bridge = SessionScreenshotBridge(png),
            store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
                idGenerator = FakeIds("session-a", "preview-a", "preview-screen-a", "session-b").next,
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val sessionA = service.openSession(null, newSession = true)
        val preview = kotlinx.coroutines.runBlocking { service.capturePreview(sessionA.sessionId) }
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val path = "/api/preview/${encode(preview.previewId)}/screenshot/full" +
                "?sessionId=${encode(sessionA.sessionId)}"
            val response = ConsoleHttpTestClient(server.url).getResponse(path)

            assertEquals(200, response.statusCode)
            assertTrue(response.contentTypeStartsWith("image/png"))
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun previewScreenshotRejectsMismatchedExplicitSessionId() {
        val root = Files.createTempDirectory("fixthis-artifact-mismatch").toFile()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val service = FeedbackSessionService(
            bridge = SessionScreenshotBridge(png),
            store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
                idGenerator = FakeIds("session-a", "preview-a", "preview-screen-a", "session-b").next,
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val sessionA = service.openSession(null, newSession = true)
        val preview = kotlinx.coroutines.runBlocking { service.capturePreview(sessionA.sessionId) }
        val sessionB = service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val path = "/api/preview/${encode(preview.previewId)}/screenshot/full" +
                "?sessionId=${encode(sessionB.sessionId)}"
            val response = ConsoleHttpTestClient(server.url).getResponse(path)

            assertEquals(404, response.statusCode)
        } finally {
            server.stop()
            root.deleteRecursively()
        }
        assertTrue(sessionA.sessionId != sessionB.sessionId)
    }

    @Test
    fun screenScreenshotUsesExplicitSessionIdWhenCurrentSessionChanged() {
        val root = Files.createTempDirectory("fixthis-screen-artifact-scope").toFile()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val service = FeedbackSessionService(
            bridge = SessionScreenshotBridge(png),
            store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
                idGenerator = FakeIds("session-a", "screen-a", "session-b").next,
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val sessionA = service.openSession(null, newSession = true)
        val screen = kotlinx.coroutines.runBlocking { service.captureScreen(sessionA.sessionId) }
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val path = "/api/screens/${encode(screen.screenId)}/screenshot/full?sessionId=${encode(sessionA.sessionId)}"
            val response = ConsoleHttpTestClient(server.url).getResponse(path)

            assertEquals(200, response.statusCode)
            assertTrue(response.contentTypeStartsWith("image/png"))
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun screenScreenshotRejectsPngAliasWhoseCanonicalTargetIsNotPng() {
        val root = Files.createTempDirectory("fixthis-screen-artifact-alias").toFile()
        val service = FeedbackSessionService(
            bridge = SessionScreenshotBridge(png),
            store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L).next,
                idGenerator = FakeIds("session-a", "screen-a").next,
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = kotlinx.coroutines.runBlocking { service.captureScreen(session.sessionId) }
        val alias = File(checkNotNull(screen.screenshot?.desktopFullPath))
        val payload = File(alias.parentFile, "payload.jpg")
        payload.writeBytes(png)
        assertTrue(alias.delete())
        Files.createSymbolicLink(alias.toPath(), payload.toPath())
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).getResponse(
                "/api/screens/${encode(screen.screenId)}/screenshot/full?sessionId=${encode(session.sessionId)}",
            )

            assertEquals(404, response.statusCode)
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteScreenUsesExplicitSessionIdWhenCurrentSessionChanged() {
        val root = Files.createTempDirectory("fixthis-screen-delete-scope").toFile()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val service = FeedbackSessionService(
            bridge = SessionScreenshotBridge(png),
            store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
                idGenerator = FakeIds("session-a", "screen-a", "session-b").next,
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val sessionA = service.openSession(null, newSession = true)
        val screen = kotlinx.coroutines.runBlocking { service.captureScreen(sessionA.sessionId) }
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection(
                "/api/screens/${encode(screen.screenId)}?sessionId=${encode(sessionA.sessionId)}",
                method = "DELETE",
            )

            assertEquals(200, connection.responseCode)
            assertTrue(service.getSession(sessionA.sessionId).screens.isEmpty())
            assertTrue(service.requireCurrentSession().sessionId != sessionA.sessionId)
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun verificationAfterScreenshotUsesExplicitSessionIdWhenCurrentSessionChanged() = withReceiptFixture(
        switchCurrentSession = true,
    ) { fixture ->
        val response = fixture.getAfterScreenshot(sessionId = fixture.session.sessionId)

        assertEquals(200, response.statusCode)
        assertTrue(response.contentTypeStartsWith("image/png"))
    }

    @Test
    fun verificationAfterScreenshotUsesCurrentSessionWithoutExplicitSessionId() = withReceiptFixture { fixture ->
        val response = fixture.getAfterScreenshot(sessionId = null)

        assertEquals(200, response.statusCode)
        assertTrue(response.contentTypeStartsWith("image/png"))
    }

    @Test
    fun verificationAfterScreenshotRejectsReceiptOutsideExplicitSession() = withReceiptFixture(
        switchCurrentSession = true,
    ) { fixture ->
        assertEquals(404, fixture.getAfterScreenshot(sessionId = fixture.otherSessionId).statusCode)
    }

    @Test
    fun verificationAfterScreenshotRejectsOutsideRootAndWrongExtension() {
        val outside = Files.createTempFile("fixthis-receipt-outside", ".png").toFile()
        try {
            withReceiptFixture(artifactPath = { outside }) { fixture ->
                assertEquals(404, fixture.getAfterScreenshot(sessionId = fixture.session.sessionId).statusCode)
            }
        } finally {
            outside.delete()
        }
        withReceiptFixture(artifactPath = { fixture -> File(fixture.receiptDirectory, "after.jpg") }) { fixture ->
            assertEquals(404, fixture.getAfterScreenshot(sessionId = fixture.session.sessionId).statusCode)
        }
    }

    @Test
    fun verificationAfterScreenshotRejectsMissingNonRegularAndSymlinkArtifacts() {
        withReceiptFixture(
            artifactPath = { fixture -> File(fixture.receiptDirectory, "missing.png") },
            createArtifact = false,
        ) { fixture ->
            assertEquals(404, fixture.getAfterScreenshot(sessionId = fixture.session.sessionId).statusCode)
        }
        withReceiptFixture(
            artifactPath = { fixture -> File(fixture.receiptDirectory, "directory.png") },
            createArtifact = false,
        ) { fixture ->
            fixture.artifactFile.parentFile.mkdirs()
            assertTrue(fixture.artifactFile.mkdir())
            assertEquals(404, fixture.getAfterScreenshot(sessionId = fixture.session.sessionId).statusCode)
        }
        withReceiptFixture(
            artifactPath = { fixture -> File(fixture.receiptDirectory, "linked.png") },
            createArtifact = false,
        ) { fixture ->
            fixture.artifactFile.parentFile.mkdirs()
            val target = File(fixture.receiptDirectory, "target.png")
            target.writeBytes(png)
            Files.createSymbolicLink(fixture.artifactFile.toPath(), target.toPath())

            assertEquals(404, fixture.getAfterScreenshot(sessionId = fixture.session.sessionId).statusCode)
        }
    }

    @Test
    fun verificationAfterScreenshotRejectsFileAsReceiptArtifactRoot() = withReceiptFixture(
        receiptId = "receipt.png",
        artifactPath = { fixture -> fixture.receiptDirectory },
    ) { fixture ->
        assertEquals(404, fixture.getAfterScreenshot(sessionId = fixture.session.sessionId).statusCode)
    }

    @Test
    fun verificationAfterScreenshotRejectsSymlinkedReceiptArtifactRoot() = withReceiptFixture(
        artifactPath = { fixture -> File(fixture.receiptDirectory, "after.png") },
        createArtifact = false,
    ) { fixture ->
        fixture.receiptDirectory.parentFile.mkdirs()
        val outside = File(fixture.receiptDirectory.parentFile, "outside-receipt-root").apply { mkdirs() }
        File(outside, "after.png").writeBytes(png)
        Files.createSymbolicLink(fixture.receiptDirectory.toPath(), outside.toPath())

        assertEquals(404, fixture.getAfterScreenshot(sessionId = fixture.session.sessionId).statusCode)
    }

    @Test
    fun verificationAfterScreenshotRejectsSymlinkedVerificationParent() = withReceiptFixture(
        artifactPath = { fixture -> File(fixture.receiptDirectory, "after.png") },
        createArtifact = false,
    ) { fixture ->
        val verification = fixture.receiptDirectory.parentFile
        verification.parentFile.mkdirs()
        val outside = File(verification.parentFile, "outside-verification-root").apply { mkdirs() }
        File(File(outside, fixture.receiptId), "after.png").apply {
            parentFile.mkdirs()
            writeBytes(png)
        }
        Files.createSymbolicLink(verification.toPath(), outside.toPath())

        assertEquals(404, fixture.getAfterScreenshot(sessionId = fixture.session.sessionId).statusCode)
    }

    @Test
    fun verificationAfterScreenshotDecodesReceiptIdAndRejectsTraversalId() {
        withReceiptFixture(receiptId = "receipt encoded") { fixture ->
            val response = fixture.getAfterScreenshot(sessionId = fixture.session.sessionId)

            assertEquals(200, response.statusCode)
            assertTrue(response.contentTypeStartsWith("image/png"))
        }
        withReceiptFixture { fixture ->
            val response = fixture.getAfterScreenshot(
                receiptId = "%2E%2E%2F${encode(fixture.receiptId)}",
                sessionId = fixture.session.sessionId,
                encodedReceiptId = true,
            )

            assertEquals(404, response.statusCode)
        }
    }

    private fun withReceiptFixture(
        receiptId: String = "receipt-a",
        switchCurrentSession: Boolean = false,
        artifactPath: ((ReceiptFixture) -> File)? = null,
        createArtifact: Boolean = true,
        block: (ReceiptFixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("fixthis-receipt-artifact").toFile()
        val store = FeedbackSessionStore(
            clock = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
            idGenerator = FakeIds("session-a", "session-b").next,
        )
        val service = FeedbackSessionService(
            bridge = SessionScreenshotBridge(png),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val provisional = ReceiptFixture(
            session = session,
            otherSessionId = "session-b",
            receiptId = receiptId,
            receiptDirectory = File(root, ".fixthis/feedback-sessions/${session.sessionId}/verification/$receiptId"),
            artifactFile = File(root, "unused"),
            client = null,
        )
        val artifactFile = artifactPath?.invoke(provisional) ?: File(provisional.receiptDirectory, "after.png")
        if (createArtifact) {
            artifactFile.parentFile?.mkdirs()
            if (!artifactFile.exists()) artifactFile.writeBytes(png)
        }
        store.replaceSessionForDomain(
            session.copy(
                verificationReceipts = listOf(
                    FeedbackVerificationReceiptDto(
                        receiptId = receiptId,
                        itemId = "item-a",
                        baselineScreenId = "screen-a",
                        createdAtEpochMillis = 100L,
                        verdict = FeedbackVerificationVerdict.PASS,
                        checks = emptyList(),
                        assertions = emptyList(),
                        afterScreenshot = io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto(
                            desktopFullPath = artifactFile.absolutePath,
                        ),
                    ),
                ),
            ),
        )
        val otherSessionId = if (switchCurrentSession) service.openSession(null, newSession = true).sessionId else session.sessionId
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            block(provisional.copy(otherSessionId = otherSessionId, artifactFile = artifactFile, client = ConsoleHttpTestClient(server.url)))
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    private data class ReceiptFixture(
        val session: io.github.beyondwin.fixthis.mcp.session.dto.SessionDto,
        val otherSessionId: String,
        val receiptId: String,
        val receiptDirectory: File,
        val artifactFile: File,
        val client: ConsoleHttpTestClient?,
    ) {
        fun getAfterScreenshot(
            receiptId: String = this.receiptId,
            sessionId: String?,
            encodedReceiptId: Boolean = false,
        ) = checkNotNull(client).getResponse(
            "/api/verification-receipts/${if (encodedReceiptId) receiptId else URLEncoder.encode(receiptId, Charsets.UTF_8.name())}/screenshot/after" +
                sessionId?.let { "?sessionId=${URLEncoder.encode(it, Charsets.UTF_8.name())}" }.orEmpty(),
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    }
}
