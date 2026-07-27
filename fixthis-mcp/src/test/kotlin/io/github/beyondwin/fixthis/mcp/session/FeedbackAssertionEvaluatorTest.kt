package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotRootDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackAssertionEvaluator
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackTargetCorrespondence
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackTargetCorrespondenceEvaluator
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackTargetCorrespondenceResult
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionKind
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationCheckOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackAssertionEvaluatorTest {
    private val evaluator = FeedbackTargetCorrespondenceEvaluator()
    private val assertionEvaluator = FeedbackAssertionEvaluator()

    @Test
    fun assertionsAreScopedToTheCorrespondingNode() {
        val target = node(uid = "target", text = listOf("Pay now"), role = "Button")
        val current = screenWithNodes(
            target = target,
            unrelated = node(uid = "unrelated", text = listOf("Delete account"), role = "Button"),
        )
        val correspondence = evaluator.evaluate(itemWithRoleText("Button", "Pay now"), current)
        val result = assertionEvaluator.evaluate(
            listOf(textAbsent("Delete account", role = "Button")),
            correspondence,
        )

        assertEquals(FeedbackVerificationCheckOutcome.PASSED, result.single().outcome)
    }

    @Test
    fun textChecksAreCaseSensitiveAndRoleScoped() {
        val correspondence = correspondence(
            node(
                uid = "target",
                text = listOf("Pay now"),
                contentDescription = listOf("Submit payment"),
                role = "Button",
            ),
        )

        assertEquals(
            FeedbackVerificationCheckOutcome.PASSED,
            assertionEvaluator.evaluate(listOf(textPresent("Pay now", "Button")), correspondence).single().outcome,
        )
        assertEquals(
            FeedbackVerificationCheckOutcome.FAILED,
            assertionEvaluator.evaluate(listOf(textPresent("pay now", "Button")), correspondence).single().outcome,
        )
        assertEquals(
            FeedbackVerificationCheckOutcome.FAILED,
            assertionEvaluator.evaluate(listOf(textAbsent("Pay now", "Text")), correspondence).single().outcome,
        )
    }

    @Test
    fun targetPresentAcceptsLowButNoneFailsEveryAssertion() {
        val targetPresent = FeedbackVerificationAssertionDto(
            kind = FeedbackVerificationAssertionKind.TARGET_PRESENT,
        )
        val low = correspondence(node(uid = "target"), FeedbackTargetCorrespondence.LOW)
        val none = FeedbackTargetCorrespondenceResult(
            confidence = FeedbackTargetCorrespondence.NONE,
            matchedNode = null,
            reasons = listOf("TARGET_NOT_FOUND"),
            summary = null,
        )

        assertEquals(
            FeedbackVerificationCheckOutcome.PASSED,
            assertionEvaluator.evaluate(listOf(targetPresent), low).single().outcome,
        )
        listOf(
            targetPresent,
            textPresent("Pay now", null),
            textAbsent("Pay now", null),
        ).forEach { assertion ->
            assertEquals(
                FeedbackVerificationCheckOutcome.FAILED,
                assertionEvaluator.evaluate(listOf(assertion), none).single().outcome,
            )
        }
    }

    @Test
    fun assertionMessagesAreBounded() {
        val result = assertionEvaluator.evaluate(
            listOf(textPresent("x".repeat(600), null)),
            correspondence(node(uid = "target")),
        )

        assertTrue(result.single().message.length <= 512)
    }

    private fun itemWithRoleText(role: String, text: String): AnnotationDto {
        val selected = node(uid = "baseline", text = listOf(text), role = role)
        return AnnotationDto(
            itemId = "item-1",
            screenId = "screen-1",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            target = AnnotationTargetDto.Node(selected.uid, selected.boundsInWindow),
            selectedNode = selected,
            comment = "Fix it",
        )
    }

    private fun screenWithNodes(target: FixThisNode, unrelated: FixThisNode) = SnapshotDto(
        screenId = "current",
        capturedAtEpochMillis = 3L,
        displayName = "Checkout",
        roots = listOf(
            SnapshotRootDto(
                rootIndex = 0,
                boundsInWindow = FixThisRect(0f, 0f, 500f, 500f),
                mergedNodes = listOf(target, unrelated),
            ),
        ),
    )

    private fun correspondence(
        node: FixThisNode,
        confidence: FeedbackTargetCorrespondence = FeedbackTargetCorrespondence.HIGH,
    ) = FeedbackTargetCorrespondenceResult(
        confidence = confidence,
        matchedNode = node,
        reasons = emptyList(),
        summary = null,
    )

    private fun textPresent(value: String, role: String?) = FeedbackVerificationAssertionDto(
        kind = FeedbackVerificationAssertionKind.TEXT_PRESENT,
        value = value,
        role = role,
    )

    private fun textAbsent(value: String, role: String?) = FeedbackVerificationAssertionDto(
        kind = FeedbackVerificationAssertionKind.TEXT_ABSENT,
        value = value,
        role = role,
    )

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        contentDescription: List<String> = emptyList(),
        role: String? = null,
    ) = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(10f, 10f, 110f, 60f),
        text = text,
        contentDescription = contentDescription,
        role = role,
    )
}
