# Annotation Screen Mismatch Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the FixThis console so that after annotating screen 1 and moving the device to screen 2, the live preview correctly shows screen 2 with no annotation overlays; clicking a saved annotation swaps the preview to that annotation's saved screenshot with only that screen's pins; deselecting returns to the live preview with no overlays.

**Architecture:** Two logic changes in JavaScript source modules (`preview.js`, `rendering.js`), mirrored verbatim in the compiled bundle `app.js`. `latestScreen()` gains a focused-annotation branch and reorders the live preview ahead of `latestPersistedScreen()` so the live feed is no longer blocked. `renderSelectionOverlay()` stops drawing saved annotation pins unconditionally and instead draws only the focused annotation's screen's pins. Three existing Kotlin structural assertions in `FeedbackConsoleServerTest.kt` are updated first (TDD) so they describe the new contract.

**Tech Stack:** JavaScript (browser console), Kotlin/JUnit 5 (asset structural tests), Gradle 8.

**Background:**
- `app.js` (the served bundle) is a verbatim concatenation of source modules from `fixthis-mcp/src/main/console/`, joined as `// <filename>\n<content trimEnd()>\n`. The order is fixed (`state.js`, `api.js`, `connection.js`, `devices.js`, `preview.js`, `annotations.js`, `history.js`, `prompt.js`, `rendering.js`, `shortcuts.js`, `main.js`). The test `generatedConsoleAppMatchesConsoleSourceModules` enforces that the bundle exactly matches this concatenation. Any source change must therefore be mirrored in `app.js`.
- `focusedSavedItemId` is the existing module-level `let` (declared in `state.js`) that tracks which saved annotation is currently selected. `clearSelection()` and `focusPendingFeedbackItem()` already null it out and call `renderSelectionOverlay()` / `renderPreviewOnly()`.
- `screenImageUrl(screen)` already returns `/api/screens/{screenId}/screenshot/full` when the passed-in `screen` is not the live `state.preview.screen` reference, so swapping `latestScreen()` to a screen pulled from `state.session.screens` is sufficient to swap the displayed image. That endpoint is implemented in `ArtifactRoutes.kt`.

---

## File Map

| File | Change |
|------|--------|
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt` | Update 3 assertions (lines ~1608, ~1611, ~1760) so they fail until the source/bundle changes land |
| `fixthis-mcp/src/main/console/preview.js` | Rewrite `latestScreen()` body (lines 87–89) |
| `fixthis-mcp/src/main/console/rendering.js` | Replace unconditional saved-overlay block (lines 73–76) with `focusedSavedItemId` + `screenId` filter |
| `fixthis-mcp/src/main/resources/console/app.js` | Mirror both source changes verbatim |

No production Kotlin files change. No new state, no new endpoints, no server changes.

---

## Task 1: Update Kotlin structural tests to reflect new behavior (TDD — failing first)

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

The three existing assertions lock in the buggy behavior. Update them now so they describe the corrected contract. They will fail (and the bundle-equality test will pass) until Tasks 2–4 are complete.

### Step 1.1: Update `latestScreen()` content assertion (line ~1608)

Inside test `consoleHtmlKeepsFrozenPreviewStableAndShowsPersistedScreenHistory`, find:

```kotlin
        assertTrue(html.contains("return addItemsFlow?.screen || latestPersistedScreen() || state.preview?.screen;"))
```

Replace with:

```kotlin
        assertTrue(html.contains("function latestScreen()"))
        assertTrue(html.contains("if (addItemsFlow) return addItemsFlow.screen;"))
        assertTrue(html.contains("if (focusedSavedItemId) {"))
        assertTrue(html.contains("return state.preview?.screen || latestPersistedScreen();"))
        assertFalse(html.contains("return addItemsFlow?.screen || latestPersistedScreen() || state.preview?.screen;"))
```

### Step 1.2: Update saved-overlay assertion in the same test (line ~1611)

Inside the same test, find:

```kotlin
        assertTrue(html.contains("const persistedItems = savedEvidenceItems();"))
        assertTrue(html.contains("renderSavedEvidenceOverlay(overlay, image, persistedItems);"))
