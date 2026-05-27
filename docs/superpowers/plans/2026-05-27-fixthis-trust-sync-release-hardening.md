# FixThis Trust Sync Release Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the approved trust-to-release hardening program: clearer interop/visual-area handoff guidance, reduced happy-path session pull refreshes under healthy SSE, lightweight event-stream diagnostics, and release evidence alignment.

**Architecture:** Keep persisted MCP/session JSON additive. Put reusable handoff boundary wording in `fixthis-mcp` renderer helpers, keep console event diagnostics process-local, and narrow browser pull refreshes only after tests prove SSE/server responses own the happy path. Release docs are updated last so every claim points to a concrete validation command.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, JUnit/kotlin.test, Node.js `node:test`, browser reliability script with Playwright, Markdown docs.

---

## Scope Check

The approved spec intentionally combines three subsystems. This plan keeps one
umbrella file but splits execution into independent tracks:

- Track A tasks can ship without Track B.
- Track B tasks can ship without Track C.
- Track C must run last because it documents evidence produced by Track A and
  Track B.

## File Structure

### Track A - Interop Evidence Trust

- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryGuidance.kt`
  - Single responsibility: derive agent-facing boundary guidance from an
    `AnnotationDto` without changing persisted JSON.
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryGuidanceTest.kt`
  - Focused unit tests for interop, visual-area, no-meaningful-target, and
    no-guidance cases.
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
  - Render PRECISE/FULL boundary lines before likely source candidates.
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
  - Render compact `targetBoundary=interop-risk`, `targetBoundary=visual-area`,
    or `targetBoundary=no-compose-target` lines for weak boundary cases.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`
  - Add renderer regression tests.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
  - Add compact token regression tests.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt`
  - Add optional expected boundary token to corpus metadata.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`
  - Verify corpus boundary tokens in PRECISE and COMPACT output.
- Modify: `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`
  - Mark visual-area and interop corpus cases with expected boundary tokens.

### Track B - SSE Pull-Path Retirement And Observability

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/events/ConsoleEventModels.kt`
  - Add serializable event diagnostics model.
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/events/ConsoleEventBus.kt`
  - Track emitted events, subscribers, replay requests, and overflow recovery.
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventDiagnosticsRoutes.kt`
  - Expose `GET /api/events/diagnostics` as local diagnostic JSON.
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`
  - Register the diagnostics route.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventBusTest.kt`
  - Test stats counters.
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt`
  - Test diagnostics route JSON.
- Modify: `fixthis-mcp/src/main/console/history.js`
  - Add `refreshSessionsWhenEventsDisconnected()`.
- Modify: `fixthis-mcp/src/main/console/annotations.js`
  - Gate saved-item and draft-persistence session refreshes behind SSE state.
- Modify: `fixthis-mcp/src/main/console/prompt.js`
  - Gate Copy Prompt and Save to MCP post-mutation refreshes behind SSE state.
- Modify: `fixthis-mcp/src/main/console/state.js`
  - Use the SSE-aware refresh helper for stale-response recovery.
- Modify: `scripts/studioReliabilityContract-test.mjs`
  - Static contract tests for the SSE-aware helper and mutation call sites.
- Modify: `scripts/console-browser-reliability.mjs`
  - Browser proof that Save to MCP does not fetch `/api/session` or
    `/api/sessions` again while EventSource is connected.

### Track C - Release And Agent Install Evidence Pack

- Modify: `docs/contributing/release-readiness.md`
  - Add Trust Sync release evidence table and command mapping.
- Modify: `docs/releases/unreleased.md`
  - Add current-main highlights for Track A/B.
- Modify: `CHANGELOG.md`
  - Add user-visible unreleased entries for Track A/B/C.
- Modify: `scripts/check-release-readiness.mjs`
  - Add release-readiness rules for the new evidence section.

## Task 1: Add Boundary Guidance Unit

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryGuidanceTest.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryGuidance.kt`

