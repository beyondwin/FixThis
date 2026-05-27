# Interop Trust And Push-First Console Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make interop-adjacent targets render nearby Compose context without implying exact ownership, and make healthy SSE stop redundant session/preview polling.

**Architecture:** Track A stays inside existing MCP/session handoff contracts by deriving boundary context from persisted `nearbyNodes`, `targetReliability`, and bounds instead of adding new JSON fields. Track B keeps `/api/events` as the push channel, adds session summary payloads to `sessions-updated`, and gates session polling behind EventSource fallback state.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, kotlin.test/JUnit, vanilla browser JavaScript, Node.js `node:test`, Markdown docs.

---

## Scope Check

The approved spec has two tracks, but they are not independent products. Track A and Track B can ship separately with clean commit boundaries:

- Track A produces working handoff improvements without touching console polling.
- Track B produces working SSE/polling cleanup without changing target evidence policy.
- Final docs and graph refresh happen after both tracks pass focused tests.

This plan intentionally avoids XML/View exact targeting, WebView DOM inspection, new transports, release-build behavior, and persisted field renames.

## File Structure

### Track A - Interop Boundary Evidence

- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatter.kt`
  - Single responsibility: derive compact and precise context lines from existing `AnnotationDto.nearbyNodes` and `targetReliability`.
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatterTest.kt`
  - Locks privacy-safe context rendering and non-interop no-op behavior.
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
  - Render one compact boundary context line after `targetBoundary=interop-risk`.
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
  - Render precise boundary context lines before source candidates.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
  - Prove compact interop output includes context without exact ownership language.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`
  - Prove precise interop output includes boundary context before candidates.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt`
  - Add optional nearby-node fixture support for corpus cases.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`
  - Add the new corpus case id and assert interop context appears.
- Modify: `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`
  - Add an interop case with a nearby Compose host.

### Track B - SSE Polling Retirement

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventEmitters.kt`
  - Include `FeedbackSessionSummary.from(session)` in `sessions-updated` events.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt`
  - Assert emitted `sessions-updated` events carry a summary payload.
- Modify: `fixthis-mcp/src/main/console/history.js`
  - Add an upsert helper for pushed session summaries.
- Modify: `fixthis-mcp/src/main/console/events.js`
  - Apply pushed summary payloads without HTTP refresh; fallback to pull only when needed.
- Modify: `fixthis-mcp/src/main/console/sse.js`
  - Add `shouldUseSessionFallbackPolling()` beside preview fallback state.
- Modify: `fixthis-mcp/src/main/console/sessions-polling.js`
  - Gate passive polling on `shouldUseSessionFallbackPolling()`.
- Modify: `scripts/studioReliabilityContract-test.mjs`
  - Add grep-contract tests for summary application and fallback-only polling.
- Modify: `scripts/console-browser-reliability.mjs`
  - Extend the healthy-SSE proof to fail on redundant session refreshes.

### Docs And Verification

- Modify: `docs/reference/feedback-console-contract.md`
  - Document interop boundary context and summary-bearing `sessions-updated`.
- Modify: `docs/architecture/console-state-sync-design.md`
  - Update remaining work/status around session polling fallback.
- Modify: `docs/product/roadmap.md`
  - Narrow the interop/SSE roadmap entries after the implementation lands.
- Modify: `CHANGELOG.md`
  - Add user-visible unreleased entries.

## Task 1: Add Boundary Context Formatter

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatterTest.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatter.kt`

