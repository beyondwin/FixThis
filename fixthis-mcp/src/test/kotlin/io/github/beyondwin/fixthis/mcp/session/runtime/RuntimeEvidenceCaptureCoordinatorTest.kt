package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceCapabilities
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceContext
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceKind
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceResult
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceStatus
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.tools.RuntimeEvidenceBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeEvidenceCaptureCoordinatorTest {
    @Test
    fun baselineLinksOneSharedCaptureToTwoItemsOnlyAfterBundleCommit() = runBlocking {
        val fixture = fixture(itemCount = 2)

        val actual = fixture.coordinator.collect(fixture.request(itemIds = listOf("i1", "i2")))

        assertEquals(RuntimeEvidenceStatus.COMPLETE, actual.status)
        assertEquals(listOf("i1", "i2"), actual.linkedItemIds)
        assertEquals(3, actual.attachmentIds.size)
        assertEquals(1, fixture.artifacts.commits.size)
        assertEquals(setOf(actual.captureId), fixture.store.getSession("s1").runtimeEvidence.map { it.captureId }.toSet())
        assertTrue(fixture.store.getSession("s1").items.all { it.runtimeEvidenceIds == actual.attachmentIds })
    }

    @Test
    fun processWideCollectorConcurrencyNeverExceedsTwoAcrossCoordinatorInstances() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val bridge = FakeRuntimeEvidenceBridge(
            timing = FakeBridgeTiming(collectDelayMillis = 40),
            coordination = FakeBridgeCoordination(active = active, maximum = maximum),
        )
        val first = fixture(bridge = bridge, sessionId = "s-process-a")
        val second = fixture(bridge = bridge, sessionId = "s-process-b")

        awaitAll(
            async { first.coordinator.collect(first.request()) },
            async { second.coordinator.collect(second.request()) },
        )

        assertTrue(maximum.get() <= 2, "observed collector concurrency ${maximum.get()}")
    }

    @Test
    fun deadlineReturnsTimeoutWithoutLinkingWhenNoCollectorCompletes() = runBlocking {
        val fixture = fixture(
            bridge = FakeRuntimeEvidenceBridge(timing = FakeBridgeTiming(collectDelayMillis = 200)),
            timing = FixtureTiming(deadlineMillis = 30),
        )

        val actual = fixture.coordinator.collect(fixture.request())

        assertEquals(RuntimeEvidenceStatus.FAILED, actual.status)
        assertEquals(RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT, actual.failureReason)
        assertTrue(actual.linkedItemIds.isEmpty())
        assertTrue(fixture.store.getSession("s1").runtimeEvidence.isEmpty())
    }

    @Test
    fun deadlineKeepsFastArtifactSynthesizesSlowTimeoutsAndReleasesPermits() = runBlocking {
        val bridge = FakeRuntimeEvidenceBridge(
            timing = FakeBridgeTiming(
                delayByKind = mapOf(
                    CliRuntimeEvidenceKind.MEMORY_SUMMARY to 200,
                    CliRuntimeEvidenceKind.FRAME_SUMMARY to 200,
                ),
            ),
        )
        val fixture = fixture(bridge = bridge, timing = FixtureTiming(deadlineMillis = 80))

        val timed = fixture.coordinator.collect(fixture.request())
        val immediate = fixture.coordinator.collect(fixture.request(preset = RuntimeEvidencePreset.LOGS))

        assertEquals(RuntimeEvidenceStatus.PARTIAL, timed.status)
        assertEquals(setOf(RuntimeEvidenceType.LOGCAT_WINDOW), fixture.artifacts.commits.first().inputs.map { it.type }.toSet())
        val stored = fixture.store.getSession("s1").runtimeEvidence.filter { it.captureId == timed.captureId }
        assertEquals(RuntimeEvidenceStatus.COMPLETE, stored.single { it.type == RuntimeEvidenceType.LOGCAT_WINDOW }.status)
        assertEquals(
            2,
            stored.count {
                it.status == RuntimeEvidenceStatus.FAILED &&
                    it.failureReason == RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT
            },
        )
        assertEquals(RuntimeEvidenceStatus.COMPLETE, immediate.status)
    }

    @Test
    fun automaticCallsDeduplicateInFlightAndEntryIsRemovedAfterCompletion() = runBlocking {
        val bridge = FakeRuntimeEvidenceBridge(
            timing = FakeBridgeTiming(collectDelayMillis = 30),
            coordination = FakeBridgeCoordination(startContextBarrier = SuspendBarrier(2)),
        )
        val fixture = fixture(bridge = bridge)
        val request = fixture.request(trigger = RuntimeEvidenceTrigger.HANDOFF_AUTO)

        val results = awaitAll(
            async { fixture.coordinator.collect(request) },
            async { fixture.coordinator.collect(request) },
        )
        val third = fixture.coordinator.collect(request)

        assertEquals(results[0].captureId, results[1].captureId)
        assertNotEquals(results[0].captureId, third.captureId)
        assertEquals(6, bridge.collectCalls.get())
    }

    @Test
    fun startContextCapabilitiesCollectorsAndEndContextShareOneWallClockDeadline() = runBlocking {
        val bridge = FakeRuntimeEvidenceBridge(
            timing = FakeBridgeTiming(
                contextDelayMillis = 20,
                capabilitiesDelayMillis = 20,
                collectDelayMillis = 200,
            ),
        )
        val fixture = fixture(bridge = bridge, timing = FixtureTiming(deadlineMillis = 90))
        val started = System.nanoTime()

        val actual = fixture.coordinator.collect(fixture.request())
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(elapsedMillis < 180, "capture exceeded total budget: $elapsedMillis ms")
        assertEquals(RuntimeEvidenceStatus.FAILED, actual.status)
        assertTrue(
            actual.failureReason in setOf(
                RuntimeEvidenceFailureReason.CAPTURE_TIMEOUT,
                RuntimeEvidenceFailureReason.DEVICE_UNAVAILABLE,
            ),
        )
    }

    @Test
    fun cancellingFirstAutomaticWaiterDoesNotCancelSharedCapture() = runBlocking {
        val bridge = FakeRuntimeEvidenceBridge(timing = FakeBridgeTiming(collectDelayMillis = 50))
        val fixture = fixture(bridge = bridge)
        val request = fixture.request(trigger = RuntimeEvidenceTrigger.HANDOFF_AUTO)
        val first = async { fixture.coordinator.collect(request) }
        delay(10)
        val second = async { fixture.coordinator.collect(request) }

        first.cancel(CancellationException("caller left"))
        first.cancelAndJoin()
        val actual = second.await()

        assertEquals(RuntimeEvidenceStatus.COMPLETE, actual.status)
        assertEquals(3, bridge.collectCalls.get())
    }

    @Test
    fun serialAndInstallChangesFailWithoutLinkingAndDeleteUnlinkedBundle() = runBlocking {
        val serial = fixture(
            bridge = FakeRuntimeEvidenceBridge(
                contexts = listOf(context(), context().copy(deviceSerial = "other")),
            ),
        )
        val install = fixture(
            bridge = FakeRuntimeEvidenceBridge(
                contexts = listOf(context(), context().copy(installEpochMillis = 99)),
            ),
            sessionId = "s-install",
        )

        val serialResult = serial.coordinator.collect(serial.request())
        val installResult = install.coordinator.collect(install.request())

        listOf(serialResult, installResult).forEach {
            assertEquals(RuntimeEvidenceStatus.FAILED, it.status)
            assertEquals(RuntimeEvidenceFailureReason.CONTEXT_CHANGED, it.failureReason)
            assertTrue(it.linkedItemIds.isEmpty())
        }
        assertTrue(serial.artifacts.commits.isEmpty())
        assertTrue(install.artifacts.commits.isEmpty())
        assertTrue(serial.artifacts.deletes.isEmpty())
        assertTrue(install.artifacts.deletes.isEmpty())
    }

    @Test
    fun emptyUnsupportedOutputStillRunsEndContextAndRejectsSerialDrift() = runBlocking {
        val bridge = FakeRuntimeEvidenceBridge(
            contexts = listOf(context(), context().copy(deviceSerial = "other")),
            results = mapOf(
                CliRuntimeEvidenceKind.LOGCAT_WINDOW to result(
                    CliRuntimeEvidenceKind.LOGCAT_WINDOW,
                    CliRuntimeEvidenceStatus.UNSUPPORTED,
                    "",
                    failureCode = "unsupported",
                ),
            ),
        )
        val fixture = fixture(bridge = bridge)

        val actual = fixture.coordinator.collect(fixture.request(preset = RuntimeEvidencePreset.LOGS))

        assertEquals(RuntimeEvidenceStatus.FAILED, actual.status)
        assertEquals(RuntimeEvidenceFailureReason.CONTEXT_CHANGED, actual.failureReason)
        assertTrue(fixture.store.getSession("s1").runtimeEvidence.isEmpty())
    }

    @Test
    fun pidRestartKeepsUsefulEvidenceAsPartial() = runBlocking {
        val fixture = fixture(
            bridge = FakeRuntimeEvidenceBridge(contexts = listOf(context(), context().copy(pid = 202))),
        )

        val actual = fixture.coordinator.collect(fixture.request())

        assertEquals(RuntimeEvidenceStatus.PARTIAL, actual.status)
        assertTrue(RuntimeEvidenceWarning.PROCESS_RESTARTED in actual.warnings)
        assertEquals(3, actual.attachmentIds.size)
    }

    @Test
    fun missingStartPidStillCollectsCrashLogsAndMarksMemoryAndFramesNotRunning() = runBlocking {
        val fixture = fixture(
            bridge = FakeRuntimeEvidenceBridge(contexts = listOf(context().copy(pid = null), context().copy(pid = null))),
        )

        val actual = fixture.coordinator.collect(fixture.request())

        assertEquals(RuntimeEvidenceStatus.PARTIAL, actual.status)
        assertEquals(1, (fixture.coordinatorBridge as? FakeRuntimeEvidenceBridge)?.collectCalls?.get())
        assertEquals(
            2,
            fixture.store.getSession("s1").runtimeEvidence.count {
                it.failureReason == RuntimeEvidenceFailureReason.PROCESS_NOT_RUNNING
            },
        )
    }

    @Test
    fun fingerprintDriftLowersTrustWithoutInvalidatingCapture() = runBlocking {
        val fixture = fixture(
            bridge = FakeRuntimeEvidenceBridge(
                contexts = listOf(context(), context().copy(currentScreenFingerprint = "new-screen")),
            ),
            timing = FixtureTiming(clockValues = listOf(3_000, 3_100)),
        )

        val actual = fixture.coordinator.collect(fixture.request())

        assertEquals(RuntimeEvidenceStatus.PARTIAL, actual.status)
        assertTrue(RuntimeEvidenceWarning.CONTEXT_CHANGED in actual.warnings)
        assertTrue(fixture.store.getSession("s1").runtimeEvidence.all { it.proximity != RuntimeEvidenceProximity.NEAR })
    }

    @Test
    fun proximityUsesInclusiveThreeAndFifteenSecondBoundaries() = runBlocking {
        val near = fixture(timing = FixtureTiming(clockValues = listOf(4_000, 4_100)), sessionId = "near")
        val delayed = fixture(timing = FixtureTiming(clockValues = listOf(16_000, 16_100)), sessionId = "delayed")
        val stale = fixture(timing = FixtureTiming(clockValues = listOf(16_001, 16_101)), sessionId = "stale")

        near.coordinator.collect(near.request())
        delayed.coordinator.collect(delayed.request())
        stale.coordinator.collect(stale.request())

        assertEquals(RuntimeEvidenceProximity.NEAR, near.store.getSession("near").runtimeEvidence.first().proximity)
        assertEquals(RuntimeEvidenceProximity.DELAYED, delayed.store.getSession("delayed").runtimeEvidence.first().proximity)
        assertEquals(RuntimeEvidenceProximity.STALE, stale.store.getSession("stale").runtimeEvidence.first().proximity)
        assertTrue(stale.store.getSession("stale").runtimeEvidence.all { RuntimeEvidenceWarning.STALE_WINDOW in it.warnings })
    }

    @Test
    fun negativeCaptureDeltaIsNeverClassifiedNear() = runBlocking {
        val fixture = fixture(timing = FixtureTiming(clockValues = listOf(999, 1_100)))

        fixture.coordinator.collect(fixture.request())

        assertTrue(
            fixture.store.getSession("s1").runtimeEvidence.all {
                it.proximity == RuntimeEvidenceProximity.STALE && RuntimeEvidenceWarning.CONTEXT_CHANGED in it.warnings
            },
        )
    }

    @Test
    fun missingScreenClosedSessionAndDeletedItemFailWithoutLinks() = runBlocking {
        val missingScreen = fixture()
        val closed = fixture(sessionId = "closed")
        closed.store.replaceSessionForDomain(closed.store.getSession("closed").copy(status = SessionStatusDto.CLOSED))
        val deleteBridge = FakeRuntimeEvidenceBridge()
        val deleted = fixture(bridge = deleteBridge, sessionId = "deleted")
        deleteBridge.afterFirstCollect = { deleted.store.deleteDraftItem("deleted", "i1") }

        val missingResult = missingScreen.coordinator.collect(missingScreen.request(screenId = "unknown"))
        val closedResult = closed.coordinator.collect(closed.request())
        val deletedResult = deleted.coordinator.collect(deleted.request())

        listOf(missingResult, closedResult, deletedResult).forEach {
            assertEquals(RuntimeEvidenceStatus.FAILED, it.status)
            assertEquals(RuntimeEvidenceFailureReason.CONTEXT_CHANGED, it.failureReason)
            assertTrue(it.linkedItemIds.isEmpty())
        }
        assertTrue(deleted.artifacts.commits.isEmpty())
        assertTrue(deleted.artifacts.deletes.isEmpty())
    }

    @Test
    fun unsupportedPermissionTruncationAndRedactionRemainExplicit() = runBlocking {
        val bridge = FakeRuntimeEvidenceBridge(
            results = mapOf(
                CliRuntimeEvidenceKind.LOGCAT_WINDOW to result(
                    CliRuntimeEvidenceKind.LOGCAT_WINDOW,
                    CliRuntimeEvidenceStatus.PARTIAL,
                    "api_key=raw-secret java.lang.IllegalStateException",
                    warnings = setOf("output_truncated"),
                    failureCode = "permission_denied",
                ),
                CliRuntimeEvidenceKind.MEMORY_SUMMARY to result(
                    CliRuntimeEvidenceKind.MEMORY_SUMMARY,
                    CliRuntimeEvidenceStatus.UNSUPPORTED,
                    "",
                    failureCode = "unsupported",
                ),
                CliRuntimeEvidenceKind.FRAME_SUMMARY to result(
                    CliRuntimeEvidenceKind.FRAME_SUMMARY,
                    CliRuntimeEvidenceStatus.COMPLETE,
                    "Janky frames: 2",
                ),
            ),
        )
        val fixture = fixture(bridge = bridge)

        val actual = fixture.coordinator.collect(fixture.request())

        assertEquals(RuntimeEvidenceStatus.PARTIAL, actual.status)
        assertTrue(RuntimeEvidenceWarning.OUTPUT_TRUNCATED in actual.warnings)
        assertTrue(RuntimeEvidenceWarning.REDACTION_APPLIED in actual.warnings)
        assertFalse(fixture.artifacts.commits.flatMap { it.inputs }.any { it.redactedText.contains("raw-secret") })
        assertFalse(fixture.store.getSession("s1").runtimeEvidence.any { it.summary.contains("raw-secret") })
    }

    @Test
    fun persistedCommandMetadataUsesFixedIdentityInsteadOfRawArguments() = runBlocking {
        val bridge = FakeRuntimeEvidenceBridge(
            results = defaultResults().mapValues { (_, value) ->
                value.copy(command = listOf("/Users/dev/Android/sdk/adb", "shell", "api_key=raw-secret"))
            },
        )
        val fixture = fixture(bridge = bridge)

        fixture.coordinator.collect(fixture.request())

        fixture.store.getSession("s1").runtimeEvidence.forEach { attachment ->
            assertTrue(attachment.captureCommand.orEmpty().startsWith("adb:"))
            assertFalse(attachment.captureCommand.orEmpty().contains("/Users"))
            assertFalse(attachment.captureCommand.orEmpty().contains("raw-secret"))
        }
    }

    @Test
    fun quotaAndWriteFailuresReturnStableFailureWithoutEventLinks() = runBlocking {
        val quota = fixture(effects = FixtureEffects(artifactFailure = RuntimeEvidenceArtifactQuotaException("full")))
        val write = fixture(effects = FixtureEffects(artifactFailure = IllegalStateException("disk")), sessionId = "write")

        val quotaResult = quota.coordinator.collect(quota.request())
        val writeResult = write.coordinator.collect(write.request())

        assertEquals(RuntimeEvidenceFailureReason.QUOTA_EXCEEDED, quotaResult.failureReason)
        assertEquals(RuntimeEvidenceFailureReason.ARTIFACT_WRITE_FAILED, writeResult.failureReason)
        assertTrue(quota.store.getSession("s1").runtimeEvidence.isEmpty())
        assertTrue(write.store.getSession("write").runtimeEvidence.isEmpty())
    }

    @Test
    fun onlyTransientFailuresRetryOnce() = runBlocking {
        val transient = FakeRuntimeEvidenceBridge(
            scriptedResults = ArrayDeque(
                listOf(
                    result(
                        CliRuntimeEvidenceKind.LOGCAT_WINDOW,
                        CliRuntimeEvidenceStatus.FAILED,
                        "",
                        failureCode = "adb_command_failed",
                    ),
                    result(CliRuntimeEvidenceKind.LOGCAT_WINDOW, CliRuntimeEvidenceStatus.COMPLETE, "ok"),
                ),
            ),
        )
        val permission = FakeRuntimeEvidenceBridge(
            results = mapOf(
                CliRuntimeEvidenceKind.LOGCAT_WINDOW to result(
                    CliRuntimeEvidenceKind.LOGCAT_WINDOW,
                    CliRuntimeEvidenceStatus.FAILED,
                    "",
                    failureCode = "permission_denied",
                ),
            ),
        )
        val transientFixture = fixture(bridge = transient)
        val permissionFixture = fixture(bridge = permission, sessionId = "permission")

        transientFixture.coordinator.collect(transientFixture.request(preset = RuntimeEvidencePreset.LOGS))
        permissionFixture.coordinator.collect(permissionFixture.request(preset = RuntimeEvidencePreset.LOGS))

        assertEquals(2, transient.collectCalls.get())
        assertEquals(1, permission.collectCalls.get())
    }

    @Test
    fun genericLinkWriterFailureKeepsCommittedBundleForReplayButContextRejectionDeletesIt() = runBlocking {
        // The concrete store's event-before-snapshot behavior is covered by RuntimeEvidenceSessionEventTest.
        // This assertion pins the coordinator boundary through a deliberately failing link port.
        val fixture = fixture(
            effects = FixtureEffects(
                linker = { _, _, _, _, _ -> throw IllegalStateException("snapshot failed after event") },
            ),
        )

        val actual = fixture.coordinator.collect(fixture.request())

        assertEquals(RuntimeEvidenceStatus.FAILED, actual.status)
        assertTrue(fixture.artifacts.deletes.isEmpty())
        assertTrue(fixture.artifacts.commits.isNotEmpty())
        assertNull(actual.skippedReason)
    }

    private fun fixture(
        bridge: FakeRuntimeEvidenceBridge = FakeRuntimeEvidenceBridge(),
        itemCount: Int = 1,
        sessionId: String = "s1",
        timing: FixtureTiming = FixtureTiming(),
        effects: FixtureEffects = FixtureEffects(),
    ): Fixture {
        val deadlineMillis = timing.deadlineMillis
        val clockValues = timing.clockValues
        val clock = ArrayDeque(clockValues)
        val store = FeedbackSessionStore(clock = { clockValues.last() }, idGenerator = AtomicIds()::next)
        store.replaceSessionForDomain(session(sessionId, itemCount))
        val artifacts = RecordingArtifactStore(effects.artifactFailure)
        val coordinator = RuntimeEvidenceCaptureCoordinator(
            bridge = bridge,
            store = store,
            projectRoot = java.io.File("/tmp/$sessionId"),
            artifactStore = artifacts,
            dependencies = RuntimeEvidenceCaptureDependencies(
                redactor = RuntimeEvidenceRedactor(),
                summarizer = RuntimeEvidenceSummarizer(RuntimeEvidenceRedactor()),
                idGenerator = AtomicIds("capture")::next,
                clock = { if (clock.isEmpty()) clockValues.last() else clock.removeFirst() },
                deadlineMillis = deadlineMillis,
                linker = effects.linker,
            ),
        )
        return Fixture(store, artifacts, coordinator, sessionId, bridge)
    }

    private data class Fixture(
        val store: FeedbackSessionStore,
        val artifacts: RecordingArtifactStore,
        val coordinator: RuntimeEvidenceCaptureCoordinator,
        val sessionId: String,
        val coordinatorBridge: RuntimeEvidenceBridge,
    ) {
        fun request(
            itemIds: List<String> = listOf("i1"),
            screenId: String = "screen1",
            preset: RuntimeEvidencePreset = RuntimeEvidencePreset.BASELINE,
            trigger: RuntimeEvidenceTrigger = RuntimeEvidenceTrigger.MCP_MANUAL,
        ) = RuntimeEvidenceCaptureRequest(sessionId, itemIds, screenId, preset, trigger)
    }

    private data class FixtureTiming(
        val deadlineMillis: Long = 2_500,
        val clockValues: List<Long> = listOf(2_000, 2_100),
    )

    private data class FixtureEffects(
        val artifactFailure: RuntimeException? = null,
        val linker: RuntimeEvidenceLinker? = null,
    )

    private fun session(id: String, itemCount: Int): SessionDto = SessionDto(
        sessionId = id,
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/tmp/$id",
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        screens = listOf(SnapshotDto("screen1", 1_000, displayName = "Screen", fingerprint = "frozen")),
        items = (1..itemCount).map { index ->
            AnnotationDto(
                itemId = "i$index",
                screenId = "screen1",
                createdAtEpochMillis = 1,
                updatedAtEpochMillis = 1,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                comment = "comment $index",
            )
        },
    )

    private class AtomicIds(private val prefix: String = "id") {
        private val next = AtomicInteger()
        fun next(): String = "$prefix-${next.incrementAndGet()}"
    }
}

