package io.beyondwin.fixthis.mcp.console

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeProtocolVersionSyncTest {
    @Test
    fun allMirrorSitesAgreeOnBridgeProtocolVersion() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }

        val sites = listOf(
            MirrorSite(
                "BridgeProtocol.kt",
                File(
                    root,
                    "fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocol.kt",
                ),
                Regex("""const val VERSION: String = "([^"]+)""""),
            ),
            MirrorSite(
                "BridgeClient.kt",
                File(root, "fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt"),
                Regex("""const val BridgeProtocolVersion = "([^"]+)""""),
            ),
            MirrorSite(
                "ServerVersionRoutes.kt",
                File(
                    root,
                    "fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ServerVersionRoutes.kt",
                ),
                Regex("""const val BridgeProtocolVersion = "([^"]+)""""),
            ),
            MirrorSite(
                "staleness.js",
                File(root, "fixthis-mcp/src/main/console/staleness.js"),
                Regex("""const MinimumSupportedProtocolVersion = '([^']+)'"""),
            ),
        )

        val extracted = sites.associate { site ->
            require(site.file.isFile) { "Mirror site file not found: ${site.file}" }
            val match = site.regex.find(site.file.readText())
                ?: error(
                    "Mirror site ${site.label} (${site.file}) does not match regex " +
                        "${site.regex.pattern} — has the constant been renamed? " +
                        "Update BridgeProtocolVersionSyncTest.kt accordingly.",
                )
            site.label to match.groupValues[1]
        }

        val unique = extracted.values.toSet()
        assertEquals(
            1,
            unique.size,
            "Bridge protocol version mismatch across mirror sites: $extracted. " +
                "All four sites must hold the same string. See CLAUDE.md " +
                "\"Bridge Protocol Compatibility\" for the bump rule.",
        )
    }

    private data class MirrorSite(val label: String, val file: File, val regex: Regex)
}
