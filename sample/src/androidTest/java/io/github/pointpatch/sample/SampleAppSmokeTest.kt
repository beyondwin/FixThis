package io.github.pointpatch.sample

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class SampleAppSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun sampleShowsCheckoutAndNavigatesTabs() {
        rule.onNodeWithText("Pay now").assertExists()
        rule.onNodeWithText("Feed").performClick()
        rule.onNodeWithText("Monthly plan").assertExists()
        rule.onNodeWithText("Form").performClick()
        rule.onNodeWithText("Email address").assertExists()
    }
}
