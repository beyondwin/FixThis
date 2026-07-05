package io.github.beyondwin.fixthis.mcp

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildInfoTest {
    @Test
    fun buildInfoExposesBuildEpoch() {
        assertTrue(BuildInfo.BUILD_EPOCH_MS > 0L, "build epoch must be set")
    }

    @Test
    fun buildInfoExposesGitSha() {
        assertTrue(BuildInfo.GIT_SHA.isNotBlank(), "git sha must be set")
        assertTrue(BuildInfo.GIT_SHA.length in 4..40, "git sha length plausible")
    }

    @Test
    fun buildInfoValuesAreNotPublicCompileTimeConstants() {
        val fields = listOf("BUILD_EPOCH_MS", "GIT_SHA").map(BuildInfo::class.java::getDeclaredField)

        fields.forEach { field ->
            assertFalse(
                Modifier.isPublic(field.modifiers),
                "${field.name} must not be public const because console routes read BuildInfo at runtime",
            )
        }
    }
}
