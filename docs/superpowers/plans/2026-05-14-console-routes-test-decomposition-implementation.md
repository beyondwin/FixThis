# Console Routes Test Decomposition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the 2897-line, 100-test `ConsoleFeedbackItemRoutesTest.kt` into seven focused test files aligned 1:1 with the already-decomposed production route modules, with one shared fixture helper to remove inline-setup duplication.

**Architecture:** Move tests verbatim into new files grouped by production-module prefix (item/session/handoff/asset/polling/etag/navigation). Add a single `ConsoleRouteTestFixtures.kt` factory under `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/fixtures/`. Verify completeness via a pre-computed ledger and Gradle test-count invariant; preserve every `@Test` body without behavioral change.

**Tech Stack:** Kotlin/JVM 21, JUnit (`kotlin.test`), Gradle, existing `ConsoleHttpTestClient` / `FakeIds` / `FakeLongs` / `FakeFixThisBridge` fixtures.

---

## File Structure

Create these new test files:

- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleSessionRoutesTest.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleHandoffRoutesTest.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleSessionsPollingContractTest.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleEtagRoutesTest.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleNavigationRoutesTest.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/fixtures/ConsoleRouteTestFixtures.kt`
- `docs/superpowers/work-notes/2026-05-14-console-test-decomposition-ledger.md`

Modify these existing files:

- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt` — shrink to only the 18 feedback-item route tests.
- `CONTRIBUTING.md` — update reference to the legacy test file name.
- `docs/reference/feedback-console-contract.md` — update reference if present.

## Conventions

- **No body rewrites.** Test method bodies are copied verbatim. Only import lists, package declarations, and class names are written fresh in each new file.
- **No `git mv` is required.** Every test extraction is implemented as: (a) write new file, (b) delete moved tests from legacy file, (c) commit. The legacy file keeps its history; new files start with the extraction commit as their first authorship.
- Each task ends with a commit. The commit message starts with `test(console):` and names the destination file.
- The full Gradle test count for package `io.beyondwin.fixthis.mcp.console.*` must equal the baseline at every checkpoint.
- The ledger from Task 1 is the source of truth; the final task verifies every ledger entry resolved to exactly one new file.

---

### Task 1: Capture Baseline Ledger

**Files:**
- Create: `docs/superpowers/work-notes/2026-05-14-console-test-decomposition-ledger.md`

- [ ] **Step 1: Capture the current test count**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.*" --info \
  2>&1 | tee /tmp/console-test-baseline.log
grep -cE " > .* PASSED" /tmp/console-test-baseline.log
```

Expected output (a single integer; record the value):

```
<BASELINE_TOTAL>
```

Also capture the per-class breakdown:

```bash
grep -E "ConsoleFeedbackItemRoutesTest > .* PASSED" /tmp/console-test-baseline.log \
  | wc -l
```

Expected: `100`.

- [ ] **Step 2: Enumerate test names and destinations**

Run, capturing to a temp file:

```bash
grep -nE "^    fun " \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt \
  | awk -F'fun ' '{print $2}' \
  | awk -F'(' '{print $1}' \
  > /tmp/console-test-names.txt
wc -l /tmp/console-test-names.txt
```

Expected: `100`.

- [ ] **Step 3: Write the ledger file**

Create `docs/superpowers/work-notes/2026-05-14-console-test-decomposition-ledger.md` with the following content (each entry produced by classifying the test name against the regex partition in §3.2 of the design spec):

```markdown
# Console Test Decomposition Ledger (2026-05-14)

Source: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
Baseline total: 100 `@Test` methods