- [ ] **Step 1: Write the failing formatter tests**

Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatterTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetBoundaryContextFormatterTest {
    @Test
    fun compactLineUsesNearestNonSensitiveComposeContextForInteropRisk() {
        val item = interopAreaItem(
            nearbyNodes = listOf(
                node(
                    uid = "native-chart-host",
                    testTag = "comp:NativeChartHost:chart",
                    role = "Image",
                    bounds = FixThisRect(0f, 80f, 320f, 260f),
                ),
            ),
        )

        val line = TargetBoundaryContextFormatter.compactLine(item)

        assertEquals(
            "boundaryContext: tag=\"comp:NativeChartHost:chart\"; role=Image; box=(0.0,80.0)-(320.0,260.0)",
            line,
        )
    }

    @Test
    fun preciseLinesTellAgentThatContextIsNotExactOwnership() {
        val item = interopAreaItem(
            nearbyNodes = listOf(
                node(
                    uid = "native-chart-host",
                    text = listOf("Revenue"),
                    testTag = "comp:NativeChartHost:chart",
                    bounds = FixThisRect(0f, 80f, 320f, 260f),
                ),
            ),
        )

        val lines = TargetBoundaryContextFormatter.preciseLines(item)

        assertTrue(lines.contains("- Boundary context: nearest Compose context tag=\"comp:NativeChartHost:chart\"; text=\"Revenue\"; box=`0.0,80.0,320.0,260.0`."))
        assertTrue(lines.contains("- Boundary context note: this context helps locate the host; it does not prove Compose owns the selected pixels."))
    }

    @Test
    fun sensitiveNearbyNodesDoNotLeakTextOrEditableContent() {
        val item = interopAreaItem(
            nearbyNodes = listOf(
                node(
                    uid = "password",
                    text = listOf("secret-token"),
                    editableText = "secret-token",
                    isSensitive = true,
                    testTag = "comp:LoginField:password",
                ),
            ),
        )

        val precise = TargetBoundaryContextFormatter.preciseLines(item).joinToString("\n")

        assertTrue(precise.contains("tag=\"comp:LoginField:password\""))
        assertTrue(!precise.contains("secret-token"), precise)
    }

    @Test
    fun nonInteropItemDoesNotRenderContext() {
        val item = AnnotationDto(
            itemId = "item",
            screenId = "screen",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 100f, 80f)),
            nearbyNodes = listOf(node(uid = "host", testTag = "comp:Host:root")),
            comment = "Tighten this area",
        )

        assertNull(TargetBoundaryContextFormatter.compactLine(item))
        assertEquals(emptyList(), TargetBoundaryContextFormatter.preciseLines(item))
    }

    private fun interopAreaItem(nearbyNodes: List<FixThisNode>): AnnotationDto = AnnotationDto(
        itemId = "item",
        screenId = "screen",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Area(FixThisRect(40f, 120f, 220f, 220f)),
        nearbyNodes = nearbyNodes,
        comment = "Fix the native chart spacing",
        targetReliability = TargetReliability(
            confidence = TargetConfidence.LOW,
            warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
        ),
    )

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        editableText: String? = null,
        testTag: String? = null,
        role: String? = null,
        bounds: FixThisRect = FixThisRect(0f, 0f, 120f, 80f),
        isSensitive: Boolean = false,
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
        editableText = editableText,
        role = role,
        testTag = testTag,
        isSensitive = isSensitive,
    )
}
```

- [ ] **Step 2: Run the formatter test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --no-daemon
```

Expected: FAIL because `TargetBoundaryContextFormatter` does not exist.

- [ ] **Step 3: Add the formatter implementation**

Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatter.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning

internal object TargetBoundaryContextFormatter {
    fun compactLine(item: AnnotationDto): String? {
        if (!item.hasInteropBoundary()) return null
        val node = item.boundaryContextNode() ?: return null
        val summary = node.safeSummaryParts().joinToString("; ")
        if (summary.isBlank()) return null
        return "boundaryContext: $summary; box=${node.boundsInWindow.formatBox()}"
    }

    fun preciseLines(item: AnnotationDto): List<String> {
        if (!item.hasInteropBoundary()) return emptyList()
        val node = item.boundaryContextNode() ?: return emptyList()
        val summary = node.safeSummaryParts().joinToString("; ")
        if (summary.isBlank()) return emptyList()
        return listOf(
            "- Boundary context: nearest Compose context $summary; box=`${node.boundsInWindow.formatBounds()}`.",
            "- Boundary context note: this context helps locate the host; it does not prove Compose owns the selected pixels.",
        )
    }

    private fun AnnotationDto.hasInteropBoundary(): Boolean =
        targetReliability?.warnings.orEmpty().contains(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP)

