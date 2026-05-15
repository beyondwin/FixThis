package io.github.beyondwin.fixthis.compose.sidekick.bridge

import java.io.File

/**
 * Path containment helpers used by the bridge to keep file access scoped to
 * known directories (e.g. the screenshot cache).
 */
internal object PathSafety {
    /**
     * Canonical-path containment check. Note: there is a TOCTOU window between
     * this check and the subsequent file open — callers should resolve and
     * check inside the same I/O critical section.
     */
    fun isUnder(child: File, parent: File): Boolean {
        val canonicalChild = child.canonicalFile
        val canonicalParent = parent.canonicalFile
        val childPath = canonicalChild.path
        val parentPath = canonicalParent.path
        return childPath == parentPath || childPath.startsWith(parentPath + File.separator)
    }
}
