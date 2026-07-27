package io.github.beyondwin.fixthis.mcp.session.verification

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FeedbackVerificationVerdict {
    @SerialName("pass") PASS,
    @SerialName("warn") WARN,
    @SerialName("fail") FAIL,
}

@Serializable
enum class FeedbackVerificationAssertionKind {
    @SerialName("text_present") TEXT_PRESENT,
    @SerialName("text_absent") TEXT_ABSENT,
    @SerialName("target_present") TARGET_PRESENT,
}

@Serializable
enum class FeedbackVerificationCheckOutcome {
    @SerialName("passed") PASSED,
    @SerialName("warning") WARNING,
    @SerialName("failed") FAILED,
}

@Serializable
enum class FeedbackTargetCorrespondence {
    @SerialName("high") HIGH,
    @SerialName("medium") MEDIUM,
    @SerialName("low") LOW,
    @SerialName("none") NONE,
}

@Serializable
data class FeedbackVerificationAssertionDto(
    val kind: FeedbackVerificationAssertionKind,
    val value: String? = null,
    val role: String? = null,
)

internal data class FeedbackAssertionEvaluation(
    val assertion: FeedbackVerificationAssertionDto,
    val outcome: FeedbackVerificationCheckOutcome,
    val message: String,
)

@Serializable
data class FeedbackVerificationCheckDto(
    val kind: String,
    val outcome: FeedbackVerificationCheckOutcome,
    val message: String,
)

internal fun boundFeedbackVerificationChecks(
    checks: List<FeedbackVerificationCheckDto>,
): List<FeedbackVerificationCheckDto> = checks
    .take(MAX_VERIFICATION_CHECKS)
    .map { check ->
        check.copy(message = check.message.take(MAX_VERIFICATION_MESSAGE_LENGTH))
    }

@Serializable
data class MatchedTargetSummaryDto(
    val confidence: FeedbackTargetCorrespondence,
    val nodeUid: String? = null,
    val role: String? = null,
    val text: List<String> = emptyList(),
    val contentDescriptions: List<String> = emptyList(),
    val boundsInWindow: FixThisRect? = null,
)

@Serializable
data class FeedbackVerificationReceiptDto(
    val receiptId: String,
    val itemId: String,
    val baselineScreenId: String,
    val createdAtEpochMillis: Long,
    val verdict: FeedbackVerificationVerdict,
    var checks: List<FeedbackVerificationCheckDto>,
    val assertions: List<FeedbackVerificationAssertionDto>,
    val currentActivity: String? = null,
    val currentScreenFingerprint: String? = null,
    val installedAtEpochMillis: Long? = null,
    val afterScreenshot: SnapshotScreenshotDto? = null,
    val matchedTargetSummary: MatchedTargetSummaryDto? = null,
) {
    init {
        checks = boundFeedbackVerificationChecks(checks)
    }
}

data class FeedbackVerificationRequest(
    val sessionId: String,
    val itemId: String,
    val assertions: List<FeedbackVerificationAssertionDto>,
)

private const val MAX_VERIFICATION_CHECKS = 16
private const val MAX_VERIFICATION_MESSAGE_LENGTH = 512
