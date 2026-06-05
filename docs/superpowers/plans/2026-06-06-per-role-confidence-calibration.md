# Per-Role Edit-Surface Confidence Calibration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the three source-backed edit-surface roles (`COPY_OR_DATA`, `COMPONENT_DEFINITION`, `LAYOUT_OR_STYLE`) respond to evidence — promote above their default confidence ceiling under strong, unambiguous evidence and demote below it under ambiguity or weak anchoring.

**Architecture:** A new `EditSurfaceEvidence` value object derives named boolean signals once from a `SourceCandidate`. `EditSurfaceConfidencePolicy` reads those signals in three per-role private helpers that pick a ceiling (promote/demote), then applies the existing `cap(source, ceiling)` so the source candidate's own confidence stays an upper bound. No wire schema or serialization change — every signal comes from existing `SourceCandidate` fields.

**Tech Stack:** Kotlin, kotlin.test (JUnit), Gradle (`:fixthis-mcp` module), detekt, spotless. All work is in the `fixthis-mcp` module.

Design spec: `docs/superpowers/specs/2026-06-06-per-role-confidence-calibration-design.md`

---

## File Structure

- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceEvidence.kt` — signal extraction value object (single responsibility: interpret a `SourceCandidate` into booleans).
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicy.kt` — per-role promote/demote ceilings.
- Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceEvidenceTest.kt` — unit tests for signal extraction.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicyTest.kt` — role × condition matrix.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt` — thread `evidenceStrength` + `riskFlags` through the corpus fixture candidate.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt` — stable-coverage ID list, new exact-confidence assertion, budget.
- Modify `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json` — three new promotion cases.

### Reference facts (verified against current code)

- `SourceCandidate` (core) fields used: `confidence: SelectionConfidence`, `matchReasons: List<String>` (wire labels), `riskFlags: List<SourceCandidateRisk>`, `evidenceStrength: SourceEvidenceStrength?`, `ownerComposable: String?`.
- `SelectionConfidence` ordering (low→high): `NONE, LOW, MEDIUM, HIGH`.
- `SourceEvidenceStrength` values: `STRONG, MEDIUM, WEAK`.
- `SourceCandidateRisk` values include: `AMBIGUOUS, SHARED_COMPONENT, TEXT_ONLY, NEARBY_ONLY`.
- `SourceMatchReason` is `internal` to core, so the mcp module matches raw wire-label strings (existing pattern: `EditSurfaceCandidateService.kt:101`). Exact-copy wire labels: `"selected text"`, `"selected stringResource"`, `"selected resolved stringResource"`, `"selected contentDescription"`.
- `EditSurfaceConfidencePolicy.score(role, sourceCandidate)` is called by `EditSurfaceCandidateService.toEditSurface` with the raw `SourceCandidate` (so the policy sees `evidenceStrength`/`riskFlags` directly).
- Corpus fixture `HandoffEvaluationSourceCandidate.toSourceCandidate()` currently DROPS `evidenceStrength` and `riskFlags` (defaults to `null` / empty), so existing corpus cases will NOT promote after this change — this is the intended no-regression behavior.

---

