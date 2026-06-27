# Handoff Intelligence Role Confidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make role-bearing FixThis handoffs expose role-specific confidence, basis, and action guidance without renaming persisted MCP fields.

**Architecture:** Keep `fixthis-compose-core` as the source-evidence owner and implement the new contract inside `fixthis-mcp`'s edit-surface package. Add one internal role-contract helper, feed its result through `EditSurfaceConfidencePolicy` and `EditSurfaceCandidateService`, then render action guidance from the existing optional `EditSurfaceCandidateDto.note` field as an `action:` line in compact handoff Markdown.

**Tech Stack:** Kotlin/JUnit tests under `:fixthis-mcp`, kotlinx-serializable DTOs already in `SessionDtoModels.kt`, existing Node fixture/evidence scripts, Markdown reference docs.

## Global Constraints

- FixThis remains debug-only and Jetpack Compose-only.
- Do not rename persisted MCP JSON fields.
- Do not rename `items`, `screens`, `itemId`, `screenId`, `targetEvidence`, `targetReliability`, or `sourceCandidates`.
- Do not change bridge protocol fields.
- Do not add Android runtime proof runner or release-gate orchestration work.
- Do not add XML/View/WebView exact source targeting.
- Prefer reusing existing optional `confidenceBasis` and `note` fields over adding a new persisted field.
- After modifying code, run `graphify update .`.

---

## File Structure

- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/editsurface/EditSurfaceRoleContract.kt`
  - Owns the static role action guidance used by policy and candidate construction.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/editsurface/EditSurfaceConfidencePolicy.kt`
  - Adds `action` to `EditSurfaceConfidenceResult` and reads role action guidance from the contract.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/editsurface/EditSurfaceCandidateService.kt`
  - Copies policy action guidance into `EditSurfaceCandidateDto.note`, combined with existing caveats where present.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/CompactHandoffRenderer.kt`
  - Renders edit-surface candidate notes as `action:` lines while leaving source-candidate cautions as `note:` lines.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicyTest.kt`
  - Pins role contracts, ceilings, basis, and action strings.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateServiceTest.kt`
  - Pins candidate action guidance and shared-component caveats.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
  - Pins compact Markdown rendering of `action:` lines and overclaim prevention.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`
  - Adds corpus-level assertion that each role-bearing handoff includes action guidance.
- Modify `docs/reference/output-schema.md`, `docs/reference/mcp-tools.md`, and `docs/reference/feedback-console-contract.md`
  - Documents that `note` on edit-surface candidates renders as compact `action:` guidance.

---

### Task 1: Add The Role Confidence Contract

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/editsurface/EditSurfaceRoleContract.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/editsurface/EditSurfaceConfidencePolicy.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicyTest.kt`

**Interfaces:**
- Consumes: `EditSurfaceRoleDto`, `SelectionConfidence`, `SourceCandidate`, existing `EditSurfaceEvidence.from(sourceCandidate)`.
- Produces:
  - `internal data class EditSurfaceRoleContract(val role: EditSurfaceRoleDto, val actionGuidance: String)`
  - `internal object EditSurfaceRoleContracts { fun forRole(role: EditSurfaceRoleDto): EditSurfaceRoleContract }`
  - `internal data class EditSurfaceConfidenceResult(val confidence: SelectionConfidence, val basis: String, val action: String)`
  - `EditSurfaceConfidencePolicy.score(role: EditSurfaceRoleDto, sourceCandidate: SourceCandidate?): EditSurfaceConfidenceResult`

- [ ] **Step 1: Add failing role contract and action tests**

Append these tests to `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicyTest.kt` and add the imports shown below.

```kotlin
import io.github.beyondwin.fixthis.mcp.session.editsurface.EditSurfaceRoleContracts
```