```

Replace with:

```kotlin
        assertTrue(html.contains("if (!addItemsFlow && focusedSavedItemId)"))
        assertTrue(html.contains("savedEvidenceItems().filter(item => item.screenId === focusedItem.screenId)"))
        assertTrue(html.contains("renderSavedEvidenceOverlay(overlay, image, sameScreenItems);"))
        assertFalse(html.contains("renderSavedEvidenceOverlay(overlay, image, persistedItems);"))
```

(Note: the `const persistedItems = savedEvidenceItems();` line still appears elsewhere — `savedEvidenceItems()` is reused — so we drop the assertion that requires it specifically inside `renderSelectionOverlay()`. The `assertFalse` on `renderSavedEvidenceOverlay(overlay, image, persistedItems);` is what proves the unconditional call is gone. The assertion `assertTrue(html.contains("const persistedItems = savedEvidenceItems();"))` on line 1633 inside `consoleHtmlKeepsSavedAnnotationPreviewsInCenterDeviceOnly` continues to hold because `renderSavedEvidenceGroups()` and other call sites still use that local.)

If after running tests `consoleHtmlKeepsSavedAnnotationPreviewsInCenterDeviceOnly` (line ~1633) fails because the literal `const persistedItems = savedEvidenceItems();` no longer appears anywhere in the bundle, replace that single line with:

```kotlin
        assertTrue(html.contains("savedEvidenceItems()"))
```

In the current source `renderSavedEvidenceGroups()` already references `const items = savedEvidenceItems();`, so the more specific `persistedItems` literal may disappear after Task 3. Verify after Task 4.

### Step 1.3: Update `latestScreen()` Regex assertion (line ~1760)

Inside test `consoleHtmlResetsPreviewContextOnSessionOrDeviceChange`, find:

```kotlin
        assertTrue(Regex("function latestScreen\\(\\) \\{\\s+return addItemsFlow\\?\\.screen \\|\\| latestPersistedScreen\\(\\) \\|\\| state\\.preview\\?\\.screen;\\s+\\}").containsMatchIn(html))
```

Replace with:

```kotlin
        assertTrue(Regex("function latestScreen\\(\\) \\{\\s+if \\(addItemsFlow\\) return addItemsFlow\\.screen;").containsMatchIn(html))
        assertTrue(Regex("if \\(focusedSavedItemId\\) \\{[\\s\\S]*?const focusedItem = savedEvidenceItems\\(\\)\\.find\\(item => item\\.itemId === focusedSavedItemId\\);").containsMatchIn(html))
        assertTrue(html.contains("return state.preview?.screen || latestPersistedScreen();"))
```

### Step 1.4: Run the failing tests

```bash
./gradlew :fixthis-mcp:test \
  --tests "io.github.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.consoleHtmlKeepsFrozenPreviewStableAndShowsPersistedScreenHistory" \
  --tests "io.github.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.consoleHtmlKeepsSavedAnnotationPreviewsInCenterDeviceOnly" \
  --tests "io.github.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.consoleHtmlResetsPreviewContextOnSessionOrDeviceChange" 2>&1 | tail -40
```

**Expected output (excerpt):**

```
> Task :fixthis-mcp:test FAILED
FeedbackConsoleServerTest > consoleHtmlKeepsFrozenPreviewStableAndShowsPersistedScreenHistory FAILED
    org.opentest4j.AssertionFailedError at FeedbackConsoleServerTest.kt:...
FeedbackConsoleServerTest > consoleHtmlResetsPreviewContextOnSessionOrDeviceChange FAILED
    org.opentest4j.AssertionFailedError at FeedbackConsoleServerTest.kt:...
3 tests completed, 2 failed
```

(`consoleHtmlKeepsSavedAnnotationPreviewsInCenterDeviceOnly` may pass or fail at this stage depending on whether you adjusted the `persistedItems` literal in Step 1.2; either way, it must pass after Task 4.)

If all three tests pass at this point, the asserts were not actually changed — re-check Step 1.1–1.3.

---

## Task 2: Fix `latestScreen()` in `preview.js`

**Files:**
- Modify: `fixthis-mcp/src/main/console/preview.js` (lines 87–89)

### Step 2.1: Replace the body of `latestScreen()`

Find in `preview.js` (lines 87–89, exact 4-space + 12-space indentation as in the file):

```js
            function latestScreen() {
              return addItemsFlow?.screen || latestPersistedScreen() || state.preview?.screen;
            }
