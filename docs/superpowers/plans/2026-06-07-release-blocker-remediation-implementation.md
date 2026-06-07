# Release Blocker Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every next-release blocker found after `v1.1.0`: shared-component runtime source-trust overconfidence, detekt baseline budget failure, stale release claims, and final release gate failures.

**Architecture:** Keep the fix local. `SourceRiskClassifier` owns confidence ceilings and must no longer let ranked shared-component call-site hints promote the reused definition candidate to HIGH. `SourceMatcher` continues to emit ranked `callSites` metadata. `SourceIndex.kt` moves source-signal weight literals into named constants so detekt ratchet passes without raising the budget.

**Tech Stack:** Kotlin, Gradle, detekt, Node release scripts, runtime source-matching fixtures, Android AVD for strict runtime checks.

**Spec:** `docs/superpowers/specs/2026-06-07-release-blocker-remediation-detailed-spec.md`

---

## File Structure

- Modify `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifier.kt` - replace ordinal-based `capAt` with a true ceiling helper and remove shared-component HIGH relaxation.
- Modify `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt` - stop passing `confidentCallSite` into the cap decision while preserving ranked call-site output.
- Modify `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifierTest.kt` - pin the new shared-definition ceiling.
- Modify `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherSharedComponentTest.kt` - keep `recommendedEditSite` but expect MEDIUM confidence for the shared definition.
- Modify `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt` - update older K2 expectations that assert HIGH for shared definitions.
- Modify `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt` - introduce named weight constants for source signals.
- Modify `config/detekt/baseline-fixthis-compose-core.xml` only if detekt leaves stale `MagicNumber:SourceIndex.kt` IDs after the constants are introduced.
- Modify `config/detekt/baseline-budget.json` only if the actual compose-core baseline count drops below 57. Do not increase it.
- Modify `CHANGELOG.md`, `docs/releases/unreleased.md`, `docs/product/roadmap.md`, and `docs/superpowers/specs/2026-06-04-source-matching-refinement-design.md` - align current release claims with the corrected trust contract.

---

## Task 1: Reproduce and pin the shared-component confidence contract

**Files:**
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifierTest.kt`
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherSharedComponentTest.kt`
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`

- [ ] **Step 1: Confirm the runtime failure before touching production code**

Run:

```bash
npm run source-matching:fixtures:runtime -- --strict
```

Expected before fix: FAIL with `fixthis-sample-shared-header-medium-cap` and `fixthis-sample-shared-header-recommended-edit-site` reporting `overconfident`, `unexpected_high_confidence`, and `weak_evidence_promoted`.

- [ ] **Step 2: Change `SourceRiskClassifierTest` to pin MEDIUM for shared definitions**

Replace the current `confidentSingleCallSiteAllowsHighDespiteSharedComponent` test with this contract:

```kotlin
    @Test
    fun sharedComponentDefinitionCapsAtMediumEvenWithRecommendedCallSite() {
        val result = SourceRiskClassifier.applyCaps(
            sharedProfile,
            SelectionConfidence.HIGH,
        )

        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
        assertTrue(result.flags.contains(SourceCandidateRisk.SHARED_COMPONENT))
    }
```

Keep `ambiguousSharedComponentStillCapsAtMedium()` and update its call to the new signature if needed:

```kotlin
        val result = SourceRiskClassifier.applyCaps(
            sharedProfile,
            SelectionConfidence.HIGH,
        )
```

- [ ] **Step 3: Run the focused classifier test and verify RED**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceRiskClassifierTest" --no-daemon
```

Expected before production fix: FAIL because the current code returns HIGH when the old `confidentCallSite` path is used or because the test no longer matches the current signature after Step 2.

- [ ] **Step 4: Update `SourceMatcherSharedComponentTest` expectations**

Edit the test currently named `confidentCallSiteYieldsHigh`. Rename it to:

```kotlin
fun confidentCallSiteKeepsRecommendedEditSiteButDefinitionStaysMedium()
```

Change the assertion block to:

```kotlin
        assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
        assertTrue(candidate.riskFlags.contains(SourceCandidateRisk.SHARED_COMPONENT))
        assertEquals(1, candidate.callSites.count { it.recommendedEditSite })
```

Update the class comment to say:

```kotlin
 * The confident fixture must still expose exactly one `recommendedEditSite`,
 * but the shared definition candidate remains MEDIUM because the edit site is
 * verification context, not exact ownership.
```

- [ ] **Step 5: Update older `SourceMatcherTest` K2 assertions**