| Test method | Destination file |
|---|---|
| itemPatchUpdatesDraftAnnotation | ConsoleFeedbackItemRoutesTest.kt |
| itemPatchUsesRequestedSessionWhenCurrentSessionChanged | ConsoleFeedbackItemRoutesTest.kt |
| batchSaveUsesRequestedSessionWhenCurrentSessionChanged | ConsoleFeedbackItemRoutesTest.kt |
| agentHandoffUsesRequestedSessionWhenCurrentSessionChanged | ConsoleHandoffRoutesTest.kt |
| consoleHtmlIncludesSessionPickerControls | ConsoleAssetContractTest.kt |
| consoleHtmlOmitsToolbarNavigationControls | ConsoleAssetContractTest.kt |
| consoleHtmlUsesBrowserStudioLayout | ConsoleAssetContractTest.kt |
| consoleHtmlKeepsStudioUsableInNarrowBrowser | ConsoleAssetContractTest.kt |
| consoleHtmlUsesModeAwareStudioInspector | ConsoleAssetContractTest.kt |
| consoleHtmlEditsSelectedAnnotationsAndFocusesComment | ConsoleAssetContractTest.kt |
| consoleHtmlResetsAnnotationComposerStateAcrossSessionActions | ConsoleAssetContractTest.kt |
| consoleHtmlKeepsFixThisTopLevelActionsInStudioTopbar | ConsoleAssetContractTest.kt |
| consoleHtmlAddsStudioKeyboardAndAccessibilityGuards | ConsoleAssetContractTest.kt |
| consoleHtmlDoesNotRenderSavedAnnotationPinsOnLivePreviewWithoutFocus | ConsoleAssetContractTest.kt |
| consoleHtmlRefreshesSessionSummariesAfterSavedItemDeleteOrEdit | ConsoleAssetContractTest.kt |
| consoleHtmlReplacesPlaceholderYouLabelWithScreensCount | ConsoleAssetContractTest.kt |
| consoleHtmlGroupsSavedAnnotationsByScreenInPanel | ConsoleAssetContractTest.kt |
| consoleHtmlComposerInspectorAlsoShowsSavedAnnotations | ConsoleAssetContractTest.kt |
| consoleHtmlNoLongerFiltersSentItemsFromInspector | ConsoleAssetContractTest.kt |
| consoleHtmlIncludesSelectionHandoffWorkspace | ConsoleAssetContractTest.kt |
| consoleHtmlDoesNotRenderInternalIdsInHumanLabels | ConsoleAssetContractTest.kt |
| consoleHtmlRendersStudioSessionHistoryWithoutInternalIds | ConsoleAssetContractTest.kt |
| consoleHtmlFlushesPendingAnnotationsBeforeSessionSwitch | ConsoleAssetContractTest.kt |
| consoleUsesOptionASelectAnnotateToolsAndSimpleLabels | ConsoleAssetContractTest.kt |
| consoleHtmlKeepsHiddenInspectorListsOutOfLayout | ConsoleAssetContractTest.kt |
| consoleHtmlShowsStartAnnotatingWhenSavedAnnotationsAreEmpty | ConsoleAssetContractTest.kt |
| consoleHtmlGivesBackToAnnotationsButtonButtonPadding | ConsoleAssetContractTest.kt |
| consoleHtmlCreatesHistorySessionBeforeAnnotatingFromEmptyState | ConsoleAssetContractTest.kt |
| consoleHtmlNoLongerFiltersReadyForAgentSessions | ConsoleAssetContractTest.kt |
| consoleHtmlRendersSavedAnnotationsWithSameListUiAfterSessionSwitch | ConsoleAssetContractTest.kt |
| consoleHtmlRendersOptionACanvasToolbar | ConsoleAssetContractTest.kt |
| consoleHtmlCountsActivePendingAnnotationsInHistory | ConsoleAssetContractTest.kt |
| consoleHtmlFocusesPendingItemWithoutDrawingUnnumberedSelectionOverlay | ConsoleAssetContractTest.kt |
| consoleHtmlImplementsSnapshotSelectionModes | ConsoleAssetContractTest.kt |
| consoleHtmlReportsNavigationCaptureErrors | ConsoleAssetContractTest.kt |
| consoleHtmlAnnotationSaveUsesCurrentSelectionPayload | ConsoleAssetContractTest.kt |
| savingDraftItemsAppendsOneScreenAndTwoItems | ConsoleFeedbackItemRoutesTest.kt |
| batchItemsApiReturnsConflictWhenLiveScreenFingerprintDiffersFromFrozenPreview | ConsoleFeedbackItemRoutesTest.kt |
| batchItemsApiReturnsFingerprintUnavailableHeaderWhenCurrentFingerprintIsMissing | ConsoleFeedbackItemRoutesTest.kt |
| batchItemsApiReturnsServerErrorWhenLiveRecaptureThrowsIllegalArgumentException | ConsoleFeedbackItemRoutesTest.kt |
| savingDraftItemsAllowsBlankCommentsForUnwrittenAnnotations | ConsoleFeedbackItemRoutesTest.kt |
| batchItemsApiReturnsBadRequestForEmptyItemList | ConsoleFeedbackItemRoutesTest.kt |
| batchItemsApiReturnsNotFoundForUnknownPreviewId | ConsoleFeedbackItemRoutesTest.kt |
| batchItemsApiReturnsBadRequestForInvalidPreviewTarget | ConsoleFeedbackItemRoutesTest.kt |
| previewSaveInProgressMapsToConflict | ConsoleFeedbackItemRoutesTest.kt |
| sessionApiDoesNotCreateSessionWhenHistoryIsEmpty | ConsoleSessionRoutesTest.kt |
| agentHandoffApiSendsDraftAndClearsDraftList | ConsoleHandoffRoutesTest.kt |
| agentHandoffApiReturnsConflictWhenNoDraftItemsExist | ConsoleHandoffRoutesTest.kt |
| clearDraftApiKeepsSentItems | ConsoleFeedbackItemRoutesTest.kt |
| itemsApiUsesCapturedNodeBoundsInsteadOfRequestBounds | ConsoleFeedbackItemRoutesTest.kt |
| itemsApiReturnsBadRequestForUnknownScreenId | ConsoleFeedbackItemRoutesTest.kt |
| itemsApiReturnsBadRequestForUnsupportedFields | ConsoleFeedbackItemRoutesTest.kt |
| itemsApiReturnsBadRequestForInvalidAreaBounds | ConsoleFeedbackItemRoutesTest.kt |
| sessionsApiListsWorkspaces | ConsoleSessionRoutesTest.kt |
| sessionsApiFiltersByPackageNameQuery | ConsoleSessionRoutesTest.kt |
| openSessionApiSwitchesCurrentSession | ConsoleSessionRoutesTest.kt |
| openSessionApiReturnsNotFoundForUnknownSessionId | ConsoleSessionRoutesTest.kt |
| sessionApiReturnsServerErrorForSessionSaveFailure | ConsoleSessionRoutesTest.kt |
| closeSessionApiClosesCurrentSession | ConsoleSessionRoutesTest.kt |
| closeSessionApiReturnsNotFoundForUnknownSessionId | ConsoleSessionRoutesTest.kt |
| navigationApiPerformsAction | ConsoleNavigationRoutesTest.kt |
| navigationApiRejectsUnknownAutomationFields | ConsoleNavigationRoutesTest.kt |
| deleteScreenApiDeletesScreenAndLinkedItems | ConsoleFeedbackItemRoutesTest.kt |
| apiSessionsResponseIncludesEtag | ConsoleEtagRoutesTest.kt |
| apiSessionsReturns304ForMatchingIfNoneMatch | ConsoleEtagRoutesTest.kt |
| apiSessionsEtagChangesAfterMutation | ConsoleEtagRoutesTest.kt |
| apiSessionResponseIncludesEtag | ConsoleEtagRoutesTest.kt |
| apiSessionReturns304ForMatchingIfNoneMatch | ConsoleEtagRoutesTest.kt |
| apiSessionWithoutCurrentReturns200NullAndNoEtag | ConsoleEtagRoutesTest.kt |
| historyPipsCollapseWorkingIntoOpen | ConsoleSessionsPollingContractTest.kt |
| historyPipDropsPointsLabel | ConsoleSessionsPollingContractTest.kt |
| consoleHtmlContainsSessionsPolling | ConsoleAssetContractTest.kt |
| consoleHtmlDeclaresPollingGlobals | ConsoleAssetContractTest.kt |
| saveToMcpToastMentionsAgentPickup | ConsoleHandoffRoutesTest.kt |
| promptActionsDoNotSilentlyDropUncommentedPendingAnnotations | ConsoleSessionsPollingContractTest.kt |
| mutationsAreWrappedInLock | ConsoleSessionsPollingContractTest.kt |
| mergeSessionIntoStatePreservesUserState | ConsoleSessionsPollingContractTest.kt |
| mergeSessionIntoStateSkipsHighlightOnBulkChange | ConsoleSessionsPollingContractTest.kt |
| startSessionsPollingIsCalledOnBoot | ConsoleSessionsPollingContractTest.kt |
| sessionsPollingDeclaresFailureBackoffConstants | ConsoleSessionsPollingContractTest.kt |
| pollSessionsTickResetsFailureCounterOnSuccess | ConsoleSessionsPollingContractTest.kt |
| pollSessionsTickIncrementsFailureCounterOnError | ConsoleSessionsPollingContractTest.kt |
| pollSessionsTickPausesAfterThreshold | ConsoleSessionsPollingContractTest.kt |
| visibilityChangeRecoversFromPolledFailure | ConsoleSessionsPollingContractTest.kt |
| withMutationLockRecoversFromPolledFailure | ConsoleSessionsPollingContractTest.kt |
| handoffPreviewEndpointReturnsMarkdownForRequestedItems | ConsoleHandoffRoutesTest.kt |
| handoffPreviewEndpointRejectsEmptyItemIds | ConsoleHandoffRoutesTest.kt |
| handoffPreviewEndpointReturns404ForUnknownSession | ConsoleHandoffRoutesTest.kt |
| handoffPreviewEndpointEmitsJsonErrorBody | ConsoleHandoffRoutesTest.kt |
| markHandedOffEndpointUpdatesLastHandedOffAtForItems | ConsoleHandoffRoutesTest.kt |
| markHandedOffEndpointRejectsEmptyItemIds | ConsoleHandoffRoutesTest.kt |
| markHandedOffEndpointReturns404ForUnknownSession | ConsoleHandoffRoutesTest.kt |
| markHandedOffEndpointRequiresConsoleToken | ConsoleHandoffRoutesTest.kt |
| agentHandoffsAcceptsItemIdsAndReturnsRenderedPrompt | ConsoleHandoffRoutesTest.kt |
| agentHandoffsRejectsLegacyPromptBody | ConsoleHandoffRoutesTest.kt |
| agentHandoffsRejectsEmptyItemIds | ConsoleHandoffRoutesTest.kt |
| agentHandoffsFlipsOnlySpecifiedItemIdsToSentLeavesOthersAsDraft | ConsoleHandoffRoutesTest.kt |
| sessionResponseIncludesStaleAfterHandoffFalseInitially | ConsoleHandoffRoutesTest.kt |
| sessionResponseStaleAfterHandoffTrueWhenUpdatedAfterSend | ConsoleHandoffRoutesTest.kt |
| sessionResponseStaleAfterHandoffFalseAfterReSave | ConsoleHandoffRoutesTest.kt |