```

Replace with:

```js
            function latestScreen() {
              if (addItemsFlow) return addItemsFlow.screen;
              if (focusedSavedItemId) {
                const focusedItem = savedEvidenceItems().find(item => item.itemId === focusedSavedItemId);
                if (focusedItem) {
                  const focusedScreen = (state.session?.screens || []).find(s => s.screenId === focusedItem.screenId);
                  if (focusedScreen) return focusedScreen;
                }
              }
              return state.preview?.screen || latestPersistedScreen();
            }
```

Branch semantics:
- `addItemsFlow` — annotate flow active: keep returning the frozen capture screen.
- `focusedSavedItemId` — user clicked a saved pin: return that annotation's screen out of `state.session.screens` so `screenImageUrl()` switches to the saved screenshot.
- Default: live preview first; `latestPersistedScreen()` is now only the last-resort fallback (e.g. fresh page load before the first poll completes).

### Step 2.2: Confirm structure (no test run yet — still need bundle update)

```bash
grep -n "function latestScreen" /Users/kws/source/android/FixThis/fixthis-mcp/src/main/console/preview.js
```

**Expected output:**

```
87:            function latestScreen() {
```

(Line number may shift slightly after the rewrite; presence of one match is what matters.)

---

## Task 3: Fix `renderSelectionOverlay()` in `rendering.js`

**Files:**
- Modify: `fixthis-mcp/src/main/console/rendering.js` (lines 73–76)

### Step 3.1: Replace unconditional saved-overlay block

Find in `rendering.js` (lines 73–76, exact indentation):

```js
              if (!addItemsFlow) {
                const persistedItems = savedEvidenceItems();
                if (persistedItems.length) renderSavedEvidenceOverlay(overlay, image, persistedItems);
              }
```

Replace with:

```js
              if (!addItemsFlow && focusedSavedItemId) {
                const focusedItem = savedEvidenceItems().find(item => item.itemId === focusedSavedItemId);
                if (focusedItem) {
                  const sameScreenItems = savedEvidenceItems().filter(item => item.screenId === focusedItem.screenId);
                  renderSavedEvidenceOverlay(overlay, image, sameScreenItems);
                }
              }
```

Semantics:
- Without a focused saved annotation we never overlay saved pins on the live preview — that was the cross-screen leak.
- When focused, we overlay only items whose `screenId` matches the focused item's, so we never paint screen-1 pins onto a screen-2 image even briefly.

---

## Task 4: Mirror both changes into `app.js` bundle

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/app.js`

The bundle is a verbatim concatenation of `state.js`, `api.js`, `connection.js`, `devices.js`, `preview.js`, `annotations.js`, `history.js`, `prompt.js`, `rendering.js`, `shortcuts.js`, `main.js`, separated by `// <filename>\n<content trimEnd()>\n`. The test `generatedConsoleAppMatchesConsoleSourceModules` (`FeedbackConsoleServerTest.kt:474–499`) enforces byte-for-byte equality, so both edits must be applied identically.

### Step 4.1: Mirror `latestScreen()` fix in `app.js`

In `app.js`, search for the `// preview.js` header, then for `function latestScreen`. Find:

```js
            function latestScreen() {
              return addItemsFlow?.screen || latestPersistedScreen() || state.preview?.screen;
            }
```

Replace with:

```js
            function latestScreen() {
              if (addItemsFlow) return addItemsFlow.screen;
              if (focusedSavedItemId) {
                const focusedItem = savedEvidenceItems().find(item => item.itemId === focusedSavedItemId);
                if (focusedItem) {
                  const focusedScreen = (state.session?.screens || []).find(s => s.screenId === focusedItem.screenId);
                  if (focusedScreen) return focusedScreen;
                }
              }
              return state.preview?.screen || latestPersistedScreen();
            }
```

### Step 4.2: Mirror `renderSelectionOverlay()` fix in `app.js`

In `app.js`, search for the `// rendering.js` header, then for `function renderSelectionOverlay`. Find:

```js
              if (!addItemsFlow) {
                const persistedItems = savedEvidenceItems();
                if (persistedItems.length) renderSavedEvidenceOverlay(overlay, image, persistedItems);
              }
```

Replace with:

```js
              if (!addItemsFlow && focusedSavedItemId) {
                const focusedItem = savedEvidenceItems().find(item => item.itemId === focusedSavedItemId);
                if (focusedItem) {
                  const sameScreenItems = savedEvidenceItems().filter(item => item.screenId === focusedItem.screenId);
                  renderSavedEvidenceOverlay(overlay, image, sameScreenItems);
                }
              }
```

### Step 4.3: Verify bundle matches sources

```bash
./gradlew :fixthis-mcp:test \
  --tests "io.github.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.generatedConsoleAppMatchesConsoleSourceModules" 2>&1 | tail -20
```

**Expected output:**

```
BUILD SUCCESSFUL in <n>s
1 actionable task: executed
```

If this test FAILS, the assertion error prints the first differing line of the concatenation. Fix the corresponding region in `app.js` until equality holds — do not edit the test.

---

## Task 5: Run full test suite and commit

### Step 5.1: Run the focused console suite

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest" 2>&1 | tail -40
```

**Expected output:**

```
BUILD SUCCESSFUL in <n>s
> Task :fixthis-mcp:test
... (all tests in FeedbackConsoleServerTest passing) ...
```

If any other test in this class checks for `latestScreen()` or `renderSelectionOverlay()` content with the old strings, the failure stack will pinpoint it. Update the assertion the same way as in Task 1 (replace literal expectations with the new strings) — do not weaken assertions just to make them pass.

### Step 5.2: Run the full project test suite

```bash
./gradlew :fixthis-mcp:test 2>&1 | tail -20
```

**Expected output:**

```
BUILD SUCCESSFUL in <n>s
```

### Step 5.3: Commit

```bash
git add fixthis-mcp/src/main/console/preview.js \
        fixthis-mcp/src/main/console/rendering.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "fix(console): show live preview after screen change, only overlay focused annotation's screen"
```

---

## Self-Review

**Spec coverage:**
- Default state shows live preview — `latestScreen()` returns `state.preview?.screen` first.
- No saved overlays on live preview — `renderSelectionOverlay()` guarded by `focusedSavedItemId`.
- Focused saved annotation shows its screen's screenshot — `latestScreen()` returns `focusedScreen` from `state.session.screens`; `screenImageUrl()` falls through to `/api/screens/{id}/screenshot/full` because the reference comparison `state.preview?.screen === screen` is false for a screen object pulled from `state.session.screens`.
- Only that screen's pins drawn — `sameScreenItems` filtered by `item.screenId === focusedItem.screenId`.
- Deselect returns to live preview — both `clearSelection()` and `focusPendingFeedbackItem()` already null out `focusedSavedItemId` and trigger `renderSelectionOverlay()` / `renderPreviewOnly()`, which cascade through the corrected `latestScreen()`.
- `latestPersistedScreen()` retained as a last-resort fallback for fresh page load before first poll.
- `annotations.js` not modified — `focusSavedEvidenceItem()` already chains into `renderPreviewOnly()` → `renderPreviewRegion()` → `latestScreen()`.
- `app.js` bundle equality preserved — `generatedConsoleAppMatchesConsoleSourceModules` enforces it.

**Placeholder scan:** No TBDs/TODOs; every find/replace block is concrete.

**Type consistency:**
- `focusedSavedItemId` — module-level `let`, `string | null`.
- `savedEvidenceItems()` — `SessionItem[]` with `itemId: string`, `screenId: string`.
- `state.session?.screens` — `Screen[]` with `screenId: string`.
- `renderSavedEvidenceOverlay(overlay, image, items)` — signature unchanged.
