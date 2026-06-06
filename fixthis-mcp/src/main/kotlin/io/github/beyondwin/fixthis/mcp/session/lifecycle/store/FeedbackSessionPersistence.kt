package io.github.beyondwin.fixthis.mcp.session.lifecycle.store

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Suppress("TooManyFunctions")
class FeedbackSessionPersistence(
    private val paths: FeedbackSessionPaths,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun save(session: SessionDto) {
        var tempSessionFile: File? = null
        var tempIndexFile: File? = null
        var sessionBackup: FileBackup? = null
        var indexBackup: FileBackup? = null
        runCatching {
            val sessionDirectory = paths.sessionDirectory(session.sessionId)
            check(sessionDirectory.isDirectory || sessionDirectory.mkdirs()) {
                "Could not create feedback session directory: ${sessionDirectory.absolutePath}"
            }
            check(paths.rootDirectory.isDirectory || paths.rootDirectory.mkdirs()) {
                "Could not create feedback session root: ${paths.rootDirectory.absolutePath}"
            }
            val sessionFile = paths.sessionFile(session.sessionId)
            val sessionTemp = File.createTempFile("session-", ".json.tmp", sessionDirectory)
            tempSessionFile = sessionTemp
            sessionTemp.writeText(fixThisJson.encodeToString(SessionDto.serializer(), session))
            val indexTemp = File.createTempFile("index-", ".json.tmp", paths.rootDirectory)
            tempIndexFile = indexTemp
            indexTemp.writeText(indexJson(session))
            sessionBackup = backupFile(sessionFile, "session-backup-", sessionDirectory)
            indexBackup = backupFile(paths.indexFile, "index-backup-", paths.rootDirectory)
            var sessionChanged = false
            var indexChanged = false
            runCatching {
                replaceFile(sessionTemp, sessionFile)
                tempSessionFile = null
                sessionChanged = true
                replaceFile(indexTemp, paths.indexFile)
                tempIndexFile = null
                indexChanged = true
            }.getOrElse { error ->
                if (indexChanged) runCatching { restoreFile(paths.indexFile, indexBackup) }
                if (sessionChanged) runCatching { restoreFile(sessionFile, sessionBackup) }
                throw error
            }
        }.getOrElse { error ->
            tempSessionFile?.delete()
            tempIndexFile?.delete()
            deleteBackup(sessionBackup)
            deleteBackup(indexBackup)
            throw FeedbackSessionException(
                "SESSION_SAVE_FAILED: Could not save feedback session ${session.sessionId}: ${error.message}",
            )
        }
        deleteBackup(sessionBackup)
        deleteBackup(indexBackup)
    }

    fun load(sessionId: String): SessionDto {
        val sessionFile = paths.sessionFile(sessionId)
        if (!sessionFile.isFile) {
            throw FeedbackSessionException("SESSION_NOT_FOUND: Feedback session does not exist: $sessionId")
        }
        return runCatching {
            fixThisJson.decodeFromString(SessionDto.serializer(), sessionFile.readText())
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
            .filter { includeClosed || it.status != SessionStatusDto.CLOSED }
            .map(FeedbackSessionSummary.Companion::from)
            .sortedByDescending { it.updatedAtEpochMillis }
        return FeedbackSessionList(sessions = sessions, skippedSessions = loaded.skipped)
    }

    fun artifactPaths(): FeedbackSessionPaths = paths

    /**
     * Regenerate index.json from a full scan of the session.json files (the sole source of
     * truth). index.json is a derived, non-authoritative cache; this is the recovery path when
     * it is corrupted, stale, or hand-edited out of band.
     */
    fun rebuildIndex() {
        check(paths.rootDirectory.isDirectory || paths.rootDirectory.mkdirs()) {
            "Could not create feedback session root: ${paths.rootDirectory.absolutePath}"
        }
        val summaries = loadAll().sessions
            .map(FeedbackSessionSummary.Companion::from)
            .sortedByDescending { it.updatedAtEpochMillis }
        val payload = fixThisJson.encodeToString(
            FeedbackSessionIndex.serializer(),
            FeedbackSessionIndex(updatedAtEpochMillis = clock(), sessions = summaries),
        )
        val temp = File.createTempFile("index-rebuild-", ".json.tmp", paths.rootDirectory)
        runCatching {
            temp.writeText(payload)
            replaceFile(temp, paths.indexFile)
        }.getOrElse { error ->
            temp.delete()
            throw FeedbackSessionException("INDEX_REBUILD_FAILED: Could not rebuild feedback session index: ${error.message}")
        }
    }

    private fun indexJson(candidate: SessionDto): String {
        // Build incrementally from the existing index summaries rather than re-reading and
        // re-parsing every session.json on disk (which made each save O(total sessions)).
        // Falls back to a full directory scan when the index is missing or unreadable.
        val existing = paths.indexFile.takeIf { it.isFile }?.let { indexFile ->
            runCatching {
                fixThisJson.decodeFromString(FeedbackSessionIndex.serializer(), indexFile.readText()).sessions
            }.getOrNull()
        }
        val others = existing
            ?.filterNot { it.sessionId == candidate.sessionId }
            // Drop phantom summaries whose backing session.json no longer exists, so the cache
            // converges toward truth on each save. A cheap isFile stat only — never a re-parse.
            ?.filter { paths.sessionFile(it.sessionId).isFile }
            ?: loadAll().sessions
                .filterNot { it.sessionId == candidate.sessionId }
                .map(FeedbackSessionSummary.Companion::from)
        val listed = (others + FeedbackSessionSummary.from(candidate))
            .sortedByDescending { it.updatedAtEpochMillis }
        return fixThisJson.encodeToString(
            FeedbackSessionIndex.serializer(),
            FeedbackSessionIndex(updatedAtEpochMillis = clock(), sessions = listed),
        )
    }

    private fun backupFile(target: File, prefix: String, directory: File): FileBackup = if (target.isFile) {
        val tempFile = File.createTempFile(prefix, ".json.tmp", directory)
        Files.copy(target.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        FileBackup.Present(tempFile)
    } else {
        FileBackup.Absent
    }

    private fun restoreFile(target: File, backup: FileBackup?) {
        when (backup) {
            is FileBackup.Present -> replaceFile(backup.tempFile, target)
            FileBackup.Absent,
            null,
            -> if (target.isFile) target.delete()
        }
    }

    private fun deleteBackup(backup: FileBackup?) {
        if (backup is FileBackup.Present) backup.tempFile.delete()
    }

    private fun replaceFile(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse { error ->
            if (error !is AtomicMoveNotSupportedException) throw error
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private sealed interface FileBackup {
        data class Present(val tempFile: File) : FileBackup

        data object Absent : FileBackup
    }

    private fun loadAll(): LoadedSessions {
        if (!paths.rootDirectory.isDirectory) return LoadedSessions(emptyList(), emptyList())
        val sessions = mutableListOf<SessionDto>()
        val skipped = mutableListOf<SkippedFeedbackSession>()
        paths.rootDirectory.listFiles().orEmpty()
            .filter { it.isDirectory }
            .forEach { directory ->
                val file = File(directory, "session.json")
                if (file.isFile) {
                    runCatching {
                        fixThisJson.decodeFromString(SessionDto.serializer(), file.readText())
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
        val sessions: List<SessionDto>,
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
