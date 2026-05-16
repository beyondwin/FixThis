package io.github.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentSurfaceMessagesTest {
    @Test
    fun noAppModuleMessageHasCauseVerifyAndFixOptions() {
        val msg = AgentSurfaceMessages.noAppModule(packageName = "com.example.app")
        val expected = """
            Multi-module project has no app module matching 'com.example.app'.
              verify: ./gradlew projects
              fix:    pass --package <correct-applicationId>
              fix:    apply plugin manually: id("io.github.beyondwin.fixthis.compose")
        """.trimIndent()
        assertEquals(expected, msg.trim())
    }

    @Test
    fun releaseOnlyVariantMessageNamesDebugAssemble() {
        val msg = AgentSurfaceMessages.releaseOnlyVariant()
        assertEquals(true, msg.contains("verify: ./gradlew tasks --group=build | grep Debug"))
        assertEquals(true, msg.contains("fix:    add a debug build variant"))
    }

    @Test
    fun viewSystemMixedMessageNamesGrepCommand() {
        val msg = AgentSurfaceMessages.viewSystemMixed(modulePath = ":app")
        assertEquals(true, msg.contains("verify: grep -r 'setContentView' app/src/main"))
    }

    @Test
    fun missingApplicationIdMessageNamesGradleProperty() {
        val msg = AgentSurfaceMessages.missingApplicationId()
        assertEquals(true, msg.contains("verify: ./gradlew :app:properties | grep applicationId"))
    }
}