- [ ] **Step 1: Write the failing boundary-guidance tests**

Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryGuidanceTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TargetBoundaryGuidanceTest {
    @Test
    fun interopWarningProducesInteropBoundaryGuidance() {
        val guidance = TargetBoundaryGuidance.from(
            areaItem(warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP)),
        )

        assertEquals("interop-risk", guidance.compactToken)
        assertTrue(
            guidance.preciseLines.contains(
                "- Boundary: possible AndroidView/WebView target; source candidates are context only.",
            ),
        )
        assertTrue(
            guidance.preciseLines.contains(
                "- Boundary action: inspect the Compose parent or native view boundary before editing.",
            ),
        )
    }

    @Test
    fun visualAreaProducesNoExactSourceGuidance() {
        val guidance = TargetBoundaryGuidance.from(areaItem(warnings = listOf(TargetReliabilityWarning.VISUAL_AREA_ONLY)))

        assertEquals("visual-area", guidance.compactToken)
        assertTrue(
            guidance.preciseLines.contains(
                "- Boundary: visual area target; do not infer an exact Compose owner from nearby labels.",
            ),
        )
    }

    @Test
    fun noMeaningfulComposeTargetProducesSearchGuidance() {
        val guidance = TargetBoundaryGuidance.from(
            nodeItem(warnings = listOf(TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET)),
        )

        assertEquals("no-compose-target", guidance.compactToken)
        assertTrue(
            guidance.preciseLines.contains(
                "- Boundary: no meaningful Compose node covers this target; search from surrounding labels.",
            ),
        )
    }

    @Test
    fun strongNodeHasNoBoundaryGuidance() {
        assertEquals(TargetBoundaryGuidance.NONE, TargetBoundaryGuidance.from(nodeItem(warnings = emptyList())))
    }

    private fun nodeItem(warnings: List<TargetReliabilityWarning>): AnnotationDto = AnnotationDto(
        itemId = "node-item",
        screenId = "screen",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Node("node", FixThisRect(0f, 0f, 100f, 80f)),
        comment = "Edit this target",
        targetReliability = reliability(warnings),
    )

    private fun areaItem(warnings: List<TargetReliabilityWarning>): AnnotationDto = AnnotationDto(
        itemId = "area-item",
        screenId = "screen",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 100f, 80f)),
        comment = "Edit this area",
        targetReliability = reliability(warnings),
    )

    private fun reliability(warnings: List<TargetReliabilityWarning>): TargetReliability = TargetReliability(
        confidence = if (warnings.isEmpty()) TargetConfidence.HIGH else TargetConfidence.LOW,
        warnings = warnings,
    )
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*TargetBoundaryGuidanceTest" --no-daemon
```

Expected: FAIL because `TargetBoundaryGuidance` does not exist.

- [ ] **Step 3: Add the boundary-guidance implementation**

Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryGuidance.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning

internal data class TargetBoundaryGuidance(
    val compactToken: String? = null,
    val preciseLines: List<String> = emptyList(),
) {
    companion object {
        val NONE = TargetBoundaryGuidance()

        fun from(item: AnnotationDto): TargetBoundaryGuidance {
            val warnings = item.targetReliability?.warnings.orEmpty()
            return when {
                TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP in warnings -> TargetBoundaryGuidance(
                    compactToken = "interop-risk",
                    preciseLines = listOf(
                        "- Boundary: possible AndroidView/WebView target; source candidates are context only.",
                        "- Boundary action: inspect the Compose parent or native view boundary before editing.",
                    ),
                )
                item.target is AnnotationTargetDto.Area ||
                    TargetReliabilityWarning.VISUAL_AREA_ONLY in warnings -> TargetBoundaryGuidance(
                    compactToken = "visual-area",
                    preciseLines = listOf(
                        "- Boundary: visual area target; do not infer an exact Compose owner from nearby labels.",
                    ),
                )
                TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET in warnings -> TargetBoundaryGuidance(
                    compactToken = "no-compose-target",
                    preciseLines = listOf(
                        "- Boundary: no meaningful Compose node covers this target; search from surrounding labels.",
                    ),
                )
                else -> NONE
            }
        }
    }
}
```

- [ ] **Step 4: Run the boundary-guidance test and verify it passes**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*TargetBoundaryGuidanceTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryGuidance.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryGuidanceTest.kt
git commit -m "feat(handoff): derive target boundary guidance"
```

## Task 2: Render Boundary Guidance In PRECISE And COMPACT Handoffs

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`

- [ ] **Step 1: Add PRECISE renderer regression tests**

Append these tests inside `FeedbackQueueFormatterPhase2Test`:

```kotlin
    @Test
    fun boundaryGuidance_rendersBeforeLikelySourceForInteropRisk() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "sample/DiagnosticsScreen.kt")),
            editSurfaceCandidates = emptyList(),
            targetReliability = reliability(
                confidence = io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW,
                warnings = listOf(
                    io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP,
                ),
            ),
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        val boundaryIndex = md.indexOf("- Boundary: possible AndroidView/WebView target")
        val sourceIndex = md.indexOf("1. `sample/DiagnosticsScreen.kt`")
        assertTrue(boundaryIndex >= 0, "missing boundary guidance\n$md")
        assertTrue(sourceIndex > boundaryIndex, "boundary guidance must precede source candidates\n$md")
        assertTrue(md.contains("- Boundary action: inspect the Compose parent or native view boundary before editing."))
    }

    @Test
    fun boundaryGuidance_visualAreaNoSourceDoesNotInventSource() {
        val item = AnnotationDto(
            itemId = "area-no-source",
            screenId = "screen-1",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 160f, 80f)),
            sourceCandidates = emptyList(),
            editSurfaceCandidates = emptyList(),
            comment = "Tighten this empty area",
            targetReliability = reliability(
                confidence = io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW,
                warnings = listOf(
                    io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.VISUAL_AREA_ONLY,
                ),
            ),
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(md.contains("- Boundary: visual area target; do not infer an exact Compose owner from nearby labels."))
        assertTrue(md.contains("No source candidate from current evidence; search by target labels and request."))
        assertTrue(!md.contains("Likely Source:\n1."), "must not invent a source candidate\n$md")
    }
```

