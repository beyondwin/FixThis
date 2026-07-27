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
            assertions.isEmpty() ||
            !hasRequiredPassEvidence(checks) ->
            FeedbackVerificationVerdict.WARN

        else -> FeedbackVerificationVerdict.PASS
    }

    fun boundedChecks(checks: List<FeedbackVerificationCheckDto>): List<FeedbackVerificationCheckDto> = boundFeedbackVerificationChecks(checks)

    private fun hasRequiredPassEvidence(checks: List<FeedbackVerificationCheckDto>): Boolean = RequiredPassEvidence.entries.all { evidence ->
        checks.any { check ->
            check.outcome == FeedbackVerificationCheckOutcome.PASSED &&
                check.kind in evidence.acceptedKinds
        }
    }

    private enum class RequiredPassEvidence(
        val acceptedKinds: Set<String>,
    ) {
        EXPECTED_PACKAGE_REACHABLE(setOf("APP_AVAILABLE")),
        INSTALL_FRESH(setOf("SOURCE_INSTALL_FRESH")),
        SCREEN_COMPATIBLE(setOf("SCREEN_CONTEXT_MATCH")),
        TARGET_CORRESPONDS(setOf("TARGET_HIGH", "TARGET_MEDIUM")),
    }
}
