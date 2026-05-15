package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import java.io.File

/**
 * Re-reads the host's source files at each candidate's `(file, line)` and compares
 * the live trimmed line against the source-index excerpt. Marks mismatches as stale
 * so AI agents do not edit coordinates that no longer match the live code.
 */
class SourceCandidateStalenessChecker(private val projectRoot: File) {

    fun annotate(candidates: List<SourceCandidate>, sourceIndex: SourceIndex): List<SourceCandidate> {
        if (candidates.isEmpty()) return candidates
        val byFileKey = sourceIndex.entries.associateBy { entryKey(it.file, it.line) }
        val byRepoFileKey = sourceIndex.entries
            .mapNotNull { entry -> entry.repoFile?.let { entryKey(it, entry.line) to entry } }
            .toMap()
        val resolver = HostSourcePathResolver(projectRoot)
        return candidates.map { candidate ->
            val entry = candidate.repoFile
                ?.let { byRepoFileKey[entryKey(it, candidate.line)] }
                ?: byFileKey[entryKey(candidate.file, candidate.line)]
                ?: byRepoFileKey[entryKey(candidate.file, candidate.line)]
            annotate(candidate, entry, sourceIndex, resolver)
        }
    }

    private fun annotate(
        candidate: SourceCandidate,
        entry: SourceIndexEntry?,
        sourceIndex: SourceIndex,
        resolver: HostSourcePathResolver,
    ): SourceCandidate {
        if (entry?.excerpt.isNullOrBlank() || candidate.line == null) return candidate
        val expected = requireNotNull(entry?.excerpt)
        val line = requireNotNull(candidate.line)

        val resolution = resolver.resolve(entry, sourceIndex)
        return if (resolution.file == null) {
            candidate.flagStale(resolution.failureReason ?: "file not found on host")
        } else {
            candidate.annotateResolvedSource(resolution, expected, line)
        }
    }

    private fun SourceCandidate.annotateResolvedSource(
        resolution: HostSourcePathResolution,
        expected: String,
        line: Int,
    ): SourceCandidate {
        val resolved = requireNotNull(resolution.file)
        val resolvedCandidate = withResolvedRepoFile(resolution)
        val live = if (resolved.length() > MaxBytesToRead) null else readLine(resolved, line)
        return when {
            resolved.length() > MaxBytesToRead -> resolvedCandidate.flagStale("file too large to verify")
            live == null -> resolvedCandidate.flagStale("line out of range")
            live.trim() == expected.trim() -> resolvedCandidate.copy(stale = false, staleReason = null)
            else -> resolvedCandidate.flagStale("excerpt mismatch")
        }
    }

    private fun readLine(file: File, lineNumber: Int): String? {
        if (lineNumber < 1) return null
        file.bufferedReader().use { reader ->
            var current = 0
            while (true) {
                val l = reader.readLine() ?: return null
                current++
                if (current == lineNumber) return l
            }
        }
    }

    private fun SourceCandidate.flagStale(reason: String): SourceCandidate = copy(stale = true, staleReason = reason)

    private fun SourceCandidate.withResolvedRepoFile(resolution: HostSourcePathResolution): SourceCandidate {
        val displayPath = resolution.displayPath?.takeIf { it.isNotBlank() }
        return if (displayPath == null || !repoFile.isNullOrBlank() || displayPath == file) {
            this
        } else {
            copy(repoFile = displayPath)
        }
    }

    private fun entryKey(file: String, line: Int?): String = "$file::${line ?: -1}"

    private companion object {
        const val MaxBytesToRead: Long = 1_048_576L
    }
}
