# Copy Prompt Target Evidence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make compact Copy Prompt handoffs target-first by adding an up-front handoff quality summary and a redaction-safe per-item target summary line.

**Architecture:** Keep persistence and source matching unchanged. Add small private formatter helpers in `:fixthis-mcp` that derive target summaries and quality counts from existing `SessionDto` and `AnnotationDto` data, render those helpers from `CompactHandoffRenderer`, and update the compact handoff contract docs.

**Tech Stack:** Kotlin/JVM, Kotlin serialization DTOs, existing MCP session formatter tests, Markdown contract docs, Gradle test runner.

---

## Scope Check

The approved spec is one cohesive renderer change. It touches compact Markdown
rendering, formatter tests, and docs only. It does not require Android runtime
changes, bridge protocol changes, source matcher changes, sample app changes,
or console JavaScript UI changes.

## File Structure

- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`.
  Owns compact Markdown ordering and calls the new helpers.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FormatterExtensions.kt`.
  Owns small string helpers shared by compact formatter code.
- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetSummaryFormatter.kt`.
  Owns redaction-safe per-item `target:` summary rendering.
- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffQualitySummary.kt`.
  Owns aggregate quality counts and pluralized summary text.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`.
  Adds focused coverage for new prompt lines and compatibility tokens.
- Modify `docs/reference/feedback-console-contract.md`.
  Updates compact handoff grammar and definitions.
- Modify `docs/reference/mcp-tools.md`.
  Documents agent-facing interpretation.

## Task 1: Add Target Summary Formatter

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetSummaryFormatter.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FormatterExtensions.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`

- [ ] **Step 1: Add failing tests for target summary lines**

Append these tests to `CompactHandoffRendererTest`:

```kotlin
    @Test
    fun renderAddsTargetSummaryBeforeBoxLine() {
        val session = SessionDto(
            sessionId = "session-target-summary",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Review",
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node("node-1", FixThisRect(42f, 184f, 436f, 252f)),
                    selectedNode = FixThisNode(
                        uid = "node-1",
                        composeNodeId = 1,
                        rootIndex = 0,
                        treeKind = TreeKind.MERGED,
                        boundsInWindow = FixThisRect(42f, 184f, 436f, 252f),
                        text = listOf("Review request"),
                    ),
                    comment = "Make heading clearer",
                    sequenceNumber = 1,
                ),
            ),
        )

        val lines = CompactHandoffRenderer.render(session).lines()
        val idIndex = lines.indexOf("  id: item-1")
        val targetIndex = lines.indexOf("""  target: text="Review request"""")
        val boxIndex = lines.indexOf("  box=(42.0,184.0)-(436.0,252.0)")

        assertTrue(idIndex >= 0, "Expected id line in:\n${lines.joinToString("\n")}")
        assertTrue(targetIndex > idIndex, "target: line should come after id:")
        assertTrue(boxIndex > targetIndex, "box line should come after target:")
    }

    @Test
    fun renderTargetSummaryIncludesTagTextContentDescriptionAndRoleInStableOrder() {
        val session = oneItemSession(
            item = AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node("node-1", FixThisRect(0f, 0f, 100f, 50f)),
                selectedNode = FixThisNode(
                    uid = "node-1",
                    composeNodeId = 1,
                    rootIndex = 0,
                    treeKind = TreeKind.MERGED,
                    boundsInWindow = FixThisRect(0f, 0f, 100f, 50f),
                    text = listOf("Submit request"),
                    contentDescription = listOf("Submit handoff request"),
                    role = "Button",
                    testTag = "comp:ReviewScreen:submit",
                ),
                comment = "Increase tap affordance",
                sequenceNumber = 1,
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)

        assertTrue(
            markdown.contains(
                """  target: tag="comp:ReviewScreen:submit"; text="Submit request"; contentDescription="Submit handoff request"; role=Button""",
            ),
            markdown,
        )
    }

    @Test
    fun renderTargetSummaryForVisualAreaWithoutSelectedNode() {
        val session = oneItemSession(
            item = AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(10f, 20f, 120f, 80f)),
                selectedNode = null,
                comment = "Adjust chart spacing",
                sequenceNumber = 1,
            ),
        )

        assertTrue(CompactHandoffRenderer.render(session).contains("  target: visual area"))
    }

    @Test
    fun renderTargetSummaryRedactsSensitiveEditableAndPasswordText() {
        val sensitiveNode = FixThisNode(
            uid = "node-1",
            composeNodeId = 1,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(0f, 0f, 100f, 50f),
            text = listOf("agent-context-token"),
            editableText = "agent-context-token",
            role = "TextField",
            isPassword = true,
            isSensitive = true,
        )
        val session = oneItemSession(
            item = AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node("node-1", FixThisRect(0f, 0f, 100f, 50f)),
                selectedNode = sensitiveNode,
                comment = "Token field layout",
                sequenceNumber = 1,
                targetReliability = TargetReliability(
                    confidence = TargetConfidence.MEDIUM,
                    warnings = listOf(TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)

        assertTrue(markdown.contains("  target: redacted sensitive target; role=TextField"), markdown)
        assertTrue(!markdown.contains("agent-context-token"), markdown)
    }

    private fun oneItemSession(item: AnnotationDto): SessionDto = SessionDto(
        sessionId = "session-one-item",
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 1L,
                displayName = "Review",
            ),
        ),
        items = listOf(item),
    )
```

