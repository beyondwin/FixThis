# Copy Prompt Edit Surface and Source Ranking Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Copy Prompt handoffs distinguish source-origin hints from likely visual edit surfaces, while preventing selected UI targets from being outranked by nearby-only source matches.

**Architecture:** Keep the Android bridge unchanged. Add a small source-ranking guardrail in `:fixthis-compose-core`, then add MCP-session-only edit-surface DTOs, deterministic intent classification, target-owner resolution from persisted screen roots, compact renderer output, contract docs, and golden tests.

**Tech Stack:** Kotlin/JVM, Kotlin serialization, existing MCP session DTOs, Markdown compact handoff renderer, Gradle test runner.

---

## Scope Check

This plan covers one cohesive handoff-quality improvement. It changes matching
ranking, MCP session enrichment, compact rendering, and docs. It does not alter
the Android bridge protocol, Compose sidekick capture payload, release runtime,
or sample app UI.

## File Structure

- Modify `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`.
  Adds evidence-tier sorting so selected target evidence beats nearby-only aggregate matches.
- Modify `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`.
  Adds ranking regression tests.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt`.
  Adds optional `editSurfaceCandidates` DTOs on `AnnotationDto`.
- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditIntentClassifier.kt`.
  Owns deterministic Korean/English style/layout intent detection.
- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetOwnerResolver.kt`.
  Owns nearest containing/overlapping owner derivation from persisted snapshot roots.
- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt`.
  Combines intent, target owner, and source candidates into up to two edit-surface hints.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt`.
  Calls the new service when enriching saved items.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetSummaryFormatter.kt`.
  Renders owner context on the `target:` line when available.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`.
  Renders `editSurface:` lines and actionable warnings.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`.
  Adds compact output and golden regression coverage.
- Modify `docs/reference/feedback-console-contract.md`, `docs/reference/output-schema.md`, and `docs/reference/mcp-tools.md`.
  Documents source-vs-edit-surface interpretation.

## Task 1: Add Source Ranking Guardrail

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`

- [ ] **Step 1: Add failing regression test for selected text outranking nearby-only aggregate**

Append this test to `SourceMatcherTest`:

```kotlin
    @Test
    fun selectedTextCandidateOutranksHigherRawNearbyOnlyAggregate() {
        val matcher = SourceMatcher(
            SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt",
                        line = 50,
                        text = listOf("Open queue"),
                        signals = listOf(
                            SourceSignal(
                                kind = SourceSignalKind.UI_TEXT,
                                value = "Open queue",
                                confidenceWeight = 1.0,
                            ),
                        ),
                    ),
                    SourceIndexEntry(
                        file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/model/FixThisDemoData.kt",
                        line = 122,
                        text = listOf("Diagnostics", "Review"),
                        signals = listOf(
                            SourceSignal(
                                kind = SourceSignalKind.UI_TEXT,
                                value = "Diagnostics",
                                confidenceWeight = 1.0,
                            ),
                            SourceSignal(
                                kind = SourceSignalKind.UI_TEXT,
                                value = "Review",
                                confidenceWeight = 1.0,
                            ),
                        ),
                    ),
                ),
            ),
        )

        val matches = matcher.match(
            selectedNode = node(uid = "open-queue", text = listOf("Open queue")),
            nearbyNodes = listOf(
                node(uid = "diagnostics-tab", text = listOf("Diagnostics")),
                node(uid = "review-tab", text = listOf("Review")),
            ),
            activityName = null,
        )

        assertEquals(
            "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt",
            matches.first().file,
        )
        assertTrue(matches.first().matchReasons.contains("selected text"))
        assertTrue(matches[1].matchReasons.all { it.startsWith("nearby ") })
    }
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest.selectedTextCandidateOutranksHigherRawNearbyOnlyAggregate" --no-daemon
```

Expected: FAIL before implementation because the nearby-only aggregate can sort ahead by raw score.

- [ ] **Step 3: Implement evidence-tier sorting**

In `SourceMatcher.kt`, replace the current `sortedWith` comparator in `match()` with evidence-tier-first sorting:

```kotlin
            .sortedWith(
                compareByDescending<MatchScore> { it.sourceRankingTier }
                    .thenByDescending { it.rawScore }
                    .thenBy { it.entry.file }
                    .thenBy { it.entry.line ?: Int.MAX_VALUE },
            )
