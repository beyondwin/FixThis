# FixThis V1 Trust, Install, And Inner-Loop Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the approved umbrella hardening program: stronger source-trust calibration, more recoverable agent-first install evidence, and a profile-based local evidence runner.

**Architecture:** Keep the product contracts additive and local-first. Track A improves source matching and edit-surface guidance without changing persisted JSON shape. Track B aligns CLI JSON, generated setup artifacts, docs, and release readiness. Track C adds an orchestration runner over existing checks rather than replacing canonical commands.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, JUnit/kotlin.test, Node.js `node:test`, Bash, Markdown docs.

---

## Scope Check

The approved design intentionally combines three independently useful subsystems in one umbrella spec. This plan keeps one implementation plan, but each track has separate tasks, focused tests, and commit boundaries:

- Track A can ship after Tasks 1-3.
- Track B can ship after Tasks 4-6.
- Track C can ship after Tasks 7-9.
- Task 10 is the final integration and graph refresh pass.

Runtime-trust checks remain local-only. If Android SDK or a ready emulator is unavailable, runtime-only checks are recorded as deferred unless the selected profile explicitly requires strict runtime verification.

## File Structure

### Track A - Trust And Source Matching

- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt`
  - Add a wire reason for layout-renderer context so MCP role classification can tell layout call sites from ordinary owner-function matches.
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
  - Append the layout-renderer context reason only when a selected owner-function match lands on an entry that also has a `LAYOUT_RENDERER` signal.
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`
  - Protect medium confidence and the new match reason for layout renderer entries.
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceRoleClassifier.kt`
  - Classify copy/data from source evidence, not only user wording.
  - Classify layout-renderer context as `LAYOUT_OR_STYLE` when the intent is otherwise unknown or spacing-oriented.
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt`
  - Let selected string-resource matches count as selected text renderer candidates.
  - Prefer layout-renderer candidates for layout/style edit surfaces.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceRoleClassifierTest.kt`
  - Add source-evidence role tests.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateServiceTest.kt`
  - Add edit-surface candidate tests for selected string resources and layout-renderer context.
- Modify: `scripts/source-matching-fixtures-test.mjs`
  - Lock fixture schema expectations for source confidence, risk flags, and warnings used by Track A.
- Modify: `fixtures/source-matching/manifest.json`
  - Add a local source-index fixture case for copy/data source evidence. Layout-renderer evidence is covered by core matcher and Gradle scanner tests.

### Track B - Agent Install And Release Evidence

- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt`
  - Make top-level `nextAction` prefer explicit readiness next action when present.
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt`
  - Add top-level readiness and next action to `doctor --json`.
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt`
  - Pin next-action agreement.
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt`
  - Pin top-level doctor readiness.
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFilesTest.kt`
  - Ensure generated setup JSON and Markdown keep the doctor-first agent path.
- Modify: `docs/contributing/release-readiness.md`
  - Add V1 trust/install/inner-loop evidence section.
- Modify: `docs/releases/unreleased.md`
  - Record current-main user-facing changes.
- Modify: `CHANGELOG.md`
  - Add unreleased entries.
- Modify: `scripts/check-release-readiness.mjs`
  - Add rules for the new evidence section and current-channel-only claim.

### Track C - Local Evidence Runner

- Create: `scripts/evidence-runner.mjs`
  - Profile-based local runner with dry-run, JSON report, Markdown report, environment probes, and deterministic command order.
- Create: `scripts/evidence-runner-test.mjs`
  - Unit tests for profile expansion, dry-run output, report shape, and runtime deferral.
- Modify: `package.json`
  - Add evidence runner scripts.
- Modify: `docs/contributing/release-readiness.md`
  - Reference runner profiles while preserving canonical commands.
- Modify: `CONTRIBUTING.md`
  - Add a short local evidence runner section near existing build/test guidance.

## Task 1: Add Layout-Renderer Source Match Context

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`

- [ ] **Step 1: Add failing source matcher assertions**

In `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`, update the existing `strictComposableTagCanSurfaceLayoutRendererEntryAsMediumConfidence` test by adding these assertions after the existing owner-composable assertion:

```kotlin
        assertTrue(match.matchReasons.contains("layout renderer context"))
        assertEquals(SourceEvidenceStrength.MEDIUM, match.evidenceStrength)
        assertFalse(SourceCandidateRisk.ARBITRARY_LITERAL in match.riskFlags)
```

Also update `strictComposableTagPrefersLayoutRendererCallSiteOverOwnerOnlyEntry` by adding this assertion after the existing owner-composable assertion:

```kotlin
        assertTrue(match.matchReasons.contains("layout renderer context"))
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest.strictComposableTagCanSurfaceLayoutRendererEntryAsMediumConfidence" --tests "*SourceMatcherTest.strictComposableTagPrefersLayoutRendererCallSiteOverOwnerOnlyEntry" --no-daemon
```

Expected: FAIL with assertions showing `layout renderer context` is not present.

- [ ] **Step 3: Add the layout-renderer match reason**

In `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt`, add the enum entry before `ACTIVITY`:

```kotlin
    LAYOUT_RENDERER_CONTEXT("layout renderer context"),
```

The full enum tail should read:

```kotlin
    NEARBY_TEXT("nearby text"),
    NEARBY_CONTENT_DESCRIPTION("nearby contentDescription"),
    NEARBY_TEST_TAG("nearby testTag"),
    NEARBY_ROLE("nearby role"),
    LAYOUT_RENDERER_CONTEXT("layout renderer context"),
    ACTIVITY("activity"),
    ARBITRARY_LITERAL("arbitrary literal"),
    UNTYPED_FALLBACK("legacy fallback"),
    ;
```

