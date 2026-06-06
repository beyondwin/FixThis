package io.github.beyondwin.fixthis.mcp.fixtures

import io.github.beyondwin.fixthis.mcp.console.FeedbackConsoleServer
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.tools.FixThisBridge
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
        defaultPackageName: String = "io.github.beyondwin.fixthis.sample",
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
        defaultPackageName: String = "io.github.beyondwin.fixthis.sample",
    ): ConsoleSessionFixture = newConsoleSessionFixture(
        clock = clock,
        idGenerator = idGenerator,
        bridge = bridge,
        defaultPackageName = defaultPackageName,
        projectRoot = Files.createTempDirectory(prefix).toString(),
    )
}