## Task 1: `EditSurfaceEvidence` signal extraction

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceEvidence.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceEvidenceTest.kt`

- [ ] **Step 1: Write the failing test**

Create `EditSurfaceEvidenceTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditSurfaceEvidenceTest {
    private fun candidate(
        confidence: SelectionConfidence = SelectionConfidence.MEDIUM,
        matchReasons: List<String> = emptyList(),
        riskFlags: List<SourceCandidateRisk> = emptyList(),
        evidenceStrength: SourceEvidenceStrength? = null,
        ownerComposable: String? = null,
    ) = SourceCandidate(
        file = "Foo.kt",
        line = 1,
        score = 0.5,
        confidence = confidence,
        matchReasons = matchReasons,
        riskFlags = riskFlags,
        evidenceStrength = evidenceStrength,
        ownerComposable = ownerComposable,
    )

    @Test
    fun `null candidate yields all-false signals`() {
        val evidence = EditSurfaceEvidence.from(null)
        assertFalse(evidence.strong)
        assertFalse(evidence.exactCopyMatch)
        assertFalse(evidence.ambiguous)
        assertFalse(evidence.proximityOnly)
        assertFalse(evidence.shared)
        assertFalse(evidence.confidentCallSite)
    }

    @Test
    fun `strong is true only for STRONG evidence strength`() {
        assertTrue(EditSurfaceEvidence.from(candidate(evidenceStrength = SourceEvidenceStrength.STRONG)).strong)
        assertFalse(EditSurfaceEvidence.from(candidate(evidenceStrength = SourceEvidenceStrength.MEDIUM)).strong)
        assertFalse(EditSurfaceEvidence.from(candidate(evidenceStrength = null)).strong)
    }

    @Test
    fun `exactCopyMatch matches selected-literal wire labels but not nearby`() {
        assertTrue(EditSurfaceEvidence.from(candidate(matchReasons = listOf("selected text"))).exactCopyMatch)
        assertTrue(EditSurfaceEvidence.from(candidate(matchReasons = listOf("selected stringResource"))).exactCopyMatch)
        assertTrue(
            EditSurfaceEvidence.from(candidate(matchReasons = listOf("selected resolved stringResource"))).exactCopyMatch,
        )
        assertTrue(
            EditSurfaceEvidence.from(candidate(matchReasons = listOf("selected contentDescription"))).exactCopyMatch,
        )
        assertFalse(EditSurfaceEvidence.from(candidate(matchReasons = listOf("nearby text"))).exactCopyMatch)
    }

    @Test
    fun `ambiguous proximityOnly and shared read risk flags`() {
        assertTrue(EditSurfaceEvidence.from(candidate(riskFlags = listOf(SourceCandidateRisk.AMBIGUOUS))).ambiguous)
        assertTrue(EditSurfaceEvidence.from(candidate(riskFlags = listOf(SourceCandidateRisk.NEARBY_ONLY))).proximityOnly)
        assertTrue(EditSurfaceEvidence.from(candidate(riskFlags = listOf(SourceCandidateRisk.TEXT_ONLY))).proximityOnly)
        assertTrue(EditSurfaceEvidence.from(candidate(riskFlags = listOf(SourceCandidateRisk.SHARED_COMPONENT))).shared)
    }

    @Test
    fun `confidentCallSite requires HIGH confidence resolved owner and no ambiguity`() {
        assertTrue(
            EditSurfaceEvidence.from(
                candidate(confidence = SelectionConfidence.HIGH, ownerComposable = "Foo"),
            ).confidentCallSite,
        )
        assertFalse(
            EditSurfaceEvidence.from(
                candidate(confidence = SelectionConfidence.MEDIUM, ownerComposable = "Foo"),
            ).confidentCallSite,
        )
        assertFalse(
            EditSurfaceEvidence.from(
                candidate(confidence = SelectionConfidence.HIGH, ownerComposable = null),
            ).confidentCallSite,
        )
        assertFalse(
            EditSurfaceEvidence.from(
                candidate(
                    confidence = SelectionConfidence.HIGH,
                    ownerComposable = "Foo",
                    riskFlags = listOf(SourceCandidateRisk.AMBIGUOUS),
                ),
            ).confidentCallSite,
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.EditSurfaceEvidenceTest"`
Expected: FAIL — `EditSurfaceEvidence` is unresolved (does not compile yet).

- [ ] **Step 3: Write the implementation**

Create `EditSurfaceEvidence.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength

internal data class EditSurfaceEvidence(
    val strong: Boolean,
    val exactCopyMatch: Boolean,
    val ambiguous: Boolean,
    val proximityOnly: Boolean,
    val shared: Boolean,
    val confidentCallSite: Boolean,
) {
    companion object {
        private val EXACT_COPY_REASONS = setOf(
            "selected text",
            "selected stringResource",
            "selected resolved stringResource",
            "selected contentDescription",
        )

        fun from(candidate: SourceCandidate?): EditSurfaceEvidence {
            if (candidate == null) {
                return EditSurfaceEvidence(
                    strong = false,
                    exactCopyMatch = false,
                    ambiguous = false,
                    proximityOnly = false,
                    shared = false,
                    confidentCallSite = false,
                )
            }
            val flags = candidate.riskFlags
            val ambiguous = SourceCandidateRisk.AMBIGUOUS in flags
            return EditSurfaceEvidence(
                strong = candidate.evidenceStrength == SourceEvidenceStrength.STRONG,
                exactCopyMatch = candidate.matchReasons.any { it in EXACT_COPY_REASONS },
                ambiguous = ambiguous,
                proximityOnly = SourceCandidateRisk.NEARBY_ONLY in flags || SourceCandidateRisk.TEXT_ONLY in flags,
                shared = SourceCandidateRisk.SHARED_COMPONENT in flags,
                confidentCallSite = candidate.confidence == SelectionConfidence.HIGH &&
                    candidate.ownerComposable != null &&
                    !ambiguous,
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.EditSurfaceEvidenceTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceEvidence.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceEvidenceTest.kt
git commit -m "feat(mcp): add EditSurfaceEvidence signal extraction for per-role confidence"
```

---

## Task 2: `COPY_OR_DATA` evidence-conditional ceiling

Default MEDIUM. Promote → HIGH when `strong && exactCopyMatch && !ambiguous`. Demote → LOW when `ambiguous || proximityOnly` (demotion wins ties).

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicy.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicyTest.kt`

- [ ] **Step 1: Write the failing tests**

In `EditSurfaceConfidencePolicyTest.kt`, first extend the `candidate(...)` helper to accept the new signals (replace the existing helper at lines 10-20):

```kotlin
    private fun candidate(
        confidence: SelectionConfidence,
        reasons: List<String> = listOf("selected owner composable"),
        riskFlags: List<io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk> = emptyList(),
        evidenceStrength: io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength? = null,
        ownerComposable: String? = null,
    ) = SourceCandidate(
        file = "Foo.kt",
        line = 10,
        score = 0.8,
        matchedTerms = emptyList(),
        matchReasons = reasons,
        confidence = confidence,
        riskFlags = riskFlags,
        evidenceStrength = evidenceStrength,
        ownerComposable = ownerComposable,
    )
```

Then add these tests:

```kotlin
    @Test
    fun `copy or data promotes to high under strong exact-literal evidence`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COPY_OR_DATA,
            sourceCandidate = candidate(
                SelectionConfidence.HIGH,
                reasons = listOf("selected text"),
                evidenceStrength = io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength.STRONG,
            ),
        )
        assertEquals(SelectionConfidence.HIGH, result.confidence)
        assertTrue(result.basis.contains("exact", ignoreCase = true))
    }

    @Test
    fun `copy or data demotes to low under ambiguity even with exact literal`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COPY_OR_DATA,
            sourceCandidate = candidate(
                SelectionConfidence.HIGH,
                reasons = listOf("selected text"),
                evidenceStrength = io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength.STRONG,
                riskFlags = listOf(io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.AMBIGUOUS),
            ),
        )
        assertEquals(SelectionConfidence.LOW, result.confidence)
    }

    @Test
    fun `copy or data demotes to low under proximity-only evidence`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COPY_OR_DATA,
            sourceCandidate = candidate(
                SelectionConfidence.MEDIUM,
                reasons = listOf("nearby text"),
                riskFlags = listOf(io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.NEARBY_ONLY),
            ),
        )
        assertEquals(SelectionConfidence.LOW, result.confidence)
    }