- [ ] **Step 4: Emit the reason without adding score**

In `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`, add this block immediately after the existing arbitrary literal / untyped fallback post-processing block in `score()` and before `return MatchScore(...)`:

```kotlin
        if (
            SourceMatchReason.SELECTED_OWNER_FUNCTION in matchReasons &&
            entry.signals.any { signal -> signal.kind == SourceSignalKind.LAYOUT_RENDERER }
        ) {
            matchReasons.add(SourceMatchReason.LAYOUT_RENDERER_CONTEXT)
        }
```

Do not add `LAYOUT_RENDERER_CONTEXT` to `SourceScoringPolicy.BUCKET_SCORES`. It is descriptive evidence only and must not raise confidence.

- [ ] **Step 5: Run the focused test and verify it passes**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest.strictComposableTagCanSurfaceLayoutRendererEntryAsMediumConfidence" --tests "*SourceMatcherTest.strictComposableTagPrefersLayoutRendererCallSiteOverOwnerOnlyEntry" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Run the full source matcher test**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit Task 1**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatchReason.kt \
  fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt \
  fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt
git commit -m "feat(source): expose layout renderer match context"
```

## Task 2: Classify Edit Surfaces From Source Evidence

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceRoleClassifier.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceRoleClassifierTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateServiceTest.kt`

- [ ] **Step 1: Add failing role classifier tests**

Append these tests to `EditSurfaceRoleClassifierTest` before its private helper methods:

```kotlin
    @Test
    fun classifiesSelectedStringResourceEvidenceAsCopyOrData() {
        val item = item(
            comment = "Make it clearer",
            selectedNode = node(text = listOf("Continue"), role = "Button"),
            candidates = listOf(
                candidate(
                    "CheckoutStrings.kt",
                    reasons = listOf("selected resolved stringResource"),
                    terms = listOf("Continue"),
                ),
            ),
        )

        val role = EditSurfaceRoleClassifier.classify(
            item,
            EditIntentAnalyzer.analyze(item, screenWith(item.selectedNode!!)),
        )

        assertEquals(EditSurfaceRoleDto.COPY_OR_DATA, role.role)
        assertEquals(SelectionConfidence.MEDIUM, role.confidenceCap)
    }

    @Test
    fun classifiesLayoutRendererContextAsLayoutOrStyleWhenIntentIsUnknown() {
        val item = item(
            comment = "This grid feels cramped",
            selectedNode = node(testTag = "comp:AdaptiveGrid:tile"),
            candidates = listOf(
                candidate(
                    "AdaptiveGrid.kt",
                    reasons = listOf("selected owner composable", "layout renderer context"),
                    owner = "AdaptiveGrid",
                ),
            ),
        )

        val role = EditSurfaceRoleClassifier.classify(
            item,
            EditIntentAnalyzer.analyze(item, screenWith(item.selectedNode!!)),
        )

        assertEquals(EditSurfaceRoleDto.LAYOUT_OR_STYLE, role.role)
        assertEquals(SelectionConfidence.LOW, role.confidenceCap)
    }
```

- [ ] **Step 2: Add failing edit-surface candidate service tests**

Append these tests to `EditSurfaceCandidateServiceTest` before its private helper methods:

```kotlin
    @Test
    fun selectedStringResourceCandidateBuildsCopyOrDataSurface() {
        val button = node(uid = "button", text = listOf("Continue"), role = "Button")
        val item = item(
            comment = "Make it clearer",
            selectedNode = button,
            candidates = listOf(
                candidate(
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/CheckoutStrings.kt",
                    reasons = listOf("selected resolved stringResource"),
                    terms = listOf("Continue"),
                ),
            ),
        )

        val candidates = EditSurfaceCandidateService.build(item, screenWith(button))

        assertEquals(EditSurfaceRoleDto.COPY_OR_DATA, candidates.single().role)
        assertEquals(EditSurfaceKindDto.COMPONENT_RENDERER, candidates.single().kind)
        assertEquals(EditSurfaceReasonDto.SELECTED_TEXT_RENDERER, candidates.single().reasons.single())
    }

    @Test
    fun layoutRendererContextBuildsLowConfidenceLayoutSurface() {
        val tile = node(uid = "tile", testTag = "comp:AdaptiveGrid:tile")
        val item = item(
            comment = "This grid feels cramped",
            selectedNode = tile,
            candidates = listOf(
                candidate(
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/AdaptiveGrid.kt",
                    reasons = listOf("selected owner composable", "layout renderer context"),
                    owner = "AdaptiveGrid",
                ),
            ),
        )

        val candidates = EditSurfaceCandidateService.build(item, screenWith(tile))

        assertEquals(EditSurfaceRoleDto.LAYOUT_OR_STYLE, candidates.single().role)
        assertEquals(EditSurfaceKindDto.SPACING, candidates.single().kind)
        assertEquals(SelectionConfidence.LOW, candidates.single().confidence)
    }
```

If `EditSurfaceCandidateServiceTest` helper names differ from the snippets above, add helper overloads with these exact signatures:

```kotlin
    private fun item(
        comment: String,
        selectedNode: FixThisNode,
        candidates: List<SourceCandidate>,
    ): AnnotationDto = AnnotationDto(
        itemId = "item",
        screenId = "screen",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Node(selectedNode.uid, selectedNode.boundsInWindow),
        selectedNode = selectedNode,
        sourceCandidates = candidates,
        comment = comment,
    )

    private fun candidate(
        file: String,
        reasons: List<String>,
        terms: List<String> = emptyList(),
        owner: String? = null,
    ): SourceCandidate = SourceCandidate(
        file = file,
        line = 12,
        score = 0.8,
        confidence = SelectionConfidence.MEDIUM,
        matchReasons = reasons,
        matchedTerms = terms,
        ownerComposable = owner,
    )
```

