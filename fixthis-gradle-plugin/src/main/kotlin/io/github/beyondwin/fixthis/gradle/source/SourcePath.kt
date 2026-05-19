package io.github.beyondwin.fixthis.gradle.source

import java.io.File

internal fun File.relativeSourcePath(projectDirectory: File, rootProjectDirectory: File): String = if (isUnder(projectDirectory)) {
    relativeTo(projectDirectory).invariantSeparatorsPath
} else {
    relativeToOrSelf(rootProjectDirectory).invariantSeparatorsPath
}

private fun File.isUnder(parent: File): Boolean {
    val parentPath = parent.canonicalFile.toPath()
    return canonicalFile.toPath().startsWith(parentPath)
}
