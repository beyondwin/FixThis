package io.beyondwin.fixthis.mcp.session

import java.io.File

class PreviewCacheRetentionPolicy(
    private val maxDirectoriesPerSession: Int = 30,
    private val minAgeMillis: Long = 10 * 60 * 1000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(maxDirectoriesPerSession > 0) { "Preview cache directory limit must be positive" }
        require(minAgeMillis >= 0L) { "Preview cache minimum age must not be negative" }
    }

    fun cleanupSessionPreviewRoot(previewRoot: File) {
        val directories = previewRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith("fingerprint-") }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .orEmpty()
        if (directories.size <= maxDirectoriesPerSession) return
        val now = clock()
        val removable = directories
            .dropLast(maxDirectoriesPerSession)
            .filter { dir -> (now - dir.lastModified()).coerceAtLeast(0L) >= minAgeMillis }
        removable.forEach { it.deleteRecursively() }
    }
}
