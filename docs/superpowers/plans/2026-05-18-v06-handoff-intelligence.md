# v0.6 Handoff Intelligence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build measured edit-surface intelligence so FixThis handoffs help agents find the right code surface more often without overclaiming weak evidence.

**Architecture:** Add a deterministic evaluation corpus and baseline gate before changing handoff intelligence. Then add additive edit-surface role metadata, classify it through focused policy objects, and render the role in compact handoffs without renaming persisted MCP JSON fields.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, JUnit, Node.js `node:test`, existing `:fixthis-mcp` session DTOs and compact handoff renderer.

---

## Scope Check

This plan implements Track A from `docs/superpowers/specs/2026-05-18-v06-umbrella-roadmap-design.md`.

It does not change console state management, release readiness automation, Android runtime behavior, Gradle plugin source indexing, or public install docs. Track B and Track C have separate plans.

## File Structure

### Create

- `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`
  - Stable corpus of feedback cases and expected edit-surface role outcomes.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`
  - Loads the corpus, validates schema, computes baseline metrics, and enforces regression gates.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt`
  - Converts compact JSON corpus entries into `SessionDto`, `SnapshotDto`, `AnnotationDto`, and `SourceCandidate` objects.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceRoleClassifier.kt`
  - Focused policy for assigning generalized edit-surface roles.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceRoleClassifierTest.kt`
  - Unit tests for role classification and caution behavior.

### Modify

- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt`
  - Add `EditSurfaceRoleDto` and additive nullable `role` field on `EditSurfaceCandidateDto`.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt`
  - Classify role for existing edit-surface candidates and produce visual-area/interop risk candidates when source candidates are intentionally absent.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
  - Render `role=<token>` on `editSurface:` lines when present.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateServiceTest.kt`
  - Cover role propagation from classifier to candidates.
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
  - Cover compact Markdown output and token budget.
- `package.json`
  - Add `handoff:eval:test` script.

## Task 1: Add The Handoff Evaluation Corpus

**Files:**
- Create: `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`

- [ ] **Step 1: Write the corpus fixture**

Create `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`:

```json
{
  "schemaVersion": 1,
  "cases": [
    {
      "id": "button-copy-call-site",
      "comment": "Rename this button to Checkout",
      "targetType": "node",
      "selectedText": ["Continue"],
      "selectedRole": "Button",
      "selectedTestTag": "comp:PrimaryButton:checkout",
      "sourceCandidates": [
        {
          "file": "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/CheckoutScreen.kt",
          "line": 42,
          "score": 92.0,
          "confidence": "HIGH",
          "matchReasons": ["selected text"],
          "matchedTerms": ["Continue"],
          "ownerComposable": "CheckoutScreen"
        },
        {
          "file": "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/PrimaryButton.kt",
          "line": 18,
          "score": 54.0,
          "confidence": "MEDIUM",
          "matchReasons": ["selected testTag convention composable"],
          "matchedTerms": ["PrimaryButton"],
          "ownerComposable": "PrimaryButton"
        }
      ],
      "expectedRole": "COPY_OR_DATA",
      "expectedTop3Contains": "CheckoutScreen.kt",
      "allowHighConfidence": true
    },
    {
      "id": "reusable-card-color",
      "comment": "Make this card background green",
      "targetType": "node",
      "selectedText": ["Resolved this week"],
      "selectedTestTag": "comp:MetricCard:summary",
      "sourceCandidates": [
        {
          "file": "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/MetricCard.kt",
          "line": 27,
          "score": 88.0,
          "confidence": "HIGH",
          "matchReasons": ["selected testTag convention composable"],
          "matchedTerms": ["MetricCard"],
          "ownerComposable": "MetricCard"
        }
      ],
      "expectedRole": "COMPONENT_DEFINITION",
      "expectedTop3Contains": "MetricCard.kt",
      "allowHighConfidence": true
    },
    {
      "id": "spacing-layout",
      "comment": "Reduce the bottom spacing by 8dp",
      "targetType": "node",
      "selectedText": ["Priority feedback"],
      "selectedTestTag": "comp:FeedbackCard:priority",
      "sourceCandidates": [
        {
          "file": "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/QueueScreen.kt",
          "line": 76,
          "score": 73.0,
          "confidence": "MEDIUM",
          "matchReasons": ["nearby text"],
          "matchedTerms": ["Priority feedback"],
          "ownerComposable": "QueueScreen"
        }
      ],
      "expectedRole": "LAYOUT_OR_STYLE",
      "expectedTop3Contains": "QueueScreen.kt",
      "allowHighConfidence": false
    },
    {
      "id": "visual-area-gap",
      "comment": "Tighten this empty gap",
      "targetType": "area",
      "sourceCandidates": [],
      "expectedRole": "VISUAL_AREA",
      "expectedTop3Contains": null,
      "allowHighConfidence": false
    },
    {
      "id": "interop-risk",
      "comment": "Fix the native chart spacing",
      "targetType": "area",
      "targetWarnings": ["POSSIBLE_VIEW_INTEROP"],
      "sourceCandidates": [],
      "expectedRole": "INTEROP_RISK",
      "expectedTop3Contains": null,
      "allowHighConfidence": false
    },
    {
      "id": "ambiguous-repeated-text",
      "comment": "Change this status copy",
      "targetType": "node",
      "selectedText": ["Resolved"],
      "sourceCandidates": [
        {
          "file": "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt",
          "line": 50,
          "score": 61.0,
          "confidence": "MEDIUM",
          "scoreMargin": 0.5,
          "matchReasons": ["selected text"],
          "matchedTerms": ["Resolved"],
          "ownerComposable": "HomeScreen"
        },
        {
          "file": "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/ReviewScreen.kt",
          "line": 91,
          "score": 60.5,
          "confidence": "MEDIUM",
          "scoreMargin": 0.5,
          "matchReasons": ["selected text"],
          "matchedTerms": ["Resolved"],
          "ownerComposable": "ReviewScreen"
        }
      ],
      "expectedRole": "COPY_OR_DATA",
      "expectedTop3Contains": "HomeScreen.kt",
      "allowHighConfidence": false
    }
  ]
}
```

- [ ] **Step 2: Add the fixture loader**

Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt`:

