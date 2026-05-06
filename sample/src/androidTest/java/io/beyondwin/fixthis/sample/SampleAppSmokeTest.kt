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
    fun fixThisShowsHomeAndNavigatesTabs() {
        rule.onNodeWithText("FixThis Studio").assertExists()
        rule.onNodeWithText("Queue").performClick()
        rule.onNodeWithText("Feedback queue").assertExists()
        rule.onNodeWithText("Review").performClick()
        rule.onNodeWithText("Submit request").assertExists()
        rule.onNodeWithText("Diagnostics").performClick()
        rule.onNodeWithText("Semantic signal timeline").assertExists()
    }
}
