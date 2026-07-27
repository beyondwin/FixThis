package io.github.beyondwin.fixthis.mcp.session.verification

internal class FeedbackAssertionEvaluator {
    fun evaluate(
        assertions: List<FeedbackVerificationAssertionDto>,
        correspondence: FeedbackTargetCorrespondenceResult?,
    ): List<FeedbackAssertionEvaluation> = assertions.map { assertion ->
        evaluate(assertion, correspondence)
    }

    private fun evaluate(
        assertion: FeedbackVerificationAssertionDto,
        correspondence: FeedbackTargetCorrespondenceResult?,
    ): FeedbackAssertionEvaluation {
        val node = correspondence?.matchedNode
        val correspondencePresent = correspondence?.confidence in setOf(
            FeedbackTargetCorrespondence.HIGH,
            FeedbackTargetCorrespondence.MEDIUM,
            FeedbackTargetCorrespondence.LOW,
        )
        val matchesRole = assertion.role == null || assertion.role == node?.role
        val targetStrings = node?.let {
            buildList {
                addAll(it.text)
                it.editableText?.let(::add)
                addAll(it.contentDescription)
                it.stateDescription?.let(::add)
            }
        }.orEmpty()
        val passed = correspondencePresent && when (assertion.kind) {
            FeedbackVerificationAssertionKind.TEXT_PRESENT ->
                matchesRole && targetStrings.any { it.contains(assertion.value.orEmpty(), ignoreCase = false) }
            FeedbackVerificationAssertionKind.TEXT_ABSENT ->
                matchesRole && targetStrings.none { it.contains(assertion.value.orEmpty(), ignoreCase = false) }
            FeedbackVerificationAssertionKind.TARGET_PRESENT ->
                matchesRole
        }
        val outcome = if (passed) {
            FeedbackVerificationCheckOutcome.PASSED
        } else {
            FeedbackVerificationCheckOutcome.FAILED
        }
        return FeedbackAssertionEvaluation(
            assertion = assertion,
            outcome = outcome,
            message = message(assertion.kind, outcome).take(MAX_MESSAGE_LENGTH),
        )
    }

    private fun message(
        kind: FeedbackVerificationAssertionKind,
        outcome: FeedbackVerificationCheckOutcome,
    ): String = when (kind) {
        FeedbackVerificationAssertionKind.TEXT_PRESENT ->
            if (outcome == FeedbackVerificationCheckOutcome.PASSED) {
                "Required text is present on the corresponding target"
            } else {
                "Required text is not present on the corresponding target"
            }
        FeedbackVerificationAssertionKind.TEXT_ABSENT ->
            if (outcome == FeedbackVerificationCheckOutcome.PASSED) {
                "Forbidden text is absent from the corresponding target"
            } else {
                "Forbidden text is present or the corresponding target role does not match"
            }
        FeedbackVerificationAssertionKind.TARGET_PRESENT ->
            if (outcome == FeedbackVerificationCheckOutcome.PASSED) {
                "The corresponding target is present"
            } else {
                "The corresponding target is not present"
            }
    }

    private companion object {
        const val MAX_MESSAGE_LENGTH = 512
    }
}
