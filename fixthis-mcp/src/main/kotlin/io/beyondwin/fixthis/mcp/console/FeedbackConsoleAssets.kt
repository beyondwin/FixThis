package io.beyondwin.fixthis.mcp.console

internal object FeedbackConsoleAssets {
    private const val BasePath = "/console"
    private const val StylesPlaceholder = "<!-- FIXTHIS_STYLES -->"
    private const val ScriptPlaceholder = "<!-- FIXTHIS_SCRIPT -->"

    val indexHtml: String by lazy {
        readText("index.html")
            .replace(StylesPlaceholder, "<style>\n${readText("styles.css")}\n</style>")
            .replace(ScriptPlaceholder, "<script>\n${readText("app.js")}\n</script>")
    }

    fun resource(path: String): ByteArray {
        validateResourcePath(path)
        val safePath = path.removePrefix("/")
        return checkNotNull(javaClass.getResourceAsStream("$BasePath/$safePath")) {
            "console asset not found: $safePath"
        }.use { input -> input.readAllBytes() }
    }

    private fun readText(path: String): String =
        resource(path).toString(Charsets.UTF_8)

    private fun validateResourcePath(path: String) {
        require(path.isNotBlank()) { "console asset path must not be blank" }
        require(!path.contains("..")) { "path traversal not allowed: $path" }
        require(!path.startsWith("/")) { "absolute asset paths are not allowed: $path" }
    }
}
