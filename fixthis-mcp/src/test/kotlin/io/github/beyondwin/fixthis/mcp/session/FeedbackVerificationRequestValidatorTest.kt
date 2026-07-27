package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackDelivery
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionKind
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationRequestValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeedbackVerificationRequestValidatorTest {
    private val validator = FeedbackVerificationRequestValidator()

    @Test
    fun rejectsUnsentAndUnclaimedItemsWithStablePrefixes() {
        assertFailsWithMessage("VERIFICATION_ITEM_NOT_SENT:") {
            validator.validate(draftSession(), "item-1", emptyList())
        }
        assertFailsWithMessage("VERIFICATION_ITEM_NOT_IN_PROGRESS:") {
            validator.validate(sentOpenSession(), "item-1", emptyList())
        }
    }

    @Test
    fun rejectsClosedSessionAndMissingBaselineWithStablePrefixes() {
        assertFailsWithMessage("VERIFICATION_CONTEXT_CHANGED:") {
            validator.validate(claimedSession().copy(status = SessionStatusDto.CLOSED), "item-1", emptyList())
        }
        assertFailsWithMessage("VERIFICATION_BASELINE_NOT_FOUND:") {
            validator.validate(claimedSession().copy(screens = emptyList()), "item-1", emptyList())
        }
    }

    @Test
    fun rejectsAssertionBudgetsWithoutCreatingContext() {
        val oversized = FeedbackVerificationAssertionDto(
            kind = FeedbackVerificationAssertionKind.TEXT_PRESENT,
            value = "x".repeat(257),
        )
        assertFailsWithMessage("VERIFICATION_ASSERTIONS_INVALID:") {
            validator.validate(claimedSession(), "item-1", listOf(oversized))
        }
        assertFailsWithMessage("VERIFICATION_ASSERTIONS_INVALID:") {
            validator.validate(
                claimedSession(),
                "item-1",
                List(9) {
                    FeedbackVerificationAssertionDto(
                        kind = FeedbackVerificationAssertionKind.TARGET_PRESENT,
                    )
                },
            )
        }
    }

    @Test
    fun normalizesAssertionsAndCapturesOptimisticFences() {
        val session = claimedSession()

        val context = validator.validate(
            session,
            "item-1",
            listOf(
                FeedbackVerificationAssertionDto(
                    kind = FeedbackVerificationAssertionKind.TEXT_PRESENT,
                    value = "  Pay now  ",
                    role = "  Button  ",
                ),
            ),
        )

        assertEquals(session.sessionId, context.sessionId)
        assertEquals(session.updatedAtEpochMillis, context.sessionUpdatedAtEpochMillis)
        assertEquals(session.items.single(), context.item)
        assertEquals(session.items.single().updatedAtEpochMillis, context.itemUpdatedAtEpochMillis)
        assertEquals(session.screens.single(), context.baselineScreen)
        assertEquals(1, context.receiptCount)
        assertEquals("Pay now", context.assertions.single().value)
        assertEquals("Button", context.assertions.single().role)
    }

    @Test
    fun assertionKindsEnforceValueAndRoleContracts() {
        val invalidAssertions = listOf(
            FeedbackVerificationAssertionDto(FeedbackVerificationAssertionKind.TEXT_PRESENT, value = " "),
            FeedbackVerificationAssertionDto(FeedbackVerificationAssertionKind.TEXT_ABSENT, value = null),
            FeedbackVerificationAssertionDto(FeedbackVerificationAssertionKind.TARGET_PRESENT, value = "unexpected"),
            FeedbackVerificationAssertionDto(
                FeedbackVerificationAssertionKind.TARGET_PRESENT,
                role = "r".repeat(65),
            ),
        )

        invalidAssertions.forEach { assertion ->
            assertFailsWithMessage("VERIFICATION_ASSERTIONS_INVALID:") {
                validator.validate(claimedSession(), "item-1", listOf(assertion))
            }
        }
    }

    private fun draftSession() = session(
        delivery = FeedbackDelivery.DRAFT,
        itemStatus = AnnotationStatusDto.OPEN,
    )

    private fun sentOpenSession() = session(
        delivery = FeedbackDelivery.SENT,
        itemStatus = AnnotationStatusDto.OPEN,
    )

    private fun claimedSession() = session(
        delivery = FeedbackDelivery.SENT,
        itemStatus = AnnotationStatusDto.IN_PROGRESS,
    )

    private fun session(
        delivery: FeedbackDelivery,
        itemStatus: AnnotationStatusDto,
    ): SessionDto {
        val baseline = SnapshotDto(
            screenId = "screen-1",
            capturedAtEpochMillis = 90L,
            displayName = "Checkout",
        )
        return SessionDto(
            sessionId = "session-1",
            packageName = "sample.app",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 200L,
            screens = listOf(baseline),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = baseline.screenId,
                    createdAtEpochMillis = 100L,
                    updatedAtEpochMillis = 150L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                    comment = "Fix it",
                    delivery = delivery,
                    status = itemStatus,
                ),
            ),
            verificationReceipts = listOf(
                io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationReceiptDto(
                    receiptId = "receipt-1",
                    itemId = "other-item",
                    baselineScreenId = baseline.screenId,
                    createdAtEpochMillis = 180L,
                    verdict = io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationVerdict.WARN,
                    checks = emptyList(),
                    assertions = emptyList(),
                ),
            ),
        )
    }

    private fun assertFailsWithMessage(prefix: String, block: () -> Unit) {
        val failure = assertFailsWith<IllegalArgumentException>(block = block)
        assertTrue(failure.message.orEmpty().startsWith(prefix), failure.message)
    }
}