- [ ] **Step 3: Run the focused MCP tests and verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditSurfaceRoleClassifierTest" --tests "*EditSurfaceCandidateServiceTest" --no-daemon
```

Expected: FAIL because source-evidence based role classification is not implemented.

- [ ] **Step 4: Update role classifier implementation**

In `EditSurfaceRoleClassifier.kt`, replace the `classify` function and add the helper functions below it. Keep the existing `decision`, `hasInteropRisk`, `styleKinds`, `hasComponentSignal`, and `looksLikeCopyIntent` helpers.

```kotlin
    fun classify(item: AnnotationDto, intent: EditIntent): EditSurfaceRoleDecision = when {
        hasInteropRisk(item) -> decision(
            role = EditSurfaceRoleDto.INTEROP_RISK,
            confidenceCap = SelectionConfidence.LOW,
            note = "possible AndroidView/WebView area; verify runtime target before editing",
        )
        item.target is AnnotationTargetDto.Area -> decision(
            role = EditSurfaceRoleDto.VISUAL_AREA,
            confidenceCap = SelectionConfidence.LOW,
            note = "visual area selection has no precise semantics node",
        )
        looksLikeCopyIntent(item.comment) || hasCopyOrDataSourceEvidence(item) -> decision(
            role = EditSurfaceRoleDto.COPY_OR_DATA,
            confidenceCap = SelectionConfidence.MEDIUM,
        )
        intent.primaryKind == EditSurfaceKindDto.SPACING || hasLayoutRendererContext(item) -> decision(
            role = EditSurfaceRoleDto.LAYOUT_OR_STYLE,
            confidenceCap = SelectionConfidence.LOW,
        )
        intent.primaryKind in styleKinds() && hasComponentSignal(item) -> decision(
            role = EditSurfaceRoleDto.COMPONENT_DEFINITION,
            confidenceCap = SelectionConfidence.MEDIUM,
        )
        else -> decision(
            role = EditSurfaceRoleDto.CALL_SITE,
            confidenceCap = SelectionConfidence.MEDIUM,
        )
    }

    private fun hasCopyOrDataSourceEvidence(item: AnnotationDto): Boolean =
        item.sourceCandidates.any { candidate ->
            candidate.matchReasons.any { reason ->
                reason == "selected stringResource" || reason == "selected resolved stringResource"
            }
        }

    private fun hasLayoutRendererContext(item: AnnotationDto): Boolean =
        item.sourceCandidates.any { candidate ->
            candidate.matchReasons.contains("layout renderer context")
        }
```

- [ ] **Step 5: Update candidate selection implementation**

In `EditSurfaceCandidateService.kt`, replace `sourceCandidates` so layout/style roles prefer layout renderer context before generic owner candidates:

```kotlin
    private fun sourceCandidates(
        item: AnnotationDto,
        screen: SnapshotDto?,
        intent: EditIntent,
        roleDecision: EditSurfaceRoleDecision,
    ): List<EditSurfaceCandidateDto> {
        val candidates = mutableListOf<EditSurfaceCandidateDto>()
        if (roleDecision.role == EditSurfaceRoleDto.LAYOUT_OR_STYLE) {
            spacingCandidate(item, intent, roleDecision)?.let { candidates += it }
        }
        ownerCandidate(item, screen, intent, roleDecision)?.let { candidates += it }
        selectedTextCandidate(item, intent, roleDecision).takeIf { candidates.isEmpty() }?.let { candidates += it }
        if (roleDecision.role != EditSurfaceRoleDto.LAYOUT_OR_STYLE) {
            spacingCandidate(item, intent, roleDecision)?.let { candidates += it }
        }
        return candidates
    }
```

Then change `selectedTextCandidate` so selected string-resource evidence can produce copy/data edit surfaces:

```kotlin
    private fun selectedTextCandidate(
        item: AnnotationDto,
        intent: EditIntent,
        roleDecision: EditSurfaceRoleDecision,
    ): EditSurfaceCandidateDto? = item.sourceCandidates
        .firstOrNull { candidate ->
            candidate.matchReasons.any { reason ->
                reason == "selected text" ||
                    reason == "selected stringResource" ||
                    reason == "selected resolved stringResource"
            }
        }
        ?.toEditSurface(
            kind = normalizedKind(intent),
            roleDecision = roleDecision,
            confidence = SelectionConfidence.MEDIUM,
            reasons = intent.reasons + EditSurfaceReasonDto.SELECTED_TEXT_RENDERER,
        )
```

Then replace `spacingCandidate` with this version so layout-renderer context becomes the preferred layout/style surface:

```kotlin
    private fun spacingCandidate(
        item: AnnotationDto,
        intent: EditIntent,
        roleDecision: EditSurfaceRoleDecision,
    ): EditSurfaceCandidateDto? {
        if (intent.primaryKind == EditSurfaceKindDto.SPACING || roleDecision.role == EditSurfaceRoleDto.LAYOUT_OR_STYLE) {
            val source = item.sourceCandidates.firstOrNull { candidate ->
                candidate.matchReasons.contains("layout renderer context")
            } ?: item.sourceCandidates.firstOrNull()
            return source?.toEditSurface(
                kind = EditSurfaceKindDto.SPACING,
                roleDecision = roleDecision,
                confidence = SelectionConfidence.LOW,
                reasons = (intent.reasons + EditSurfaceReasonDto.CALL_SITE).distinct(),
            )
        }
        return null
    }