Search:

```bash
rg -n "K2|shared-component cap|relaxed|SelectionConfidence.HIGH|recommendedEditSite" \
  fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt
```

For any shared-component definition case that currently says a recommended edit site relaxes the cap to HIGH, change the assertion to MEDIUM and keep the `recommendedEditSite` assertion. The desired shape is:

```kotlin
assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
assertTrue(SourceCandidateRisk.SHARED_COMPONENT in candidate.riskFlags)
assertEquals(1, candidate.callSites.count { it.recommendedEditSite })
```

Do not change non-shared strong evidence tests that legitimately expect HIGH.

- [ ] **Step 6: Run focused tests and keep the failure local**

Run:

```bash
./gradlew :fixthis-compose-core:test \
  --tests "*SourceRiskClassifierTest" \
  --tests "*SourceMatcherSharedComponentTest" \
  --tests "*SourceMatcherTest" \
  --no-daemon
```

Expected before production fix: FAIL only on confidence expectations affected by the old cap behavior. If unrelated tests fail, stop and inspect before changing production code.

- [ ] **Step 7: Commit tests only if working in small commits**

If using task-by-task commits:

```bash
git add fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifierTest.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherSharedComponentTest.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt
git commit -m "test(core): pin shared definitions below high confidence"
```

---

