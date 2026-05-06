package io.beyondwin.fixthis.sample

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class SampleAppSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun fixThisShowsProductScenesAndNavigatesTabs() {
        rule.onNodeWithText("FixThis Studio").assertExists()
        rule.onNodeWithText("Review work, at a glance").assertExists()

        rule.onNodeWithText("Queue").performClick()
        rule.onNodeWithText("Feedback queue").assertExists()
        rule.onNodeWithText("Needs screenshot").assertExists()

        rule.onNodeWithText("Project").performClick()
        rule.onNodeWithText("Affected preview").assertExists()
        rule.onNodeWithText("Close issue").assertExists()

        rule.onNodeWithText("Review").performClick()
        rule.onNodeWithText("Compose fix request").assertExists()
        rule.onNodeWithText("Submit request").assertExists()

        rule.onNodeWithText("Diagnostics").performClick()
        rule.onNodeWithText("Visual-only sparkline").assertExists()
        rule.onNodeWithText("Semantic signal timeline").assertExists()
    }
}
