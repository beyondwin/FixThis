package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import io.github.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotRootDto
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackDelivery
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogReader
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogWriter
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPaths
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPersistence
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewCaptureService
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewSnapshotCache
import io.github.beyondwin.fixthis.mcp.session.source.HostSourceFreshnessProbe
import io.github.beyondwin.fixthis.mcp.session.source.SourceIndexRegistry
import io.github.beyondwin.fixthis.mcp.session.target.TargetEvidenceService
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationArtifactException
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationArtifactStore
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionKind
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationCheckOutcome
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationCoordinator
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationReceiptDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationRequest
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationSessionAccess
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationStartContext
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationVerdict
import io.github.beyondwin.fixthis.mcp.session.verification.VerificationArtifactStoreHooks
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackVerificationCoordinatorTest {
    @Test
    fun successfulNodeVerificationPersistsBoundedAfterScreenshotThroughService() = withFixture { fixture ->
        val service = fixture.service()

        val receipt = runBlocking {
            service.verifyFeedback(
                sessionId = fixture.sessionId,
                itemId = fixture.itemId,
                assertions = listOf(targetPresent(), textPresent("Pay now")),
            )
        }

        assertEquals(FeedbackVerificationVerdict.PASS, receipt.verdict)
        assertEquals(receipt, fixture.persistedReceipt())
        assertEquals("MainActivity", receipt.currentActivity)
        assertEquals(fixture.installedAt, receipt.installedAtEpochMillis)
        assertTrue(receipt.checks.size <= 16)
        assertTrue(receipt.checks.all { it.message.length <= 512 })
        val afterPath = assertNotNull(receipt.afterScreenshot?.desktopFullPath)
        assertTrue(File(afterPath).isFile)
        assertTrue(afterPath.endsWith("/verification/${receipt.receiptId}/after.png"))
        assertNull(receipt.afterScreenshot.fullPath)
        assertNull(receipt.afterScreenshot.cropPath)
        assertFalse(fixture.captureDirectory().exists())
    }

    @Test
    fun appUnavailablePersistsFailWithoutCaptureOrArtifact() = withFixture { fixture ->
        fixture.bridge.statusError = IOException("device offline")

        val receipt = fixture.verify()

        assertFail(receipt, "APP_UNAVAILABLE")
        assertEquals(0, fixture.bridge.captureCount)
        assertNull(receipt.afterScreenshot)
        assertEquals(receipt, fixture.persistedReceipt())
    }

    @Test
    fun capturedScreenWithoutActivityPersistsAppUnavailableFail() = withFixture { fixture ->
        fixture.bridge.captureActivity = null

        val receipt = fixture.verify()

        assertFail(receipt, "APP_UNAVAILABLE")
        assertEquals(receipt, fixture.persistedReceipt())
    }

    @Test
    fun returnedPackageMismatchPersistsFailAndDoesNotCapture() = withFixture { fixture ->
        fixture.bridge.statusPackageName = "other.app"

        val receipt = fixture.verify()

        assertFail(receipt, "PACKAGE_MISMATCH")
        assertEquals(0, fixture.bridge.captureCount)
        assertEquals(receipt, fixture.persistedReceipt())
    }

    @Test
    fun staleInstallPersistsFailReceipt() = withFixture { fixture ->
        assertTrue(fixture.sourceFile.setLastModified(fixture.installedAt + 1_000L))

        val receipt = fixture.verify()

        assertFail(receipt, "SOURCE_INSTALL_STALE")
        assertEquals(receipt, fixture.persistedReceipt())
    }

    @Test
    fun unavailableInstallFreshnessProducesWarnNotPass() = withFixture { fixture ->
        fixture.bridge.installEpochMillis = null

        val receipt = fixture.verify(textPresent("Pay now"))

        assertEquals(FeedbackVerificationVerdict.WARN, receipt.verdict)
        assertCheck(receipt, "INSTALL_FRESHNESS_UNKNOWN", FeedbackVerificationCheckOutcome.WARNING)
        assertEquals(receipt, fixture.persistedReceipt())
    }

    @Test
    fun knownDifferentScreenContextPersistsFail() = withFixture { fixture ->
        fixture.bridge.statusActivity = "OtherActivity"
        fixture.bridge.captureActivity = "OtherActivity"

        val receipt = fixture.verify()

        assertFail(receipt, "SCREEN_CONTEXT_MISMATCH")
        assertEquals(receipt, fixture.persistedReceipt())
    }

    @Test
    fun unavailableBaselineScreenIdentityProducesWarn() = withFixture(baselineActivity = null) { fixture ->
        val receipt = fixture.verify()

        assertEquals(FeedbackVerificationVerdict.WARN, receipt.verdict)
        assertCheck(receipt, "SCREEN_CONTEXT_UNKNOWN", FeedbackVerificationCheckOutcome.WARNING)
    }

    @Test
    fun visualAreaRequiresManualReviewAndNeverPasses() = withFixture(visualArea = true) { fixture ->
        val receipt = fixture.verify()

        assertEquals(FeedbackVerificationVerdict.WARN, receipt.verdict)
        assertCheck(receipt, "MANUAL_VISUAL_REVIEW_REQUIRED", FeedbackVerificationCheckOutcome.WARNING)
    }

    @Test
    fun contextRaceDeletesPromotedArtifactAndCreatesNoReceipt() = withFixture(
        artifactHooks = { fixture ->
            VerificationArtifactStoreHooks(
                beforePromotion = {
                    fixture.store.claimFeedback(fixture.sessionId, fixture.itemId, "raced")
                },
            )
        },
    ) { fixture ->
        val failure = assertFailsWith<RuntimeException> { fixture.verify() }

        assertTrue(failure.message.orEmpty().startsWith("VERIFICATION_CONTEXT_CHANGED:"), failure.message)
        fixture.assertNoReceiptOrOwnedArtifacts()
    }

    @Test
    fun invalidRequestCreatesNoReceiptOrVerificationArtifacts() = withFixture { fixture ->
        val failure = assertFailsWith<IllegalArgumentException> {
            fixture.verify(textPresent("x".repeat(257)))
        }

        assertTrue(failure.message.orEmpty().startsWith("VERIFICATION_ASSERTIONS_INVALID:"), failure.message)
        fixture.assertNoReceiptOrOwnedArtifacts()
    }

    @Test
    fun artifactPromotionFailureCleansTemporaryCaptureAndCreatesNoReceipt() = withFixture(
        artifactHooks = {
            VerificationArtifactStoreHooks(
                beforePromotion = { throw IOException("promotion blocked") },
            )
        },
    ) { fixture ->
        val failure = assertFailsWith<FeedbackVerificationArtifactException> { fixture.verify() }

        assertTrue(failure.message.orEmpty().startsWith("VERIFICATION_ARTIFACT_FAILED:"), failure.message)
        fixture.assertNoReceiptOrOwnedArtifacts()
    }

    @Test
    fun eventAppendFailureDeletesPromotedArtifactAndCreatesNoReceipt() = withFixture(
        eventWriterFailure = true,
    ) { fixture ->
        assertFailsWith<RuntimeException> { fixture.verify() }

        fixture.assertNoReceiptOrOwnedArtifacts()
    }

    private fun assertFail(receipt: FeedbackVerificationReceiptDto, kind: String) {
        assertEquals(FeedbackVerificationVerdict.FAIL, receipt.verdict)
        assertCheck(receipt, kind, FeedbackVerificationCheckOutcome.FAILED)
    }

    private fun assertCheck(
        receipt: FeedbackVerificationReceiptDto,
        kind: String,
        outcome: FeedbackVerificationCheckOutcome,
    ) {
        assertTrue(
            receipt.checks.any { it.kind == kind && it.outcome == outcome },
            "Expected $kind/$outcome in ${receipt.checks}",
        )
    }

    private fun withFixture(
        baselineActivity: String? = "MainActivity",
        visualArea: Boolean = false,
        eventWriterFailure: Boolean = false,
        artifactHooks: (Fixture) -> VerificationArtifactStoreHooks = { VerificationArtifactStoreHooks() },
        block: (Fixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("feedback-verification-coordinator-").toFile()
        try {
            val fixture = Fixture.create(
                root = root,
                baselineActivity = baselineActivity,
                visualArea = visualArea,
                eventWriterFailure = eventWriterFailure,
            )
            fixture.artifactStore = FeedbackVerificationArtifactStore(root, hooks = artifactHooks(fixture))
            block(fixture)
        } finally {
            root.deleteRecursively()
        }
    }

    @Suppress("LongParameterList")
    private class Fixture private constructor(
        val root: File,
        val store: FeedbackSessionStore,
        val bridge: FakeFixThisBridge,
        val sourceFile: File,
        val installedAt: Long,
        val sessionId: String,
        val itemId: String,
        private val targetEvidenceService: TargetEvidenceService,
        private val previewCaptureService: PreviewCaptureService,
    ) {
        lateinit var artifactStore: FeedbackVerificationArtifactStore

        fun service(): FeedbackSessionService = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = PACKAGE_NAME,
        )

        fun verify(vararg assertions: FeedbackVerificationAssertionDto): FeedbackVerificationReceiptDto = runBlocking {
            coordinator().verify(
                FeedbackVerificationRequest(
                    sessionId = sessionId,
                    itemId = itemId,
                    assertions = assertions.toList().ifEmpty { listOf(targetPresent()) },
                ),
            )
        }

        fun persistedReceipt(): FeedbackVerificationReceiptDto = store.getSession(sessionId).verificationReceipts.single()

        fun assertNoReceiptOrOwnedArtifacts() {
            assertTrue(store.getSession(sessionId).verificationReceipts.isEmpty())
            assertFalse(receiptDirectory().exists(), "Promoted receipt directory leaked")
            val verificationRoot = root.resolve(".fixthis/feedback-sessions/$sessionId/verification")
            assertTrue(
                verificationRoot.listFiles().orEmpty().none {
                    it.name.startsWith(".$RECEIPT_ID.tmp-") || it.name == ".$RECEIPT_ID.reserve"
                },
                "Temporary verification artifact leaked: ${verificationRoot.listFiles().orEmpty().toList()}",
            )
            assertFalse(captureDirectory().exists(), "Temporary live capture leaked")
        }

        private fun coordinator(): FeedbackVerificationCoordinator = FeedbackVerificationCoordinator(
            bridge = bridge,
            sessionAccess = object : FeedbackVerificationSessionAccess {
                override fun getSession(sessionId: String): SessionDto = store.getSession(sessionId)

                override fun captureContext(
                    sessionId: String,
                    itemId: String,
                    assertions: List<FeedbackVerificationAssertionDto>,
                ): FeedbackVerificationStartContext = store.captureVerificationContext(sessionId, itemId, assertions)

                override fun validateContext(context: FeedbackVerificationStartContext) {
                    store.validateVerificationContext(context)
                }

                override fun attachReceipt(
                    context: FeedbackVerificationStartContext,
                    receipt: FeedbackVerificationReceiptDto,
                ): SessionDto = store.attachVerificationReceipt(context, receipt)
            },
            previewCaptureService = previewCaptureService,
            targetEvidenceService = targetEvidenceService,
            freshnessProbe = HostSourceFreshnessProbe(root),
            artifactStore = artifactStore,
            clock = { RECEIPT_TIME },
            idGenerator = { RECEIPT_ID },
        )

        private fun receiptDirectory(): File = root.resolve(".fixthis/feedback-sessions/$sessionId/verification/$RECEIPT_ID")

        fun captureDirectory(): File = root.resolve(".fixthis/preview-cache/$sessionId/verification-$RECEIPT_ID")

        companion object {
            @Suppress("LongMethod")
            fun create(
                root: File,
                baselineActivity: String?,
                visualArea: Boolean,
                eventWriterFailure: Boolean,
            ): Fixture {
                val node = payButton()
                val captureRoots = listOf(
                    SnapshotRootDto(
                        rootIndex = 0,
                        boundsInWindow = FixThisRect(0f, 0f, 720f, 1600f),
                        mergedNodes = listOf(node),
                    ),
                )
                val sourceIndex = SourceIndex(
                    entries = listOf(
                        SourceIndexEntry(
                            file = SOURCE_PATH,
                            line = 1,
                            text = listOf("Pay now"),
                            testTags = listOf("pay"),
                            activityNames = listOf("MainActivity"),
                        ),
                    ),
                )
                val bridge = FakeFixThisBridge(
                    packageName = PACKAGE_NAME,
                    captureRoots = captureRoots,
                    sourceIndex = sourceIndex,
                ).apply {
                    installEpochMillis = INSTALLED_AT
                }
                val paths = FeedbackSessionPaths(root)
                val persistence = FeedbackSessionPersistence(paths)
                var failEventWrites = false
                var now = 1_000L
                var id = 0
                val store = FeedbackSessionStore(
                    clock = { ++now },
                    idGenerator = { "id-${++id}" },
                    persistence = persistence,
                    eventLogWriterProvider = { sessionId ->
                        EventLogWriter(
                            directory = paths.eventLogDirectory(sessionId),
                            onWriteHook = {
                                if (failEventWrites) throw IOException("event disk full")
                            },
                        )
                    },
                    eventLogReaderProvider = { sessionId -> EventLogReader(paths.eventLogDirectory(sessionId)) },
                )
                val sourceFile = root.resolve(SOURCE_PATH).apply {
                    parentFile.mkdirs()
                    writeText("fun PayButton() = Unit")
                    assertTrue(setLastModified(INSTALLED_AT - 1_000L))
                }
                val session = store.openSession(PACKAGE_NAME, root.absolutePath)
                val screen = store.addScreen(
                    session.sessionId,
                    SnapshotDto(
                        screenId = "pending",
                        capturedAtEpochMillis = 500L,
                        activityName = baselineActivity,
                        displayName = "Checkout",
                        roots = captureRoots,
                        sourceIndexAvailable = true,
                        fingerprint = "baseline-fingerprint",
                    ),
                )
                val target = if (visualArea) {
                    AnnotationTargetDto.Area(node.boundsInWindow)
                } else {
                    AnnotationTargetDto.Node(node.uid, node.boundsInWindow)
                }
                val item = store.addItem(
                    session.sessionId,
                    AnnotationDto(
                        itemId = "pending",
                        screenId = screen.screenId,
                        createdAtEpochMillis = 0L,
                        updatedAtEpochMillis = 0L,
                        target = target,
                        selectedNode = node.takeUnless { visualArea },
                        comment = "Verify checkout button",
                        delivery = FeedbackDelivery.DRAFT,
                    ),
                )
                store.sendDraftToAgent(session.sessionId, markdownSnapshot = "handoff")
                store.claimFeedback(session.sessionId, item.itemId, "working")
                failEventWrites = eventWriterFailure
                val targetEvidenceService = TargetEvidenceService(
                    bridge = bridge,
                    sourceIndexRegistry = SourceIndexRegistry(),
                    projectRoot = root,
                )
                val previewCaptureService = PreviewCaptureService(
                    bridge = bridge,
                    store = store,
                    previewCache = PreviewSnapshotCache(3),
                    targetEvidenceService = targetEvidenceService,
                )
                return Fixture(
                    root = root,
                    store = store,
                    bridge = bridge,
                    sourceFile = sourceFile,
                    installedAt = INSTALLED_AT,
                    sessionId = session.sessionId,
                    itemId = item.itemId,
                    targetEvidenceService = targetEvidenceService,
                    previewCaptureService = previewCaptureService,
                )
            }

            private fun payButton(): FixThisNode = FixThisNode(
                uid = "pay-button",
                composeNodeId = 1,
                rootIndex = 0,
                treeKind = TreeKind.MERGED,
                boundsInWindow = FixThisRect(20f, 40f, 220f, 120f),
                text = listOf("Pay now"),
                role = "Button",
                testTag = "pay",
            )
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.test.checkout"
        const val SOURCE_PATH = "src/main/kotlin/CheckoutScreen.kt"
        const val INSTALLED_AT = 1_700_000_000_000L
        const val RECEIPT_TIME = 1_700_000_100_000L
        const val RECEIPT_ID = "receipt-1"

        fun targetPresent(): FeedbackVerificationAssertionDto = FeedbackVerificationAssertionDto(
            kind = FeedbackVerificationAssertionKind.TARGET_PRESENT,
        )

        fun textPresent(value: String): FeedbackVerificationAssertionDto = FeedbackVerificationAssertionDto(
            kind = FeedbackVerificationAssertionKind.TEXT_PRESENT,
            value = value,
            role = "Button",
        )
    }
}
