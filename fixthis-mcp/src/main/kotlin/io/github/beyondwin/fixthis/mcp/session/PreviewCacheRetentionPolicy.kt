package io.github.beyondwin.fixthis.mcp.session

import java.io.File

private const val DEFAULT_MAX_PREVIEW_DIRECTORIES_PER_SESSION = 30
private const val PREVIEW_CACHE_RETENTION_MINUTES = 10L
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val DEFAULT_PREVIEW_CACHE_MIN_AGE_MILLIS =
    PREVIEW_CACHE_RETENTION_MINUTES * SECONDS_PER_MINUTE * MILLIS_PER_SECOND

class PreviewCacheRetentionPolicy(
    private val maxDirectoriesPerSession: Int = DEFAULT_MAX_PREVIEW_DIRECTORIES_PER_SESSION,
    private val minAgeMillis: Long = DEFAULT_PREVIEW_CACHE_MIN_AGE_MILLIS,
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