```

Add this private property near `MatchScore`:

```kotlin
    private val MatchScore.sourceRankingTier: Int
        get() {
            val reasons = matchReasons.toSet()
            return when {
                SourceMatchReason.SELECTED_TEST_TAG in reasons ||
                    SourceMatchReason.SELECTED_TEST_TAG_CONVENTION_COMPOSABLE in reasons -> 50
                SourceMatchReason.SELECTED_TEXT in reasons ||
                    SourceMatchReason.SELECTED_CONTENT_DESCRIPTION in reasons ||
                    SourceMatchReason.SELECTED_STRING_RESOURCE in reasons ||
                    SourceMatchReason.SELECTED_ROLE in reasons -> 40
                reasons.any { it in NEARBY_REASONS } -> 20
                SourceMatchReason.ACTIVITY in reasons -> 10
                else -> 0
            }
        }

    private companion object {
        val NEARBY_REASONS: Set<SourceMatchReason> = setOf(
            SourceMatchReason.NEARBY_TEXT,
            SourceMatchReason.NEARBY_CONTENT_DESCRIPTION,
            SourceMatchReason.NEARBY_TEST_TAG,
            SourceMatchReason.NEARBY_ROLE,
        )
    }
```

If `SourceMatcher` already has a companion object, merge `NEARBY_REASONS` into it instead of creating a second companion.

- [ ] **Step 4: Run source matcher tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon
```

Expected: PASS.

## Task 2: Add Edit Surface DTOs and Intent Classifier

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditIntentClassifier.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/EditIntentClassifierTest.kt`

- [ ] **Step 1: Add failing classifier tests**

Create `EditIntentClassifierTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import org.junit.Assert.assertEquals
import org.junit.Test

class EditIntentClassifierTest {
    @Test
    fun detectsKoreanAndEnglishVisualEditIntents() {
        assertEquals(EditSurfaceKindDto.CONTAINER_COLOR, EditIntentClassifier.classify("여기 배경 초록색").primaryKind)
        assertEquals(EditSurfaceKindDto.TEXT_COLOR, EditIntentClassifier.classify("여기 글자 파란색").primaryKind)
        assertEquals(EditSurfaceKindDto.TYPOGRAPHY, EditIntentClassifier.classify("여기 텍스트 더크게").primaryKind)
        assertEquals(EditSurfaceKindDto.SPACING, EditIntentClassifier.classify("여기 아래 바텀마진 8dp더").primaryKind)
        assertEquals(EditSurfaceKindDto.TEXT_COLOR, EditIntentClassifier.classify("make this label red").primaryKind)
    }

    @Test
    fun returnsUnknownForContentOnlyFeedback() {
        assertEquals(EditSurfaceKindDto.UNKNOWN, EditIntentClassifier.classify("Rename this to Checkout").primaryKind)
    }
}
```

- [ ] **Step 2: Add DTOs**

In `SessionDtoModels.kt`, add imports if needed and add this field to `AnnotationDto` after `sourceCandidates`:

```kotlin
    val editSurfaceCandidates: List<EditSurfaceCandidateDto> = emptyList(),
```

Add DTOs near the annotation DTO declarations:

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
)

@Serializable
enum class EditSurfaceKindDto {
    CONTAINER_COLOR,
    TEXT_COLOR,
    TYPOGRAPHY,
    SPACING,
    CHIP_COLOR,
    COMPONENT_RENDERER,
    UNKNOWN,
}

@Serializable
enum class EditSurfaceReasonDto {
    STYLE_INTENT,
    LAYOUT_INTENT,
    TYPOGRAPHY_INTENT,
    TARGET_OWNER,
    SELECTED_TEXT_RENDERER,
    COMPONENT_DEFINITION,
    CALL_SITE,
    LIST_ITEM_SPACING,
    COMPONENT_CONTAINER,
}
```

- [ ] **Step 3: Implement classifier**

