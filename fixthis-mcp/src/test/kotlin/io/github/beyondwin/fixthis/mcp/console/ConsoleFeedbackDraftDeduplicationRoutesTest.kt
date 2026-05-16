package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.github.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.github.beyondwin.fixthis.mcp.fixtures.FakeLongs
import io.github.beyondwin.fixthis.mcp.session.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.eventlog.EventLogWriter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

private inline fun withTempProject(prefix: String, block: (File) -> Unit) {
    val projectRoot = Files.createTempDirectory(prefix).toFile()
    try {
        block(projectRoot)
    } finally {
        projectRoot.deleteRecursively()
    }
}

private inline fun withConsoleServer(service: FeedbackSessionService, block: (FeedbackConsoleServer) -> Unit) {
    val server = FeedbackConsoleServer(service = service, port = 0)
    server.start()
    try {
        block(server)
    } finally {
        server.stop()
    }
}

private fun eventLogByteCount(root: File): Long {
    if (!root.exists()) return 0L
    return Files.walk(root.toPath()).use { paths ->
        paths
            .filter { Files.isRegularFile(it) }
            .mapToLong { Files.size(it) }
            .sum()
    }
}

class ConsoleFeedbackDraftDeduplicationRoutesTest {
    @Test
    fun batchItemsApiDoesNotAppendEventForFullWorkspaceDuplicate() {
        withTempProject("fixthis-console-noop-event-log") { projectRoot ->
            val eventRoot = File(projectRoot, "events")
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = FeedbackSessionStore(
                    clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L).next,
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1", "item-2").next,
                    eventLogWriterProvider = { sessionId -> EventLogWriter(File(eventRoot, "$sessionId/events")) },
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.beyondwin.fixthis.sample",
            )
            service.openSession(null, newSession = true)
            val preview = runBlocking { service.capturePreview("session-1") }
            val screenJson = fixThisJson.encodeToString(preview.screen)
            val body = """
                {
                  "workspaceId": "workspace-a",
                  "previewId": "${preview.previewId}",
                  "screen": $screenJson,
                  "items": [{
                    "draftItemId": "draft-a",
                    "targetType": "area",
                    "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                    "comment": "save once"
                  }]
                }
            """.trimIndent()

            withConsoleServer(service) { server ->
                val client = ConsoleHttpTestClient(server.url)
                assertEquals(200, client.postJson("/api/items/batch", body).statusCode)
                val eventLogBytesAfterFirstSave = eventLogByteCount(eventRoot)

                assertEquals(
                    200,
                    client.postJson("/api/items/batch", body, headers = mapOf("If-Match" to "*")).statusCode,
                )
                val eventLogBytesAfterDuplicateSave = eventLogByteCount(eventRoot)

                assertEquals(eventLogBytesAfterFirstSave, eventLogBytesAfterDuplicateSave)
            }
        }
    }

    @Test
    @Suppress("LongMethod")
    fun batchItemsApiReusesExistingScreenForPartialWorkspaceDuplicate() {
        withTempProject("fixthis-console-partial-idempotent-batch") { projectRoot ->
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = FeedbackSessionStore(
                    clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L).next,
                    idGenerator = FakeIds(
                        "session-1",
                        "preview-1",
                        "preview-screen-1",
                        "item-1",
                        "item-2",
                        "item-3",
                        "screen-should-not-be-used",
                    ).next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.beyondwin.fixthis.sample",
            )
            service.openSession(null, newSession = true)
            val preview = runBlocking { service.capturePreview("session-1") }
            val screenJson = fixThisJson.encodeToString(preview.screen)
            val pendingScreenJson = fixThisJson.encodeToString(preview.screen.copy(screenId = "pending"))

            withConsoleServer(service) { server ->
                val firstBody = """
                    {
                      "workspaceId": "workspace-a",
                      "previewId": "${preview.previewId}",
                      "screen": $screenJson,
                      "items": [
                        {
                          "draftItemId": "draft-1",
                          "targetType": "area",
                          "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                          "comment": "first"
                        },
                        {
                          "draftItemId": "draft-2",
                          "targetType": "area",
                          "bounds": {"left":40.0,"top":50.0,"right":90.0,"bottom":100.0},
                          "comment": "second"
                        }
                      ]
                    }
                """.trimIndent()
                val secondBody = """
                    {
                      "workspaceId": "workspace-a",
                      "previewId": "${preview.previewId}",
                      "screen": $pendingScreenJson,
                      "items": [
                        {
                          "draftItemId": "draft-1",
                          "targetType": "area",
                          "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                          "comment": "first"
                        },
                        {
                          "draftItemId": "draft-2",
                          "targetType": "area",
                          "bounds": {"left":40.0,"top":50.0,"right":90.0,"bottom":100.0},
                          "comment": "second"
                        },
                        {
                          "draftItemId": "draft-3",
                          "targetType": "area",
                          "bounds": {"left":100.0,"top":110.0,"right":150.0,"bottom":160.0},
                          "comment": "third"
                        }
                      ]
                    }
                """.trimIndent()

                assertEquals(200, ConsoleHttpTestClient(server.url).postJson("/api/items/batch", firstBody).statusCode)
                assertEquals(
                    200,
                    ConsoleHttpTestClient(server.url).postJson(
                        "/api/items/batch",
                        secondBody,
                        headers = mapOf("If-Match" to "*"),
                    ).statusCode,
                )

                val stored = service.getSession("session-1")
                assertEquals(1, stored.screens.size)
                assertEquals(3, stored.items.size)
                assertEquals(setOf(stored.screens.single().screenId), stored.items.map { it.screenId }.toSet())
                assertEquals(listOf("draft-1", "draft-2", "draft-3"), stored.items.map { it.clientDraftItemId })
            }
        }
    }

    @Test
    fun batchItemsApiDedupeLegacyServerItemWithSameTargetAndComment() {
        withTempProject("fixthis-console-legacy-idempotent-batch") { projectRoot ->
            val store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L).next,
                idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "legacy-item", "new-item").next,
            )
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = store,
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.beyondwin.fixthis.sample",
            )
            service.openSession(null, newSession = true)
            val preview = runBlocking { service.capturePreview("session-1") }
            store.addScreenWithItems(
                sessionId = "session-1",
                screen = preview.screen,
                items = listOf(
                    AnnotationDto(
                        itemId = "pending",
                        screenId = preview.screen.screenId,
                        createdAtEpochMillis = 0L,
                        updatedAtEpochMillis = 0L,
                        target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 30f, 40f)),
                        comment = "legacy saved comment",
                    ),
                ),
            )
            val screenJson = fixThisJson.encodeToString(preview.screen)

            withConsoleServer(service) { server ->
                val body = """
                    {
                      "workspaceId": "workspace-a",
                      "previewId": "${preview.previewId}",
                      "screen": $screenJson,
                      "items": [{
                        "draftItemId": "draft-a",
                        "targetType": "area",
                        "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                        "comment": "legacy saved comment"
                      }]
                    }
                """.trimIndent()

                assertEquals(200, ConsoleHttpTestClient(server.url).postJson("/api/items/batch", body).statusCode)
                val stored = service.getSession("session-1")
                assertEquals(1, stored.items.size)
                assertEquals(1, stored.screens.size)
            }
        }
    }

    @Test
    fun batchItemsApiRejectsBlankDraftBeforeLegacyDedupe() {
        withTempProject("fixthis-console-legacy-blank-batch") { projectRoot ->
            val store = FeedbackSessionStore(
                clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L).next,
                idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "legacy-item", "new-item").next,
            )
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = store,
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.beyondwin.fixthis.sample",
            )
            service.openSession(null, newSession = true)
            val preview = runBlocking { service.capturePreview("session-1") }
            val screenJson = fixThisJson.encodeToString(preview.screen)

            withConsoleServer(service) { server ->
                val body = """
                    {
                      "workspaceId": "workspace-a",
                      "previewId": "${preview.previewId}",
                      "screen": $screenJson,
                      "items": [{
                        "draftItemId": "draft-a",
                        "targetType": "area",
                        "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                        "comment": ""
                      }]
                    }
                """.trimIndent()

                assertEquals(422, ConsoleHttpTestClient(server.url).postJson("/api/items/batch", body).statusCode)
                val stored = service.getSession("session-1")
                assertEquals(0, stored.items.size)
                assertEquals(0, stored.screens.size)
            }
        }
    }
}