```

- [ ] **Step 6: Run the focused MCP tests and verify they pass**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditSurfaceRoleClassifierTest" --tests "*EditSurfaceCandidateServiceTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Run handoff evaluation tests**

Run:

```bash
npm run handoff:eval:test
```

Expected: PASS.

- [ ] **Step 8: Commit Task 2**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceRoleClassifier.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceRoleClassifierTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateServiceTest.kt
git commit -m "feat(handoff): classify edit surfaces from source evidence"
```

## Task 3: Add Fixture Coverage For Track A Evidence

**Files:**
- Modify: `fixtures/source-matching/manifest.json`
- Modify: `scripts/source-matching-fixtures-test.mjs`
- Modify: `docs/guides/source-matching-fixture-lab.md`

- [ ] **Step 1: Add failing manifest contract test**

In `scripts/source-matching-fixtures-test.mjs`, add this test near the existing manifest shape tests:

```js
test("manifest includes local copy-data source trust case", () => {
  const manifest = readJson("fixtures/source-matching/manifest.json");
  const local = manifest.fixtures.find((fixture) => fixture.id === "fixthis-sample");
  assert.ok(local, "fixthis-sample fixture is required");
  const ids = new Set(local.cases.map((entry) => entry.id));
  assert.ok(ids.has("fixthis-sample-copy-data-source-index"));
});
```

- [ ] **Step 2: Run the fixture tests and verify they fail**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: FAIL because the local copy/data manifest case is absent.

- [ ] **Step 3: Add local source-index cases to the manifest**

In `fixtures/source-matching/manifest.json`, add this object to the `fixthis-sample` fixture `cases` array after `fixthis-sample-home-primary-runtime`:

```json
        {
          "id": "fixthis-sample-copy-data-source-index",
          "mode": "source-index",
          "expectedEntryPathContains": "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt",
          "expectedSignal": { "kind": "ARBITRARY_STRING_LITERAL", "value": "Refresh" }
        }
```

- [ ] **Step 4: Document what these cases protect**

In `docs/guides/source-matching-fixture-lab.md`, add this paragraph under the section that explains source-index cases:

```markdown
The local `fixthis-sample-copy-data-source-index` case protects V1 trust
hardening by keeping copy/data text evidence visible in the source index
without requiring a device-backed runtime observation. Layout-renderer evidence
is protected by `KotlinSourceScannerTest` and `SourceMatcherTest`, where the
scanner and matcher can control layout-specific source snippets directly.
```

- [ ] **Step 5: Run fixture tests and source index fixture run**

Run:

```bash
npm run source-matching:fixtures:test
npm run source-matching:fixtures
```

Expected: both commands PASS.

- [ ] **Step 6: Commit Task 3**

```bash
git add fixtures/source-matching/manifest.json \
  scripts/source-matching-fixtures-test.mjs \
  docs/guides/source-matching-fixture-lab.md
git commit -m "test(fixtures): cover v1 source trust evidence"
```

## Task 4: Align Install-Agent Next Action Semantics

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt`

- [ ] **Step 1: Add failing next-action agreement test**

Append this test to `InstallAgentJsonReportTest`:

```kotlin
    @Test
    fun topLevelNextActionPrefersReadinessNextActionWhenProvided() {
        val rendered = InstallAgentJsonReport.render(
            applied = emptyList(),
            skipped = emptyList(),
            errors = emptyList(),
            next = listOf("restart your agent", "fixthis doctor --project-dir /repo --json"),
            readiness = FirstRunReadinessCatalog.configRecoverable(
                cause = "Setup completed; verify with doctor.",
            ).copy(
                nextAction = "fixthis doctor --project-dir /repo --json",
            ),
            restartRequired = true,
        )

        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals(
            "fixthis doctor --project-dir /repo --json",
            obj.getValue("nextAction").jsonPrimitive.content,
        )
    }
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew :fixthis-cli:test --tests "*InstallAgentJsonReportTest.topLevelNextActionPrefersReadinessNextActionWhenProvided" --no-daemon
```

Expected: FAIL because `nextAction` still uses `next.first()`.

- [ ] **Step 3: Update JSON report rendering**

In `InstallAgentJsonReport.kt`, replace:

```kotlin
            next.firstOrNull()?.let { put("nextAction", it) }
            putReadiness(readiness)
```

with:

```kotlin
            val preferredNextAction = readiness?.nextAction ?: next.firstOrNull()
            preferredNextAction?.let { put("nextAction", it) }
            putReadiness(readiness)
```

- [ ] **Step 4: Run focused and full CLI report tests**

Run:

```bash
./gradlew :fixthis-cli:test --tests "*InstallAgentJsonReportTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit Task 4**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt \
  fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt
git commit -m "fix(cli): align install-agent next action"
```

## Task 5: Add Top-Level Doctor Readiness

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt`

- [ ] **Step 1: Add failing doctor JSON test**

Append this test to `DoctorCommandTest`:

```kotlin
    @Test
    fun doctorJsonReportCarriesTopLevelReadinessAndNextAction() {
        val report = renderDoctorJsonReport(
            DoctorReport(
                packageName = "com.example",
                checks = listOf(
                    DoctorCheckResult(
                        name = "android_project_found",
                        label = "Android project",
                        ok = true,
                    ),
                    DoctorCheckResult(
                        name = "device_connected",
                        label = "Device connected",
                        ok = false,
                        message = "No ready Android device or emulator is connected.",
                        fix = "Start an emulator.",
                        readiness = FirstRunReadinessCatalog.deviceBlocked(
                            cause = "No ready Android device or emulator is connected.",
                            fix = "Start an emulator.",
                        ),
                    ),
                ),
            ),
        )

        val obj = Json.parseToJsonElement(report).jsonObject
        assertEquals("false", obj.getValue("ok").jsonPrimitive.content)
        assertEquals("Start an emulator.", obj.getValue("nextAction").jsonPrimitive.content)
        assertEquals(
            "DEVICE_BLOCKED",
            obj.getValue("readiness").jsonObject.getValue("state").jsonPrimitive.content,
        )
    }
```