    private fun AnnotationDto.boundaryContextNode(): FixThisNode? = nearbyNodes
        .asSequence()
        .filter { node -> node.hasSafeContextSignal() }
        .sortedWith(
            compareByDescending<FixThisNode> { it.testTag?.startsWith("comp:") == true }
                .thenBy { it.boundsInWindow.area },
        )
        .firstOrNull()

    private fun FixThisNode.hasSafeContextSignal(): Boolean =
        !testTag.isNullOrBlank() ||
            !role.isNullOrBlank() ||
            (!isSensitive && !isPassword && editableText.isNullOrBlank() && text.any { it.isNotBlank() }) ||
            (!isSensitive && !isPassword && contentDescription.any { it.isNotBlank() })

    private fun FixThisNode.safeSummaryParts(): List<String> = buildList {
        testTag?.takeIf { it.isNotBlank() }?.let { add("tag=\"${it.compactQuotedValue()}\"") }
        role?.takeIf { it.isNotBlank() }?.let { add("role=${it.inlineSafe()}") }
        if (!isSensitive && !isPassword && editableText.isNullOrBlank()) {
            text.firstOrNull { it.isNotBlank() }?.let { add("text=\"${it.compactQuotedValue()}\"") }
            contentDescription.firstOrNull { it.isNotBlank() }?.let {
                add("contentDescription=\"${it.compactQuotedValue()}\"")
            }
        }
    }
}
```

- [ ] **Step 4: Run the formatter test and verify it passes**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatter.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatterTest.kt
git commit -m "feat(handoff): format interop boundary context"
```

## Task 2: Render Boundary Context In Handoffs

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`

- [ ] **Step 1: Add failing renderer tests**

Append this test to `CompactHandoffRendererTest`:

```kotlin
@Test
fun compactHandoffRendersInteropBoundaryContextFromNearbyComposeHost() {
    val host = FixThisNode(
        uid = "host",
        composeNodeId = 10,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 80f, 320f, 260f),
        testTag = "comp:NativeChartHost:chart",
        role = "Image",
    )
    val item = AnnotationDto(
        itemId = "interop-item",
        screenId = "screen",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Area(FixThisRect(40f, 120f, 220f, 220f)),
        nearbyNodes = listOf(host),
        comment = "Fix the native chart spacing",
        targetReliability = TargetReliability(
            confidence = TargetConfidence.LOW,
            warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
        ),
    )
    val session = oneItemSession(item)

    val markdown = CompactHandoffRenderer.render(session)

    assertTrue(markdown.contains("targetBoundary=interop-risk"), markdown)
    assertTrue(markdown.contains("boundaryContext: tag=\"comp:NativeChartHost:chart\"; role=Image"), markdown)
    assertTrue(markdown.contains("targetAction=treat-source-paths-as-hints"), markdown)
}
```

Append this test to `FeedbackQueueFormatterPhase2Test`:

```kotlin
@Test
fun boundaryContext_preciseRendersBeforeLikelySourceCandidates() {
    val host = io.github.beyondwin.fixthis.compose.core.model.FixThisNode(
        uid = "host",
        composeNodeId = 10,
        rootIndex = 0,
        treeKind = io.github.beyondwin.fixthis.compose.core.model.TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 80f, 320f, 260f),
        text = listOf("Revenue"),
        testTag = "comp:NativeChartHost:chart",
    )
    val item = annotationWith(
        sourceCandidates = listOf(sourceCandidate(file = "sample/NativeChartHost.kt")),
        editSurfaceCandidates = emptyList(),
        targetReliability = reliability(
            confidence = io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW,
            warnings = listOf(
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP,
            ),
        ),
    ).copy(
        target = AnnotationTargetDto.Area(FixThisRect(40f, 120f, 220f, 220f)),
        nearbyNodes = listOf(host),
    )

    val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

    val boundaryIndex = md.indexOf("- Boundary: possible AndroidView/WebView target")
    val contextIndex = md.indexOf("- Boundary context: nearest Compose context")
    val sourceIndex = md.indexOf("1. `sample/NativeChartHost.kt`")
    assertTrue(boundaryIndex >= 0, "missing boundary guidance\n$md")
    assertTrue(contextIndex > boundaryIndex, "context must follow boundary guidance\n$md")
    assertTrue(sourceIndex > contextIndex, "source candidates must follow boundary context\n$md")
    assertTrue(md.contains("it does not prove Compose owns the selected pixels"), md)
}
```

- [ ] **Step 2: Run the renderer tests and verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests "*CompactHandoffRendererTest.compactHandoffRendersInteropBoundaryContextFromNearbyComposeHost" \
  --tests "*FeedbackQueueFormatterPhase2Test.boundaryContext_preciseRendersBeforeLikelySourceCandidates" \
  --no-daemon
```

