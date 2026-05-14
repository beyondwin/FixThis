package io.beyondwin.fixthis.mcp.console

import java.io.File

internal object FeedbackConsoleAssets {
    private const val BasePath = "/console"
    private const val StylesPlaceholder = "<!-- FIXTHIS_STYLES -->"
    private const val ScriptPlaceholder = "<!-- FIXTHIS_SCRIPT -->"

    val indexHtml: String by lazy { buildIndexHtml(consoleAssetsDir = null) }

    fun html(consoleAssetsDir: File?): String = if (consoleAssetsDir == null) indexHtml else buildIndexHtml(consoleAssetsDir)

    fun html(consoleAssetsDir: File?, consoleToken: String): String = buildIndexHtml(consoleAssetsDir, consoleToken)

    fun resource(path: String): ByteArray = resource(path, consoleAssetsDir = null)

    private fun resource(path: String, consoleAssetsDir: File?): ByteArray {
        validateResourcePath(path)
        val safePath = path.removePrefix("/")
        if (consoleAssetsDir != null) {
            val root = consoleAssetsDir.canonicalFile
            require(root.isDirectory) { "console asset directory does not exist: ${root.absolutePath}" }
            val asset = File(root, safePath).canonicalFile
            require(asset.toPath().startsWith(root.toPath())) { "path traversal not allowed: $path" }
            return checkNotNull(asset.takeIf { it.isFile }?.readBytes()) {
                "console asset not found: $safePath"
            }
        }
        return checkNotNull(javaClass.getResourceAsStream("$BasePath/$safePath")) {
            "console asset not found: $safePath"
        }.use { input -> input.readAllBytes() }
    }

    private fun consoleBuildMetaJson(): String = FeedbackConsoleAssets::class.java
        .getResource("/console/console-build-meta.json")
        ?.readText()
        ?: "{\"buildEpochMs\":0,\"gitSha\":\"unknown\"}"

    private fun effectiveBuildMetaJson(): String {
        val metaJson = consoleBuildMetaJson()
        return if (metaJson.contains("\"reproducible\"")) {
            val runtimeSha = try {
                Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short", "HEAD"))
                    .inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
            } catch (_: Exception) {
                "unknown"
            }
            """{"buildEpochMs":${System.currentTimeMillis()},"gitSha":"$runtimeSha"}"""
        } else {
            metaJson
        }
    }

    private fun buildIndexHtml(consoleAssetsDir: File?, consoleToken: String = ""): String {
        val effectiveMeta = effectiveBuildMetaJson()
        return readText("index.html", consoleAssetsDir)
            .replace(StylesPlaceholder, "<style>\n${readText("styles.css", consoleAssetsDir)}\n</style>")
            .replace(
                ScriptPlaceholder,
                """
                    <script>
                    window.FixThisConsoleConfig = { consoleToken: "${consoleToken.escapeJavaScriptString()}" };
                    window.FixThisConsoleConfig.buildMeta = $effectiveMeta;
                    </script>
                    <script>
                    ${readText("app.js", consoleAssetsDir)}
                    </script>
                """.trimIndent(),
            )
    }

    private fun readText(path: String, consoleAssetsDir: File?): String = resource(path, consoleAssetsDir).toString(Charsets.UTF_8)

    private fun validateResourcePath(path: String) {
        require(path.isNotBlank()) { "console asset path must not be blank" }
        require(!path.contains("..")) { "path traversal not allowed: $path" }
        require(!path.startsWith("/")) { "absolute asset paths are not allowed: $path" }
    }
}

private fun String.escapeJavaScriptString(): String = buildString {
    this@escapeJavaScriptString.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(character)
        }
    }
}
