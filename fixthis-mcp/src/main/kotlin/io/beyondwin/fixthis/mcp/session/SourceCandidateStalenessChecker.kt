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
        val byKey = sourceIndex.entries.associateBy { entryKey(it.file, it.line) }
        val canonicalRoot = projectRoot.canonicalFile
        return candidates.map { candidate ->
            val entry = byKey[entryKey(candidate.file, candidate.line)]
            annotate(candidate, entry, canonicalRoot)
        }
    }

    private fun annotate(candidate: SourceCandidate, entry: SourceIndexEntry?, canonicalRoot: File): SourceCandidate {
        val expected = entry?.excerpt?.takeIf { it.isNotBlank() } ?: return candidate
        val line = candidate.line ?: return candidate

        val resolved = runCatching { File(canonicalRoot, candidate.file).canonicalFile }.getOrNull()
            ?: return candidate.flagStale("file not found on host")
        if (!resolved.path.startsWith(canonicalRoot.path + File.separator) && resolved != canonicalRoot) {
            return candidate.flagStale("path escapes project root: ${candidate.file}")
        }
        if (!resolved.isFile) return candidate.flagStale("file not found on host")
        if (resolved.length() > MaxBytesToRead) return candidate.flagStale("file too large to verify")

        val live = readLine(resolved, line) ?: return candidate.flagStale("line out of range")
        return if (live.trim() == expected.trim()) {
            candidate.copy(stale = false, staleReason = null)
        } else {
            candidate.flagStale("excerpt mismatch")
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

    private fun entryKey(file: String, line: Int?): String = "$file::${line ?: -1}"

    private companion object {
        const val MaxBytesToRead: Long = 1_048_576L
    }
}