Expected: FAIL because renderers do not yet call `TargetBoundaryContextFormatter`.

- [ ] **Step 3: Wire compact rendering**

In `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`, update `appendCompactItem` immediately after the existing `targetBoundary` block:

```kotlin
        TargetBoundaryGuidance.from(item).compactToken?.let { token ->
            appendLine("  targetBoundary=$token")
        }
        TargetBoundaryContextFormatter.compactLine(item)?.let { line ->
            appendLine("  $line")
        }
```

- [ ] **Step 4: Wire precise/full rendering**

In `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`, update `appendLikelySource` so the start of the method is:

```kotlin
        val sourceCandidates = item.sourceCandidates
        val editSurfaceCandidates = item.editSurfaceCandidates
        TargetBoundaryGuidance.from(item).preciseLines.forEach { line ->
            appendLine(line)
        }
        TargetBoundaryContextFormatter.preciseLines(item).forEach { line ->
            appendLine(line)
        }
```

- [ ] **Step 5: Run the focused renderer tests**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests "*CompactHandoffRendererTest.compactHandoffRendersInteropBoundaryContextFromNearbyComposeHost" \
  --tests "*FeedbackQueueFormatterPhase2Test.boundaryContext_preciseRendersBeforeLikelySourceCandidates" \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt
git commit -m "feat(handoff): render interop boundary context"
```

## Task 3: Add Corpus Coverage For Interop Host Context

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`
- Modify: `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`

- [ ] **Step 1: Extend the corpus fixture model**

In `HandoffEvaluationFixtures.kt`, add this property to `HandoffEvaluationCase`:

```kotlin
    val nearbyNodes: List<HandoffEvaluationNode> = emptyList(),
```

Then add this data class after `HandoffEvaluationSourceCandidate`:

```kotlin
@Serializable
internal data class HandoffEvaluationNode(
    val uid: String,
    val text: List<String> = emptyList(),
    val role: String? = null,
    val testTag: String? = null,
)
```

- [ ] **Step 2: Convert nearby nodes into annotations and screens**

In `HandoffEvaluationFixtures.annotationFor`, add:

```kotlin
        val nearbyNodes = case.nearbyNodes.map { it.toNode(case.id) }
```

Then include the property in the `AnnotationDto` construction:

```kotlin
            nearbyNodes = nearbyNodes,
```

Update the return statement to pass nearby nodes into the screen:

```kotlin
        return item.copy(editSurfaceCandidates = EditSurfaceCandidateService.build(item, screenFor(case, node, nearbyNodes)))
```

Change `screenFor` to accept nearby nodes:

```kotlin
    fun screenFor(
        case: HandoffEvaluationCase,
        node: FixThisNode? = null,
        nearbyNodes: List<FixThisNode> = emptyList(),
    ): SnapshotDto = SnapshotDto(
        screenId = "screen-${case.id}",
        capturedAtEpochMillis = 1L,
        displayName = "Eval",
        roots = listOf(
            SnapshotRootDto(
                rootIndex = 0,
                boundsInWindow = FixThisRect(0f, 0f, 400f, 800f),
                mergedNodes = listOfNotNull(node) + nearbyNodes,
            ),
        ),
    )
```

Add this converter near `toSourceCandidate()`:

```kotlin
    private fun HandoffEvaluationNode.toNode(caseId: String): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = "$caseId-$uid".hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 80f, 320f, 260f),
        text = text,
        role = role,
        testTag = testTag,
        path = listOf("root", uid),
    )
```

