package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceCapabilities
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceContext
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceKind
import io.github.beyondwin.fixthis.cli.runtime.CliRuntimeEvidenceResult
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEvent
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleRouteTestFixtures.newConsoleSessionFixtureWithTempRoot
import io.github.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPaths
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPersistence
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePolicy
import io.github.beyondwin.fixthis.mcp.tools.FixThisBridge
import io.github.beyondwin.fixthis.mcp.tools.RuntimeEvidenceBridge
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleRuntimeEvidenceRoutesTest {
    @Test
    fun runtimeEvidenceCollectUsesBaselineActiveRuleAndEmitsResolvedSessionEvents() {
        val fixture = newConsoleSessionFixtureWithTempRoot(
            idGenerator = FakeIds("session-a", "item-a", "session-b").next,
        )
        fixture.use { context ->
            val bus = ConsoleEventBus(clock = { 1L })
            val seen = LinkedBlockingQueue<ConsoleEvent>()
            bus.subscribe { event -> seen += event }
            val sessionA = seedItem(context.service, context.store, "screen-a", "item-a")
            context.service.openSession(null, newSession = true)
            val server = FeedbackConsoleServer(context.service, eventBus = bus)
            server.start()
            try {
                val explicit = ConsoleHttpTestClient(server.url).postJson(
                    "/api/items/item-a/runtime-evidence/collect",
                    """{"sessionId":"$sessionA"}""",
                )

                assertEquals(200, explicit.statusCode, explicit.body)
                assertTrue(
                    fixThisJson.parseToJsonElement(explicit.body).jsonObject
                        .getValue("attempted").jsonPrimitive.content.toBoolean(),
                )
                val events = listOfNotNull(seen.poll(2, TimeUnit.SECONDS), seen.poll(2, TimeUnit.SECONDS))
                assertEquals(setOf("session-updated", "sessions-updated"), events.map { it.name }.toSet())
                assertTrue(events.all { it.data.getValue("sessionId").jsonPrimitive.content == sessionA })
            } finally {
                server.stop()
            }
        }

        val activeFixture = newConsoleSessionFixtureWithTempRoot(
            idGenerator = FakeIds("session-1", "item-1").next,
        )
        activeFixture.use { context ->
            seedItem(context.service, context.store, "screen-1", "item-1")
            val response = ConsoleHttpTestClient(context.server.url).postJson(
                "/api/items/item-1/runtime-evidence/collect",
                "{}",
            )
            assertEquals(200, response.statusCode, response.body)
        }
    }

    @Test
    fun runtimeEvidenceCollectPrevalidatesMalformedMethodPresetSessionItemAndClosed() {
        val bridge = CountingRuntimeBridge()
        val store = FeedbackSessionStore(idGenerator = FakeIds("session-1", "item-1").next)
        val root = Files.createTempDirectory("fixthis-console-runtime-route").toFile()
        val service = FeedbackSessionService(bridge, store, root.absolutePath, PACKAGE_NAME)
        val sessionId = seedItem(service, store, "screen-1", "item-1")
        val server = FeedbackConsoleServer(service)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)
            assertEquals(400, client.postJson(collectPath("item-1"), "{").statusCode)
            val get = client.getResponse(collectPath("item-1"))
            assertEquals(405, get.statusCode)
            assertEquals("POST", get.header("Allow"))
            assertEquals(400, client.postJson(collectPath("item-1"), """{"preset":"everything"}""").statusCode)
            assertEquals(404, client.postJson(collectPath("item-1"), """{"sessionId":"missing"}""").statusCode)
            assertEquals(404, client.postJson(collectPath("missing"), """{"sessionId":"$sessionId"}""").statusCode)
            assertEquals(0, bridge.runtimeCalls.get(), "invalid requests must fail before ADB collection")

            service.closeSession(sessionId)
            assertEquals(409, client.postJson(collectPath("item-1"), """{"sessionId":"$sessionId"}""").statusCode)
            assertEquals(0, bridge.runtimeCalls.get(), "closed sessions must fail before ADB collection")
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun runtimeEvidenceCollectNearMissPathsDoNotReachCollection() {
        val bridge = CountingRuntimeBridge()
        val store = FeedbackSessionStore(idGenerator = FakeIds("session-1", "item-1").next)
        val root = Files.createTempDirectory("fixthis-console-runtime-near-miss").toFile()
        val service = FeedbackSessionService(bridge, store, root.absolutePath, PACKAGE_NAME)
        seedItem(service, store, "screen-1", "item-1")
        val server = FeedbackConsoleServer(service)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)
            assertEquals(405, client.postJson("/api/items/item-1/nested/runtime-evidence/collect", "{}").statusCode)
            assertEquals(405, client.postJson("/api/items/item-1/runtime-evidence/collect/", "{}").statusCode)
            assertEquals(405, client.postJson("/api/items/item-1/runtime-evidence/collector", "{}").statusCode)
            assertEquals(0, bridge.runtimeCalls.get())
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun runtimeEvidenceCollectOffSkipsWithoutBridgeAndLegacyManualAttachmentRemainsReachable() {
        val bridge = CountingRuntimeBridge()
        val store = FeedbackSessionStore(idGenerator = FakeIds("session-1", "item-1", "evidence-1").next)
        val root = Files.createTempDirectory("fixthis-console-runtime-off").toFile()
        val service = FeedbackSessionService(bridge, store, root.absolutePath, PACKAGE_NAME)
        val sessionId = seedItem(service, store, "screen-1", "item-1")
        service.updateRuntimeEvidencePolicy(sessionId, RuntimeEvidencePolicy.OFF)
        val server = FeedbackConsoleServer(service)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)
            val skipped = client.postJson(collectPath("item-1"), """{"sessionId":"$sessionId"}""")
            assertEquals(200, skipped.statusCode, skipped.body)
            assertEquals("runtime_evidence_disabled", fixThisJson.parseToJsonElement(skipped.body).jsonObject.getValue("skippedReason").jsonPrimitive.content)
            assertEquals(0, bridge.runtimeCalls.get())

            val legacy = client.postJson(
                "/api/items/item-1/runtime-evidence",
                """{"sessionId":"$sessionId","type":"logcat_window","summary":"safe summary"}""",
            )
            assertEquals(200, legacy.statusCode, legacy.body)
            assertEquals(1, service.getSession(sessionId).runtimeEvidence.size)
        } finally {
            server.stop()
            root.deleteRecursively()
        }
    }

    @Test
    fun runtimeEvidencePolicyPersistsEmitsAndDoesNotShadowHandoffRoutes() {
        val root = Files.createTempDirectory("fixthis-console-runtime-policy").toFile()
        try {
            val paths = FeedbackSessionPaths(root)
            val store = FeedbackSessionStore(
                clock = { 100L },
                idGenerator = FakeIds("session-1").next,
                persistence = FeedbackSessionPersistence(paths, clock = { 100L }),
            )
            val service = FeedbackSessionService(FakeFixThisBridge(), store, root.absolutePath, PACKAGE_NAME)
            val session = service.openSession(null, newSession = true)
            val bus = ConsoleEventBus(clock = { 1L })
            val seen = LinkedBlockingQueue<ConsoleEvent>()
            bus.subscribe { event -> seen += event }
            val server = FeedbackConsoleServer(service, eventBus = bus)
            server.start()
            try {
                val client = ConsoleHttpTestClient(server.url)
                val response = client.postJson(
                    "/api/sessions/${session.sessionId}/runtime-evidence-policy",
                    """{"policy":"off"}""",
                )
                assertEquals(200, response.statusCode, response.body)
                assertEquals(RuntimeEvidencePolicy.OFF, service.getSession(session.sessionId).runtimeEvidencePolicy)
                val events = listOfNotNull(seen.poll(2, TimeUnit.SECONDS), seen.poll(2, TimeUnit.SECONDS))
                assertEquals(setOf("session-updated", "sessions-updated"), events.map { it.name }.toSet())
                assertTrue(events.all { it.data.getValue("sessionId").jsonPrimitive.content == session.sessionId })

                assertEquals(
                    400,
                    client.postJson("/api/sessions/${session.sessionId}/handoff-preview", "{}").statusCode,
                    "exact policy matching must leave handoff-preview routed to its owner",
                )
            } finally {
                server.stop()
            }

            val reopened = FeedbackSessionStore(persistence = FeedbackSessionPersistence(paths, clock = { 200L }))
            assertEquals(RuntimeEvidencePolicy.OFF, reopened.getSession(session.sessionId).runtimeEvidencePolicy)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun runtimeEvidencePolicyRejectsMalformedUnknownMethodSessionAndClosed() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1").next),
            "/repo",
            PACKAGE_NAME,
        )
        val session = service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)
            val path = "/api/sessions/${session.sessionId}/runtime-evidence-policy"
            assertEquals(400, client.postJson(path, "{").statusCode)
            assertEquals(400, client.postJson(path, """{"policy":"sometimes"}""").statusCode)
            assertEquals(404, client.postJson("/api/sessions/missing/runtime-evidence-policy", """{"policy":"manual"}""").statusCode)
            val get = client.getResponse(path)
            assertEquals(405, get.statusCode)
            assertEquals("POST", get.header("Allow"))
            service.closeSession(session.sessionId)
            assertEquals(409, client.postJson(path, """{"policy":"manual"}""").statusCode)
        } finally {
            server.stop()
        }
    }

    private fun seedItem(
        service: FeedbackSessionService,
        store: FeedbackSessionStore,
        screenId: String,
        itemId: String,
    ): String {
        val session = service.openSession(null, newSession = true)
        store.addScreen(
            session.sessionId,
            SnapshotDto(screenId = screenId, capturedAtEpochMillis = 100L, displayName = "Screen"),
        )
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = itemId,
                screenId = screenId,
                createdAtEpochMillis = 100L,
                updatedAtEpochMillis = 100L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                comment = "Capture diagnostics",
            ),
        )
        return session.sessionId
    }

    private fun collectPath(itemId: String) = "/api/items/$itemId/runtime-evidence/collect"

    private companion object {
        const val PACKAGE_NAME = "io.github.beyondwin.fixthis.sample"
    }
}

private class CountingRuntimeBridge :
    FixThisBridge by FakeFixThisBridge(),
    RuntimeEvidenceBridge {
    val runtimeCalls = AtomicInteger()

    override fun capabilities(packageName: String): CliRuntimeEvidenceCapabilities {
        runtimeCalls.incrementAndGet()
        return CliRuntimeEvidenceCapabilities(false, emptySet())
    }

    override suspend fun context(packageName: String): CliRuntimeEvidenceContext {
        runtimeCalls.incrementAndGet()
        error("runtime context should not be requested")
    }

    override suspend fun collect(
        packageName: String,
        kind: CliRuntimeEvidenceKind,
        screenCapturedAtEpochMillis: Long,
    ): CliRuntimeEvidenceResult {
        runtimeCalls.incrementAndGet()
        error("runtime collector should not be requested")
    }
}
