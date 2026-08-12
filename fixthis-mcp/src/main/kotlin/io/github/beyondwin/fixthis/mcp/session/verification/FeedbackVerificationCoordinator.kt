package io.github.beyondwin.fixthis.mcp.session.verification

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewCaptureService
import io.github.beyondwin.fixthis.mcp.session.source.HostSourceFreshnessProbe
import io.github.beyondwin.fixthis.mcp.session.source.HostSourceFreshnessResult
import io.github.beyondwin.fixthis.mcp.session.target.TargetEvidenceService
import io.github.beyondwin.fixthis.mcp.tools.FixThisBridge
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

internal interface FeedbackVerificationSessionAccess {
    fun getSession(sessionId: String): SessionDto

    fun captureContext(
        sessionId: String,
        itemId: String,
        assertions: List<FeedbackVerificationAssertionDto>,
    ): FeedbackVerificationStartContext

    fun validateContext(context: FeedbackVerificationStartContext)

    fun attachReceipt(
        context: FeedbackVerificationStartContext,
        receipt: FeedbackVerificationReceiptDto,
    ): SessionDto
}

@Suppress("LongParameterList", "TooManyFunctions")
internal class FeedbackVerificationCoordinator(
    private val bridge: FixThisBridge,
    private val sessionAccess: FeedbackVerificationSessionAccess,
    private val previewCaptureService: PreviewCaptureService,
    private val targetEvidenceService: TargetEvidenceService,
    private val freshnessProbe: HostSourceFreshnessProbe,
    private val artifactStore: FeedbackVerificationArtifactStore,
    private val correspondenceEvaluator: FeedbackTargetCorrespondenceEvaluator = FeedbackTargetCorrespondenceEvaluator(),
    private val assertionEvaluator: FeedbackAssertionEvaluator = FeedbackAssertionEvaluator(),
    private val verdictPolicy: FeedbackVerificationVerdictPolicy = FeedbackVerificationVerdictPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun verify(request: FeedbackVerificationRequest): FeedbackVerificationReceiptDto {
        val context = sessionAccess.captureContext(request.sessionId, request.itemId, request.assertions)
        val session = sessionAccess.getSession(request.sessionId)
        val receiptId = idGenerator()
        VerificationArtifactNaming.validateSessionId(session.sessionId)
        VerificationArtifactNaming.validateReceiptId(receiptId)
        val captureDirectory = File(
            session.projectRoot,
            ".fixthis/preview-cache/${session.sessionId}/verification-$receiptId",
        )
        var prepared: PreparedVerificationArtifact? = null
        var promoted = false
        return try {
            val evidence = collectEvidence(session, context, receiptId, captureDirectory)
            val receipt = assembleReceipt(receiptId, context, evidence)
            prepared = artifactStore.prepare(session, receiptId, evidence.currentScreen?.screenshot)
            sessionAccess.validateContext(context)
            val afterScreenshot = artifactStore.promote(checkNotNull(prepared))
            promoted = true
            artifactStore.discard(checkNotNull(prepared))
            prepared = null
            requireCaptureCleanup(captureDirectory, receiptId)
            sessionAccess.attachReceipt(
                context,
                receipt.copy(afterScreenshot = afterScreenshot),
            ).verificationReceipts.single { it.receiptId == receiptId }
        } catch (failure: CancellationException) {
            cleanupFailureArtifacts(session, receiptId, captureDirectory, prepared, promoted, failure)
            throw failure
        } catch (failure: Throwable) {
            cleanupFailureArtifacts(session, receiptId, captureDirectory, prepared, promoted, failure)
            throw failure
        }
    }

    private suspend fun collectEvidence(
        session: SessionDto,
        context: FeedbackVerificationStartContext,
        receiptId: String,
        captureDirectory: File,
    ): LiveVerificationEvidence {
        val status = bridgeResult { bridge.status(session.packageName) }
        val statusActivity = status.value?.stringOrNull("activity")
        val returnedPackage = status.value?.stringOrNull("packageName")
        val packageMismatch = returnedPackage != null && returnedPackage != session.packageName
        val statusAvailable = status.failure == null && !statusActivity.isNullOrBlank()
        val screen = if (statusAvailable && !packageMismatch) {
            bridgeResult {
                previewCaptureService.captureCurrentScreen(
                    session = session,
                    screenId = receiptId,
                    destinationDirectory = captureDirectory,
                    displayName = "Verification screen",
                )
            }
        } else {
            BridgeResult<SnapshotDto>(null, null)
        }
        val currentScreen = screen.value
        val sourceIndex = currentScreen?.let {
            targetEvidenceService.readSourceIndexOrNull(session.packageName, it)
        }
        val installEpoch = status.value?.get("installEpochMillis")?.jsonPrimitive?.longOrNull
        val freshness = sourceIndex?.let { freshnessProbe.evaluate(it, installEpoch) }
        val correspondence = currentScreen?.let { correspondenceEvaluator.evaluate(context.item, it) }
        val assertions = assertionEvaluator.evaluate(context.assertions, correspondence)
        val checks = buildChecks(
            statusAvailable = statusAvailable,
            statusFailure = status.failure,
            captureFailure = screen.failure,
            expectedPackage = session.packageName,
            returnedPackage = returnedPackage,
            currentScreen = currentScreen,
            context = context,
            freshness = freshness,
            installEpoch = installEpoch,
            correspondence = correspondence,
            assertions = assertions,
        )
        return LiveVerificationEvidence(
            currentScreen = currentScreen,
            freshness = freshness,
            installEpoch = installEpoch,
            correspondence = correspondence,
            assertions = assertions,
            checks = checks,
        )
    }

    @Suppress("LongParameterList")
    private fun buildChecks(
        statusAvailable: Boolean,
        statusFailure: Throwable?,
        captureFailure: Throwable?,
        expectedPackage: String,
        returnedPackage: String?,
        currentScreen: SnapshotDto?,
        context: FeedbackVerificationStartContext,
        freshness: HostSourceFreshnessResult?,
        installEpoch: Long?,
        correspondence: FeedbackTargetCorrespondenceResult?,
        assertions: List<FeedbackAssertionEvaluation>,
    ): List<FeedbackVerificationCheckDto> = buildList {
        val packageMismatch = returnedPackage != null && returnedPackage != expectedPackage
        when {
            packageMismatch -> add(failed("PACKAGE_MISMATCH", "Expected package $expectedPackage but reached $returnedPackage"))
            !statusAvailable || captureFailure != null || currentScreen?.activityName.isNullOrBlank() -> add(
                failed(
                    "APP_UNAVAILABLE",
                    statusFailure?.message ?: captureFailure?.message ?: "Expected app activity is unavailable",
                ),
            )
            else -> add(passed("APP_AVAILABLE", "Expected package is reachable"))
        }
        if (currentScreen != null) {
            add(freshnessCheck(freshness, installEpoch))
            add(screenContextCheck(context.baselineScreen.activityName, currentScreen.activityName))
            correspondence?.let { add(correspondenceCheck(it)) }
        }
        if (context.assertions.isEmpty()) {
            add(warning("ASSERTION_NOT_PROVIDED", "No explicit verification assertion was provided"))
        }
        assertions.forEach { evaluation -> add(evaluation.toCheck()) }
    }

    private fun freshnessCheck(
        freshness: HostSourceFreshnessResult?,
        installEpoch: Long?,
    ): FeedbackVerificationCheckDto = when {
        freshness?.installStale == true -> failed(
            "SOURCE_INSTALL_STALE",
            freshness.reason ?: "Host source is newer than the installed APK",
        )
        freshness == null || installEpoch == null || freshness.reason != null -> warning(
            "INSTALL_FRESHNESS_UNKNOWN",
            freshness?.reason ?: "Source index or install epoch is unavailable",
        )
        else -> passed("SOURCE_INSTALL_FRESH", "Installed APK is not older than indexed host source")
    }

    private fun screenContextCheck(
        baselineActivity: String?,
        currentActivity: String?,
    ): FeedbackVerificationCheckDto = when {
        baselineActivity.isNullOrBlank() || currentActivity.isNullOrBlank() -> warning(
            "SCREEN_CONTEXT_UNKNOWN",
            "Baseline or current activity identity is unavailable",
        )
        baselineActivity != currentActivity -> failed(
            "SCREEN_CONTEXT_MISMATCH",
            "Baseline activity $baselineActivity differs from current activity $currentActivity",
        )
        else -> passed("SCREEN_CONTEXT_MATCH", "Current activity matches the baseline activity")
    }

    private fun correspondenceCheck(result: FeedbackTargetCorrespondenceResult): FeedbackVerificationCheckDto = when (result.confidence) {
        FeedbackTargetCorrespondence.HIGH -> passed("TARGET_HIGH", "Current target has high correspondence")
        FeedbackTargetCorrespondence.MEDIUM -> passed("TARGET_MEDIUM", "Current target has medium correspondence")
        FeedbackTargetCorrespondence.LOW -> {
            val kind = if ("MANUAL_VISUAL_REVIEW_REQUIRED" in result.reasons) {
                "MANUAL_VISUAL_REVIEW_REQUIRED"
            } else {
                "TARGET_LOW_CONFIDENCE"
            }
            warning(kind, "Current target requires manual review")
        }
        FeedbackTargetCorrespondence.NONE -> failed("TARGET_NOT_FOUND", "No corresponding current target was found")
    }

    private fun assembleReceipt(
        receiptId: String,
        context: FeedbackVerificationStartContext,
        evidence: LiveVerificationEvidence,
    ): FeedbackVerificationReceiptDto {
        val boundedChecks = verdictPolicy.boundedChecks(evidence.checks)
        return FeedbackVerificationReceiptDto(
            receiptId = receiptId,
            itemId = context.item.itemId,
            baselineScreenId = context.baselineScreen.screenId,
            createdAtEpochMillis = clock(),
            verdict = verdictPolicy.decide(boundedChecks, evidence.assertions),
            checks = boundedChecks,
            assertions = context.assertions,
            currentActivity = evidence.currentScreen?.activityName,
            currentScreenFingerprint = evidence.currentScreen?.fingerprint,
            installedAtEpochMillis = evidence.freshness?.installedAtEpochMillis ?: evidence.installEpoch,
            matchedTargetSummary = evidence.correspondence?.summary,
        )
    }

    private fun cleanupFailureArtifacts(
        session: SessionDto,
        receiptId: String,
        captureDirectory: File,
        prepared: PreparedVerificationArtifact?,
        promoted: Boolean,
        failure: Throwable,
    ) {
        if (promoted) {
            suppressCleanupFailure(failure) {
                artifactStore.deleteReceiptArtifact(session, receiptId)
            }
        }
        prepared?.let { artifact -> suppressCleanupFailure(failure) { artifactStore.discard(artifact) } }
        suppressCleanupFailure(failure) { requireCaptureCleanup(captureDirectory, receiptId) }
    }

    private fun requireCaptureCleanup(directory: File, receiptId: String) {
        if (directory.exists() && !directory.deleteRecursively()) {
            throw FeedbackVerificationArtifactException(
                "VERIFICATION_ARTIFACT_FAILED: Could not delete temporary verification capture $receiptId",
            )
        }
    }

    private inline fun suppressCleanupFailure(primary: Throwable, cleanup: () -> Unit) {
        runCatching(cleanup).exceptionOrNull()?.let(primary::addSuppressed)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> bridgeResult(block: suspend () -> T): BridgeResult<T> = try {
        BridgeResult(block(), null)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        BridgeResult(null, failure)
    }

    private fun FeedbackAssertionEvaluation.toCheck(): FeedbackVerificationCheckDto = FeedbackVerificationCheckDto(
        kind = if (outcome == FeedbackVerificationCheckOutcome.PASSED) "ASSERTION_PASSED" else "ASSERTION_FAILED",
        outcome = outcome,
        message = message,
    )

    private fun passed(kind: String, message: String) = check(kind, FeedbackVerificationCheckOutcome.PASSED, message)

    private fun warning(kind: String, message: String) = check(kind, FeedbackVerificationCheckOutcome.WARNING, message)

    private fun failed(kind: String, message: String) = check(kind, FeedbackVerificationCheckOutcome.FAILED, message)

    private fun check(
        kind: String,
        outcome: FeedbackVerificationCheckOutcome,
        message: String,
    ) = FeedbackVerificationCheckDto(kind, outcome, message)

    private data class BridgeResult<T>(val value: T?, val failure: Throwable?)

    private data class LiveVerificationEvidence(
        val currentScreen: SnapshotDto?,
        val freshness: HostSourceFreshnessResult?,
        val installEpoch: Long?,
        val correspondence: FeedbackTargetCorrespondenceResult?,
        val assertions: List<FeedbackAssertionEvaluation>,
        val checks: List<FeedbackVerificationCheckDto>,
    )
}

private fun JsonObject.stringOrNull(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
