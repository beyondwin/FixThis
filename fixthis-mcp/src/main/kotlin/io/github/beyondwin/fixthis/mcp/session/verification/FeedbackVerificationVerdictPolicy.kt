package io.github.beyondwin.fixthis.mcp.session.verification

internal class FeedbackVerificationVerdictPolicy {
    fun decide(
        checks: List<FeedbackVerificationCheckDto>,
        assertions: List<FeedbackAssertionEvaluation>,
    ): FeedbackVerificationVerdict = when {
        checks.any { it.outcome == FeedbackVerificationCheckOutcome.FAILED } ||
            assertions.any { it.outcome == FeedbackVerificationCheckOutcome.FAILED } ->
            FeedbackVerificationVerdict.FAIL

        checks.any { it.outcome == FeedbackVerificationCheckOutcome.WARNING } ||
            assertions.any { it.outcome == FeedbackVerificationCheckOutcome.WARNING } ||
            assertions.isEmpty() ->
            FeedbackVerificationVerdict.WARN

        else -> FeedbackVerificationVerdict.PASS
    }

    fun boundedChecks(checks: List<FeedbackVerificationCheckDto>): List<FeedbackVerificationCheckDto> =
        checks.take(MAX_CHECKS).map { check ->
            check.copy(message = check.message.take(MAX_MESSAGE_LENGTH))
        }

    private companion object {
        const val MAX_CHECKS = 16
        const val MAX_MESSAGE_LENGTH = 512
    }
}
