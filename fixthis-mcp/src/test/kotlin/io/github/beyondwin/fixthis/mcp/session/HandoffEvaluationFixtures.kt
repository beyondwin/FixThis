@file:Suppress("LongParameterList")

package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.compose.core.identity.TestTagConventionSet
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceRoleDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotRootDto
import io.github.beyondwin.fixthis.mcp.session.editsurface.EditSurfaceCandidateService
import io.github.beyondwin.fixthis.mcp.session.handoff.CompactHandoffRenderer
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
    val testTagConventions: List<String> = emptyList(),
    val targetWarnings: List<String> = emptyList(),
    val expectedBoundaryToken: String? = null,
    val nearbyNodes: List<HandoffEvaluationNode> = emptyList(),
    val sourceCandidates: List<HandoffEvaluationSourceCandidate> = emptyList(),
    val expectedRole: EditSurfaceRoleDto,
    val expectedTop3Contains: String? = null,
    val allowHighConfidence: Boolean,
    val correctness: HandoffCorrectnessExpectation,
)

@Serializable
internal data class HandoffCorrectnessExpectation(
    val category: String,
    val ownerContains: String,
    val expectedRole: EditSurfaceRoleDto,
    val maxConfidence: String,
    val requiredCautions: List<String> = emptyList(),
    val releaseCritical: Boolean,
    val promptUsabilityRequired: Boolean,
)

internal data class HandoffCorrectnessResult(
    val score: Int,
    val dimensions: List<HandoffCorrectnessDimension>,
    val hardFailures: List<HandoffCorrectnessFailure>,
)

internal data class HandoffCorrectnessDimension(
    val name: String,
    val passed: Boolean,
    val message: String,
)

internal data class HandoffCorrectnessFailure(
    val id: String,
    val message: String,
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
    val evidenceStrength: String? = null,
    val riskFlags: List<String> = emptyList(),
)

@Serializable
internal data class HandoffEvaluationNode(
    val uid: String,
    val text: List<String> = emptyList(),
    val role: String? = null,
    val testTag: String? = null,
)

internal object HandoffEvaluationFixtures {
    private val json: Json = fixThisJson

    fun loadCorpus(root: File = File("")): HandoffEvaluationCorpus {
        val text = javaClass.classLoader
            ?.getResourceAsStream("handoff-eval/v06-corpus.json")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: run {
                val file = File(root.absoluteFile, "fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json")
                file.readText()
            }
        return json.decodeFromString(HandoffEvaluationCorpus.serializer(), text)
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
        val nearbyNodes = case.nearbyNodes.map { it.toNode(case.id) }
        val sourceCandidates = case.sourceCandidates.map { it.toSourceCandidate() }
        val item = AnnotationDto(
            itemId = "item-${case.id}",
            screenId = "screen-${case.id}",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = target,
            selectedNode = node,
            nearbyNodes = nearbyNodes,
            sourceCandidates = sourceCandidates,
            comment = case.comment,
            targetReliability = reliabilityFor(case),
        )
        return item.copy(
            editSurfaceCandidates = EditSurfaceCandidateService.build(
                item,
                screenFor(case, node, nearbyNodes),
                conventions = TestTagConventionSet.fromPatternStrings(case.testTagConventions),
            ),
        )
    }

    fun screenFor(
        case: HandoffEvaluationCase,
        node: FixThisNode? = null,
        nearbyNodes: List<FixThisNode> = emptyList(),
    ): SnapshotDto = SnapshotDto(
        screenId = "screen-${case.id}",
        capturedAtEpochMillis = 1L,
        displayName = "Eval",
        roots = listOf(
            SnapshotRootDto(
                rootIndex = 0,
                boundsInWindow = FixThisRect(0f, 0f, 400f, 800f),
                mergedNodes = listOfNotNull(node) + nearbyNodes,
            ),
        ),
    )

