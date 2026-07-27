package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackAssertionEvaluation
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackAssertionEvaluator
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackTargetCorrespondenceEvaluator
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionKind
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationCheckDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationCheckOutcome
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationVerdict
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationVerdictPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class FeedbackVerificationVerdictPolicyTest {
    private val policy = FeedbackVerificationVerdictPolicy()

    @Test
    fun verdictTablePreventsFalsePasses() {
        assertEquals(
            FeedbackVerificationVerdict.FAIL,
            policy.decide(listOf(failed("SOURCE_INSTALL_STALE")), passedAssertions()),
        )
        assertEquals(
            FeedbackVerificationVerdict.WARN,
            policy.decide(listOf(warning("INSTALL_FRESHNESS_UNKNOWN")), passedAssertions()),
        )
        assertEquals(
            FeedbackVerificationVerdict.WARN,
            policy.decide(listOf(passed("TARGET_MEDIUM")), emptyList()),
        )
        assertEquals(
            FeedbackVerificationVerdict.PASS,
            policy.decide(passChecks(), passedAssertions()),
        )
        assertEquals(
            FeedbackVerificationVerdict.PASS,
            policy.decide(contextPassChecks() + passed("TARGET_MEDIUM"), passedAssertions()),
        )
    }

    @Test
    fun failedAssertionPrecedesWarnings() {
        val assertions = listOf(
            FeedbackAssertionEvaluation(
                assertion = FeedbackVerificationAssertionDto(
                    kind = FeedbackVerificationAssertionKind.TEXT_PRESENT,
                    value = "Pay now",
                ),
                outcome = FeedbackVerificationCheckOutcome.FAILED,
                message = "Required text was not present",
            ),
        )

        assertEquals(
            FeedbackVerificationVerdict.FAIL,
            policy.decide(listOf(warning("TARGET_LOW_CONFIDENCE")), assertions),
        )
    }

    @Test
    fun missingMandatoryPositiveChecksCannotPass() {
        assertEquals(
            FeedbackVerificationVerdict.WARN,
            policy.decide(emptyList(), passedAssertions()),
        )
        passChecks().indices.forEach { missingIndex ->
            assertEquals(
                FeedbackVerificationVerdict.WARN,
                policy.decide(passChecks().filterIndexed { index, _ -> index != missingIndex }, passedAssertions()),
            )
        }
    }

    @Test
    fun lowTargetEvidenceCannotPass() {
        assertEquals(
            FeedbackVerificationVerdict.WARN,
            policy.decide(
                contextPassChecks() + passed("TARGET_LOW_CONFIDENCE"),
                passedAssertions(),
            ),
        )
    }

    @Test
    fun visualAreaTargetPresentCannotProduceFalsePass() {
        val areaItem = AnnotationDto(
            itemId = "item-1",
            screenId = "screen-1",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 100f, 100f)),
            comment = "Fix spacing",
        )
        val correspondence = FeedbackTargetCorrespondenceEvaluator().evaluate(
            areaItem,
            SnapshotDto(
                screenId = "current",
                capturedAtEpochMillis = 3L,
                displayName = "Checkout",
            ),
        )
        val assertions = FeedbackAssertionEvaluator().evaluate(
            listOf(
                FeedbackVerificationAssertionDto(
                    kind = FeedbackVerificationAssertionKind.TARGET_PRESENT,
                ),
            ),
            correspondence,
        )

        assertEquals(FeedbackVerificationCheckOutcome.PASSED, assertions.single().outcome)
        assertEquals(
            FeedbackVerificationVerdict.WARN,
            policy.decide(contextPassChecks(), assertions),
        )
    }

    private fun contextPassChecks() = listOf(
        passed("APP_AVAILABLE"),
        passed("SOURCE_INSTALL_FRESH"),
        passed("SCREEN_CONTEXT_MATCH"),
    )

    private fun passChecks() = contextPassChecks() + listOf(
        passed("TARGET_HIGH"),
    )

    private fun passedAssertions() = listOf(
        FeedbackAssertionEvaluation(
            assertion = FeedbackVerificationAssertionDto(
                kind = FeedbackVerificationAssertionKind.TARGET_PRESENT,
            ),
            outcome = FeedbackVerificationCheckOutcome.PASSED,
            message = "Target is present",
        ),
    )

    private fun passed(kind: String) = FeedbackVerificationCheckDto(
        kind = kind,
        outcome = FeedbackVerificationCheckOutcome.PASSED,
        message = kind,
    )

    private fun warning(kind: String) = FeedbackVerificationCheckDto(
        kind = kind,
        outcome = FeedbackVerificationCheckOutcome.WARNING,
        message = kind,
    )

    private fun failed(kind: String) = FeedbackVerificationCheckDto(
        kind = kind,
        outcome = FeedbackVerificationCheckOutcome.FAILED,
        message = kind,
    )
}
