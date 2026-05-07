package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.identity.TestTagConvention
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate

class SourceMatcher(private val sourceIndex: SourceIndex) {
    fun match(
        selectedNode: FixThisNode?,
        nearbyNodes: List<FixThisNode>,
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
            selectedNode: FixThisNode?,
            nearbyNodes: List<FixThisNode>,
            activityName: String?
        ): List<SourceCandidate> =
            SourceMatcher(sourceIndex).match(selectedNode, nearbyNodes, activityName)
    }

    private fun score(
        entry: SourceIndexEntry,
        selectedNode: FixThisNode,
        nearbyNodes: List<FixThisNode>,
        activityName: String?
    ): MatchScore {
        val matchedTerms = linkedSetOf<String>()
        val matchReasons = linkedSetOf<String>()
        val scoredEvidence = mutableSetOf<String>()
        var rawScore = 0.0

        selectedNode.text.forEach { term ->
            rawScore += addIfMatches(entry.textLikeWeight(term), term, "selected text", 45.0, matchedTerms, matchReasons, scoredEvidence)
        }
        selectedNode.editableText?.let { term ->
            rawScore += addIfMatches(entry.textLikeWeight(term), term, "selected text", 45.0, matchedTerms, matchReasons, scoredEvidence)
        }
        selectedNode.contentDescription.forEach { term ->
            rawScore += addIfMatches(
                entry.contentDescriptionWeight(term),
                term,
                "selected contentDescription",
                40.0,
                matchedTerms,
                matchReasons,
                scoredEvidence
            )
        }
        selectedNode.testTag?.let { term ->
            rawScore += addSelectedTestTagScore(entry, term, matchedTerms, matchReasons, scoredEvidence)
        }
        selectedNode.role?.let { term ->
            rawScore += addIfMatches(entry.roleWeight(term), term, "selected role", 25.0, matchedTerms, matchReasons, scoredEvidence)
        }

        nearbyNodes.forEach { node ->
            node.text.forEach { term ->
                rawScore += addIfMatches(entry.textLikeWeight(term), term, "nearby text", 24.0, matchedTerms, matchReasons, scoredEvidence)
            }
            node.editableText?.let { term ->
                rawScore += addIfMatches(entry.textLikeWeight(term), term, "nearby text", 24.0, matchedTerms, matchReasons, scoredEvidence)
            }
            node.contentDescription.forEach { term ->
                rawScore += addIfMatches(
                    entry.contentDescriptionWeight(term),
                    term,
                    "nearby contentDescription",
                    22.0,
                    matchedTerms,
                    matchReasons,
                    scoredEvidence
                )
            }
            node.testTag?.let { term ->
                rawScore += addIfMatches(entry.testTagWeight(term), term, "nearby testTag", 18.0, matchedTerms, matchReasons, scoredEvidence)
            }
            node.role?.let { term ->
                rawScore += addIfMatches(entry.roleWeight(term), term, "nearby role", 8.0, matchedTerms, matchReasons, scoredEvidence)
            }
        }

        activityName?.takeUnless { it.isBlank() }?.let { name ->
            rawScore += addIfMatches(
                entry.activityWeight(name),
                name.substringAfterLast('.'),
                "activity",
                15.0,
                matchedTerms,
                matchReasons,
                scoredEvidence
            )
        }

        return MatchScore(
            entry = entry,
            rawScore = rawScore,
            matchedTerms = matchedTerms.toList(),
            matchReasons = matchReasons.toList()
        )
    }

    private fun addSelectedTestTagScore(
        entry: SourceIndexEntry,
        testTag: String,
        matchedTerms: MutableSet<String>,
        matchReasons: MutableSet<String>,
        scoredEvidence: MutableSet<String>
    ): Double {
        var score = addIfMatches(
            matchWeight = entry.testTagWeight(testTag),
            term = testTag,
            reason = "selected testTag",
            score = 55.0,
            matchedTerms = matchedTerms,
            matchReasons = matchReasons,
            scoredEvidence = scoredEvidence
        )

        TestTagConvention.parse(testTag)?.let { parsed ->
            val conventionScore = addIfMatches(
                matchWeight = entry.conventionComposableWeight(parsed.composableName),
                term = parsed.composableName,
                reason = "selected testTag convention composable",
                score = 65.0,
                matchedTerms = matchedTerms,
                matchReasons = matchReasons,
                scoredEvidence = scoredEvidence
            )
            score = maxOf(score, conventionScore)
        }

        return score
    }

    private fun addIfMatches(
        matchWeight: Double,
        term: String,
        reason: String,
        score: Double,
        matchedTerms: MutableSet<String>,
        matchReasons: MutableSet<String>,
        scoredEvidence: MutableSet<String>
    ): Double {
        val cleaned = term.trim()
        if (matchWeight <= 0.0 || cleaned.isEmpty()) return 0.0
        matchedTerms.add(cleaned)
        matchReasons.add(reason)
        if (!scoredEvidence.add("$reason\u001f${cleaned.normalizedForMatch()}")) return 0.0
        return score * matchWeight
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

    private fun SourceIndexEntry.textLikeWeight(term: String): Double =
        signalOrLegacyWeight(
            term = term,
            kinds = setOf(
                SourceSignalKind.UI_TEXT,
                SourceSignalKind.STRING_RESOURCE,
                SourceSignalKind.ARBITRARY_STRING_LITERAL
            ),
            legacyCandidates = text + stringResources + symbols + listOfNotNull(excerpt)
        )

    private fun SourceIndexEntry.contentDescriptionWeight(term: String): Double =
        signalOrLegacyWeight(
            term = term,
            kinds = setOf(
                SourceSignalKind.CONTENT_DESCRIPTION,
                SourceSignalKind.STRING_RESOURCE,
                SourceSignalKind.ARBITRARY_STRING_LITERAL
            ),
            legacyCandidates = contentDescriptions + stringResources + symbols + listOfNotNull(excerpt)
        )

    private fun SourceIndexEntry.testTagWeight(term: String): Double =
        signalOrLegacyWeight(
            term = term,
            kinds = setOf(SourceSignalKind.TEST_TAG, SourceSignalKind.STRICT_COMP_TEST_TAG),
            legacyCandidates = testTags + symbols + listOfNotNull(excerpt)
        )

    private fun SourceIndexEntry.conventionComposableWeight(composableName: String): Double =
        signalOrLegacyWeight(
            term = composableName,
            kinds = setOf(SourceSignalKind.COMPOSABLE_SYMBOL, SourceSignalKind.STRICT_COMP_TEST_TAG),
            legacyCandidates = symbols + listOf(file) + listOfNotNull(excerpt)
        )

    private fun SourceIndexEntry.roleWeight(term: String): Double =
        signalOrLegacyWeight(
            term = term,
            kinds = setOf(SourceSignalKind.ROLE),
            legacyCandidates = roles + symbols + listOfNotNull(excerpt)
        )

    private fun SourceIndexEntry.activityWeight(activityName: String): Double {
        val simpleName = activityName.substringAfterLast('.')
        val signalMatchWeight = maxOf(
            signalWeight(activityName, setOf(SourceSignalKind.ACTIVITY_NAME)),
            signalWeight(simpleName, setOf(SourceSignalKind.ACTIVITY_NAME))
        )
        if (signalMatchWeight > 0.0) return signalMatchWeight

        val activityTerms = activityNames + listOf(file)
        return legacyWeight(matchesAny(activityName, activityTerms) || matchesAny(simpleName, activityTerms))
    }

    private fun SourceIndexEntry.signalOrLegacyWeight(
        term: String,
        kinds: Set<SourceSignalKind>,
        legacyCandidates: List<String>
    ): Double {
        val signalMatchWeight = signalWeight(term, kinds)
        return if (signalMatchWeight > 0.0) {
            signalMatchWeight
        } else {
            legacyWeight(matchesAny(term, legacyCandidates))
        }
    }

    private fun SourceIndexEntry.signalWeight(term: String, kinds: Set<SourceSignalKind>): Double =
        signals.asSequence()
            .filter { it.kind in kinds }
            .filter { matchesAny(term, listOf(it.value)) }
            .map { signal -> signal.kind.baseMatchWeight * signal.confidenceWeight.coerceAtLeast(0.0) }
            .maxOrNull()
            ?: 0.0

    private fun legacyWeight(matches: Boolean): Double = if (matches) 1.0 else 0.0

    private val SourceSignalKind.baseMatchWeight: Double
        get() = when (this) {
            SourceSignalKind.STRICT_COMP_TEST_TAG -> 1.15
            SourceSignalKind.UI_TEXT,
            SourceSignalKind.TEST_TAG,
            SourceSignalKind.CONTENT_DESCRIPTION,
            SourceSignalKind.COMPOSABLE_SYMBOL -> 1.0
            SourceSignalKind.STRING_RESOURCE,
            SourceSignalKind.ROLE,
            SourceSignalKind.ACTIVITY_NAME -> 0.85
            SourceSignalKind.ARBITRARY_STRING_LITERAL -> 0.35
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
