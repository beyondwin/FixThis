package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
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
        val files = resolvedFiles(sourceIndex)
        if (installEpochMillis == null) {
            return HostSourceFreshnessResult(
                installStale = false,
                newerFileCount = 0,
                totalIndexedFiles = files.size,
                installedAtEpochMillis = null,
                sampleNewerFiles = emptyList(),
                reason = "install epoch unavailable; older sidekick",
            )
        }
        if (files.isNotEmpty()) {
            val existsCount = files.count { it.resolution.found }
            if (existsCount == 0) {
                return HostSourceFreshnessResult(
                    installStale = false,
                    newerFileCount = 0,
                    totalIndexedFiles = files.size,
                    installedAtEpochMillis = installEpochMillis,
                    sampleNewerFiles = emptyList(),
                    reason = "projectRoot may be misconfigured: 0 of ${files.size} indexed files exist on host",
                )
            }
        }
        val newer = files.mapNotNull { resolvedFile ->
            val file = resolvedFile.resolution.file ?: return@mapNotNull null
            if (file.lastModified() > installEpochMillis) resolvedFile.displayPath else null
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

    private fun resolvedFiles(sourceIndex: SourceIndex): List<ResolvedIndexedFile> {
        val resolver = HostSourcePathResolver(projectRoot)
        return sourceIndex.entries
            .map { entry ->
                val resolution = resolver.resolve(entry, sourceIndex)
                ResolvedIndexedFile(
                    displayPath = resolution.displayPath ?: entry.file,
                    resolution = resolution,
                )
            }
            .distinctBy { it.displayPath }
    }

    private data class ResolvedIndexedFile(
        val displayPath: String,
        val resolution: HostSourcePathResolution,
    )

    private companion object {
        const val SampleSize = 3
    }
}
