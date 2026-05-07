package io.beyondwin.fixthis.mcp.console

import java.io.File

internal object FeedbackConsoleAssets {
    private const val BasePath = "/console"
    private const val StylesPlaceholder = "<!-- FIXTHIS_STYLES -->"
    private const val ScriptPlaceholder = "<!-- FIXTHIS_SCRIPT -->"

    val indexHtml: String by lazy { buildIndexHtml(consoleAssetsDir = null) }

    fun html(consoleAssetsDir: File?): String =
        if (consoleAssetsDir == null) indexHtml else buildIndexHtml(consoleAssetsDir)

    fun html(consoleAssetsDir: File?, consoleToken: String): String =
        buildIndexHtml(consoleAssetsDir, consoleToken)

    fun resource(path: String): ByteArray {
        return resource(path, consoleAssetsDir = null)
    }

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

    private fun buildIndexHtml(consoleAssetsDir: File?, consoleToken: String = ""): String =
        readText("index.html", consoleAssetsDir)
            .replace(StylesPlaceholder, "<style>\n${readText("styles.css", consoleAssetsDir)}\n</style>")
            .replace(
                ScriptPlaceholder,
                """
                <script>
                window.FixThisConsoleConfig = { consoleToken: "${consoleToken.escapeJavaScriptString()}" };
                </script>
                <script>
                ${readText("app.js", consoleAssetsDir)}
                </script>
                """.trimIndent(),
            )

    private fun readText(path: String, consoleAssetsDir: File?): String =
        resource(path, consoleAssetsDir).toString(Charsets.UTF_8)

    private fun validateResourcePath(path: String) {
        require(path.isNotBlank()) { "console asset path must not be blank" }
        require(!path.contains("..")) { "path traversal not allowed: $path" }
        require(!path.startsWith("/")) { "absolute asset paths are not allowed: $path" }
    }
}

private fun String.escapeJavaScriptString(): String =
    buildString {
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