Create `EditIntentClassifier.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

internal data class EditIntent(
    val primaryKind: EditSurfaceKindDto,
    val reasons: List<EditSurfaceReasonDto>,
)

internal object EditIntentClassifier {
    fun classify(comment: String): EditIntent {
        val normalized = comment.lowercase()
        return when {
            normalized.hasAny("배경", "배경색", "카드색", "background", "container", "card color") ->
                EditIntent(EditSurfaceKindDto.CONTAINER_COLOR, listOf(EditSurfaceReasonDto.STYLE_INTENT))
            normalized.hasAny("마진", "패딩", "간격", "바텀마진", "아래", "margin", "padding", "spacing", "bottom") ->
                EditIntent(EditSurfaceKindDto.SPACING, listOf(EditSurfaceReasonDto.LAYOUT_INTENT))
            normalized.hasAny("크게", "작게", "폰트", "글씨", "더크게", "bigger", "smaller", "font", "text size", "typography") ->
                EditIntent(EditSurfaceKindDto.TYPOGRAPHY, listOf(EditSurfaceReasonDto.TYPOGRAPHY_INTENT))
            normalized.hasAny("글자", "텍스트", "컬러", "색", "파란", "빨간", "보라", "text", "label", "color", "blue", "red", "purple") ->
                EditIntent(EditSurfaceKindDto.TEXT_COLOR, listOf(EditSurfaceReasonDto.STYLE_INTENT))
            else ->
                EditIntent(EditSurfaceKindDto.UNKNOWN, emptyList())
        }
    }

    private fun String.hasAny(vararg tokens: String): Boolean = tokens.any { contains(it.lowercase()) }
}
```

- [ ] **Step 4: Run classifier tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditIntentClassifierTest" --no-daemon
```

Expected: PASS.

## Task 3: Resolve Target Owner Context

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetOwnerResolver.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetSummaryFormatter.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`

- [ ] **Step 1: Add failing renderer test for owner context**

Append this test to `CompactHandoffRendererTest`:

```kotlin
    @Test
    fun renderTargetSummaryIncludesContainingTaggedOwner() {
        val owner = FixThisNode(
            uid = "owner-card",
            composeNodeId = 91,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(42f, 1167f, 1038f, 1509f),
            testTag = "comp:MetricCard:summary",
            path = listOf("root", "node:2", "node:61", "node:91"),
        )
        val selected = FixThisNode(
            uid = "metric-label",
            composeNodeId = 94,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(79f, 1204f, 348f, 1241f),
            text = listOf("Resolved this week"),
            path = listOf("root", "node:2", "node:61", "node:91", "node:94"),
        )
        val session = oneItemSession(
            screen = SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 1L,
                displayName = "MainActivity",
                roots = listOf(SnapshotRootDto(rootIndex = 0, mergedNodes = listOf(owner, selected))),
            ),
            item = AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node(selected.uid, selected.boundsInWindow),
                selectedNode = selected,
                comment = "여기 글자 파란색",
                sequenceNumber = 1,
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)

        assertTrue(markdown.contains("""target: text="Resolved this week"; inside tag="comp:MetricCard:summary""""))
    }
```

If the existing `oneItemSession` helper only accepts an item, add an overload in the test file:

```kotlin
    private fun oneItemSession(screen: SnapshotDto, item: AnnotationDto): SessionDto = SessionDto(
        sessionId = "session-one-item",
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(screen),
        items = listOf(item),
    )
```

- [ ] **Step 2: Implement owner resolver**

Create `TargetOwnerResolver.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode

internal data class TargetOwner(
    val node: FixThisNode,
)

internal object TargetOwnerResolver {
    fun resolve(item: AnnotationDto, screen: SnapshotDto?): TargetOwner? {
        val selected = item.selectedNode ?: return null
        val candidates = screen?.roots.orEmpty()
            .flatMap { it.mergedNodes }
            .filter { it.uid != selected.uid }
            .filter { candidate -> candidate.contains(selected) || candidate.path.isPrefixOf(selected.path) }
            .filter { it.testTag?.isNotBlank() == true || it.text.isNotEmpty() || it.contentDescription.isNotEmpty() || it.role != null }
            .sortedWith(
                compareByDescending<FixThisNode> { it.testTag?.startsWith("comp:") == true }
                    .thenBy { it.boundsInWindow.area() }
            )
        return candidates.firstOrNull()?.let(::TargetOwner)
    }

    private fun FixThisNode.contains(other: FixThisNode): Boolean =
        boundsInWindow.left <= other.boundsInWindow.left &&
            boundsInWindow.top <= other.boundsInWindow.top &&
            boundsInWindow.right >= other.boundsInWindow.right &&
            boundsInWindow.bottom >= other.boundsInWindow.bottom

    private fun List<String>.isPrefixOf(other: List<String>): Boolean =
        size < other.size && other.take(size) == this
}
```