Totals (must sum to 100):
- ConsoleFeedbackItemRoutesTest.kt: 18
- ConsoleSessionRoutesTest.kt: 7
- ConsoleHandoffRoutesTest.kt: 22
- ConsoleSessionsPollingContractTest.kt: 13
- ConsoleAssetContractTest.kt: 33
- ConsoleEtagRoutesTest.kt: 6
- ConsoleNavigationRoutesTest.kt: 2
```

- [ ] **Step 4: Verify ledger totals**

Run:

```bash
grep -E "^\| [a-z]" docs/superpowers/work-notes/2026-05-14-console-test-decomposition-ledger.md | wc -l
```

Expected: `100`.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/work-notes/2026-05-14-console-test-decomposition-ledger.md
git commit -m "$(cat <<'EOF'
test(console): record decomposition ledger for routes test split

Baseline: 100 @Test methods in ConsoleFeedbackItemRoutesTest.kt.
Each test mapped to one of 7 destination files.
EOF
)"
```

---

### Task 2: Add Shared Fixture Helper

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/fixtures/ConsoleRouteTestFixtures.kt`

- [ ] **Step 1: Write the failing helper-use test**

Edit the legacy `ConsoleFeedbackItemRoutesTest.kt` to replace the body of *only* the `itemPatchUpdatesDraftAnnotation` test setup with a call to the new helper. Replace lines 57–108 boilerplate with:

```kotlin
@Test
fun itemPatchUpdatesDraftAnnotation() {
    val fixture = newConsoleSessionFixture(
        clock = FakeLongs(100L, 200L, 300L, 400L).next,
        idGenerator = FakeIds("session-1", "item-1").next,
    )
    fixture.use { (service, store, server, _) ->
        val session = service.openSession(null, newSession = true)
        store.addScreen(
            session.sessionId,
            SnapshotDto(screenId = "screen-1", capturedAtEpochMillis = 100L, displayName = "Screen 1"),
        )
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = "screen-1",
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                comment = "Before",
            ),
        )
        val connection = ConsoleHttpTestClient(server.url).connection(
            "/api/items/item-1",
            method = "PUT",
            body = """{"comment":"After","status":"in_progress"}""",
        )

        assertEquals(200, connection.responseCode)
        val payload = fixThisJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
        val item = payload.getValue("items").jsonArray.single().jsonObject
        assertEquals("After", item.getValue("comment").jsonPrimitive.content)
        assertEquals("in_progress", item.getValue("status").jsonPrimitive.content)
        assertEquals("After", service.getSession("session-1").items.single().comment)
        assertEquals(AnnotationStatusDto.IN_PROGRESS, service.getSession("session-1").items.single().status)
    }
}
```

Add at the top of `ConsoleFeedbackItemRoutesTest.kt`:

```kotlin
import io.beyondwin.fixthis.mcp.fixtures.ConsoleRouteTestFixtures.newConsoleSessionFixture
```

Run:

```bash
./gradlew :fixthis-mcp:compileTestKotlin
```

Expected: **FAIL** with `Unresolved reference: ConsoleRouteTestFixtures`.

- [ ] **Step 2: Run to verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.itemPatchUpdatesDraftAnnotation" 2>&1 | tail -20
```