```kotlin
    @Test
    fun `every edit surface role exposes action guidance`() {
        val missing = EditSurfaceRoleDto.entries
            .map { role -> role to EditSurfaceRoleContracts.forRole(role).actionGuidance }
            .filter { (_, action) -> action.isBlank() }

        assertTrue(missing.isEmpty(), "Missing action guidance for: $missing")
    }

    @Test
    fun `visual area stays low and tells agent to treat source paths as hints`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.VISUAL_AREA,
            sourceCandidate = candidate(SelectionConfidence.HIGH),
        )

        assertEquals(SelectionConfidence.LOW, result.confidence)
        assertTrue(result.basis.contains("visual-area", ignoreCase = true))
        assertTrue(result.action.contains("source paths as hints", ignoreCase = true))
    }

    @Test
    fun `layout renderer context never promotes layout or style to high`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.LAYOUT_OR_STYLE,
            sourceCandidate = candidate(
                SelectionConfidence.HIGH,
                reasons = listOf("layout renderer context"),
                ownerComposable = "AdaptiveGrid",
            ),
        )

        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
        assertTrue(result.action.contains("layout renderer", ignoreCase = true))
        assertTrue(result.action.contains("verify", ignoreCase = true))
    }

    @Test
    fun `shared component definition action warns about call site impact`() {
        val result = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COMPONENT_DEFINITION,
            sourceCandidate = candidate(
                SelectionConfidence.HIGH,
                evidenceStrength = io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength.STRONG,
                riskFlags = listOf(io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.SHARED_COMPONENT),
            ),
        )

        assertEquals(SelectionConfidence.MEDIUM, result.confidence)
        assertTrue(result.action.contains("call site", ignoreCase = true))
        assertTrue(result.action.contains("definition", ignoreCase = true))
    }

    @Test
    fun `copy or data high confidence requires exact source evidence`() {
        val high = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COPY_OR_DATA,
            sourceCandidate = candidate(
                SelectionConfidence.HIGH,
                reasons = listOf("selected resolved stringResource"),
                evidenceStrength = io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength.STRONG,
            ),
        )
        val weak = EditSurfaceConfidencePolicy.score(
            role = EditSurfaceRoleDto.COPY_OR_DATA,
            sourceCandidate = candidate(
                SelectionConfidence.HIGH,
                reasons = listOf("nearby text"),
                riskFlags = listOf(io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk.NEARBY_ONLY),
            ),
        )

        assertEquals(SelectionConfidence.HIGH, high.confidence)
        assertTrue(high.action.contains("copy", ignoreCase = true))
        assertEquals(SelectionConfidence.LOW, weak.confidence)
    }
```

- [ ] **Step 2: Run the focused policy test and verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditSurfaceConfidencePolicyTest" --no-daemon
```

Expected: FAIL with unresolved reference `EditSurfaceRoleContracts` or unresolved property `action`.

- [ ] **Step 3: Create the role contract helper**

Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/editsurface/EditSurfaceRoleContract.kt` with this complete content:

```kotlin
package io.github.beyondwin.fixthis.mcp.session.editsurface

import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceRoleDto

internal data class EditSurfaceRoleContract(
    val role: EditSurfaceRoleDto,
    val actionGuidance: String,
)

internal object EditSurfaceRoleContracts {
    fun forRole(role: EditSurfaceRoleDto): EditSurfaceRoleContract = when (role) {
        EditSurfaceRoleDto.CALL_SITE -> EditSurfaceRoleContract(
            role = role,
            actionGuidance = "edit the matched call site, then verify the preview",
        )
        EditSurfaceRoleDto.COMPONENT_DEFINITION -> EditSurfaceRoleContract(
            role = role,
            actionGuidance = "verify call-site impact before editing the shared component definition",
        )
        EditSurfaceRoleDto.COPY_OR_DATA -> EditSurfaceRoleContract(
            role = role,
            actionGuidance = "confirm whether the change belongs in copy/data or the renderer",
        )
        EditSurfaceRoleDto.LAYOUT_OR_STYLE -> EditSurfaceRoleContract(
            role = role,
            actionGuidance = "treat layout renderer context as an edit hint and verify before editing",
        )
        EditSurfaceRoleDto.VISUAL_AREA -> EditSurfaceRoleContract(
            role = role,
            actionGuidance = "treat source paths as hints because the target is a visual area",
        )
        EditSurfaceRoleDto.INTEROP_RISK -> EditSurfaceRoleContract(
            role = role,
            actionGuidance = "verify runtime target and boundary context before editing",
        )
    }
}
```

