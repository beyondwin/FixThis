package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.identity.TestTagConvention
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk

class SourceMatcher(private val sourceIndex: SourceIndex) {
    fun match(
        selectedNode: FixThisNode?,
        nearbyNodes: List<FixThisNode>,
        activityName: String?
    ): List<SourceCandidate> {
        if (selectedNode == null || sourceIndex.entries.isEmpty()) return emptyList()

        val matchScores = sourceIndex.entries.asSequence()
            .map { entry -> score(entry, selectedNode, nearbyNodes, activityName) }
            .filter { it.rawScore > 0.0 }
            .sortedWith(
                compareByDescending<MatchScore> { it.rawScore }
                    .thenBy { it.entry.file }
                    .thenBy { it.entry.line ?: Int.MAX_VALUE }
            )
            .take(MAX_CANDIDATES)
            .toList()

        val normalizedScores = matchScores.map {
            (it.rawScore / HIGH_CONFIDENCE_SCORE).coerceIn(0.0, 1.0)
        }
        return matchScores.mapIndexed { index, score -> score.toCandidate(index, normalizedScores) }
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

    // Tracks which origin kinds fired during scoring for a candidate, used to
    // determine "arbitrary literal" and "legacy fallback" reason emissions.
    // Only selected (non-nearby, non-activity) text/contentDescription/role terms are
    // tracked for the legacy-fallback emission; activity uses its own cap (ACTIVITY_ONLY).
    private class ScoreContext {
        var anyTypedSignalNonLiteral: Boolean = false
        var anyArbitraryLiteralSignal: Boolean = false
        var anyLegacyOnly: Boolean = false
        var anyTermMatched: Boolean = false
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
        val ctx = ScoreContext()
        var rawScore = 0.0

        selectedNode.text.forEach { term ->
            rawScore += addIfMatches(
                hit = entry.textLikeWeightHit(term),
                term = term,
                reason = "selected text",
                score = 45.0,
                matchedTerms = matchedTerms,
                matchReasons = matchReasons,
                scoredEvidence = scoredEvidence,
                ctx = ctx,
            )
        }
        selectedNode.editableText?.let { term ->
            rawScore += addIfMatches(
                hit = entry.textLikeWeightHit(term),
                term = term,
                reason = "selected text",
                score = 45.0,
                matchedTerms = matchedTerms,
                matchReasons = matchReasons,
                scoredEvidence = scoredEvidence,
                ctx = ctx,
            )
        }
        selectedNode.contentDescription.forEach { term ->
            rawScore += addIfMatches(
                hit = entry.contentDescriptionWeightHit(term),
                term = term,
                reason = "selected contentDescription",
                score = 40.0,
                matchedTerms = matchedTerms,
                matchReasons = matchReasons,
                scoredEvidence = scoredEvidence,
                ctx = ctx,
            )
        }
        selectedNode.testTag?.let { term ->
            rawScore += addSelectedTestTagScore(entry, term, matchedTerms, matchReasons, scoredEvidence, ctx)
        }
        selectedNode.role?.let { term ->
            rawScore += addIfMatches(
                hit = entry.roleWeightHit(term),
                term = term,
                reason = "selected role",
                score = 25.0,
                matchedTerms = matchedTerms,
                matchReasons = matchReasons,
                scoredEvidence = scoredEvidence,
                ctx = ctx,
            )
        }

        nearbyNodes.forEach { node ->
            node.text.forEach { term ->
                rawScore += addIfMatches(
                    hit = entry.textLikeWeightHit(term),
                    term = term,
                    reason = "nearby text",
                    score = 24.0,
                    matchedTerms = matchedTerms,
                    matchReasons = matchReasons,
                    scoredEvidence = scoredEvidence,
                    ctx = ctx,
                    isNearby = true,
                )
            }
            node.editableText?.let { term ->
                rawScore += addIfMatches(
                    hit = entry.textLikeWeightHit(term),
                    term = term,
                    reason = "nearby text",
                    score = 24.0,
                    matchedTerms = matchedTerms,
                    matchReasons = matchReasons,
                    scoredEvidence = scoredEvidence,
                    ctx = ctx,
                    isNearby = true,
                )
            }
            node.contentDescription.forEach { term ->
                rawScore += addIfMatches(
                    hit = entry.contentDescriptionWeightHit(term),
                    term = term,
                    reason = "nearby contentDescription",
                    score = 22.0,
                    matchedTerms = matchedTerms,
                    matchReasons = matchReasons,
                    scoredEvidence = scoredEvidence,
                    ctx = ctx,
                    isNearby = true,
                )
            }
            node.testTag?.let { term ->
                rawScore += addIfMatches(
                    hit = entry.testTagWeightHit(term),
                    term = term,
                    reason = "nearby testTag",
                    score = 18.0,
                    matchedTerms = matchedTerms,
                    matchReasons = matchReasons,
                    scoredEvidence = scoredEvidence,
                    ctx = ctx,
                    isNearby = true,
                )
            }
            node.role?.let { term ->
                rawScore += addIfMatches(
                    hit = entry.roleWeightHit(term),
                    term = term,
                    reason = "nearby role",
                    score = 8.0,
                    matchedTerms = matchedTerms,
                    matchReasons = matchReasons,
                    scoredEvidence = scoredEvidence,
                    ctx = ctx,
                    isNearby = true,
                )
            }
        }

        activityName?.takeUnless { it.isBlank() }?.let { name ->
            rawScore += addIfMatches(
                hit = entry.activityWeightHit(name),
                term = name.substringAfterLast('.'),
                reason = "activity",
                score = 15.0,
                matchedTerms = matchedTerms,
                matchReasons = matchReasons,
                scoredEvidence = scoredEvidence,
                ctx = ctx,
            )
        }

        // Post-processing: emit "arbitrary literal" or "legacy fallback" origin markers
        if (ctx.anyTermMatched) {
            if (!ctx.anyTypedSignalNonLiteral && !ctx.anyLegacyOnly && ctx.anyArbitraryLiteralSignal) {
                matchReasons.add("arbitrary literal")
            }
            if (!ctx.anyTypedSignalNonLiteral && !ctx.anyArbitraryLiteralSignal && ctx.anyLegacyOnly) {
                matchReasons.add("legacy fallback")
            }
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
        scoredEvidence: MutableSet<String>,
        ctx: ScoreContext,
    ): Double {
        var score = addIfMatches(
            hit = entry.testTagWeightHit(testTag),
            term = testTag,
            reason = "selected testTag",
            score = 55.0,
            matchedTerms = matchedTerms,
            matchReasons = matchReasons,
            scoredEvidence = scoredEvidence,
            ctx = ctx,
        )

        TestTagConvention.parse(testTag)?.let { parsed ->
            val conventionScore = addIfMatches(
                hit = entry.conventionComposableWeightHit(parsed.composableName),
                term = parsed.composableName,
                reason = "selected testTag convention composable",
                score = 65.0,
                matchedTerms = matchedTerms,
                matchReasons = matchReasons,
                scoredEvidence = scoredEvidence,
                ctx = ctx,
            )
            score = maxOf(score, conventionScore)
        }

        return score
    }

    private fun addIfMatches(
        hit: WeightHit,
        term: String,
        reason: String,
        score: Double,
        matchedTerms: MutableSet<String>,
        matchReasons: MutableSet<String>,
        scoredEvidence: MutableSet<String>,
        ctx: ScoreContext,
        isNearby: Boolean = false,
    ): Double {
        val cleaned = term.trim()
        if (hit.weight <= 0.0 || cleaned.isEmpty()) return 0.0
        matchedTerms.add(cleaned)
        matchReasons.add(reason)

        // Track origin for "arbitrary literal" / "legacy fallback" reason emission.
        // Only applies to selected (non-nearby) text/contentDescription/role bucket reasons.
        // Activity-only matches are handled separately by the ACTIVITY_ONLY cap and
        // are NOT tracked here so they don't pollute the legacy-fallback marker.
        val isSelectedBucket = !isNearby && reason != "activity"
        if (isSelectedBucket) {
            ctx.anyTermMatched = true
            when {
                hit.signalKind == SourceSignalKind.ARBITRARY_STRING_LITERAL ->
                    ctx.anyArbitraryLiteralSignal = true
                hit.viaLegacy ->
                    ctx.anyLegacyOnly = true
                hit.signalKind != null ->
                    ctx.anyTypedSignalNonLiteral = true
            }
        }

        // Emit "selected stringResource" reason when a STRING_RESOURCE signal matched
        // a text or contentDescription bucket
        if (hit.signalKind == SourceSignalKind.STRING_RESOURCE &&
            (reason == "selected text" || reason == "selected contentDescription")
        ) {
            matchReasons.add("selected stringResource")
        }

        if (!scoredEvidence.add("$reason${cleaned.normalizedForMatch()}")) return 0.0
        return score * hit.weight
    }

    // Weight-hit carrier: weight from the winning signal, its kind (null = legacy), and whether it came from legacy path
    private data class WeightHit(
        val weight: Double,
        val signalKind: SourceSignalKind?,
        val viaLegacy: Boolean,
    )

    private fun SourceIndexEntry.textLikeWeightHit(term: String): WeightHit =
        signalOrLegacyWeightHit(
            term = term,
            kinds = setOf(
                SourceSignalKind.UI_TEXT,
                SourceSignalKind.STRING_RESOURCE,
                SourceSignalKind.ARBITRARY_STRING_LITERAL
            ),
            legacyCandidates = text + stringResources + symbols + listOfNotNull(excerpt)
        )

    private fun SourceIndexEntry.contentDescriptionWeightHit(term: String): WeightHit =
        signalOrLegacyWeightHit(
            term = term,
            kinds = setOf(
                SourceSignalKind.CONTENT_DESCRIPTION,
                SourceSignalKind.STRING_RESOURCE,
                SourceSignalKind.ARBITRARY_STRING_LITERAL
            ),
            legacyCandidates = contentDescriptions + stringResources + symbols + listOfNotNull(excerpt)
        )

    private fun SourceIndexEntry.testTagWeightHit(term: String): WeightHit =
        signalOrLegacyWeightHit(
            term = term,
            kinds = setOf(SourceSignalKind.TEST_TAG, SourceSignalKind.STRICT_COMP_TEST_TAG),
            legacyCandidates = testTags + symbols + listOfNotNull(excerpt)
        )

    private fun SourceIndexEntry.conventionComposableWeightHit(composableName: String): WeightHit =
        signalOrLegacyWeightHit(
            term = composableName,
            kinds = setOf(SourceSignalKind.COMPOSABLE_SYMBOL, SourceSignalKind.STRICT_COMP_TEST_TAG),
            legacyCandidates = symbols + listOf(file) + listOfNotNull(excerpt)
        )

    private fun SourceIndexEntry.roleWeightHit(term: String): WeightHit =
        signalOrLegacyWeightHit(
            term = term,
            kinds = setOf(SourceSignalKind.ROLE),
            legacyCandidates = roles + symbols + listOfNotNull(excerpt)
        )

    private fun SourceIndexEntry.activityWeightHit(activityName: String): WeightHit {
        val simpleName = activityName.substringAfterLast('.')
        val (signalMatchWeight, signalKind) = bestSignalHit(
            listOf(activityName, simpleName),
            setOf(SourceSignalKind.ACTIVITY_NAME)
        )
        if (signalMatchWeight > 0.0) return WeightHit(signalMatchWeight, signalKind, viaLegacy = false)

        val activityTerms = activityNames + listOf(file)
        val legacyMatches = matchesAny(activityName, activityTerms) || matchesAny(simpleName, activityTerms)
        return WeightHit(legacyWeight(legacyMatches), signalKind = null, viaLegacy = legacyMatches)
    }

    private fun SourceIndexEntry.signalOrLegacyWeightHit(
        term: String,
        kinds: Set<SourceSignalKind>,
        legacyCandidates: List<String>
    ): WeightHit {
        val (signalMatchWeight, signalKind) = bestSignalHit(listOf(term), kinds)
        return if (signalMatchWeight > 0.0) {
            WeightHit(signalMatchWeight, signalKind, viaLegacy = false)
        } else {
            val legacyMatches = matchesAny(term, legacyCandidates)
            WeightHit(legacyWeight(legacyMatches), signalKind = null, viaLegacy = legacyMatches)
        }
    }

    // Returns the best (weight, kind) pair from matching any term against signals of the given kinds.
    private fun SourceIndexEntry.bestSignalHit(
        terms: List<String>,
        kinds: Set<SourceSignalKind>,
    ): Pair<Double, SourceSignalKind?> {
        var bestWeight = 0.0
        var bestKind: SourceSignalKind? = null
        for (signal in signals) {
            if (signal.kind !in kinds) continue
            for (term in terms) {
                if (matchesAny(term, listOf(signal.value))) {
                    val w = signal.kind.baseMatchWeight * signal.confidenceWeight.coerceAtLeast(0.0)
                    if (w > bestWeight) {
                        bestWeight = w
                        bestKind = signal.kind
                    }
                }
            }
        }
        return bestWeight to bestKind
    }

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

    private fun MatchScore.toCandidate(
        index: Int,
        normalizedScores: List<Double>,
    ): SourceCandidate {
        val profile = EvidenceProfile.fromReasons(matchReasons, rawScore)
        val margin = MarginContext.of(normalizedScores, index)
        val baseConfidence = baseConfidenceFor(profile, margin)
        val capInfo = applyCaps(profile, baseConfidence)
        val (afterAmbiguity, ambiguousFlag) = applyAmbiguityDowngrade(
            confidence = capInfo.confidence,
            margin = margin,
            totalCandidates = normalizedScores.size,
        )
        val flags = SourceCandidateRiskPrecedence.ordered(
            buildList {
                ambiguousFlag?.let(::add)
                addAll(capInfo.flags)
            },
        )
        val caution = cautionFor(afterAmbiguity, flags)
        return SourceCandidate(
            file = entry.file,
            line = entry.line,
            score = (rawScore / HIGH_CONFIDENCE_SCORE).coerceIn(0.0, 1.0),
            matchedTerms = matchedTerms,
            matchReasons = matchReasons,
            confidence = afterAmbiguity,
            ranking = margin.ranking,
            scoreMargin = margin.scoreMargin,
            evidenceStrength = profile.strength(),
            riskFlags = flags,
            caution = caution,
        )
    }

    private fun baseConfidenceFor(profile: EvidenceProfile, margin: MarginContext): SelectionConfidence {
        val clear = margin.scoreMargin >= MarginContext.CLEAR_MARGIN
        return when {
            profile.rawScore <= 0.0 -> SelectionConfidence.NONE
            profile.selectedStrongCount > 0 && clear -> SelectionConfidence.HIGH
            profile.distinctSelectedMediumKinds >= 2 && clear -> SelectionConfidence.HIGH
            profile.hasSelectedUiText || profile.hasSelectedContentDescription -> SelectionConfidence.MEDIUM
            profile.selectedStrongCount > 0 -> SelectionConfidence.MEDIUM
            else -> SelectionConfidence.LOW
        }
    }

    private data class CapResult(
        val confidence: SelectionConfidence,
        val flags: List<SourceCandidateRisk>,
    )

    private fun applyCaps(
        profile: EvidenceProfile,
        baseConfidence: SelectionConfidence,
    ): CapResult {
        val flags = mutableListOf<SourceCandidateRisk>()
        var confidence = baseConfidence

        when {
            profile.isArbitraryLiteralOnly -> {
                flags.add(SourceCandidateRisk.ARBITRARY_LITERAL)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.isLegacyFallbackOnly -> {
                flags.add(SourceCandidateRisk.LEGACY_FALLBACK)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.isNearbyOnly -> {
                flags.add(SourceCandidateRisk.NEARBY_ONLY)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.isActivityOnly -> {
                flags.add(SourceCandidateRisk.ACTIVITY_ONLY)
                confidence = capAt(confidence, SelectionConfidence.LOW)
            }
            profile.isTextOnly -> {
                flags.add(SourceCandidateRisk.TEXT_ONLY)
                confidence = capAt(confidence, SelectionConfidence.MEDIUM)
            }
        }

        return CapResult(confidence, flags)
    }

    private fun applyAmbiguityDowngrade(
        confidence: SelectionConfidence,
        margin: MarginContext,
        totalCandidates: Int,
    ): Pair<SelectionConfidence, SourceCandidateRisk?> {
        if (totalCandidates < 2) return confidence to null
        return when {
            margin.isAmbiguous -> downgrade(confidence) to SourceCandidateRisk.AMBIGUOUS
            margin.isMediumCeiling && confidence == SelectionConfidence.HIGH ->
                SelectionConfidence.MEDIUM to null
            else -> confidence to null
        }
    }

    private fun downgrade(confidence: SelectionConfidence): SelectionConfidence = when (confidence) {
        SelectionConfidence.HIGH -> SelectionConfidence.MEDIUM
        SelectionConfidence.MEDIUM -> SelectionConfidence.LOW
        else -> confidence
    }

    // SelectionConfidence ordinal: HIGH=0, MEDIUM=1, LOW=2, NONE=3 (smaller = higher confidence)
    // capAt returns ceiling when current is "above" (smaller ordinal than) ceiling.
    private fun capAt(current: SelectionConfidence, ceiling: SelectionConfidence): SelectionConfidence =
        if (current.ordinal < ceiling.ordinal) ceiling else current

    private fun cautionFor(
        confidence: SelectionConfidence,
        flags: List<SourceCandidateRisk>,
    ): String? {
        val highest = SourceCandidateRiskPrecedence.highest(flags)
        if (highest != null) {
            return when (highest) {
                SourceCandidateRisk.AMBIGUOUS ->
                    "Verify this source candidate before editing; top candidates are close."
                SourceCandidateRisk.AREA_SELECTION ->
                    "Visual-area selection; use screenshot and bounds before editing."
                SourceCandidateRisk.TEXT_ONLY ->
                    "Text-only match; confirm against screenshot and code."
                SourceCandidateRisk.NEARBY_ONLY ->
                    "Nearby-only match; confirm against screenshot and code."
                SourceCandidateRisk.ARBITRARY_LITERAL ->
                    "Match relied on a generic string literal; confirm against screenshot and code."
                SourceCandidateRisk.ACTIVITY_ONLY ->
                    "Activity-only match; confirm against screenshot and code."
                SourceCandidateRisk.LEGACY_FALLBACK ->
                    "Legacy-fallback match; confirm against screenshot and code."
            }
        }
        return when (confidence) {
            SelectionConfidence.LOW -> "Top source candidate has low confidence; verify before editing."
            SelectionConfidence.NONE -> "No source candidate was available from current evidence."
            else -> null
        }
    }
}

private const val MAX_CANDIDATES = 5
private const val HIGH_CONFIDENCE_SCORE = 100.0
@Suppress("unused")
private const val MEDIUM_CONFIDENCE_SCORE = 55.0
private const val MIN_PARTIAL_MATCH_LENGTH = 3
