package io.beyondwin.fixthis.mcp.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackSessionRegistryTest {

    @Test
    fun openSessionCreatesNewSessionForResolvedPackage() {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val registry = newRegistry(bridge, store, projectRoot = "/repo")

        val session = registry.openSession(packageNameOverride = null)

        assertEquals("session-1", session.sessionId)
        assertEquals("io.beyondwin.fixthis.sample", session.packageName)
        assertEquals("/repo", session.projectRoot)
        assertEquals(session.sessionId, registry.currentSessionOrNull()?.sessionId)
    }

    @Test
    fun openSessionReusesCurrentSessionWhenPackageAndProjectMatch() {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val registry = newRegistry(bridge, store, projectRoot = "/repo")

        val first = registry.openSession(null)
        val second = registry.openSession(null)

        assertEquals(first.sessionId, second.sessionId)
    }

    @Test
    fun openSessionWithBlankOverrideFallsBackToDefaultPackage() {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val registry = newRegistry(bridge, store, projectRoot = "/repo")

        val session = registry.openSession("  ")

        assertEquals("io.beyondwin.fixthis.sample", session.packageName)
        assertEquals(listOf<String?>("io.beyondwin.fixthis.sample"), bridge.resolvedOverrides)
    }

    @Test
    fun openSessionWithExplicitSessionIdOpensThatSession() {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "session-2").next)
        val registry = newRegistry(bridge, store, projectRoot = "/repo")
        val created = registry.openSession(packageNameOverride = null, newSession = true)
        val other = registry.openSession(packageNameOverride = null, newSession = true)

        val reopened = registry.openSession(packageNameOverride = null, sessionId = created.sessionId)

        assertEquals(created.sessionId, reopened.sessionId)
        assertEquals(2, registry.listSessions().sessions.size)
        assertTrue(other.sessionId != reopened.sessionId)
    }

    @Test
    fun requireCurrentSessionThrowsWhenNoSession() {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val registry = newRegistry(bridge, store, projectRoot = "/repo")

        val error = assertFailsWith<FeedbackSessionException> { registry.requireCurrentSession() }
        assertTrue(error.message.orEmpty().contains("NO_ACTIVE_SESSION"))
    }

    @Test
    fun currentSessionOpensOneWhenAbsent() {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val registry = newRegistry(bridge, store, projectRoot = "/repo")

        val session = registry.currentSession()

        assertEquals("session-1", session.sessionId)
    }

    @Test
    fun closeSessionMarksSessionClosed() {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val registry = newRegistry(bridge, store, projectRoot = "/repo")
        val session = registry.openSession(null, newSession = true)

        val closed = registry.closeSession(session.sessionId)

        assertEquals(SessionStatusDto.CLOSED, closed.status)
        assertNull(registry.currentSessionOrNull())
    }

    @Test
    fun findSessionReturnsNullForMissingSession() {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val registry = newRegistry(bridge, store, projectRoot = "/repo")

        assertNull(registry.findSession("missing"))
    }

    @Test
    fun findSessionReturnsExistingSession() {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val registry = newRegistry(bridge, store, projectRoot = "/repo")
        val session = registry.openSession(null)

        assertNotNull(registry.findSession(session.sessionId))
    }

    @Test
    fun listSessionsResolvesPackageOverride() {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val registry = newRegistry(bridge, store, projectRoot = "/repo")
        registry.openSession(null, newSession = true)

        val sessions = registry.listSessions(packageNameOverride = "io.beyondwin.fixthis.sample")

        assertEquals(listOf("session-1"), sessions.sessions.map { it.sessionId })
    }

    @Test
    fun transientConsoleSessionUsesDefaultPackage() {
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val registry = newRegistry(bridge, store, projectRoot = "/repo")

        val transient = registry.transientConsoleSession()

        assertEquals("", transient.sessionId)
        assertEquals("io.beyondwin.fixthis.sample", transient.packageName)
        assertEquals("/repo", transient.projectRoot)
    }

    private fun newRegistry(
        bridge: FakeFixThisBridge,
        store: FeedbackSessionStore,
        projectRoot: String,
    ): FeedbackSessionRegistry =
        FeedbackSessionRegistry(
            bridge = bridge,
            store = store,
            projectRoot = projectRoot,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )

    private class FakeIds(vararg values: String) {
        private val queue = ArrayDeque(values.toList())
        val next: () -> String = { queue.removeFirst() }
    }

    @Suppress("unused")
    private fun tempDir(prefix: String): File =
        kotlin.io.path.createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }
}