- [ ] **Step 4: Extend policy results with action guidance**

In `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/editsurface/EditSurfaceConfidencePolicy.kt`, replace the result type and `score` body with this shape. Keep the existing private helper names, then update each return to call `withAction(role, ...)`.

```kotlin
internal data class EditSurfaceConfidenceResult(
    val confidence: SelectionConfidence,
    val basis: String,
    val action: String,
)

internal object EditSurfaceConfidencePolicy {
    fun score(
        role: EditSurfaceRoleDto,
        sourceCandidate: SourceCandidate?,
    ): EditSurfaceConfidenceResult {
        val source = sourceCandidate?.confidence ?: SelectionConfidence.NONE
        val reasons = sourceCandidate?.matchReasons.orEmpty()
        val evidence = EditSurfaceEvidence.from(sourceCandidate)
        return when (role) {
            EditSurfaceRoleDto.INTEROP_RISK -> withAction(
                role,
                SelectionConfidence.LOW,
                "interop boundary: verify runtime target before editing",
            )
            EditSurfaceRoleDto.VISUAL_AREA -> withAction(
                role,
                SelectionConfidence.LOW,
                "visual-area selection: no precise semantics node",
            )
            EditSurfaceRoleDto.COMPONENT_DEFINITION -> componentDefinition(role, source, evidence)
            EditSurfaceRoleDto.COPY_OR_DATA -> copyOrData(role, source, evidence, reasons)
            EditSurfaceRoleDto.LAYOUT_OR_STYLE -> layoutOrStyle(role, source, evidence)
            EditSurfaceRoleDto.CALL_SITE -> withAction(
                role,
                cap(source, SelectionConfidence.HIGH),
                "call site matched${reasonSuffix(reasons)}",
            )
        }
    }

    private fun withAction(
        role: EditSurfaceRoleDto,
        confidence: SelectionConfidence,
        basis: String,
    ): EditSurfaceConfidenceResult = EditSurfaceConfidenceResult(
        confidence = confidence,
        basis = basis,
        action = EditSurfaceRoleContracts.forRole(role).actionGuidance,
    )
```

Then update the private helper signatures and returns:

```kotlin
    private fun componentDefinition(
        role: EditSurfaceRoleDto,
        source: SelectionConfidence,
        evidence: EditSurfaceEvidence,
    ): EditSurfaceConfidenceResult {
        val (ceiling, label) = when {
            evidence.ambiguous -> SelectionConfidence.LOW to "ambiguous owner — verify before editing"
            !evidence.shared && evidence.strong -> SelectionConfidence.HIGH to "single-owner definition"
            else -> SelectionConfidence.MEDIUM to "editing it changes every call site"
        }
        return withAction(role, cap(source, ceiling), "shared component definition: $label")
    }

    private fun layoutOrStyle(
        role: EditSurfaceRoleDto,
        source: SelectionConfidence,
        evidence: EditSurfaceEvidence,
    ): EditSurfaceConfidenceResult {
        val ceiling = if (evidence.confidentCallSite) SelectionConfidence.MEDIUM else SelectionConfidence.LOW
        return withAction(role, cap(source, ceiling), "layout/style edit applies at the call site")
    }

    private fun copyOrData(
        role: EditSurfaceRoleDto,
        source: SelectionConfidence,
        evidence: EditSurfaceEvidence,
        reasons: List<String>,
    ): EditSurfaceConfidenceResult {
        val (ceiling, label) = when {
            evidence.ambiguous || evidence.proximityOnly -> SelectionConfidence.LOW to "nearby only — verify"
            evidence.strong && evidence.exactCopyMatch -> SelectionConfidence.HIGH to "exact literal"
            else -> SelectionConfidence.MEDIUM to "matched copy/data"
        }
        return withAction(role, cap(source, ceiling), "$label${reasonSuffix(reasons)}")
    }
```

