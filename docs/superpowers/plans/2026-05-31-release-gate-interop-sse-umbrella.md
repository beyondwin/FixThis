# Release Gate, Interop Evidence, and SSE Closure Umbrella Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deepen AndroidView/WebView-risk evidence, close SSE fallback debt with proof, and package the result as one release-gate evidence report.

**Architecture:** Track B improves existing MCP/session interop evidence rendering without adding persisted breaking fields. Track C adds an explicit console reliability report around the existing SSE/fallback proof. Track A adds `npm run release:gate`, backed by an evidence-runner profile and release-readiness checks, so maintainers get one pass/deferred/fail artifact.

**Tech Stack:** Kotlin/JVM + kotlinx.serialization, Node.js 20 ESM (`node:test`), Playwright, Jetpack Compose sample/runtime fixtures, Markdown docs.

Design: [`docs/superpowers/specs/2026-05-31-release-gate-interop-sse-umbrella-design.md`](../specs/2026-05-31-release-gate-interop-sse-umbrella-design.md).

---

## Scope Check

The approved spec spans three tracks, but they are intentionally one umbrella:
Track A is the release evidence aggregator for Tracks B and C. Keep all tracks
in this one plan so the final release gate proves the code that just landed.

Implementation order:

1. Track B - interop boundary evidence depth.
2. Track C - SSE reliability reporting and fallback-only proof.
3. Track A - release gate aggregation, docs, and final verification.

## File Structure

### Track B - AndroidView / Interop Evidence Depth v2

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatter.kt`
  - Adds structured boundary-context rows with `host`, `ancestor`, and `context` kinds.
  - Keeps the output renderer-only; no persisted schema field is added.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatterTest.kt`
  - Locks host/ancestor/context classification, ordering, and non-interop no-op behavior.
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryGuidance.kt`
  - Strengthens interop guidance language so source candidates are verification hints.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryGuidanceTest.kt`
  - Locks the stronger guidance.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
  - Guards against interop handoffs looking like high-confidence exact ownership.
- Modify: `fixthis-mcp/src/main/console/presentation/annotationDetailView.js`
  - Mirrors host/ancestor/context row labels in the Evidence panel.
- Modify: `scripts/annotationDetailActions-test.mjs`
  - Locks the browser-side row labels and caveat.
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureModels.kt`
  - Adds runtime-only boundary-context observations for fixture reports.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustObservationMapperTest.kt`
  - Locks runtime boundary-context observation serialization.
- Modify: `scripts/source-matching-fixtures.mjs`
  - Adds runtime fixture expectations for boundary-context kinds.
- Modify: `scripts/source-matching-fixtures-test.mjs`
  - Locks manifest validation and classification for boundary-context observations.
- Modify: `fixtures/source-matching/manifest.json`
  - Requires the existing AndroidView interop fixture to observe a host/context boundary row.

### Track C - SSE State Sync Debt Closure

- Create: `scripts/console-reliability-report.mjs`
  - Pure report helpers for request counts, fallback reasons, and Markdown/JSON output.
- Create: `scripts/console-reliability-report-test.mjs`
  - Contract tests for the report helper.
- Modify: `scripts/console-browser-reliability.mjs`
  - Writes `build/reports/fixthis-console-reliability/report.json` and `.md`.
- Modify: `scripts/studioReliabilityContract-test.mjs`
  - Adds a text contract that fallback-only refresh remains behind `refreshSessionsWhenEventsDisconnected`.
- Modify: `scripts/console-tests.json`
  - Adds the new pure report test to the reliability group.

### Track A - Release Gate / Evidence Report Pack

- Create: `scripts/release-gate.mjs`
  - Runs or dry-runs the release gate, normalizes evidence statuses, writes JSON/Markdown.
- Create: `scripts/release-gate-test.mjs`
  - Contract tests for pass/deferred/fail normalization and report rendering.
- Modify: `scripts/evidence-runner.mjs`
  - Adds a `gate` profile containing Track B, Track C, and release-readiness evidence commands.
- Modify: `scripts/evidence-runner-test.mjs`
  - Locks the new `gate` profile.
- Modify: `package.json`
  - Adds `release:gate` and `release:gate:test`.
- Modify: `scripts/check-release-readiness.mjs`
  - Adds rules requiring the release-gate command and readiness row.
- Modify: `docs/contributing/release-readiness.md`
  - Adds the Release Gate / Interop / SSE evidence manifest.
- Modify: `docs/releases/unreleased.md`
  - Adds a concise unreleased note once the evidence is implemented.
- Modify: `CHANGELOG.md`
  - Adds user-visible changes after the final verification passes.

---

## Task 1: Classify Interop Boundary Context Rows

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatterTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatter.kt`

- [x] **Step 1: Write failing tests for host, ancestor, and context rows**

Append these tests inside `TargetBoundaryContextFormatterTest`, before the
private helper functions:

```kotlin
    @Test
    fun compactLineLabelsOverlappingCompNodeAsBoundaryHost() {
        val item = interopAreaItem(
            nearbyNodes = listOf(
                node(
                    uid = "host",
                    testTag = "comp:NativeChartHost:chart",
                    role = "Image",
                    bounds = FixThisRect(20f, 100f, 260f, 240f),
                ),
            ),
        )

        val line = TargetBoundaryContextFormatter.compactLine(item)

        assertEquals(
            "boundaryHost: tag=\"comp:NativeChartHost:chart\"; role=Image; box=(20.0,100.0)-(260.0,240.0)",
            line,
        )
    }

    @Test
    fun preciseLinesClassifyBoundaryHostAncestorAndContext() {
        val item = interopAreaItem(
            nearbyNodes = listOf(
                node(
                    uid = "root",
                    testTag = "comp:DiagnosticsScreen:root",
                    bounds = FixThisRect(0f, 0f, 400f, 800f),
                ),
                node(
                    uid = "host",
                    testTag = "comp:NativeChartHost:chart",
                    role = "Image",
                    bounds = FixThisRect(24f, 112f, 360f, 260f),
                ),
                node(
                    uid = "label",
                    text = listOf("Native chart"),
                    bounds = FixThisRect(24f, 280f, 220f, 312f),
                ),
            ),
        )

        val lines = TargetBoundaryContextFormatter.preciseLines(item)

        assertTrue(
            lines.any { it.contains("Boundary host: tag=\"comp:NativeChartHost:chart\"; role=Image") },
            lines.joinToString("\n"),
        )
        assertTrue(
            lines.any { it.contains("Boundary ancestor: tag=\"comp:DiagnosticsScreen:root\"") },
            lines.joinToString("\n"),
        )
        assertTrue(
            lines.any { it.contains("Boundary context: text=\"Native chart\"") },
            lines.joinToString("\n"),
        )
    }
```

- [x] **Step 2: Run the focused tests to verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --no-daemon
```

Expected: FAIL. The current compact line starts with `boundaryContext:` and
precise rows do not label `Boundary host` or `Boundary ancestor`.

- [x] **Step 3: Implement structured boundary-context rows**

Replace `TargetBoundaryContextFormatter.kt` with this implementation:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning

internal object TargetBoundaryContextFormatter {
    private const val MAX_CONTEXT_NODES = 3

    internal data class BoundaryContextRow(
        val kind: BoundaryContextKind,
        val node: FixThisNode,
        val summary: String,
    )