```

Note: the existing test `copy or data with a string-resource match is medium` must remain green — its candidate has no `evidenceStrength`, so `strong` is false and it stays MEDIUM.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.EditSurfaceConfidencePolicyTest"`
Expected: FAIL — the new COPY_OR_DATA cases currently yield MEDIUM/MEDIUM (flat cap), so the HIGH and LOW assertions fail.

- [ ] **Step 3: Implement the COPY_OR_DATA helper**

In `EditSurfaceConfidencePolicy.kt`, compute evidence once and delegate the COPY_OR_DATA branch. Add `val evidence = EditSurfaceEvidence.from(sourceCandidate)` right after the `reasons` line in `score`, then replace the `COPY_OR_DATA` branch with a delegation and add the private helper:

```kotlin
            EditSurfaceRoleDto.COPY_OR_DATA -> copyOrData(source, evidence, reasons)
```

```kotlin
    private fun copyOrData(
        source: SelectionConfidence,
        evidence: EditSurfaceEvidence,
        reasons: List<String>,
    ): EditSurfaceConfidenceResult {
        val (ceiling, label) = when {
            evidence.ambiguous || evidence.proximityOnly -> SelectionConfidence.LOW to "nearby only — verify"
            evidence.strong && evidence.exactCopyMatch -> SelectionConfidence.HIGH to "exact literal"
            else -> SelectionConfidence.MEDIUM to "matched copy/data"
        }
        return EditSurfaceConfidenceResult(cap(source, ceiling), "$label${reasonSuffix(reasons)}")
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.EditSurfaceConfidencePolicyTest"`
Expected: PASS (including the unchanged string-resource-is-medium test).

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicy.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicyTest.kt
git commit -m "feat(mcp): COPY_OR_DATA confidence promotes on exact literal, demotes on ambiguity"
```

---

## Task 3: `COMPONENT_DEFINITION` evidence-conditional ceiling

Default MEDIUM. Promote → HIGH when `!shared && strong && !ambiguous`. Demote → LOW when `ambiguous`.

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicy.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicyTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `EditSurfaceConfidencePolicyTest.kt`:

```kotlin
    @Test
    fun `component definition promotes to high for non-shared strong definition`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COMPONENT_DEFINITION,
            sourceCandidate = candidate(
                SelectionConfidence.HIGH,
                evidenceStrength = io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength.STRONG,
            ),
        )
        assertEquals(SelectionConfidence.HIGH, result.confidence)
    }

    @Test
    fun `component definition stays medium for shared definition even when strong`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COMPONENT_DEFINITION,
            sourceCandidate = candidate(
                SelectionConfidence.HIGH,
                evidenceStrength = io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength.STRONG,
                riskFlags = listOf(io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.SHARED_COMPONENT),
            ),
        )
        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
        assertTrue(result.basis.contains("call site", ignoreCase = true))
    }

    @Test
    fun `component definition demotes to low under ambiguity`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COMPONENT_DEFINITION,
            sourceCandidate = candidate(
                SelectionConfidence.HIGH,
                evidenceStrength = io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength.STRONG,
                riskFlags = listOf(io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.AMBIGUOUS),
            ),
        )
        assertEquals(SelectionConfidence.LOW, result.confidence)
    }
