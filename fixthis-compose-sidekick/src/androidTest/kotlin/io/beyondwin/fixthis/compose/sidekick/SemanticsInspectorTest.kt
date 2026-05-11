package io.beyondwin.fixthis.compose.sidekick

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.beyondwin.fixthis.compose.sidekick.inspect.SemanticsInspector
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticsInspectorTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun inspectCurrentScreenFindsPayNowAndRedactsEditablePassword() {
        composeRule.setContent {
            Column {
                BasicText("Pay now")
                Spacer(
                    modifier = Modifier
                        .size(24.dp)
                        .semantics {
                            editableText = AnnotatedString("card 4242")
                            password()
                        },
                )
            }
        }
        composeRule.waitForIdle()

        val decorView = composeRule.activity.window.decorView
        val result = SemanticsInspector().inspect(decorView)
        val allNodes = result.mergedNodes + result.unmergedNodes

        assertTrue(result.errors.joinToString { it.message }, result.errors.isEmpty())
        assertTrue(result.roots.isNotEmpty())
        assertTrue(allNodes.any { node -> node.text.any { it.contains("Pay now") } })
        assertTrue(
            allNodes.any { node ->
                node.isPassword &&
                    node.isSensitive &&
                    node.editableText == "<redacted-password>" &&
                    node.text == listOf("<redacted-password>")
            },
        )
    }
}
