package io.github.beyondwin.fixthis.sample

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Rule
import org.junit.Test

class SampleAppSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun fixThisShowsProductScenesAndNavigatesTabs() {
        rule.onNodeWithText("FixThis Studio").assertExists()
        rule.onNodeWithText("Review work, at a glance").assertExists()
        rule.onNodeWithContentDescription("Home tab").assertExists()
        rule.onNodeWithContentDescription("Queue tab").assertExists()
        rule.onNodeWithContentDescription("Project tab").assertExists()
        rule.onNodeWithContentDescription("Review tab").assertExists()
        rule.onNodeWithContentDescription("Diagnostics tab").assertExists()

        rule.onNodeWithText("Queue").performClick()
        rule.onNodeWithText("Feedback queue").assertExists()
        rule.onNodeWithText("Search feedback").assertExists()
        rule.onNodeWithText("Needs screenshot").assertExists()
        rule.onNodeWithContentDescription("Save FX-1042").assertExists()

        rule.onNodeWithText("Project").performClick()
        rule.onNodeWithText("Affected preview").assertExists()
        rule.onNodeWithTag("screen:Project:list").performScrollToNode(hasText("Close issue"))
        rule.onNodeWithText("Close issue").performClick()
        rule.onNodeWithText("Close FX-1042 and keep this feedback in the project history?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithTag("screen:Project:list").performScrollToNode(hasText("More actions"))
        rule.onNodeWithText("More actions").performClick()
        rule.onNodeWithText("Assign reviewer").assertExists()
        rule.onNodeWithText("Assign reviewer").performClick()

        rule.onNodeWithText("Review").performClick()
        rule.onNodeWithText("Compose fix request").assertExists()
        rule.onNodeWithText("Submit request").assertExists()

        rule.onNodeWithText("Diagnostics").performClick()
        rule.onNodeWithText("Visual-only sparkline").assertExists()
        rule.onNodeWithText("Semantic signal timeline").assertExists()
        rule.onNodeWithContentDescription("Semantic signal timeline").assertExists()
        rule.onNodeWithText(
            "Nested row target remains selectable while the disabled action communicates blocked state.",
        ).assertExists()
        rule.onNodeWithText("Disabled action").assertExists()
        rule.onNodeWithText("Weak semantic preview").assertExists()
        rule.onNodeWithText(
            "Very long diagnostic row label that should wrap without breaking Smart Select behavior",
        ).assertExists()
    }

    @Test
    fun diagnosticsLongRowKeepsDisabledActionVisible() {
        rule.onNodeWithText("Diagnostics").performClick()
        rule.waitForIdle()

        rule.onNodeWithText(
            "Very long diagnostic row label that should wrap without breaking Smart Select behavior",
        ).assertExists()
        rule.onNodeWithText("Disabled action").assertExists()
    }

    @Test
    fun homeScreenExposesStableTargetEvidenceTags() {
        rule.onNodeWithTag("comp:StudioHeader:root").assertExists()
        rule.onNodeWithTag("comp:HomePrimaryAction:primary").assertExists()
        rule.onAllNodesWithTag("comp:MetricCard:summary").assertCountEquals(3)
    }
}