```

Note: the existing test `component definition stays capped at medium with a shared-definition basis` uses a candidate with no `evidenceStrength` (strong=false) → default MEDIUM branch, basis still contains "call site". It stays green.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.EditSurfaceConfidencePolicyTest"`
Expected: FAIL — non-shared strong case yields MEDIUM (flat cap), ambiguous case yields MEDIUM.

- [ ] **Step 3: Implement the COMPONENT_DEFINITION helper**

In `EditSurfaceConfidencePolicy.kt`, replace the `COMPONENT_DEFINITION` branch with a delegation and add the helper:

```kotlin
            EditSurfaceRoleDto.COMPONENT_DEFINITION -> componentDefinition(source, evidence)
```

```kotlin
    private fun componentDefinition(
        source: SelectionConfidence,
        evidence: EditSurfaceEvidence,
    ): EditSurfaceConfidenceResult {
        val (ceiling, label) = when {
            evidence.ambiguous -> SelectionConfidence.LOW to "ambiguous owner — verify before editing"
            !evidence.shared && evidence.strong -> SelectionConfidence.HIGH to "single-owner definition"
            else -> SelectionConfidence.MEDIUM to "editing it changes every call site"
        }
        return EditSurfaceConfidenceResult(cap(source, ceiling), "shared component definition: $label")
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.EditSurfaceConfidencePolicyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicy.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicyTest.kt
git commit -m "feat(mcp): COMPONENT_DEFINITION confidence promotes for single-owner, demotes on ambiguity"
```