private class FakeRuntimeEvidenceBridge(
    contexts: List<CliRuntimeEvidenceContext> = listOf(context(), context()),
    private val results: Map<CliRuntimeEvidenceKind, CliRuntimeEvidenceResult> = defaultResults(),
    private val timing: FakeBridgeTiming = FakeBridgeTiming(),
    private val coordination: FakeBridgeCoordination = FakeBridgeCoordination(),
    private val scriptedResults: ArrayDeque<CliRuntimeEvidenceResult> = ArrayDeque(),
) : RuntimeEvidenceBridge {
    private val contexts = ArrayDeque(contexts)
    val collectCalls = AtomicInteger()
    var afterFirstCollect: (() -> Unit)? = null

    override fun capabilities(packageName: String): CliRuntimeEvidenceCapabilities {
        if (timing.capabilitiesDelayMillis > 0) Thread.sleep(timing.capabilitiesDelayMillis)
        return CliRuntimeEvidenceCapabilities(
            baselineAvailable = true,
            supportedCollectors = setOf(
                CliRuntimeEvidenceKind.LOGCAT_WINDOW,
                CliRuntimeEvidenceKind.MEMORY_SUMMARY,
                CliRuntimeEvidenceKind.FRAME_SUMMARY,
            ),
        )
    }

    override suspend fun context(packageName: String): CliRuntimeEvidenceContext {
        coordination.startContextBarrier?.await()
        if (timing.contextDelayMillis > 0) delay(timing.contextDelayMillis)
        return synchronized(contexts) {
            if (contexts.size > 1) contexts.removeFirst() else contexts.first()
        }
    }

    override suspend fun collect(
        packageName: String,
        kind: CliRuntimeEvidenceKind,
        screenCapturedAtEpochMillis: Long,
    ): CliRuntimeEvidenceResult {
        collectCalls.incrementAndGet()
        val current = coordination.active.incrementAndGet()
        coordination.maximum.accumulateAndGet(current, ::maxOf)
        return try {
            val delayMillis = timing.delayByKind[kind] ?: timing.collectDelayMillis
            if (delayMillis > 0) delay(delayMillis)
            if (collectCalls.get() == 1) afterFirstCollect?.invoke()
            synchronized(scriptedResults) {
                if (scriptedResults.isEmpty()) results.getValue(kind) else scriptedResults.removeFirst()
            }
        } finally {
            coordination.active.decrementAndGet()
        }
    }
}

