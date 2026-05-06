package io.beyondwin.fixthis.sample

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.beyondwin.fixthis.compose.sidekick.inspect.SemanticsInspector
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SemanticsInspectorSampleAppTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun inspectorFindsSubmitRequestOnReviewScreen() {
        rule.onNodeWithText("Review").performClick()
        rule.waitForIdle()

        val result = SemanticsInspector().inspect(rule.activity.window.decorView)
        val nodes = result.mergedNodes + result.unmergedNodes

        assertTrue(result.errors.joinToString { it.message }, result.errors.isEmpty())
        assertTrue(result.roots.isNotEmpty())
        assertTrue(nodes.any { node -> node.text.any { it.contains("Submit request") } })
    }
}