```kotlin
@file:Suppress("LongParameterList")

package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class HandoffEvaluationCorpus(
    val schemaVersion: Int,
    val cases: List<HandoffEvaluationCase>,
)

@Serializable
internal data class HandoffEvaluationCase(
    val id: String,
    val comment: String,
    val targetType: String,
    val selectedText: List<String> = emptyList(),
    val selectedRole: String? = null,
    val selectedTestTag: String? = null,
    val targetWarnings: List<String> = emptyList(),
    val sourceCandidates: List<HandoffEvaluationSourceCandidate> = emptyList(),
    val expectedRole: EditSurfaceRoleDto,
    val expectedTop3Contains: String? = null,
    val allowHighConfidence: Boolean,
)

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
)

internal object HandoffEvaluationFixtures {
    private val json: Json = fixThisJson

    fun loadCorpus(root: File = File("")): HandoffEvaluationCorpus {
        val file = File(root.absoluteFile, "fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json")
        return json.decodeFromString(HandoffEvaluationCorpus.serializer(), file.readText())
    }

    fun annotationFor(case: HandoffEvaluationCase): AnnotationDto {
        val bounds = FixThisRect(10f, 20f, 210f, 120f)
        val node = if (case.targetType == "node") {
            FixThisNode(
                uid = "${case.id}-node",
                composeNodeId = case.id.hashCode(),
                rootIndex = 0,
                treeKind = TreeKind.MERGED,
                boundsInWindow = bounds,
                text = case.selectedText,
                role = case.selectedRole,
                testTag = case.selectedTestTag,
                path = listOf("root", case.id),
            )
        } else {
            null
        }
        val target = if (case.targetType == "area") {
            AnnotationTargetDto.Area(bounds)
        } else {
            AnnotationTargetDto.Node(requireNotNull(node).uid, bounds)
        }
        val sourceCandidates = case.sourceCandidates.map { it.toSourceCandidate() }
        val item = AnnotationDto(
            itemId = "item-${case.id}",
            screenId = "screen-${case.id}",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = target,
            selectedNode = node,
            sourceCandidates = sourceCandidates,
            comment = case.comment,
            targetReliability = reliabilityFor(case),
        )
        return item.copy(editSurfaceCandidates = EditSurfaceCandidateService.build(item, screenFor(case, node)))
    }

    fun screenFor(case: HandoffEvaluationCase, node: FixThisNode? = null): SnapshotDto = SnapshotDto(
        screenId = "screen-${case.id}",
        capturedAtEpochMillis = 1L,
        displayName = "Eval",
        roots = listOf(
            SnapshotRootDto(
                rootIndex = 0,
                boundsInWindow = FixThisRect(0f, 0f, 400f, 800f),
                mergedNodes = listOfNotNull(node),
            ),
        ),
    )

    private fun HandoffEvaluationSourceCandidate.toSourceCandidate(): SourceCandidate = SourceCandidate(
        file = file,
        line = line,
        score = score,
        confidence = SelectionConfidence.valueOf(confidence),
        scoreMargin = scoreMargin,
        matchReasons = matchReasons,
        matchedTerms = matchedTerms,
        ownerComposable = ownerComposable,
    )

    private fun reliabilityFor(case: HandoffEvaluationCase): TargetReliability? {
        if (case.targetWarnings.isEmpty()) return null
        return TargetReliability(
            confidence = TargetConfidence.LOW,
            reasons = listOf(TargetReliabilityReason.VISUAL_AREA_SELECTION),
            warnings = case.targetWarnings.map { TargetReliabilityWarning.valueOf(it) },
        )
    }
}
```