- [ ] **Step 2: Add COMPACT renderer regression tests**

Append this test inside `CompactHandoffRendererTest`:

```kotlin
    @Test
    fun compactHandoffRendersTargetBoundaryTokenForInteropRisk() {
        val markdown = CompactHandoffRenderer.render(
            oneItemSession(
                AnnotationDto(
                    itemId = "item-boundary",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(10f, 20f, 200f, 120f)),
                    comment = "Fix native chart spacing",
                    sequenceNumber = 1,
                    targetReliability = TargetReliability(
                        confidence = TargetConfidence.LOW,
                        warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
                    ),
                ),
            ),
        )

        assertTrue(markdown.contains("  targetBoundary=interop-risk"), markdown)
        assertTrue(markdown.contains("  targetAction=treat-source-paths-as-hints"), markdown)
    }
```

- [ ] **Step 3: Run the renderer tests and verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackQueueFormatterPhase2Test" --tests "*CompactHandoffRendererTest.compactHandoffRendersTargetBoundaryTokenForInteropRisk" --no-daemon
```

Expected: FAIL because renderers do not call `TargetBoundaryGuidance`.

- [ ] **Step 4: Integrate boundary guidance into PRECISE/FULL output**

In `FeedbackQueueFormatter.appendLikelySource`, insert this block immediately
after the `sourceCandidates` and `editSurfaceCandidates` local values:

```kotlin
        TargetBoundaryGuidance.from(item).preciseLines.forEach { line ->
            appendLine(line)
        }
```

The start of the function should read:

```kotlin
    private fun StringBuilder.appendLikelySource(
        item: AnnotationDto,
        target: AnnotationTargetDto,
        maxCandidates: Int,
    ) {
        val sourceCandidates = item.sourceCandidates
        val editSurfaceCandidates = item.editSurfaceCandidates
        TargetBoundaryGuidance.from(item).preciseLines.forEach { line ->
            appendLine(line)
        }
        if (sourceCandidates.isEmpty() && editSurfaceCandidates.isEmpty()) {
            appendLine("No source candidate from current evidence; search by target labels and request.")
            return
        }
```

- [ ] **Step 5: Integrate boundary guidance into COMPACT output**

In `CompactHandoffRenderer.appendCompactItem`, insert the compact line after
`TargetSummaryFormatter.render(...)` and before `compactUiLine(...)`:

```kotlin
        TargetBoundaryGuidance.from(item).compactToken?.let { token ->
            appendLine("  targetBoundary=$token")
        }
```

The local block should read:

```kotlin
        appendLine("  ${TargetSummaryFormatter.render(item, owner)}")
        TargetBoundaryGuidance.from(item).compactToken?.let { token ->
            appendLine("  targetBoundary=$token")
        }
        appendLine(compactUiLine(item, context.isOverlap, context.instanceLabel, context.dupRefMarker))
```

- [ ] **Step 6: Run renderer tests and verify they pass**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackQueueFormatterPhase2Test" --tests "*CompactHandoffRendererTest.compactHandoffRendersTargetBoundaryTokenForInteropRisk" --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit Task 2**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt
git commit -m "feat(handoff): render target boundary guidance"
```

## Task 3: Add Boundary Expectations To Handoff Evaluation Corpus

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`
- Modify: `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`

- [ ] **Step 1: Add corpus metadata support**

In `HandoffEvaluationCase`, add this nullable field after `targetWarnings`:

```kotlin
    val expectedBoundaryToken: String? = null,
```

- [ ] **Step 2: Add failing corpus boundary assertions**

Append this test inside `HandoffEvaluationCorpusTest`:

```kotlin
    @Test
    fun corpusItemsRenderBoundaryGuidanceWhenExpected() {
        val corpus = HandoffEvaluationFixtures.loadCorpus()
        val items = corpus.cases.map { HandoffEvaluationFixtures.annotationFor(it) }
        val session = SessionDto(
            sessionId = "handoff-boundary-eval",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = corpus.cases.map { HandoffEvaluationFixtures.screenFor(it) },
            items = items.mapIndexed { index, item -> item.copy(sequenceNumber = index + 1) },
        )

        val compact = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)
        val precise = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)

        corpus.cases.filter { it.expectedBoundaryToken != null }.forEach { case ->
            val expectedToken = requireNotNull(case.expectedBoundaryToken)
            assertTrue(
                compact.contains("targetBoundary=$expectedToken"),
                "COMPACT missing boundary token for ${case.id}\n$compact",
            )
            assertTrue(
                precise.contains("- Boundary:"),
                "PRECISE missing boundary guidance for ${case.id}\n$precise",
            )
        }
    }