    internal enum class BoundaryContextKind(
        val compactToken: String,
        val preciseLabel: String,
        val sortRank: Int,
    ) {
        HOST("boundaryHost", "Boundary host", 0),
        ANCESTOR("boundaryAncestor", "Boundary ancestor", 1),
        CONTEXT("boundaryContext", "Boundary context", 2),
    }

    fun compactLine(item: AnnotationDto): String? = item.boundaryContextRows().firstOrNull()
        ?.let { row -> "${row.kind.compactToken}: ${row.summary}; box=${row.node.boundsInWindow.formatBox()}" }

    fun preciseLines(item: AnnotationDto): List<String> {
        val rows = item.boundaryContextRows()
        if (rows.isEmpty()) return emptyList()
        return buildList {
            rows.forEach { row ->
                add(
                    "- ${row.kind.preciseLabel}: ${row.summary}; " +
                        "box=`${row.node.boundsInWindow.formatBounds()}`.",
                )
            }
            add(
                "- Boundary context note: this context helps locate the host; it does not prove Compose owns the selected pixels.",
            )
        }
    }

    internal fun structuredRows(item: AnnotationDto): List<BoundaryContextRow> = item.boundaryContextRows()

    private fun AnnotationDto.boundaryContextRows(): List<BoundaryContextRow> {
        if (!hasInteropBoundary()) return emptyList()
        val targetBounds = target.bounds()
        return nearbyNodes
            .asSequence()
            .filter { node -> node.hasSafeContextSignal() }
            .mapNotNull { node ->
                val summary = node.safeSummaryParts().joinToString("; ").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                BoundaryContextRow(
                    kind = node.boundaryContextKind(targetBounds),
                    node = node,
                    summary = summary,
                )
            }
            .sortedWith(
                compareBy<BoundaryContextRow> { it.kind.sortRank }
                    .thenByDescending { it.node.testTag?.startsWith("comp:") == true }
                    .thenBy { it.node.boundsInWindow.area },
            )
            .take(MAX_CONTEXT_NODES)
            .toList()
    }

    private fun AnnotationDto.hasInteropBoundary(): Boolean =
        targetReliability?.warnings.orEmpty().contains(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP)

    private fun AnnotationTargetDto.bounds(): FixThisRect = when (this) {
        is AnnotationTargetDto.Area -> boundsInWindow
        is AnnotationTargetDto.Node -> boundsInWindow
    }

    private fun FixThisNode.boundaryContextKind(targetBounds: FixThisRect): BoundaryContextKind {
        val compTagged = testTag?.startsWith("comp:") == true
        val containsTarget = boundsInWindow.contains(targetBounds)
        val muchLargerThanTarget = boundsInWindow.area > targetBounds.area * 6f
        return when {
            compTagged && containsTarget && muchLargerThanTarget -> BoundaryContextKind.ANCESTOR
            compTagged && boundsInWindow.intersects(targetBounds) -> BoundaryContextKind.HOST
            containsTarget && boundsInWindow.area > targetBounds.area -> BoundaryContextKind.ANCESTOR
            else -> BoundaryContextKind.CONTEXT
        }
    }

    private fun FixThisRect.contains(other: FixThisRect): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

