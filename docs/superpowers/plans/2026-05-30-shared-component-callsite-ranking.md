# Shared-Component Call-Site Ranking Hint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rank a shared component's call-site inventory by how well each call site's static context matches the selected node's runtime evidence, surfacing the most plausible usage first as a best-effort hint — without raising confidence or claiming a precise target.

**Architecture:** The Gradle fan-in pass captures two lightweight static fields per call site (the enclosing function/composable name and the call's string-literal arguments) and serializes them additively into the existing `SHARED_COMPONENT_CALL_SITE` signal value. Core parses that context, scores each call site against normalized selection tokens (text, editable text, content description, role, activity), reorders the `callSites` list by score with a stable tiebreak on the existing static order, and marks the top entry `mostLikely` only past a clear margin. Confidence stays MEDIUM-capped; ranking is internal to the `callSites` list. The console renders the ordered list and labels the most-likely entry.

**Tech Stack:** Kotlin (`:fixthis-gradle-plugin`, `:fixthis-compose-core`), kotlinx.serialization, JUnit4, the JS feedback console (`fixthis-mcp/src/main/console`), Node fixture-lab (`scripts/source-matching-fixtures.mjs`).

**Spec:** [`docs/superpowers/specs/2026-05-30-shared-component-callsite-ranking-design.md`](../specs/2026-05-30-shared-component-callsite-ranking-design.md)

---

## File Structure

- **Modify** `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanIn.kt` — add `enclosingName`/`argLiterals` to `ComposableCallSite`, capture them during the scan, add `encodeSignalValue()` and a per-arg cap.
- **Modify** `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt` — emit the encoded value.
- **Modify** `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanInTest.kt` — cover context capture and encoding.
- **Modify** `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt` — add `mostLikely` to `SourceLocationRef`.
- **Modify** `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt` — add `mostLikely` to `SourceHintLocation`.
- **Modify** `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt` — carry `mostLikely` both directions.
- **Create** `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SharedComponentCallSiteRanking.kt` — self-contained parse + score + rank, plus selection-token builder. Keeps the bulk out of `SourceMatcher.kt`.
- **Modify** `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt` — build selection tokens, thread them to `toCandidate`, delegate ranking to the new helper.
- **Create** `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SharedComponentCallSiteRankingTest.kt` — ranking/no-evidence/margin tests.
- **Modify** `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt` — end-to-end ranking through `match`, confidence-still-MEDIUM guard.
- **Modify** `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateSerializationTest.kt` — `mostLikely` round-trips.
- **Modify** `fixthis-mcp/src/main/console/presentation/annotationDetailView.js` — render the most-likely marker.
- **Modify** `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt` — bump the `SourceMatcher.kt` budget for the few threading lines.
- **Modify** `fixtures/source-matching/manifest.json` — add a reused-component runtime-trust case (non-goal guard).

---

## Task 1: Capture enclosing name and argument literals during the fan-in scan

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanIn.kt`
- Test: `fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanInTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `ComposableCallSiteFanInTest`:

```kotlin
    @Test
    fun capturesEnclosingNameAndArgumentLiteralsPerCallSite() {
        val sources = listOf(
            CallSiteSource(
                path = "ui/Screen.kt",
                content =
                "@Composable\n" +
                    "fun ProfileScreen() {\n" +
                    "  PrimaryButton(text = \"Save\", subtitle = \"Profile\")\n" +
                    "}\n",
            ),
        )

        val sites = composableCallSites(sources, definitionNames = setOf("PrimaryButton"))

        assertEquals(
            listOf(
                ComposableCallSite(
                    file = "ui/Screen.kt",
                    line = 3,
                    enclosingName = "ProfileScreen",
                    argLiterals = listOf("Save", "Profile"),
                ),
            ),
            sites["PrimaryButton"],
        )
    }

    @Test
    fun encodesCallSiteSignalValueOnlyWithContextWhenPresent() {
        val plain = ComposableCallSite(file = "ui/A.kt", line = 5)
        val withContext = ComposableCallSite(
            file = "ui/B.kt",
            line = 9,
            enclosingName = "HomeScreen",
            argLiterals = listOf("Add", "Cart"),
        )

        assertEquals("ui/A.kt:5", plain.encodeSignalValue())
        assertEquals("ui/B.kt:9\tHomeScreen\tAdd|Cart", withContext.encodeSignalValue())
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*ComposableCallSiteFanInTest*"`
Expected: FAIL — `ComposableCallSite` has no `enclosingName`/`argLiterals` params; `encodeSignalValue` unresolved.

- [ ] **Step 3: Implement the capture and encoding**

In `ComposableCallSiteFanIn.kt`, add the per-arg cap constant near the existing limits:

```kotlin
/** Maximum string-literal arguments captured per call site for ranking context. */
internal const val SHARED_COMPONENT_CALLSITE_ARG_LIMIT: Int = 8
```

Extend the data class:

```kotlin
/** A single resolved call site of a composable definition, with best-effort static ranking context. */
internal data class ComposableCallSite(
    val file: String,
    val line: Int,
    val enclosingName: String? = null,
    val argLiterals: List<String> = emptyList(),
)
```

Add a regex for enclosing-name lookup beside `funDeclarationBeforeNameRegex`:

```kotlin
private val enclosingFunDeclarationRegex = Regex("""\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
```

In `composableCallSites`, populate the new fields where the site is added. Replace the existing add line:

```kotlin
                val line = text.lineNumberAt(start)
                sites.getOrPut(name) { mutableListOf() }.add(
                    ComposableCallSite(
                        file = source.path,
                        line = line,
                        enclosingName = text.enclosingFunctionName(start),
                        argLiterals = text.callArgumentLiterals(match.range.last, ignoredRanges),
                    ),
                )
```

Add the helpers and the encoder at the bottom of the file:

```kotlin
internal fun ComposableCallSite.encodeSignalValue(): String {
    val location = "$file:$line"
    return if (enclosingName == null && argLiterals.isEmpty()) {
        location
    } else {
        location + "\t" + (enclosingName ?: "") + "\t" + argLiterals.joinToString("|")
    }
}

private fun String.enclosingFunctionName(offset: Int): String? =
    enclosingFunDeclarationRegex.findAll(substring(0, offset)).lastOrNull()?.groupValues?.get(1)

private fun String.callArgumentLiterals(openParenIndex: Int, ignoredRanges: List<IntRange>): List<String> {
    val close = matchingParenIndex(openParenIndex, ignoredRanges) ?: return emptyList()
    val commentRanges = commentRanges()
    val span = substring(openParenIndex + 1, close)
    return kotlinSourceQuotedStringRegex.findAll(span)
        .filter { match -> commentRanges.none { (openParenIndex + 1 + match.range.first) in it } }
        .map { it.value.trim('"').replace('\t', ' ').replace('|', '/') }
        .filter { it.isNotEmpty() }
        .take(SHARED_COMPONENT_CALLSITE_ARG_LIMIT)
        .toList()
}

private fun String.matchingParenIndex(open: Int, ignoredRanges: List<IntRange>): Int? {
    var depth = 0
    var i = open
    while (i < length) {
        if (ignoredRanges.none { i in it }) {
            when (this[i]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return i
                }
            }
        }
        i += 1
    }
    return null
}
```

Note: `match.range.last` for `composableCallRegex` is the index of the `(`, so `matchingParenIndex` starts at the opening paren.

- [ ] **Step 4: Update the two existing location tests for the new populated fields**

The scan now populates context, so the existing expected `ComposableCallSite(...)` values must include it. Update `recordsCallSiteLocationsByFileAndLine`:

```kotlin
        assertEquals(
            listOf(
                ComposableCallSite(file = "ui/ScreenA.kt", line = 3, enclosingName = "ScreenA", argLiterals = listOf("Save")),
                ComposableCallSite(file = "ui/ScreenB.kt", line = 3, enclosingName = "ScreenB", argLiterals = listOf("Cancel")),
            ),
            sites["PrimaryButton"],
        )
```

Update `callSiteLocationsExcludeDeclarationsStringsAndMemberCalls`:

```kotlin
        assertEquals(
            listOf(ComposableCallSite(file = "ui/Screen.kt", line = 5, enclosingName = "Screen", argLiterals = listOf("real"))),
            sites["PrimaryButton"],
        )
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :fixthis-gradle-plugin:test --tests "*ComposableCallSiteFanInTest*"`
Expected: PASS (all fan-in tests green).

- [ ] **Step 6: Commit**

```bash
git add fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanIn.kt fixthis-gradle-plugin/src/test/kotlin/io/github/beyondwin/fixthis/gradle/source/ComposableCallSiteFanInTest.kt
git commit -m "feat(source): capture call-site enclosing name and arg literals"
```

---

## Task 2: Emit the encoded call-site value from the index generator

**Files:**
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt`

- [ ] **Step 1: Update the generator to use the encoder**

In `withSharedComponentSignal`, replace the `callSiteSignals` mapping:

```kotlin
        val callSiteSignals = sites.take(SHARED_COMPONENT_CALLSITE_LIMIT).map { site ->
            SourceSignalAsset(SourceSignalKindAsset.SHARED_COMPONENT_CALL_SITE, site.encodeSignalValue())
        }
```

(Only the value argument changes: `"${site.file}:${site.line}"` → `site.encodeSignalValue()`.)

- [ ] **Step 2: Run the gradle-plugin test suite to verify no regression**

Run: `./gradlew :fixthis-gradle-plugin:test`
Expected: PASS. (Generator output for sites without context is byte-identical; sites with context now carry the additive tab-delimited fields.)

- [ ] **Step 3: Commit**

```bash
git add fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/SourceIndexGenerator.kt
git commit -m "feat(source): serialize call-site ranking context into index signal"
```

---

## Task 3: Add the additive `mostLikely` flag to the call-site models

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt:103-106`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt:27-30`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt:47-49`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateSerializationTest.kt`

- [ ] **Step 1: Write the failing test**

In `SourceCandidateSerializationTest`, add:

```kotlin
    @Test
    fun roundTripsMostLikelyCallSiteFlag() {
        val candidate = SourceCandidate(
            file = "ui/PrimaryButton.kt",
            score = 0.5,
            confidence = SelectionConfidence.MEDIUM,
            callSites = listOf(
                SourceLocationRef(file = "ui/ScreenA.kt", line = 42, mostLikely = true),
                SourceLocationRef(file = "ui/ScreenB.kt", line = 13),
            ),
        )

        val decoded = Json.decodeFromString<SourceCandidate>(Json.encodeToString(candidate))

        assertEquals(candidate.callSites, decoded.callSites)
        assertEquals(true, decoded.callSites[0].mostLikely)
        assertEquals(false, decoded.callSites[1].mostLikely)
    }
```

(If the test file lacks the `Json`/`encodeToString` imports used by sibling tests, copy them from the existing serialization tests at the top of the file.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceCandidateSerializationTest*"`
Expected: FAIL — `SourceLocationRef` has no `mostLikely` parameter.

- [ ] **Step 3: Add the field to both models and the mappers**

`Models.kt` — `SourceLocationRef`:

```kotlin
@Serializable
data class SourceLocationRef(
    val file: String,
    val line: Int? = null,
    val mostLikely: Boolean = false,
)
```

`SourceHint.kt` — `SourceHintLocation`:

```kotlin
data class SourceHintLocation(
    val file: String,
    val line: Int? = null,
    val mostLikely: Boolean = false,
)
```

`SourceCandidateMappers.kt` — carry the flag both directions:

```kotlin
private fun SourceLocationRef.toSourceHintLocation(): SourceHintLocation =
    SourceHintLocation(file = file, line = line, mostLikely = mostLikely)

private fun SourceHintLocation.toSourceLocationRef(): SourceLocationRef =
    SourceLocationRef(file = file, line = line, mostLikely = mostLikely)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceCandidateSerializationTest*" --tests "*SourceCandidateMappersTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateMappers.kt fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/SourceCandidateSerializationTest.kt
git commit -m "feat(source): add additive mostLikely flag to call-site refs"
```

---

## Task 4: Parse context, score, and rank call sites

**Files:**
- Create: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SharedComponentCallSiteRanking.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SharedComponentCallSiteRankingTest.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt:16-32, 468-536`

- [ ] **Step 1: Write the failing ranking-helper test**

Create `SharedComponentCallSiteRankingTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.SourceLocationRef
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedComponentCallSiteRankingTest {
    @Test
    fun ranksLiteralMatchFirstAndMarksMostLikely() {
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/ScreenA.kt:10\tScreenA\tCancel",
                "ui/ScreenB.kt:20\tProfileScreen\tSave|Profile",
            ),
            selectionTokens = setOf("save"),
        )

        assertEquals(
            listOf(
                SourceLocationRef(file = "ui/ScreenB.kt", line = 20, mostLikely = true),
                SourceLocationRef(file = "ui/ScreenA.kt", line = 10, mostLikely = false),
            ),
            ranked,
        )
    }

    @Test
    fun keepsStaticOrderAndNoMarkWhenNoEvidenceMatches() {
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/ScreenA.kt:10\tScreenA\tCancel",
                "ui/ScreenB.kt:20\tScreenB\tDelete",
            ),
            selectionTokens = setOf("save"),
        )

        assertEquals(
            listOf(
                SourceLocationRef(file = "ui/ScreenA.kt", line = 10, mostLikely = false),
                SourceLocationRef(file = "ui/ScreenB.kt", line = 20, mostLikely = false),
            ),
            ranked,
        )
    }

    @Test
    fun parsesPlainLocationWithoutContext() {
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf("ui/ScreenA.kt:10", "ui/ScreenB.kt"),
            selectionTokens = emptySet(),
        )

        assertEquals(
            listOf(
                SourceLocationRef(file = "ui/ScreenA.kt", line = 10, mostLikely = false),
                SourceLocationRef(file = "ui/ScreenB.kt", line = null, mostLikely = false),
            ),
            ranked,
        )
    }

    @Test
    fun doesNotMarkMostLikelyWhenTopMatchesTie() {
        val ranked = rankSharedComponentCallSites(
            callSiteSignalValues = listOf(
                "ui/ScreenA.kt:10\tScreenA\tSave",
                "ui/ScreenB.kt:20\tScreenB\tSave",
            ),
            selectionTokens = setOf("save"),
        )

        assertEquals(false, ranked[0].mostLikely)
        assertEquals(false, ranked[1].mostLikely)
        assertEquals("ui/ScreenA.kt", ranked[0].file)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*SharedComponentCallSiteRankingTest*"`
Expected: FAIL — `rankSharedComponentCallSites` is unresolved.

- [ ] **Step 3: Implement the ranking helper**

Create `SharedComponentCallSiteRanking.kt`:

```kotlin
package io.github.beyondwin.fixthis.compose.core.source

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.SourceLocationRef

private const val CALL_SITE_LITERAL_WEIGHT: Double = 2.0
private const val CALL_SITE_ENCLOSING_WEIGHT: Double = 1.0
private const val CALL_SITE_MOST_LIKELY_MARGIN: Double = 1.0
private const val CALL_SITE_MIN_PARTIAL_MATCH_LENGTH: Int = 3

private data class ParsedCallSite(
    val file: String,
    val line: Int?,
    val enclosing: String?,
    val literals: List<String>,
)

/** Builds the normalized selection-evidence tokens used to rank shared-component call sites. */
internal fun selectionTokensFor(selectedNode: FixThisNode, activityName: String?): Set<String> =
    buildSet {
        selectedNode.text.forEach { add(it) }
        selectedNode.editableText?.let { add(it) }
        selectedNode.contentDescription.forEach { add(it) }
        selectedNode.role?.let { add(it) }
        activityName?.let { add(it) }
    }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/**
 * Reorders the call-site inventory by how well each site's static context (enclosing function name
 * and string-literal arguments) overlaps the selection tokens. Ordering is a best-effort hint: ties
 * and zero-evidence cases preserve the original static order, and the top entry is marked
 * `mostLikely` only when its score clears the next site by a fixed margin.
 */
internal fun rankSharedComponentCallSites(
    callSiteSignalValues: List<String>,
    selectionTokens: Set<String>,
): List<SourceLocationRef> {
    val parsed = callSiteSignalValues.map(::parseCallSiteSignal)
    val scored = parsed.map { it to callSiteScore(it, selectionTokens) }
    val ordered = scored.sortedByDescending { it.second } // stable: ties keep static order
    val topScore = ordered.firstOrNull()?.second ?: 0.0
    val secondScore = ordered.getOrNull(1)?.second ?: 0.0
    val markTop = topScore > 0.0 && (topScore - secondScore) >= CALL_SITE_MOST_LIKELY_MARGIN
    return ordered.mapIndexed { index, (site, _) ->
        SourceLocationRef(file = site.file, line = site.line, mostLikely = markTop && index == 0)
    }
}

private fun parseCallSiteSignal(raw: String): ParsedCallSite {
    val parts = raw.split('\t')
    val location = parts[0]
    val sep = location.lastIndexOf(':')
    val file: String
    val line: Int?
    if (sep <= 0) {
        file = location
        line = null
    } else {
        file = location.substring(0, sep)
        line = location.substring(sep + 1).toIntOrNull()
    }
    val enclosing = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
    val literals = parts.getOrNull(2)?.split('|')?.filter { it.isNotEmpty() }.orEmpty()
    return ParsedCallSite(file = file, line = line, enclosing = enclosing, literals = literals)
}

private fun callSiteScore(site: ParsedCallSite, tokens: Set<String>): Double {
    if (tokens.isEmpty()) return 0.0
    var score = 0.0
    if (site.literals.any { literal -> tokens.any { token -> tokenMatches(token, literal) } }) {
        score += CALL_SITE_LITERAL_WEIGHT
    }
    val enclosing = site.enclosing
    if (enclosing != null && tokens.any { token -> tokenMatches(token, enclosing) }) {
        score += CALL_SITE_ENCLOSING_WEIGHT
    }
    return score
}

private fun tokenMatches(token: String, candidate: String): Boolean {
    val normalizedToken = token.normalizedForCallSiteMatch()
    val normalizedCandidate = candidate.normalizedForCallSiteMatch()
    if (normalizedToken.isEmpty() || normalizedCandidate.isEmpty()) return false
    return normalizedToken == normalizedCandidate ||
        (normalizedToken.length >= CALL_SITE_MIN_PARTIAL_MATCH_LENGTH && normalizedCandidate.contains(normalizedToken)) ||
        (normalizedCandidate.length >= CALL_SITE_MIN_PARTIAL_MATCH_LENGTH && normalizedToken.contains(normalizedCandidate))
}

private fun String.normalizedForCallSiteMatch(): String = trim().lowercase().replace(Regex("\\s+"), " ")
```

- [ ] **Step 4: Run the helper test to verify it passes**

Run: `./gradlew :fixthis-compose-core:test --tests "*SharedComponentCallSiteRankingTest*"`
Expected: PASS.

- [ ] **Step 5: Write the failing end-to-end matcher test**

In `SourceMatcherTest`, add (mirrors `populatesCallSitesForSharedComponentDefinition`, but with literal context and matching selection text):

```kotlin
    @Test
    fun ranksSharedComponentCallSitesBySelectionEvidenceAndKeepsMediumConfidence() {
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "$SAMPLE_SOURCE_PREFIX/components/PrimaryButton.kt",
                    line = 8,
                    symbols = listOf("PrimaryButton"),
                    testTags = listOf("comp:PrimaryButton:root"),
                    signals = listOf(
                        SourceSignal(kind = SourceSignalKind.COMPOSABLE_SYMBOL, value = "PrimaryButton"),
                        SourceSignal(kind = SourceSignalKind.STRICT_COMP_TEST_TAG, value = "comp:PrimaryButton:root"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT, value = "2"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE, value = "ui/ScreenA.kt:42\tScreenA\tCancel"),
                        SourceSignal(kind = SourceSignalKind.SHARED_COMPONENT_CALL_SITE, value = "ui/ScreenB.kt:13\tScreenB\tSave changes"),
                    ),
                    excerpt = "@Composable fun PrimaryButton(",
                ),
            ),
        )

        val candidate = SourceMatcher(index).match(
            selectedNode = node(uid = "primary", text = listOf("Save changes"), testTag = "comp:PrimaryButton:root"),
            nearbyNodes = emptyList(),
            activityName = null,
        ).first()

        assertEquals(
            listOf(
                SourceLocationRef(file = "ui/ScreenB.kt", line = 13, mostLikely = true),
                SourceLocationRef(file = "ui/ScreenA.kt", line = 42, mostLikely = false),
            ),
            candidate.callSites,
        )
        assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
        assertTrue(candidate.riskFlags.contains(SourceCandidateRisk.SHARED_COMPONENT))
    }
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest.ranksSharedComponentCallSitesBySelectionEvidenceAndKeepsMediumConfidence*"`
Expected: FAIL — call sites still returned in static order, `mostLikely` always false (ranking not wired into `SourceMatcher`).

- [ ] **Step 7: Wire the matcher to the helper**

In `SourceMatcher.kt`, inside `match(...)`, immediately after the early-return guard
(`if (selectedNode == null || sourceIndex.entries.isEmpty()) return emptyList()`), add:

```kotlin
        val selectionTokens = selectionTokensFor(selectedNode, activityName)
```

Change the final mapping to thread the tokens:

```kotlin
        return matchScores.mapIndexed { index, score -> score.toCandidate(index, normalizedScores, selectionTokens) }
```

Update `toCandidate`'s signature to accept the tokens:

```kotlin
    private fun MatchScore.toCandidate(
        index: Int,
        normalizedScores: List<Double>,
        selectionTokens: Set<String>,
    ): SourceCandidate {
```

Update the `callSites` assignment inside `toCandidate`:

```kotlin
        val callSites = sharedComponentCallSites(selectionTokens)
```

Replace the body of `sharedComponentCallSites` to delegate (it no longer parses inline):

```kotlin
    private fun MatchScore.sharedComponentCallSites(selectionTokens: Set<String>): List<SourceLocationRef> {
        if (SourceMatchReason.SHARED_COMPONENT_DEFINITION !in matchReasons) return emptyList()
        return rankSharedComponentCallSites(
            callSiteSignalValues = entry.signals
                .filter { it.kind == SourceSignalKind.SHARED_COMPONENT_CALL_SITE }
                .map { it.value },
            selectionTokens = selectionTokens,
        )
    }
```

Notes:
- The old `sharedComponentCallSites(profile)` used `profile.hasSharedComponentDefinition`; the inline guard `SourceMatchReason.SHARED_COMPONENT_DEFINITION in matchReasons` is equivalent and removes the now-unused `profile` parameter. Leave the `EvidenceProfile` import/usage elsewhere in `toCandidate` untouched.
- Delete the old inline `file:line` parsing block (lines ~521-536) that this replaces.

- [ ] **Step 8: Run the matcher tests to verify pass (incl. the pre-existing call-site test)**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest*"`
Expected: PASS. `populatesCallSitesForSharedComponentDefinition` still passes — its signal values carry no context and its selected node has no text/role, so tokens are empty, order is preserved, and `mostLikely` defaults to false.

- [ ] **Step 9: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SharedComponentCallSiteRanking.kt fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SharedComponentCallSiteRankingTest.kt fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt
git commit -m "feat(source): rank shared-component call sites by selection evidence"
```

---

## Task 5: Raise the `SourceMatcher.kt` hotspot budget for the threading lines

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt:33`

- [ ] **Step 1: Run the budget test to see whether it trips**

Run: `./gradlew :fixthis-mcp:test --tests "*ArchitectureHotspotBudgetTest*"`
Expected: Likely FAIL — `SourceMatcher.kt` was 577/580 and gained a few lines from token threading. The failure message reports the exact current line count.

- [ ] **Step 2: Raise the budget to the reported count rounded up to the next 5**

Edit line 33; set the budget to the smallest multiple of 5 at or above the reported line count (e.g. `585`):

```kotlin
            "fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt" to 585,
```

(If Step 1 passed because the file stayed at/under 580, skip the edit and this task.)

- [ ] **Step 3: Run the budget test to verify it passes**

Run: `./gradlew :fixthis-mcp:test --tests "*ArchitectureHotspotBudgetTest*"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt
git commit -m "chore(arch): raise SourceMatcher.kt hotspot budget for call-site ranking"
```

---

## Task 6: Render the most-likely marker in the console

**Files:**
- Modify: `fixthis-mcp/src/main/console/presentation/annotationDetailView.js:19-24`

- [ ] **Step 1: Update the call-site formatter to label the most-likely entry**

Replace `sourceCandidateCallSites`:

```javascript
            function sourceCandidateCallSites(candidate) {
              const sites = (candidate && candidate.callSites) || [];
              return sites
                .map(site => {
                  const location = site.line == null ? site.file : site.file + ':' + site.line;
                  return site.mostLikely ? location + ' (most likely)' : location;
                })
                .filter(Boolean);
            }
```

The existing row builder at lines 81-84 (`'Shared component used at'`) needs no change — it renders the joined, already-ordered list.

- [ ] **Step 2: Rebundle the console assets**

Run: `node scripts/build-console-assets.mjs`
Expected: writes `fixthis-mcp/src/main/resources/console/app.js`, `app.js.map`, and `console-build-meta.json` with no size-budget error.

- [ ] **Step 3: Verify the bundle**

Run: `node scripts/build-console-assets.mjs --check`
Expected: PASS (bundle matches source; under the raw/gzip budgets).

- [ ] **Step 4: Run the console JS harness**

Run: `npm run console:harness:test`
Expected: PASS (no regression in console rendering harness).

- [ ] **Step 5: Visual verification of the Evidence panel**

Per repo guidance (UI changes require visual confirmation, not just unit tests), drive the console with a shared-component handoff and confirm the "Shared component used at" row lists the ranked call sites with the most-likely entry labeled. Use the console reliability/browser harness or a manual `--console-assets-dir` run:

Run: `npm run console:browser:reliability`
Expected: PASS. Capture a screenshot of the Evidence panel showing the "(most likely)" suffix on the top call site and confirm the others render plainly. If the harness has no shared-component fixture, do a manual run (`bash scripts/restart-console.sh --console-assets-dir fixthis-mcp/src/main/console`) and screenshot the Evidence panel for a shared-component selection.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/presentation/annotationDetailView.js fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/main/resources/console/app.js.map fixthis-mcp/src/main/resources/console/console-build-meta.json
git commit -m "feat(console): label most-likely shared-component call site"
```

---

## Task 7: Add a fixture-lab non-goal guard for a reused component

**Files:**
- Modify: `fixtures/source-matching/manifest.json`

This case guards the core non-goal — a reused component definition must not resolve at HIGH confidence even with ranking — using the existing runtime-trust fixture mode (no harness changes required).

**Fixture choice (ENV_BLOCKER resolution, 2026-05-30):** the guard lives in the **local `fixthis-sample`** fixture, not the external `android/compose-samples` (Jetsnack/Reply) fixtures. External GitHub fixtures attach no source candidates at runtime, so `expectedTop3PathContains`/`expectedSourceConfidence` can never be observed there (only the confidence cap is observable). The local `fixthis-sample` runtime path *does* attach source candidates (see the existing `fixthis-sample-home-primary-runtime` case), so the full assertion set — top-3 path, source confidence, and the runtime confidence cap — can all be verified green without weakening any assertion. The local app's `StudioHeader` composable is defined once and invoked at 4 call sites with a stable `Modifier.testTag("comp:StudioHeader:root")`, making it a faithful reused-component target.

- [ ] **Step 1: Confirm the reused composable in the local sample app**

Run: `grep -rn "StudioHeader(" sample/src/main/java | grep -v "fun StudioHeader("`
Expected: 4 call sites (HomeScreen, ProjectScreen, DiagnosticsScreen, ReviewScreen). Confirm the definition in `sample/src/main/java/io/github/beyondwin/fixthis/sample/components/StudioHeader.kt` carries `Modifier.testTag("comp:StudioHeader:root")` so all call sites share a stable runtime selector.

- [ ] **Step 2: Add the runtime-trust case to the `fixthis-sample` fixture `cases` array**

Add to the `fixthis-sample` fixture (illustrative values shown — verify `expectedTop3PathContains` and the observed confidence by running Step 3, and set them to the actual observed values; do NOT weaken the cap):

```json
        {
          "id": "fixthis-sample-shared-header-medium-cap",
          "mode": "runtime-trust",
          "trustPurpose": "reused component definition (StudioHeader, 4 call sites) stays capped below HIGH even with call-site ranking",
          "runtimeTarget": { "testTag": "comp:StudioHeader:root" },
          "expectedTop3PathContains": "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/StudioHeader.kt",
          "expectedConfidence": "low-or-medium",
          "expectedSourceConfidence": "low-or-medium",
          "mustNotHighConfidence": true
        }
```

- [ ] **Step 3: Run the fixture lab for the new case**

Run: `node scripts/source-matching-fixtures.mjs --id fixthis-sample`
Expected: PASS — the new case asserts the reused `StudioHeader` target resolves at low-or-medium (never HIGH, enforced by `mustNotHighConfidence`); the existing `fixthis-sample` cases stay green. Set `expectedTop3PathContains` to the actually-observed candidate path if it differs from the definition file.

If `StudioHeader` unexpectedly resolves at HIGH, that is a REAL feature finding (the SHARED_COMPONENT cap is not firing for a testTag-precise reused component) — escalate it; do NOT weaken the `expectedConfidence`/`mustNotHighConfidence` assertion to force a pass. If the runtime observation cannot attach source candidates even locally, escalate rather than dropping `expectedSourceConfidence`.

- [ ] **Step 4: Commit**

```bash
git add fixtures/source-matching/manifest.json
git commit -m "test(fixtures): guard reused-component confidence cap under ranking"
```

---

## Task 8: Final verification and changelog

**Files:**
- Modify: `CHANGELOG.md` (Unreleased → Added)

- [ ] **Step 1: Run the full local Gradle matrix**

Run: `./gradlew test` (or the project's documented full matrix from `CONTRIBUTING.md` § Required local checks — include `assembleDebug` if listed)
Expected: PASS across `:fixthis-gradle-plugin`, `:fixthis-compose-core`, and `:fixthis-mcp`, including `ArchitectureHotspotBudgetTest` and the clean-architecture boundary ratchet.

- [ ] **Step 2: Run console asset + JS checks**

Run: `node scripts/build-console-assets.mjs --check && npm run console:harness:test`
Expected: PASS.

- [ ] **Step 3: Verify bridge-protocol impact (expected additive, no bump)**

The change adds only an optional `mostLikely` field to an existing call-site object and reorders an existing list. Per `docs/reference/bridge-protocol.md`, confirm this is additive and requires no `BridgeProtocol.VERSION` bump.

Run: `./gradlew :fixthis-mcp:test --tests "*BridgeProtocolVersionSyncTest*"`
Expected: PASS (version unchanged; the four mirrored constants stay equal).

If any wire-visible consumer treats the field as required, follow the bridge-protocol checklist instead of forcing the test.

- [ ] **Step 4: Whitespace check**

Run: `git diff --check`
Expected: no output.

- [ ] **Step 5: Update the changelog**

In `CHANGELOG.md` under `## Unreleased` → `### Added`, extend the existing shared-component entry with a sentence on ranking:

```markdown
- The shared-component call-site inventory (`sourceCandidates[].callSites`) is
  now ordered by a best-effort match between each call site's static context
  (enclosing composable name and string-literal arguments) and the selected
  node's evidence. The most plausible usage is listed first and flagged
  `mostLikely` when it clears the next site by a clear margin; the feedback
  console labels it "(most likely)". Confidence stays capped at medium and no
  precise-target claim is made — ties and zero-evidence selections keep the
  prior static `file:line` order.
```

- [ ] **Step 6: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs: changelog for shared-component call-site ranking"
```

---

## Self-Review Notes

- **Spec coverage:** Index-layer context capture (Task 1-2), core `mostLikely` model (Task 3), ranking with stable tiebreak + margin-gated mark (Task 4), confidence-stays-MEDIUM guard (Task 4 Step 5 + Task 7), console rendering + visual check (Task 6), fixture-lab non-goal guard (Task 7), bridge-protocol verification (Task 8 Step 3). All spec sections map to a task.
- **Non-goals honored:** confidence never raised (asserted in Task 4/7); caution text untouched; only additive fields/values; no `:fixthis-compose-core` dependency added (the new helper depends only on existing core models); ranking evidence limited to the two enumerated signals.
- **Type consistency:** `rankSharedComponentCallSites(callSiteSignalValues, selectionTokens)` and `selectionTokensFor(selectedNode, activityName)` are defined in Task 4 and consumed exactly as named in `SourceMatcher`. `mostLikely: Boolean = false` is consistent across `SourceLocationRef`, `SourceHintLocation`, and both mapper directions. `encodeSignalValue()` (Task 1) is the exact symbol called in Task 2.
- **Encoding/parse round-trip:** Task 1 emits `file:line` (no context) or `file:line\t{enclosing}\t{lit|lit}`; Task 4's `parseCallSiteSignal` reverses both forms and tolerates a missing line (`file` only). Literals are sanitized of `\t` and `|` at capture so the delimiters stay unambiguous.
```