```

- [ ] **Step 3: Mark visual-area and interop cases in the corpus**

In `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`, update the
`visual-area-gap` case:

```json
    {
      "id": "visual-area-gap",
      "comment": "Tighten this empty gap",
      "targetType": "area",
      "sourceCandidates": [],
      "expectedRole": "VISUAL_AREA",
      "expectedBoundaryToken": "visual-area",
      "expectedTop3Contains": null,
      "allowHighConfidence": false
    },
```

Update the `interop-risk` case:

```json
    {
      "id": "interop-risk",
      "comment": "Fix the native chart spacing",
      "targetType": "area",
      "targetWarnings": ["POSSIBLE_VIEW_INTEROP"],
      "sourceCandidates": [],
      "expectedRole": "INTEROP_RISK",
      "expectedBoundaryToken": "interop-risk",
      "expectedTop3Contains": null,
      "allowHighConfidence": false
    },
```

- [ ] **Step 4: Run corpus tests and verify they pass**

Run:

```bash
npm run handoff:eval:test
```

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt \
  fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json
git commit -m "test(handoff): cover boundary guidance corpus"
```

## Task 4: Add Event Bus Diagnostics

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/events/ConsoleEventModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/events/ConsoleEventBus.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventDiagnosticsRoutes.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventBusTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt`

- [ ] **Step 1: Add failing bus stats test**

Append this test inside `ConsoleEventBusTest`:

```kotlin
    @Test
    fun statsTrackEmitsSubscribersReplayAndOverflow() {
        val bus = ConsoleEventBus(ringSize = 1, clock = { 123L })
        val subscription = bus.subscribe { }
        bus.emit("a", buildJsonObject { put("value", 1) })
        bus.emit("b", buildJsonObject { put("value", 2) })
        val replay = bus.eventsAfter(0L)
        subscription.close()

        assertTrue(replay.overflow)
        val stats = bus.stats()
        assertEquals(2L, stats.emittedEvents)
        assertEquals(1L, stats.openedSubscriptions)
        assertEquals(1L, stats.closedSubscriptions)
        assertEquals(0, stats.activeSubscriptions)
        assertEquals(1L, stats.replayRequests)
        assertEquals(1L, stats.replayOverflowCount)
    }
```

- [ ] **Step 2: Add failing diagnostics route test**

Append this test inside `ConsoleEventsRoutesTest`:

```kotlin
    @Test
    fun eventDiagnosticsEndpointReturnsBusStats() {
        val fixture = newConsoleSessionFixtureWithTempRoot(
            idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
        )
        val bus = ConsoleEventBus(clock = { 1L })
        val server = FeedbackConsoleServer(fixture.service, eventBus = bus)
        try {
            server.start()
            bus.emit("sessions-updated", kotlinx.serialization.json.buildJsonObject { put("sessionId", "session-1") })

            val connection = URI("${server.url}/api/events/diagnostics").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000

            assertEquals(200, connection.responseCode)
            val payload = Json.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            assertEquals("1", payload.getValue("emittedEvents").jsonPrimitive.content)
        } finally {
            server.stop()
            fixture.close()
        }
    }
```

Add these imports if missing:

```kotlin
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
```

- [ ] **Step 3: Run diagnostics tests and verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleEventBusTest" --tests "*ConsoleEventsRoutesTest.eventDiagnosticsEndpointReturnsBusStats" --no-daemon
```

Expected: FAIL because `stats()` and `/api/events/diagnostics` do not exist.

- [ ] **Step 4: Add the serializable diagnostics model**

In `ConsoleEventModels.kt`, add:

```kotlin
@Serializable
data class ConsoleEventBusStats(
    val emittedEvents: Long,
    val openedSubscriptions: Long,
    val closedSubscriptions: Long,
    val activeSubscriptions: Int,
    val replayRequests: Long,
    val replayOverflowCount: Long,
    val oldestAvailableEventId: Long?,
    val newestEventId: Long?,
)
```

- [ ] **Step 5: Implement stats in `ConsoleEventBus`**

Replace the body of `ConsoleEventBus` with this counter-aware version, keeping
the existing constructor signature:

```kotlin
class ConsoleEventBus(
    private val ringSize: Int = 256,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(ringSize > 0) { "Console event ring size must be positive" }
    }

    private val nextId = AtomicLong(0)
    private val emittedEvents = AtomicLong(0)
    private val openedSubscriptions = AtomicLong(0)
    private val closedSubscriptions = AtomicLong(0)
    private val replayRequests = AtomicLong(0)
    private val replayOverflowCount = AtomicLong(0)
    private val ring = ArrayDeque<ConsoleEvent>()
    private val subscribers = CopyOnWriteArrayList<(ConsoleEvent) -> Unit>()
    private val lock = Any()

    fun emit(name: String, data: JsonObject): ConsoleEvent {
        val event = ConsoleEvent(
            id = nextId.incrementAndGet(),
            name = name,
            data = data,
            createdAtEpochMillis = clock(),
        )
        emittedEvents.incrementAndGet()
        synchronized(lock) {
            ring.addLast(event)
            while (ring.size > ringSize) ring.removeFirst()
        }
        subscribers.forEach { subscriber -> runCatching { subscriber(event) } }
        return event
    }

    fun eventsAfter(lastEventId: Long): ConsoleEventReplay = synchronized(lock) {
        replayRequests.incrementAndGet()
        val events = ring.toList()
        val oldest = events.firstOrNull()?.id
        if (oldest != null && lastEventId < oldest - 1) {
            replayOverflowCount.incrementAndGet()
            ConsoleEventReplay(emptyList(), overflow = true, oldestAvailableEventId = oldest)
        } else {
            ConsoleEventReplay(events.filter { it.id > lastEventId }, overflow = false, oldestAvailableEventId = oldest)
        }
    }

    fun subscribe(listener: (ConsoleEvent) -> Unit): AutoCloseable {
        subscribers += listener
        openedSubscriptions.incrementAndGet()
        return AutoCloseable {
            if (subscribers.remove(listener)) {
                closedSubscriptions.incrementAndGet()
            }
        }
    }

    fun stats(): ConsoleEventBusStats = synchronized(lock) {
        val events = ring.toList()
        ConsoleEventBusStats(
            emittedEvents = emittedEvents.get(),
            openedSubscriptions = openedSubscriptions.get(),
            closedSubscriptions = closedSubscriptions.get(),
            activeSubscriptions = subscribers.size,
            replayRequests = replayRequests.get(),
            replayOverflowCount = replayOverflowCount.get(),
            oldestAvailableEventId = events.firstOrNull()?.id,
            newestEventId = events.lastOrNull()?.id,
        )
    }
}
```

Ensure `ConsoleEventBus.kt` imports `ConsoleEventBusStats` from the same
package and keeps existing imports for `JsonObject`, `ArrayDeque`,
`CopyOnWriteArrayList`, and `AtomicLong`.

- [ ] **Step 6: Add diagnostics route**

Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventDiagnosticsRoutes.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBusStats

internal class ConsoleEventDiagnosticsRoutes(
    private val eventBus: ConsoleEventBus,
) : ConsoleRoute {
    override fun matches(path: String): Boolean = path == "/api/events/diagnostics"

    override fun handle(exchange: HttpExchange) {
        exchange.requireMethod("GET") {
            exchange.sendJson(
                status = 200,
                body = fixThisJson.encodeToString(ConsoleEventBusStats.serializer(), eventBus.stats()),
            )
        }
    }
}
```

- [ ] **Step 7: Register diagnostics route**

In `FeedbackConsoleServer.consoleRouteTable`, insert the route immediately
after `ConsoleEventRoutes(config.service, config.eventBus)`:

```kotlin
                ConsoleEventDiagnosticsRoutes(config.eventBus),
```

- [ ] **Step 8: Run diagnostics tests and verify they pass**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleEventBusTest" --tests "*ConsoleEventsRoutesTest.eventDiagnosticsEndpointReturnsBusStats" --no-daemon
```

Expected: PASS.