- [ ] **Step 5: Run policy tests and verify pass**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditSurfaceConfidencePolicyTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/editsurface/EditSurfaceRoleContract.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/editsurface/EditSurfaceConfidencePolicy.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceConfidencePolicyTest.kt
git commit -m "feat: add edit surface role confidence contract"
```

---

### Task 2: Carry Role Actions Into Edit-Surface Candidates

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/editsurface/EditSurfaceCandidateService.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateServiceTest.kt`

**Interfaces:**
- Consumes: `EditSurfaceConfidencePolicy.score(...).action` from Task 1.
- Produces: `EditSurfaceCandidateDto.note` populated with role action guidance plus existing caveats.

- [ ] **Step 1: Add failing candidate service tests**

In `EditSurfaceCandidateServiceTest.kt`, add these imports:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.SourceEvidenceStrength
import io.github.beyondwin.fixthis.compose.core.model.SourceLocationRef
```

Append these tests:

```kotlin
    @Test
    fun sharedComponentDefinitionCarriesCallSiteActionGuidance() {
        val chip = node("shared-header", text = listOf("Diagnostics"), testTag = "comp:StudioHeader:root")
        val item = item(
            comment = "Make this header calmer",
            selectedNode = chip,
            candidates = listOf(
                sourceCandidate(
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/StudioHeader.kt",
                    matchedTerms = listOf("StudioHeader"),
                    ownerComposable = "StudioHeader",
                ).copy(
                    confidence = SelectionConfidence.HIGH,
                    evidenceStrength = SourceEvidenceStrength.STRONG,
                    riskFlags = listOf(SourceCandidateRisk.SHARED_COMPONENT),
                    callSites = listOf(
                        SourceLocationRef(
                            file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt",
                            line = 49,
                            mostLikely = true,
                            recommendedEditSite = true,
                        ),
                    ),
                ),
            ),
        )

        val candidate = EditSurfaceCandidateService.build(item, screenWith(chip)).single()

        assertEquals(EditSurfaceRoleDto.COMPONENT_DEFINITION, candidate.role)
        assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
        assertTrue(candidate.confidenceBasis!!.contains("shared component definition"))
        assertTrue(candidate.note!!.contains("call-site impact", ignoreCase = true))
        assertTrue(candidate.note!!.contains("definition", ignoreCase = true))
    }

    @Test
    fun visualAreaFallbackCarriesHintActionGuidance() {
        val item = AnnotationDto(
            itemId = "area-action",
            screenId = "screen-1",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 80f, 80f)),
            sourceCandidates = emptyList(),
            comment = "Tighten this empty gap",
        )

        val candidate = EditSurfaceCandidateService.build(item, screenWith()).single()

        assertEquals(EditSurfaceRoleDto.VISUAL_AREA, candidate.role)
        assertEquals(SelectionConfidence.LOW, candidate.confidence)
        assertTrue(candidate.note!!.contains("source paths as hints", ignoreCase = true))
    }

    @Test
    fun typographyTextCandidateCombinesRendererCaveatAndRoleAction() {
        val label = node("status-label", text = listOf("Ready"), testTag = "comp:StatusLine:label")
        val item = item(
            comment = "Make this text bolder",
            selectedNode = label,
            candidates = listOf(
                sourceCandidate(
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/StatusLine.kt",
                    matchedTerms = listOf("StatusLine"),
                    ownerComposable = "StatusLine",
                ),
            ),
        )

        val candidate = EditSurfaceCandidateService.build(item, screenWith(label)).single()

        assertEquals(EditSurfaceKindDto.TYPOGRAPHY, candidate.kind)
        assertTrue(candidate.note!!.contains("source candidate identifies data text"))
        assertTrue(candidate.note!!.contains("call site", ignoreCase = true))
    }
```

- [ ] **Step 2: Run candidate service tests and verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditSurfaceCandidateServiceTest" --no-daemon
```