- [ ] **Step 2: Run the target summary tests and verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest.renderAddsTargetSummaryBeforeBoxLine" --tests "*CompactHandoffRendererTest.renderTargetSummaryIncludesTagTextContentDescriptionAndRoleInStableOrder" --tests "*CompactHandoffRendererTest.renderTargetSummaryForVisualAreaWithoutSelectedNode" --tests "*CompactHandoffRendererTest.renderTargetSummaryRedactsSensitiveEditableAndPasswordText" --no-daemon
```

Expected: FAIL because `target:` lines are not rendered yet.

- [ ] **Step 3: Add string helpers**

In `FormatterExtensions.kt`, add:

```kotlin
internal fun String.compactQuotedValue(maxLength: Int = 80): String {
    val normalized = inlineSafe().replace("\"", "'")
    return if (normalized.length <= maxLength) {
        normalized
    } else {
        normalized.take(maxLength - 3) + "..."
    }
}
```

This helper keeps generated Kotlin source ASCII-only while preserving a compact
preview of long values.

- [ ] **Step 4: Add target summary formatter**

Create `TargetSummaryFormatter.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning

internal object TargetSummaryFormatter {
    fun render(item: AnnotationDto): String {
        val node = item.selectedNode
        if (node == null) {
            return when (item.target) {
                is AnnotationTargetDto.Area -> "target: visual area"
                is AnnotationTargetDto.Node -> "target: semantics node"
            }
        }

        val redacted = shouldRedact(node, item)
        val parts = mutableListOf<String>()

        node.testTag?.takeIf { it.isNotBlank() }?.let { tag ->
            parts += "tag=\"${tag.compactQuotedValue()}\""
        }

        if (!redacted) {
            node.text.firstOrNull { it.isNotBlank() }?.let { text ->
                parts += "text=\"${text.compactQuotedValue()}\""
            }
            node.contentDescription.firstOrNull { it.isNotBlank() }?.let { description ->
                parts += "contentDescription=\"${description.compactQuotedValue()}\""
            }
        }

        node.role?.takeIf { it.isNotBlank() }?.let { role ->
            parts += "role=${role.inlineSafe()}"
        }

        return when {
            parts.isNotEmpty() && redacted -> "target: redacted sensitive target; ${parts.joinToString("; ")}"
            parts.isNotEmpty() -> "target: ${parts.joinToString("; ")}"
            redacted -> "target: redacted sensitive target"
            else -> "target: semantics node"
        }
    }

    fun isRedacted(item: AnnotationDto): Boolean = item.selectedNode?.let { shouldRedact(it, item) } ?: false

    private fun shouldRedact(node: FixThisNode, item: AnnotationDto): Boolean =
        node.isPassword ||
            node.isSensitive ||
            !node.editableText.isNullOrBlank() ||
            item.targetReliability?.warnings.orEmpty().contains(TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED)
}
```

- [ ] **Step 5: Render the target summary from CompactHandoffRenderer**

In `CompactHandoffRenderer.appendCompactItem`, add the target summary between
the `id:` line and existing compact UI line:

```kotlin
        appendLine("  id: ${item.itemId}")
        appendLine("  ${TargetSummaryFormatter.render(item)}")
        appendLine(compactUiLine(item, isOverlap, instanceLabel, dupRefMarker))
```

- [ ] **Step 6: Run the target summary tests and verify they pass**

Run the same targeted command from Step 2.

Expected: PASS for all four target summary tests.

- [ ] **Step 7: Commit target summary formatter**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FormatterExtensions.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetSummaryFormatter.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt
git commit -m "feat: add target summaries to compact handoff"
```

