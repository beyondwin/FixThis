package io.beyondwin.fixthis.mcp.fixtures

import java.nio.file.Files
import java.nio.file.Paths

object ConsoleSourceFixtures {
    private val jsSourceDir = Paths.get("src/main/console")
    private val resourcesDir = Paths.get("src/main/resources/console")

    fun read(moduleFileName: String): String = Files.readString(jsSourceDir.resolve(moduleFileName))

    fun readAll(): String = buildString {
        appendLine(Files.readString(resourcesDir.resolve("index.html")))
        appendLine(Files.readString(resourcesDir.resolve("styles.css")))
        Files.list(jsSourceDir).use { paths ->
            paths.filter { it.toString().endsWith(".js") }
                .sorted()
                .forEach { appendLine(Files.readString(it)) }
        }
    }
}
