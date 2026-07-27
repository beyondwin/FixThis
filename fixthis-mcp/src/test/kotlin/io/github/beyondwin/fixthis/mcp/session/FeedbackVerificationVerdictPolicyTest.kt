package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackAssertionEvaluation
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionKind
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationCheckDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationCheckOutcome
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationVerdict
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationVerdictPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun finalChecksAreLimitedAndMessagesAreTruncated() {
        val bounded = policy.boundedChecks(
            List(20) { index ->
                passed("CHECK_$index", message = "m".repeat(600))
            },
        )

        assertEquals(16, bounded.size)
        assertTrue(bounded.all { it.message.length == 512 })
        assertEquals("CHECK_0", bounded.first().kind)
        assertEquals("CHECK_15", bounded.last().kind)
    }

    private fun passChecks() = listOf(
        passed("APP_AVAILABLE"),
        passed("SOURCE_INSTALL_FRESH"),
        passed("SCREEN_CONTEXT_MATCH"),
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

    private fun passed(kind: String, message: String = kind) = FeedbackVerificationCheckDto(
        kind = kind,
        outcome = FeedbackVerificationCheckOutcome.PASSED,
        message = message,
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