Expected: compilation failure.

- [ ] **Step 3: Implement the helper**

Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/fixtures/ConsoleRouteTestFixtures.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.fixtures

import io.beyondwin.fixthis.mcp.console.FeedbackConsoleServer
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.session.FixThisBridge
import java.nio.file.Files

data class ConsoleSessionFixture(
    val service: FeedbackSessionService,
    val store: FeedbackSessionStore,
    val server: FeedbackConsoleServer,
    val client: ConsoleHttpTestClient,
) : AutoCloseable {
    override fun close() {
        server.stop()
    }
}

object ConsoleRouteTestFixtures {
    fun newConsoleSessionFixture(
        clock: () -> Long = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
        idGenerator: () -> String = FakeIds("session-1", "item-1").next,
        bridge: FixThisBridge = FakeFixThisBridge(),
        defaultPackageName: String = "io.beyondwin.fixthis.sample",
        projectRoot: String = "/repo",
    ): ConsoleSessionFixture {
        val store = FeedbackSessionStore(clock = clock, idGenerator = idGenerator)
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = projectRoot,
            defaultPackageName = defaultPackageName,
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        val client = ConsoleHttpTestClient(server.url)
        return ConsoleSessionFixture(service, store, server, client)
    }

    fun newConsoleSessionFixtureWithTempRoot(
        prefix: String = "fixthis-routes-test",
        clock: () -> Long = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
        idGenerator: () -> String = FakeIds("session-1", "item-1").next,
        bridge: FixThisBridge = FakeFixThisBridge(),
        defaultPackageName: String = "io.beyondwin.fixthis.sample",
    ): ConsoleSessionFixture = newConsoleSessionFixture(
        clock = clock,
        idGenerator = idGenerator,
        bridge = bridge,
        defaultPackageName = defaultPackageName,
        projectRoot = Files.createTempDirectory(prefix).toString(),
    )
}
```

- [ ] **Step 4: Run to verify PASS**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleFeedbackItemRoutesTest.itemPatchUpdatesDraftAnnotation"
```

