package io.github.pointpatch.compose.console.studio

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class StudioShellComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun toolbarSwitchesToAnnotate() {
        composeFeedbackConsole()

        composeRule.onNodeWithTag(StudioTestTags.ToolAnnotate)
            .performClick()

        composeRule.onNodeWithTag(StudioTestTags.ToolAnnotate)
            .assertIsSelected()
    }

    @Test
    fun emptyStateStartAnnotatingSwitchesToAnnotate() {
        composeFeedbackConsole()

        composeRule.onNodeWithTag(StudioTestTags.EmptyStartAnnotating)
            .performClick()

        composeRule.onNodeWithTag(StudioTestTags.ToolAnnotate)
            .assertIsSelected()
    }

    @Test
    fun annotationRowClickOpensDetail() {
        composeFeedbackConsole()

        composeRule.onNodeWithTag(StudioTestTags.EmptyStartAnnotating)
            .performClick()
        composeRule.onNodeWithTag(StudioTestTags.widget("action-send"))
            .performTouchInput { click(center) }

        composeRule.onNodeWithTag(StudioTestTags.annotationRow(0))
            .performClick()

        composeRule.onNodeWithTag(StudioTestTags.AnnotationDetail)
            .assertExists()
        composeRule.onNodeWithText("Action send")
            .assertExists()
    }

    private fun composeFeedbackConsole() {
        composeRule.setContent {
            FeedbackConsoleScreen(
                previewScreenshot = null,
                modifier = Modifier.requiredSize(width = 1200.dp, height = 800.dp),
            )
        }
        composeRule.waitForIdle()
    }
}