`FixThisRect.area()` already exists in MCP helpers; if not, add this private extension in the file:

```kotlin
private fun FixThisRect.area(): Float =
    ((right - left).coerceAtLeast(0f)) * ((bottom - top).coerceAtLeast(0f))
```

- [ ] **Step 3: Pass owner context into target summary rendering**

Change `CompactHandoffRenderer.appendCompactItem` to find the screen before rendering the item. The method already has access to `screenId`; pass the current screen into `appendCompactItem`, then call:

```kotlin
val owner = TargetOwnerResolver.resolve(item, screen)
appendLine("  ${TargetSummaryFormatter.render(item, owner)}")
```

Update `TargetSummaryFormatter.render` signature:

```kotlin
fun render(item: AnnotationDto, owner: TargetOwner? = null): String
```

Append owner text before returning:

```kotlin
owner?.node?.testTag?.takeIf { it.isNotBlank() && it != node.testTag }?.let { tag ->
    parts += "inside tag=\"${tag.compactQuotedValue()}\""
}
```

- [ ] **Step 4: Run renderer target tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest.renderTargetSummaryIncludesContainingTaggedOwner" --no-daemon
```

Expected: PASS.

## Task 4: Build and Render Edit Surface Candidates

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`

- [ ] **Step 1: Add failing compact renderer test for source/edit-surface split**

Append this test to `CompactHandoffRendererTest`:

```kotlin
    @Test
    fun renderShowsEditSurfaceBeforeDataSourceCandidateForStyleIntent() {
        val item = AnnotationDto(
            itemId = "item-1",
            screenId = "screen-1",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Node("label", FixThisRect(79f, 1204f, 348f, 1241f)),
            selectedNode = FixThisNode(
                uid = "label",
                composeNodeId = 94,
                rootIndex = 0,
                treeKind = TreeKind.MERGED,
                boundsInWindow = FixThisRect(79f, 1204f, 348f, 1241f),
                text = listOf("Resolved this week"),
            ),
            sourceCandidates = listOf(
                SourceCandidate(
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/model/FixThisDemoData.kt",
                    line = 59,
                    score = 0.49,
                    matchedTerms = listOf("Resolved this week"),
                    matchReasons = listOf("selected text"),
                    confidence = SelectionConfidence.MEDIUM,
                ),
            ),
            editSurfaceCandidates = listOf(
                EditSurfaceCandidateDto(
                    kind = EditSurfaceKindDto.TEXT_COLOR,
                    file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/MetricCard.kt",
                    line = 26,
                    confidence = SelectionConfidence.MEDIUM,
                    reasons = listOf(EditSurfaceReasonDto.STYLE_INTENT, EditSurfaceReasonDto.TARGET_OWNER),
                    note = "source candidate identifies data text; editSurface identifies likely rendering code",
                ),
            ),
            comment = "여기 글자 파란색",
            sequenceNumber = 1,
        )

        val markdown = CompactHandoffRenderer.render(oneItemSession(item))
        val editIndex = markdown.indexOf("editSurface: textColor ->")
        val sourceIndex = markdown.indexOf("FixThisDemoData.kt:59")

        assertTrue(editIndex >= 0)
        assertTrue(sourceIndex > editIndex)
        assertTrue(markdown.contains("note: source candidate identifies data text; editSurface identifies likely rendering code"))
    }
```

- [ ] **Step 2: Implement edit surface candidate service**