---

## Task 4: `LAYOUT_OR_STYLE` evidence-conditional ceiling

Default LOW (already the floor). Promote → MEDIUM when `confidentCallSite` (which already requires HIGH confidence, a resolved owner, and no ambiguity). Never HIGH.

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicy.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicyTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `EditSurfaceConfidencePolicyTest.kt`:

```kotlin
    @Test
    fun `layout or style promotes to medium for a confident call site`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.LAYOUT_OR_STYLE,
            sourceCandidate = candidate(SelectionConfidence.HIGH, ownerComposable = "QueueScreen"),
        )
        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
    }

    @Test
    fun `layout or style stays low without a confident call site`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.LAYOUT_OR_STYLE,
            sourceCandidate = candidate(SelectionConfidence.MEDIUM, ownerComposable = "QueueScreen"),
        )
        assertEquals(SelectionConfidence.LOW, result.confidence)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.EditSurfaceConfidencePolicyTest"`
Expected: FAIL — confident-call-site case yields LOW (flat cap at LOW).

- [ ] **Step 3: Implement the LAYOUT_OR_STYLE helper**

In `EditSurfaceConfidencePolicy.kt`, replace the `LAYOUT_OR_STYLE` branch with a delegation and add the helper:

```kotlin
            EditSurfaceRoleDto.LAYOUT_OR_STYLE -> layoutOrStyle(source, evidence)
```

```kotlin
    private fun layoutOrStyle(
        source: SelectionConfidence,
        evidence: EditSurfaceEvidence,
    ): EditSurfaceConfidenceResult {
        val ceiling = if (evidence.confidentCallSite) SelectionConfidence.MEDIUM else SelectionConfidence.LOW
        return EditSurfaceConfidenceResult(cap(source, ceiling), "layout/style edit applies at the call site")
    }
```

- [ ] **Step 4: Run the full policy + service suite to verify no regressions**

