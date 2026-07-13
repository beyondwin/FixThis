package io.github.beyondwin.fixthis.mcp.session.lifecycle.store

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.runtime.FileRuntimeEvidenceArtifactStore
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceRedactor
import java.io.File

internal class SessionArtifactJanitor(
    private val persistence: FeedbackSessionPersistence?,
) {
    fun deleteScreenArtifacts(sessionId: String, screenId: String) {
        persistence?.artifactPaths()
            ?.screenArtifactDirectory(sessionId, screenId)
            ?.deleteRecursively()
    }

    fun cleanupRuntimeEvidence(
        sessions: List<SessionDto>,
        referencesComplete: Boolean,
    ) {
        val sessionsByRoot = linkedMapOf<String, MutableList<SessionDto>>()
        persistence?.artifactPaths()?.projectRoot?.canonicalPath?.let { root ->
            sessionsByRoot.getOrPut(root) { mutableListOf() }
        }
        sessions.forEach { session ->
            val root = File(session.projectRoot).canonicalPath
            sessionsByRoot.getOrPut(root) { mutableListOf() } += session
        }
        sessionsByRoot.forEach { (root, rootSessions) ->
            val artifactStore = FileRuntimeEvidenceArtifactStore(File(root), RuntimeEvidenceRedactor())
            artifactStore.cleanupIncomplete()
            if (!referencesComplete) return@forEach
            val references = rootSessions.associate { session ->
                session.sessionId to session.runtimeEvidence.mapNotNull { it.captureId }.toSet()
            }
            artifactStore.cleanupOrphans(references)
        }
    }
}
