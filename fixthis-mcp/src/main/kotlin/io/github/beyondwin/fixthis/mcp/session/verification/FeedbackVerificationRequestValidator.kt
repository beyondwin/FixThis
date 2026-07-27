package io.github.beyondwin.fixthis.mcp.session.verification

import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackDelivery

internal data class FeedbackVerificationStartContext(
    val sessionId: String,
    val sessionUpdatedAtEpochMillis: Long,
    val item: AnnotationDto,
    val itemUpdatedAtEpochMillis: Long,
    val baselineScreen: SnapshotDto,
    val receiptCount: Int,
    val assertions: List<FeedbackVerificationAssertionDto>,
)

internal class FeedbackVerificationRequestValidator {
    fun validate(
        session: SessionDto,
        itemId: String,
        assertions: List<FeedbackVerificationAssertionDto>,
    ): FeedbackVerificationStartContext {
        requireVerification(
            session.status != SessionStatusDto.CLOSED,
            "VERIFICATION_CONTEXT_CHANGED:",
            "Session is closed",
        )
        val item = session.items.singleOrNull { it.itemId == itemId }
            ?: verificationError("VERIFICATION_CONTEXT_CHANGED:", "Item not found: $itemId")
        requireVerification(
            item.delivery == FeedbackDelivery.SENT,
            "VERIFICATION_ITEM_NOT_SENT:",
            itemId,
        )
        requireVerification(
            item.status == AnnotationStatusDto.IN_PROGRESS,
            "VERIFICATION_ITEM_NOT_IN_PROGRESS:",
            itemId,
        )
        val baseline = session.screens.singleOrNull { it.screenId == item.screenId }
            ?: verificationError("VERIFICATION_BASELINE_NOT_FOUND:", item.screenId)
        requireVerification(
            assertions.size <= MAX_ASSERTIONS,
            "VERIFICATION_ASSERTIONS_INVALID:",
            "At most 8 assertions are allowed",
        )
        val normalizedAssertions = assertions.map(::normalizeAssertion)

        return FeedbackVerificationStartContext(
            sessionId = session.sessionId,
            sessionUpdatedAtEpochMillis = session.updatedAtEpochMillis,
            item = item,
            itemUpdatedAtEpochMillis = item.updatedAtEpochMillis,
            baselineScreen = baseline,
            receiptCount = session.verificationReceipts.size,
            assertions = normalizedAssertions,
        )
    }

    private fun normalizeAssertion(
        assertion: FeedbackVerificationAssertionDto,
    ): FeedbackVerificationAssertionDto {
        val normalizedValue = assertion.value?.trim()
        val normalizedRole = assertion.role?.trim()?.takeUnless(String::isBlank)

        requireVerification(
            normalizedValue == null || normalizedValue.length <= MAX_ASSERTION_VALUE_LENGTH,
            "VERIFICATION_ASSERTIONS_INVALID:",
            "Assertion values must be at most 256 characters",
        )
        requireVerification(
            normalizedRole == null || normalizedRole.length <= MAX_ASSERTION_ROLE_LENGTH,
            "VERIFICATION_ASSERTIONS_INVALID:",
            "Assertion roles must be at most 64 characters",
        )
        when (assertion.kind) {
            FeedbackVerificationAssertionKind.TEXT_PRESENT,
            FeedbackVerificationAssertionKind.TEXT_ABSENT,
            -> requireVerification(
                !normalizedValue.isNullOrBlank(),
                "VERIFICATION_ASSERTIONS_INVALID:",
                "${assertion.kind} requires a nonblank value",
            )

            FeedbackVerificationAssertionKind.TARGET_PRESENT -> requireVerification(
                assertion.value == null,
                "VERIFICATION_ASSERTIONS_INVALID:",
                "TARGET_PRESENT does not accept a value",
            )
        }
        return assertion.copy(value = normalizedValue, role = normalizedRole)
    }

    private fun requireVerification(
        condition: Boolean,
        prefix: String,
        detail: String,
    ) {
        require(condition) { "$prefix $detail" }
    }

    private fun verificationError(prefix: String, detail: String): Nothing = throw IllegalArgumentException("$prefix $detail")

    private companion object {
        const val MAX_ASSERTIONS = 8
        const val MAX_ASSERTION_VALUE_LENGTH = 256
        const val MAX_ASSERTION_ROLE_LENGTH = 64
    }
}
