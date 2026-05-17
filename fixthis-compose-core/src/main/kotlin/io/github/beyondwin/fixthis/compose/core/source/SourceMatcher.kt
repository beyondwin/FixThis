package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.identity.TestTagConvention
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk

class SourceMatcher(private val sourceIndex: SourceIndex) {
    fun match(
        selectedNode: FixThisNode?,
        nearbyNodes: List<FixThisNode>,
        activityName: String?,
    ): List<SourceCandidate> {
        if (selectedNode == null || sourceIndex.entries.isEmpty()) return emptyList()

        val matchScores = sourceIndex.entries.asSequence()
            .map { entry -> score(entry, selectedNode, nearbyNodes, activityName) }
            .filter { it.rawScore > 0.0 }
            .sortedWith(
                compareByDescending<MatchScore> { it.sourceRankingTier }
                    .thenByDescending { it.rawScore }
                    .thenBy { it.entry.file }
                    .thenBy { it.entry.line ?: Int.MAX_VALUE },
            )
            .take(SourceScoringPolicy.maxCandidates)
            .toList()

        val normalizedScores = matchScores.map {
            (it.rawScore / SourceScoringPolicy.highConfidenceScore).coerceIn(0.0, 1.0)
        }
        return matchScores.mapIndexed { index, score -> score.toCandidate(index, normalizedScores) }
    }

    companion object {
        fun match(
            sourceIndex: SourceIndex,
            selectedNode: FixThisNode?,
            nearbyNodes: List<FixThisNode>,
            activityName: String?,
        ): List<SourceCandidate> = SourceMatcher(sourceIndex).match(selectedNode, nearbyNodes, activityName)
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
        activityName: String?,
    ): MatchScore {
        val matchedTerms = linkedSetOf<String>()
        val matchReasons = linkedSetOf<SourceMatchReason>()
        val scoredEvidence = mutableSetOf<String>()
        val ctx = ScoreContext()
        val accumulator = MatchAccumulator(matchedTerms, matchReasons, scoredEvidence, ctx)
        var rawScore = 0.0

        selectedNode.text.forEach { term ->
            rawScore += addIfMatches(
                hit = entry.textLikeWeightHit(term),
                term = term,
                reason = SourceMatchReason.SELECTED_TEXT,
                accumulator = accumulator,
            )
        }
        selectedNode.editableText?.let { term ->
            rawScore += addIfMatches(
                hit = entry.textLikeWeightHit(term),
                term = term,
                reason = SourceMatchReason.SELECTED_TEXT,
                accumulator = accumulator,
            )
        }
        selectedNode.contentDescription.forEach { term ->
            rawScore += addIfMatches(
                hit = entry.contentDescriptionWeightHit(term),
                term = term,
                reason = SourceMatchReason.SELECTED_CONTENT_DESCRIPTION,
                accumulator = accumulator,
            )
        }
        selectedNode.testTag?.let { term ->
            rawScore += addSelectedTestTagScore(entry, term, accumulator)
        }
        selectedNode.role?.let { term ->
            rawScore += addIfMatches(
                hit = entry.roleWeightHit(term),
                term = term,
                reason = SourceMatchReason.SELECTED_ROLE,
                accumulator = accumulator,
            )
        }

        nearbyNodes.forEach { node ->
            node.text.forEach { term ->
                rawScore += addIfMatches(
                    hit = entry.textLikeWeightHit(term),
                    term = term,
                    reason = SourceMatchReason.NEARBY_TEXT,
                    accumulator = accumulator,
                    isNearby = true,
                )
            }
            node.editableText?.let { term ->
                rawScore += addIfMatches(
                    hit = entry.textLikeWeightHit(term),
                    term = term,
                    reason = SourceMatchReason.NEARBY_TEXT,
                    accumulator = accumulator,
                    isNearby = true,
                )
            }
            node.contentDescription.forEach { term ->
                rawScore += addIfMatches(
                    hit = entry.contentDescriptionWeightHit(term),
                    term = term,
                    reason = SourceMatchReason.NEARBY_CONTENT_DESCRIPTION,
                    accumulator = accumulator,
                    isNearby = true,
                )
            }
            node.testTag?.let { term ->
                rawScore += addIfMatches(
                    hit = entry.testTagWeightHit(term),
                    term = term,
                    reason = SourceMatchReason.NEARBY_TEST_TAG,
                    accumulator = accumulator,
                    isNearby = true,
                )
            }
            node.role?.let { term ->
                rawScore += addIfMatches(
                    hit = entry.roleWeightHit(term),
                    term = term,
                    reason = SourceMatchReason.NEARBY_ROLE,
                    accumulator = accumulator,
                    isNearby = true,
                )
            }
        }

        activityName?.takeUnless { it.isBlank() }?.let { name ->
            rawScore += addIfMatches(
                hit = entry.activityWeightHit(name),
                term = name.substringAfterLast('.'),
                reason = SourceMatchReason.ACTIVITY,
                accumulator = accumulator,
            )
        }

        // Post-processing: emit "arbitrary literal" or "legacy fallback" origin markers
        if (ctx.anyTermMatched) {
            if (!ctx.anyTypedSignalNonLiteral && !ctx.anyLegacyOnly && ctx.anyArbitraryLiteralSignal) {
                matchReasons.add(SourceMatchReason.ARBITRARY_LITERAL)
            }
            if (!ctx.anyTypedSignalNonLiteral && !ctx.anyArbitraryLiteralSignal && ctx.anyLegacyOnly) {
                matchReasons.add(SourceMatchReason.UNTYPED_FALLBACK)
            }
        }

        return MatchScore(
            entry = entry,
            rawScore = rawScore,
            matchedTerms = matchedTerms.toList(),
            matchReasons = matchReasons.toList(),
        )
    }

