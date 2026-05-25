package io.github.beyondwin.fixthis.mcp.console

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class FeedbackConsoleAssets(
    private val shaResolver: () -> String? = ::defaultShaResolver,
    private val clock: () -> Long = System::currentTimeMillis,
    private val errSink: (String) -> Unit = { System.err.println(it) },
    private val classpathResourceLoader: (String) -> ByteArray? = { path ->
        FeedbackConsoleAssets::class.java.getResourceAsStream("/console/$path")
            ?.use { input -> input.readAllBytes() }
    },
) {
    @Volatile
    private var cachedRuntimeSha: String? = null

    @Volatile
    private var shaResolved: Boolean = false

    private val bundledResourceCache = ConcurrentHashMap<String, ByteArray>()

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
        val cached = bundledResourceCache.computeIfAbsent(safePath) {
            checkNotNull(classpathResourceLoader(it)) {
                "console asset not found: $safePath"
            }
        }
        return cached.copyOf()
    }

    private fun resolveRuntimeShaCached(): String {
        if (!shaResolved) {
            synchronized(this) {
                if (!shaResolved) {
                    val raw = try {
                        shaResolver()
                    } catch (error: IOException) {
                        errSink("FeedbackConsoleAssets: gitSha resolution failed: ${error.message}")
                        null
                    }
                    cachedRuntimeSha = raw?.trim()?.takeIf { it.matches(ShaRegex) } ?: UnknownSha
                    shaResolved = true
                }
            }
        }
        return cachedRuntimeSha ?: UnknownSha
    }

    private fun effectiveBuildMetaJson(): String {
        val sha = resolveRuntimeShaCached()
        return """{"buildEpochMs":${clock()},"gitSha":"$sha"}"""
    }

    fun buildIndexHtml(consoleAssetsDir: File?, consoleToken: String = ""): String {
        val effectiveMeta = effectiveBuildMetaJson()
        val devFields = if (consoleAssetsDir != null) {
            val buildHash = readBuildHashFromDir(consoleAssetsDir).orEmpty()
            """, devReloadEnabled: true, buildHash: "${buildHash.escapeJavaScriptString()}""""
        } else {
            ""
        }
        return readText("index.html", consoleAssetsDir)
            .replace(StylesPlaceholder, "<style>\n${readText("styles.css", consoleAssetsDir)}\n</style>")
            .replace(
                ScriptPlaceholder,
                """
                    <script>
                    window.FixThisConsoleConfig = { consoleToken: "${consoleToken.escapeJavaScriptString()}"$devFields };
                    window.FixThisConsoleConfig.buildMeta = $effectiveMeta;
                    </script>
                    <script>
                    ${readText("app.js", consoleAssetsDir)}
                    </script>
                """.trimIndent(),
            )
    }

    private fun readBuildHashFromDir(consoleAssetsDir: File): String? {
        val metaFile = File(consoleAssetsDir, "console-build-meta.json")
        if (!metaFile.isFile) return null
        return try {
            val element = Json.parseToJsonElement(metaFile.readText())
            (element as? JsonObject)?.get("gitSha")?.jsonPrimitive?.contentOrNull
        } catch (error: Exception) {
            errSink("FeedbackConsoleAssets: failed to read console-build-meta.json: ${error.message}")
            null
        }
    }

    private fun readText(path: String, consoleAssetsDir: File?): String = resource(path, consoleAssetsDir).toString(Charsets.UTF_8)

    private fun validateResourcePath(path: String) {
        require(path.isNotBlank()) { "console asset path must not be blank" }
        require(!path.contains("..")) { "path traversal not allowed: $path" }
        require(!path.startsWith("/")) { "absolute asset paths are not allowed: $path" }
    }

    companion object {
        private const val StylesPlaceholder = "<!-- FIXTHIS_STYLES -->"
        private const val ScriptPlaceholder = "<!-- FIXTHIS_SCRIPT -->"
        private const val UnknownSha = "unknown"
        private val ShaRegex = Regex("^[0-9a-f]{7,40}$")

        private val default: FeedbackConsoleAssets = FeedbackConsoleAssets()

        val indexHtml: String
            get() = default.buildIndexHtml(consoleAssetsDir = null, consoleToken = "")

        fun html(consoleAssetsDir: File?): String = default.buildIndexHtml(consoleAssetsDir, consoleToken = "")

        fun html(dir: File?, token: String): String = default.buildIndexHtml(dir, token)

        fun resource(path: String): ByteArray = default.resource(path)

        private fun defaultShaResolver(): String? {
            var process: Process? = null
            return try {
                process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start()
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroy()
                    return null
                }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val token = output.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
                token.takeIf { it.matches(ShaRegex) }
            } catch (error: IOException) {
                System.err.println("FeedbackConsoleAssets: gitSha resolution failed: ${error.message}")
                null
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                System.err.println("FeedbackConsoleAssets: gitSha resolution failed: ${error.message}")
                null
            } finally {
                process?.destroy()
            }
        }
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