Expected: `BUILD SUCCESSFUL` with one test passed.

- [ ] **Step 5: Run the full console-package suite to confirm no regression**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.*" 2>&1 | tee /tmp/after-task2.log
grep -cE " > .* PASSED" /tmp/after-task2.log
```

Expected: same baseline integer as Task 1 Step 1.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/fixtures/ConsoleRouteTestFixtures.kt \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
git commit -m "$(cat <<'EOF'
test(console): add ConsoleRouteTestFixtures helper

Single factory removes inline FeedbackSessionStore/Service/Server boilerplate
that is duplicated across every console route test.
EOF
)"
```

---

### Task 3: Extract Navigation Routes Tests

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleNavigationRoutesTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`

- [ ] **Step 1: Write the failing assertion**

Run this preflight to confirm the two navigation tests are still in the legacy file:

```bash
grep -nE "fun navigationApi" \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
```

Expected output:

```
2082:    fun navigationApiPerformsAction() {
2111:    fun navigationApiRejectsUnknownAutomationFields() {
```

If either line number drifts but both functions appear, proceed (the line numbers in the rest of this plan are derived from a 2897-line snapshot; the *function presence* is what matters).

- [ ] **Step 2: Create the new test file**

Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleNavigationRoutesTest.kt` with the package + imports + the two `@Test` functions copied verbatim from the legacy file. Use this scaffold:

```kotlin
package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.beyondwin.fixthis.mcp.fixtures.FakeLongs
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationAction
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

private fun ConsoleHttpTestClient.getJsonObject(path: String): JsonObject = fixThisJson
    .parseToJsonElement(get(path))
    .jsonObject

class ConsoleNavigationRoutesTest {
    @Test
    fun navigationApiPerformsAction() {
        // (paste verbatim body of legacy navigationApiPerformsAction)
    }

    @Test
    fun navigationApiRejectsUnknownAutomationFields() {
        // (paste verbatim body of legacy navigationApiRejectsUnknownAutomationFields)
    }
}
```

Important: the bodies inside `// (paste verbatim ...)` are copied byte-for-byte from the legacy file. Do not rewrite, do not "improve", do not introduce the new helper here — that comes in Task 9.

- [ ] **Step 3: Run to verify PASS on new file**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleNavigationRoutesTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 4: Delete the two functions from the legacy file**

Open `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt` and delete:

- The `@Test` annotation and `fun navigationApiPerformsAction()` function (the original block currently spanning lines 2081–2109).
- The `@Test` annotation and `fun navigationApiRejectsUnknownAutomationFields()` function (the original block currently spanning lines 2110–2144).

Save the file.

- [ ] **Step 5: Run the full console-package suite**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.*" 2>&1 | tee /tmp/after-task3.log
grep -cE " > .* PASSED" /tmp/after-task3.log
```

Expected: same baseline integer from Task 1 (unchanged total).

```bash
grep -cE "ConsoleFeedbackItemRoutesTest > .* PASSED" /tmp/after-task3.log
```

Expected: `98` (decreased by exactly 2).

```bash
grep -cE "ConsoleNavigationRoutesTest > .* PASSED" /tmp/after-task3.log
```

Expected: `2`.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleNavigationRoutesTest.kt \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
git commit -m "$(cat <<'EOF'
test(console): extract ConsoleNavigationRoutesTest (2 tests)

Aligns with already-decomposed sibling files
(ConsoleAssetRoutesTest, ConsoleConnectionRoutesTest, ConsolePreviewRoutesTest).
EOF
)"
```

---

### Task 4: Extract ETag Routes Tests

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleEtagRoutesTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`

- [ ] **Step 1: Identify the 6 ETag tests**

```bash
grep -nE "fun (apiSessions|apiSession)" \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
```

Expected (function names — line numbers may drift slightly):

```
fun apiSessionsResponseIncludesEtag(
fun apiSessionsReturns304ForMatchingIfNoneMatch(
fun apiSessionsEtagChangesAfterMutation(
fun apiSessionResponseIncludesEtag(
fun apiSessionReturns304ForMatchingIfNoneMatch(
fun apiSessionWithoutCurrentReturns200NullAndNoEtag(
```

- [ ] **Step 2: Create the new test file with verbatim bodies**

Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleEtagRoutesTest.kt` using the same scaffold pattern as Task 3 Step 2 (package, the imports actually referenced by these 6 tests, one `class ConsoleEtagRoutesTest` containing all 6 functions copied verbatim).

- [ ] **Step 3: Run new file only**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleEtagRoutesTest"
```

Expected: 6 tests passed.

- [ ] **Step 4: Delete the 6 functions from the legacy file**

Remove each `@Test`/`fun` block byte-for-byte from `ConsoleFeedbackItemRoutesTest.kt`.

- [ ] **Step 5: Run the full suite + per-class counts**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.*" 2>&1 | tee /tmp/after-task4.log
grep -cE " > .* PASSED" /tmp/after-task4.log                            # baseline
grep -cE "ConsoleFeedbackItemRoutesTest > .* PASSED" /tmp/after-task4.log   # 92
grep -cE "ConsoleEtagRoutesTest > .* PASSED" /tmp/after-task4.log           # 6
```

Expected: baseline unchanged; legacy = 92; new = 6.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleEtagRoutesTest.kt \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
git commit -m "$(cat <<'EOF'
test(console): extract ConsoleEtagRoutesTest (6 tests)

Aligns with ConsoleHttpEtagTest naming pattern; covers /api/sessions and
/api/session ETag round-trips.
EOF
)"
```

---

### Task 5: Extract Sessions Polling Contract Tests

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleSessionsPollingContractTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`

- [ ] **Step 1: Identify the 13 contract tests**

```bash
grep -nE "fun (pollSessionsTick|sessionsPollingDeclaresFailureBackoffConstants|startSessionsPollingIsCalledOnBoot|visibilityChangeRecoversFromPolledFailure|withMutationLockRecoversFromPolledFailure|mutationsAreWrappedInLock|mergeSessionIntoState|historyPip|promptActionsDoNotSilentlyDropUncommentedPendingAnnotations)" \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt | wc -l
```

Expected: `13`.

- [ ] **Step 2: Create the new test file**

Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleSessionsPollingContractTest.kt` with a KDoc header explaining the file's purpose:

```kotlin
/**
 * JS-source contract tests for the sessions-polling subsystem.
 *
 * These tests read the bundled `console/app.js` resource as a UTF-8 string and
 * assert that specific function names and literal constants exist in expected
 * positions. They do not exercise HTTP routes.
 *
 * Source modules under test:
 * - fixthis-mcp/src/main/console/sessions-polling.js (poll loop, backoff,
 *   visibility recovery)
 * - fixthis-mcp/src/main/console/state.js (withMutationLock,
 *   mergeSessionIntoState helpers)
 * - fixthis-mcp/src/main/console/history.js (historyPip rendering)
 * - fixthis-mcp/src/main/console/prompt.js (promptActions guard)
 *
 * If a bundle-optimization or state-machine refactor renames any of these
 * symbols, update the assertions here in lockstep.
 */
package io.beyondwin.fixthis.mcp.console
// ... imports ...

class ConsoleSessionsPollingContractTest {
    // 13 @Test functions copied verbatim from legacy file
}
```

- [ ] **Step 3: Run new file only**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleSessionsPollingContractTest"
```

Expected: 13 tests passed.

- [ ] **Step 4: Delete the 13 functions from the legacy file**

- [ ] **Step 5: Verify totals**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.*" 2>&1 | tee /tmp/after-task5.log
grep -cE " > .* PASSED" /tmp/after-task5.log                                  # baseline
grep -cE "ConsoleFeedbackItemRoutesTest > .* PASSED" /tmp/after-task5.log     # 79
grep -cE "ConsoleSessionsPollingContractTest > .* PASSED" /tmp/after-task5.log  # 13
```

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleSessionsPollingContractTest.kt \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
git commit -m "$(cat <<'EOF'
test(console): extract ConsoleSessionsPollingContractTest (13 tests)

Polling, mutation lock, merge helpers, history pips, prompt guard.
These are JS-source string assertions, not HTTP route tests.
EOF
)"
```

---

### Task 6: Extract Session Routes Tests

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleSessionRoutesTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`

- [ ] **Step 1: Identify the 7 session tests**

```bash
grep -nE "fun (sessionsApi(Lists|Filters)|openSessionApi|closeSessionApi|sessionApi(Returns|Does))" \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt | wc -l
```

Expected: `7`.

- [ ] **Step 2: Create the new test file**

Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleSessionRoutesTest.kt` containing the 7 functions verbatim.

- [ ] **Step 3: Run new file only**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleSessionRoutesTest"
```

Expected: 7 tests passed.

- [ ] **Step 4: Delete the 7 functions from the legacy file**

- [ ] **Step 5: Verify totals**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.*" 2>&1 | tee /tmp/after-task6.log
grep -cE " > .* PASSED" /tmp/after-task6.log                              # baseline
grep -cE "ConsoleFeedbackItemRoutesTest > .* PASSED" /tmp/after-task6.log # 72
grep -cE "ConsoleSessionRoutesTest > .* PASSED" /tmp/after-task6.log      # 7
```

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleSessionRoutesTest.kt \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
git commit -m "$(cat <<'EOF'
test(console): extract ConsoleSessionRoutesTest (7 tests)

Mirrors fixthis-mcp/src/main/kotlin/.../console/SessionRoutes.kt.
EOF
)"
```

---

### Task 7: Extract Handoff Routes Tests

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleHandoffRoutesTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`

- [ ] **Step 1: Identify the 22 handoff tests**

```bash
grep -nE "fun (agentHandoff|handoffPreviewEndpoint|markHandedOffEndpoint|sessionResponseStaleAfterHandoff|sessionResponseIncludesStaleAfterHandoff|saveToMcpToast)" \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt | wc -l
```

Expected: `22`.

- [ ] **Step 2: Create the new test file**

Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleHandoffRoutesTest.kt` containing the 22 functions verbatim.

Because some handoff tests use a temp-directory project root, ensure these imports survive the copy:

```kotlin
import java.nio.file.Files
```

- [ ] **Step 3: Run new file only**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleHandoffRoutesTest"
```

Expected: 22 tests passed.

- [ ] **Step 4: Delete the 22 functions from the legacy file**

- [ ] **Step 5: Verify totals**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.*" 2>&1 | tee /tmp/after-task7.log
grep -cE " > .* PASSED" /tmp/after-task7.log                              # baseline
grep -cE "ConsoleFeedbackItemRoutesTest > .* PASSED" /tmp/after-task7.log # 50
grep -cE "ConsoleHandoffRoutesTest > .* PASSED" /tmp/after-task7.log      # 22
```

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleHandoffRoutesTest.kt \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
git commit -m "$(cat <<'EOF'
test(console): extract ConsoleHandoffRoutesTest (22 tests)

Covers /api/agent/handoff, /api/handoff/preview, /api/items/handed-off,
and the staleAfterHandoff session response fields.
EOF
)"
```

---

### Task 8: Extract Asset Contract Tests

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`

- [ ] **Step 1: Identify the 33 asset tests**

```bash
grep -nE "fun (consoleHtml|consoleUsesOptionASelectAnnotateToolsAndSimpleLabels)" \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt | wc -l
```

Expected: `33`.

- [ ] **Step 2: Create the new test file with KDoc**

Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt`:

```kotlin
/**
 * Asset contract tests for the served console payload.
 *
 * These tests load the served HTML/JS via FeedbackConsoleAssets (the same path
 * the browser hits) and assert that:
 *  - DOM element IDs the JS expects exist in the rendered HTML.
 *  - JS function names / literal strings appear in the bundled app.js.
 *  - CSS class names referenced by the JS exist in the bundled CSS.
 *
 * Running in isolation: a fresh `node scripts/build-console-assets.mjs` must
 * have been run since the last edit to `fixthis-mcp/src/main/console/*.js`.
 * See CONTRIBUTING.md for the full local-checks recipe.
 */
package io.beyondwin.fixthis.mcp.console
// ... imports ...

class ConsoleAssetContractTest {
    // 33 @Test functions copied verbatim
}
```

- [ ] **Step 3: Run new file only**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleAssetContractTest"
```

Expected: 33 tests passed.

- [ ] **Step 4: Delete the 33 functions from the legacy file**

- [ ] **Step 5: Verify totals**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.*" 2>&1 | tee /tmp/after-task8.log
grep -cE " > .* PASSED" /tmp/after-task8.log                              # baseline
grep -cE "ConsoleFeedbackItemRoutesTest > .* PASSED" /tmp/after-task8.log # 17 + 1 from Task 2 helper-use
grep -cE "ConsoleAssetContractTest > .* PASSED" /tmp/after-task8.log      # 33
```

(The legacy file should now hold the 18 feedback-item route tests.)

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
git commit -m "$(cat <<'EOF'
test(console): extract ConsoleAssetContractTest (33 tests)

consoleHtml*/consoleUsesOptionASelect* tests assert against the served
HTML/JS payload, not against any route. Aligns naming with
ConsoleAssetRoutesTest (which tests the asset routes themselves).
EOF
)"
```

---

### Task 9: Slim Down Legacy File and Apply Helper

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`

- [ ] **Step 1: Verify only 18 tests remain**

```bash
grep -cE "^    @Test" \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
```

Expected: `18`.

- [ ] **Step 2: Prune imports**

Remove imports that are no longer referenced in the file. The remaining 18 tests only need:

```kotlin
package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.beyondwin.fixthis.mcp.fixtures.ConsoleRouteTestFixtures.newConsoleSessionFixture
import io.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.beyondwin.fixthis.mcp.fixtures.FakeLongs
import io.beyondwin.fixthis.mcp.fixtures.NullableSequencedFingerprintBridge
import io.beyondwin.fixthis.mcp.fixtures.SecondCaptureIllegalArgumentBridge
import io.beyondwin.fixthis.mcp.fixtures.SequencedFingerprintBridge
import io.beyondwin.fixthis.mcp.fixtures.javascriptFunctionBody
import io.beyondwin.fixthis.mcp.fixtures.seedSessionWithOneItem
import io.beyondwin.fixthis.mcp.session.AnnotationDto
import io.beyondwin.fixthis.mcp.session.AnnotationStatusDto
import io.beyondwin.fixthis.mcp.session.AnnotationTargetDto
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
```

Drop any import not actually referenced by the remaining 18 test bodies (use the compiler — it will flag unused imports as warnings).

- [ ] **Step 3: Verify file size**

```bash
wc -l fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
```

Expected: ≤ `800` lines.

- [ ] **Step 4: Run the full suite one last time**

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.*" 2>&1 | tee /tmp/after-task9.log
grep -cE " > .* PASSED" /tmp/after-task9.log
```

Expected: baseline integer from Task 1.

- [ ] **Step 5: Run the ledger completeness check**

```bash
while IFS='|' read -r _ name dest _; do
  name=$(echo "$name" | xargs)
  [ -z "$name" ] && continue
  [ "$name" = "Test method" ] && continue
  count=$(grep -rE "fun ${name}\(" \
    fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ | wc -l)
  if [ "$count" -ne 1 ]; then
    echo "DUPLICATE or MISSING: $name (found $count)"
  fi
done < docs/superpowers/work-notes/2026-05-14-console-test-decomposition-ledger.md
```

Expected: no output.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt
git commit -m "$(cat <<'EOF'
test(console): trim ConsoleFeedbackItemRoutesTest to 18 route tests

File now mirrors FeedbackItemRoutes.kt: item PATCH, batch save, items API,
clearDraft, deleteScreen, previewSaveInProgress.
EOF
)"
```

---

### Task 10: Documentation Sync

**Files:**
- Modify: `CONTRIBUTING.md`
- Modify: `docs/reference/feedback-console-contract.md`

- [ ] **Step 1: Search for legacy file references**

```bash
grep -rn "ConsoleFeedbackItemRoutesTest" \
  CONTRIBUTING.md docs/ 2>&1 | head -20
```

Capture every hit.

- [ ] **Step 2: Update each reference**

For each hit, replace single-file references with the new partition:

In `CONTRIBUTING.md`, if a line reads `ConsoleFeedbackItemRoutesTest` in a context like "run console tests", change it to:

```
io.beyondwin.fixthis.mcp.console.*  # covers all decomposed console route tests
```

Add a one-sentence note in the "Required local checks" section:

```markdown
> As of 2026-05-14, `ConsoleFeedbackItemRoutesTest.kt` was split into seven
> focused files. If you previously pinned that class in your IDE run
> configurations, switch to the package filter
> `io.beyondwin.fixthis.mcp.console.*`.
```

In `docs/reference/feedback-console-contract.md`, if any "see X test" cross-reference exists, point it at the appropriate new file per §3.2 of the design spec.

- [ ] **Step 3: Verify no stale references**

```bash
grep -rn "ConsoleFeedbackItemRoutesTest" CONTRIBUTING.md docs/ \
  | grep -v "2026-05-14-console-routes-test-decomposition" \
  | grep -v "Trim ConsoleFeedbackItemRoutesTest" \
  | grep -v "split into"
```

Expected: no output (only references inside the new spec/plan and the changelog note remain).

- [ ] **Step 4: Run the full Gradle matrix one final time**

```bash
./gradlew :fixthis-mcp:test
./gradlew check
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add CONTRIBUTING.md docs/reference/feedback-console-contract.md
git commit -m "$(cat <<'EOF'
docs(console): point at decomposed routes test files

Updates CONTRIBUTING.md and feedback-console-contract.md cross-references
to the seven post-split test files.
EOF
)"
```

---

## Self-Review Checklist

- [ ] All 100 baseline tests appear in exactly one new/legacy file (Task 9 Step 5).
- [ ] Gradle reports the same total console-package test count at every checkpoint (Tasks 3–9 Step 5).
- [ ] No test body was rewritten; only imports and class names are fresh (Task 8 commit description).
- [ ] The shared helper is in `fixtures/`, not in any test class (Task 2 Step 3).
- [ ] Every new file is ≤ 800 lines (Task 9 Step 3).
- [ ] Cross-cutting JS-source tests live in `ConsoleSessionsPollingContractTest.kt`, asset-payload tests in `ConsoleAssetContractTest.kt` (design spec §3.1).
- [ ] Documentation references updated (Task 10 Step 3).
- [ ] Total commit count for this plan: 10 (one per task).
- [ ] No `--no-verify`, no `git rebase`, no `git push --force` anywhere.
