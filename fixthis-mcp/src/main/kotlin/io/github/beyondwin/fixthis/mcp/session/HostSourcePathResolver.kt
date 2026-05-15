package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import io.github.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import java.io.File

data class HostSourcePathResolution(
    val file: File?,
    val displayPath: String?,
    val reason: HostSourcePathResolutionReason?,
    val failureReason: String?,
) {
    val found: Boolean get() = file != null
}

enum class HostSourcePathResolutionReason {
    REPO_FILE,
    SOURCE_ROOT,
    LEGACY_ROOT,
    UNIQUE_SUFFIX,
}

class HostSourcePathResolver(projectRoot: File) {
    private val canonicalRoot: File = projectRoot.canonicalFile

    fun resolve(entry: SourceIndexEntry, sourceIndex: SourceIndex): HostSourcePathResolution {
        val hadSourceRoot = sourceIndex.sourceRoot?.projectDir != null
        val candidates = buildList {
            entry.repoFile?.takeIf { it.isNotBlank() }?.let { add(it to HostSourcePathResolutionReason.REPO_FILE) }
            sourceIndex.sourceRoot?.projectDir?.let { projectDir ->
                val prefix = projectDir.trim('/').takeIf { it.isNotBlank() }
                val path = if (prefix == null) entry.file else "$prefix/${entry.file}"
                add(path to HostSourcePathResolutionReason.SOURCE_ROOT)
            }
            add(entry.file to HostSourcePathResolutionReason.LEGACY_ROOT)
        }

        for ((relativePath, reason) in candidates.distinctBy { it.first }) {
            val resolution = resolveRelative(relativePath, reason)
            if (resolution.found || resolution.failureReason?.startsWith(PATH_ESCAPE_PREFIX) == true) {
                return resolution
            }
        }

        return uniqueSuffix(entry.file, hadSourceRoot)
    }

    private fun resolveRelative(
        relativePath: String,
        reason: HostSourcePathResolutionReason,
    ): HostSourcePathResolution {
        val resolved = runCatching { File(canonicalRoot, relativePath).canonicalFile }
            .getOrNull()
        val failureReason = when {
            resolved == null -> "file not found on host"
            !resolved.isInsideProjectRoot() -> "$PATH_ESCAPE_PREFIX: $relativePath"
            !resolved.isFile -> "file not found on host"
            else -> null
        }
        return if (failureReason != null) {
            unresolved(failureReason)
        } else {
            val file = requireNotNull(resolved)
            HostSourcePathResolution(
                file = file,
                displayPath = file.relativeTo(canonicalRoot).invariantSeparatorsPath,
                reason = reason,
                failureReason = null,
            )
        }
    }

    private fun uniqueSuffix(rawPath: String, hadSourceRoot: Boolean): HostSourcePathResolution {
        val suffix = rawPath.trim('/').replace(File.separatorChar, '/')
        if (suffix.isBlank() || suffix.contains("..")) {
            return unresolved("file not found on host")
        }
        val matches = canonicalRoot.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                file.relativeTo(canonicalRoot).invariantSeparatorsPath.endsWith(suffix)
            }
            .take(2)
            .toList()

        return when (matches.size) {
            1 -> {
                val resolved = matches.single().canonicalFile
                HostSourcePathResolution(
                    file = resolved,
                    displayPath = resolved.relativeTo(canonicalRoot).invariantSeparatorsPath,
                    reason = HostSourcePathResolutionReason.UNIQUE_SUFFIX,
                    failureReason = null,
                )
            }
            0 -> unresolved(
                if (hadSourceRoot) {
                    "file not found on host; sourceRoot unresolved"
                } else {
                    "file not found on host"
                },
            )
            else -> unresolved("file not found on host; multiple suffix matches")
        }
    }

    private fun File.isInsideProjectRoot(): Boolean = toPath().startsWith(canonicalRoot.toPath())

    private fun unresolved(reason: String): HostSourcePathResolution = HostSourcePathResolution(
        file = null,
        displayPath = null,
        reason = null,
        failureReason = reason,
    )

    private companion object {
        const val PATH_ESCAPE_PREFIX = "path escapes project root"
    }
}
