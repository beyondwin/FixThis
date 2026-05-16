package io.github.beyondwin.fixthis.cli.commands

import java.io.File

internal object GlobalScopeGuard {
    enum class Decision { PROCEED, SKIP_GLOBAL_WRITES }

    fun isAndroidProject(root: File): Boolean {
        val hasSettings = root.resolve("settings.gradle.kts").isFile ||
            root.resolve("settings.gradle").isFile
        if (!hasSettings) return false
        return findApplicationIdInTree(root)
    }

    fun decide(root: File, allowGlobal: Boolean): Decision = when {
        allowGlobal -> Decision.PROCEED
        isAndroidProject(root) -> Decision.PROCEED
        else -> Decision.SKIP_GLOBAL_WRITES
    }

    private fun findApplicationIdInTree(root: File): Boolean {
        if (!root.isDirectory) return false
        val rootCanonical = root.canonicalFile
        val candidates = root.walkTopDown()
            .onEnter { dir ->
                // Skip .git, node_modules, build dirs, and anything that escapes root via symlink
                when (dir.name) {
                    ".git", "node_modules", "build" -> false
                    else -> try { dir.canonicalFile.startsWith(rootCanonical) } catch (_: Exception) { false }
                }
            }
            .maxDepth(3)
            .filter { it.isFile && (it.name == "build.gradle.kts" || it.name == "build.gradle") }
        return candidates.any { build ->
            build.useLines { lines -> lines.any { it.contains("applicationId") } }
        }
    }
}