Add missing imports if needed:

```kotlin
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
./gradlew :fixthis-cli:test --tests "*DoctorCommandTest.doctorJsonReportCarriesTopLevelReadinessAndNextAction" --no-daemon
```

Expected: FAIL because `doctor --json` has check-level readiness only.

- [ ] **Step 3: Add report-level readiness**

In `DoctorCommand.kt`, change `DoctorReport` to:

```kotlin
internal data class DoctorReport(
    val packageName: String?,
    val checks: List<DoctorCheckResult>,
) {
    val ok: Boolean = checks.all { it.ok }
    val readiness: FirstRunReadiness
        get() = checks.firstOrNull { !it.ok }?.readiness ?: FirstRunReadinessCatalog.ready(
            details = packageName?.let { mapOf("packageName" to it) } ?: emptyMap(),
        )
}
```

Then in `renderDoctorJsonReport`, add these lines after `report.packageName?.let { put("packageName", it) }`:

```kotlin
        put(
            "readiness",
            fixThisJson.encodeToJsonElement(FirstRunReadiness.serializer(), report.readiness).jsonObject,
        )
        put("nextAction", report.readiness.nextAction)
```

- [ ] **Step 4: Run focused and full doctor tests**

Run:

```bash
./gradlew :fixthis-cli:test --tests "*DoctorCommandTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit Task 5**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt \
  fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt
git commit -m "feat(cli): expose doctor readiness summary"
```

## Task 6: Align Release And Agent Install Evidence Docs

**Files:**
- Modify: `docs/contributing/release-readiness.md`
- Modify: `docs/releases/unreleased.md`
- Modify: `CHANGELOG.md`
- Modify: `scripts/check-release-readiness.mjs`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFilesTest.kt`

- [ ] **Step 1: Add failing release readiness rules**

In `scripts/check-release-readiness.mjs`, add these rules after the existing `R27.trust-sync-event-diagnostics-command` rule:

```js
requireIncludes(
  'R28.v1-trust-install-inner-loop-section',
  'docs/contributing/release-readiness.md',
  '## V1 Trust, Install, And Inner-Loop Evidence',
);
requireIncludes(
  'R29.v1-evidence-runner-fast-profile',
  'docs/contributing/release-readiness.md',
  '`npm run evidence:fast -- --dry-run`',
);
requireIncludes(
  'R30.v1-current-channel-only-claim',
  'docs/contributing/release-readiness.md',
  'This evidence pack does not add PyPI, Docker, or any new package channel.',
);
```

- [ ] **Step 2: Add failing agent setup assertion**

In `AgentSetupFilesTest`, add this test:

```kotlin
    @Test
    fun setupJsonAndMarkdownKeepDoctorBeforeConsole() {
        val root = createTempDir(prefix = "fixthis-agent-setup")
        try {
            AgentSetupFiles.write(
                projectRoot = root,
                packageName = "com.example.app",
                serverName = "fixthis",
                dryRun = false,
                echo = {},
            )

            val jsonText = java.io.File(root, ".fixthis/agent-setup.json").readText()
            val mdText = java.io.File(root, ".fixthis/agent-setup.md").readText()

            assertTrue(jsonText.indexOf("fixthis doctor --project-dir") < jsonText.indexOf("fixthis_open_feedback_console"))
            assertTrue(mdText.indexOf("fixthis doctor --project-dir . --json") < mdText.indexOf("fixthis_open_feedback_console"))
        } finally {
            root.deleteRecursively()
        }
    }
```

- [ ] **Step 3: Run the focused checks and verify they fail**

Run:

```bash
node scripts/check-release-readiness.mjs
./gradlew :fixthis-cli:test --tests "*AgentSetupFilesTest.setupJsonAndMarkdownKeepDoctorBeforeConsole" --no-daemon
```

Expected: release readiness FAILS for missing R28-R30. The AgentSetupFiles test may already pass; if it does, keep it as a regression test.

- [ ] **Step 4: Update release readiness docs**

Add this section to `docs/contributing/release-readiness.md` after `## Trust Sync Release Hardening Evidence`:

```markdown
## V1 Trust, Install, And Inner-Loop Evidence

The V1 umbrella hardening line may be claimed only when each area below has
matching local evidence from the release commit. This evidence pack does not
add PyPI, Docker, or any new package channel.

| Claim | Required evidence |
| --- | --- |
| Source trust avoids overconfident layout, copy/data, visual-area, and interop guidance. | `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon`, `npm run handoff:eval:test`, and `npm run source-matching:fixtures:test`. |
| Agent-first setup reports a recoverable next action from install through doctor. | `./gradlew :fixthis-cli:test --tests "*InstallAgentJsonReportTest" --tests "*DoctorCommandTest" --tests "*AgentSetupFilesTest" --no-daemon` and `npm run docs:agent-bootstrap:test`. |
| Local evidence profiles are available without hiding canonical commands. | `npm run evidence:fast -- --dry-run`, `npm run evidence:test`, and `node scripts/check-release-readiness.mjs`. |

Runtime trust remains local-only. If Android SDK or an unlocked emulator is
unavailable, record `npm run source-matching:fixtures:runtime -- --strict` as
deferred rather than implying it passed.
```

- [ ] **Step 5: Update release notes and changelog**

Add this bullet under the unreleased section in `docs/releases/unreleased.md`:

```markdown
- Planned V1 hardening now ties source-trust calibration, agent install
  recovery, and local evidence profiles to explicit release evidence.
```