- [ ] **Step 9: Commit Task 4**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/events/ConsoleEventModels.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/events/ConsoleEventBus.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventDiagnosticsRoutes.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventBusTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ConsoleEventsRoutesTest.kt
git commit -m "feat(console): expose event stream diagnostics"
```

## Task 5: Gate Item And Handoff Mutation Refreshes Behind SSE Health

**Files:**
- Modify: `scripts/studioReliabilityContract-test.mjs`
- Modify: `fixthis-mcp/src/main/console/history.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/prompt.js`
- Modify: `fixthis-mcp/src/main/console/state.js`

- [ ] **Step 1: Add failing static contract tests**

Append this test to `scripts/studioReliabilityContract-test.mjs`:

```js
test('item and handoff mutation paths use SSE-aware session refresh fallback', () => {
  const history = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/history.js'), 'utf8');
  const prompt = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/prompt.js'), 'utf8');
  const stateSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/state.js'), 'utf8');

  assert.match(history, /async function refreshSessionsWhenEventsDisconnected\(\)/);
  assert.match(history, /if \(isConsoleEventsConnected\(\)\) return state\.sessionSummaries \|\| \[\];/);

  const savedUpdate = body(annotations, 'function applySavedSessionUpdate(updatedSession, sessionId)');
  assert.doesNotMatch(savedUpdate, /refreshSessions\(\)\.catch\(showError\)/);
  assert.match(savedUpdate, /refreshSessionsWhenEventsDisconnected\(\)\.catch\(showError\)/);

  const deleteSaved = body(annotations, 'async function deleteSavedEvidenceItem(itemId');
  assert.doesNotMatch(deleteSaved, /refreshSessions\(\)\.catch\(showError\)/);
  assert.match(deleteSaved, /refreshSessionsWhenEventsDisconnected\(\)\.catch\(showError\)/);

  const persistPending = body(annotations, 'async function persistPendingFeedbackItems(options = {})');
  assert.doesNotMatch(persistPending, /await refreshSessions\(\);/);
  assert.match(persistPending, /await refreshSessionsWhenEventsDisconnected\(\);/);

  const copyPromptBody = body(prompt, 'async function copyPrompt()');
  assert.doesNotMatch(copyPromptBody, /await refreshSessions\(\);/);
  assert.match(copyPromptBody, /await refreshSessionsWhenEventsDisconnected\(\);/);

  const sendAgentBody = body(prompt, 'async function sendAgentPrompt()');
  assert.doesNotMatch(sendAgentBody, /await refreshSessions\(\);/);
  assert.match(sendAgentBody, /await refreshSessionsWhenEventsDisconnected\(\);/);

  assert.match(stateSource, /refreshSessionsWhenEventsDisconnected\(\)\.catch\(showError\)/);
});
```

- [ ] **Step 2: Run the static contract and verify it fails**

Run:

```bash
node --test scripts/studioReliabilityContract-test.mjs
```

Expected: FAIL because `refreshSessionsWhenEventsDisconnected` is missing and
mutation paths call `refreshSessions()` directly.

- [ ] **Step 3: Add SSE-aware refresh helper**

In `fixthis-mcp/src/main/console/history.js`, add this function immediately
after `refreshSessions()`:

```js
            async function refreshSessionsWhenEventsDisconnected() {
              if (isConsoleEventsConnected()) return state.sessionSummaries || [];
              return refreshSessions();
            }
```

- [ ] **Step 4: Replace item mutation refresh calls**

In `fixthis-mcp/src/main/console/annotations.js`, replace the two
`refreshSessions().catch(showError);` calls inside `applySavedSessionUpdate`
with:

```js
                refreshSessionsWhenEventsDisconnected().catch(showError);
```

In `deleteSavedEvidenceItem`, replace both `refreshSessions().catch(showError);`
calls with:

```js
                refreshSessionsWhenEventsDisconnected().catch(showError);
```

In `persistPendingFeedbackItems`, replace the fallback line:

```js
              await refreshSessions();
```

with:

```js
              await refreshSessionsWhenEventsDisconnected();
```

- [ ] **Step 5: Replace prompt mutation refresh calls**

In `fixthis-mcp/src/main/console/prompt.js`, replace both post-mutation
`await refreshSessions();` calls with:

```js
                            await refreshSessionsWhenEventsDisconnected();
```

and:

```js
                        await refreshSessionsWhenEventsDisconnected();
```

- [ ] **Step 6: Replace stale response refresh recovery**

In `fixthis-mcp/src/main/console/state.js`, replace:

```js
                  refreshSessions().catch(showError);
```

with:

```js
                  refreshSessionsWhenEventsDisconnected().catch(showError);
```

- [ ] **Step 7: Run the static contract and verify it passes**

Run:

```bash
node --test scripts/studioReliabilityContract-test.mjs
```

Expected: PASS.

- [ ] **Step 8: Commit Task 5**

```bash
git add scripts/studioReliabilityContract-test.mjs \
  fixthis-mcp/src/main/console/history.js \
  fixthis-mcp/src/main/console/annotations.js \
  fixthis-mcp/src/main/console/prompt.js \
  fixthis-mcp/src/main/console/state.js
