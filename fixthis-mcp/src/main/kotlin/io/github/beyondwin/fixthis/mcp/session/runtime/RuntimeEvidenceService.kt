package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto

internal class RuntimeEvidenceService(
    private val idGenerator: () -> String,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun attachManualSummary(
        session: SessionDto,
        itemId: String,
        type: RuntimeEvidenceType,
        summary: String,
        artifactPath: String?,
    ): SessionDto {
        val item = session.items.firstOrNull { it.itemId == itemId }
            ?: throw IllegalArgumentException("Unknown feedback item: $itemId")
        val evidenceId = idGenerator()
        val now = clock()
        val attachment = RuntimeEvidenceAttachment(
            evidenceId = evidenceId,
            type = type,
            capturedAtEpochMillis = now,
            packageName = session.packageName,
            summary = summary.take(MaxSummaryChars),
            artifactPath = artifactPath?.validatedArtifactPath(),
        )
        return session.copy(
            runtimeEvidence = session.runtimeEvidence + attachment,
            items = session.items.map { existing ->
                if (existing.itemId == item.itemId) {
                    existing.copy(runtimeEvidenceIds = (existing.runtimeEvidenceIds + evidenceId).distinct())
                } else {
                    existing
                }
            },
            updatedAtEpochMillis = now,
        )
    }

    private companion object {
        const val MaxSummaryChars = 240
    }
}

private fun String.validatedArtifactPath(): String {
    require(!startsWith("/")) { "Runtime evidence artifactPath must be a relative .fixthis/ path" }
    require(!contains("..")) { "Runtime evidence artifactPath must not traverse directories" }
    require(startsWith(".fixthis/")) { "Runtime evidence artifactPath must be under .fixthis/" }
    return this
}