Add this bullet under the latest unreleased section in `CHANGELOG.md`:

```markdown
- Documented the V1 trust/install/inner-loop hardening evidence pack for the
  next implementation cycle.
```

- [ ] **Step 6: Run focused checks**

Run:

```bash
node scripts/check-release-readiness.mjs
./gradlew :fixthis-cli:test --tests "*InstallAgentJsonReportTest" --tests "*DoctorCommandTest" --tests "*AgentSetupFilesTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit Task 6**

```bash
git add docs/contributing/release-readiness.md \
  docs/releases/unreleased.md \
  CHANGELOG.md \
  scripts/check-release-readiness.mjs \
  fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFilesTest.kt
git commit -m "docs(release): add v1 hardening evidence"
```

## Task 7: Add The Evidence Runner

**Files:**
- Create: `scripts/evidence-runner.mjs`
- Create: `scripts/evidence-runner-test.mjs`
- Modify: `package.json`

- [ ] **Step 1: Write failing runner tests**

Create `scripts/evidence-runner-test.mjs`:

```js
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  expandProfile,
  planRun,
  renderDryRun,
  writeReports,
} from "./evidence-runner.mjs";

test("fast profile expands to cheap local commands", () => {
  const commands = expandProfile("fast").map((step) => step.command);
  assert.deepEqual(commands, [
    "node scripts/check-doc-consistency.mjs",
    "node scripts/check-release-readiness.mjs",
    "npm run docs:agent-bootstrap:test",
    "node scripts/build-console-assets.mjs --check",
    "npm run console:test:fast",
    "node scripts/check-whitespace.mjs diff --check",
  ]);
});

test("trust profile includes source matching and runtime strict as deferrable", () => {
  const steps = expandProfile("trust");
  assert.ok(steps.some((step) => step.command === "npm run source-matching:fixtures:test"));
  const runtime = steps.find((step) => step.command === "npm run source-matching:fixtures:runtime -- --strict");
  assert.equal(runtime.deferrable, true);
});

test("dry run renders commands without executing", () => {
  const text = renderDryRun(planRun({ profile: "release", dryRun: true }));
  assert.match(text, /DRY RUN profile=release/);
  assert.match(text, /node scripts\/check-release-readiness\.mjs/);
  assert.match(text, /npm run release:version:check/);
});

