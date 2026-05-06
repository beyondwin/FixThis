package io.github.pointpatch.compose.core.source

import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.SelectionConfidence
import io.github.pointpatch.compose.core.model.SourceCandidate

class SourceMatcher(private val sourceIndex: SourceIndex) {
    fun match(
        selectedNode: PointPatchNode?,
        nearbyNodes: List<PointPatchNode>,
        activityName: String?
    ): List<SourceCandidate> {
        if (selectedNode == null || sourceIndex.entries.isEmpty()) return emptyList()

        return sourceIndex.entries.asSequence()
            .map { entry -> score(entry, selectedNode, nearbyNodes, activityName) }
            .filter { it.rawScore > 0.0 }
            .sortedWith(
                compareByDescending<MatchScore> { it.rawScore }
                    .thenBy { it.entry.file }
                    .thenBy { it.entry.line ?: Int.MAX_VALUE }
            )
            .take(MAX_CANDIDATES)
            .map { it.toCandidate() }
            .toList()
    }

    companion object {
        fun match(
            sourceIndex: SourceIndex,
            selectedNode: PointPatchNode?,
            nearbyNodes: List<PointPatchNode>,
            activityName: String?
        ): List<SourceCandidate> =
            SourceMatcher(sourceIndex).match(selectedNode, nearbyNodes, activityName)
    }

    private fun score(
        entry: SourceIndexEntry,
        selectedNode: PointPatchNode,
        nearbyNodes: List<PointPatchNode>,
        activityName: String?
    ): MatchScore {
        val matchedTerms = linkedSetOf<String>()
        val matchReasons = linkedSetOf<String>()
        val scoredEvidence = mutableSetOf<String>()
        var rawScore = 0.0

        selectedNode.text.forEach { term ->
            rawScore += addIfMatches(entry.matchesTextLike(term), term, "selected text", 45.0, matchedTerms, matchReasons, scoredEvidence)
        }
        selectedNode.editableText?.let { term ->
            rawScore += addIfMatches(entry.matchesTextLike(term), term, "selected text", 45.0, matchedTerms, matchReasons, scoredEvidence)
        }
        selectedNode.contentDescription.forEach { term ->
            rawScore += addIfMatches(entry.matchesContentDescription(term), term, "selected contentDescription", 40.0, matchedTerms, matchReasons, scoredEvidence)
        }
        selectedNode.testTag?.let { term ->
            rawScore += addIfMatches(entry.matchesTestTag(term), term, "selected testTag", 55.0, matchedTerms, matchReasons, scoredEvidence)
        }
        selectedNode.role?.let { term ->
            rawScore += addIfMatches(entry.matchesRole(term), term, "selected role", 25.0, matchedTerms, matchReasons, scoredEvidence)
        }

        nearbyNodes.forEach { node ->
            node.text.forEach { term ->
                rawScore += addIfMatches(entry.matchesTextLike(term), term, "nearby text", 24.0, matchedTerms, matchReasons, scoredEvidence)
            }
            node.editableText?.let { term ->
                rawScore += addIfMatches(entry.matchesTextLike(term), term, "nearby text", 24.0, matchedTerms, matchReasons, scoredEvidence)
            }
            node.contentDescription.forEach { term ->
                rawScore += addIfMatches(entry.matchesContentDescription(term), term, "nearby contentDescription", 22.0, matchedTerms, matchReasons, scoredEvidence)
            }
            node.testTag?.let { term ->
                rawScore += addIfMatches(entry.matchesTestTag(term), term, "nearby testTag", 18.0, matchedTerms, matchReasons, scoredEvidence)
            }
            node.role?.let { term ->
                rawScore += addIfMatches(entry.matchesRole(term), term, "nearby role", 8.0, matchedTerms, matchReasons, scoredEvidence)
            }
        }

        activityName?.takeUnless { it.isBlank() }?.let { name ->
            rawScore += addIfMatches(entry.matchesActivity(name), name.substringAfterLast('.'), "activity", 15.0, matchedTerms, matchReasons, scoredEvidence)
        }

        return MatchScore(
            entry = entry,
            rawScore = rawScore,
            matchedTerms = matchedTerms.toList(),
            matchReasons = matchReasons.toList()
        )
    }

    private fun addIfMatches(
        matches: Boolean,
        term: String,
        reason: String,
        score: Double,
        matchedTerms: MutableSet<String>,
        matchReasons: MutableSet<String>,
        scoredEvidence: MutableSet<String>
    ): Double {
        val cleaned = term.trim()
        if (!matches || cleaned.isEmpty()) return 0.0
        matchedTerms.add(cleaned)
        matchReasons.add(reason)
        if (!scoredEvidence.add("$reason\u001f${cleaned.normalizedForMatch()}")) return 0.0
        return score
    }

    private fun MatchScore.toCandidate(): SourceCandidate =
        SourceCandidate(
            file = entry.file,
            line = entry.line,
            score = (rawScore / HIGH_CONFIDENCE_SCORE).coerceIn(0.0, 1.0),
            matchedTerms = matchedTerms,
            matchReasons = matchReasons,
            confidence = when {
                rawScore >= HIGH_CONFIDENCE_SCORE -> SelectionConfidence.HIGH
                rawScore >= MEDIUM_CONFIDENCE_SCORE -> SelectionConfidence.MEDIUM
                rawScore > 0.0 -> SelectionConfidence.LOW
                else -> SelectionConfidence.NONE
            }
        )

    private fun SourceIndexEntry.matchesTextLike(term: String): Boolean =
        matchesAny(term, text + stringResources + symbols + listOfNotNull(excerpt))

    private fun SourceIndexEntry.matchesContentDescription(term: String): Boolean =
        matchesAny(term, contentDescriptions + stringResources + symbols + listOfNotNull(excerpt))

    private fun SourceIndexEntry.matchesTestTag(term: String): Boolean =
        matchesAny(term, testTags + symbols + listOfNotNull(excerpt))

    private fun SourceIndexEntry.matchesRole(term: String): Boolean =
        matchesAny(term, roles + symbols + listOfNotNull(excerpt))

    private fun SourceIndexEntry.matchesActivity(activityName: String): Boolean {
        val activityTerms = activityNames + listOf(file)
        val simpleName = activityName.substringAfterLast('.')
        return matchesAny(activityName, activityTerms) || matchesAny(simpleName, activityTerms)
    }

    private fun matchesAny(term: String, candidates: List<String>): Boolean {
        val normalizedTerm = term.normalizedForMatch()
        if (normalizedTerm.isEmpty()) return false
        return candidates.any { candidate ->
            val normalizedCandidate = candidate.normalizedForMatch()
            normalizedCandidate == normalizedTerm ||
                (normalizedTerm.length >= MIN_PARTIAL_MATCH_LENGTH && normalizedCandidate.contains(normalizedTerm))
        }
    }

    private fun String.normalizedForMatch(): String =
        trim().lowercase().replace(Regex("\\s+"), " ")

    private data class MatchScore(
        val entry: SourceIndexEntry,
        val rawScore: Double,
        val matchedTerms: List<String>,
        val matchReasons: List<String>
    )
}

private const val MAX_CANDIDATES = 5
private const val HIGH_CONFIDENCE_SCORE = 100.0
private const val MEDIUM_CONFIDENCE_SCORE = 55.0
private const val MIN_PARTIAL_MATCH_LENGTH = 3