git commit -m "feat(console): gate mutation refreshes on SSE health"
```

## Task 6: Add Browser Proof For No Pull Refresh On Healthy SSE Handoff

**Files:**
- Modify: `scripts/console-browser-reliability.mjs`

- [ ] **Step 1: Add failing browser proof**

Add this helper near the existing browser proof helpers:

```js
function countSessionPulls(fixture) {
  return fixture.getRequestLog().filter((entry) =>
    entry.method === 'GET' && (entry.path === '/api/session' || entry.path === '/api/sessions')
  ).length;
}
```

Add this test function after `testSsePreviewPushDoesNotPollPreview()`:

```js
async function testSaveToMcpDoesNotPullSessionsWhenSseIsConnected() {
  await withBrowser(async ({ fixture, context }) => {
    const page = await openConsolePage(context, fixture.url);
    await waitUntil(() => fixture.eventClientCount() >= 1);
    await createDraftAnnotation(page, 'Persist through SSE without extra pull refresh');
    const before = countSessionPulls(fixture);

    await page.click('#sendAgentButton');
    await page.waitForFunction(
      () => window.FixThisConsoleDebug.getState().session?.items?.some((item) => item.delivery === 'sent'),
      null,
      { timeout: 8000 },
    );
    await new Promise((resolve) => setTimeout(resolve, 600));

    assert.equal(
      countSessionPulls(fixture),
      before,
      'healthy EventSource Save to MCP should not issue extra /api/session or /api/sessions pulls',
    );
  });
}
```

Call it from `run()` after `testSsePreviewPushDoesNotPollPreview()`:

```js
  await testSaveToMcpDoesNotPullSessionsWhenSseIsConnected();
```

- [ ] **Step 2: Run the browser proof and verify it fails before Task 5 implementation**

Run this only if Task 5 has not been implemented yet:

```bash
npm run console:browser:reliability
```

Expected before Task 5: FAIL because Save to MCP still calls
`refreshSessions()` directly. Expected after Task 5: PASS.

- [ ] **Step 3: Run the browser proof after Task 5 and verify it passes**

Run:

```bash
npm run console:browser:reliability
```

Expected: PASS with `PASS console browser reliability proof`.

- [ ] **Step 4: Commit Task 6**

```bash
git add scripts/console-browser-reliability.mjs
git commit -m "test(console): prove SSE handoff avoids pull refresh"
```

## Task 7: Align Release Evidence Documentation

**Files:**
- Modify: `docs/contributing/release-readiness.md`
- Modify: `docs/releases/unreleased.md`
- Modify: `CHANGELOG.md`
- Modify: `scripts/check-release-readiness.mjs`

- [ ] **Step 1: Add failing release-readiness script rules**

In `scripts/check-release-readiness.mjs`, append these rules after `R23`:

```js
requireIncludes(
  'R24.trust-sync-release-hardening-section',
  'docs/contributing/release-readiness.md',
  '## Trust Sync Release Hardening Evidence',
);
requireIncludes(
  'R25.trust-sync-boundary-guidance-command',
  'docs/contributing/release-readiness.md',
  '`npm run handoff:eval:test`',
);
requireIncludes(
  'R26.trust-sync-sse-browser-proof-command',
  'docs/contributing/release-readiness.md',
  '`npm run console:browser:reliability`',
);
requireIncludes(
  'R27.trust-sync-event-diagnostics-command',
  'docs/contributing/release-readiness.md',
  '`./gradlew :fixthis-mcp:test --tests "*ConsoleEventBusTest" --tests "*ConsoleEventsRoutesTest" --no-daemon`',
);
```

- [ ] **Step 2: Run release-readiness check and verify it fails**

Run:

```bash
node scripts/check-release-readiness.mjs
```

Expected: FAIL because the Trust Sync section is not yet documented.

- [ ] **Step 3: Add release-readiness evidence section**

In `docs/contributing/release-readiness.md`, insert this section after the
`v0.6 Release Claim Manifest` evidence list and before
`Required Before Next Source Release`:

```markdown
## Trust Sync Release Hardening Evidence

The trust-sync hardening line may be claimed only when each claim below has
matching local evidence from the release commit.

| Claim | Required evidence |
| --- | --- |
| Interop and visual-area handoffs avoid exact-source overclaiming. | `npm run handoff:eval:test` plus `./gradlew :fixthis-mcp:test --tests "*TargetBoundaryGuidanceTest" --tests "*FeedbackQueueFormatterPhase2Test" --tests "*CompactHandoffRendererTest" --no-daemon`. |
| SSE is the happy-path console sync channel for item and handoff mutations. | `node --test scripts/studioReliabilityContract-test.mjs` and `npm run console:browser:reliability`. |
| Event-stream diagnostics expose local event count, reconnect/subscriber, replay, and overflow state. | `./gradlew :fixthis-mcp:test --tests "*ConsoleEventBusTest" --tests "*ConsoleEventsRoutesTest" --no-daemon`. |
| Release and agent-install docs match the supported public surfaces. | `node scripts/check-release-readiness.mjs`, `bash scripts/check-docs-cli-surface.sh`, `npm run docs:agent-bootstrap:test`, and `npm run release:version:check`. |