## Task 2: Implement true confidence ceilings for shared definitions

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifier.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`

- [ ] **Step 1: Replace `SourceRiskClassifier.applyCaps` signature**

In `SourceRiskClassifier.kt`, change:

```kotlin
    fun applyCaps(
        profile: EvidenceProfile,
        baseConfidence: SelectionConfidence,
        confidentCallSite: Boolean = false,
    ): Result {
```

to:

```kotlin
    fun applyCaps(
        profile: EvidenceProfile,
        baseConfidence: SelectionConfidence,
    ): Result {
```

- [ ] **Step 2: Replace the shared-component cap branch**

Change:

```kotlin
        if (profile.hasSharedComponentDefinition) {
            flags.add(SourceCandidateRisk.SHARED_COMPONENT)
            if (!confidentCallSite) {
                confidence = capAt(confidence, SelectionConfidence.MEDIUM)
            }
        }
```

to:

```kotlin
        if (profile.hasSharedComponentDefinition) {
            flags.add(SourceCandidateRisk.SHARED_COMPONENT)
            confidence = ceiling(confidence, SelectionConfidence.MEDIUM)
        }
```

- [ ] **Step 3: Replace every risk ceiling call**

Within `SourceRiskClassifier.kt`, replace every `capAt(confidence, ...)` call with `ceiling(confidence, ...)`.

The full changed method should read like this:

```kotlin
    fun applyCaps(
        profile: EvidenceProfile,
        baseConfidence: SelectionConfidence,
    ): Result {
        val flags = mutableListOf<SourceCandidateRisk>()
        var confidence = baseConfidence

        when {
            profile.isArbitraryLiteralOnly -> {
                flags.add(SourceCandidateRisk.ARBITRARY_LITERAL)
                confidence = ceiling(confidence, SelectionConfidence.LOW)
            }
            profile.isUntypedFallbackOnly -> {
                flags.add(SourceCandidateRisk.UNTYPED_FALLBACK)
                confidence = ceiling(confidence, SelectionConfidence.LOW)
            }
            profile.isNearbyOnly -> {
                flags.add(SourceCandidateRisk.NEARBY_ONLY)
                confidence = ceiling(confidence, SelectionConfidence.LOW)
            }
            profile.isActivityOnly -> {
                flags.add(SourceCandidateRisk.ACTIVITY_ONLY)
                confidence = ceiling(confidence, SelectionConfidence.LOW)
            }
            profile.hasArbitraryLiteral && !profile.hasSelectedTestTag && !profile.hasStrictCompTag -> {
                flags.add(SourceCandidateRisk.ARBITRARY_LITERAL)
                confidence = ceiling(confidence, SelectionConfidence.MEDIUM)
            }
            profile.isTextOnly -> {
                flags.add(SourceCandidateRisk.TEXT_ONLY)
                confidence = ceiling(confidence, SelectionConfidence.MEDIUM)
            }
        }

        if (profile.hasSharedComponentDefinition) {
            flags.add(SourceCandidateRisk.SHARED_COMPONENT)
            confidence = ceiling(confidence, SelectionConfidence.MEDIUM)
        }

        return Result(confidence, flags)
    }
```

- [ ] **Step 4: Add explicit confidence rank helper**

Replace the old `capAt` helper with:

```kotlin
    private val confidenceRank = mapOf(
        SelectionConfidence.NONE to 0,
        SelectionConfidence.LOW to 1,
        SelectionConfidence.MEDIUM to 2,
        SelectionConfidence.HIGH to 3,
    )

    private fun ceiling(
        current: SelectionConfidence,
        max: SelectionConfidence,
    ): SelectionConfidence {
        val currentRank = confidenceRank.getValue(current)
        val maxRank = confidenceRank.getValue(max)
        return if (currentRank <= maxRank) current else max
    }
```

This avoids relying on enum ordinal order.

- [ ] **Step 5: Stop passing `confidentCallSite` from `SourceMatcher`**

In `SourceMatcher.kt`, remove:

```kotlin
        val confidentCallSite = callSites.count { it.recommendedEditSite } == 1 &&
            (profile.hasSelectedOwnerFunction || profile.hasStrictCompTag || profile.hasSelectedTestTag)
        val capInfo = SourceRiskClassifier.applyCaps(profile, baseConfidence, confidentCallSite)
```

Replace with:

```kotlin
        val capInfo = SourceRiskClassifier.applyCaps(profile, baseConfidence)
```

Do not remove `callSites`; it is still used in the returned `SourceCandidate`.

- [ ] **Step 6: Run focused tests**

Run:

```bash
./gradlew :fixthis-compose-core:test \
  --tests "*SourceRiskClassifierTest" \
  --tests "*SourceMatcherSharedComponentTest" \
  --tests "*SourceMatcherTest" \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Run fixture contract tests**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS.

- [ ] **Step 8: Commit production confidence fix**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifier.kt \
        fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceRiskClassifierTest.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherSharedComponentTest.kt \
        fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt
git commit -m "fix(core): keep shared definitions below high confidence"
```

---

## Task 3: Repair compose-core detekt ratchet without increasing budget

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt`
- Possibly modify: `config/detekt/baseline-fixthis-compose-core.xml`
- Possibly modify: `config/detekt/baseline-budget.json`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceSignalKindWeightTest.kt`

- [ ] **Step 1: Confirm current ratchet failure**

Run:

```bash
npm run detekt:baseline:check
```

Expected before fix: FAIL with:

```text
[detekt-baseline] over budget: config/detekt/baseline-fixthis-compose-core.xml: 58/57
```

- [ ] **Step 2: Move source-signal weights into named constants**

In `SourceIndex.kt`, add this object above `SourceSignalKind`:

```kotlin
internal object SourceSignalWeights {
    const val EXACT: Double = 1.0
    const val STRING_RESOURCE: Double = 0.85
    const val STRICT_COMP_TEST_TAG: Double = 1.15
    const val ROLE: Double = 0.85
    const val ACTIVITY_NAME: Double = 0.85
    const val ARBITRARY_STRING_LITERAL: Double = 0.35
    const val LAYOUT_RENDERER: Double = 0.75
    const val STRUCTURAL_MARKER: Double = 0.0
}
```

Then replace the enum entries with:

```kotlin
enum class SourceSignalKind(val baseMatchWeight: Double) {
    COMPOSABLE_SYMBOL(SourceSignalWeights.EXACT),
    UI_TEXT(SourceSignalWeights.EXACT),
    STRING_RESOURCE(SourceSignalWeights.STRING_RESOURCE),
    TEST_TAG(SourceSignalWeights.EXACT),
    STRICT_COMP_TEST_TAG(SourceSignalWeights.STRICT_COMP_TEST_TAG),
    CONTENT_DESCRIPTION(SourceSignalWeights.EXACT),
    ROLE(SourceSignalWeights.ROLE),
    ACTIVITY_NAME(SourceSignalWeights.ACTIVITY_NAME),
    ARBITRARY_STRING_LITERAL(SourceSignalWeights.ARBITRARY_STRING_LITERAL),
    STRING_RESOURCE_RESOLVED(SourceSignalWeights.EXACT),
    LAMBDA_OWNER_FUNCTION(SourceSignalWeights.EXACT),
    LAZY_ITEM_OWNER(SourceSignalWeights.EXACT),
    NAV_DESTINATION_OWNER(SourceSignalWeights.EXACT),
    MODIFIER_TARGET(SourceSignalWeights.EXACT),
    LAYOUT_RENDERER(SourceSignalWeights.LAYOUT_RENDERER),
    SHARED_COMPONENT(SourceSignalWeights.STRUCTURAL_MARKER),
    SHARED_COMPONENT_CALL_SITE(SourceSignalWeights.STRUCTURAL_MARKER),
}
```

- [ ] **Step 3: Run the weight serialization tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceSignalKindWeightTest" --no-daemon
```

Expected: PASS. This proves enum serialization still uses names and weights still match the legacy table.

- [ ] **Step 4: Run compose-core detekt**

Run:

```bash
./gradlew :fixthis-compose-core:detekt --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Run baseline budget check**

Run:

```bash
npm run detekt:baseline:check
```

Expected: PASS, or `improved` for compose-core.

If it still reports `58/57`, inspect stale baseline entries:

```bash
rg -n "MagicNumber:SourceIndex.kt" config/detekt/baseline-fixthis-compose-core.xml
```

Remove only entries that no longer correspond to active detekt findings after Step 4. Do not remove unrelated entries.

- [ ] **Step 6: Lower budget only if actual count dropped**

Run:

```bash
node -e 'const fs=require("fs"); const text=fs.readFileSync("config/detekt/baseline-fixthis-compose-core.xml","utf8"); console.log((text.match(/<ID>/g)||[]).length)'
```

If the count is `57`, keep `config/detekt/baseline-budget.json` unchanged.

If the count is below `57`, lower the compose-core budget to that exact count:

```json
"config/detekt/baseline-fixthis-compose-core.xml": <actual_count>
```

Never set it above 57.

- [ ] **Step 7: Commit detekt ratchet fix**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt \
        config/detekt/baseline-fixthis-compose-core.xml \
        config/detekt/baseline-budget.json
git commit -m "chore(detekt): repair compose-core baseline ratchet"
```

If only `SourceIndex.kt` changed, stage only that file:

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceIndex.kt
git commit -m "refactor(core): name source signal match weights"
```

---

## Task 4: Sync release and roadmap claims with corrected trust contract

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/releases/unreleased.md`
- Modify: `docs/product/roadmap.md`
- Modify: `docs/superpowers/specs/2026-06-04-source-matching-refinement-design.md`

- [ ] **Step 1: Find stale claims**

Run:

```bash
rg -n "confident.*call.?site|shared.*HIGH|shared-component.*HIGH|cap.*HIGH|keeps HIGH|keep HIGH|relax" \
  CHANGELOG.md docs/releases/unreleased.md docs/product/roadmap.md docs/superpowers/specs/2026-06-04-source-matching-refinement-design.md
```

Expected: finds the current stale claims.

- [ ] **Step 2: Update `CHANGELOG.md` current unreleased entries**

Replace claims like:

```text
Confident single call-site shared components now keep HIGH confidence
```

with:

```text
Shared-component definitions now keep MEDIUM-or-lower confidence while still
surfacing a single ranked `recommendedEditSite` when the call-site evidence is
clear. This keeps the agent's first place to inspect visible without claiming
exact ownership of the reused definition.
```

Keep any `CALL_SITE edit surfaces can reach HIGH` claim only if it refers to
MCP edit-surface role confidence, not core shared-definition source confidence.

- [ ] **Step 3: Update `docs/releases/unreleased.md`**

Replace the source-matching highlight with this wording:

```text
- `CALL_SITE` edit surfaces can reach HIGH confidence under strong,
  unambiguous evidence. Shared-component definitions remain capped below HIGH;
  a confident single call site is surfaced as `recommendedEditSite` verification
  context instead of promoting the shared definition itself.
```

- [ ] **Step 4: Update `docs/product/roadmap.md`**

Replace the line that says confident single call-site disambiguation keeps HIGH with:

```text
  confidence; confident single call-site disambiguation delivered as a
  `recommendedEditSite` hint while shared definitions remain capped below HIGH;
  ambiguous cases remain caveated.
```

- [ ] **Step 5: Update the superseded K2 section in the 2026-06-04 spec**

In `docs/superpowers/specs/2026-06-04-source-matching-refinement-design.md`,
change the K2 section to explicitly record the correction:

```markdown
### 4. K2 correction - recommended edit site does not raise the shared definition

The earlier K2 idea to relax the shared-component cap is superseded by runtime
fixture evidence. A single `recommendedEditSite` remains valuable, but the
source candidate for the shared definition must stay MEDIUM-or-lower because
editing the definition changes every call site. The HIGH confidence path belongs
to non-shared strong evidence and to edit-surface roles that identify a precise
call-site surface.
```

- [ ] **Step 6: Run doc checks**

Run:

```bash
node scripts/check-doc-consistency.mjs
node scripts/check-release-readiness.mjs
git diff --check
```

Expected: all PASS.

- [ ] **Step 7: Commit docs sync**

```bash
git add CHANGELOG.md docs/releases/unreleased.md docs/product/roadmap.md docs/superpowers/specs/2026-06-04-source-matching-refinement-design.md
git commit -m "docs(release): correct shared-component confidence claims"
```

---

## Task 5: Prove runtime source-trust and release gates are green

**Files:**
- No source files expected.
- Generated reports under `build/reports/` are not committed.
- `.fixthis/` runtime fixture workspaces are not committed.

- [ ] **Step 1: Run connected runtime source-trust**

Run:

```bash
npm run source-matching:fixtures:runtime -- --strict
```

Expected: PASS. Confirm the generated report says:

```text
Status: pass
Failed cases: 0
Environment downgrade cases: 0
```

If it fails, inspect:

```bash
cat build/reports/fixthis-source-matching/report.md
jq '.summary, .fixtures[] | select(.status == "fail")' build/reports/fixthis-source-matching/report.json
```

Fix the production behavior, not the manifest expectation.

- [ ] **Step 2: Run release check**

Run:

```bash
npm run release:check
```

Expected: PASS. This covers doc consistency, release readiness, v0.6 evidence contracts, agent bootstrap docs, evidence runner tests, first-run smoke contract, and detekt baseline ratchet.

- [ ] **Step 3: Run strict release gate**

Run:

```bash
npm run release:gate -- --strict
```

Expected: PASS. Confirm `build/reports/fixthis-release-gate/report.md` shows:

```text
- Status: pass
```

and `runtime-source-trust` is `pass`.

- [ ] **Step 4: Run full core and MCP safety net**

Run:

```bash
./gradlew :fixthis-compose-core:test :fixthis-mcp:test --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run final workspace checks**

Run:

```bash
git status --short
git diff --check
```

Expected:

- `git diff --check` exits 0.
- `git status --short` shows only intentional tracked source/doc/config changes before commit.
- `.fixthis/`, `build/reports/`, `graphify-out/`, and Android build outputs are not staged.

- [ ] **Step 6: Commit final verification docs only if any tracked report docs changed**

If no tracked files changed after verification, skip this commit.

If a tracked release note or readiness doc was updated during final verification:

```bash
git add <tracked-doc-file>
git commit -m "docs(release): record release blocker verification"
```

---

## Task 6: Final release-readiness handoff

**Files:**
- No required source changes.

- [ ] **Step 1: Summarize passing commands for the release issue**

Use this exact checklist in the release issue or final handoff:

```text
Release blocker remediation verification:
- ./gradlew :fixthis-compose-core:test --tests "*SourceRiskClassifierTest" --no-daemon
- ./gradlew :fixthis-compose-core:test --tests "*SourceMatcherSharedComponentTest" --no-daemon
- ./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon
- npm run source-matching:fixtures:test
- npm run source-matching:fixtures:runtime -- --strict
- npm run detekt:baseline:check
- ./gradlew :fixthis-compose-core:detekt --no-daemon
- node scripts/check-doc-consistency.mjs
- node scripts/check-release-readiness.mjs
- npm run release:check
- npm run release:gate -- --strict
- git diff --check
```

- [ ] **Step 2: Confirm no generated artifacts are staged**

Run:

```bash
git status --short
git diff --cached --name-only
```

Expected: no `.fixthis/`, `build/`, `graphify-out/`, or generated Android build output.

- [ ] **Step 3: Optional graph refresh**

If implementation changed Kotlin structure, run:

```bash
graphify update .
```

Expected: graphify may update ignored `graphify-out/` files. Do not stage them.

---

## Self-Review Checklist

- Spec coverage: Tasks 1-2 cover runtime source-trust overconfidence, Task 3 covers detekt ratchet, Task 4 covers stale release claims, Tasks 5-6 cover final release proof.
- Placeholder scan: no unresolved placeholder tokens or unspecified validation gates remain.
- Type consistency: all referenced functions and files exist in the current repository, except changed signatures that are introduced in the same task.
- Boundary check: no bridge protocol or persisted MCP field changes.
- Runtime honesty: strict connected checks are required because the review failure reached the AVD and failed by case classification.
- Release honesty: current docs must say recommended edit sites are verification context, not source confidence promotion.