    private fun addSelectedTestTagScore(
        entry: SourceIndexEntry,
        testTag: String,
        accumulator: MatchAccumulator,
    ): Double {
        var score = addIfMatches(
            hit = entry.testTagWeightHit(testTag),
            term = testTag,
            reason = SourceMatchReason.SELECTED_TEST_TAG,
            accumulator = accumulator,
        )

        TestTagConvention.parse(testTag)?.let { parsed ->
            val conventionScore = addIfMatches(
                hit = entry.conventionComposableWeightHit(parsed.composableName),
                term = parsed.composableName,
                reason = SourceMatchReason.SELECTED_TEST_TAG_CONVENTION_COMPOSABLE,
                accumulator = accumulator,
            )
            score = maxOf(score, conventionScore)
        }

        return score
    }

    private fun addIfMatches(
        hit: WeightHit,
        term: String,
        reason: SourceMatchReason,
        accumulator: MatchAccumulator,
        isNearby: Boolean = false,
    ): Double {
        val cleaned = term.trim()
        return if (hit.weight <= 0.0 || cleaned.isEmpty()) {
            0.0
        } else {
            accumulator.recordMatch(hit, reason, cleaned, isNearby)
        }
    }

    private fun MatchAccumulator.recordMatch(
        hit: WeightHit,
        reason: SourceMatchReason,
        cleaned: String,
        isNearby: Boolean,
    ): Double {
        val effectiveReason = if (
            hit.signalKind == SourceSignalKind.STRING_RESOURCE_RESOLVED &&
            reason == SourceMatchReason.SELECTED_TEXT
        ) {
            SourceMatchReason.SELECTED_RESOLVED_STRING_RESOURCE
        } else {
            reason
        }
        matchedTerms.add(cleaned)
        matchReasons.add(effectiveReason)
        trackOrigin(hit, effectiveReason, isNearby)
        if (hit.signalKind == SourceSignalKind.STRING_RESOURCE &&
            (
                reason == SourceMatchReason.SELECTED_TEXT ||
                    reason == SourceMatchReason.SELECTED_CONTENT_DESCRIPTION
                )
        ) {
            matchReasons.add(SourceMatchReason.SELECTED_STRING_RESOURCE)
        }
        val evidenceKey = "${effectiveReason.wireLabel}${cleaned.normalizedForMatch()}"
        return if (scoredEvidence.add(evidenceKey)) {
            SourceScoringPolicy.bucketScore(effectiveReason) * hit.weight
        } else {
            0.0
        }
    }

    private fun MatchAccumulator.trackOrigin(
        hit: WeightHit,
        reason: SourceMatchReason,
        isNearby: Boolean,
    ) {
        val isSelectedBucket = !isNearby && reason != SourceMatchReason.ACTIVITY
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
    }

    private data class MatchAccumulator(
        val matchedTerms: MutableSet<String>,
        val matchReasons: MutableSet<SourceMatchReason>,
        val scoredEvidence: MutableSet<String>,
        val ctx: ScoreContext,
    )

    // Weight-hit carrier: weight from the winning signal, its kind (null = legacy), and whether it came from legacy path
    private data class WeightHit(
        val weight: Double,
        val signalKind: SourceSignalKind?,
        val viaLegacy: Boolean,
    )

    private fun SourceIndexEntry.textLikeWeightHit(term: String): WeightHit = signalOrLegacyWeightHit(
        term = term,
        kinds = setOf(
            SourceSignalKind.UI_TEXT,
            SourceSignalKind.STRING_RESOURCE_RESOLVED,
            SourceSignalKind.STRING_RESOURCE,
            SourceSignalKind.ARBITRARY_STRING_LITERAL,
        ),
        legacyCandidates = text + stringResources + symbols + listOfNotNull(excerpt),
    )

    private fun SourceIndexEntry.contentDescriptionWeightHit(term: String): WeightHit = signalOrLegacyWeightHit(
        term = term,
        kinds = setOf(
            SourceSignalKind.CONTENT_DESCRIPTION,
            SourceSignalKind.STRING_RESOURCE,
            SourceSignalKind.ARBITRARY_STRING_LITERAL,
        ),
        legacyCandidates = contentDescriptions + stringResources + symbols + listOfNotNull(excerpt),
    )

