# Annotation Screen Mismatch Fix — Implementation Details

**Date:** 2026-05-09
**Status:** Implementation reference (companion to `2026-05-09-annotation-screen-mismatch-fix-design.md` and `plans/2026-05-09-annotation-screen-mismatch-fix.md`)
**Primary modules:** `fixthis-mcp/src/main/console/`, `fixthis-mcp/src/main/resources/console/app.js`, `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

This document is a deep technical companion to the plan. Read it before editing if you want to understand exactly *why* each line changes and *why* nothing else needs to.

## 1. How `app.js` is built

The browser-side console is split across small JavaScript source modules under `fixthis-mcp/src/main/console/`. The deployable artifact is a single bundle at `fixthis-mcp/src/main/resources/console/app.js`. There is no JS toolchain — the bundle is a verbatim concatenation of the source modules, in a fixed order, with one separator format.

The contract is enforced by the JUnit 5 test `generatedConsoleAppMatchesConsoleSourceModules` in `FeedbackConsoleServerTest.kt:474–499`:

```kotlin
@Test
fun generatedConsoleAppMatchesConsoleSourceModules() {
    val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }
    val sourceDir = File(root, "fixthis-mcp/src/main/console")
    val modules = listOf(
        "state.js",
        "api.js",
        "connection.js",
        "devices.js",
        "preview.js",
        "annotations.js",
        "history.js",
        "prompt.js",
        "rendering.js",
        "shortcuts.js",
        "main.js",
    )
    val expected = modules.joinToString("\n") { name ->
        val source = File(sourceDir, name)
        assertTrue(source.isFile, "Expected console source module $name")
        "// $name\n${source.readText().trimEnd()}\n"
    }
    val generated = File(root, "fixthis-mcp/src/main/resources/console/app.js").readText()

    assertEquals(expected, generated)
}
```

Consequences for every change in this fix:

- The order is fixed; do not reshuffle modules.
- For each module the bundle holds `// <filename>\n` followed by the file content with trailing whitespace stripped (`trimEnd()`), then a single `\n`. Modules are joined with `\n`. Practically: between two modules you see one blank line, and each module starts with its filename comment.
- Any edit in `preview.js` or `rendering.js` must be mirrored byte-for-byte in `app.js`. The test prints the first differing region on failure, so iterating is cheap, but the *contract* is byte equality, not "near enough".
- There is no build step that auto-regenerates `app.js`. The author is responsible for keeping the bundle in sync. A common failure mode is fixing the source modules and forgetting `app.js`, which leaves the served console broken even though structural assertions on the source pass — this is exactly why `generatedConsoleAppMatchesConsoleSourceModules` is a structural gate.

## 2. The new `latestScreen()` — line by line

New body, in `fixthis-mcp/src/main/console/preview.js` (and mirrored in `app.js`):

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

Branch-by-branch:

1. `if (addItemsFlow) return addItemsFlow.screen;` — annotate flow has its own frozen capture. We keep the same priority for that mode; the bug never manifested here because the live poll is paused while annotating.
2. `if (focusedSavedItemId) { ... }` — user clicked a saved annotation pin. We resolve the saved item from `savedEvidenceItems()` (which is `(state.session?.items || []).filter(item => item.delivery !== 'sent')`), then look up the matching screen object in `state.session?.screens`. If both exist we return the **session-screens** copy of that screen. Returning that object instead of the live `state.preview.screen` is what causes `screenImageUrl()` to switch to the saved screenshot endpoint (see §5).
3. Fallback: `state.preview?.screen || latestPersistedScreen();`. The reorder is the headline change. The live feed wins; the persisted screen is now only a fresh-load fallback (see §8).

Two important properties of branch 2:

- It is read-only. We do not mutate `state.preview` or any cached value. The render cascade is re-entrant safe.
- It silently falls through when the focused item references a screen that is no longer present in `state.session.screens` (e.g. the session was reloaded with a partial screens list). In that fallback case the user will see the live preview rather than a stale image, which is the safer default.

## 3. The new `renderSelectionOverlay()` saved-overlay block — line by line

New block, replacing the previous `if (!addItemsFlow) { ... renderSavedEvidenceOverlay(overlay, image, persistedItems); }` in `fixthis-mcp/src/main/console/rendering.js` (and mirrored in `app.js`):

```js
if (!addItemsFlow && focusedSavedItemId) {
  const focusedItem = savedEvidenceItems().find(item => item.itemId === focusedSavedItemId);
  if (focusedItem) {
    const sameScreenItems = savedEvidenceItems().filter(item => item.screenId === focusedItem.screenId);
    renderSavedEvidenceOverlay(overlay, image, sameScreenItems);
  }
}
```

Why every clause matters:

- `!addItemsFlow` — preserved. While annotating, the pending-pin overlay is the source of truth, and saved overlays are intentionally hidden so the user is not distracted.
- `focusedSavedItemId` — new gate. Without this, the overlay path runs every time `renderSelectionOverlay()` fires, and on a poll tick that landed on a different physical screen we would paint screen-1 pins onto a screen-2 image. Gating means: in the default live state the overlay is empty by design.
- `find(...)` returning `focusedItem` — defensively re-resolved per render. The saved item could have been deleted between the click and the render, in which case the inner branch does nothing.
- `sameScreenItems` filter — even when focused, we never paint pins from other screens. This matters for sessions that have multiple saved annotations across multiple screens: the user expects the focused screen's siblings, not the entire saved set.
- The signature of `renderSavedEvidenceOverlay(overlay, image, items)` is unchanged, so per-pin numbering / severity / click handlers continue to work without further edits.

## 4. Why `focusSavedEvidenceItem()` needs no changes

`focusSavedEvidenceItem(itemId)` already does:

```js
function focusSavedEvidenceItem(itemId) {
  focusedSavedItemId = itemId;
  focusedSavedSessionId = state.session?.sessionId || null;
  focusedPendingItemIndex = null;
  currentSelection = null;
  toolMode = 'select';
  comment.value = '';
  renderPreviewOnly();
  renderInspectorRegion();
}
```

`renderPreviewOnly()` (in `rendering.js`) calls `renderPreviewRegion()`, which reads `latestScreen()` to choose the image, then sets `snapshotImage.src = screenImageUrl(screen)` and finally calls `renderSelectionOverlay()`. With the corrected `latestScreen()`:

1. `focusedSavedItemId` is now set, so `latestScreen()` returns the saved-item's screen from `state.session.screens`.
2. `screenImageUrl(screen)` produces `/api/screens/{screenId}/screenshot/full` (see §5), so the `<img>` swaps to the saved screenshot.
3. The new gate in `renderSelectionOverlay()` runs the focused branch and overlays the same-screen pins.

Nothing else in the click handler needs to change. We deliberately did not add a "force refresh" or push a manual `state.preview` mutation — the existing cascade already does the work once `latestScreen()` makes the right choice.

## 5. `screenImageUrl()` flow for the focused state

`screenImageUrl()` is unchanged:

```js
function screenImageUrl(screen) {
  if (addItemsFlow) return addItemsFlow.screenshotUrl;
  if (state.preview?.screen === screen && state.preview?.previewId) return previewScreenshotUrl(state.preview.previewId);
  if (screen?.screenId) return '/api/screens/' + encodeURIComponent(screen.screenId) + '/screenshot/full';
  return '';
}
```

Three observations make this work transparently with the new `latestScreen()`:

1. The second branch's guard is a **reference equality** check (`===`) on `state.preview?.screen`. The screen object that branch-2 of `latestScreen()` returns is pulled from `state.session.screens`, which is a different object identity even if it represents the same `screenId`. The `===` test is therefore false, and we fall through.
2. The third branch returns `/api/screens/{screenId}/screenshot/full`. This route is implemented in `ArtifactRoutes.kt` (server side) and serves the persisted full-resolution screenshot stored at capture time. No new endpoint or server change is needed.
3. There is no caching layer in the way: the `<img src>` change forces the browser to reload from the new URL. The endpoint sets appropriate cache headers per `ArtifactRoutes.kt`'s existing behavior.

The only theoretical edge case is if a future refactor starts caching `latestScreen()` results or replaces the `===` reference comparison with a `screenId`-based comparison. Either of those would re-introduce a cross-screen leak; treat them as breaking changes if encountered later.

## 6. The 3 Kotlin assertions to update — exact before/after

All three live in `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`.

### 6.1 Line ~1608 — `latestScreen()` content (string contains)

Inside `consoleHtmlKeepsFrozenPreviewStableAndShowsPersistedScreenHistory`.

Before:

```kotlin
assertTrue(html.contains("return addItemsFlow?.screen || latestPersistedScreen() || state.preview?.screen;"))
```

After:

```kotlin
assertTrue(html.contains("function latestScreen()"))
assertTrue(html.contains("if (addItemsFlow) return addItemsFlow.screen;"))
assertTrue(html.contains("if (focusedSavedItemId) {"))
assertTrue(html.contains("return state.preview?.screen || latestPersistedScreen();"))
assertFalse(html.contains("return addItemsFlow?.screen || latestPersistedScreen() || state.preview?.screen;"))
```

The `assertFalse` is non-negotiable: it is what *proves* the buggy expression is gone. Without it, a future regression could re-introduce the old code and only this test's positive assertions would still pass against new branches.

### 6.2 Line ~1611 — saved-overlay assertion (string contains)

Inside the same test.

Before:

```kotlin
assertTrue(html.contains("const persistedItems = savedEvidenceItems();"))
assertTrue(html.contains("renderSavedEvidenceOverlay(overlay, image, persistedItems);"))
```

After:

```kotlin
assertTrue(html.contains("if (!addItemsFlow && focusedSavedItemId)"))
assertTrue(html.contains("savedEvidenceItems().filter(item => item.screenId === focusedItem.screenId)"))
assertTrue(html.contains("renderSavedEvidenceOverlay(overlay, image, sameScreenItems);"))
assertFalse(html.contains("renderSavedEvidenceOverlay(overlay, image, persistedItems);"))
```