- [ ] **Step 3: Add the failing corpus test**

Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HandoffEvaluationCorpusTest {
    @Test
    fun corpusHasStableV06Coverage() {
        val corpus = HandoffEvaluationFixtures.loadCorpus()

        assertEquals(1, corpus.schemaVersion)
        assertEquals(
            listOf(
                "button-copy-call-site",
                "reusable-card-color",
                "spacing-layout",
                "visual-area-gap",
                "interop-risk",
                "ambiguous-repeated-text",
            ),
            corpus.cases.map { it.id },
        )
    }

    @Test
    fun v06CorpusMeetsEditSurfaceRegressionGate() {
        val failures = HandoffEvaluationFixtures.loadCorpus().cases.mapNotNull { case ->
            val item = HandoffEvaluationFixtures.annotationFor(case)
            val topRole = item.editSurfaceCandidates.firstOrNull()?.role
            val topFiles = item.sourceCandidates.take(3).map { it.file.substringAfterLast('/') }
            val highConfidence = item.editSurfaceCandidates.any { it.confidence == SelectionConfidence.HIGH }
            when {
                topRole != case.expectedRole ->
                    "${case.id}: expected role ${case.expectedRole}, got $topRole"
                case.expectedTop3Contains != null && topFiles.none { it == case.expectedTop3Contains } ->
                    "${case.id}: top-3 candidates did not include ${case.expectedTop3Contains}; got $topFiles"
                !case.allowHighConfidence && highConfidence ->
                    "${case.id}: weak case produced high-confidence edit surface"
                else -> null
            }
        }

        assertTrue(failures.isEmpty(), failures.joinToString(separator = "\n"))
    }

    @Test
    fun corpusItemsRenderCompactHandoffWithoutDroppingRoles() {
        val corpus = HandoffEvaluationFixtures.loadCorpus()
        val items = corpus.cases.map { HandoffEvaluationFixtures.annotationFor(it) }
        val session = SessionDto(
            sessionId = "handoff-eval",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = corpus.cases.map { case ->
                HandoffEvaluationFixtures.screenFor(case)
            },
            items = items.mapIndexed { index, item -> item.copy(sequenceNumber = index + 1) },
        )

        val markdown = CompactHandoffRenderer.render(session)

        for (case in corpus.cases) {
            assertTrue(markdown.contains("role=${case.expectedRole.renderToken()}"), "Missing role for ${case.id}")
        }
        assertTrue(markdown.length <= 6500, "v0.6 corpus handoff is ${markdown.length} chars; budget is 6500")
    }

    private fun EditSurfaceRoleDto.renderToken(): String = name.lowercase().replace("_", "-")
}
```

- [ ] **Step 4: Run the new test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*HandoffEvaluationCorpusTest" --no-daemon
```

