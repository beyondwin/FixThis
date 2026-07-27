package io.github.beyondwin.fixthis.mcp.session.verification

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Collections

@Serializable
enum class FeedbackVerificationVerdict {
    @SerialName("pass")
    PASS,

    @SerialName("warn")
    WARN,

    @SerialName("fail")
    FAIL,
}

@Serializable
enum class FeedbackVerificationAssertionKind {
    @SerialName("text_present")
    TEXT_PRESENT,

    @SerialName("text_absent")
    TEXT_ABSENT,

    @SerialName("target_present")
    TARGET_PRESENT,
}

@Serializable
enum class FeedbackVerificationCheckOutcome {
    @SerialName("passed")
    PASSED,

    @SerialName("warning")
    WARNING,

    @SerialName("failed")
    FAILED,
}

@Serializable
enum class FeedbackTargetCorrespondence {
    @SerialName("high")
    HIGH,

    @SerialName("medium")
    MEDIUM,

    @SerialName("low")
    LOW,

    @SerialName("none")
    NONE,
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
    checks: Collection<FeedbackVerificationCheckDto>,
): List<FeedbackVerificationCheckDto> = Collections.unmodifiableList(
    checks
        .take(MAX_VERIFICATION_CHECKS)
        .map { check ->
            check.copy(message = check.message.take(MAX_VERIFICATION_MESSAGE_LENGTH))
        },
)

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
@Suppress("LongParameterList")
class FeedbackVerificationReceiptDto(
    val receiptId: String,
    val itemId: String,
    val baselineScreenId: String,
    val createdAtEpochMillis: Long,
    val verdict: FeedbackVerificationVerdict,
    @SerialName("checks")
    private var persistedChecks: List<FeedbackVerificationCheckDto>,
    val assertions: List<FeedbackVerificationAssertionDto>,
    val currentActivity: String? = null,
    val currentScreenFingerprint: String? = null,
    val installedAtEpochMillis: Long? = null,
    val afterScreenshot: SnapshotScreenshotDto? = null,
    val matchedTargetSummary: MatchedTargetSummaryDto? = null,
) {
    val checks: List<FeedbackVerificationCheckDto>
        get() = persistedChecks

    init {
        persistedChecks = boundFeedbackVerificationChecks(persistedChecks)
    }

    @Suppress("LongParameterList")
    constructor(
        receiptId: String,
        itemId: String,
        baselineScreenId: String,
        createdAtEpochMillis: Long,
        verdict: FeedbackVerificationVerdict,
        checks: Collection<FeedbackVerificationCheckDto>,
        assertions: List<FeedbackVerificationAssertionDto>,
        currentActivity: String? = null,
        currentScreenFingerprint: String? = null,
        installedAtEpochMillis: Long? = null,
        afterScreenshot: SnapshotScreenshotDto? = null,
        matchedTargetSummary: MatchedTargetSummaryDto? = null,
    ) : this(
        receiptId = receiptId,
        itemId = itemId,
        baselineScreenId = baselineScreenId,
        createdAtEpochMillis = createdAtEpochMillis,
        verdict = verdict,
        persistedChecks = checks.toList(),
        assertions = assertions,
        currentActivity = currentActivity,
        currentScreenFingerprint = currentScreenFingerprint,
        installedAtEpochMillis = installedAtEpochMillis,
        afterScreenshot = afterScreenshot,
        matchedTargetSummary = matchedTargetSummary,
    )

    @Suppress("LongParameterList")
    fun copy(
        receiptId: String = this.receiptId,
        itemId: String = this.itemId,
        baselineScreenId: String = this.baselineScreenId,
        createdAtEpochMillis: Long = this.createdAtEpochMillis,
        verdict: FeedbackVerificationVerdict = this.verdict,
        checks: Collection<FeedbackVerificationCheckDto> = this.checks,
        assertions: List<FeedbackVerificationAssertionDto> = this.assertions,
        currentActivity: String? = this.currentActivity,
        currentScreenFingerprint: String? = this.currentScreenFingerprint,
        installedAtEpochMillis: Long? = this.installedAtEpochMillis,
        afterScreenshot: SnapshotScreenshotDto? = this.afterScreenshot,
        matchedTargetSummary: MatchedTargetSummaryDto? = this.matchedTargetSummary,
    ): FeedbackVerificationReceiptDto = FeedbackVerificationReceiptDto(
        receiptId = receiptId,
        itemId = itemId,
        baselineScreenId = baselineScreenId,
        createdAtEpochMillis = createdAtEpochMillis,
        verdict = verdict,
        checks = checks,
        assertions = assertions,
        currentActivity = currentActivity,
        currentScreenFingerprint = currentScreenFingerprint,
        installedAtEpochMillis = installedAtEpochMillis,
        afterScreenshot = afterScreenshot,
        matchedTargetSummary = matchedTargetSummary,
    )

    @Suppress("ComplexCondition")
    override fun equals(other: Any?): Boolean = other is FeedbackVerificationReceiptDto &&
        receiptId == other.receiptId &&
        itemId == other.itemId &&
        baselineScreenId == other.baselineScreenId &&
        createdAtEpochMillis == other.createdAtEpochMillis &&
        verdict == other.verdict &&
        checks == other.checks &&
        assertions == other.assertions &&
        currentActivity == other.currentActivity &&
        currentScreenFingerprint == other.currentScreenFingerprint &&
        installedAtEpochMillis == other.installedAtEpochMillis &&
        afterScreenshot == other.afterScreenshot &&
        matchedTargetSummary == other.matchedTargetSummary

    override fun hashCode(): Int {
        var result = receiptId.hashCode()
        result = 31 * result + itemId.hashCode()
        result = 31 * result + baselineScreenId.hashCode()
        result = 31 * result + createdAtEpochMillis.hashCode()
        result = 31 * result + verdict.hashCode()
        result = 31 * result + checks.hashCode()
        result = 31 * result + assertions.hashCode()
        result = 31 * result + (currentActivity?.hashCode() ?: 0)
        result = 31 * result + (currentScreenFingerprint?.hashCode() ?: 0)
        result = 31 * result + (installedAtEpochMillis?.hashCode() ?: 0)
        result = 31 * result + (afterScreenshot?.hashCode() ?: 0)
        result = 31 * result + (matchedTargetSummary?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "FeedbackVerificationReceiptDto(" +
        "receiptId=$receiptId, " +
        "itemId=$itemId, " +
        "baselineScreenId=$baselineScreenId, " +
        "createdAtEpochMillis=$createdAtEpochMillis, " +
        "verdict=$verdict, " +
        "checks=$checks, " +
        "assertions=$assertions, " +
        "currentActivity=$currentActivity, " +
        "currentScreenFingerprint=$currentScreenFingerprint, " +
        "installedAtEpochMillis=$installedAtEpochMillis, " +
        "afterScreenshot=$afterScreenshot, " +
        "matchedTargetSummary=$matchedTargetSummary)"
}

data class FeedbackVerificationRequest(
    val sessionId: String,
    val itemId: String,
    val assertions: List<FeedbackVerificationAssertionDto>,
)

private const val MAX_VERIFICATION_CHECKS = 16
private const val MAX_VERIFICATION_MESSAGE_LENGTH = 512