Run: `./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.EditSurfaceConfidencePolicyTest" --tests "io.github.beyondwin.fixthis.mcp.session.EditSurfaceCandidateServiceTest"`
Expected: PASS — confirms the three role helpers compose correctly and the candidate service still builds surfaces.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicy.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicyTest.kt
git commit -m "feat(mcp): LAYOUT_OR_STYLE confidence promotes to medium for a confident call site"
```

---

## Task 5: Failing end-to-end corpus assertion for promotion cases

The existing corpus gates only check confidence is `<=` `maxConfidence`, so they cannot prove promotion fired. Add an assertion that the three promotion cases reach their exact expected confidence. This test fails first because the cases do not exist yet.

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `HandoffEvaluationCorpusTest.kt`:

```kotlin
    @Test
    fun promotionCasesReachExpectedConfidence() {
        val corpus = HandoffEvaluationFixtures.loadCorpus()
        val expected = mapOf(
            "strong-copy-high" to SelectionConfidence.HIGH,
            "single-owner-component-high" to SelectionConfidence.HIGH,
            "confident-layout-medium" to SelectionConfidence.MEDIUM,
        )
        val failures = expected.mapNotNull { (id, wanted) ->
            val case = corpus.cases.singleOrNull { it.id == id }
                ?: return@mapNotNull "$id: case not found in corpus"
            val top = HandoffEvaluationFixtures.annotationFor(case).editSurfaceCandidates.firstOrNull()
                ?: return@mapNotNull "$id: produced no edit-surface candidate"
            if (top.confidence != wanted) "$id: expected $wanted, got ${top.confidence}" else null
        }
        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.HandoffEvaluationCorpusTest.promotionCasesReachExpectedConfidence"`
Expected: FAIL — "case not found in corpus" for all three IDs.

- [ ] **Step 3: Commit the failing test**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt
git commit -m "test(mcp): assert promotion corpus cases reach exact expected confidence"
```

---

## Task 6: Thread evidence into the corpus fixture and add promotion cases

Make the corpus fixture carry `evidenceStrength` and `riskFlags`, then add the three cases so Task 5's assertion passes. Update the stable-coverage ID list and the handoff budget.

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt`
- Modify: `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`

- [ ] **Step 1: Extend the fixture candidate model to carry the new signals**

In `HandoffEvaluationFixtures.kt`, add two fields to `HandoffEvaluationSourceCandidate` (currently lines 73-83):

```kotlin
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
```

Then thread them through `toSourceCandidate()` (currently lines 253-262) and add the imports:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength
```

```kotlin
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
```

- [ ] **Step 2: Add the three promotion cases to the corpus JSON**

In `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`, insert these three case objects into the `cases` array immediately after the `strong-call-site-high` case (before the closing `]`). Add a comma after the `strong-call-site-high` closing brace.

```json
    {
      "id": "strong-copy-high",
      "comment": "Rename this label to Saved",
      "targetType": "node",
      "selectedText": ["Save"],
      "selectedRole": "Button",
      "selectedTestTag": "comp:SaveButton:label",
      "sourceCandidates": [
        {
          "file": "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/ProfileScreen.kt",
          "line": 40,
          "score": 95,
          "confidence": "HIGH",
          "evidenceStrength": "STRONG",
          "matchReasons": ["selected text"],
          "matchedTerms": ["Save"],
          "ownerComposable": "ProfileScreen"
        }
      ],
      "expectedRole": "COPY_OR_DATA",
      "expectedTop3Contains": "ProfileScreen.kt",
      "allowHighConfidence": true,
      "correctness": {
        "category": "copy-or-data",
        "ownerContains": "ProfileScreen.kt",
        "expectedRole": "COPY_OR_DATA",
        "maxConfidence": "HIGH",
        "requiredCautions": [],
        "releaseCritical": true,
        "promptUsabilityRequired": true
      }
    },
    {
      "id": "single-owner-component-high",
      "comment": "Make this card background green",
      "targetType": "node",
      "selectedText": ["Summary"],
      "selectedTestTag": "comp:SummaryCard:summary",
      "sourceCandidates": [
        {
          "file": "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/SummaryCard.kt",
          "line": 27,
          "score": 90,
          "confidence": "HIGH",
          "evidenceStrength": "STRONG",
          "matchReasons": ["selected testTag convention composable"],
          "matchedTerms": ["SummaryCard"],
          "ownerComposable": "SummaryCard"
        }
      ],
      "expectedRole": "COMPONENT_DEFINITION",
      "expectedTop3Contains": "SummaryCard.kt",
      "allowHighConfidence": true,
      "correctness": {
        "category": "shared-component",
        "ownerContains": "SummaryCard.kt",
        "expectedRole": "COMPONENT_DEFINITION",
        "maxConfidence": "HIGH",
        "requiredCautions": [],
        "releaseCritical": true,
        "promptUsabilityRequired": true
      }
    },
    {
      "id": "confident-layout-medium",
      "comment": "Reduce the bottom spacing by 8dp",
      "targetType": "node",
      "selectedText": ["Priority feedback"],
      "selectedTestTag": "comp:FeedbackCard:priority",
      "sourceCandidates": [
        {
          "file": "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/QueueScreen.kt",
          "line": 76,
          "score": 91,
          "confidence": "HIGH",
          "matchReasons": ["layout renderer context"],
          "matchedTerms": ["Priority feedback"],
          "ownerComposable": "QueueScreen"
        }
      ],
      "expectedRole": "LAYOUT_OR_STYLE",
      "expectedTop3Contains": "QueueScreen.kt",
      "allowHighConfidence": false,
      "correctness": {
        "category": "layout-or-style",
        "ownerContains": "QueueScreen.kt",
        "expectedRole": "LAYOUT_OR_STYLE",
        "maxConfidence": "MEDIUM",
        "requiredCautions": [],
        "releaseCritical": true,
        "promptUsabilityRequired": true
      }
    }