Expected: FAIL because `EditSurfaceRoleDto` and `EditSurfaceCandidateDto.role` do not exist yet.

- [ ] **Step 5: Commit the failing corpus gate**

Run:

```bash
git add fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt
git commit -m "test: add v0.6 handoff evaluation corpus"
```

## Task 2: Add Additive Edit-Surface Role Metadata

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`

- [ ] **Step 1: Write the renderer test**

Append this test to `CompactHandoffRendererTest`:

```kotlin
@Test
fun compactHandoffRendersEditSurfaceRoleWhenPresent() {
    val session = SessionDto(
        sessionId = "role-session",
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
                    ),
                ),
            ),
        ),
    )

    val markdown = CompactHandoffRenderer.render(session)

    assertTrue(markdown.contains("editSurface: spacing"))
    assertTrue(markdown.contains("role=layout-or-style"))
}
```

- [ ] **Step 2: Run the renderer test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest.compactHandoffRendersEditSurfaceRoleWhenPresent" --no-daemon
```

Expected: FAIL because `role` and `EditSurfaceRoleDto` are not defined.

- [ ] **Step 3: Add the additive DTO field**

In `SessionDtoModels.kt`, replace `EditSurfaceCandidateDto` and add the enum directly below it:

```kotlin
@Serializable
data class EditSurfaceCandidateDto(
    val kind: EditSurfaceKindDto,
    val file: String,
    val repoFile: String? = null,
    val line: Int? = null,
    val confidence: SelectionConfidence,
    val reasons: List<EditSurfaceReasonDto> = emptyList(),
    val note: String? = null,
    val role: EditSurfaceRoleDto? = null,
)

@Serializable
enum class EditSurfaceRoleDto {
    CALL_SITE,
    COMPONENT_DEFINITION,
    COPY_OR_DATA,
    LAYOUT_OR_STYLE,
    VISUAL_AREA,
    INTEROP_RISK,
}
```

This is additive: older JSON without `role` still decodes because the field defaults to `null`.

- [ ] **Step 4: Render the role token**

In `CompactHandoffRenderer.kt`, update `formatEditSurfaceLine()`:

```kotlin
private fun EditSurfaceCandidateDto.formatEditSurfaceLine(): String {
    val kindToken = when (kind) {
        EditSurfaceKindDto.CONTAINER_COLOR -> "containerColor"
        EditSurfaceKindDto.TEXT_COLOR -> "textColor"
        EditSurfaceKindDto.TYPOGRAPHY -> "typography"
        EditSurfaceKindDto.SPACING -> "spacing"
        EditSurfaceKindDto.CHIP_COLOR -> "chipColor"
        EditSurfaceKindDto.COMPONENT_RENDERER -> "componentRenderer"
        EditSurfaceKindDto.UNKNOWN -> "unknown"
    }
    val fileLine = if (line != null) "$file:$line" else file
    val reasonTokens = reasons.joinToString(",") { it.name.lowercase().replace("_", "-") }
    val roleToken = role?.let { "  role=${it.name.lowercase().replace("_", "-")}" }.orEmpty()
    return "editSurface: $kindToken$roleToken -> ${fileLine.inlineSafe()}  " +
        "conf=${confidence.name.lowercase()}  why=[$reasonTokens]"
}
```

- [ ] **Step 5: Run the renderer test and verify it passes**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest.compactHandoffRendersEditSurfaceRoleWhenPresent" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Run serialization-adjacent tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest" --tests "*CopyPromptEditSurfaceRendererTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt
git commit -m "feat: render edit surface roles in handoffs"
```

## Task 3: Classify Edit-Surface Roles

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceRoleClassifier.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceRoleClassifierTest.kt`

- [ ] **Step 1: Write classifier tests**

Create `EditSurfaceRoleClassifierTest.kt`:

```kotlin
@file:Suppress("LongParameterList")

package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import kotlin.test.Test
import kotlin.test.assertEquals

class EditSurfaceRoleClassifierTest {
    @Test
    fun classifiesCopyIntentAsCopyOrData() {
        val item = item(
            comment = "Rename this button to Checkout",
            selectedNode = node(text = listOf("Continue"), role = "Button"),
            candidates = listOf(candidate("CheckoutScreen.kt", reasons = listOf("selected text"))),
        )

        val role = EditSurfaceRoleClassifier.classify(item, EditIntentAnalyzer.analyze(item, screenWith(item.selectedNode!!)))

        assertEquals(EditSurfaceRoleDto.COPY_OR_DATA, role.role)
        assertEquals(SelectionConfidence.MEDIUM, role.confidenceCap)
    }

    @Test
    fun classifiesSpacingAsLayoutOrStyle() {
        val item = item(
            comment = "Reduce the bottom spacing by 8dp",
            selectedNode = node(testTag = "comp:FeedbackCard:priority"),
            candidates = listOf(candidate("QueueScreen.kt", reasons = listOf("nearby text"))),
        )

        val role = EditSurfaceRoleClassifier.classify(item, EditIntentAnalyzer.analyze(item, screenWith(item.selectedNode!!)))

        assertEquals(EditSurfaceRoleDto.LAYOUT_OR_STYLE, role.role)
        assertEquals(SelectionConfidence.LOW, role.confidenceCap)
    }

    @Test
    fun classifiesTaggedComponentStyleAsComponentDefinition() {
        val item = item(
            comment = "Make this card background green",
            selectedNode = node(text = listOf("Resolved this week"), testTag = "comp:MetricCard:summary"),
            candidates = listOf(candidate("MetricCard.kt", owner = "MetricCard", terms = listOf("MetricCard"))),
        )

        val role = EditSurfaceRoleClassifier.classify(item, EditIntentAnalyzer.analyze(item, screenWith(item.selectedNode!!)))

        assertEquals(EditSurfaceRoleDto.COMPONENT_DEFINITION, role.role)
        assertEquals(SelectionConfidence.MEDIUM, role.confidenceCap)
    }

    @Test
    fun classifiesVisualAreaWithoutSourceAsVisualArea() {
        val item = areaItem(comment = "Tighten this empty gap", warnings = emptyList())

        val role = EditSurfaceRoleClassifier.classify(item, EditIntentAnalyzer.analyze(item, screenWith()))

        assertEquals(EditSurfaceRoleDto.VISUAL_AREA, role.role)
        assertEquals(SelectionConfidence.LOW, role.confidenceCap)
    }

    @Test
    fun classifiesInteropWarningAsInteropRisk() {
        val item = areaItem(comment = "Fix the native chart spacing", warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP))

        val role = EditSurfaceRoleClassifier.classify(item, EditIntentAnalyzer.analyze(item, screenWith()))

        assertEquals(EditSurfaceRoleDto.INTEROP_RISK, role.role)
        assertEquals(SelectionConfidence.LOW, role.confidenceCap)
    }

    private fun item(comment: String, selectedNode: FixThisNode, candidates: List<SourceCandidate>): AnnotationDto =
        AnnotationDto(
            itemId = "item",
            screenId = "screen",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Node(selectedNode.uid, selectedNode.boundsInWindow),
            selectedNode = selectedNode,
            sourceCandidates = candidates,
            comment = comment,
        )

    private fun areaItem(comment: String, warnings: List<TargetReliabilityWarning>): AnnotationDto =
        AnnotationDto(
            itemId = "item",
            screenId = "screen",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 200f, 120f)),
            sourceCandidates = emptyList(),
            comment = comment,
            targetReliability = TargetReliability(
                confidence = TargetConfidence.LOW,
                reasons = listOf(TargetReliabilityReason.VISUAL_AREA_SELECTION),
                warnings = warnings,
            ),
        )

    private fun node(
        text: List<String> = emptyList(),
        role: String? = null,
        testTag: String? = null,
    ): FixThisNode = FixThisNode(
        uid = "node",
        composeNodeId = 1,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 0f, 200f, 120f),
        text = text,
        role = role,
        testTag = testTag,
        path = listOf("root", "node"),
    )

    private fun screenWith(vararg nodes: FixThisNode): SnapshotDto = SnapshotDto(
        screenId = "screen",
        capturedAtEpochMillis = 1L,
        displayName = "Eval",
        roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 400f, 800f), mergedNodes = nodes.toList())),
    )

    private fun candidate(
        file: String,
        reasons: List<String> = listOf("selected testTag convention composable"),
        terms: List<String> = emptyList(),
        owner: String? = null,
    ): SourceCandidate = SourceCandidate(
        file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/$file",
        line = 12,
        score = 80.0,
        confidence = SelectionConfidence.MEDIUM,
        matchReasons = reasons,
        matchedTerms = terms,
        ownerComposable = owner,
    )
}
```

