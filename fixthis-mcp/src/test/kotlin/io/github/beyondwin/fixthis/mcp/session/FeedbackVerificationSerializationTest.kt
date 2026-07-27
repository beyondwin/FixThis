package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackTargetCorrespondence
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionKind
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationCheckDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationCheckOutcome
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationReceiptDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationVerdict
import io.github.beyondwin.fixthis.mcp.session.verification.MatchedTargetSummaryDto
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackVerificationSerializationTest {
    @Test
    fun oldSessionJsonDefaultsVerificationFields() {
        val decoded = fixThisJson.decodeFromString<SessionDto>(legacySessionJson)

        assertTrue(decoded.verificationReceipts.isEmpty())
        assertNull(decoded.items.single().resolutionVerificationReceiptId)
    }

    @Test
    fun receiptRoundTripKeepsBoundedEvidenceShape() {
        val receipt = receiptFixture(
            verdict = FeedbackVerificationVerdict.PASS,
            assertions = listOf(
                FeedbackVerificationAssertionDto(
                    kind = FeedbackVerificationAssertionKind.TEXT_PRESENT,
                    value = "Pay now",
                    role = "Button",
                ),
            ),
        )
        val decoded = fixThisJson.decodeFromString<FeedbackVerificationReceiptDto>(
            fixThisJson.encodeToString(receipt),
        )

        assertEquals(receipt, decoded)
    }

    @Test
    fun receiptConstructionAndSerializationEnforceCheckBudgets() {
        val receipt = receiptFixture(
            verdict = FeedbackVerificationVerdict.WARN,
            assertions = emptyList(),
            checks = List(20) { index ->
                FeedbackVerificationCheckDto(
                    kind = "CHECK_$index",
                    outcome = FeedbackVerificationCheckOutcome.WARNING,
                    message = "m".repeat(600),
                )
            },
        )
        val decoded = fixThisJson.decodeFromString<FeedbackVerificationReceiptDto>(
            fixThisJson.encodeToString(receipt),
        )

        assertEquals(16, receipt.checks.size)
        assertTrue(receipt.checks.all { it.message.length == 512 })
        assertEquals(receipt, decoded)
    }

    @Test
    fun receiptChecksHaveNoPublicMutationBypassAndCopyRebounds() {
        val receipt = receiptFixture(
            verdict = FeedbackVerificationVerdict.WARN,
            assertions = emptyList(),
        )
        val oversized = List(20) { index ->
            FeedbackVerificationCheckDto(
                kind = "CHECK_$index",
                outcome = FeedbackVerificationCheckOutcome.WARNING,
                message = "m".repeat(600),
            )
        }
        val publicSetter = FeedbackVerificationReceiptDto::class.java.methods
            .singleOrNull { it.name == "setChecks" }
        publicSetter?.invoke(receipt, oversized)
        val mutatedJson = fixThisJson.encodeToString(receipt)

        assertNull(publicSetter)
        assertTrue(
            fixThisJson.parseToJsonElement(mutatedJson).jsonObject
                .getValue("checks")
                .jsonArray
                .size <= 16,
        )

        val copied = receipt.copy(checks = oversized)
        val copiedJson = fixThisJson.encodeToString(copied)

        assertEquals(16, copied.checks.size)
        assertTrue(copied.checks.all { it.message.length == 512 })
        assertEquals(
            16,
            fixThisJson.parseToJsonElement(copiedJson).jsonObject.getValue("checks").jsonArray.size,
        )
    }

    private fun receiptFixture(
        verdict: FeedbackVerificationVerdict,
        assertions: List<FeedbackVerificationAssertionDto>,
        checks: List<FeedbackVerificationCheckDto> = listOf(
            FeedbackVerificationCheckDto(
                kind = "assertion",
                outcome = FeedbackVerificationCheckOutcome.PASSED,
                message = "Text is present",
            ),
        ),
    ) = FeedbackVerificationReceiptDto(
        receiptId = "receipt-1",
        itemId = "item-1",
        baselineScreenId = "screen-1",
        createdAtEpochMillis = 1_700_000_000_000L,
        verdict = verdict,
        checks = checks,
        assertions = assertions,
        currentActivity = "io.github.beyondwin.fixthis.sample.MainActivity",
        currentScreenFingerprint = "screen-fingerprint",
        installedAtEpochMillis = 1_699_999_999_000L,
        afterScreenshot = SnapshotScreenshotDto(
            desktopFullPath = "/repo/.fixthis/feedback-sessions/session-1/artifacts/screens/screen-1/after.png",
            width = 1080,
            height = 2400,
        ),
        matchedTargetSummary = MatchedTargetSummaryDto(
            confidence = FeedbackTargetCorrespondence.HIGH,
            nodeUid = "node-1",
            role = "Button",
            text = listOf("Pay now"),
            contentDescriptions = listOf("Submit payment"),
            boundsInWindow = FixThisRect(left = 10f, top = 20f, right = 210f, bottom = 80f),
        ),
    )

    private companion object {
        val legacySessionJson =
            """
            {
              "schemaVersion": "1.0",
              "sessionId": "session-1",
              "packageName": "io.github.beyondwin.fixthis.sample",
              "projectRoot": "/repo",
              "createdAtEpochMillis": 100,
              "updatedAtEpochMillis": 200,
              "items": [
                {
                  "itemId": "item-1",
                  "screenId": "screen-1",
                  "createdAtEpochMillis": 100,
                  "updatedAtEpochMillis": 200,
                  "target": {
                    "type": "visual_area",
                    "boundsInWindow": { "left": 0, "top": 0, "right": 100, "bottom": 100 }
                  },
                  "comment": "Fix spacing"
                }
              ]
            }
            """.trimIndent()
    }
}
