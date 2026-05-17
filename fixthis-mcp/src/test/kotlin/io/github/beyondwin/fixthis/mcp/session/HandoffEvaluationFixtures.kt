@file:Suppress("LongParameterList")

package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class HandoffEvaluationCorpus(
    val schemaVersion: Int,
    val cases: List<HandoffEvaluationCase>,
)

@Serializable
internal data class HandoffEvaluationCase(
    val id: String,
    val comment: String,
    val targetType: String,
    val selectedText: List<String> = emptyList(),
    val selectedRole: String? = null,
    val selectedTestTag: String? = null,
    val targetWarnings: List<String> = emptyList(),
    val sourceCandidates: List<HandoffEvaluationSourceCandidate> = emptyList(),
    val expectedRole: EditSurfaceRoleDto,
    val expectedTop3Contains: String? = null,
    val allowHighConfidence: Boolean,
)

@Serializable
internal data class HandoffEvaluationSourceCandidate(
    val file: String,
    val line: Int? = null,
    val score: Double,
    val confidence: String,
    val scoreMargin: Double? = null,
    val matchReasons: List<String> = emptyList(),
    val matchedTerms: List<String> = emptyList(),
    val ownerComposable: String? = null,
)

internal object HandoffEvaluationFixtures {
    private val json: Json = fixThisJson

    fun loadCorpus(root: File = File("")): HandoffEvaluationCorpus {
        val file = File(root.absoluteFile, "fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json")
        return json.decodeFromString(HandoffEvaluationCorpus.serializer(), file.readText())
    }

    fun annotationFor(case: HandoffEvaluationCase): AnnotationDto {
        val bounds = FixThisRect(10f, 20f, 210f, 120f)
        val node = if (case.targetType == "node") {
            FixThisNode(
                uid = "${case.id}-node",
                composeNodeId = case.id.hashCode(),
                rootIndex = 0,
                treeKind = TreeKind.MERGED,
                boundsInWindow = bounds,
                text = case.selectedText,
                role = case.selectedRole,
                testTag = case.selectedTestTag,
                path = listOf("root", case.id),
            )
        } else {
            null
        }
        val target = if (case.targetType == "area") {
            AnnotationTargetDto.Area(bounds)
        } else {
            AnnotationTargetDto.Node(requireNotNull(node).uid, bounds)
        }
        val sourceCandidates = case.sourceCandidates.map { it.toSourceCandidate() }
        val item = AnnotationDto(
            itemId = "item-${case.id}",
            screenId = "screen-${case.id}",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = target,
            selectedNode = node,
            sourceCandidates = sourceCandidates,
            comment = case.comment,
            targetReliability = reliabilityFor(case),
        )
        return item.copy(editSurfaceCandidates = EditSurfaceCandidateService.build(item, screenFor(case, node)))
    }

    fun screenFor(case: HandoffEvaluationCase, node: FixThisNode? = null): SnapshotDto = SnapshotDto(
        screenId = "screen-${case.id}",
        capturedAtEpochMillis = 1L,
        displayName = "Eval",
        roots = listOf(
            SnapshotRootDto(
                rootIndex = 0,
                boundsInWindow = FixThisRect(0f, 0f, 400f, 800f),
                mergedNodes = listOfNotNull(node),
            ),
        ),
    )

    private fun HandoffEvaluationSourceCandidate.toSourceCandidate(): SourceCandidate = SourceCandidate(
        file = file,
        line = line,
        score = score,
        confidence = SelectionConfidence.valueOf(confidence),
        scoreMargin = scoreMargin,
        matchReasons = matchReasons,
        matchedTerms = matchedTerms,
        ownerComposable = ownerComposable,
    )

    private fun reliabilityFor(case: HandoffEvaluationCase): TargetReliability? {
        if (case.targetWarnings.isEmpty()) return null
        return TargetReliability(
            confidence = TargetConfidence.LOW,
            reasons = listOf(TargetReliabilityReason.VISUAL_AREA_SELECTION),
            warnings = case.targetWarnings.map { TargetReliabilityWarning.valueOf(it) },
        )
    }
}