- [ ] **Step 2: Run classifier tests and verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditSurfaceRoleClassifierTest" --no-daemon
```

Expected: FAIL because `EditSurfaceRoleClassifier` is missing.

- [ ] **Step 3: Implement classifier**

Create `EditSurfaceRoleClassifier.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning

internal data class EditSurfaceRoleDecision(
    val role: EditSurfaceRoleDto,
    val confidenceCap: SelectionConfidence,
    val note: String? = null,
)

internal object EditSurfaceRoleClassifier {
    fun classify(item: AnnotationDto, intent: EditIntent): EditSurfaceRoleDecision {
        val interop = item.targetReliability?.warnings.orEmpty()
            .any { it == TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP }
        if (interop) {
            return EditSurfaceRoleDecision(
                role = EditSurfaceRoleDto.INTEROP_RISK,
                confidenceCap = SelectionConfidence.LOW,
                note = "possible AndroidView/WebView area; verify runtime target before editing",
            )
        }
        if (item.target is AnnotationTargetDto.Area) {
            return EditSurfaceRoleDecision(
                role = EditSurfaceRoleDto.VISUAL_AREA,
                confidenceCap = SelectionConfidence.LOW,
                note = "visual area selection has no precise semantics node",
            )
        }
        if (intent.primaryKind == EditSurfaceKindDto.SPACING) {
            return EditSurfaceRoleDecision(
                role = EditSurfaceRoleDto.LAYOUT_OR_STYLE,
                confidenceCap = SelectionConfidence.LOW,
            )
        }
        if (intent.primaryKind in styleKinds() && hasComponentSignal(item)) {
            return EditSurfaceRoleDecision(
                role = EditSurfaceRoleDto.COMPONENT_DEFINITION,
                confidenceCap = SelectionConfidence.MEDIUM,
            )
        }
        if (looksLikeCopyIntent(item.comment)) {
            return EditSurfaceRoleDecision(
                role = EditSurfaceRoleDto.COPY_OR_DATA,
                confidenceCap = SelectionConfidence.MEDIUM,
            )
        }
        return EditSurfaceRoleDecision(
            role = EditSurfaceRoleDto.CALL_SITE,
            confidenceCap = SelectionConfidence.MEDIUM,
        )
    }

    private fun styleKinds(): Set<EditSurfaceKindDto> = setOf(
        EditSurfaceKindDto.CONTAINER_COLOR,
        EditSurfaceKindDto.TEXT_COLOR,
        EditSurfaceKindDto.TYPOGRAPHY,
        EditSurfaceKindDto.CHIP_COLOR,
        EditSurfaceKindDto.COMPONENT_RENDERER,
    )

    private fun hasComponentSignal(item: AnnotationDto): Boolean =
        item.selectedNode?.testTag?.startsWith("comp:") == true ||
            item.sourceCandidates.any { candidate ->
                candidate.ownerComposable != null ||
                    candidate.matchReasons.contains("selected testTag convention composable")
            }

    private fun looksLikeCopyIntent(comment: String): Boolean {
        val normalized = comment.lowercase()
        return listOf("rename", "copy", "text", "label", "wording", "문구", "텍스트", "이름").any {
            normalized.contains(it)
        }
    }
}
```

- [ ] **Step 4: Run classifier tests and verify they pass**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditSurfaceRoleClassifierTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceRoleClassifier.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceRoleClassifierTest.kt
git commit -m "feat: classify handoff edit surface roles"
```