- [ ] **Step 3: Add the corpus case**

In `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`, insert this case after `interop-risk`:

```json
    {
      "id": "interop-risk-with-compose-host",
      "comment": "Fix the native chart spacing",
      "targetType": "area",
      "targetWarnings": ["POSSIBLE_VIEW_INTEROP"],
      "nearbyNodes": [
        {
          "uid": "native-chart-host",
          "text": ["Revenue"],
          "role": "Image",
          "testTag": "comp:NativeChartHost:chart"
        }
      ],
      "sourceCandidates": [
        {
          "file": "sample/src/main/java/io/github/beyondwin/fixthis/sample/NativeChartHost.kt",
          "line": 31,
          "score": 62.0,
          "confidence": "MEDIUM",
          "matchReasons": ["nearby testTag"],
          "matchedTerms": ["NativeChartHost"],
          "ownerComposable": "NativeChartHost"
        }
      ],
      "expectedRole": "INTEROP_RISK",
      "expectedBoundaryToken": "interop-risk",
      "expectedTop3Contains": "NativeChartHost.kt",
      "allowHighConfidence": false
    },
```

- [ ] **Step 4: Update corpus tests**

In `HandoffEvaluationCorpusTest.corpusHasStableV06Coverage`, add `"interop-risk-with-compose-host"` after `"interop-risk"`.

Add this test to `HandoffEvaluationCorpusTest`:

```kotlin
    @Test
    fun corpusInteropHostCaseRendersBoundaryContext() {
        val case = HandoffEvaluationFixtures.loadCorpus().cases.single { it.id == "interop-risk-with-compose-host" }
        val item = HandoffEvaluationFixtures.annotationFor(case)
        val session = SessionDto(
            sessionId = "handoff-boundary-context-eval",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(HandoffEvaluationFixtures.screenFor(case)),
            items = listOf(item.copy(sequenceNumber = 1)),
        )

        val compact = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)
        val precise = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)

        assertTrue(compact.contains("boundaryContext: tag=\"comp:NativeChartHost:chart\""), compact)
        assertTrue(precise.contains("- Boundary context: nearest Compose context tag=\"comp:NativeChartHost:chart\""), precise)
    }
```

- [ ] **Step 5: Run corpus and handoff eval tests**

Run:

```bash
npm run handoff:eval:test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 3**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt \
  fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json
git commit -m "test(handoff): cover interop host context"
```