private data class FakeBridgeTiming(
    val collectDelayMillis: Long = 0,
    val delayByKind: Map<CliRuntimeEvidenceKind, Long> = emptyMap(),
    val contextDelayMillis: Long = 0,
    val capabilitiesDelayMillis: Long = 0,
)

private data class FakeBridgeCoordination(
    val active: AtomicInteger = AtomicInteger(),
    val maximum: AtomicInteger = AtomicInteger(),
    val startContextBarrier: SuspendBarrier? = null,
)

private class SuspendBarrier(private val parties: Int) {
    private val arrivals = AtomicInteger()
    private val released = CompletableDeferred<Unit>()

    suspend fun await() {
        if (arrivals.incrementAndGet() >= parties) released.complete(Unit)
        released.await()
    }
}

private class RecordingArtifactStore(
    private val failure: RuntimeException? = null,
) : RuntimeEvidenceArtifactStore {
    data class Commit(val sessionId: String, val captureId: String, val inputs: List<RuntimeEvidenceArtifactInput>)

    val commits = mutableListOf<Commit>()
    val deletes = mutableListOf<Pair<String, String?>>()

    override fun commit(
        sessionId: String,
        captureId: String,
        inputs: List<RuntimeEvidenceArtifactInput>,
    ): CommittedRuntimeEvidenceBundle {
        failure?.let { throw it }
        commits += Commit(sessionId, captureId, inputs)
        val directory = ".fixthis/runtime-evidence/$sessionId/$captureId"
        return CommittedRuntimeEvidenceBundle(
            captureId,
            directory,
            inputs.associate { it.type to "$directory/${it.fileName}" },
        )
    }

    override fun deleteBundle(sessionId: String, captureId: String) {
        deletes += sessionId to captureId
    }

    override fun cleanupIncomplete() = 0
    override fun cleanupOrphans(referencedCaptureIdsBySession: Map<String, Set<String>>) = 0
    override fun deleteSession(sessionId: String) = Unit
}

