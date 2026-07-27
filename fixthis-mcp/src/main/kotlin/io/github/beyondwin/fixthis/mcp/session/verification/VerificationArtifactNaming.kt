package io.github.beyondwin.fixthis.mcp.session.verification

import java.io.File

internal class FeedbackVerificationArtifactException(message: String) : RuntimeException(message)

internal data class VerificationArtifactStoreHooks(
    val afterSourceParentOpened: (java.nio.file.Path) -> Unit = {},
    val afterTemporaryDirectoryOpened: (java.nio.file.Path) -> Unit = {},
    val beforePromotion: () -> Unit = {},
    val afterFallbackFileOpenedBeforeValidation: (java.nio.file.Path) -> Unit = {},
    val beforeFallbackFileCreate: (java.nio.file.Path) -> Unit = {},
    val beforeFallbackDirectoryCreate: (java.nio.file.Path) -> Unit = {},
    val beforeFallbackDelete: (java.nio.file.Path) -> Unit = {},
    val beforeFallbackMove: (java.nio.file.Path, java.nio.file.Path) -> Unit = { _, _ -> },
    val rootDirectoryStreamFactory: (java.nio.file.Path) -> java.nio.file.DirectoryStream<java.nio.file.Path> = {
        java.nio.file.Files.newDirectoryStream(it)
    },
)

internal object VerificationArtifactNaming {
    const val FEEDBACK_ROOT_RELATIVE = ".fixthis/feedback-sessions"
    const val VERIFICATION_DIRECTORY = "verification"
    const val AFTER_SCREENSHOT = "after.png"
    const val MAX_SEGMENT_LENGTH = 128
    private val safeSegment = Regex("[A-Za-z0-9._-]+")
    private val token = Regex("[0-9a-f]{32}")
    private val temporary = Regex("\\.([A-Za-z0-9._-]{1,128})\\.tmp-([0-9a-f]{32})")
    private val reservation = Regex("\\.([A-Za-z0-9._-]{1,128})\\.reserve")

    fun validateSegment(value: String, label: String) {
        artifactRequire(
            value.length in 1..MAX_SEGMENT_LENGTH &&
                safeSegment.matches(value) &&
                value != "." &&
                value != "..",
        ) {
            "$label must be one safe path segment"
        }
    }

    fun validateToken(value: String) {
        artifactRequire(token.matches(value)) {
            "Verification artifact ownership token must contain exactly 32 lowercase hex characters"
        }
    }

    fun temporaryName(receiptId: String, ownershipToken: String): String {
        validateSegment(receiptId, "receiptId")
        validateToken(ownershipToken)
        return ".$receiptId.tmp-$ownershipToken"
    }

    fun reservationName(receiptId: String): String {
        validateSegment(receiptId, "receiptId")
        return ".$receiptId.reserve"
    }

    fun isTemporaryName(name: String): Boolean = temporary.matches(name)

    fun isTemporaryNameFor(name: String, receiptId: String, ownershipToken: String): Boolean = name == temporaryName(receiptId, ownershipToken)

    fun isReservationName(name: String): Boolean = reservation.matches(name)

    fun receiptIdFromReservation(name: String): String? = reservation.matchEntire(name)?.groupValues?.get(1)
}

internal class VerificationArtifactPaths(projectRoot: File) {
    val projectRoot: File = projectRoot.canonicalFile

    val feedbackRoot: File = this.projectRoot.resolve(VerificationArtifactNaming.FEEDBACK_ROOT_RELATIVE)

    fun sessionRoot(sessionId: String): File = feedbackRoot.resolve(sessionId)

    fun verificationRoot(sessionId: String): File = sessionRoot(sessionId)
        .resolve(VerificationArtifactNaming.VERIFICATION_DIRECTORY)

    fun temporaryDirectory(sessionId: String, receiptId: String, ownershipToken: String): File = verificationRoot(sessionId).resolve(
        VerificationArtifactNaming.temporaryName(receiptId, ownershipToken),
    )

    fun finalDirectory(sessionId: String, receiptId: String): File = verificationRoot(sessionId).resolve(receiptId)
}

internal inline fun artifactRequire(condition: Boolean, lazyMessage: () -> String) {
    if (!condition) throw FeedbackVerificationArtifactException(lazyMessage())
}

internal fun artifactFailure(
    operation: String,
    receiptId: String,
    failure: Exception,
): FeedbackVerificationArtifactException = if (failure is FeedbackVerificationArtifactException) {
    failure
} else {
    FeedbackVerificationArtifactException(
        "VERIFICATION_ARTIFACT_FAILED: Could not $operation receipt artifact $receiptId: ${failure.message}",
    )
}
