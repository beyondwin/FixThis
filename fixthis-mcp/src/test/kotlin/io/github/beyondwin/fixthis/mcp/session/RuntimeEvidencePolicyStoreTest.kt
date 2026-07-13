package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPaths
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPersistence
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePolicy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeEvidencePolicyStoreTest {
    @Test
    fun newSessionsDefaultToAutoOnHandoffAndPolicyPersists() {
        val root = createTempDirectory(prefix = "fixthis-runtime-evidence-policy-").toFile().also { it.deleteOnExit() }
        val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 100L })
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }, persistence = persistence)
        val session = store.openSession("io.example", root.absolutePath)

        assertEquals(RuntimeEvidencePolicy.AUTO_ON_HANDOFF, session.runtimeEvidencePolicy)

        val updated = store.updateRuntimeEvidencePolicy(
            session.sessionId,
            RuntimeEvidencePolicy.AUTO_ON_HANDOFF,
        )
        val reopened = FeedbackSessionStore(clock = { 200L }, persistence = persistence)
            .openExistingSession(session.sessionId)

        assertEquals(RuntimeEvidencePolicy.AUTO_ON_HANDOFF, updated.runtimeEvidencePolicy)
        assertEquals(RuntimeEvidencePolicy.AUTO_ON_HANDOFF, reopened.runtimeEvidencePolicy)
    }
}