private fun context() = CliRuntimeEvidenceContext(
    deviceSerial = "emulator-5554",
    packageName = "io.github.beyondwin.fixthis.sample",
    packageAvailable = true,
    pid = 101,
    installEpochMillis = 10,
    currentActivity = "MainActivity",
    bridgeProtocolVersion = "1.3",
    currentScreenFingerprint = "frozen",
)

private fun result(
    kind: CliRuntimeEvidenceKind,
    status: CliRuntimeEvidenceStatus,
    output: String,
    warnings: Set<String> = emptySet(),
    failureCode: String? = null,
) = CliRuntimeEvidenceResult(
    kind = kind,
    status = status,
    startedAtEpochMillis = 2_000,
    completedAtEpochMillis = 2_010,
    command = listOf("shell", "bounded"),
    output = output,
    warnings = warnings,
    failureCode = failureCode,
)

private fun defaultResults() = mapOf(
    CliRuntimeEvidenceKind.LOGCAT_WINDOW to result(
        CliRuntimeEvidenceKind.LOGCAT_WINDOW,
        CliRuntimeEvidenceStatus.COMPLETE,
        "no matching line",
    ),
    CliRuntimeEvidenceKind.MEMORY_SUMMARY to result(
        CliRuntimeEvidenceKind.MEMORY_SUMMARY,
        CliRuntimeEvidenceStatus.COMPLETE,
        "TOTAL PSS: 1234",
    ),
    CliRuntimeEvidenceKind.FRAME_SUMMARY to result(
        CliRuntimeEvidenceKind.FRAME_SUMMARY,
        CliRuntimeEvidenceStatus.COMPLETE,
        "Janky frames: 1",
    ),
)