    private fun FixThisRect.intersects(other: FixThisRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    private fun FixThisNode.hasSafeContextSignal(): Boolean = !testTag.isNullOrBlank() ||
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

- [x] **Step 4: Run the focused tests to verify pass**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --no-daemon
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatter.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatterTest.kt
git commit -m "feat(mcp): classify interop boundary context rows"
```

---

## Task 2: Strengthen Interop Handoff Guidance

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryGuidanceTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryGuidance.kt`

- [x] **Step 1: Write failing guidance assertions**

In `TargetBoundaryGuidanceTest.interopWarningProducesInteropBoundaryGuidance`,
add these assertions after the existing precise-line checks:

```kotlin
        assertTrue(
            guidance.preciseLines.contains(
                "- Boundary source rule: source candidates are verification hints, not exact ownership.",
            ),
        )
        assertTrue(
            guidance.preciseLines.contains(
                "- Boundary action: inspect-and-corroborate the Compose host first; verify native View/WebView ownership before editing.",
            ),
        )
```

In `CompactHandoffRendererTest.compactHandoffRendersInteropBoundaryContextFromNearbyComposeHost`,
replace the existing `boundaryContext:` assertion with this `boundaryHost:`
assertion, then add the `inspect-source-first` guard:

```kotlin
        assertTrue(markdown.contains("boundaryHost: tag=\"comp:NativeChartHost:chart\"; role=Image"), markdown)
        assertTrue(!markdown.contains("targetAction=inspect-source-first"), markdown)
```

- [x] **Step 2: Run focused tests to verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*TargetBoundaryGuidanceTest" --tests "*CompactHandoffRendererTest.compactHandoffRendersInteropBoundaryContextFromNearbyComposeHost" --no-daemon
```

Expected: FAIL. The new source-rule line is missing, and the compact boundary
line still says `boundaryContext`.

- [x] **Step 3: Update interop guidance text**

In `TargetBoundaryGuidance.kt`, replace the `POSSIBLE_VIEW_INTEROP` branch with:

```kotlin
                TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP in warnings -> TargetBoundaryGuidance(
                    compactToken = "interop-risk",
                    preciseLines = listOf(
                        "- Boundary: possible AndroidView/WebView target; source candidates are context only.",
                        "- Boundary source rule: source candidates are verification hints, not exact ownership.",
                        "- Boundary action: inspect-and-corroborate the Compose host first; verify native View/WebView ownership before editing.",
                    ),
                )
```

- [x] **Step 4: Run focused tests to verify pass**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*TargetBoundaryGuidanceTest" --tests "*CompactHandoffRendererTest.compactHandoffRendersInteropBoundaryContextFromNearbyComposeHost" --no-daemon
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryGuidance.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryGuidanceTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt
git commit -m "test(mcp): harden interop handoff guidance"
```

---

## Task 3: Mirror Boundary Row Kinds In The Console Evidence Panel

**Files:**
- Modify: `scripts/annotationDetailActions-test.mjs`
- Modify: `fixthis-mcp/src/main/console/presentation/annotationDetailView.js`

- [x] **Step 1: Write failing browser-side row-kind tests**

Append this test to `scripts/annotationDetailActions-test.mjs` after
`three interop boundary nodes produce three rows plus exactly one caveat`:

```js
test('interop boundary rows label host ancestor and context distinctly', () => {
  const interopBoundaryContextRows = buildInteropRowsFn();
  const item = {
    target: { type: 'visual_area', boundsInWindow: { left: 40, top: 120, right: 220, bottom: 220 } },
    targetReliability: { warnings: ['possible_view_interop'] },
    nearbyNodes: [
      { testTag: 'comp:DiagnosticsScreen:root', boundsInWindow: { left: 0, top: 0, right: 400, bottom: 800 } },
      { testTag: 'comp:NativeChartHost:chart', role: 'Image', boundsInWindow: { left: 24, top: 112, right: 360, bottom: 260 } },
      { text: ['Native chart'], boundsInWindow: { left: 24, top: 280, right: 220, bottom: 312 } },
    ],
  };

  const rows = interopBoundaryContextRows(item);

  assert.equal(rows[0][0], 'Boundary host');
  assert.equal(rows[1][0], 'Boundary ancestor');
  assert.equal(rows[2][0], 'Boundary context');
  assert.equal(rows[3][0], 'Boundary context note');
});
```

Update `buildInteropRowsFn()` so the generated function body includes the new
helpers required by the production function:

```js
  const targetBounds = 'function targetBoundsForBoundary(item)' +
    '{' + functionBody(detailSource, 'function targetBoundsForBoundary(item)') + '}';
  const intersects = 'function boundsIntersect(a, b)' +
    '{' + functionBody(detailSource, 'function boundsIntersect(a, b)') + '}';
  const contains = 'function boundsContain(a, b)' +
    '{' + functionBody(detailSource, 'function boundsContain(a, b)') + '}';
  const kind = 'function boundaryContextKind(item, node)' +
    '{' + functionBody(detailSource, 'function boundaryContextKind(item, node)') + '}';
  const label = 'function boundaryContextLabel(kind, index)' +
    '{' + functionBody(detailSource, 'function boundaryContextLabel(kind, index)') + '}';
```

Then update the `new Function` body to concatenate those helpers before `rows`:

```js
    constant + isInterop + targetBounds + intersects + contains + kind + label + summary + rows + 'return interopBoundaryContextRows;',
```

Update the existing `three interop boundary nodes produce three rows plus exactly one caveat`
test so it counts all boundary rows instead of only numbered context rows:

```js
  const boundaryRows = rows.filter(row => /^Boundary (host|ancestor|context)( \d+)?$/.test(row[0]));
  const caveatRows = rows.filter(row => row[1].includes('does not prove Compose owns the selected pixels'));
  assert.equal(boundaryRows.length, 3);
  assert.equal(caveatRows.length, 1);
```

- [x] **Step 2: Run the JS test to verify failure**

Run:

```bash
node --test scripts/annotationDetailActions-test.mjs
```

Expected: FAIL because `targetBoundsForBoundary`, `boundaryContextKind`, and
`boundaryContextLabel` are not defined.

- [x] **Step 3: Implement row-kind rendering in the console**

In `annotationDetailView.js`, insert these helpers after `isInteropRiskItem`:

```js
            function targetBoundsForBoundary(item) {
              const target = item?.target || {};
              return target.boundsInWindow || target.bounds || item?.bounds || null;
            }

            function boundsIntersect(a, b) {
              if (!a || !b) return false;
              return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
            }

            function boundsContain(a, b) {
              if (!a || !b) return false;
              return a.left <= b.left && a.top <= b.top && a.right >= b.right && a.bottom >= b.bottom;
            }

            function boundaryContextKind(item, node) {
              const bounds = node?.boundsInWindow;
              const targetBounds = targetBoundsForBoundary(item);
              const tag = String(node?.testTag || '').trim();
              const compTagged = tag.startsWith('comp:');
              const containsTarget = boundsContain(bounds, targetBounds);
              const boundsArea = Math.max(0, (bounds?.right || 0) - (bounds?.left || 0)) *
                Math.max(0, (bounds?.bottom || 0) - (bounds?.top || 0));
              const targetArea = Math.max(0, (targetBounds?.right || 0) - (targetBounds?.left || 0)) *
                Math.max(0, (targetBounds?.bottom || 0) - (targetBounds?.top || 0));
              const muchLargerThanTarget = targetArea > 0 && boundsArea > targetArea * 6;
              if (compTagged && containsTarget && muchLargerThanTarget) return 'ancestor';
              if (compTagged && boundsIntersect(bounds, targetBounds)) return 'host';
              if (containsTarget) return 'ancestor';
              return 'context';
            }

            function boundaryContextLabel(kind, index) {
              if (kind === 'host') return 'Boundary host';
              if (kind === 'ancestor') return 'Boundary ancestor';
              return 'Boundary context';
            }
```

Then replace `interopBoundaryContextRows(item)` with:

```js
            function interopBoundaryContextRows(item) {
              if (!isInteropRiskItem(item)) return [];
              const nodes = (item?.nearbyNodes || [])
                .map(node => ({
                  node: node,
                  summary: boundaryContextNodeSummary(node),
                  kind: boundaryContextKind(item, node),
                }))
                .filter(entry => entry.summary)
                .sort((a, b) => {
                  const rank = { host: 0, ancestor: 1, context: 2 };
                  const byKind = (rank[a.kind] ?? 2) - (rank[b.kind] ?? 2);
                  if (byKind !== 0) return byKind;
                  const aArea = Math.max(0, (a.node?.boundsInWindow?.right || 0) - (a.node?.boundsInWindow?.left || 0)) *
                    Math.max(0, (a.node?.boundsInWindow?.bottom || 0) - (a.node?.boundsInWindow?.top || 0));
                  const bArea = Math.max(0, (b.node?.boundsInWindow?.right || 0) - (b.node?.boundsInWindow?.left || 0)) *
                    Math.max(0, (b.node?.boundsInWindow?.bottom || 0) - (b.node?.boundsInWindow?.top || 0));
                  return aArea - bArea;
                })
                .slice(0, INTEROP_BOUNDARY_CONTEXT_LIMIT);
              if (!nodes.length) return [];
              const rows = nodes.map((entry, index) => {
                const bounds = entry.node?.boundsInWindow;
                const boundsLabel = bounds ? ' · ' + formatBounds(bounds) : '';
                return [boundaryContextLabel(entry.kind, index), entry.summary + boundsLabel];
              });
              rows.push(['Boundary context note', 'helps locate the host; it does not prove Compose owns the selected pixels.']);
              return rows;
            }
```

- [x] **Step 4: Run JS tests to verify pass**

Run:

```bash
node --test scripts/annotationDetailActions-test.mjs
```

Expected: PASS.

- [x] **Step 5: Rebuild console assets and run a focused Kotlin route test**

Run:

```bash
FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests "*ConsoleConnectionRoutesTest" --no-daemon
```

Expected: PASS. If `build-console-assets.mjs` changes
`fixthis-mcp/src/main/resources/console/app.js`, include it in the commit.

- [x] **Step 6: Commit**

```bash
git add scripts/annotationDetailActions-test.mjs \
  fixthis-mcp/src/main/console/presentation/annotationDetailView.js \
  fixthis-mcp/src/main/resources/console/app.js
git commit -m "feat(console): label interop boundary context rows"
```

---

## Task 4: Add Runtime Boundary-Context Observations

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureModels.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustObservationMapperTest.kt`
- Modify: `scripts/source-matching-fixtures.mjs`
- Modify: `scripts/source-matching-fixtures-test.mjs`
- Modify: `fixtures/source-matching/manifest.json`

- [x] **Step 1: Write failing mapper test**

Append this test to `RuntimeTrustObservationMapperTest`:

```kotlin
    @Test
    fun `interop observation includes boundary context kinds`() {
        val observed = RuntimeTrustObservationMapper.fromAnnotation(
            AnnotationDto(
                itemId = "interop",
                screenId = "screen",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(40f, 120f, 220f, 220f)),
                nearbyNodes = listOf(
                    FixThisNode(
                        uid = "host",
                        composeNodeId = 1,
                        rootIndex = 0,
                        treeKind = TreeKind.MERGED,
                        boundsInWindow = FixThisRect(24f, 112f, 360f, 260f),
                        testTag = "comp:NativeChartHost:chart",
                        role = "Image",
                    ),
                ),
                comment = "Fix the native chart",
                targetReliability = TargetReliability(
                    confidence = TargetConfidence.LOW,
                    warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
                ),
            ),
        )

        assertEquals("host", observed.boundaryContext?.single()?.kind)
        assertTrue(observed.boundaryContext?.single()?.summary.orEmpty().contains("NativeChartHost"))
    }
```

Add imports if they are missing:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.session.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.AnnotationTargetDto
```

- [x] **Step 2: Run mapper test to verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*RuntimeTrustObservationMapperTest" --no-daemon
```

Expected: FAIL because `RuntimeTrustObserved.boundaryContext` does not exist.

- [x] **Step 3: Implement runtime observation model**

In `RuntimeTrustFixtureModels.kt`, add this serializable data class after
`RuntimeTrustObserved`:

```kotlin
@Serializable
data class RuntimeTrustBoundaryContext(
    val kind: String,
    val summary: String,
)
```

Add a property to `RuntimeTrustObserved`:

```kotlin
    val boundaryContext: List<RuntimeTrustBoundaryContext>? = null,
```

Then add this argument to `RuntimeTrustObserved(...)` in
`RuntimeTrustObservationMapper.fromAnnotation`:

```kotlin
            boundaryContext = TargetBoundaryContextFormatter.structuredRows(item).map { row ->
                RuntimeTrustBoundaryContext(
                    kind = row.kind.name.lowercase(),
                    summary = row.summary,
                )
            }.takeIf { it.isNotEmpty() },
```

Add the import:

```kotlin
import io.github.beyondwin.fixthis.mcp.session.TargetBoundaryContextFormatter
```

- [x] **Step 4: Run mapper test to verify pass**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*RuntimeTrustObservationMapperTest" --no-daemon
```

Expected: PASS.

- [x] **Step 5: Write failing fixture-lab expectation tests**

In `scripts/source-matching-fixtures-test.mjs`, add this test near the runtime
trust classification tests:

```js
test("runtime trust classification verifies boundary context kinds", () => {
  const outcome = classifyRuntimeTrustOutcome(
    {
      id: "interop",
      mode: "runtime-trust",
      expectedBoundaryContextKinds: ["host"],
    },
    {
      confidence: "low",
      warnings: ["POSSIBLE_VIEW_INTEROP"],
      boundaryContext: [{ kind: "host", summary: "tag=\"comp:NativeChartHost:chart\"" }],
    },
  );

  assert.deepEqual(outcome.failures, []);
  assert.ok(outcome.metrics.includes("boundary_context_kind_present"));
});

test("runtime trust classification fails missing boundary context kinds", () => {
  const outcome = classifyRuntimeTrustOutcome(
    {
      id: "interop",
      mode: "runtime-trust",
      expectedBoundaryContextKinds: ["host"],
    },
    {
      confidence: "low",
      warnings: ["POSSIBLE_VIEW_INTEROP"],
      boundaryContext: [{ kind: "context", summary: "text=\"Native chart\"" }],
    },
  );

  assert.ok(outcome.failures.includes("missing_boundary_context_kind"));
});
```

Also add a manifest validation test:

```js
test("validateManifest accepts expected runtime boundary context kinds", () => {
  const errors = validateManifest({
    fixtures: [{
      id: "local",
      projectDir: "sample",
      packageName: "io.github.beyondwin.fixthis.sample",
      cases: [{
        id: "interop",
        mode: "runtime-trust",
        runtimeTarget: { visualArea: { left: 1, top: 1, right: 2, bottom: 2 } },
        expectedBoundaryContextKinds: ["host", "ancestor", "context"],
      }],
    }],
  });

  assert.deepEqual(errors, []);
});
```

- [x] **Step 6: Run fixture-lab tests to verify failure**

Run:

```bash
node --test scripts/source-matching-fixtures-test.mjs
```

Expected: FAIL because `expectedBoundaryContextKinds` is not supported.

- [x] **Step 7: Implement fixture-lab boundary-context checks**

In `scripts/source-matching-fixtures.mjs`, add the field name to
`runtimeTrustCaseFields`:

```js
  "expectedBoundaryContextKinds",
```

Add this validation after the existing warning validation:

```js
        validateAllowedValues(
          entry.expectedBoundaryContextKinds,
          ["host", "ancestor", "context"],
          `${entry.id || "case"} expectedBoundaryContextKinds`,
          errors,
        );
```

In `classifyRuntimeTrustOutcome`, before `return outcome;`, add:

```js
  if ((expectation.expectedBoundaryContextKinds || []).length > 0) {
    if (!hasOwn(observed, "boundaryContext")) {
      addUnique(outcome.failures, "missing_boundary_context_observation");
    } else {
      const observedKinds = new Set((observed.boundaryContext || []).map((entry) => entry && entry.kind).filter(Boolean));
      for (const kind of expectation.expectedBoundaryContextKinds || []) {
        if (observedKinds.has(kind)) {
          addUnique(outcome.metrics, "boundary_context_kind_present");
        } else {
          addUnique(outcome.failures, "missing_boundary_context_kind");
        }
      }
    }
  }
```

- [x] **Step 8: Pin the existing interop runtime fixture**

In `fixtures/source-matching/manifest.json`, update the
`fixthis-sample-diagnostics-androidview-interop` case by adding:

```json
          "expectedBoundaryContextKinds": ["host"],
```

Keep the existing `mustWarn` and `mustNotHighConfidence` fields.

- [x] **Step 9: Run tests to verify pass**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*RuntimeTrustObservationMapperTest" --no-daemon
node --test scripts/source-matching-fixtures-test.mjs
```

Expected: PASS.

- [x] **Step 10: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustFixtureModels.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixture/RuntimeTrustObservationMapperTest.kt \
  scripts/source-matching-fixtures.mjs \
  scripts/source-matching-fixtures-test.mjs \
  fixtures/source-matching/manifest.json
git commit -m "test(trust): record interop boundary context observations"
```

---

## Task 5: Add Console Reliability Report Helpers

**Files:**
- Create: `scripts/console-reliability-report.mjs`
- Create: `scripts/console-reliability-report-test.mjs`
- Modify: `scripts/console-tests.json`

- [x] **Step 1: Write the failing pure helper tests**

Create `scripts/console-reliability-report-test.mjs`:

```js
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  buildConsoleReliabilityReport,
  renderConsoleReliabilityMarkdown,
  summarizeRequests,
  writeConsoleReliabilityReports,
} from './console-reliability-report.mjs';

test('summarizeRequests counts fallback poll endpoints after marker time', () => {
  const summary = summarizeRequests([
    { method: 'GET', path: '/api/session', time: 1 },
    { method: 'GET', path: '/api/sessions', time: 10 },
    { method: 'GET', path: '/api/preview', time: 11 },
    { method: 'GET', path: '/api/preview/preview-1/screenshot/full', time: 12 },
    { method: 'GET', path: '/api/events', time: 13 },
  ], { since: 5 });

  assert.equal(summary.sessionPolls, 1);
  assert.equal(summary.previewPolls, 1);
  assert.equal(summary.eventConnections, 1);
});

test('buildConsoleReliabilityReport fails healthy SSE polling regressions', () => {
  const report = buildConsoleReliabilityReport({
    observations: [{
      name: 'healthy-sse',
      eventSourceConnected: true,
      requestSummary: { sessionPolls: 1, previewPolls: 0, eventConnections: 1 },
      fallbackReasons: [],
    }],
  });

  assert.equal(report.status, 'fail');
  assert.equal(report.observations[0].status, 'fail');
});

test('buildConsoleReliabilityReport passes explicit fallback observations', () => {
  const report = buildConsoleReliabilityReport({
    observations: [{
      name: 'sse-disconnect',
      eventSourceConnected: false,
      requestSummary: { sessionPolls: 2, previewPolls: 1, eventConnections: 2 },
      fallbackReasons: ['eventsource-disconnected'],
    }],
  });

  assert.equal(report.status, 'pass');
  assert.equal(report.observations[0].status, 'pass');
});

test('markdown report includes request counts and fallback reasons', () => {
  const text = renderConsoleReliabilityMarkdown({
    schemaVersion: '1.0',
    status: 'pass',
    generatedAt: '2026-05-31T00:00:00.000Z',
    observations: [{
      name: 'healthy-sse',
      status: 'pass',
      eventSourceConnected: true,
      requestSummary: { sessionPolls: 0, previewPolls: 0, eventConnections: 1 },
      fallbackReasons: [],
    }],
  });

  assert.match(text, /# FixThis Console Reliability Report/);
  assert.match(text, /\| healthy-sse \| pass \| true \| 0 \| 0 \| 1 \| - \|/);
});

test('writeConsoleReliabilityReports writes JSON and Markdown', () => {
  const root = mkdtempSync(join(tmpdir(), 'fixthis-console-reliability-'));
  try {
    const paths = writeConsoleReliabilityReports({
      schemaVersion: '1.0',
      status: 'pass',
      generatedAt: '2026-05-31T00:00:00.000Z',
      observations: [],
    }, root);
    assert.match(readFileSync(paths.json, 'utf8'), /"status": "pass"/);
    assert.match(readFileSync(paths.markdown, 'utf8'), /FixThis Console Reliability Report/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
```

- [x] **Step 2: Run the pure helper tests to verify failure**

Run:

```bash
node --test scripts/console-reliability-report-test.mjs
```

Expected: FAIL with `Cannot find module` for
`scripts/console-reliability-report.mjs`.

- [x] **Step 3: Implement the report helper**

Create `scripts/console-reliability-report.mjs`:

```js
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..');
export const defaultConsoleReliabilityReportDir = join(repoRoot, 'build/reports/fixthis-console-reliability');

export function summarizeRequests(requests, { since = 0 } = {}) {
  const relevant = (requests || []).filter((entry) => Number(entry.time || 0) >= since);
  return {
    sessionPolls: relevant.filter((entry) =>
      entry.method === 'GET' && (entry.path === '/api/session' || entry.path === '/api/sessions')
    ).length,
    previewPolls: relevant.filter((entry) =>
      entry.method === 'GET' && entry.path === '/api/preview'
    ).length,
    eventConnections: relevant.filter((entry) =>
      entry.method === 'GET' && entry.path === '/api/events'
    ).length,
  };
}

function observationStatus(observation) {
  const summary = observation.requestSummary || {};
  const fallbackReasons = observation.fallbackReasons || [];
  const hasPolling = Number(summary.sessionPolls || 0) > 0 || Number(summary.previewPolls || 0) > 0;
  if (observation.eventSourceConnected && hasPolling && fallbackReasons.length === 0) return 'fail';
  return 'pass';
}

export function buildConsoleReliabilityReport({
  observations,
  generatedAt = new Date().toISOString(),
} = {}) {
  const normalized = (observations || []).map((observation) => ({
    ...observation,
    status: observationStatus(observation),
  }));
  return {
    schemaVersion: '1.0',
    status: normalized.some((observation) => observation.status === 'fail') ? 'fail' : 'pass',
    generatedAt,
    observations: normalized,
  };
}

function cell(value) {
  if (value === null || value === undefined || value === '') return '-';
  if (Array.isArray(value)) return value.length ? value.join(', ') : '-';
  return String(value).replaceAll('|', '\\|');
}

export function renderConsoleReliabilityMarkdown(report) {
  const lines = [
    '# FixThis Console Reliability Report',
    '',
    `- Status: ${report.status}`,
    `- Generated: ${report.generatedAt}`,
    '',
    '| Observation | Status | EventSource connected | Session polls | Preview polls | Event connections | Fallback reasons |',
    '| --- | --- | --- | --- | --- | --- | --- |',
  ];
  for (const observation of report.observations || []) {
    const summary = observation.requestSummary || {};
    lines.push([
      cell(observation.name),
      cell(observation.status),
      cell(observation.eventSourceConnected),
      cell(summary.sessionPolls || 0),
      cell(summary.previewPolls || 0),
      cell(summary.eventConnections || 0),
      cell(observation.fallbackReasons || []),
    ].join(' | ').replace(/^/, '| ').replace(/$/, ' |'));
  }
  return `${lines.join('\n')}\n`;
}

export function writeConsoleReliabilityReports(report, reportDir = defaultConsoleReliabilityReportDir) {
  mkdirSync(reportDir, { recursive: true });
  const json = join(reportDir, 'report.json');
  const markdown = join(reportDir, 'report.md');
  writeFileSync(json, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdown, renderConsoleReliabilityMarkdown(report));
  return { json, markdown };
}
```

- [x] **Step 4: Register the test in the reliability group**

In `scripts/console-tests.json`, add
`"scripts/console-reliability-report-test.mjs"` to the `reliability` array.

- [x] **Step 5: Run tests to verify pass**

Run:

```bash
node --test scripts/console-reliability-report-test.mjs
npm run console:reliability:test
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add scripts/console-reliability-report.mjs \
  scripts/console-reliability-report-test.mjs \
  scripts/console-tests.json
git commit -m "test(console): add reliability report contracts"
```

---

## Task 6: Write Console Reliability Reports From Browser Proof

**Files:**
- Modify: `scripts/console-browser-reliability.mjs`
- Modify: `scripts/studioReliabilityContract-test.mjs`

- [x] **Step 1: Add a text contract for fallback-only refresh**

Append this test to `scripts/studioReliabilityContract-test.mjs`:

```js
test('refreshSessionsWhenEventsDisconnected keeps pull refresh behind the SSE gate', () => {
  const history = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');
  const helper = body(history, 'async function refreshSessionsWhenEventsDisconnected()');

  assert.match(helper, /isConsoleEventsConnected\(\) \|\| wasConsoleEventsRecentlyConnected\(\)/);
  assert.match(helper, /return state\.sessionSummaries \|\| \[\];/);
  assert.match(helper, /return refreshSessions\(\);/);
});
```

- [x] **Step 2: Run the contract test to verify current state**

Run:

```bash
node --test scripts/studioReliabilityContract-test.mjs
```

Expected: PASS if the SSE gate is already present. If this fails, update
`history.js` so the helper keeps both the currently-connected and
recently-connected guards:

```js
            async function refreshSessionsWhenEventsDisconnected() {
              if (isConsoleEventsConnected() || wasConsoleEventsRecentlyConnected()) return state.sessionSummaries || [];
              return refreshSessions();
            }
```

- [x] **Step 3: Wire report helpers into browser reliability**

At the top of `scripts/console-browser-reliability.mjs`, add:

```js
import {
  buildConsoleReliabilityReport,
  summarizeRequests,
  writeConsoleReliabilityReports,
} from './console-reliability-report.mjs';
```

After `waitUntil`, add:

```js
const reliabilityObservations = [];

function recordReliabilityObservation(observation) {
  reliabilityObservations.push(observation);
}
```

In `testSsePreviewPushDoesNotPollPreview`, after the two `assert.equal(...)`
checks, add:

```js
    recordReliabilityObservation({
      name: 'sse-preview-push',
      eventSourceConnected: true,
      requestSummary: summarizeRequests(fixture.getRequestLog(), { since: Date.now() - 2000 }),
      fallbackReasons: [],
    });
```

In `testSaveToMcpDoesNotPullSessionsWhenSseIsConnected`, after the final
assertion, add:

```js
    recordReliabilityObservation({
      name: 'save-to-mcp-healthy-sse',
      eventSourceConnected: true,
      requestSummary: summarizeRequests(fixture.getRequestLog(), { since: Date.now() - 2000 }),
      fallbackReasons: [],
    });
```

In `testEventSourceReconnectRecovery`, after the visible-item assertion, add:

```js
    recordReliabilityObservation({
      name: 'eventsource-reconnect',
      eventSourceConnected: true,
      requestSummary: summarizeRequests(fixture.getRequestLog()),
      fallbackReasons: ['eventsource-disconnected'],
    });
```

In `assertNoSessionPollingUnderHealthySse`, after the visibilitychange
assertion, add:

```js
  recordReliabilityObservation({
    name: 'healthy-sse-steady-state',
    eventSourceConnected: true,
    requestSummary: {
      sessionPolls: sessionPollCount,
      previewPolls: previewPollCount,
      eventConnections: 1,
    },
    fallbackReasons: [],
  });
```

At the end of `run()`, before `console.log('PASS console browser reliability proof');`, add:

```js
  const report = buildConsoleReliabilityReport({ observations: reliabilityObservations });
  const paths = writeConsoleReliabilityReports(report);
  console.log(`Console reliability report: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  if (report.status !== 'pass') {
    throw new Error(`console reliability report failed: ${paths.json}`);
  }
```

- [x] **Step 4: Run browser reliability**

Run:

```bash
npm run console:browser:reliability
```

Expected: PASS and output paths under
`build/reports/fixthis-console-reliability/`. Do not commit the generated
report files.

- [x] **Step 5: Commit**

```bash
git add scripts/console-browser-reliability.mjs scripts/studioReliabilityContract-test.mjs
git commit -m "test(console): report SSE reliability evidence"
```

---

## Task 7: Add Release Gate Contracts

**Files:**
- Create: `scripts/release-gate-test.mjs`
- Create: `scripts/release-gate.mjs`

- [x] **Step 1: Write failing release gate tests**

Create `scripts/release-gate-test.mjs`:

```js
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  buildReleaseGateReport,
  normalizeEvidenceStep,
  renderReleaseGateMarkdown,
  releaseGateSteps,
  runReleaseGate,
  writeReleaseGateReports,
} from './release-gate.mjs';

