package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.IdentityHint
import io.github.beyondwin.fixthis.compose.core.model.TargetEvidence
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotRootDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackTargetCorrespondence
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackTargetCorrespondenceEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackTargetCorrespondenceEvaluatorTest {
    private val evaluator = FeedbackTargetCorrespondenceEvaluator()

    @Test
    fun matchingStableTagIsHighAndRoleTextFallbackIsMedium() {
        assertEquals(
            FeedbackTargetCorrespondence.HIGH,
            evaluator.evaluate(itemWithTag("pay"), screenWithTag("pay")).confidence,
        )
        assertEquals(
            FeedbackTargetCorrespondence.MEDIUM,
            evaluator.evaluate(
                itemWithRoleText("Button", "Pay now"),
                screenWithRoleText("Button", "Pay now"),
            ).confidence,
        )
    }

    @Test
    fun targetEvidenceIdentityIsHighWhenStrictTagChangedUid() {
        val item = itemWithRoleText("Button", "Pay now").copy(
            targetEvidence = TargetEvidence(
                identityHint = IdentityHint(
                    composableNameHint = "CheckoutButton",
                    variantHint = "primary",
                ),
            ),
        )
        val current = screenWith(
            node(
                uid = "current-node",
                role = "Button",
                text = listOf("Complete order"),
                testTag = "comp:CheckoutButton:primary",
            ),
        )

        assertEquals(FeedbackTargetCorrespondence.HIGH, evaluator.evaluate(item, current).confidence)
    }

    @Test
    fun deterministicTieBreakUsesSemanticCountThenIouThenLexicalUid() {
        val baseline = node(
            uid = "baseline",
            role = "Button",
            text = listOf("Pay now", "Secure"),
            bounds = FixThisRect(10f, 10f, 110f, 60f),
        )
        val item = item(baseline)
        val current = screenWith(
            node(
                uid = "z-one-overlap",
                role = "Button",
                text = listOf("Pay now"),
                bounds = baseline.boundsInWindow,
            ),
            node(
                uid = "z-two-overlaps",
                role = "Button",
                text = listOf("Pay now", "Secure"),
                bounds = FixThisRect(20f, 10f, 120f, 60f),
            ),
            node(
                uid = "a-two-overlaps",
                role = "Button",
                text = listOf("Pay now", "Secure"),
                bounds = FixThisRect(20f, 10f, 120f, 60f),
            ),
        )

        assertEquals("a-two-overlaps", evaluator.evaluate(item, current).matchedNode?.uid)
    }

    @Test
    fun visualAreaRequiresManualReviewWithoutInventingNodeIdentity() {
        val item = item(node("baseline")).copy(
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 100f, 100f)),
            selectedNode = null,
        )

        val result = evaluator.evaluate(item, screenWith(node("current")))

        assertEquals(FeedbackTargetCorrespondence.LOW, result.confidence)
        assertNull(result.matchedNode)
        assertTrue("MANUAL_VISUAL_REVIEW_REQUIRED" in result.reasons)
    }

    @Test
    fun summariesOmitSensitiveAndPasswordText() {
        val baseline = node(
            uid = "baseline",
            role = "TextField",
            testTag = "secret",
            bounds = FixThisRect(0f, 0f, 100f, 50f),
        )
        listOf(
            node(
                uid = "sensitive",
                role = "TextField",
                testTag = "secret",
                text = listOf("sensitive value"),
                contentDescription = listOf("sensitive description"),
                isSensitive = true,
                bounds = baseline.boundsInWindow,
            ),
            node(
                uid = "password",
                role = "TextField",
                testTag = "secret",
                text = listOf("password value"),
                contentDescription = listOf("password description"),
                isPassword = true,
                bounds = baseline.boundsInWindow,
            ),
        ).forEach { privateNode ->
            val summary = evaluator.evaluate(item(baseline), screenWith(privateNode)).summary

            assertTrue(summary != null)
            assertTrue(summary.text.isEmpty())
            assertTrue(summary.contentDescriptions.isEmpty())
        }
    }

    @Test
    fun nonSensitiveSummaryLimitsEntriesAndCharacters() {
        val baseline = node(uid = "baseline", role = "Button", testTag = "tag")
        val current = node(
            uid = "current",
            role = "Button",
            testTag = "tag",
            text = List(6) { "  text-$it-${"x".repeat(300)}  " },
            contentDescription = List(6) { "  description-$it-${"y".repeat(300)}  " },
        )

        val summary = evaluator.evaluate(item(baseline), screenWith(current)).summary!!

        assertEquals(4, summary.text.size)
        assertEquals(4, summary.contentDescriptions.size)
        assertTrue(summary.text.all { it.length <= 256 && it == it.trim() })
        assertTrue(summary.contentDescriptions.all { it.length <= 256 && it == it.trim() })
    }

    private fun itemWithTag(tag: String): AnnotationDto {
        val selected = node(uid = "baseline", role = "Button", testTag = tag)
        return item(selected)
    }

    private fun screenWithTag(tag: String) = screenWith(
        node(uid = "current", role = "Button", testTag = tag),
    )

    private fun itemWithRoleText(role: String, text: String): AnnotationDto {
        val selected = node(uid = "baseline", role = role, text = listOf(text))
        return item(selected)
    }

    private fun screenWithRoleText(role: String, text: String) = screenWith(
        node(uid = "current", role = role, text = listOf(text)),
    )

    private fun item(selected: FixThisNode) = AnnotationDto(
        itemId = "item-1",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        target = AnnotationTargetDto.Node(selected.uid, selected.boundsInWindow),
        selectedNode = selected,
        comment = "Fix it",
    )

    private fun screenWith(vararg nodes: FixThisNode) = SnapshotDto(
        screenId = "current-screen",
        capturedAtEpochMillis = 3L,
        displayName = "Checkout",
        roots = listOf(
            SnapshotRootDto(
                rootIndex = 0,
                boundsInWindow = FixThisRect(0f, 0f, 500f, 500f),
                mergedNodes = nodes.toList(),
                unmergedNodes = nodes.filter { it.treeKind == TreeKind.UNMERGED },
            ),
        ),
    )

    @Suppress("LongParameterList")
    private fun node(
        uid: String,
        role: String? = null,
        text: List<String> = emptyList(),
        contentDescription: List<String> = emptyList(),
        testTag: String? = null,
        isSensitive: Boolean = false,
        isPassword: Boolean = false,
        bounds: FixThisRect = FixThisRect(10f, 10f, 110f, 60f),
    ) = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
        contentDescription = contentDescription,
        role = role,
        testTag = testTag,
        isSensitive = isSensitive,
        isPassword = isPassword,
    )
}
