@file:Suppress("TooManyFunctions", "MatchingDeclarationName", "NestedBlockDepth")

package io.github.beyondwin.fixthis.compose.core.source

// Weight-hit carrier: weight from the winning signal, its kind (null = legacy), and whether it came from legacy path
internal data class WeightHit(
    val weight: Double,
    val signalKind: SourceSignalKind?,
    val viaLegacy: Boolean,
)

internal fun SourceIndexEntry.textLikeWeightHit(term: String): WeightHit = signalOrLegacyWeightHit(
    term = term,
    kinds = setOf(
        SourceSignalKind.UI_TEXT,
        SourceSignalKind.STRING_RESOURCE_RESOLVED,
        SourceSignalKind.STRING_RESOURCE,
        SourceSignalKind.ARBITRARY_STRING_LITERAL,
    ),
    legacyCandidates = text + stringResources + symbols + listOfNotNull(excerpt),
)

internal fun SourceIndexEntry.contentDescriptionWeightHit(term: String): WeightHit = signalOrLegacyWeightHit(
    term = term,
    kinds = setOf(
        SourceSignalKind.CONTENT_DESCRIPTION,
        SourceSignalKind.STRING_RESOURCE,
        SourceSignalKind.ARBITRARY_STRING_LITERAL,
        SourceSignalKind.MODIFIER_TARGET,
    ),
    legacyCandidates = contentDescriptions + stringResources + symbols + listOfNotNull(excerpt),
)

internal fun SourceIndexEntry.testTagWeightHit(term: String): WeightHit = signalOrLegacyWeightHit(
    term = term,
    kinds = setOf(SourceSignalKind.TEST_TAG, SourceSignalKind.STRICT_COMP_TEST_TAG),
    legacyCandidates = testTags + symbols + listOfNotNull(excerpt),
)

internal fun SourceIndexEntry.conventionComposableWeightHit(composableName: String): WeightHit {
    val (signalMatchWeight, signalKind) = bestSignalHit(
        terms = listOf(composableName),
        kinds = setOf(
            SourceSignalKind.COMPOSABLE_SYMBOL,
            SourceSignalKind.STRICT_COMP_TEST_TAG,
            SourceSignalKind.LAMBDA_OWNER_FUNCTION,
            SourceSignalKind.LAZY_ITEM_OWNER,
            SourceSignalKind.NAV_DESTINATION_OWNER,
        ),
    )
    if (signalMatchWeight > 0.0) {
        return WeightHit(
            weight = if (signalKind == SourceSignalKind.LAMBDA_OWNER_FUNCTION) signalMatchWeight * 0.85 else signalMatchWeight,
            signalKind = signalKind,
            viaLegacy = false,
        )
    }

    val legacyMatches = matchesAny(composableName, symbols + listOf(file) + listOfNotNull(excerpt))
    return WeightHit(legacyWeight(legacyMatches), signalKind = null, viaLegacy = legacyMatches)
}

internal fun SourceIndexEntry.roleWeightHit(term: String): WeightHit = signalOrLegacyWeightHit(
    term = term,
    kinds = setOf(SourceSignalKind.ROLE),
    legacyCandidates = roles + symbols + listOfNotNull(excerpt),
)

internal fun SourceIndexEntry.activityWeightHit(activityName: String): WeightHit {
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

internal fun SourceIndexEntry.signalOrLegacyWeightHit(
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
internal fun SourceIndexEntry.bestSignalHit(
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

internal fun legacyWeight(matches: Boolean): Double = if (matches) 1.0 else 0.0

internal fun matchesAny(term: String, candidates: List<String>): Boolean {
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

internal fun String.normalizedForMatch(): String = trim().lowercase().replace(Regex("\\s+"), " ")