We drop the `const persistedItems = savedEvidenceItems();` literal because that exact expression no longer occurs inside `renderSelectionOverlay()` after Task 3. Other call sites still call `savedEvidenceItems()` (e.g. `renderSavedEvidenceGroups()` uses `const items = savedEvidenceItems();`), so the function-level guarantee is preserved. The neighbor test `consoleHtmlKeepsSavedAnnotationPreviewsInCenterDeviceOnly` asserts on the same literal at line ~1633 — verify post-edit; if that assertion now fails (i.e. the literal `const persistedItems = savedEvidenceItems();` is no longer in the bundle anywhere), relax it to `assertTrue(html.contains("savedEvidenceItems()"))`.

### 6.3 Line ~1760 — `latestScreen()` Regex

Inside `consoleHtmlResetsPreviewContextOnSessionOrDeviceChange`.

Before:

```kotlin
assertTrue(Regex("function latestScreen\\(\\) \\{\\s+return addItemsFlow\\?\\.screen \\|\\| latestPersistedScreen\\(\\) \\|\\| state\\.preview\\?\\.screen;\\s+\\}").containsMatchIn(html))
```

After:

```kotlin
assertTrue(Regex("function latestScreen\\(\\) \\{\\s+if \\(addItemsFlow\\) return addItemsFlow\\.screen;").containsMatchIn(html))
assertTrue(Regex("if \\(focusedSavedItemId\\) \\{[\\s\\S]*?const focusedItem = savedEvidenceItems\\(\\)\\.find\\(item => item\\.itemId === focusedSavedItemId\\);").containsMatchIn(html))
assertTrue(html.contains("return state.preview?.screen || latestPersistedScreen();"))
```

The new function body is several lines, so a single tight regex would be fragile. Two regexes plus a string contains form a triangulation that's robust against indentation changes but tight enough to fail loudly if any branch is missing or reordered.

## 7. Deselect behavior

Two paths leave the focused state and must return to "live preview, no overlays":

```js
function clearSelection() {
  currentSelection = null;
  focusedPendingItemIndex = null;
  focusedSavedItemId = null;
  focusedSavedSessionId = null;
  hoveredAnnotationTarget = null;
  comment.value = '';
  clearDragState();
  renderSelectionOverlay();
  renderInspectorRegion();
  updateComposerState();
}
```

```js
function focusPendingFeedbackItem(index) {
  focusedPendingItemIndex = index;
  focusedSavedItemId = null;
  focusedSavedSessionId = null;
  currentSelection = null;
  toolMode = 'select';
  comment.value = pendingFeedbackItems[index]?.comment || '';
  renderPreviewOnly();
  renderInspectorRegion();
}
```

Both null `focusedSavedItemId` *before* re-rendering. After the fix:

- `latestScreen()` no longer takes the `focusedSavedItemId` branch, so it returns `state.preview?.screen` (or `latestPersistedScreen()` as last resort).
- `renderSelectionOverlay()` no longer enters the saved-overlay branch (the `&& focusedSavedItemId` guard is false), so it draws no saved pins.

Net effect: deselect transparently returns the user to the live, overlay-free state without any extra wiring. This is the entire reason `annotations.js` requires zero edits.

## 8. `latestPersistedScreen()` fallback

`latestPersistedScreen()` is preserved as the very last branch of `latestScreen()`:

```js
return state.preview?.screen || latestPersistedScreen();
```

The reason: on a fresh page load, before the first `requestLivePreview()` resolves, `state.preview` is `null`. Without `latestPersistedScreen()` the preview region would render an empty `<img>` until the first poll completes, which can take up to the configured interval. Falling back to the most recent persisted screen with a real screenshot keeps the UI populated immediately.

The bug never lived in `latestPersistedScreen()` itself — its job (find the freshest persisted screen with a screenshot) is still useful. The bug was the **priority** that placed it *before* the live feed. With the priority corrected, `latestPersistedScreen()` is exercised only when the live feed has not yet produced any data.

## 9. Verification matrix

After all four code edits land:

| Scenario | `latestScreen()` returns | `<img src>` resolves to | Saved-overlay branch entered? | Pins drawn |
|---|---|---|---|---|
| Live preview, nothing focused | `state.preview.screen` (live) | `previewScreenshotUrl(previewId)` (matches by `===`) | No (`focusedSavedItemId` null) | None |
| Live preview, fresh page load (no `state.preview` yet) | `latestPersistedScreen()` | `/api/screens/{id}/screenshot/full` | No | None |
| Click saved pin (`focusedSavedItemId` set) | session-screens copy of that screen | `/api/screens/{id}/screenshot/full` (different reference, falls through) | Yes | Same-screen items only |
| Deselect (clearSelection / focusPendingFeedbackItem) | `state.preview.screen` (live) | `previewScreenshotUrl(previewId)` | No | None |
| Annotate flow active | `addItemsFlow.screen` | `addItemsFlow.screenshotUrl` | No (`!addItemsFlow` false) | Pending pins (handled elsewhere) |

This matrix matches the UX table in the design doc; it is reproduced here from the implementation's perspective so reviewers can trace each cell back to a specific branch in the new code.