Create `EditSurfaceCandidateService.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.identity.TestTagConvention
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate

internal object EditSurfaceCandidateService {
    fun build(
        item: AnnotationDto,
        screen: SnapshotDto?,
    ): List<EditSurfaceCandidateDto> {
        val intent = EditIntentClassifier.classify(item.comment)
        if (intent.primaryKind == EditSurfaceKindDto.UNKNOWN) return emptyList()

        val owner = TargetOwnerResolver.resolve(item, screen)
        val ownerComposable = owner?.node?.testTag?.let { TestTagConvention.parse(it)?.composableName }
        val candidates = mutableListOf<EditSurfaceCandidateDto>()

        ownerComposable?.let { composable ->
            item.sourceCandidates.firstOrNull { candidate ->
                candidate.file.substringAfterLast('/').removeSuffix(".kt") == composable ||
                    candidate.matchedTerms.any { it == composable }
            }?.let { source ->
                candidates += source.toEditSurface(
                    kind = intent.primaryKind,
                    confidence = SelectionConfidence.MEDIUM,
                    reasons = intent.reasons + EditSurfaceReasonDto.TARGET_OWNER + EditSurfaceReasonDto.COMPONENT_DEFINITION,
                )
            }
        }

        if (candidates.isEmpty()) {
            item.sourceCandidates.firstOrNull { it.matchReasons.contains("selected text") }?.let { source ->
                candidates += source.toEditSurface(
                    kind = intent.primaryKind,
                    confidence = SelectionConfidence.MEDIUM,
                    reasons = intent.reasons + EditSurfaceReasonDto.SELECTED_TEXT_RENDERER,
                )
            }
        }

        if (intent.primaryKind == EditSurfaceKindDto.SPACING) {
            item.sourceCandidates.firstOrNull()?.let { source ->
                candidates += source.toEditSurface(
                    kind = EditSurfaceKindDto.SPACING,
                    confidence = SelectionConfidence.LOW,
                    reasons = intent.reasons + EditSurfaceReasonDto.CALL_SITE,
                )
            }
        }

        return candidates.distinctBy { it.file to it.line }.take(2)
    }

    private fun SourceCandidate.toEditSurface(
        kind: EditSurfaceKindDto,
        confidence: SelectionConfidence,
        reasons: List<EditSurfaceReasonDto>,
    ): EditSurfaceCandidateDto = EditSurfaceCandidateDto(
        kind = kind,
        file = file,
        repoFile = repoFile,
        line = line,
        confidence = confidence,
        reasons = reasons.distinct(),
        note = "source candidate identifies data text; editSurface identifies likely rendering code".takeIf {
            kind == EditSurfaceKindDto.TEXT_COLOR || kind == EditSurfaceKindDto.TYPOGRAPHY
        },
    )
}
```

- [ ] **Step 3: Enrich saved items with edit surfaces**

In `TargetEvidenceService`, wherever refreshed `AnnotationDto` is built with
`sourceCandidates`, call:

```kotlin
val editSurfaceCandidates = EditSurfaceCandidateService.build(refreshed, screen)
refreshed.copy(editSurfaceCandidates = editSurfaceCandidates)
```

Place this after `sourceCandidates` and `targetEvidence` have been derived so
the service can inspect both source candidates and screen roots.

- [ ] **Step 4: Render edit surface lines**

In `CompactHandoffRenderer.appendCompactItem`, after the UI/box line and before source candidates, add:

```kotlin
appendEditSurfaceBlock(item)
```

Add helpers:

```kotlin
    private fun StringBuilder.appendEditSurfaceBlock(item: AnnotationDto) {
        item.editSurfaceCandidates.take(2).forEach { candidate ->
            appendLine("  ${candidate.formatEditSurfaceLine()}")
            candidate.note?.takeIf { it.isNotBlank() }?.let { note ->
                appendLine("  note: ${note.inlineSafe()}")
            }
        }
    }

    private fun EditSurfaceCandidateDto.formatEditSurfaceLine(): String {
        val kind = when (kind) {
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
        return "editSurface: $kind -> ${fileLine.inlineSafe()}  conf=${confidence.name.lowercase()}  why=[$reasonTokens]"
    }
```

- [ ] **Step 5: Run MCP renderer tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest.renderShowsEditSurfaceBeforeDataSourceCandidateForStyleIntent" --no-daemon
```

Expected: PASS.

## Task 5: Add Golden Regression for the Six Failure Modes

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`