test('releaseGateSteps include release reality interop SSE docs and whitespace evidence', () => {
  const commands = releaseGateSteps().map((step) => step.command);
  assert.ok(commands.includes('npm run release:reality'));
  assert.ok(commands.some((command) => command.includes('TargetBoundaryContextFormatterTest')));
  assert.ok(commands.includes('npm run console:browser:reliability'));
  assert.ok(commands.includes('node scripts/check-doc-consistency.mjs'));
  assert.ok(commands.includes('node scripts/check-whitespace.mjs diff --check'));
});

test('normalizeEvidenceStep maps passed failed and deferred statuses', () => {
  assert.equal(normalizeEvidenceStep({ name: 'ok', command: 'true', status: 'passed' }).status, 'pass');
  assert.equal(normalizeEvidenceStep({ name: 'skip', command: 'android', status: 'deferred', reason: 'no emulator' }).status, 'deferred');
  assert.equal(normalizeEvidenceStep({ name: 'bad', command: 'false', status: 'failed', exitCode: 1 }).status, 'fail');
});

test('strict release gate fails deferred evidence', () => {
  const report = buildReleaseGateReport({
    strict: true,
    steps: [
      normalizeEvidenceStep({ name: 'Release reality', command: 'npm run release:reality', status: 'pass' }),
      normalizeEvidenceStep({ name: 'Runtime trust', command: 'npm run source-matching:fixtures:runtime -- --strict', status: 'deferred', reason: 'Android SDK unavailable' }),
    ],
  });

  assert.equal(report.status, 'fail');
});