## Task 2: Add Handoff Quality Summary

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffQualitySummary.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`

- [ ] **Step 1: Add failing tests for quality summary**

Append these tests to `CompactHandoffRendererTest`:

```kotlin
    @Test
    fun renderAddsHandoffQualitySummaryForRiskSignals() {
        val session = SessionDto(
            sessionId = "session-quality",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Review")),
            items = listOf(
                AnnotationDto(
                    itemId = "item-low",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(10f, 10f, 120f, 120f)),
                    comment = "Visual area feedback",
                    sequenceNumber = 1,
                    targetReliability = TargetReliability(
                        confidence = TargetConfidence.LOW,
                        warnings = listOf(TargetReliabilityWarning.VISUAL_AREA_ONLY),
                    ),
                    sourceCandidates = emptyList(),
                ),
                AnnotationDto(
                    itemId = "item-redacted",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node("node-redacted", FixThisRect(130f, 10f, 240f, 120f)),
                    selectedNode = FixThisNode(
                        uid = "node-redacted",
                        composeNodeId = 2,
                        rootIndex = 0,
                        treeKind = TreeKind.MERGED,
                        boundsInWindow = FixThisRect(130f, 10f, 240f, 120f),
                        text = listOf("secret"),
                        editableText = "secret",
                        isSensitive = true,
                    ),
                    comment = "Sensitive field",
                    sequenceNumber = 2,
                    targetReliability = TargetReliability(
                        confidence = TargetConfidence.MEDIUM,
                        warnings = listOf(TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED),
                    ),
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/ReviewScreen.kt",
                            line = 70,
                            score = 0.9,
                            confidence = SelectionConfidence.HIGH,
                            stale = true,
                            staleReason = "excerpt mismatch",
                        ),
                    ),
                ),
                AnnotationDto(
                    itemId = "item-overlap",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node("node-overlap", FixThisRect(20f, 20f, 100f, 100f)),
                    selectedNode = FixThisNode(
                        uid = "node-overlap",
                        composeNodeId = 3,
                        rootIndex = 0,
                        treeKind = TreeKind.MERGED,
                        boundsInWindow = FixThisRect(20f, 20f, 100f, 100f),
                        text = listOf("Overlap"),
                    ),
                    comment = "Overlapping node",
                    sequenceNumber = 3,
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)

        assertTrue(markdown.contains("Handoff quality:"), markdown)
        assertTrue(markdown.contains("1 low-confidence target"), markdown)
        assertTrue(markdown.contains("2 warning targets"), markdown)
        assertTrue(markdown.contains("1 overlap group"), markdown)
        assertTrue(markdown.contains("1 visual area"), markdown)
        assertTrue(markdown.contains("1 redacted target"), markdown)
        assertTrue(markdown.contains("1 stale source candidate"), markdown)
        assertTrue(markdown.contains("1 item without source candidates"), markdown)
    }

    @Test
    fun renderOmitsHandoffQualitySummaryWhenNoSignalsExist() {
        val session = oneItemSession(
            item = AnnotationDto(
                itemId = "item-clean",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node("node-clean", FixThisRect(0f, 0f, 100f, 50f)),
                selectedNode = FixThisNode(
                    uid = "node-clean",
                    composeNodeId = 1,
                    rootIndex = 0,
                    treeKind = TreeKind.MERGED,
                    boundsInWindow = FixThisRect(0f, 0f, 100f, 50f),
                    text = listOf("Clean target"),
                ),
                comment = "Clean feedback",
                sequenceNumber = 1,
                targetReliability = TargetReliability(confidence = TargetConfidence.HIGH),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/java/ReviewScreen.kt",
                        line = 56,
                        score = 0.95,
                        confidence = SelectionConfidence.HIGH,
                    ),
                ),
            ),
        )

        assertTrue(!CompactHandoffRenderer.render(session).contains("Handoff quality:"))
    }

    @Test
    fun renderHandoffQualitySummaryUsesFilteredItemSet() {
        val session = SessionDto(
            sessionId = "session-filtered-quality",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Review")),
            items = listOf(
                AnnotationDto(
                    itemId = "item-clean",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node("node-clean", FixThisRect(0f, 0f, 100f, 50f)),
                    comment = "Clean feedback",
                    sequenceNumber = 1,
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/ReviewScreen.kt",
                            line = 56,
                            score = 0.95,
                            confidence = SelectionConfidence.HIGH,
                        ),
                    ),
                ),
                AnnotationDto(
                    itemId = "item-low",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(10f, 10f, 120f, 120f)),
                    comment = "Low confidence feedback",
                    sequenceNumber = 2,
                    targetReliability = TargetReliability(
                        confidence = TargetConfidence.LOW,
                        warnings = listOf(TargetReliabilityWarning.VISUAL_AREA_ONLY),
                    ),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session, itemIds = listOf("item-clean"))

        assertTrue(!markdown.contains("Handoff quality:"), markdown)
        assertTrue(markdown.contains("[1] Clean feedback"), markdown)
        assertTrue(!markdown.contains("Low confidence feedback"), markdown)
    }