    private fun SourceIndexEntry.testTagWeightHit(term: String): WeightHit = signalOrLegacyWeightHit(
        term = term,
        kinds = setOf(SourceSignalKind.TEST_TAG, SourceSignalKind.STRICT_COMP_TEST_TAG),
        legacyCandidates = testTags + symbols + listOfNotNull(excerpt),
    )

    private fun SourceIndexEntry.conventionComposableWeightHit(composableName: String): WeightHit = signalOrLegacyWeightHit(
        term = composableName,
        kinds = setOf(SourceSignalKind.COMPOSABLE_SYMBOL, SourceSignalKind.STRICT_COMP_TEST_TAG),
        legacyCandidates = symbols + listOf(file) + listOfNotNull(excerpt),
    )

    private fun SourceIndexEntry.roleWeightHit(term: String): WeightHit = signalOrLegacyWeightHit(
        term = term,
        kinds = setOf(SourceSignalKind.ROLE),
        legacyCandidates = roles + symbols + listOfNotNull(excerpt),
    )

    private fun SourceIndexEntry.activityWeightHit(activityName: String): WeightHit {
        val simpleName = activityName.substringAfterLast('.')
        val (signalMatchWeight, signalKind) = bestSignalHit(
            listOf(activityName, simpleName),
            setOf(SourceSignalKind.ACTIVITY_NAME),
        )
        if (signalMatchWeight > 0.0) return WeightHit(signalMatchWeight, signalKind, viaLegacy = false)

        val activityTerms = activityNames + listOf(file)
        val legacyMatches = matchesAny(activityName, activityTerms) || matchesAny(simpleName, activityTerms)
        return WeightHit(legacyWeight(legacyMatches), signalKind = null, viaLegacy = legacyMatches)
    }

    private fun SourceIndexEntry.signalOrLegacyWeightHit(
        term: String,
        kinds: Set<SourceSignalKind>,
        legacyCandidates: List<String>,
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
            SourceSignalKind.STRING_RESOURCE_RESOLVED,
            SourceSignalKind.UI_TEXT,
            SourceSignalKind.TEST_TAG,
            SourceSignalKind.CONTENT_DESCRIPTION,
            SourceSignalKind.COMPOSABLE_SYMBOL,
            SourceSignalKind.LAMBDA_OWNER_FUNCTION,
            -> 1.0
            SourceSignalKind.STRING_RESOURCE,
            SourceSignalKind.ROLE,
            SourceSignalKind.ACTIVITY_NAME,
            -> 0.85
            SourceSignalKind.ARBITRARY_STRING_LITERAL -> 0.35
        }

    private fun matchesAny(term: String, candidates: List<String>): Boolean {
        val normalizedTerm = term.normalizedForMatch()
        if (normalizedTerm.isEmpty()) return false
        return candidates.any { candidate ->
            val normalizedCandidate = candidate.normalizedForMatch()
            normalizedCandidate == normalizedTerm ||
                (
                    normalizedTerm.length >= SourceScoringPolicy.minPartialMatchLength &&
                        normalizedCandidate.contains(normalizedTerm)
                    )
        }
    }

    private fun String.normalizedForMatch(): String = trim().lowercase().replace(Regex("\\s+"), " ")

    private data class MatchScore(
        val entry: SourceIndexEntry,
        val rawScore: Double,
        val matchedTerms: List<String>,
        val matchReasons: List<SourceMatchReason>,
    )

    private val MatchScore.sourceRankingTier: Int
        get() = SourceScoringPolicy.rankingTier(matchReasons)

    private fun MatchScore.toCandidate(
        index: Int,
        normalizedScores: List<Double>,
    ): SourceCandidate {
        val profile = EvidenceProfile.fromMatchReasons(matchReasons, rawScore)
        val wireReasons = matchReasons.map { it.wireLabel }
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
        val scoreMargin = if (index == 0 && normalizedScores.size >= 2) {
            normalizedScores[0] - normalizedScores[1]
        } else {
            null
        }
        return SourceCandidate(
            file = entry.file,
            repoFile = entry.repoFile,
            line = entry.line,
            score = (rawScore / SourceScoringPolicy.highConfidenceScore).coerceIn(0.0, 1.0),
            matchedTerms = matchedTerms,
            matchReasons = wireReasons,
            confidence = afterAmbiguity,
            ranking = margin.ranking,
            scoreMargin = scoreMargin,
            evidenceStrength = profile.strength(),
            riskFlags = flags,
            caution = caution,
            ownerComposable = entry.ownerComposable,
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
    private fun capAt(current: SelectionConfidence, ceiling: SelectionConfidence): SelectionConfidence = if (current.ordinal < ceiling.ordinal) ceiling else current

    private fun cautionFor(
        confidence: SelectionConfidence,
        flags: List<SourceCandidateRisk>,
    ): String? = SourceConfidencePolicy.cautionFor(confidence, flags)
}
