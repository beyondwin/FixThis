# Android Agent Evidence Umbrella Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add explicit agent verification guidance, optional Android runtime evidence attachments, and Codex plugin packaging around the existing FixThis source-aware feedback loop.

**Architecture:** Keep the core handoff model additive: `:fixthis-mcp` derives agent verification guidance and stores runtime evidence references, while `:fixthis-compose-core` remains independent of MCP, CLI, Android UI, and `.fixthis/` paths. Runtime evidence capture starts with bounded local summaries and artifact paths, not raw log or trace dumps. Codex plugin packaging wraps the existing CLI/MCP workflows instead of duplicating FixThis runtime behavior.

**Tech Stack:** Kotlin/JVM with kotlinx.serialization, existing FixThis MCP and console routes, Node.js 20 script tests, Codex plugin manifest and skills, Gradle test commands, Markdown reference docs.

Design: [`docs/superpowers/specs/2026-06-22-android-agent-evidence-umbrella-design.md`](../specs/2026-06-22-android-agent-evidence-umbrella-design.md).

## Global Constraints

- No release-build support. FixThis remains debug-only.
- No XML/View exact source targeting and no WebView DOM inspection.
- No automatic app code edits during FixThis evidence capture.
- No cloud upload, external AI API call, or remote artifact storage.
- No bridge-protocol breaking change.
- No persisted field rename for `items`, `screens`, `itemId`, `screenId`, `targetEvidence`, `targetReliability`, or `sourceCandidates`.
- No committed `.fixthis/`, `graphify-out/`, Android build output, screenshots, generated reports, log captures, traces, or local fixture workspaces.
- No requirement that all Android apps use runtime evidence attachments before using Copy Prompt or Save to MCP.
- `:fixthis-compose-core` must stay free of MCP, CLI, Android UI, and `.fixthis/` path dependencies.
- All persisted schema changes must be optional and additive.

---

## File Structure

- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/AgentVerificationGuidance.kt`
  - Pure renderer-side classifier for item-level verification posture.
  - Produces `source-first`, `corroborate`, `hint-only`, and `manual` modes.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/CompactHandoffRenderer.kt`
  - Render `verify:` and `verifyBeforeEdit:` lines after existing target action and warning lines.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
  - Cover all verification modes and ordering.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/dto/SessionDtoModels.kt`
  - Add optional session-level `runtimeEvidence` and item-level `runtimeEvidenceIds`.
- Leave `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/dto/SessionDomainMappers.kt` unchanged.
  - Runtime evidence is MCP-session-local in this plan and does not enter `:fixthis-compose-core`.
- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceModels.kt`
  - Runtime evidence enums and serializers.
- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceService.kt`
  - Session update service that writes bounded evidence summaries and artifact paths.
- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/AndroidRuntimeEvidenceCapture.kt`
  - Narrow local capture helper for logcat window, frame summary, and memory summary.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FeedbackToolOperations.kt`
  - Add `captureRuntimeEvidence(arguments: JsonObject)`.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/handlers/DefaultMcpToolHandlers.kt`
  - Register `fixthis_capture_runtime_evidence`.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/McpToolRegistry.kt`
  - Add MCP tool definition and schema.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt`
  - Add `POST /api/items/{itemId}/runtime-evidence`.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/AnnotationRequestModels.kt`
  - Add `RuntimeEvidenceRequest`.
- Modify `fixthis-mcp/src/main/console/*`
  - Add a minimal saved-item "Attach evidence" action only after backend behavior is tested.
- Create `scripts/runtime-evidence-smoke.mjs`
  - Connected local smoke for strict/non-strict runtime evidence capture.
- Create `scripts/runtime-evidence-smoke-test.mjs`
  - Fast script contract tests.
- Modify `package.json`
  - Add `runtime-evidence:smoke` and `runtime-evidence:smoke:test`.
- Modify `scripts/evidence-runner.mjs` and `scripts/evidence-runner-test.mjs`
  - Add runtime evidence to trust or release profiles with strict/non-strict deferral semantics.
- Create `.codex-plugin/plugin.json`
  - Codex plugin manifest for FixThis workflows.
- Create `.codex-plugin/skills/fixthis-install-agent/SKILL.md`
- Create `.codex-plugin/skills/fixthis-feedback-loop/SKILL.md`
- Create `.codex-plugin/skills/fixthis-android-evidence/SKILL.md`
- Create `.codex-plugin/skills/fixthis-release-smoke/SKILL.md`
  - Workflow-only skills that point to canonical docs and commands.
- Create `scripts/fixthis-plugin-contract-test.mjs`
  - Validates plugin manifest, skill files, required commands, and safety language.
- Modify `docs/reference/feedback-console-contract.md`
  - Document `verify:`, `verifyBeforeEdit:`, and runtime evidence compact grammar.
- Modify `docs/reference/mcp-tools.md`
  - Document `fixthis_capture_runtime_evidence`.
- Modify `docs/getting-started/connect-your-agent.md`
  - Mention Codex plugin packaging only after plugin tests pass.
- Modify `docs/contributing/release-readiness.md`
  - Add runtime evidence and plugin validation evidence rows.

## Task 1: Agent Verification Guidance Projection

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/AgentVerificationGuidance.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/CompactHandoffRenderer.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
- Modify: `docs/reference/feedback-console-contract.md`

**Interfaces:**
- Consumes: `AnnotationDto`, `AnnotationTargetDto`, `TargetReliability`, source candidates, edit-surface candidates, overlap/duplicate analysis from `CompactHandoffRenderer`.
- Produces:
  - `internal enum class AgentVerificationMode { SOURCE_FIRST, CORROBORATE, HINT_ONLY, MANUAL }`
  - `internal data class AgentVerificationGuidance(val mode: AgentVerificationMode, val reasons: List<String>, val beforeEdit: List<String>)`
  - `internal object AgentVerificationGuidanceClassifier { fun classify(item: AnnotationDto, isOverlap: Boolean, hasDuplicateReference: Boolean): AgentVerificationGuidance }`
  - Compact Markdown lines:
    - `verify: <mode>  because=<comma-separated-reasons>`
    - `verifyBeforeEdit: <comma-separated-actions>`

- [ ] **Step 1: Write failing renderer tests for verification modes**

Append these tests to `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`:

```kotlin
@Test
fun compactHandoffRendersSourceFirstVerificationForStrongEvidence() {
    val markdown = CompactHandoffRenderer.render(
        oneItemSession(
            AnnotationDto(
                itemId = "item-source-first",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node("node-1", FixThisRect(1f, 2f, 30f, 40f)),
                selectedNode = nodeWithText("node-1", "Save"),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/kotlin/Home.kt",
                        line = 42,
                        score = 0.95,
                        confidence = SelectionConfidence.HIGH,
                        scoreMargin = 0.55,
                        matchReasons = listOf("selected text", "selected testTag"),
                    ),
                ),
                comment = "Tighten the save button spacing",
                sequenceNumber = 1,
                targetReliability = TargetReliability(confidence = TargetConfidence.HIGH),
            ),
        ),
    )

    assertTrue(markdown.contains("  verify: source-first  because=strong-target,strong-source,clear-margin"))
    assertTrue(markdown.contains("  verifyBeforeEdit: claim-feedback,inspect-source,compare-screenshot"))
}

@Test
fun compactHandoffDowngradesVisualAreaToManualVerification() {
    val markdown = CompactHandoffRenderer.render(
        oneItemSession(
            AnnotationDto(
                itemId = "item-area",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 30f, 40f)),
                sourceCandidates = emptyList(),
                comment = "This empty space is too large",
                sequenceNumber = 1,
                targetReliability = TargetReliability(
                    confidence = TargetConfidence.LOW,
                    warnings = listOf(TargetReliabilityWarning.VISUAL_AREA_ONLY),
                ),
            ),
        ),
    )

    assertTrue(markdown.contains("  verify: manual  because=visual-area,missing-source"))
    assertTrue(markdown.contains("  verifyBeforeEdit: claim-feedback,compare-screenshot,verify-manually"))
    assertTrue(!markdown.contains("verify: source-first"))
}

@Test
fun compactHandoffDowngradesInteropToHintOnly() {
    val markdown = CompactHandoffRenderer.render(
        oneItemSession(
            AnnotationDto(
                itemId = "item-interop",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node("node-interop", FixThisRect(1f, 2f, 30f, 40f)),
                selectedNode = nodeWithText("node-interop", "Chart"),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/kotlin/ChartHost.kt",
                        line = 17,
                        score = 0.68,
                        confidence = SelectionConfidence.MEDIUM,
                        matchReasons = listOf("selected text"),
                    ),
                ),
                comment = "Fix chart label clipping",
                sequenceNumber = 1,
                targetReliability = TargetReliability(
                    confidence = TargetConfidence.LOW,
                    warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
                ),
            ),
        ),
    )

    assertTrue(markdown.contains("  verify: hint-only  because=interop-risk,low-target-confidence"))
    assertTrue(markdown.contains("  verifyBeforeEdit: claim-feedback,compare-screenshot,review-edit-surface,verify-manually"))
}
```

Add this helper near the existing helpers:

```kotlin
private fun nodeWithText(uid: String, text: String): FixThisNode = FixThisNode(
    uid = uid,
    composeNodeId = 1,
    rootIndex = 0,
    treeKind = TreeKind.MERGED,
    boundsInWindow = FixThisRect(1f, 2f, 30f, 40f),
    text = listOf(text),
)
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest" --no-daemon
```

Expected: FAIL because `verify:` and `verifyBeforeEdit:` lines are not rendered.

- [ ] **Step 3: Add the guidance classifier**

Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/AgentVerificationGuidance.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session.handoff

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto

internal enum class AgentVerificationMode(val wire: String) {
    SOURCE_FIRST("source-first"),
    CORROBORATE("corroborate"),
    HINT_ONLY("hint-only"),
    MANUAL("manual"),
}

internal data class AgentVerificationGuidance(
    val mode: AgentVerificationMode,
    val reasons: List<String>,
    val beforeEdit: List<String>,
) {
    fun verifyLine(): String = "verify: ${mode.wire}  because=${reasons.joinToString(",")}"
    fun beforeEditLine(): String = "verifyBeforeEdit: ${beforeEdit.joinToString(",")}"
}

internal object AgentVerificationGuidanceClassifier {
    fun classify(
        item: AnnotationDto,
        isOverlap: Boolean,
        hasDuplicateReference: Boolean,
    ): AgentVerificationGuidance {
        val warnings = item.targetReliability?.warnings.orEmpty()
        val confidence = item.targetReliability?.confidence ?: TargetConfidence.UNKNOWN
        val top = item.sourceCandidates.firstOrNull()
        val isVisualArea = item.target is AnnotationTargetDto.Area ||
            warnings.contains(TargetReliabilityWarning.VISUAL_AREA_ONLY)
        val isInterop = warnings.contains(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP)
        val isStale = warnings.contains(TargetReliabilityWarning.SOURCE_INDEX_STALE) ||
            item.sourceCandidates.any { it.stale == true }
        val isSensitive = warnings.contains(TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED)
        val hasClearMargin = (top?.scoreMargin ?: 0.0) >= 0.50
        val hasStrongSource = top?.confidence == SelectionConfidence.HIGH
        val hasStrongTarget = confidence == TargetConfidence.HIGH

        return when {
            isVisualArea || item.sourceCandidates.isEmpty() || isSensitive -> AgentVerificationGuidance(
                mode = AgentVerificationMode.MANUAL,
                reasons = listOfNotNull(
                    "visual-area".takeIf { isVisualArea },
                    "missing-source".takeIf { item.sourceCandidates.isEmpty() },
                    "sensitive-target".takeIf { isSensitive },
                ).ifEmpty { listOf("manual-verification") },
                beforeEdit = listOf("claim-feedback", "compare-screenshot", "verify-manually"),
            )
            isInterop || isStale || isOverlap || hasDuplicateReference -> AgentVerificationGuidance(
                mode = AgentVerificationMode.HINT_ONLY,
                reasons = listOfNotNull(
                    "interop-risk".takeIf { isInterop },
                    "stale-source".takeIf { isStale },
                    "overlap".takeIf { isOverlap },
                    "duplicate-marker".takeIf { hasDuplicateReference },
                    "low-target-confidence".takeIf { confidence == TargetConfidence.LOW },
                ).ifEmpty { listOf("hint-only") },
                beforeEdit = listOf("claim-feedback", "compare-screenshot", "review-edit-surface", "verify-manually"),
            )
            hasStrongTarget && hasStrongSource && hasClearMargin -> AgentVerificationGuidance(
                mode = AgentVerificationMode.SOURCE_FIRST,
                reasons = listOf("strong-target", "strong-source", "clear-margin"),
                beforeEdit = listOf("claim-feedback", "inspect-source", "compare-screenshot"),
            )
            else -> AgentVerificationGuidance(
                mode = AgentVerificationMode.CORROBORATE,
                reasons = listOf("mixed-evidence"),
                beforeEdit = listOf("claim-feedback", "inspect-source", "compare-screenshot", "check-target-summary"),
            )
        }
    }
}
```

- [ ] **Step 4: Render guidance from compact handoff**

In `CompactHandoffRenderer.appendCompactItem`, after `appendReliabilityBlock(item.targetReliability)`, add:

```kotlin
appendVerificationGuidance(
    AgentVerificationGuidanceClassifier.classify(
        item = item,
        isOverlap = context.isOverlap,
        hasDuplicateReference = context.dupRefMarker != null,
    ),
)
```

Add this helper in `CompactHandoffRenderer`:

```kotlin
private fun StringBuilder.appendVerificationGuidance(guidance: AgentVerificationGuidance) {
    appendLine("  ${guidance.verifyLine()}")
    appendLine("  ${guidance.beforeEditLine()}")
}
```

- [ ] **Step 5: Run renderer tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Document compact grammar**

Modify `docs/reference/feedback-console-contract.md` compact handoff schema so `reliability_block` includes:

```text
verification_block = verify_line verify_before_edit_line
verify_line = "  verify: " ("source-first" | "corroborate" | "hint-only" | "manual") "  because=" token_list
verify_before_edit_line = "  verifyBeforeEdit: " token_list
```

Add bullets:

```markdown
- `verify:` is an agent-facing verification posture derived from target confidence, source confidence, warnings, overlap, duplicate, and runtime evidence state. It does not replace `targetConfidence`.
- `verifyBeforeEdit:` lists concrete checks the agent should perform after `fixthis_claim_feedback` and before editing code.
```

- [ ] **Step 7: Commit**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/AgentVerificationGuidance.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/CompactHandoffRenderer.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt \
  docs/reference/feedback-console-contract.md
git commit -m "feat(handoff): add agent verification guidance"
```

## Task 2: Runtime Evidence Session Model And Rendering

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/dto/SessionDtoModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/CompactHandoffRenderer.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
- Modify: `docs/reference/output-schema.md`
- Modify: `docs/reference/feedback-console-contract.md`

**Interfaces:**
- Produces:
  - `RuntimeEvidenceAttachment`
  - `RuntimeEvidenceType`
  - `EvidenceTimeRange`
  - `RuntimeEvidenceWarning`
  - `SessionDto.runtimeEvidence: List<RuntimeEvidenceAttachment> = emptyList()`
  - `AnnotationDto.runtimeEvidenceIds: List<String> = emptyList()`
- Consumes in renderer: session-level evidence collection and item-level IDs.

- [ ] **Step 1: Write failing serialization and renderer tests**

Append to `CompactHandoffRendererTest.kt`:

```kotlin
@Test
fun compactHandoffRendersRuntimeEvidenceSummariesOnly() {
    val session = oneItemSession(
        AnnotationDto(
            itemId = "item-runtime",
            screenId = "screen-1",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 30f, 40f)),
            comment = "The screen janks when this opens",
            sequenceNumber = 1,
            runtimeEvidenceIds = listOf("e-logcat", "e-frame"),
        ),
    ).copy(
        runtimeEvidence = listOf(
            RuntimeEvidenceAttachment(
                evidenceId = "e-logcat",
                type = RuntimeEvidenceType.LOGCAT_WINDOW,
                capturedAtEpochMillis = 10L,
                packageName = "io.github.beyondwin.fixthis.sample",
                summary = "2 RuntimeException lines from MainActivity",
                artifactPath = ".fixthis/runtime-evidence/e-logcat/logcat.txt",
            ),
            RuntimeEvidenceAttachment(
                evidenceId = "e-frame",
                type = RuntimeEvidenceType.FRAME_SUMMARY,
                capturedAtEpochMillis = 11L,
                packageName = "io.github.beyondwin.fixthis.sample",
                summary = "6 slow frames, 1 frozen frame candidate",
                artifactPath = ".fixthis/runtime-evidence/e-frame/gfxinfo.json",
            ),
        ),
    )

    val markdown = CompactHandoffRenderer.render(session)

    assertTrue(markdown.contains("  runtimeEvidence:"))
    assertTrue(markdown.contains("    - logcat_window -> .fixthis/runtime-evidence/e-logcat/logcat.txt"))
    assertTrue(markdown.contains("      summary: 2 RuntimeException lines from MainActivity"))
    assertTrue(markdown.contains("    - frame_summary -> .fixthis/runtime-evidence/e-frame/gfxinfo.json"))
    assertTrue(!markdown.contains("full raw log line"))
}
```

Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/RuntimeEvidenceSerializationTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceAttachment
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeEvidenceSerializationTest {
    @Test
    fun runtimeEvidenceAttachmentSerializesWithStableWireNames() {
        val json = fixThisJson.encodeToString(
            RuntimeEvidenceAttachment.serializer(),
            RuntimeEvidenceAttachment(
                evidenceId = "e-1",
                type = RuntimeEvidenceType.LOGCAT_WINDOW,
                capturedAtEpochMillis = 1L,
                packageName = "io.example",
                summary = "summary",
                artifactPath = ".fixthis/runtime-evidence/e-1/logcat.txt",
            ),
        )

        assertTrue(json.contains(""""type":"logcat_window""""))
        assertTrue(json.contains(""""artifactPath":".fixthis/runtime-evidence/e-1/logcat.txt""""))
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest" --tests "*RuntimeEvidenceSerializationTest" --no-daemon
```

Expected: FAIL because runtime evidence models and fields do not exist.

- [ ] **Step 3: Add runtime evidence models**

Create `RuntimeEvidenceModels.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session.runtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeEvidenceAttachment(
    val evidenceId: String,
    val type: RuntimeEvidenceType,
    val capturedAtEpochMillis: Long,
    val deviceSerial: String? = null,
    val packageName: String,
    val timeRangeEpochMillis: EvidenceTimeRange? = null,
    val summary: String,
    val artifactPath: String? = null,
    val captureCommand: String? = null,
    val warnings: List<RuntimeEvidenceWarning> = emptyList(),
)

@Serializable
enum class RuntimeEvidenceType {
    @SerialName("logcat_window")
    LOGCAT_WINDOW,

    @SerialName("frame_summary")
    FRAME_SUMMARY,

    @SerialName("memory_summary")
    MEMORY_SUMMARY,

    @SerialName("trace_artifact")
    TRACE_ARTIFACT,
}

@Serializable
data class EvidenceTimeRange(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

@Serializable
enum class RuntimeEvidenceWarning {
    @SerialName("capture_deferred")
    CAPTURE_DEFERRED,

    @SerialName("sensitive_logs_possible")
    SENSITIVE_LOGS_POSSIBLE,

    @SerialName("artifact_missing")
    ARTIFACT_MISSING,
}
```

- [ ] **Step 4: Add optional fields to DTOs**

Modify `SessionDtoModels.kt` imports:

```kotlin
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceAttachment
```

Add to `SessionDto`:

```kotlin
val runtimeEvidence: List<RuntimeEvidenceAttachment> = emptyList(),
```

Add to `AnnotationDto`:

```kotlin
val runtimeEvidenceIds: List<String> = emptyList(),
```

- [ ] **Step 5: Render bounded runtime evidence summaries**

In `CompactHandoffRenderer`, pass `effectiveSession.runtimeEvidence` into item rendering. Add a `runtimeEvidenceById` map near `itemsByScreen`:

```kotlin
val runtimeEvidenceById = effectiveSession.runtimeEvidence.associateBy { it.evidenceId }
```

Add `runtimeEvidenceById: Map<String, RuntimeEvidenceAttachment>` to `CompactItemRenderContext`.

After `appendVerificationGuidance(...)`, call:

```kotlin
appendRuntimeEvidenceBlock(item, context.runtimeEvidenceById)
```

Add helper:

```kotlin
private fun StringBuilder.appendRuntimeEvidenceBlock(
    item: AnnotationDto,
    runtimeEvidenceById: Map<String, RuntimeEvidenceAttachment>,
) {
    val evidence = item.runtimeEvidenceIds.mapNotNull(runtimeEvidenceById::get).take(3)
    if (evidence.isEmpty()) return
    appendLine("  runtimeEvidence:")
    evidence.forEach { attachment ->
        val path = attachment.artifactPath ?: "no-artifact"
        appendLine("    - ${attachment.type.name.lowercase()} -> ${path.inlineSafe()}")
        appendLine("      summary: ${attachment.summary.take(180).inlineSafe()}")
    }
}
```

Import `RuntimeEvidenceAttachment`.

- [ ] **Step 6: Run tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest" --tests "*RuntimeEvidenceSerializationTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Document schema**

Add optional fields to `docs/reference/output-schema.md`:

```markdown
- `runtimeEvidence` on a session is optional and additive. It contains bounded local evidence summaries and artifact paths.
- `runtimeEvidenceIds` on an item is optional and additive. It references session-level runtime evidence by id.
- Runtime evidence artifacts are local files and must not be committed.
```

Add compact grammar notes to `docs/reference/feedback-console-contract.md`:

```text
runtime_evidence_block = "  runtimeEvidence:" runtime_evidence_line{1,3}
runtime_evidence_line = "    - " type " -> " artifact_or_no_artifact "\n      summary: " bounded_text
```

- [ ] **Step 8: Commit**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceModels.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/dto/SessionDtoModels.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/CompactHandoffRenderer.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/RuntimeEvidenceSerializationTest.kt \
  docs/reference/output-schema.md \
  docs/reference/feedback-console-contract.md
git commit -m "feat(runtime-evidence): add session evidence references"
```

## Task 3: Runtime Evidence Capture Tool And Console Route

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/AndroidRuntimeEvidenceCapture.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceService.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FeedbackToolOperations.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/handlers/DefaultMcpToolHandlers.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/McpToolRegistry.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/AnnotationRequestModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
- Modify: `docs/reference/mcp-tools.md`

**Interfaces:**
- Produces:
  - `RuntimeEvidenceCaptureRequest(sessionId: String?, itemId: String?, type: RuntimeEvidenceType, durationMillis: Long?, filter: String?)`
  - `FeedbackSessionService.captureRuntimeEvidence(request): SessionDto`
  - MCP tool `fixthis_capture_runtime_evidence`
  - HTTP route `POST /api/items/{itemId}/runtime-evidence`
- Consumes: existing session store update path and artifact directory under `.fixthis/runtime-evidence/`.

- [ ] **Step 1: Write failing MCP registry and route tests**

Append to `ConsoleFeedbackItemRoutesTest.kt`:

```kotlin
@Test
fun runtimeEvidenceRouteAttachesEvidenceToRequestedItem() {
    val fixture = newConsoleSessionFixtureWithTempRoot(
        idGenerator = FakeIds("session-1", "item-1", "evidence-1").next,
    )
    fixture.use { context ->
        val service = context.service
        val store = context.store
        val server = context.server
        val session = service.openSession(null, newSession = true)
        store.addScreen(
            session.sessionId,
            SnapshotDto(screenId = "screen-1", capturedAtEpochMillis = 100L, displayName = "Screen"),
        )
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                comment = "Needs evidence",
            ),
        )

        val response = ConsoleHttpTestClient(server.url).postJson(
            "/api/items/item-1/runtime-evidence",
            """{"sessionId":"session-1","type":"logcat_window","summary":"2 warnings","artifactPath":".fixthis/runtime-evidence/evidence-1/logcat.txt"}""",
        )

        assertEquals(200, response.statusCode, response.body)
        val updated = service.getSession("session-1")
        assertEquals(1, updated.runtimeEvidence.size)
        assertEquals("evidence-1", updated.items.single().runtimeEvidenceIds.single())
    }
}
```

Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/tools/RuntimeEvidenceToolRegistryTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.tools

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

class RuntimeEvidenceToolRegistryTest {
    @Test
    fun toolRegistryExposesRuntimeEvidenceTool() {
        val tools = McpToolRegistry.listTools()
        val names = tools.jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertTrue("fixthis_capture_runtime_evidence" in names)
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleFeedbackItemRoutesTest.runtimeEvidenceRouteAttachesEvidenceToRequestedItem" --tests "*RuntimeEvidenceToolRegistryTest" --no-daemon
```

Expected: FAIL because the route, tool, and service do not exist.

- [ ] **Step 3: Add request model**

In `AnnotationRequestModels.kt`, add:

```kotlin
@Serializable
data class RuntimeEvidenceRequest(
    val sessionId: String? = null,
    val type: RuntimeEvidenceType,
    val durationMillis: Long? = null,
    val filter: String? = null,
    val summary: String? = null,
    val artifactPath: String? = null,
)
```

Import `RuntimeEvidenceType`.

- [ ] **Step 4: Add evidence service**

Create `RuntimeEvidenceService.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session.runtime

import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto

internal class RuntimeEvidenceService(
    private val idGenerator: () -> String,
    private val clock: () -> Long,
) {
    fun attachManualSummary(
        session: SessionDto,
        itemId: String,
        type: RuntimeEvidenceType,
        summary: String,
        artifactPath: String?,
    ): SessionDto {
        val item = session.items.firstOrNull { it.itemId == itemId }
            ?: throw IllegalArgumentException("Unknown feedback item: $itemId")
        val evidenceId = idGenerator()
        val attachment = RuntimeEvidenceAttachment(
            evidenceId = evidenceId,
            type = type,
            capturedAtEpochMillis = clock(),
            packageName = session.packageName,
            summary = summary.take(240),
            artifactPath = artifactPath,
        )
        return session.copy(
            runtimeEvidence = session.runtimeEvidence + attachment,
            items = session.items.map {
                if (it.itemId == item.itemId) {
                    it.copy(runtimeEvidenceIds = (it.runtimeEvidenceIds + evidenceId).distinct())
                } else {
                    it
                }
            },
            updatedAtEpochMillis = clock(),
        )
    }
}
```

- [ ] **Step 5: Add `FeedbackSessionService.captureRuntimeEvidence`**

In `FeedbackSessionService`, add a private property:

```kotlin
private val runtimeEvidenceService = RuntimeEvidenceService(
    idGenerator = { store.nextId() },
    clock = clock,
)
```

Add method:

```kotlin
fun captureRuntimeEvidence(
    sessionId: String,
    itemId: String,
    type: RuntimeEvidenceType,
    summary: String,
    artifactPath: String?,
): SessionDto {
    val session = getSession(sessionId)
    val updated = runtimeEvidenceService.attachManualSummary(session, itemId, type, summary, artifactPath)
    store.replaceSessionForDomain(updated)
    return updated
}
```

- [ ] **Step 6: Add console route**

In `FeedbackItemRoutes.matches`, include:

```kotlin
path.endsWith("/runtime-evidence")
```

Before the generic `/api/items/{itemId}` branch, add:

```kotlin
if (exchange.requestURI.path.startsWith("/api/items/") && exchange.requestURI.path.endsWith("/runtime-evidence")) {
    exchange.requireMethod("POST") {
        val itemId = exchange.requestURI.path
            .removePrefix("/api/items/")
            .removeSuffix("/runtime-evidence")
            .trim('/')
        val request = exchange.decodeJsonBody(RuntimeEvidenceRequest.serializer())
        val sessionId = requestSessionId(request.sessionId)
        val summary = request.summary?.takeIf { it.isNotBlank() }
            ?: throw FeedbackConsoleHttpException(422, "runtime evidence summary is required")
        val session = service.captureRuntimeEvidence(
            sessionId = sessionId,
            itemId = itemId,
            type = request.type,
            summary = summary,
            artifactPath = request.artifactPath,
        )
        eventBus.emitSessionUpdated(session)
        exchange.sendJson(200, session)
    }
    return
}
```

- [ ] **Step 7: Add MCP tool**

In `FeedbackToolOperations`, add:

```kotlin
internal suspend fun captureRuntimeEvidence(arguments: JsonObject): JsonObject = bridgeToolResult {
    val session = requestedSession(arguments)
    val itemId = arguments.stringParam("itemId")?.takeIf { it.isNotBlank() }
        ?: throw FixThisToolException("fixthis_capture_runtime_evidence requires itemId")
    val type = arguments.stringParam("type")?.toRuntimeEvidenceType()
        ?: throw FixThisToolException("fixthis_capture_runtime_evidence requires type")
    val summary = arguments.stringParam("summary")?.takeIf { it.isNotBlank() }
        ?: throw FixThisToolException("fixthis_capture_runtime_evidence requires summary")
    val updated = feedbackService.captureRuntimeEvidence(
        sessionId = session.sessionId,
        itemId = itemId,
        type = type,
        summary = summary,
        artifactPath = arguments.stringParam("artifactPath"),
    )
    jsonToolResult(McpProtocol.json.encodeToJsonElement(SessionDto.serializer(), updated).jsonObject)
}

private fun String.toRuntimeEvidenceType(): RuntimeEvidenceType = when (this) {
    "logcat_window" -> RuntimeEvidenceType.LOGCAT_WINDOW
    "frame_summary" -> RuntimeEvidenceType.FRAME_SUMMARY
    "memory_summary" -> RuntimeEvidenceType.MEMORY_SUMMARY
    "trace_artifact" -> RuntimeEvidenceType.TRACE_ARTIFACT
    else -> throw FixThisToolException("Unsupported runtime evidence type: $this")
}
```

Register it in `DefaultMcpToolHandlers.kt`:

```kotlin
OperationBackedToolHandler("fixthis_capture_runtime_evidence", feedbackOps::captureRuntimeEvidence),
```

Add registry definition in `McpToolRegistry.kt` with required `itemId`, `type`, and `summary`.

- [ ] **Step 8: Run targeted tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleFeedbackItemRoutesTest.runtimeEvidenceRouteAttachesEvidenceToRequestedItem" --tests "*RuntimeEvidenceToolRegistryTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 9: Document MCP tool**

Add to `docs/reference/mcp-tools.md`:

```markdown
`fixthis_capture_runtime_evidence`

Attaches bounded local runtime evidence to a feedback item. The first supported path stores a summary and optional local artifact path; raw logs and traces are not emitted in compact handoff.

Arguments:

- `sessionId`: optional feedback session id.
- `itemId`: required feedback item id.
- `type`: one of `logcat_window`, `frame_summary`, `memory_summary`, or `trace_artifact`.
- `summary`: required bounded evidence summary.
- `artifactPath`: optional local artifact path under ignored storage.
```

- [ ] **Step 10: Commit**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/RuntimeEvidenceService.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/runtime/AndroidRuntimeEvidenceCapture.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FeedbackToolOperations.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/handlers/DefaultMcpToolHandlers.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/McpToolRegistry.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/AnnotationRequestModels.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/tools/RuntimeEvidenceToolRegistryTest.kt \
  docs/reference/mcp-tools.md
git commit -m "feat(runtime-evidence): expose evidence attachment tool"
```

## Task 4: Runtime Evidence Script, Console Affordance, And Evidence Profile

**Files:**
- Create: `scripts/runtime-evidence-smoke.mjs`
- Create: `scripts/runtime-evidence-smoke-test.mjs`
- Modify: `package.json`
- Modify: `scripts/evidence-runner.mjs`
- Modify: `scripts/evidence-runner-test.mjs`
- Modify: `fixthis-mcp/src/main/console/*.js`
- Modify: `fixthis-mcp/src/main/console/presentation/annotationDetailView.js`
- Modify: `fixthis-mcp/src/main/console/api.js`
- Modify: `scripts/run-console-tests.mjs`
- Modify: `docs/reference/feedback-console-contract.md`

**Interfaces:**
- Consumes: `fixthis_capture_runtime_evidence` from Task 3.
- Produces:
  - `npm run runtime-evidence:smoke`
  - `npm run runtime-evidence:smoke:test`
  - Console action "Attach evidence" for saved items.
  - Evidence runner step `Runtime evidence attachment`.

- [ ] **Step 1: Write script contract tests**

Create `scripts/runtime-evidence-smoke-test.mjs`:

```js
import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildRuntimeEvidenceReport,
  normalizeRuntimeEvidenceStatus,
  renderRuntimeEvidenceMarkdown,
  selectRuntimeEvidenceCommand,
} from './runtime-evidence-smoke.mjs';

test('normalizeRuntimeEvidenceStatus defers missing Android prerequisites in non-strict mode', () => {
  assert.deepEqual(
    normalizeRuntimeEvidenceStatus({ strict: false, androidReady: false, reason: 'No connected Android device.' }),
    { status: 'deferred', reason: 'No connected Android device.' },
  );
  assert.deepEqual(
    normalizeRuntimeEvidenceStatus({ strict: true, androidReady: false, reason: 'No connected Android device.' }),
    { status: 'fail', reason: 'No connected Android device.' },
  );
});

test('selectRuntimeEvidenceCommand maps evidence type to stable command description', () => {
  assert.equal(selectRuntimeEvidenceCommand('logcat_window').label, 'Logcat window');
  assert.equal(selectRuntimeEvidenceCommand('frame_summary').command, 'adb shell dumpsys gfxinfo <package>');
});

test('report markdown includes status type and artifact path', () => {
  const report = buildRuntimeEvidenceReport({
    strict: false,
    status: 'pass',
    evidence: [{
      itemId: 'item-1',
      type: 'logcat_window',
      summary: '2 warnings',
      artifactPath: '.fixthis/runtime-evidence/e-1/logcat.txt',
    }],
  });
  const markdown = renderRuntimeEvidenceMarkdown(report);

  assert.match(markdown, /# FixThis Runtime Evidence Report/);
  assert.match(markdown, /\| item-1 \| logcat_window \| 2 warnings \| `.fixthis\/runtime-evidence\/e-1\/logcat.txt` \|/);
});
```

- [ ] **Step 2: Run script tests to verify failure**

Run:

```bash
node --test scripts/runtime-evidence-smoke-test.mjs
```

Expected: FAIL because `runtime-evidence-smoke.mjs` does not exist.

- [ ] **Step 3: Add script module**

Create `scripts/runtime-evidence-smoke.mjs`:

```js
#!/usr/bin/env node
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

export function normalizeRuntimeEvidenceStatus({ strict = false, androidReady = false, reason = 'Android SDK or ready emulator is unavailable.' } = {}) {
  if (androidReady) return { status: 'pass', reason: null };
  return { status: strict ? 'fail' : 'deferred', reason };
}

export function selectRuntimeEvidenceCommand(type) {
  const commands = {
    logcat_window: { label: 'Logcat window', command: 'adb logcat -d --pid <pid>' },
    frame_summary: { label: 'Frame summary', command: 'adb shell dumpsys gfxinfo <package>' },
    memory_summary: { label: 'Memory summary', command: 'adb shell dumpsys meminfo <package>' },
    trace_artifact: { label: 'Trace artifact', command: 'perfetto or simpleperf capture script' },
  };
  if (!commands[type]) throw new Error(`Unsupported runtime evidence type: ${type}`);
  return commands[type];
}

export function buildRuntimeEvidenceReport({ strict = false, status = 'pass', reason = null, evidence = [] } = {}) {
  return {
    generatedAt: new Date().toISOString(),
    strict,
    status,
    reason,
    evidence,
  };
}

export function renderRuntimeEvidenceMarkdown(report) {
  const lines = [
    '# FixThis Runtime Evidence Report',
    '',
    `Status: ${report.status}`,
    `Strict: ${report.strict}`,
    '',
    '| Item | Type | Summary | Artifact |',
    '| --- | --- | --- | --- |',
  ];
  report.evidence.forEach((entry) => {
    lines.push(`| ${entry.itemId} | ${entry.type} | ${entry.summary} | \`${entry.artifactPath || '-'}\` |`);
  });
  if (report.reason) lines.push('', `Reason: ${report.reason}`);
  return `${lines.join('\n')}\n`;
}

export function writeRuntimeEvidenceReport(report, outDir = 'build/reports/fixthis-runtime-evidence') {
  mkdirSync(outDir, { recursive: true });
  writeFileSync(join(outDir, 'report.json'), `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(join(outDir, 'report.md'), renderRuntimeEvidenceMarkdown(report));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const strict = process.argv.includes('--strict');
  const status = normalizeRuntimeEvidenceStatus({ strict, androidReady: false });
  const report = buildRuntimeEvidenceReport({ strict, status: status.status, reason: status.reason });
  writeRuntimeEvidenceReport(report);
  if (report.status === 'fail') process.exitCode = 1;
}
```

- [ ] **Step 4: Add package scripts and evidence runner step**

In `package.json`, add:

```json
"runtime-evidence:smoke": "node scripts/runtime-evidence-smoke.mjs",
"runtime-evidence:smoke:test": "node --test scripts/runtime-evidence-smoke-test.mjs",
```

In `scripts/evidence-runner.mjs`, add a non-strict trust profile step:

```js
{ name: 'Runtime evidence attachment', command: 'npm run runtime-evidence:smoke' }
```

Add the strict variant to release or full profile:

```js
{ name: 'Runtime evidence attachment strict', command: 'npm run runtime-evidence:smoke -- --strict' }
```

- [ ] **Step 5: Add evidence runner tests**

In `scripts/evidence-runner-test.mjs`, add assertions that the trust profile includes `npm run runtime-evidence:smoke` and strict release/full profile includes `npm run runtime-evidence:smoke -- --strict`.

Use the existing profile helper style in that file. Expected command strings:

```js
'npm run runtime-evidence:smoke'
'npm run runtime-evidence:smoke -- --strict'
```

- [ ] **Step 6: Add minimal console affordance**

Add a saved-item action in the existing annotation detail control area:

```html
<button id="attachEvidenceButton" type="button" data-action="attachEvidence">Attach evidence</button>
```

Wire it to call:

```js
await requestJson(`/api/items/${encodeURIComponent(item.itemId)}/runtime-evidence`, {
  method: 'POST',
  body: JSON.stringify({
    sessionId: state.session.sessionId,
    type: 'logcat_window',
    summary: 'Manual logcat evidence requested from console',
  }),
});
```

Keep the first UI path conservative: it attaches a manual summary or invokes the MCP/HTTP route; it does not stream raw logs into the browser.

- [ ] **Step 7: Run tests**

Run:

```bash
npm run runtime-evidence:smoke:test
npm run evidence:test
npm run console:test:fast
```

Expected: PASS.

- [ ] **Step 8: Commit**

Run:

```bash
git add scripts/runtime-evidence-smoke.mjs \
  scripts/runtime-evidence-smoke-test.mjs \
  package.json \
  scripts/evidence-runner.mjs \
  scripts/evidence-runner-test.mjs \
  fixthis-mcp/src/main/console \
  docs/reference/feedback-console-contract.md
git commit -m "feat(runtime-evidence): add smoke and console affordance"
```

## Task 5: Codex Plugin Packaging

**Files:**
- Create: `.codex-plugin/plugin.json`
- Create: `.codex-plugin/skills/fixthis-install-agent/SKILL.md`
- Create: `.codex-plugin/skills/fixthis-feedback-loop/SKILL.md`
- Create: `.codex-plugin/skills/fixthis-android-evidence/SKILL.md`
- Create: `.codex-plugin/skills/fixthis-release-smoke/SKILL.md`
- Create: `scripts/fixthis-plugin-contract-test.mjs`
- Modify: `package.json`
- Modify: `docs/getting-started/connect-your-agent.md`
- Modify: `docs/reference/mcp-tools.md`

**Interfaces:**
- Produces a local Codex plugin bundle with four workflow skills.
- Consumes existing commands:
  - `fixthis install-agent --project-dir . --target all`
  - `fixthis doctor --project-dir . --json`
  - `fixthis_open_feedback_console`
  - `fixthis_read_feedback`
  - `fixthis_claim_feedback`
  - `fixthis_resolve_feedback`
  - `npm run runtime-evidence:smoke`
  - `npm run release:gate`

- [ ] **Step 1: Write plugin contract tests**

Create `scripts/fixthis-plugin-contract-test.mjs`:

```js
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';

const pluginRoot = '.codex-plugin';
const requiredSkills = [
  'fixthis-install-agent',
  'fixthis-feedback-loop',
  'fixthis-android-evidence',
  'fixthis-release-smoke',
];

function read(path) {
  return readFileSync(path, 'utf8');
}

test('plugin manifest names FixThis and lists workflow skills', () => {
  const manifest = JSON.parse(read(join(pluginRoot, 'plugin.json')));
  assert.equal(manifest.name, 'fixthis');
  assert.equal(manifest.display_name, 'FixThis');
  assert.equal(manifest.version, '0.1.0');
  assert.deepEqual(manifest.skills.map((skill) => skill.name).sort(), requiredSkills.toSorted());
});

test('skills preserve debug-only and no .fixthis commit safety language', () => {
  for (const skill of requiredSkills) {
    const body = read(join(pluginRoot, 'skills', skill, 'SKILL.md'));
    assert.match(body, /debug-only/i, `${skill} must mention debug-only`);
    assert.match(body, /Do not commit `.fixthis\/`/, `${skill} must mention .fixthis safety`);
  }
});

test('feedback loop skill requires claim before editing and resolve after work', () => {
  const body = read(join(pluginRoot, 'skills', 'fixthis-feedback-loop', 'SKILL.md'));
  assert.match(body, /fixthis_claim_feedback/);
  assert.match(body, /before making code changes/);
  assert.match(body, /fixthis_resolve_feedback/);
});
```

- [ ] **Step 2: Run plugin tests to verify failure**

Run:

```bash
node --test scripts/fixthis-plugin-contract-test.mjs
```

Expected: FAIL because plugin files do not exist.

- [ ] **Step 3: Add plugin manifest**

Create `.codex-plugin/plugin.json`:

```json
{
  "name": "fixthis",
  "display_name": "FixThis",
  "version": "0.1.0",
  "description": "Agent workflows for local, debug-only Jetpack Compose UI feedback handoff through FixThis.",
  "skills": [
    {
      "name": "fixthis-install-agent",
      "description": "Install and verify FixThis in a debug Jetpack Compose Android app."
    },
    {
      "name": "fixthis-feedback-loop",
      "description": "Read, claim, implement, verify, and resolve FixThis feedback items."
    },
    {
      "name": "fixthis-android-evidence",
      "description": "Attach scoped Android runtime evidence to a FixThis feedback item."
    },
    {
      "name": "fixthis-release-smoke",
      "description": "Run FixThis release and trust-loop smoke checks."
    }
  ]
}
```

- [ ] **Step 4: Add skill files**

Create `.codex-plugin/skills/fixthis-install-agent/SKILL.md`:

```markdown
---
name: fixthis-install-agent
description: Install and verify FixThis in a debug Jetpack Compose Android app.
---

# FixThis Install Agent

Use when a user asks to install or bootstrap FixThis in an Android app.

Rules:
- FixThis is debug-only and Jetpack Compose-only.
- Do not configure release builds.
- Do not commit `.fixthis/`.
- Prefer `fixthis doctor --project-dir . --json` as the readiness source of truth.

Workflow:
1. Run `fixthis install-agent --project-dir . --target all`.
2. Run `fixthis doctor --project-dir . --json`.
3. If MCP config was written, tell the user to restart Codex or Claude Code before calling `fixthis_open_feedback_console`.
4. If doctor reports missing generated metadata, run `./gradlew fixthisSetup` and rerun doctor.
```

Create `.codex-plugin/skills/fixthis-feedback-loop/SKILL.md`:

```markdown
---
name: fixthis-feedback-loop
description: Read, claim, implement, verify, and resolve FixThis feedback items.
---

# FixThis Feedback Loop

Use when a user asks to handle FixThis feedback.

Rules:
- FixThis feedback is local and debug-only.
- Do not commit `.fixthis/`.
- Call `fixthis_claim_feedback` before making code changes when MCP queue items are available.
- Call `fixthis_resolve_feedback` after verification.

Workflow:
1. Call `fixthis_list_feedback`.
2. Call `fixthis_read_feedback` for the item.
3. Call `fixthis_claim_feedback({sessionId, itemId})` before edits.
4. Make the smallest code change that satisfies the feedback.
5. Verify with the relevant project tests and, when possible, FixThis UI evidence.
6. Call `fixthis_resolve_feedback({sessionId, itemId, status: "resolved", summary})`.
```

Create `.codex-plugin/skills/fixthis-android-evidence/SKILL.md`:

```markdown
---
name: fixthis-android-evidence
description: Attach scoped Android runtime evidence to a FixThis feedback item.
---

# FixThis Android Evidence

Use when a user asks to attach logs, frame evidence, memory evidence, or runtime proof to FixThis feedback.

Rules:
- Runtime evidence is local-first.
- Do not commit `.fixthis/`.
- Prefer summaries and local artifact paths over raw logs.
- Non-strict missing Android prerequisites are deferred with exact reasons; strict runs fail.

Workflow:
1. Identify the feedback `sessionId` and `itemId`.
2. Capture the smallest useful evidence type: `logcat_window`, `frame_summary`, `memory_summary`, or `trace_artifact`.
3. Call `fixthis_capture_runtime_evidence` with a bounded summary.
4. Re-read the handoff and confirm compact Markdown shows `runtimeEvidence`.
```

Create `.codex-plugin/skills/fixthis-release-smoke/SKILL.md`:

```markdown
---
name: fixthis-release-smoke
description: Run FixThis release and trust-loop smoke checks.
---

# FixThis Release Smoke

Use when a maintainer asks to validate FixThis release readiness or trust-loop evidence.

Rules:
- FixThis is debug-only and Jetpack Compose-only.
- Do not commit `.fixthis/`.
- Separate strict connected Android proof from non-strict deferred evidence.
- Do not claim connected runtime proof passed unless the command actually passed.

Workflow:
1. Run `npm run evidence:trust`.
2. Run `npm run runtime-evidence:smoke`.
3. For strict local release proof, run `npm run runtime-evidence:smoke -- --strict` and the existing strict trust commands required by `docs/contributing/release-readiness.md`.
4. Summarize pass, deferred, and fail results separately.
```

- [ ] **Step 5: Add package script and docs**

In `package.json`, add:

```json
"plugin:contract:test": "node --test scripts/fixthis-plugin-contract-test.mjs",
```

In `docs/getting-started/connect-your-agent.md`, add a short "Codex plugin" paragraph that says the plugin packages install, feedback-loop, evidence, and release-smoke workflows while canonical setup remains `fixthis install-agent` and `fixthis doctor --json`.

In `docs/reference/mcp-tools.md`, mention that plugin skills wrap the same MCP tools and do not change tool schemas.

- [ ] **Step 6: Run tests**

Run:

```bash
npm run plugin:contract:test
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add .codex-plugin/plugin.json \
  .codex-plugin/skills/fixthis-install-agent/SKILL.md \
  .codex-plugin/skills/fixthis-feedback-loop/SKILL.md \
  .codex-plugin/skills/fixthis-android-evidence/SKILL.md \
  .codex-plugin/skills/fixthis-release-smoke/SKILL.md \
  scripts/fixthis-plugin-contract-test.mjs \
  package.json \
  docs/getting-started/connect-your-agent.md \
  docs/reference/mcp-tools.md
git commit -m "feat(plugin): package FixThis Codex workflows"
```

## Task 6: Release Evidence, Documentation, And Final Verification

**Files:**
- Modify: `docs/contributing/release-readiness.md`
- Modify: `docs/releases/unreleased.md`
- Modify: `README.md`
- Modify: `scripts/check-release-readiness.mjs`
- Modify: `scripts/release-gate.mjs`
- Modify: `scripts/release-gate-test.mjs`

**Interfaces:**
- Consumes: artifacts from Tasks 1-5.
- Produces:
  - release readiness rows for verification guidance, runtime evidence smoke, and plugin contract.
  - release-gate claim for `android-agent-evidence-umbrella`.
  - user-facing docs that avoid unsupported release-build, XML/View, WebView, or cloud claims.

- [ ] **Step 1: Write failing release-gate tests**

Append to `scripts/release-gate-test.mjs`:

```js
test('release gate maps Android agent evidence umbrella claim', () => {
  const report = buildReleaseGateReport({
    steps: [
      { name: 'Handoff evaluation', command: 'npm run handoff:eval:test', status: 'pass' },
      { name: 'Runtime evidence attachment', command: 'npm run runtime-evidence:smoke', status: 'pass' },
      { name: 'Plugin contract', command: 'npm run plugin:contract:test', status: 'pass' },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === 'android-agent-evidence-umbrella'), {
    id: 'android-agent-evidence-umbrella',
    status: 'pass',
    evidence: ['Handoff evaluation', 'Runtime evidence attachment', 'Plugin contract'],
  });
});
```

- [ ] **Step 2: Run release tests to verify failure**

Run:

```bash
npm run release:gate:test
```

Expected: FAIL because the claim is not mapped yet.

- [ ] **Step 3: Add release-gate claim**

In `scripts/release-gate.mjs`, map a new claim:

```js
{
  id: 'android-agent-evidence-umbrella',
  evidence: ['Handoff evaluation', 'Runtime evidence attachment', 'Plugin contract'],
}
```

Use the existing claim helper so pass/deferred/fail behavior matches current claims.

- [ ] **Step 4: Add release-readiness docs**

In `docs/contributing/release-readiness.md`, add:

```markdown
## Android Agent Evidence Umbrella

| Claim | Evidence | Strict behavior |
| --- | --- | --- |
| Compact handoff renders agent verification posture. | `npm run handoff:eval:test` plus `./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest" --no-daemon` | Fails on renderer regression. |
| Runtime evidence attachments stay optional and local. | `npm run runtime-evidence:smoke` | Non-strict Android absence is deferred; strict connected run fails when unavailable. |
| Codex plugin workflows preserve safety language. | `npm run plugin:contract:test` | Fails when required skills, commands, or safety language drift. |
```

In `docs/releases/unreleased.md`, add a user-visible note:

```markdown
- Android agent evidence umbrella: compact handoffs now include explicit verification posture, runtime evidence summaries can be attached locally, and Codex workflow skills package install, feedback, evidence, and release-smoke guidance.
```

In `README.md`, add one short line under "Why FixThis vs. just sending a screenshot?":

```markdown
- **Agent verification posture.** Handoffs say whether the agent should inspect source first, corroborate multiple signals, treat source paths as hints, or verify manually.
```

- [ ] **Step 5: Update readiness checker**

In `scripts/check-release-readiness.mjs`, add required tokens:

```js
'Android Agent Evidence Umbrella',
'npm run runtime-evidence:smoke',
'npm run plugin:contract:test',
'android-agent-evidence-umbrella',
```

Use the existing check style in the file.

- [ ] **Step 6: Run full relevant verification**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest" --tests "*RuntimeEvidenceSerializationTest" --tests "*RuntimeEvidenceToolRegistryTest" --no-daemon
npm run runtime-evidence:smoke:test
npm run plugin:contract:test
npm run evidence:test
npm run release:gate:test
npm run docs:agent-bootstrap:test
git diff --check
graphify update .
```

Expected: PASS. `graphify update .` may change ignored graph output; do not commit `graphify-out/`.

- [ ] **Step 7: Inspect git status**

Run:

```bash
git status --short
```

Expected: only intended source, test, script, docs, plugin files are modified. No `.fixthis/`, `graphify-out/`, screenshots, logs, traces, Android build output, or local fixture workspaces are staged.

- [ ] **Step 8: Commit**

Run:

```bash
git add docs/contributing/release-readiness.md \
  docs/releases/unreleased.md \
  README.md \
  scripts/check-release-readiness.mjs \
  scripts/release-gate.mjs \
  scripts/release-gate-test.mjs
git commit -m "docs: record Android agent evidence release proof"
```

## Final Verification Matrix

Run these commands after all tasks land:

```bash
./gradlew :fixthis-mcp:test --no-daemon
npm run handoff:eval:test
npm run runtime-evidence:smoke:test
npm run plugin:contract:test
npm run evidence:test
npm run release:gate:test
npm run docs:agent-bootstrap:test
git diff --check
graphify update .
```

For release proof on a machine with a ready emulator, also run:

```bash
npm run runtime-evidence:smoke -- --strict
npm run evidence:trust -- --strict-runtime
```

Strict connected commands must pass before claiming connected Android runtime evidence. Non-strict runs may report deferred with exact Android prerequisite reasons.

## Execution Notes

- Implement Task 1 before runtime evidence so verification posture exists before evidence actions can reference it.
- Implement Task 2 before Task 3 so the MCP/HTTP route has stable models to persist.
- Implement Task 5 after Tasks 1-4 so plugin skills can name real commands.
- Keep each task's commit focused. Do not batch plugin packaging with runtime evidence model changes.
- Runtime evidence persistence uses `FeedbackSessionStore.replaceSessionForDomain(updated)` in this plan. If review requires event-log-specific mutation coverage, add that coverage in the same task before committing.