test("writeReports writes json and markdown summaries", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-evidence-"));
  try {
    const report = {
      schemaVersion: "1.0",
      profile: "fast",
      status: "passed",
      startedAt: "2026-05-27T00:00:00.000Z",
      finishedAt: "2026-05-27T00:00:01.000Z",
      steps: [
        {
          name: "Docs consistency",
          command: "node scripts/check-doc-consistency.mjs",
          status: "passed",
          durationMs: 10,
        },
      ],
    };
    const paths = writeReports(report, root);
    assert.match(readFileSync(paths.json, "utf8"), /"profile": "fast"/);
    assert.match(readFileSync(paths.markdown, "utf8"), /# FixThis Evidence Report/);
    assert.match(readFileSync(paths.markdown, "utf8"), /\| Docs consistency \| passed \|/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("unknown profile fails during planning", () => {
  assert.throws(() => planRun({ profile: "unknown", dryRun: true }), /Unknown evidence profile/);
});
```

- [ ] **Step 2: Run the runner tests and verify they fail**

Run:

```bash
node --test scripts/evidence-runner-test.mjs
```

Expected: FAIL because `scripts/evidence-runner.mjs` does not exist.

- [ ] **Step 3: Create the runner implementation**

Create `scripts/evidence-runner.mjs`:

```js
#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = resolve(import.meta.dirname, "..");

const profileDefinitions = {
  fast: [
    step("Docs consistency", "node scripts/check-doc-consistency.mjs"),
    step("Release readiness", "node scripts/check-release-readiness.mjs"),
    step("Agent bootstrap docs", "npm run docs:agent-bootstrap:test"),
    step("Console bundle check", "node scripts/build-console-assets.mjs --check"),
    step("Fast console contracts", "npm run console:test:fast"),
    step("Workspace whitespace", "node scripts/check-whitespace.mjs diff --check"),
  ],
  trust: [
    step("Compose core source trust", "./gradlew :fixthis-compose-core:test --tests \"*SourceMatcherTest\" --no-daemon"),
    step("Handoff evaluation", "npm run handoff:eval:test"),
    step("Source fixture contracts", "npm run source-matching:fixtures:test"),
    step("Runtime trust strict", "npm run source-matching:fixtures:runtime -- --strict", { deferrable: true, requiresAndroid: true }),
  ],
  console: [
    step("Studio reliability contract", "node --test scripts/studioReliabilityContract-test.mjs"),
    step("Console fast contracts", "npm run console:test:fast"),
    step("Browser reliability", "npm run console:browser:reliability"),
  ],
  release: [
    step("Release readiness", "node scripts/check-release-readiness.mjs"),
    step("Docs CLI surface", "bash scripts/check-docs-cli-surface.sh"),
    step("Agent bootstrap docs", "npm run docs:agent-bootstrap:test"),
    step("Version metadata", "npm run release:version:check"),
    step("Package tests", "npm run release:package:test"),
    step("npm and MCP registry tests", "npm run release:npm:test"),
  ],
};

profileDefinitions.full = [
  ...profileDefinitions.fast,
  ...profileDefinitions.trust,
  ...profileDefinitions.console,
  ...profileDefinitions.release,
  step("Local CI full mirror", "npm run ci:local"),
];

const profiles = Object.freeze(profileDefinitions);

function step(name, command, options = {}) {
  return Object.freeze({
    name,
    command,
    deferrable: options.deferrable === true,
    requiresAndroid: options.requiresAndroid === true,
  });
}

export function expandProfile(profile) {
  const selected = profiles[profile];
  if (!selected) {
    throw new Error(`Unknown evidence profile: ${profile}`);
  }
  return selected;
}

export function planRun({ profile, dryRun = false, strictRuntime = false } = {}) {
  const selectedProfile = profile || "fast";
  return {
    schemaVersion: "1.0",
    profile: selectedProfile,
    dryRun,
    strictRuntime,
    steps: expandProfile(selectedProfile).map((entry) => ({ ...entry })),
  };
}

export function renderDryRun(plan) {
  const lines = [`DRY RUN profile=${plan.profile}`];
  for (const entry of plan.steps) {
    lines.push(`RUN ${entry.command}`);
  }
  return `${lines.join("\n")}\n`;
}

function androidReady() {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk) return false;
  const adb = spawnSync("adb", ["devices"], { cwd: repoRoot, encoding: "utf8" });
  return adb.status === 0 && /\n\S+\s+device\b/.test(adb.stdout);
}

function runCommand(command) {
  const started = Date.now();
  const result = spawnSync("bash", ["-lc", command], {
    cwd: repoRoot,
    encoding: "utf8",
    stdio: "pipe",
  });
  return {
    status: result.status === 0 ? "passed" : "failed",
    durationMs: Date.now() - started,
    stdout: result.stdout,
    stderr: result.stderr,
    exitCode: result.status ?? 1,
  };
}

export function writeReports(report, root = repoRoot) {
  const reportDir = join(root, "build/reports/fixthis-evidence");
  mkdirSync(reportDir, { recursive: true });
  const jsonPath = join(reportDir, `${report.profile}.json`);
  const markdownPath = join(reportDir, `${report.profile}.md`);
  writeFileSync(jsonPath, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdownPath, renderMarkdownReport(report));
  return { json: jsonPath, markdown: markdownPath };
}

function renderMarkdownReport(report) {
  const lines = [
    "# FixThis Evidence Report",
    "",
    `Profile: ${report.profile}`,
    `Status: ${report.status}`,
    "",
    "| Step | Status | Command | Duration |",
    "| --- | --- | --- | --- |",
  ];
  for (const entry of report.steps) {
    lines.push(`| ${entry.name} | ${entry.status} | \`${entry.command}\` | ${entry.durationMs ?? 0}ms |`);
  }
  return `${lines.join("\n")}\n`;
}

function parseArgs(argv) {
  const args = {
    profile: "fast",
    dryRun: false,
    strictRuntime: false,
    continueOnFailure: false,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--profile") {
      args.profile = argv[i + 1];
      i += 1;
    } else if (arg === "--dry-run") {
      args.dryRun = true;
    } else if (arg === "--strict-runtime") {
      args.strictRuntime = true;
    } else if (arg === "--continue") {
      args.continueOnFailure = true;
    } else if (arg === "-h" || arg === "--help") {
      console.log("Usage: node scripts/evidence-runner.mjs --profile <fast|trust|console|release|full> [--dry-run] [--strict-runtime] [--continue]");
      process.exit(0);
    } else {
      throw new Error(`Unknown flag: ${arg}`);
    }
  }
  return args;
}

export function runPlan(plan, options = {}) {
  const startedAt = new Date().toISOString();
  const steps = [];
  let status = "passed";
  const canRunAndroid = androidReady();

  for (const entry of plan.steps) {
    if (entry.requiresAndroid && !canRunAndroid && !plan.strictRuntime) {
      steps.push({
        ...entry,
        status: "deferred",
        durationMs: 0,
        reason: "Android SDK or ready emulator is unavailable.",
      });
      continue;
    }
    const result = runCommand(entry.command);
    steps.push({ ...entry, ...result });
    if (result.status === "failed") {
      status = "failed";
      if (!options.continueOnFailure) break;
    }
  }

  const report = {
    schemaVersion: "1.0",
    profile: plan.profile,
    status,
    startedAt,
    finishedAt: new Date().toISOString(),
    steps,
  };
  return report;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const plan = planRun(args);
  if (args.dryRun) {
    process.stdout.write(renderDryRun(plan));
    return;
  }
  const report = runPlan(plan, args);
  const paths = writeReports(report);
  console.log(`Evidence profile ${report.profile}: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  const failed = report.steps.find((entry) => entry.status === "failed");
  if (failed) {
    console.error(`First failed command: ${failed.command}`);
    process.exit(1);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  main();
}
```

- [ ] **Step 4: Add package scripts**

In `package.json`, add these entries inside `"scripts"` after `ci:local:test`:

```json
    "evidence": "node scripts/evidence-runner.mjs",
    "evidence:fast": "node scripts/evidence-runner.mjs --profile fast",
    "evidence:trust": "node scripts/evidence-runner.mjs --profile trust",
    "evidence:console": "node scripts/evidence-runner.mjs --profile console",
    "evidence:release": "node scripts/evidence-runner.mjs --profile release",
    "evidence:full": "node scripts/evidence-runner.mjs --profile full",
    "evidence:test": "node --test scripts/evidence-runner-test.mjs",
```

Keep JSON commas valid.

- [ ] **Step 5: Run runner tests and dry-run smoke**

Run:

```bash
npm run evidence:test
npm run evidence:fast -- --dry-run
npm run evidence:trust -- --dry-run
```

Expected: PASS and dry-run output lists commands without executing them.

- [ ] **Step 6: Commit Task 7**

```bash
git add scripts/evidence-runner.mjs scripts/evidence-runner-test.mjs package.json
git commit -m "feat(scripts): add local evidence runner"
```

## Task 8: Document Evidence Runner Usage

**Files:**
- Modify: `CONTRIBUTING.md`
- Modify: `docs/contributing/release-readiness.md`
- Modify: `scripts/check-release-readiness.mjs`

- [ ] **Step 1: Add failing docs checks**

Add this rule to `scripts/check-release-readiness.mjs` after R30:

```js
requireIncludes(
  'R31.v1-evidence-runner-contributing',
  'CONTRIBUTING.md',
  'npm run evidence:fast -- --dry-run',
);
```

- [ ] **Step 2: Run release readiness and verify it fails**

Run:

```bash
node scripts/check-release-readiness.mjs
```

Expected: FAIL for R31 because `CONTRIBUTING.md` does not mention the evidence runner.

- [ ] **Step 3: Add contributing docs section**

In `CONTRIBUTING.md`, add this section under the existing build/test guidance:

````markdown
### Local Evidence Profiles

Use the evidence runner when you want a named local validation profile with a
JSON and Markdown report under `build/reports/fixthis-evidence/`.

```bash
npm run evidence:fast -- --dry-run
npm run evidence:trust
npm run evidence:console
npm run evidence:release
```

The runner is a convenience layer over canonical commands. If a profile fails,
rerun the failed command printed in the summary. Runtime trust checks are
reported as deferred when Android SDK or a ready emulator is unavailable unless
the profile is run with `--strict-runtime`.
````

- [ ] **Step 4: Add runner wording to release readiness**

In `docs/contributing/release-readiness.md`, add this sentence to the end of the `V1 Trust, Install, And Inner-Loop Evidence` section:

```markdown
The evidence runner writes local reports under
`build/reports/fixthis-evidence/`; those reports are useful release-issue
attachments but are not committed.
```

- [ ] **Step 5: Run docs checks**

Run:

```bash
node scripts/check-release-readiness.mjs
npm run docs:agent-bootstrap:test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 8**

```bash
git add CONTRIBUTING.md docs/contributing/release-readiness.md scripts/check-release-readiness.mjs
git commit -m "docs: document local evidence profiles"
```

## Task 9: Wire Evidence Runner Into Local Verification

**Files:**
- Modify: `scripts/verify-ci-local-test.mjs`
- Modify: `scripts/verify-ci-local.sh`

- [ ] **Step 1: Add failing list-mode assertion**

In `scripts/verify-ci-local-test.mjs`, update the expected `--fast` command list by inserting this command after `npm run docs:agent-bootstrap:test`:

```js
    "npm run evidence:test",
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```bash
npm run ci:local:test
```

Expected: FAIL because `verify-ci-local.sh --fast --list` does not include `npm run evidence:test`.

- [ ] **Step 3: Add evidence runner contract to local CI mirror**

In `scripts/verify-ci-local.sh`, add this command in the non-`prepush` branch immediately after `run_step "npm run docs:agent-bootstrap:test"`:

```bash
    run_step "npm run evidence:test"
```

- [ ] **Step 4: Run local CI contract tests**

Run:

```bash
npm run ci:local:test
```

Expected: PASS.

- [ ] **Step 5: Commit Task 9**

```bash
git add scripts/verify-ci-local.sh scripts/verify-ci-local-test.mjs
git commit -m "test(ci): include evidence runner contracts"
```

## Task 10: Final Verification And Graph Refresh

**Files:**
- No direct source edits expected unless verification exposes a real issue.
- Local generated outputs under `graphify-out/` and `build/reports/` remain uncommitted.

- [ ] **Step 1: Run focused verification matrix**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon
./gradlew :fixthis-mcp:test --tests "*EditSurfaceRoleClassifierTest" --tests "*EditSurfaceCandidateServiceTest" --no-daemon
./gradlew :fixthis-cli:test --tests "*InstallAgentJsonReportTest" --tests "*DoctorCommandTest" --tests "*AgentSetupFilesTest" --no-daemon
npm run source-matching:fixtures:test
npm run handoff:eval:test
npm run evidence:test
node scripts/check-release-readiness.mjs
git diff --check
```

Expected: all commands PASS.

- [ ] **Step 2: Run evidence dry-run profiles**

Run:

```bash
npm run evidence:fast -- --dry-run
npm run evidence:trust -- --dry-run
npm run evidence:console -- --dry-run
npm run evidence:release -- --dry-run
```

Expected: all commands PASS and print command lists without executing checks.

- [ ] **Step 3: Run fast evidence profile**

Run:

```bash
npm run evidence:fast
```

Expected: PASS and reports written under `build/reports/fixthis-evidence/`.

- [ ] **Step 4: Run runtime trust only when the environment is ready**

Check:

```bash
adb devices
```

If a ready device or emulator is listed and `ANDROID_HOME` or `ANDROID_SDK_ROOT` is set, run:

```bash
npm run source-matching:fixtures:runtime -- --strict
```

Expected when environment is ready: PASS.

If no ready device or emulator is listed, record runtime trust as deferred in the final implementation notes and do not claim it passed.

- [ ] **Step 5: Refresh Graphify output locally**

Run:

```bash
graphify update .
```

Expected: command completes. `graphify-out/` may change locally and must remain uncommitted.

- [ ] **Step 6: Confirm only intended tracked files are dirty**

Run:

```bash
git status --short
```

Expected: no tracked dirty files after all commits. Ignored generated outputs may exist under `build/` or `graphify-out/`.

- [ ] **Step 7: Leave the branch in a reviewable state**

Run:

```bash
git log --oneline -5
git status --short
```

Expected: recent task commits are visible, and no tracked files are dirty. If a verification step exposed a real issue, fix it in the owning task area and create a normal targeted commit before marking this final task complete. Do not create an empty commit.
