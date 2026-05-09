package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.source.SourceIndex
import java.io.File

data class HostSourceFreshnessResult(
    val installStale: Boolean,
    val newerFileCount: Int,
    val totalIndexedFiles: Int,
    val installedAtEpochMillis: Long?,
    val sampleNewerFiles: List<String>,
    val reason: String?,
)

/**
 * Compares mtime of every file referenced by the on-device source-index against the
 * APK's install epoch. Used by `fixthis_status` to surface a one-line warning when
 * the agent should reinstall before trusting source coordinates.
 */
class HostSourceFreshnessProbe(private val projectRoot: File) {

    fun evaluate(sourceIndex: SourceIndex, installEpochMillis: Long?): HostSourceFreshnessResult {
        if (installEpochMillis == null) {
            return HostSourceFreshnessResult(
                installStale = false,
                newerFileCount = 0,
                totalIndexedFiles = sourceIndex.entries.map { it.file }.distinct().size,
                installedAtEpochMillis = null,
                sampleNewerFiles = emptyList(),
                reason = "install epoch unavailable; older sidekick",
            )
        }
        val canonicalRoot = projectRoot.canonicalFile
        val files = sourceIndex.entries.map { it.file }.distinct()
        val newer = files.mapNotNull { relative ->
            val resolved = runCatching { File(canonicalRoot, relative).canonicalFile }.getOrNull()
                ?: return@mapNotNull null
            if (!resolved.path.startsWith(canonicalRoot.path + File.separator)) return@mapNotNull null
            if (!resolved.isFile) return@mapNotNull null
            if (resolved.lastModified() > installEpochMillis) relative else null
        }
        val stale = newer.isNotEmpty()
        return HostSourceFreshnessResult(
            installStale = stale,
            newerFileCount = newer.size,
            totalIndexedFiles = files.size,
            installedAtEpochMillis = installEpochMillis,
            sampleNewerFiles = newer.take(SampleSize),
            reason = if (stale) {
                "${newer.size} of ${files.size} indexed source files changed after the installed APK was built"
            } else {
                null
            },
        )
    }

    private companion object {
        const val SampleSize = 3
    }
}