## Task 4: Wire Roles Into Edit Surface Candidate Building

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateServiceTest.kt`

- [ ] **Step 1: Add service tests**

Append these tests to `EditSurfaceCandidateServiceTest`:

```kotlin
@Test
fun assignsCopyOrDataRoleForRenameFeedback() {
    val button = node("checkout-button", text = listOf("Continue"), testTag = "comp:PrimaryButton:checkout")
    val item = item(
        comment = "Rename this button to Checkout",
        selectedNode = button,
        sourceCandidates = listOf(
            sourceCandidate(
                file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/CheckoutScreen.kt",
                matchedTerms = listOf("Continue"),
                ownerComposable = "CheckoutScreen",
            ),
        ),
    )

    val candidates = EditSurfaceCandidateService.build(item, screenWith(button))

    assertEquals(EditSurfaceRoleDto.COPY_OR_DATA, candidates.single().role)
}

@Test
fun createsVisualAreaRoleCandidateWithoutSourceFile() {
    val item = AnnotationDto(
        itemId = "area",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 80f, 80f)),
        sourceCandidates = emptyList(),
        comment = "Tighten this empty gap",
    )

    val candidates = EditSurfaceCandidateService.build(item, screenWith())

    assertEquals(1, candidates.size)
    assertEquals(EditSurfaceRoleDto.VISUAL_AREA, candidates.single().role)
    assertEquals(EditSurfaceKindDto.UNKNOWN, candidates.single().kind)
    assertEquals(SelectionConfidence.LOW, candidates.single().confidence)
}
```

- [ ] **Step 2: Run service tests and verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditSurfaceCandidateServiceTest" --no-daemon
```

Expected: FAIL because roles are not wired and visual-area candidates are not emitted.

- [ ] **Step 3: Update candidate building**

In `EditSurfaceCandidateService.kt`, replace `build()` with:

```kotlin
fun build(
    item: AnnotationDto,
    screen: SnapshotDto?,
): List<EditSurfaceCandidateDto> {
    val intent = EditIntentAnalyzer.analyze(item, screen)
    val roleDecision = EditSurfaceRoleClassifier.classify(item, intent)
    if (item.sourceCandidates.isEmpty()) {
        return listOf(
            EditSurfaceCandidateDto(
                kind = if (intent.primaryKind == EditSurfaceKindDto.UNKNOWN) EditSurfaceKindDto.UNKNOWN else intent.primaryKind,
                role = roleDecision.role,
                file = "(visual area)",
                confidence = roleDecision.confidenceCap,
                reasons = intent.reasons,
                note = roleDecision.note,
            ),
        )
    }
    if (intent.primaryKind == EditSurfaceKindDto.UNKNOWN && roleDecision.role != EditSurfaceRoleDto.COPY_OR_DATA) {
        return emptyList()
    }

    val owner = TargetOwnerResolver.resolve(item, screen)
    val ownerComposable = componentNameFrom(item.selectedNode?.testTag)
        ?: componentNameFrom(owner?.node?.testTag)
    val candidates = mutableListOf<EditSurfaceCandidateDto>()

    ownerComposable?.let { composable ->
        item.sourceCandidates.firstOrNull { candidate ->
            candidate.file.substringAfterLast('/').removeSuffix(".kt") == composable ||
                candidate.matchedTerms.any { it == composable } ||
                candidate.ownerComposable == composable
        }?.let { source ->
            candidates += source.toEditSurface(
                kind = if (intent.primaryKind == EditSurfaceKindDto.UNKNOWN) EditSurfaceKindDto.COMPONENT_RENDERER else intent.primaryKind,
                roleDecision = roleDecision,
                confidence = SelectionConfidence.MEDIUM,
                reasons = intent.reasons +
                    EditSurfaceReasonDto.TARGET_OWNER +
                    EditSurfaceReasonDto.COMPONENT_DEFINITION,
            )
        }
    }

    if (candidates.isEmpty()) {
        item.sourceCandidates.firstOrNull { it.matchReasons.contains("selected text") }?.let { source ->
            candidates += source.toEditSurface(
                kind = if (intent.primaryKind == EditSurfaceKindDto.UNKNOWN) EditSurfaceKindDto.COMPONENT_RENDERER else intent.primaryKind,
                roleDecision = roleDecision,
                confidence = SelectionConfidence.MEDIUM,
                reasons = intent.reasons + EditSurfaceReasonDto.SELECTED_TEXT_RENDERER,
            )
        }
    }

    if (intent.primaryKind == EditSurfaceKindDto.SPACING) {
        item.sourceCandidates.firstOrNull()?.let { source ->
            candidates += source.toEditSurface(
                kind = EditSurfaceKindDto.SPACING,
                roleDecision = roleDecision,
                confidence = SelectionConfidence.LOW,
                reasons = intent.reasons + EditSurfaceReasonDto.CALL_SITE,
            )
        }
    }

    return candidates.distinctBy { it.file to it.line }.take(2)
}
```