## Task 4: Include Session Summary In SSE Session Updates

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventEmitters.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt`

- [ ] **Step 1: Add failing event payload test**

Add this test to `ConsoleEventsRoutesTest`:

```kotlin
    @Test
    fun sessionUpdatedEventsCarrySummaryPayloadForPushFirstSidebar() {
        val fixture = newConsoleSessionFixtureWithTempRoot(
            idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
        )
        val bus = ConsoleEventBus(clock = { 1L })
        val seen = LinkedBlockingQueue<ConsoleEvent>()
        bus.subscribe { event -> seen += event }
        val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
        try {
            server.start()
            val connection = URI("${server.url}/api/session/open").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty(CONSOLE_TOKEN_HEADER, server.consoleTokenForTests())
            connection.setRequestProperty("content-type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write("""{"newSession":true}""".toByteArray()) }
            assertEquals(200, connection.responseCode)

            val sessionsEvent = generateSequence { seen.poll(1, TimeUnit.SECONDS) }
                .first { it.name == "sessions-updated" }
            val summary = sessionsEvent.data.getValue("summary").jsonObject
            assertEquals("session-1", summary.getValue("sessionId").jsonPrimitive.content)
            assertEquals("active", summary.getValue("status").jsonPrimitive.content)
        } finally {
            server.stop()
            fixture.close()
        }
    }
```

- [ ] **Step 2: Run the event test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleEventsRoutesTest.sessionUpdatedEventsCarrySummaryPayloadForPushFirstSidebar" --no-daemon
```

Expected: FAIL with missing `summary`.

- [ ] **Step 3: Add summary payload to `sessions-updated`**

In `ConsoleEventEmitters.kt`, add this import:

```kotlin
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionSummary
```

Then update the `sessions-updated` emit block:

```kotlin
    emit(
        "sessions-updated",
        buildJsonObject {
            put("sessionId", session.sessionId)
            put(
                "summary",
                fixThisJson.encodeToJsonElement(
                    FeedbackSessionSummary.serializer(),
                    FeedbackSessionSummary.from(session),
                ).jsonObject,
            )
        },
    )
```

- [ ] **Step 4: Run the event test and verify it passes**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleEventsRoutesTest.sessionUpdatedEventsCarrySummaryPayloadForPushFirstSidebar" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit Task 4**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventEmitters.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt
git commit -m "feat(console): include session summary in SSE updates"
```

## Task 5: Apply Pushed Session Summaries Without Pull Refresh

**Files:**
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/events.js`
- Modify: `scripts/studioReliabilityContract-test.mjs`

- [ ] **Step 1: Add failing JS contract assertions**

In `scripts/studioReliabilityContract-test.mjs`, add this test:

```javascript
test('sessions-updated events apply pushed summary without pull refresh while SSE is healthy', () => {
  const events = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/events.js'), 'utf8');
  const history = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');

  assert.match(history, /function applySessionSummaryFromPayload\(summary\)/);
  assert.match(events, /if \(data\.summary\) \{/);
  assert.match(events, /applySessionSummaryFromPayload\(data\.summary\);/);
  assert.match(events, /refreshSessionsWhenEventsDisconnected\(\)\.catch\(showError\)/);
  assert.doesNotMatch(events, /on\('sessions-updated'[\s\S]*refreshSessions\(\)\.catch\(showError\)/);
});
```

- [ ] **Step 2: Run the JS contract test and verify it fails**

Run:

```bash
node --test scripts/studioReliabilityContract-test.mjs
```

Expected: FAIL because the helper does not exist and `sessions-updated` still calls `refreshSessions()`.

- [ ] **Step 3: Add pushed-summary upsert helper**

In `history.js`, add this function immediately after `renderSessionsListFromPayload`:

```javascript
            function applySessionSummaryFromPayload(summary) {
              if (!summary || !summary.sessionId) return state.sessionSummaries || [];
              const existing = state.sessionSummaries || [];
              const found = existing.some(session => session.sessionId === summary.sessionId);
              const next = found
                ? existing.map(session => session.sessionId === summary.sessionId ? summary : session)
                : [summary, ...existing];
              renderSessionsListFromPayload(next);
              return next;
            }
```

- [ ] **Step 4: Apply summaries from SSE without pull refresh**

In `events.js`, replace the current `sessions-updated` listener with:

```javascript
              on('sessions-updated', (data) => {
                if (data.summary) {
                  applySessionSummaryFromPayload(data.summary);
                  return;
                }
                if (data.sessions?.sessions) {
                  renderSessionsListFromPayload(data.sessions.sessions);
                  return;
                }
                refreshSessionsWhenEventsDisconnected().catch(showError);
              });
```

- [ ] **Step 5: Run the JS contract test**

Run:

```bash
node --test scripts/studioReliabilityContract-test.mjs
```

Expected: PASS.

- [ ] **Step 6: Commit Task 5**

```bash
git add fixthis-mcp/src/main/console/history.js \
  fixthis-mcp/src/main/console/events.js \
  scripts/studioReliabilityContract-test.mjs
git commit -m "feat(console): apply pushed session summaries"
```

## Task 6: Stop Session Polling While SSE Is Healthy

**Files:**
- Modify: `fixthis-mcp/src/main/console/sse.js`
- Modify: `fixthis-mcp/src/main/console/sessions-polling.js`
- Modify: `fixthis-mcp/src/main/console/events.js`
- Modify: `scripts/studioReliabilityContract-test.mjs`

- [ ] **Step 1: Add failing JS contract assertions**

In `scripts/studioReliabilityContract-test.mjs`, add this test:

```javascript
test('session polling is fallback-only while SSE is healthy', () => {
  const sse = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/sse.js'), 'utf8');
  const sessionsPolling = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/sessions-polling.js'), 'utf8');
  const events = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/events.js'), 'utf8');

  assert.match(sse, /function shouldUseSessionFallbackPolling\(\)/);
  assert.match(sessionsPolling, /shouldUseSessionFallbackPolling\(\)/);
  assert.match(events, /source\.onopen = \(\) => \{[\s\S]*stopSessionsPolling\(\);/);
  assert.match(events, /source\.onerror = \(\) => \{[\s\S]*startSessionsPolling\(\);/);
});
```

- [ ] **Step 2: Run the JS contract test and verify it fails**

Run:

```bash
node --test scripts/studioReliabilityContract-test.mjs
```

Expected: FAIL because session fallback state is not yet explicit.

- [ ] **Step 3: Add session fallback state to SSE helpers**

In `sse.js`, add this function after `shouldUsePreviewFallbackPolling()`:

```javascript
            function shouldUseSessionFallbackPolling() {
              return !consoleEventsConnected;
            }
```

- [ ] **Step 4: Gate session polling on SSE fallback**

In `sessions-polling.js`, update `shouldPollSessions()`:

```javascript
            function shouldPollSessions() {
              return shouldUseSessionFallbackPolling() &&
                !document.hidden &&
                pollingUseCases.getState().pendingMutationCount === 0 &&
                !isEditingAnnotation();
            }
```

- [ ] **Step 5: Stop and restart session polling from EventSource lifecycle**

In `events.js`, update `source.onopen` immediately after `stopLivePreviewPolling();`:

```javascript
                stopSessionsPolling();
                if (state.connection?.sessionsPollingPaused) setSessionsPollingPaused(false);
```

Update `source.onerror` after `startLivePreviewPolling();`:

```javascript
                startSessionsPolling();
```

- [ ] **Step 6: Run the JS contract test**

Run:

```bash
node --test scripts/studioReliabilityContract-test.mjs
```

Expected: PASS.

- [ ] **Step 7: Commit Task 6**

```bash
git add fixthis-mcp/src/main/console/sse.js \
  fixthis-mcp/src/main/console/sessions-polling.js \
  fixthis-mcp/src/main/console/events.js \
  scripts/studioReliabilityContract-test.mjs
git commit -m "feat(console): make session polling SSE fallback only"
```

## Task 7: Extend Browser Reliability Coverage

**Files:**
- Modify: `scripts/console-browser-reliability.mjs`

- [ ] **Step 1: Add failing request-log assertion**

Find the existing healthy-SSE preview proof in `scripts/console-browser-reliability.mjs`. Add these assertions after the preview update assertion and before fixture cleanup:

```javascript
const redundantSessionPulls = fixture.getRequestLog().filter((entry) =>
  entry.path === '/api/session' || entry.path === '/api/sessions'
);
assert.equal(
  redundantSessionPulls.length,
  0,
  `healthy SSE flow should not perform redundant session pulls, got ${JSON.stringify(redundantSessionPulls)}`,
);
```

If the script does not already expose `fixture.getRequestLog()`, reuse the pattern from `scripts/first-run-smoke.mjs`:

```javascript
const requestLog = fixture.getRequestLog();
```

- [ ] **Step 2: Run the browser reliability proof**

Run:

```bash
npm run console:browser:reliability
```

Expected before Tasks 4-6 are complete: FAIL if redundant session pulls still occur. Expected after Tasks 4-6: PASS.

- [ ] **Step 3: Commit Task 7**

```bash
git add scripts/console-browser-reliability.mjs
git commit -m "test(console): prove healthy SSE avoids session pulls"
```

## Task 8: Update Docs And Release Notes

**Files:**
- Modify: `docs/reference/feedback-console-contract.md`
- Modify: `docs/architecture/console-state-sync-design.md`
- Modify: `docs/product/roadmap.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Update feedback console contract**

In `docs/reference/feedback-console-contract.md`, update the live preview/session sync section with:

```markdown
- `sessions-updated` events carry a `summary` payload for the changed session
  when the server already has authoritative session state. The browser upserts
  that summary locally instead of fetching `/api/sessions` while EventSource is
  healthy.
- Interop boundary handoffs may include a `boundaryContext` line derived from
  nearby Compose semantics. This context helps locate a likely Compose host,
  but it is not exact AndroidView/WebView source ownership.
```

- [ ] **Step 2: Update console state sync architecture**

In `docs/architecture/console-state-sync-design.md`, update the residual polling gap so it says:

```markdown
1. **Fallback polling still exists, but is not steady state.** `/api/events`
   is the normal preview and session update path. Preview and session polling
   restart when EventSource is disconnected, unavailable, or explicitly
   recovering.
```

- [ ] **Step 3: Update roadmap**

In `docs/product/roadmap.md`, update the interop and SSE sections:

```markdown
Future interop work should continue toward richer subtree evidence, but V1 now
renders nearby Compose boundary context and keeps source candidates as context
for AndroidView/WebView-risk selections.
```

```markdown
Remaining SSE work is to keep observing fallback behavior and remove more
manual recovery code only after local evidence shows it is unused. Healthy
EventSource sessions no longer rely on automatic preview or session polling.
```

- [ ] **Step 4: Update changelog**

In `CHANGELOG.md` under `## Unreleased`, add:

```markdown
- Interop-risk handoffs now render nearby Compose boundary context so agents
  can inspect the likely host without treating it as exact AndroidView/WebView
  source ownership.
- The feedback console now applies pushed session summaries from `/api/events`
  and keeps session polling as an EventSource fallback instead of steady-state
  background traffic.
```

- [ ] **Step 5: Run docs checks**

Run:

```bash
bash scripts/check-docs-cli-surface.sh
node scripts/check-release-readiness.mjs
```

Expected: PASS.

- [ ] **Step 6: Commit Task 8**

```bash
git add docs/reference/feedback-console-contract.md \
  docs/architecture/console-state-sync-design.md \
  docs/product/roadmap.md \
  CHANGELOG.md
git commit -m "docs: update interop and SSE contracts"
```

## Task 9: Final Verification And Graph Refresh

**Files:**
- No source edits expected unless verification exposes a real issue.

- [ ] **Step 1: Run Track A focused verification**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests "*TargetBoundaryContextFormatterTest" \
  --tests "*CompactHandoffRendererTest" \
  --tests "*FeedbackQueueFormatterPhase2Test" \
  --tests "*HandoffEvaluationCorpusTest" \
  --no-daemon
npm run handoff:eval:test
```

Expected: PASS.

- [ ] **Step 2: Run Track B focused verification**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleEventsRoutesTest" --no-daemon
node --test scripts/studioReliabilityContract-test.mjs
npm run console:browser:reliability
```

Expected: PASS.

- [ ] **Step 3: Run fast broad verification**

Run:

```bash
npm run evidence:fast
git diff --check
```

Expected: PASS.

- [ ] **Step 4: Refresh Graphify**

Run:

```bash
graphify update .
```

Expected: command completes. Dirty `graphify-out/` files remain untracked/ignored and must not be committed.

- [ ] **Step 5: Confirm git status**

Run:

```bash
git status --short --branch
```

Expected: clean tracked worktree, with only ignored generated artifacts if Graphify or reports produced any.

- [ ] **Step 6: Commit verification fixes only if needed**

If verification required changes, commit them with a focused message:

```bash
git add <changed-source-or-doc-files>
git commit -m "fix: close interop console cleanup verification gaps"
```

Skip this commit if no source or doc changes were needed.

## Plan Self-Review

- Spec coverage: Track A context rendering, interop confidence preservation, compact/precise/full handoff behavior, corpus coverage, Track B summary-bearing SSE, fallback-only session polling, docs, release notes, and graph refresh are covered.
- Placeholder scan: no red-flag placeholder steps remain. Each implementation task has exact files, code snippets, commands, and expected outcomes.
- Type consistency: Kotlin snippets use existing `AnnotationDto`, `FixThisNode`, `TargetReliabilityWarning`, `FeedbackSessionSummary`, and console JS helper names. New helper names are introduced before later tasks reference them.