Expected: FAIL because `note` is null or does not include the role action guidance.

- [ ] **Step 3: Add note composition helper**

In `EditSurfaceCandidateService.kt`, replace the direct `note = ...` assignments in `emptySourceCandidate` and `SourceCandidate.toEditSurface` with `note = noteFor(...)`.

Add this helper inside `EditSurfaceCandidateService`:

```kotlin
    private fun noteFor(
        kind: EditSurfaceKindDto,
        roleDecision: EditSurfaceRoleDecision,
        scored: EditSurfaceConfidenceResult,
    ): String? {
        val rendererCaveat = TEXT_SOURCE_NOTE.takeIf {
            kind == EditSurfaceKindDto.TEXT_COLOR || kind == EditSurfaceKindDto.TYPOGRAPHY
        }
        return listOfNotNull(roleDecision.note, rendererCaveat, scored.action)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("; ")
            .takeIf { it.isNotBlank() }
    }
```

Update `emptySourceCandidate`:

```kotlin
        return EditSurfaceCandidateDto(
            kind = kind,
            role = roleDecision.role,
            file = "(visual area)",
            confidence = scored.confidence,
            confidenceBasis = scored.basis,
            reasons = intent.reasons,
            note = noteFor(kind, roleDecision, scored),
        )
```

Update `SourceCandidate.toEditSurface`:

```kotlin
        return EditSurfaceCandidateDto(
            kind = kind,
            role = roleDecision.role,
            file = file,
            repoFile = repoFile,
            line = line,
            confidence = scored.confidence,
            confidenceBasis = scored.basis,
            reasons = reasons.distinct(),
            note = noteFor(kind, roleDecision, scored),
        )
```

