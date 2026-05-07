package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.cli.fixThisJson
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable

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

    private fun indexJson(candidate: SessionDto): String {
        val listed = loadAll()
            .withCandidate(candidate)
            .sessions
            .map(FeedbackSessionSummary.Companion::from)
            .sortedByDescending { it.updatedAtEpochMillis }
        return fixThisJson.encodeToString(
            FeedbackSessionIndex.serializer(),
            FeedbackSessionIndex(updatedAtEpochMillis = clock(), sessions = listed),
        )
    }

    private fun backupFile(target: File, prefix: String, directory: File): FileBackup =
        if (target.isFile) {
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
    ) {
        fun withCandidate(candidate: SessionDto): LoadedSessions =
            copy(sessions = sessions.filterNot { it.sessionId == candidate.sessionId } + candidate)
    }
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