Then replace `toEditSurface()` with:

```kotlin
private fun SourceCandidate.toEditSurface(
    kind: EditSurfaceKindDto,
    roleDecision: EditSurfaceRoleDecision,
    confidence: SelectionConfidence,
    reasons: List<EditSurfaceReasonDto>,
): EditSurfaceCandidateDto = EditSurfaceCandidateDto(
    kind = kind,
    role = roleDecision.role,
    file = file,
    repoFile = repoFile,
    line = line,
    confidence = minConfidence(confidence, roleDecision.confidenceCap),
    reasons = reasons.distinct(),
    note = roleDecision.note ?: "source candidate identifies data text; editSurface identifies likely rendering code".takeIf {
        kind == EditSurfaceKindDto.TEXT_COLOR || kind == EditSurfaceKindDto.TYPOGRAPHY
    },
)

private fun minConfidence(left: SelectionConfidence, right: SelectionConfidence): SelectionConfidence {
    val order = listOf(SelectionConfidence.NONE, SelectionConfidence.LOW, SelectionConfidence.MEDIUM, SelectionConfidence.HIGH)
    return if (order.indexOf(left) <= order.indexOf(right)) left else right
}
```

- [ ] **Step 4: Run service tests and verify they pass**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditSurfaceCandidateServiceTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Run corpus gate and verify it passes**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*HandoffEvaluationCorpusTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateServiceTest.kt
git commit -m "feat: attach measured roles to edit surfaces"
```

## Task 5: Add A Focused Evaluation Script Entry

**Files:**
- Modify: `package.json`

- [ ] **Step 1: Add the npm script**

In `package.json`, add this script near the other verification scripts:

```json
"handoff:eval:test": "./gradlew :fixthis-mcp:test --tests \"*HandoffEvaluationCorpusTest\" --tests \"*EditSurfaceRoleClassifierTest\" --tests \"*EditSurfaceCandidateServiceTest\" --tests \"*CompactHandoffRendererTest.compactHandoffRendersEditSurfaceRoleWhenPresent\" --no-daemon"
```

- [ ] **Step 2: Run the focused script**

Run:

```bash
npm run handoff:eval:test
```

Expected: PASS.

- [ ] **Step 3: Commit**

Run:

```bash
git add package.json
git commit -m "test: add v0.6 handoff evaluation gate"
```

## Task 6: Run The Track A Verification Set

**Files:**
- No file edits.

- [ ] **Step 1: Run focused Kotlin tests**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests "*HandoffEvaluationCorpusTest" \
  --tests "*EditSurfaceRoleClassifierTest" \
  --tests "*EditSurfaceCandidateServiceTest" \
  --tests "*CompactHandoffRendererTest" \
  --tests "*CopyPromptEditSurfaceRendererTest" \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Run focused npm gate**

Run:

```bash
npm run handoff:eval:test
```

Expected: PASS.

- [ ] **Step 3: Run compatibility checks**

Run:

```bash
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected: both commands exit 0.

- [ ] **Step 4: Record verification in the PR notes**

Use this exact summary in the PR body:

```markdown
Track A verification:
- `npm run handoff:eval:test`
- `./gradlew :fixthis-mcp:test --tests "*HandoffEvaluationCorpusTest" --tests "*EditSurfaceRoleClassifierTest" --tests "*EditSurfaceCandidateServiceTest" --tests "*CompactHandoffRendererTest" --tests "*CopyPromptEditSurfaceRendererTest" --no-daemon`
- `node scripts/check-doc-consistency.mjs`
- `git diff --check`
```