- [ ] **Step 1: Add synthetic six-item golden test**

Append a test named `renderSampleStyleHandoffExposesEditSurfacesAndWarnings`.
The fixture should not read `.fixthis/` files. Build a `SessionDto` with:

- one `MetricCard` parent tagged `comp:MetricCard:summary`
- one selected text `Resolved this week`
- one selected text `Priority feedback`
- one selected text `Open queue`
- one `FeedbackCard` parent visual/semantics target
- one `Resolved` chip target

Assert these substrings:

```kotlin
assertTrue(markdown.contains("""inside tag="comp:MetricCard:summary""""))
assertTrue(markdown.contains("editSurface: textColor -> sample/src/main/java/io/github/beyondwin/fixthis/sample/components/MetricCard.kt:26"))
assertTrue(markdown.contains("editSurface: typography -> sample/src/main/java/io/github/beyondwin/fixthis/sample/components/SectionHeader.kt:24"))
assertTrue(markdown.contains("editSurface: textColor -> sample/src/main/java/io/github/beyondwin/fixthis/sample/components/SectionHeader.kt:29"))
assertTrue(markdown.contains("editSurface: spacing -> sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt:51"))
assertTrue(markdown.contains("editSurface: chipColor -> sample/src/main/java/io/github/beyondwin/fixthis/sample/components/StatusChip.kt:45"))
```

- [ ] **Step 2: Run the golden test**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest.renderSampleStyleHandoffExposesEditSurfacesAndWarnings" --no-daemon
```

Expected: PASS after Tasks 2-4 are implemented.

## Task 6: Update Contracts and Docs

**Files:**
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `docs/reference/output-schema.md`
- Modify: `docs/reference/mcp-tools.md`

- [ ] **Step 1: Update compact handoff grammar**

In `docs/reference/feedback-console-contract.md`, add:

```markdown
edit_surface_block = edit_surface_line{0,2}
edit_surface_line  = "  editSurface: " kind " -> " file [ ":" line ] "  conf=" lvl "  why=[" terms "]"
```

Add definitions:

```markdown
- `editSurface:` — optional inspection hint for visual/style/layout feedback.
  It is derived from the user comment, selected target, target owner, and source
  candidates. It does not replace source candidates and is not an auto-edit
  instruction.
- `sourceCandidates` identify where selected or nearby strings came from.
  `editSurface` identifies where a style/layout change is likely rendered.
```

- [ ] **Step 2: Update output schema**

In `docs/reference/output-schema.md`, add `editSurfaceCandidates` to optional
feedback item fields and define the DTO:

```markdown
- `editSurfaceCandidates`: optional list of likely rendering/edit surfaces for
  style, typography, spacing, or component-renderer feedback. Legacy sessions
  omit it.
```

- [ ] **Step 3: Update MCP tools guidance**

In `docs/reference/mcp-tools.md`, add:

```markdown
For style/layout requests, agents should inspect `editSurface` before editing a
source-origin data candidate. Source candidates remain useful for identifying
which repeated item or data value the user selected.
```

## Task 7: Full Verification

**Files:**
- No source edits beyond previous tasks.

- [ ] **Step 1: Run focused tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" :fixthis-mcp:test --tests "*EditIntentClassifierTest" --tests "*CompactHandoffRendererTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Run module tests**

Run:

```bash
./gradlew :fixthis-compose-core:test :fixthis-mcp:test --no-daemon
```

Expected: PASS.

- [ ] **Step 3: Inspect generated compact handoff manually**

Use an existing test fixture or sample session and confirm:

- `target:` lines still render for every item.
- At most two `editSurface:` lines render per item.
- Source candidates remain visible.
- Warnings explain data-vs-renderer or nearby-only demotion when present.
- Legacy sessions without `editSurfaceCandidates` render without errors.

## Implementation Notes

- Keep `editSurfaceCandidates` optional and default-empty for JSON compatibility.
- Do not mutate existing `sourceCandidates`; sort/rank improvements are allowed,
  but source-origin candidates must remain visible.
- Keep Copy Prompt concise. If more than two edit surfaces are found, render the
  best two and leave full details in JSON.
- If owner context cannot be derived, render the existing target summary without
  adding noisy placeholders.
