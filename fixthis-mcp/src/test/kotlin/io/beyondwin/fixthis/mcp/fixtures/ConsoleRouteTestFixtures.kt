package io.beyondwin.fixthis.mcp.fixtures

import io.beyondwin.fixthis.mcp.console.FeedbackConsoleServer
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.tools.FixThisBridge
import java.nio.file.Files

internal data class ConsoleSessionFixture(
    val service: FeedbackSessionService,
    val store: FeedbackSessionStore,
    val server: FeedbackConsoleServer,
    val client: ConsoleHttpTestClient,
) : AutoCloseable {
    override fun close() {
        server.stop()
    }
}

internal object ConsoleRouteTestFixtures {
    fun newConsoleSessionFixture(
        clock: () -> Long = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
        idGenerator: () -> String = FakeIds("session-1", "item-1").next,
        bridge: FixThisBridge = FakeFixThisBridge(),
        defaultPackageName: String = "io.beyondwin.fixthis.sample",
        projectRoot: String = "/repo",
    ): ConsoleSessionFixture {
        val store = FeedbackSessionStore(clock = clock, idGenerator = idGenerator)
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = projectRoot,
            defaultPackageName = defaultPackageName,
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        val client = ConsoleHttpTestClient(server.url)
        return ConsoleSessionFixture(service, store, server, client)
    }

    fun newConsoleSessionFixtureWithTempRoot(
        prefix: String = "fixthis-routes-test",
        clock: () -> Long = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
        idGenerator: () -> String = FakeIds("session-1", "item-1").next,
        bridge: FixThisBridge = FakeFixThisBridge(),
        defaultPackageName: String = "io.beyondwin.fixthis.sample",
    ): ConsoleSessionFixture = newConsoleSessionFixture(
        clock = clock,
        idGenerator = idGenerator,
        bridge = bridge,
        defaultPackageName = defaultPackageName,
        projectRoot = Files.createTempDirectory(prefix).toString(),
    )
}