```

- [ ] **Step 2: Run quality summary tests and verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest.renderAddsHandoffQualitySummaryForRiskSignals" --tests "*CompactHandoffRendererTest.renderOmitsHandoffQualitySummaryWhenNoSignalsExist" --tests "*CompactHandoffRendererTest.renderHandoffQualitySummaryUsesFilteredItemSet" --no-daemon
```

Expected: at least the risk-signal test fails because no `Handoff quality:`
line is rendered.

- [ ] **Step 3: Add quality summary helper**

Create `HandoffQualitySummary.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence

internal object HandoffQualitySummary {
    fun render(
        items: List<AnnotationDto>,
        overlapGroups: List<List<AnnotationOverlapDetector.Item>>,
        duplicateMap: Map<String, Int>,
    ): String? {
        val summary = Counts(
            lowConfidenceTargets = items.count { it.targetReliability?.confidence == TargetConfidence.LOW },
            warningTargets = items.count { it.targetReliability?.warnings.orEmpty().isNotEmpty() },
            overlapGroups = overlapGroups.count { it.size > 1 },
            duplicateMarkers = duplicateMap.size,
            visualAreas = items.count { it.target is AnnotationTargetDto.Area },
            redactedTargets = items.count { TargetSummaryFormatter.isRedacted(it) },
            staleSourceCandidateItems = items.count { item -> item.sourceCandidates.any { it.stale == true } },
            itemsWithoutSourceCandidates = items.count { it.sourceCandidates.isEmpty() },
        )
        return summary.render()
    }

    private data class Counts(
        val lowConfidenceTargets: Int,
        val warningTargets: Int,
        val overlapGroups: Int,
        val duplicateMarkers: Int,
        val visualAreas: Int,
        val redactedTargets: Int,
        val staleSourceCandidateItems: Int,
        val itemsWithoutSourceCandidates: Int,
    ) {
        fun render(): String? {
            val parts = listOfNotNull(
                lowConfidenceTargets.label("low-confidence target"),
                warningTargets.label("warning target"),
                overlapGroups.label("overlap group"),
                duplicateMarkers.label("duplicate marker"),
                visualAreas.label("visual area"),
                redactedTargets.label("redacted target"),
                staleSourceCandidateItems.label("stale source candidate"),
                itemsWithoutSourceCandidates.label("item without source candidates", plural = "items without source candidates"),
            )
            return parts.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Handoff quality: ", separator = ", ")
        }
    }

    private fun Int.label(singular: String, plural: String = "${singular}s"): String? = when (this) {
        0 -> null
        1 -> "1 $singular"
        else -> "$this $plural"
    }
}
```

- [ ] **Step 4: Compute quality data once per render**

In `CompactHandoffRenderer.render`, after `orderedItems` is computed and before
screen rendering, compute a session-level analysis object. The existing
overlap and duplicate logic is currently inside each screen loop, so extract it
into a helper to avoid duplicate detector calls.

Add private DTOs inside `CompactHandoffRenderer`:

```kotlin
    private data class ScreenHandoffAnalysis(
        val indexedItems: List<IndexedValue<AnnotationDto>>,
        val grouping: InstanceGrouping,
        val groups: List<List<AnnotationOverlapDetector.Item>>,
        val markerByItemId: Map<String, Int>,
        val duplicateMap: Map<String, Int>,
    )
```

Add helper:

```kotlin
    private fun analyzeScreen(
        indexedItems: List<IndexedValue<AnnotationDto>>,
        startingMarker: Int,
    ): ScreenHandoffAnalysis {
        val itemsForScreen = indexedItems.map { it.value }
        val grouping = InstanceGroupingHelper.compute(itemsForScreen)
        val detectorItems = indexedItems.map { entry ->
            val isArea = entry.value.target is AnnotationTargetDto.Area
            val hasWeakLabels = entry.value.selectedNode?.text?.isEmpty() ?: true
            AnnotationOverlapDetector.Item(
                id = entry.value.itemId,
                bounds = when (val target = entry.value.target) {
                    is AnnotationTargetDto.Area -> target.boundsInWindow
                    is AnnotationTargetDto.Node -> target.boundsInWindow
                },
                isAreaSelection = isArea,
                hasWeakLabels = hasWeakLabels,
            )
        }
        val groups = AnnotationOverlapDetector.detect(detectorItems)
        val itemById = indexedItems.associate { it.value.itemId to it.value }
        var preCounter = startingMarker
        val markerByItemId = mutableMapOf<String, Int>()
        groups.forEach { group ->
            group.forEach { detectorItem ->
                if (itemById.containsKey(detectorItem.id)) {
                    preCounter += 1
                    markerByItemId[detectorItem.id] = preCounter
                }
            }
        }
        val dupDetectorItems = indexedItems.mapNotNull { entry ->
            val annotation = entry.value
            val marker = markerByItemId[annotation.itemId] ?: return@mapNotNull null
            val fileLine = annotation.sourceCandidates.firstOrNull()?.fileWithLine()
            val pathLeaves = annotation.selectedNode?.path ?: emptyList()
            val bounds = when (val target = annotation.target) {
                is AnnotationTargetDto.Area -> target.boundsInWindow
                is AnnotationTargetDto.Node -> target.boundsInWindow
            }
            DuplicateMarkerDetector.Item(
                itemId = annotation.itemId,
                markerNumber = marker,
                key = DuplicateMarkerDetector.Key(
                    fileLine = fileLine,
                    testTag = annotation.selectedNode?.testTag,
                    pathLeaves = pathLeaves,
                    bounds = bounds,
                ),
            )
        }
        return ScreenHandoffAnalysis(
            indexedItems = indexedItems,
            grouping = grouping,
            groups = groups,
            markerByItemId = markerByItemId,
            duplicateMap = DuplicateMarkerDetector.detect(dupDetectorItems),
        )
    }
```

`InstanceGrouping` is the current return type of `InstanceGroupingHelper.compute`
and is already package-visible in `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/InstanceGroupingHelper.kt`.

- [ ] **Step 5: Render quality line in header**

Move the blank header separator currently rendered immediately after
`Source root:` so it is emitted after the optional quality line. The header
order should be package, optional source root, optional quality, blank line,
then the first screen block.

Build screen analyses in order before writing screen blocks:

```kotlin
        val itemsByScreen = orderedItems.groupBy { it.value.screenId }
        var precomputedMarkerCounter = 0
        val analysesByScreen = itemsByScreen.mapValues { (_, indexedItems) ->
            analyzeScreen(indexedItems, precomputedMarkerCounter).also { analysis ->
                precomputedMarkerCounter += analysis.groups.sumOf { it.size }
            }
        }
        val allItems = orderedItems.map { it.value }
        val allOverlapGroups = analysesByScreen.values.flatMap { it.groups }
        val allDuplicateMap = analysesByScreen.values.flatMap { it.duplicateMap.entries }
            .associate { it.key to it.value }
        HandoffQualitySummary.render(allItems, allOverlapGroups, allDuplicateMap)?.let {
            appendLine(it)
        }
        appendLine()
```

Then inside the screen loop, reuse `analysesByScreen.getValue(screenId)` for
`grouping`, `groups`, `markerByItemId`, and `duplicateMap`.

Keep item numbering exactly as it works today. Do not change `globalCounter`
assignment during actual item rendering.

- [ ] **Step 6: Run quality summary tests and verify they pass**

Run the targeted command from Step 2.

Expected: PASS.

- [ ] **Step 7: Run existing compact renderer tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest" --no-daemon
```

Expected: PASS. If exact fixture tests fail because the compact output gained
new additive lines, update expected fixture files only after confirming all old
contract tokens are still present.

- [ ] **Step 8: Commit quality summary**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffQualitySummary.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt
git commit -m "feat: summarize compact handoff quality"
```

## Task 3: Update Contract And MCP Docs

**Files:**
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `docs/reference/mcp-tools.md`

- [ ] **Step 1: Update compact grammar**

In `docs/reference/feedback-console-contract.md`, update the grammar block:

```markdown
prompt        = header rule package_line source_root_line? quality_line? "" screen_block+
quality_line  = "Handoff quality: " quality_token (", " quality_token)*
item_block    = item_header id_line target_summary_line target_line crop_line? source_block reliability_block? ""
target_summary_line = "  target: " target_summary
```

Add this paragraph after the existing `Source root:` bullet:

```markdown
- `Handoff quality:` - optional aggregate warning summary for the rendered item
  set. It is emitted only when at least one low-confidence target, warning,
  overlap group, duplicate marker, visual area, redacted target, stale source
  candidate, or item without source candidates exists.
```

Add this paragraph after the target-line bullet:

```markdown
- `target:` - redaction-safe semantic target summary derived from the selected
  node when available. It may include `tag="..."`, `text="..."`,
  `contentDescription="..."`, and `role=...` in that order. For visual-area
  selections it renders `target: visual area`; for sensitive/editable/password
  targets it omits text-like values and renders `redacted sensitive target`.
```

- [ ] **Step 2: Update MCP reference**

In `docs/reference/mcp-tools.md`, extend the compact Markdown description near
the existing target confidence paragraph:

```markdown
Compact Markdown may include a `Handoff quality:` line near the top of the
prompt. This is an aggregate warning summary for the rendered item set; it is
not a blocker. Agents should use it to decide how much screenshot/code
verification is needed before editing.

Each compact item includes a `target:` line before the coordinate `box=` line.
The target line is a redaction-safe semantic summary of what the user selected.
Source candidates remain hints; the target line and screenshot are the primary
evidence for what the user meant.
```

- [ ] **Step 3: Commit docs**

```bash
git add docs/reference/feedback-console-contract.md docs/reference/mcp-tools.md
git commit -m "docs: document target-first compact handoffs"
```

## Task 4: Final Verification

**Files:**
- Verify only; no planned source edits.

- [ ] **Step 1: Run focused MCP tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest" --tests "*FeedbackQueueFormatterTest" --tests "*ConsoleHandoffRoutesTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Run docs/contract grep checks**

Run:

```bash
rg -n "Handoff quality|target_summary_line|target: visual area|redacted sensitive target" docs/reference fixthis-mcp/src/main/kotlin fixthis-mcp/src/test/kotlin
```

Expected: output includes:

```text
docs/reference/feedback-console-contract.md
docs/reference/mcp-tools.md
fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetSummaryFormatter.kt
fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffQualitySummary.kt
fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt
```

- [ ] **Step 3: Review compact output manually**

Run a single renderer test with Gradle output enabled if needed, or temporarily
inspect a local rendered session through existing test fixtures. Confirm the
output order is:

```text
# FixThis Feedback Handoff

Rule: source hints are candidates; verify screenshot, target, and code before editing.

- Package: `...`
- Source root: `...`
Handoff quality: ...

Screen ...

[1] ...
  id: ...
  target: ...
  box=...
```

Do not add debug output to tests or production formatter code.

- [ ] **Step 4: Run git diff review**

Run:

```bash
git diff --check
git diff --stat
git diff -- fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session docs/reference
```

Expected:

- `git diff --check` prints no whitespace errors.
- Diff touches only formatter/helper/test/docs files listed in this plan.
- No persisted schema field names are renamed.
- No sensitive target text fixture appears unredacted in expected output.

- [ ] **Step 5: Final squashed commit option**

When executing this plan as one batch instead of using the per-task commits
above, commit the complete change:

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FormatterExtensions.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetSummaryFormatter.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffQualitySummary.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt \
  docs/reference/feedback-console-contract.md \
  docs/reference/mcp-tools.md
git commit -m "feat: make compact handoffs target-first"
```

## Plan Self-Review

- Spec coverage: target summary, quality summary, redaction, compatibility,
  docs, and verification are each covered by a task.
- Placeholder scan: no task relies on unspecified behavior or unresolved
  placeholder steps.
- Type consistency: the plan uses existing `AnnotationDto`, `FixThisNode`,
  `TargetReliability`, `TargetReliabilityWarning`, `AnnotationOverlapDetector`,
  and `DuplicateMarkerDetector` names from the current codebase.
- Scope control: the plan does not modify source matching, sidekick capture,
  sample app semantics, or console JavaScript UI.

## Execution Handoff

Plan complete. Recommended execution mode is subagent-driven development with
one worker for Task 1, one worker for Task 2, and inline review for docs and
verification. Inline execution is also safe because all edits are confined to
MCP formatter/docs files.