    fun evaluateCorrectness(case: HandoffEvaluationCase): HandoffCorrectnessResult {
        val item = annotationFor(case)
        val session = SessionDto(
            sessionId = "handoff-eval-${case.id}",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(screenFor(case)),
            items = listOf(item.copy(sequenceNumber = 1)),
        )
        val compact = CompactHandoffRenderer.render(session)
        val dimensions = correctnessDimensions(case, item, compact)
        val hardFailures = dimensions
            .filter { !it.passed && case.correctness.releaseCritical }
            .map { dimension ->
                HandoffCorrectnessFailure(
                    id = when (dimension.name) {
                        "confidence" -> "overconfident-evidence"
                        "caution" -> "missing-required-caution"
                        "owner" -> "missing-expected-owner"
                        "role" -> "wrong-edit-surface-role"
                        else -> "missing-prompt-usability"
                    },
                    message = "${case.id}: ${dimension.message}",
                )
            }
        val score = (dimensions.count { it.passed } * 100) / dimensions.size
        return HandoffCorrectnessResult(score, dimensions, hardFailures)
    }

    private fun correctnessDimensions(
        case: HandoffEvaluationCase,
        item: AnnotationDto,
        compact: String,
    ): List<HandoffCorrectnessDimension> {
        val topRole = item.editSurfaceCandidates.firstOrNull()?.role
        val topConfidence = item.editSurfaceCandidates.firstOrNull()?.confidence
        val candidateFiles = item.editSurfaceCandidates.map { it.file } + item.sourceCandidates.map { it.file }
        val maxAllowed = SelectionConfidence.valueOf(case.correctness.maxConfidence)
        return listOf(
            HandoffCorrectnessDimension(
                name = "owner",
                passed = candidateFiles.any { it.contains(case.correctness.ownerContains) } ||
                    compact.contains(case.correctness.ownerContains) ||
                    case.correctness.ownerContains == "visual-area",
                message = "expected owner containing ${case.correctness.ownerContains}",
            ),
            HandoffCorrectnessDimension(
                name = "role",
                passed = topRole == case.correctness.expectedRole,
                message = "expected role ${case.correctness.expectedRole}, got $topRole",
            ),
            HandoffCorrectnessDimension(
                name = "confidence",
                passed = topConfidence == null || confidenceRank(topConfidence) <= confidenceRank(maxAllowed),
                message = "expected confidence <= $maxAllowed, got $topConfidence",
            ),
            HandoffCorrectnessDimension(
                name = "caution",
                passed = case.correctness.requiredCautions.all { compact.contains(it) },
                message = "expected caution tokens ${case.correctness.requiredCautions}",
            ),
            HandoffCorrectnessDimension(
                name = "prompt-usability",
                passed = !case.correctness.promptUsabilityRequired ||
                    (
                        compact.contains("session_id:") &&
                            compact.contains("id: item-${case.id}") &&
                            compact.contains("agent_protocol:")
                        ),
                message = "expected compact handoff protocol fields",
            ),
        )
    }

    private fun confidenceRank(confidence: SelectionConfidence): Int = when (confidence) {
        SelectionConfidence.NONE -> 0
        SelectionConfidence.LOW -> 1
        SelectionConfidence.MEDIUM -> 2
        SelectionConfidence.HIGH -> 3
    }

    private fun HandoffEvaluationSourceCandidate.toSourceCandidate(): SourceCandidate = SourceCandidate(
        file = file,
        line = line,
        score = score,
        confidence = SelectionConfidence.valueOf(confidence),
        scoreMargin = scoreMargin,
        matchReasons = matchReasons,
        matchedTerms = matchedTerms,
        ownerComposable = ownerComposable,
        evidenceStrength = evidenceStrength?.let { SourceEvidenceStrength.valueOf(it) },
        riskFlags = riskFlags.map { SourceCandidateRisk.valueOf(it) },
    )

    private fun HandoffEvaluationNode.toNode(caseId: String): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = "$caseId-$uid".hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 80f, 320f, 260f),
        text = text,
        role = role,
        testTag = testTag,
        path = listOf("root", uid),
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