Connected runtime trust evidence remains local-only. If Android SDK or an
unlocked emulator is unavailable, record the runtime command as deferred rather
than implying it passed:

```bash
npm run source-matching:fixtures:runtime -- --strict
```
```

- [ ] **Step 4: Update current-main release notes**

In `docs/releases/unreleased.md`, add this bullet under `## Highlights` after
the existing Trust program Phase 2 bullet:

```markdown
- Trust-sync hardening now makes interop and visual-area boundaries explicit in
  agent handoffs, reduces item/handoff mutation pull refreshes while SSE is
  healthy, and exposes local event-stream diagnostics for release evidence.
```

- [ ] **Step 5: Update changelog**

In `CHANGELOG.md`, add these entries under `## Unreleased`.

Under `### Added`:

```markdown
- Handoffs now render explicit target-boundary guidance for visual-area,
  no-meaningful-Compose-target, and possible AndroidView/WebView cases so
  agents treat source candidates as context rather than exact edit ownership
  when target evidence is weak.
- The feedback console exposes local `/api/events/diagnostics` counters for
  event emissions, subscribers, replay requests, and overflow recovery.
```

Under `### Changed`:

```markdown
- Item and handoff mutation flows now skip redundant `/api/session` and
  `/api/sessions` refreshes while EventSource is healthy, keeping pull refresh
  as the manual and SSE-recovery path.
```

- [ ] **Step 6: Run release checks**

Run:

```bash
node scripts/check-release-readiness.mjs
bash scripts/check-docs-cli-surface.sh
npm run docs:agent-bootstrap:test
npm run release:version:check
```

Expected: all commands PASS.

- [ ] **Step 7: Commit Task 7**

```bash
git add docs/contributing/release-readiness.md docs/releases/unreleased.md CHANGELOG.md scripts/check-release-readiness.mjs
git commit -m "docs(release): add trust sync evidence"
```

## Task 8: Final Verification And Graph Refresh

**Files:**
- Modify: `graphify-out/*` only as an uncommitted local artifact unless the repository policy changes.

- [ ] **Step 1: Run focused Track A checks**

```bash
./gradlew :fixthis-mcp:test --tests "*TargetBoundaryGuidanceTest" --tests "*FeedbackQueueFormatterPhase2Test" --tests "*CompactHandoffRendererTest" --no-daemon
npm run handoff:eval:test
```

Expected: PASS.

- [ ] **Step 2: Run focused Track B checks**

```bash
./gradlew :fixthis-mcp:test --tests "*ConsoleEventBusTest" --tests "*ConsoleEventsRoutesTest" --no-daemon
node --test scripts/consoleEvents-test.mjs
node --test scripts/studioReliabilityContract-test.mjs
npm run console:browser:reliability
```

Expected: PASS.

- [ ] **Step 3: Run focused Track C checks**

```bash
node scripts/check-release-readiness.mjs
bash scripts/check-docs-cli-surface.sh
npm run docs:agent-bootstrap:test
npm run release:version:check
```

Expected: PASS.

- [ ] **Step 4: Run source matching fixture checks**

```bash
npm run source-matching:fixtures:test
```

Expected: PASS.

If Android SDK and an unlocked emulator are available, also run:

```bash
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
npm run source-matching:fixtures:runtime -- --strict
```

Expected with device available: PASS. If no device is available, record the
runtime command as deferred in the implementation summary and do not claim it
passed.

- [ ] **Step 5: Run whitespace and graph refresh checks**

```bash
git diff --check
graphify update .
git status --short
```

Expected: `git diff --check` passes. `graphify update .` may dirty
`graphify-out/`; leave those artifacts uncommitted unless explicitly requested.

- [ ] **Step 6: Commit verification follow-up changes if any tracked files changed**

If verification caused tracked source or docs edits, inspect and commit the
tracked edits:

```bash
git status --short
git add CHANGELOG.md docs/contributing/release-readiness.md docs/releases/unreleased.md \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session \
  fixthis-mcp/src/main/console \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session \
  fixthis-mcp/src/test/resources/handoff-eval \
  scripts
git commit -m "fix: close trust sync verification gaps"
```

Expected: no commit is needed if verification did not require code or docs
changes.

## Plan Self-Review Checklist

- Spec coverage: Track A maps to Tasks 1-3, Track B maps to Tasks 4-6, Track C
  maps to Task 7, and final evidence maps to Task 8.
- Persisted JSON compatibility: no task renames persisted MCP/session fields.
- Local-first boundary: runtime fixture artifacts, `.fixthis/`, and
  `graphify-out/` remain uncommitted.
- Recovery behavior: manual refresh, SSE failure fallback, replay overflow
  recovery, and reconnect recovery remain in scope.
- Release claims: every new release claim points to a concrete command.
