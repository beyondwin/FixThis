package io.github.pointpatch.mcp.session

import io.github.pointpatch.cli.pointPatchJson
import java.io.File
import kotlinx.serialization.Serializable

class FeedbackSessionPersistence(
    private val paths: FeedbackSessionPaths,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun save(session: FeedbackSession) {
        runCatching {
            val sessionDirectory = paths.sessionDirectory(session.sessionId)
            check(sessionDirectory.exists() || sessionDirectory.mkdirs()) {
                "Could not create feedback session directory: ${sessionDirectory.absolutePath}"
            }
            val sessionFile = paths.sessionFile(session.sessionId)
            sessionFile.writeText(pointPatchJson.encodeToString(FeedbackSession.serializer(), session))
            writeIndex()
        }.getOrElse { error ->
            throw FeedbackSessionException(
                "SESSION_SAVE_FAILED: Could not save feedback session ${session.sessionId}: ${error.message}",
            )
        }
    }

    fun load(sessionId: String): FeedbackSession {
        val sessionFile = paths.sessionFile(sessionId)
        if (!sessionFile.isFile) {
            throw FeedbackSessionException("SESSION_NOT_FOUND: Feedback session does not exist: $sessionId")
        }
        return runCatching {
            pointPatchJson.decodeFromString(FeedbackSession.serializer(), sessionFile.readText())
        }.getOrElse { error ->
            throw FeedbackSessionException(
                "SESSION_LOAD_FAILED: Could not load feedback session $sessionId from ${sessionFile.absolutePath}: ${error.message}",
            )
        }
    }

    fun list(packageName: String? = null, includeClosed: Boolean = false): FeedbackSessionList {
        val loaded = loadAll()
        val sessions = loaded.sessions
            .filter { packageName == null || it.packageName == packageName }
            .filter { includeClosed || it.status != FeedbackSessionStatus.CLOSED }
            .map(FeedbackSessionSummary.Companion::from)
            .sortedByDescending { it.updatedAtEpochMillis }
        return FeedbackSessionList(sessions = sessions, skippedSessions = loaded.skipped)
    }

    fun artifactPaths(): FeedbackSessionPaths = paths

    private fun writeIndex() {
        val listed = list(includeClosed = true)
        check(paths.rootDirectory.exists() || paths.rootDirectory.mkdirs()) {
            "Could not create feedback session root: ${paths.rootDirectory.absolutePath}"
        }
        paths.indexFile.writeText(
            pointPatchJson.encodeToString(
                FeedbackSessionIndex.serializer(),
                FeedbackSessionIndex(updatedAtEpochMillis = clock(), sessions = listed.sessions),
            ),
        )
    }

    private fun loadAll(): LoadedSessions {
        if (!paths.rootDirectory.isDirectory) return LoadedSessions(emptyList(), emptyList())
        val sessions = mutableListOf<FeedbackSession>()
        val skipped = mutableListOf<SkippedFeedbackSession>()
        paths.rootDirectory.listFiles().orEmpty()
            .filter { it.isDirectory }
            .forEach { directory ->
                val file = File(directory, "session.json")
                if (file.isFile) {
                    runCatching {
                        pointPatchJson.decodeFromString(FeedbackSession.serializer(), file.readText())
                    }.onSuccess { session ->
                        sessions += session
                    }.onFailure { error ->
                        skipped += SkippedFeedbackSession(
                            path = file.absolutePath,
                            message = error.message ?: error::class.java.simpleName,
                        )
                    }
                }
            }
        return LoadedSessions(sessions, skipped)
    }

    private data class LoadedSessions(
        val sessions: List<FeedbackSession>,
        val skipped: List<SkippedFeedbackSession>,
    )
}

@Serializable
data class FeedbackSessionList(
    val sessions: List<FeedbackSessionSummary> = emptyList(),
    val skippedSessions: List<SkippedFeedbackSession> = emptyList(),
)

@Serializable
data class SkippedFeedbackSession(
    val path: String,
    val message: String,
)