```

- [ ] **Step 3: Update the stable-coverage ID list**

In `HandoffEvaluationCorpusTest.kt`, in `corpusHasStableV06Coverage`, append the three IDs to the expected list after `"strong-call-site-high"`:

```kotlin
                "strong-call-site-high",
                "strong-copy-high",
                "single-owner-component-high",
                "confident-layout-medium",
```

- [ ] **Step 4: Run the promotion assertion + coverage tests**

Run: `./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.HandoffEvaluationCorpusTest"`
Expected: `promotionCasesReachExpectedConfidence` and `corpusHasStableV06Coverage` PASS.

If `corpusItemsRenderCompactHandoffWithoutDroppingRoles` FAILS on the budget assertion, it prints the actual length (e.g. "v0.6 corpus handoff is N chars; budget is 7200"). Update the budget on line ~96 to the next round number above `N` (e.g. if N is 7640, set 7800):

```kotlin
        assertTrue(markdown.length <= 7800, "v0.6 corpus handoff is ${markdown.length} chars; budget is 7800")
```

Re-run until green. Do not lower other cases to fit; the budget is allowed to grow for added coverage (this mirrors K1 raising it from 6500 to 7200).

- [ ] **Step 5: Run the entire corpus + service test classes to confirm no regressions**

Run: `./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.session.HandoffEvaluationCorpusTest" --tests "io.github.beyondwin.fixthis.mcp.session.EditSurfaceCandidateServiceTest"`
Expected: PASS. In particular `v06CorpusMeetsEditSurfaceRegressionGate`, `highConfidenceCasesPinExpectedOwnerAsTopCandidate`, and `corpusV2HardFailuresCatchTrustBreakingRegressions` stay green (existing cases keep their confidence because they carry no `evidenceStrength`).

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt \
        fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt
git commit -m "test(mcp): corpus cases exercise per-role confidence promotion end-to-end"
```

---

## Task 7: Full verification (module tests, detekt, spotless)

**Files:** none (verification only).

- [ ] **Step 1: Run the full mcp module test suite**

Run: `./gradlew :fixthis-mcp:test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Run detekt and spotless**

Run: `./gradlew detekt spotlessCheck`
Expected: BUILD SUCCESSFUL. If detekt flags `EditSurfaceConfidencePolicy` (e.g. method length now that branches delegate to helpers, or `ReturnCount`), resolve by keeping each helper single-return — the helpers already return one expression, so no suppression should be needed. If spotless reports formatting, run `./gradlew spotlessApply` and re-commit.

- [ ] **Step 3: Run the broader required local checks**

Run the full Gradle matrix and `git diff --check` per `CONTRIBUTING.md#required-local-checks`. This change is mcp-only and touches no console assets, bridge protocol, or wire schema, so the console/bridge checks should be unaffected.

- [ ] **Step 4: Commit any formatting fixes**

```bash
git add -A
git commit -m "style(mcp): apply spotless/detekt fixes for per-role confidence calibration"
```

(Skip this commit if Steps 1-3 produced no changes.)

---

## Self-Review Notes

- **Spec coverage:** Evidence signals (Task 1) ✓; COPY_OR_DATA promote/demote (Task 2) ✓; COMPONENT_DEFINITION promote/demote (Task 3) ✓; LAYOUT_OR_STYLE promote (Task 4) ✓; demotion-before-promotion precedence (Task 2 ambiguity-wins test) ✓; basis visibility (Tasks 2-3 basis asserts) ✓; corpus end-to-end (Tasks 5-6) ✓; regression guard + no wire change (Task 6 Step 5, Task 7) ✓; VISUAL_AREA/INTEROP_RISK untouched (their policy branches are not modified) ✓.
- **Type consistency:** `EditSurfaceEvidence.from(SourceCandidate?)` returns `EditSurfaceEvidence`; helpers `copyOrData`/`componentDefinition`/`layoutOrStyle` return `EditSurfaceConfidenceResult` and use the existing `cap(...)` and `reasonSuffix(...)`. `SourceCandidate`, `SourceCandidateRisk`, `SourceEvidenceStrength`, `SelectionConfidence` are the actual core type names.
- **No placeholders:** every step has concrete code and exact commands.