test('non-strict release gate passes with deferred evidence', () => {
  const report = buildReleaseGateReport({
    strict: false,
    steps: [
      normalizeEvidenceStep({ name: 'Release reality', command: 'npm run release:reality', status: 'pass' }),
      normalizeEvidenceStep({ name: 'Runtime trust', command: 'npm run source-matching:fixtures:runtime -- --strict', status: 'deferred', reason: 'Android SDK unavailable' }),
    ],
  });

  assert.equal(report.status, 'pass_with_deferred');
});

test('markdown report renders deferred reasons', () => {
  const text = renderReleaseGateMarkdown({
    schemaVersion: '1.0',
    status: 'pass_with_deferred',
    strict: false,
    generatedAt: '2026-05-31T00:00:00.000Z',
    steps: [{
      name: 'Runtime trust',
      command: 'npm run source-matching:fixtures:runtime -- --strict',
      status: 'deferred',
      durationMs: 0,
      reason: 'Android SDK unavailable',
    }],
  });

  assert.match(text, /# FixThis Release Gate Report/);
  assert.match(text, /\| Runtime trust \| deferred \| `npm run source-matching:fixtures:runtime -- --strict` \| 0ms \| Android SDK unavailable \|/);
});

test('writeReleaseGateReports writes json and markdown artifacts', () => {
  const root = mkdtempSync(join(tmpdir(), 'fixthis-release-gate-'));
  try {
    const paths = writeReleaseGateReports({
      schemaVersion: '1.0',
      status: 'pass',
      strict: false,
      generatedAt: '2026-05-31T00:00:00.000Z',
      steps: [],
    }, root);
    assert.match(readFileSync(paths.json, 'utf8'), /"status": "pass"/);
    assert.match(readFileSync(paths.markdown, 'utf8'), /FixThis Release Gate Report/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('runReleaseGate supports injected runner for tests', () => {
  const report = runReleaseGate({
    strict: false,
    runEvidenceProfile: () => ({
      steps: [
        { name: 'Release reality', command: 'npm run release:reality', status: 'passed', durationMs: 1 },
        { name: 'Runtime trust', command: 'npm run source-matching:fixtures:runtime -- --strict', status: 'deferred', durationMs: 0, reason: 'Android SDK unavailable' },
      ],
    }),
  });

  assert.equal(report.status, 'pass_with_deferred');
});
```

- [x] **Step 2: Add a minimal module shell**

Create `scripts/release-gate.mjs` with:

```js
export function releaseGateSteps() {
  return [];
}

export function normalizeEvidenceStep(step) {
  return step;
}

export function buildReleaseGateReport() {
  return { schemaVersion: '1.0', status: 'fail', steps: [] };
}

export function renderReleaseGateMarkdown() {
  return '';
}

export function writeReleaseGateReports() {
  return { json: '', markdown: '' };
}

export function runReleaseGate() {
  return { schemaVersion: '1.0', status: 'fail', steps: [] };
}
```

- [x] **Step 3: Run tests to verify failure**

Run:

```bash
node --test scripts/release-gate-test.mjs
```

Expected: FAIL with assertion failures for missing commands and report output.

- [x] **Step 4: Commit the failing contract only if using a RED commit**

If this repo's current branch policy accepts RED commits inside a plan, commit:

```bash
git add scripts/release-gate-test.mjs scripts/release-gate.mjs
git commit -m "test(release): add release gate contracts"
```

If RED commits are not desired, continue to Task 8 and commit both tasks
together after tests pass.

---

## Task 8: Implement Release Gate Report

**Files:**
- Modify: `scripts/release-gate.mjs`

- [x] **Step 1: Replace the shell with the implementation**

Replace `scripts/release-gate.mjs` with:

```js
#!/usr/bin/env node
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { expandProfile, runPlan } from './evidence-runner.mjs';

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = resolve(dirname(scriptPath), '..');
export const defaultReleaseGateReportDir = join(repoRoot, 'build/reports/fixthis-release-gate');

export function releaseGateSteps() {
  return expandProfile('gate').map((step) => ({ ...step }));
}

export function normalizeEvidenceStep(step) {
  const rawStatus = step.status;
  const status = rawStatus === 'passed' || rawStatus === 'pass'
    ? 'pass'
    : rawStatus === 'deferred'
      ? 'deferred'
      : 'fail';
  return {
    name: step.name,
    command: step.command,
    status,
    durationMs: step.durationMs ?? 0,
    reason: step.reason || step.stderr?.split('\n').find(Boolean) || null,
    reportPath: step.reportPath || null,
  };
}

function gateStatus(steps, strict) {
  if (steps.some((step) => step.status === 'fail')) return 'fail';
  if (strict && steps.some((step) => step.status === 'deferred')) return 'fail';
  if (steps.some((step) => step.status === 'deferred')) return 'pass_with_deferred';
  return 'pass';
}

export function buildReleaseGateReport({
  strict = false,
  steps,
  generatedAt = new Date().toISOString(),
} = {}) {
  const normalizedSteps = (steps || []).map(normalizeEvidenceStep);
  return {
    schemaVersion: '1.0',
    status: gateStatus(normalizedSteps, strict),
    strict,
    generatedAt,
    steps: normalizedSteps,
  };
}

function cell(value) {
  if (value === null || value === undefined || value === '') return '-';
  return String(value).replaceAll('|', '\\|');
}

export function renderReleaseGateMarkdown(report) {
  const lines = [
    '# FixThis Release Gate Report',
    '',
    `- Status: ${report.status}`,
    `- Strict: ${report.strict}`,
    `- Generated: ${report.generatedAt}`,
    '',
    '| Step | Status | Command | Duration | Reason |',
    '| --- | --- | --- | --- | --- |',
  ];
  for (const step of report.steps || []) {
    lines.push(`| ${cell(step.name)} | ${cell(step.status)} | \`${cell(step.command)}\` | ${cell(step.durationMs ?? 0)}ms | ${cell(step.reason)} |`);
  }
  return `${lines.join('\n')}\n`;
}

export function writeReleaseGateReports(report, reportDir = defaultReleaseGateReportDir) {
  mkdirSync(reportDir, { recursive: true });
  const json = join(reportDir, 'report.json');
  const markdown = join(reportDir, 'report.md');
  writeFileSync(json, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdown, renderReleaseGateMarkdown(report));
  return { json, markdown };
}

export function runReleaseGate({
  strict = false,
  continueOnFailure = true,
  runEvidenceProfile = (options) => runPlan({
    schemaVersion: '1.0',
    profile: 'gate',
    strictRuntime: strict,
    steps: releaseGateSteps(),
  }, options),
} = {}) {
  const evidenceReport = runEvidenceProfile({ continueOnFailure });
  return buildReleaseGateReport({ strict, steps: evidenceReport.steps || [] });
}

function parseArgs(argv) {
  const args = { strict: false };
  for (const arg of argv) {
    if (arg === '--strict') args.strict = true;
    else if (arg === '-h' || arg === '--help') {
      console.log('Usage: node scripts/release-gate.mjs [--strict]');
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return args;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const report = runReleaseGate(args);
  const paths = writeReleaseGateReports(report);
  console.log(`Release gate: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  if (report.status === 'fail') process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  main();
}
```

- [x] **Step 2: Run release gate tests**

Run:

```bash
node --test scripts/release-gate-test.mjs
```

Expected: FAIL because `expandProfile('gate')` is not implemented yet.

- [x] **Step 3: Commit only if Task 7 was committed as RED**

If Task 7 was committed separately, do not commit this task yet. Task 9 wires
the missing `gate` profile and package script, then commits the complete GREEN
slice.

---

## Task 9: Wire Release Gate Profile, Scripts, And Docs

**Files:**
- Modify: `scripts/evidence-runner.mjs`
- Modify: `scripts/evidence-runner-test.mjs`
- Modify: `package.json`
- Modify: `scripts/check-release-readiness.mjs`
- Modify: `docs/contributing/release-readiness.md`
- Modify: `docs/releases/unreleased.md`
- Modify: `CHANGELOG.md`

- [x] **Step 1: Add failing evidence-runner and package tests**

In `scripts/evidence-runner-test.mjs`, add:

```js
test("gate profile includes release interop SSE docs and whitespace evidence", () => {
  const commands = expandProfile("gate").map((step) => step.command);
  assert.equal(commands[0], "npm run release:reality");
  assert.ok(commands.some((command) => command.includes("TargetBoundaryContextFormatterTest")));
  assert.ok(commands.includes("npm run console:browser:reliability"));
  assert.ok(commands.includes("node scripts/check-doc-consistency.mjs"));
  assert.ok(commands.includes("node scripts/check-release-readiness.mjs"));
  assert.ok(commands.includes("node scripts/check-whitespace.mjs diff --check"));
});

test("package exposes release gate commands", () => {
  const pkg = JSON.parse(readFileSync("package.json", "utf8"));
  assert.equal(pkg.scripts["release:gate"], "node scripts/release-gate.mjs");
  assert.equal(pkg.scripts["release:gate:test"], "node --test scripts/release-gate-test.mjs scripts/console-reliability-report-test.mjs");
});
```

In `scripts/check-release-readiness.mjs`, add these rules before the final
failure check:

```js
requireIncludes(
  'R36.release-gate-interop-sse-section',
  'docs/contributing/release-readiness.md',
  '## Release Gate, Interop Evidence, And SSE Closure',
);
requireIncludes(
  'R37.release-gate-command',
  'docs/contributing/release-readiness.md',
  '`npm run release:gate`',
);
```

- [x] **Step 2: Run tests to verify failure**

Run:

```bash
node --test scripts/evidence-runner-test.mjs scripts/release-gate-test.mjs
node scripts/check-release-readiness.mjs
```

Expected: FAIL because the `gate` profile, package scripts, and readiness
section are missing.

- [x] **Step 3: Add the evidence-runner gate profile**

In `scripts/evidence-runner.mjs`, add this profile after `release`:

```js
  gate: [
    step("Release reality", "npm run release:reality"),
    step(
      "Interop boundary contracts",
      "./gradlew :fixthis-mcp:test --tests \"*TargetBoundaryContextFormatterTest\" --tests \"*TargetBoundaryGuidanceTest\" --tests \"*CompactHandoffRendererTest.compactHandoffRendersInteropBoundaryContextFromNearbyComposeHost\" --no-daemon",
    ),
    step("Runtime trust boundary observations", "npm run source-matching:fixtures:test"),
    step("Runtime trust strict", "npm run source-matching:fixtures:runtime -- --strict", {
      deferrable: true,
      requiresAndroid: true,
    }),
    step("Console reliability contracts", "node --test scripts/console-reliability-report-test.mjs scripts/studioReliabilityContract-test.mjs"),
    step("Console browser reliability", "npm run console:browser:reliability"),
    step("Release readiness", "node scripts/check-release-readiness.mjs"),
    step("Docs consistency", "node scripts/check-doc-consistency.mjs"),
    step("Workspace whitespace", "node scripts/check-whitespace.mjs diff --check"),
  ],
```

- [x] **Step 4: Add package scripts**

In `package.json`, add these scripts near the existing release scripts:

```json
  "release:gate": "node scripts/release-gate.mjs",
  "release:gate:test": "node --test scripts/release-gate-test.mjs scripts/console-reliability-report-test.mjs",
```

Keep JSON comma placement valid.

- [x] **Step 5: Update release-readiness docs**

In `docs/contributing/release-readiness.md`, add this section after
`## v1.1 Trust Loop Evidence`:

```markdown
## Release Gate, Interop Evidence, And SSE Closure

This umbrella may be claimed only when the release gate report includes each
area below. The report is a local release-decision artifact and does not tag or
publish by itself.

| Claim | Required evidence |
| --- | --- |
| AndroidView/WebView-risk handoffs expose host/context boundary evidence without exact source ownership. | `./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --tests "*TargetBoundaryGuidanceTest" --tests "*CompactHandoffRendererTest.compactHandoffRendersInteropBoundaryContextFromNearbyComposeHost" --no-daemon` and `npm run source-matching:fixtures:test`. |
| Healthy SSE sessions avoid redundant session/history/preview polling, with fallback paths explicitly reported. | `node --test scripts/console-reliability-report-test.mjs scripts/studioReliabilityContract-test.mjs` and `npm run console:browser:reliability`. |
| Maintainers can use one release-gate report to classify evidence as pass, deferred, or fail. | `npm run release:gate`, `npm run release:gate:test`, and `node scripts/check-release-readiness.mjs`. |

Connected Android evidence remains local-only. If Android SDK or an unlocked
emulator is unavailable, non-strict reports must record the exact deferred
reason and strict reports must fail.
```

- [x] **Step 6: Update release notes and changelog**

In `docs/releases/unreleased.md`, add this paragraph under the current v1.1
paragraph:

```markdown
The next evidence line adds a release gate report, deeper interop boundary
context, and explicit SSE reliability reporting. These changes keep
AndroidView/WebView-risk handoffs caveated while giving maintainers one local
artifact for release decisions.
```

In `CHANGELOG.md`, under `## Unreleased` / `### Added`, add:

```markdown
- Added the Release Gate / Interop / SSE evidence line: interop-risk handoffs
  now distinguish boundary host, ancestor, and context rows; console reliability
  writes an SSE/fallback request-count report; and `npm run release:gate`
  aggregates release reality, interop, runtime trust, console, docs, and
  whitespace evidence into one pass/deferred/fail report.
```

- [x] **Step 7: Run tests to verify pass**

Run:

```bash
node --test scripts/evidence-runner-test.mjs scripts/release-gate-test.mjs scripts/console-reliability-report-test.mjs
node scripts/check-release-readiness.mjs
npm run release:gate:test
```

Expected: PASS.

- [x] **Step 8: Commit**

```bash
git add scripts/evidence-runner.mjs \
  scripts/evidence-runner-test.mjs \
  scripts/release-gate.mjs \
  scripts/release-gate-test.mjs \
  package.json \
  scripts/check-release-readiness.mjs \
  docs/contributing/release-readiness.md \
  docs/releases/unreleased.md \
  CHANGELOG.md
git commit -m "feat(release): add release gate evidence report"
```

---

## Task 10: Final Verification And Graph Update

**Files:**
- Modify: `docs/superpowers/plans/2026-05-31-release-gate-interop-sse-umbrella.md`
- Generated but not committed: `build/reports/fixthis-*`
- Generated but not committed: `graphify-out/*`

- [x] **Step 1: Run the fast local verification suite**

Run:

```bash
node --test scripts/release-gate-test.mjs scripts/console-reliability-report-test.mjs scripts/evidence-runner-test.mjs scripts/source-matching-fixtures-test.mjs scripts/annotationDetailActions-test.mjs scripts/studioReliabilityContract-test.mjs
./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --tests "*TargetBoundaryGuidanceTest" --tests "*RuntimeTrustObservationMapperTest" --tests "*CompactHandoffRendererTest.compactHandoffRendersInteropBoundaryContextFromNearbyComposeHost" --no-daemon
node scripts/check-doc-consistency.mjs
node scripts/check-release-readiness.mjs
git diff --check
```

Expected: PASS.

- [x] **Step 2: Run browser reliability and release gate**

Run:

```bash
npm run console:browser:reliability
npm run release:gate
```

Expected: PASS or `pass_with_deferred` only for connected Android evidence when
no unlocked emulator/device is available. If `npm run release:gate` returns
`pass_with_deferred`, record the deferred reason from
`build/reports/fixthis-release-gate/report.md` in the implementation summary.

- [x] **Step 3: Run connected strict verification when a device is ready**

Run:

```bash
adb devices
npm run source-matching:fixtures:runtime -- --strict
npm run release:gate -- --strict
```

Expected with a ready device: PASS. If `adb devices` shows no ready device,
record the exact output and do not claim strict connected evidence.

- [x] **Step 4: Update Graphify**

Run:

```bash
graphify update .
```

Expected: command completes. Dirty `graphify-out/` files are not committed.

- [x] **Step 5: Mark the plan tasks that landed**

Edit this plan and change completed checkboxes from `- [ ]` to `- [x]` for all
implemented tasks. Leave connected strict verification unchecked if no ready
device was available, and add the exact deferred reason under Step 3.

Execution note: connected strict verification ran against `emulator-5554` and
passed. During final verification, the runtime interop fixture exposed that
saved area annotations were not carrying nearby evidence nodes; the implementation
now builds area annotations through `TargetEvidenceService.buildFeedbackItem`,
adds a stable `comp:NativeChartHost:chart` sample host tag, and pins the fixture
area to the runtime AndroidView bounds. `npm run source-matching:fixtures:runtime
-- --strict` and `npm run release:gate -- --strict` both pass.

- [x] **Step 6: Final status check**

Run:

```bash
git status --short --ignored
```

Expected: tracked source/doc changes are committed. Ignored generated output
under `build/`, `.fixthis/`, or `graphify-out/` may appear and must not be
staged.

- [x] **Step 7: Commit plan checkbox updates if changed**

```bash
git add docs/superpowers/plans/2026-05-31-release-gate-interop-sse-umbrella.md
git commit -m "docs: record release gate umbrella execution notes"
```

---

## Plan Self-Review

**Spec coverage**

- Release-gate report with `pass`, `deferred`, and `fail`: Tasks 7-9.
- Explicit deferred reasons and strict failure semantics: Tasks 7-9.
- AndroidView/WebView-risk host/context evidence: Tasks 1-4.
- No exact ownership for interop-risk handoffs: Tasks 1-3.
- Healthy SSE zero redundant polling and report output: Tasks 5-6.
- Release-readiness, unreleased notes, changelog, and evidence profiles: Task 9.
- Final connected/local verification and Graphify update: Task 10.

**Placeholder scan**

No placeholder tokens are required for implementation. Every task names exact
files, commands, expected outcomes, and code snippets for the relevant tests or
implementation blocks.

**Type consistency**

- `BoundaryContextKind` is internal to `TargetBoundaryContextFormatter`.
- Runtime fixture reports serialize `boundaryContext[].kind` as lowercase
  strings: `host`, `ancestor`, or `context`.
- Console row kinds use the same lowercase tokens and render user-visible row
  labels: `Boundary host`, `Boundary ancestor`, and `Boundary context`.
- Release gate statuses normalize to `pass`, `deferred`, `fail`, and aggregate
  to `pass`, `pass_with_deferred`, or `fail`.
