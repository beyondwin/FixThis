package io.beyondwin.fixthis.gradle.source

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XmlStringResourceScannerTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `resolveDefaults returns default-locale strings only`() {
        val projectDir = tempDir.newFolder("project")
        val resDir = projectDir.resolve("src/main/res").apply { mkdirs() }
        resDir.resolve("values").mkdirs()
        resDir.resolve("values").resolve("strings.xml").writeText(
            """
            <resources>
              <string name="login_button">로그인</string>
              <string name="cancel">취소</string>
            </resources>
            """.trimIndent(),
        )
        resDir.resolve("values-en").mkdirs()
        resDir.resolve("values-en").resolve("strings.xml").writeText(
            """
            <resources>
              <string name="login_button">Sign in</string>
            </resources>
            """.trimIndent(),
        )
        val scanner = XmlStringResourceScanner(projectDir, projectDir)
        val allXml = projectDir.walkTopDown().filter { it.name == "strings.xml" }.toList()

        val resolved = scanner.resolveDefaults(allXml)

        assertEquals(mapOf("login_button" to "로그인", "cancel" to "취소"), resolved)
    }
}