- [ ] **Step 4: Run candidate service tests and verify pass**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditSurfaceCandidateServiceTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/editsurface/EditSurfaceCandidateService.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateServiceTest.kt
git commit -m "feat: carry edit surface action guidance"
```

---

### Task 3: Render And Document Role Action Guidance

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/CompactHandoffRenderer.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `docs/reference/mcp-tools.md`
- Modify: `docs/reference/output-schema.md`

**Interfaces:**
- Consumes: `EditSurfaceCandidateDto.note` populated by Task 2.
- Produces:
  - Compact handoff line: `editSurface: ... conf=<level> ... basis=<basis>`
  - Compact action line after edit-surface line: `  action: <guidance>`
  - Source-candidate `caution` still renders as `  note: <caution>`.

- [ ] **Step 1: Add failing compact renderer tests**

Append these tests to `CompactHandoffRendererTest.kt`:

```kotlin
    @Test
    fun compactHandoffRendersEditSurfaceActionGuidance() {
        val session = SessionDto(
            sessionId = "action-session",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto(screenId = "screen-1", capturedAtEpochMillis = 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                    comment = "Tighten this gap",
                    sequenceNumber = 1,
                    editSurfaceCandidates = listOf(
                        EditSurfaceCandidateDto(
                            kind = EditSurfaceKindDto.SPACING,
                            role = EditSurfaceRoleDto.LAYOUT_OR_STYLE,
                            file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt",
                            line = 12,
                            confidence = SelectionConfidence.LOW,
                            reasons = listOf(EditSurfaceReasonDto.LAYOUT_INTENT),
                            confidenceBasis = "layout/style edit applies at the call site",
                            note = "treat layout renderer context as an edit hint and verify before editing",
                        ),
                    ),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)

        assertTrue(markdown.contains("editSurface: spacing"), markdown)
        assertTrue(markdown.contains("basis=layout/style edit applies at the call site"), markdown)
        assertTrue(markdown.contains("  action: treat layout renderer context as an edit hint and verify before editing"), markdown)
    }

    @Test
    fun compactHandoffKeepsInteropActionVerificationFirst() {
        val session = SessionDto(
            sessionId = "interop-action-session",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto(screenId = "screen-1", capturedAtEpochMillis = 1L, displayName = "Diagnostics")),
            items = listOf(
                AnnotationDto(
                    itemId = "item-interop",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(10f, 20f, 200f, 120f)),
                    comment = "Fix native chart spacing",
                    sequenceNumber = 1,
                    editSurfaceCandidates = listOf(
                        EditSurfaceCandidateDto(
                            kind = EditSurfaceKindDto.UNKNOWN,
                            role = EditSurfaceRoleDto.INTEROP_RISK,
                            file = "(visual area)",
                            confidence = SelectionConfidence.LOW,
                            confidenceBasis = "interop boundary: verify runtime target before editing",
                            note = "verify runtime target and boundary context before editing",
                        ),
                    ),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)

        assertTrue(markdown.contains("role=interop-risk"), markdown)
        assertTrue(markdown.contains("conf=low"), markdown)
        assertTrue(markdown.contains("  action: verify runtime target and boundary context before editing"), markdown)
        assertTrue(!markdown.contains("exact View"), markdown)
        assertTrue(!markdown.contains("exact WebView"), markdown)
    }
```

- [ ] **Step 2: Add failing corpus-level action guidance test**

Append this test to `HandoffEvaluationCorpusTest.kt`:

```kotlin
    @Test
    fun corpusItemsWithEditSurfaceRolesRenderActionGuidance() {
        val corpus = HandoffEvaluationFixtures.loadCorpus()
        val items = corpus.cases.map { HandoffEvaluationFixtures.annotationFor(it) }
        val session = SessionDto(
            sessionId = "handoff-action-eval",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = corpus.cases.map { HandoffEvaluationFixtures.screenFor(it) },
            items = items.mapIndexed { index, item -> item.copy(sequenceNumber = index + 1) },
        )

        val markdown = CompactHandoffRenderer.render(session)
        val roleLines = markdown.lines().filter { it.trimStart().startsWith("editSurface:") && it.contains("role=") }
        val actionLines = markdown.lines().filter { it == "  action: " || it.startsWith("  action: ") }

        assertTrue(roleLines.isNotEmpty(), markdown)
        assertTrue(
            actionLines.size >= roleLines.size,
            "Expected at least one action line per role-bearing editSurface. roles=${roleLines.size}, actions=${actionLines.size}\n$markdown",
        )
    }
```

- [ ] **Step 3: Run renderer and corpus tests and verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest" --tests "*HandoffEvaluationCorpusTest" --no-daemon
```

Expected: FAIL because edit-surface `note` renders as `note:` or is absent in corpus-generated candidates.

- [ ] **Step 4: Render edit-surface notes as action lines**

In `CompactHandoffRenderer.kt`, replace the `appendEditSurfaceBlock` note branch with this:

```kotlin
    private fun StringBuilder.appendEditSurfaceBlock(item: AnnotationDto) {
        item.editSurfaceCandidates.take(2).forEach { candidate ->
            appendLine("  ${candidate.formatEditSurfaceLine()}")
            candidate.note?.takeIf { it.isNotBlank() }?.let { action ->
                appendLine("  action: ${action.inlineSafe()}")
            }
        }
    }
```

Do not change `formatCandidateLine` or the source-candidate caution rendering. Source-candidate cautions must continue to produce `note:` lines.

- [ ] **Step 5: Update reference docs**

In `docs/reference/feedback-console-contract.md`, replace the compact grammar line for edit surface with:

```text
edit_surface_line  = "  editSurface: " kind [ "  role=" role ] " -> " file [ ":" line ] "  conf=" lvl "  why=[" terms "]" [ "  basis=" text ]
edit_surface_action_line = "  action: " text
```

Then add this paragraph below the `editSurface role=` bullets:

```markdown
- `action:` after an `editSurface:` line is role-specific agent guidance. It is rendered from the optional edit-surface candidate `note` field and does not add a persisted JSON field.
```

In `docs/reference/mcp-tools.md`, replace the paragraph beginning `Compact Markdown may also include up to two editSurface:` with:

```markdown
Compact Markdown may also include up to two `editSurface:` lines before source
candidate lines. These lines can include `role=<role>` tokens such as
`call-site`, `component-definition`, `copy-or-data`, `layout-or-style`,
`visual-area`, or `interop-risk`, plus `basis=` text when the scorer has an
explainable confidence basis. A following `action:` line is role-specific
guidance rendered from the optional edit-surface candidate `note` field. For
style/layout requests, agents should inspect `editSurface` before editing a
source-origin data candidate. Source candidates remain useful for identifying
which repeated item or data value the user selected.
```

In `docs/reference/output-schema.md`, add this bullet under `editSurfaceCandidates`:

```markdown
- `confidenceBasis`: optional human-readable basis for the role-specific confidence.
```

Replace the existing `note` bullet with:

```markdown
- `note`: optional role-specific action guidance or caveat for compact handoffs. In compact Markdown, edit-surface notes render as `action:` lines; source-candidate cautions still render as `note:` lines.
```

- [ ] **Step 6: Run renderer and corpus tests and verify pass**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest" --tests "*HandoffEvaluationCorpusTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit Task 3**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/CompactHandoffRenderer.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt \
  docs/reference/feedback-console-contract.md docs/reference/mcp-tools.md docs/reference/output-schema.md
git commit -m "docs: render edit surface action guidance"
```

---

### Task 4: Run Evidence And Update Graphify

**Files:**
- Modify: `graphify-out/` ignored artifacts only, through `graphify update .`

**Interfaces:**
- Consumes: Tasks 1 through 3.
- Produces: Fresh verification evidence for role-specific edit-surface confidence.

- [ ] **Step 1: Run focused MCP tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditSurfaceConfidencePolicyTest" --tests "*EditSurfaceCandidateServiceTest" --tests "*CompactHandoffRendererTest" --tests "*HandoffEvaluationCorpusTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Run handoff evaluation evidence**

Run:

```bash
npm run handoff:eval:test
```

Expected: PASS.

- [ ] **Step 3: Run source-matching fixture contracts**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS.

- [ ] **Step 4: Run release-readiness consistency check**

Run:

```bash
node scripts/check-release-readiness.mjs
```

Expected: PASS with all release-readiness rules passing, including the role-specific edit-surface confidence claim.

- [ ] **Step 5: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 6: Refresh Graphify**

Run:

```bash
graphify update .
```

Expected: command completes. Dirty `graphify-out/` files are ignored artifacts and should not be staged.

- [ ] **Step 7: Inspect final tracked diff**

Run:

```bash
git status --short
git diff --stat
```

Expected: tracked changes are limited to the files named in Tasks 1 through 3. Existing untracked `docs/superpowers/plans/2026-06-27-android-release-evidence-consolidation.md` may still appear and must remain unstaged unless the user explicitly asks to include it.

- [ ] **Step 8: Leave Task 4 with no tracked commit**

Task 4 is verification-only. If a command in this task exposes a tracked source,
test, or doc issue, return to Task 1, 2, or 3 and fix it in that task's commit
boundary. After `graphify update .`, do not stage `graphify-out/`.

Run:

```bash
git status --short
```

Expected: no new staged files. Ignored `graphify-out/` changes may exist and
are not part of the commit set.

---

## Self-Review

Spec coverage:

- Role confidence contract: Task 1.
- Apply contract in `EditSurfaceConfidencePolicy`: Task 1.
- Keep source confidence and edit-surface confidence separate: Task 1 policy tests.
- Shared-component definition versus recommended call-site guidance: Task 2.
- Compact handoff role/confidence/basis/action rendering: Task 3.
- Compatibility without persisted field rename: Task 2 uses `note`; Task 3 docs the rendering.
- Focused evidence and release-readiness proof: Task 4.

Type consistency:

- `EditSurfaceConfidenceResult.action` is introduced in Task 1 and consumed in Task 2.
- `EditSurfaceCandidateDto.note` already exists and remains optional.
- `CompactHandoffRenderer.appendEditSurfaceBlock` consumes `candidate.note` only for edit-surface action lines.
- Source candidate `caution` is separate and continues to render as `note:`.
