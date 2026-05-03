package io.github.pointpatch.compose.core.source

import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.SelectionConfidence
import io.github.pointpatch.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMatcherTest {
    @Test
    fun ranksSelectedNodeTextTagRoleAndActivityMatches() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "sample/src/main/java/io/github/pointpatch/sample/screens/CheckoutScreen.kt",
                    line = 42,
                    symbols = listOf("CheckoutPrimaryButton"),
                    text = listOf("Pay now"),
                    testTags = listOf("checkoutPrimaryButton"),
                    roles = listOf("Button"),
                    activityNames = listOf("MainActivity"),
                    excerpt = "Button(onClick = ...) { Text(\"Pay now\") }"
                ),
                SourceIndexEntry(
                    file = "sample/src/main/java/io/github/pointpatch/sample/screens/FeedScreen.kt",
                    line = 12,
                    symbols = listOf("FeedCard"),
                    text = listOf("Pay now"),
                    activityNames = listOf("MainActivity"),
                    excerpt = "Text(\"Pay now\")"
                )
            )
        )

        val matches = SourceMatcher(index).match(
            selectedNode = node(
                uid = "pay-button",
                text = listOf("Pay now"),
                role = "Button",
                testTag = "checkoutPrimaryButton",
                actions = listOf("OnClick")
            ),
            nearbyNodes = emptyList(),
            activityName = "io.github.pointpatch.sample.MainActivity"
        )

        assertEquals("sample/src/main/java/io/github/pointpatch/sample/screens/CheckoutScreen.kt", matches.first().file)
        assertEquals(42, matches.first().line)
        assertEquals(SelectionConfidence.HIGH, matches.first().confidence)
        assertTrue(matches.first().matchedTerms.contains("Pay now"))
        assertTrue(matches.first().matchedTerms.contains("checkoutPrimaryButton"))
        assertTrue(matches.first().matchReasons.contains("selected text"))
        assertTrue(matches.first().matchReasons.contains("selected testTag"))
        assertTrue(matches.first().matchReasons.contains("selected role"))
        assertTrue(matches.first().matchReasons.contains("activity"))
    }

    @Test
    fun usesNearbyContextToRankGenericSelectedText() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "sample/src/main/java/io/github/pointpatch/sample/screens/CheckoutScreen.kt",
                    line = 88,
                    symbols = listOf("PaymentErrorCard"),
                    text = listOf("Retry", "Payment failed"),
                    contentDescriptions = listOf("Payment failed"),
                    excerpt = "PaymentErrorCard { Text(\"Payment failed\"); Button { Text(\"Retry\") } }"
                ),
                SourceIndexEntry(
                    file = "sample/src/main/java/io/github/pointpatch/sample/screens/DialogScreen.kt",
                    line = 25,
                    symbols = listOf("RetryButton"),
                    text = listOf("Retry"),
                    excerpt = "Button { Text(\"Retry\") }"
                )
            )
        )

        val matches = SourceMatcher(index).match(
            selectedNode = node(uid = "retry", text = listOf("Retry"), role = "Button", actions = listOf("OnClick")),
            nearbyNodes = listOf(
                node(uid = "error-label", text = listOf("Payment failed")),
                node(uid = "error-icon", contentDescription = listOf("Payment failed"))
            ),
            activityName = null
        )

        assertEquals("sample/src/main/java/io/github/pointpatch/sample/screens/CheckoutScreen.kt", matches.first().file)
        assertTrue(matches.first().matchedTerms.contains("Payment failed"))
        assertTrue(matches.first().matchReasons.contains("nearby text"))
        assertTrue(matches.first().score > matches[1].score)
    }

    @Test
    fun limitsMatchesToFiveDeterministically() {
        val index = SourceIndex(
            entries = (1..8).map { number ->
                SourceIndexEntry(
                    file = "Screen$number.kt",
                    line = number,
                    symbols = listOf("SaveButton$number"),
                    text = listOf("Save"),
                    testTags = listOf("saveButton")
                )
            }
        )

        val matches = SourceMatcher(index).match(
            selectedNode = node(uid = "save", text = listOf("Save"), testTag = "saveButton"),
            nearbyNodes = emptyList(),
            activityName = null
        )

        assertEquals(5, matches.size)
        assertEquals(listOf("Screen1.kt", "Screen2.kt", "Screen3.kt", "Screen4.kt", "Screen5.kt"), matches.map { it.file })
    }

    @Test
    fun duplicateSelectedAndNearbyTermsDoNotIncreaseScoreOrConfidence() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "RetryScreen.kt",
                    line = 12,
                    text = listOf("Retry", "Payment failed"),
                    excerpt = "Text(\"Payment failed\"); Button { Text(\"Retry\") }"
                )
            )
        )

        val unique = SourceMatcher(index).match(
            selectedNode = node(uid = "retry", text = listOf("Retry")),
            nearbyNodes = listOf(node(uid = "error", text = listOf("Payment failed"))),
            activityName = null
        ).single()

        val duplicated = SourceMatcher(index).match(
            selectedNode = node(uid = "retry", text = listOf("Retry", "Retry")),
            nearbyNodes = listOf(
                node(uid = "error-1", text = listOf("Payment failed")),
                node(uid = "error-2", text = listOf("Payment failed"))
            ),
            activityName = null
        ).single()

        assertEquals(unique.score, duplicated.score, 0.0)
        assertEquals(unique.confidence, duplicated.confidence)
        assertEquals(unique.matchedTerms, duplicated.matchedTerms)
        assertEquals(unique.matchReasons, duplicated.matchReasons)
    }

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        contentDescription: List<String> = emptyList(),
        role: String? = null,
        testTag: String? = null,
        actions: List<String> = emptyList()
    ): PointPatchNode = PointPatchNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = PointPatchRect(left = 0f, top = 0f, right = 100f, bottom = 50f),
        text = text,
        contentDescription = contentDescription,
        role = role,
        testTag = testTag,
        actions = actions
    )
}
