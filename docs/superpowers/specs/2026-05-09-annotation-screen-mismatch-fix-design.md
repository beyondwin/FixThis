# Annotation Screen Mismatch Fix Design

**Date:** 2026-05-09  
**Status:** Approved

## Problem

After annotating screen 1 and saving, if the device navigates to screen 2, the console preview stays stuck on screen 1 (or shows screen 2 with screen 1 annotation pins overlaid on it). This is confusing and broken UX.

**Root cause — two compounding issues:**

1. `latestScreen()` in `preview.js` has the wrong priority order:
   ```js
   return addItemsFlow?.screen || latestPersistedScreen() || state.preview?.screen;
   ```
   `latestPersistedScreen()` (screen 1) takes priority over `state.preview?.screen` (screen 2, the live feed), so the preview image never updates to the live device screen after annotations are saved.

2. `renderSelectionOverlay()` in `rendering.js` renders ALL saved annotation overlays unconditionally whenever `!addItemsFlow`:
   ```js
   if (!addItemsFlow) {
     const persistedItems = savedEvidenceItems();
     if (persistedItems.length) renderSavedEvidenceOverlay(overlay, image, persistedItems);
   }
   ```
   Screen 1 annotation pins are drawn on whatever image happens to be displayed.

## Approved Solution: Approach B (Rendering Fix Only)

No screen-change detection. No modal. No activityName dependency. The fix is purely in the rendering layer.

**Mental model:**
- Default state: live preview, no annotation overlays shown on it
- Focused state (user clicks a saved annotation): swap preview to that annotation's saved screenshot, show overlays for that screen's annotations only
- Deselect: return to live preview, no overlays

## Architecture

### State

No new state variables. Uses existing:
- `focusedSavedItemId` — which saved annotation is selected (already exists)
- `state.session.screens` — array of all captured screens with screenshots (already exists)
- `state.preview` — current live preview (already exists)

### Changes

#### 1. `preview.js` — `latestScreen()`

Reverse the priority: live preview first, saved screen only when focusing a saved annotation.

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

`latestPersistedScreen()` still serves as final fallback (e.g. fresh page load before first live poll).

#### 2. `rendering.js` — `renderSelectionOverlay()`

Only render saved overlays when a saved annotation is focused, and only for items from that same screen.

```js
if (!addItemsFlow && focusedSavedItemId) {
  const focusedItem = savedEvidenceItems().find(item => item.itemId === focusedSavedItemId);
  if (focusedItem) {
    const sameScreenItems = savedEvidenceItems().filter(item => item.screenId === focusedItem.screenId);
    renderSavedEvidenceOverlay(overlay, image, sameScreenItems);
  }
}
```

#### 3. `annotations.js` — `focusSavedEvidenceItem()`

`focusSavedEvidenceItem()` already calls `renderPreviewOnly()`, which calls `renderPreviewRegion()`, which calls `latestScreen()`. With fix #1 in place, focusing a saved annotation automatically swaps the image to the correct saved screenshot. No additional changes needed here.

### `screenImageUrl()` already works correctly

```js
function screenImageUrl(screen) {
  if (addItemsFlow) return addItemsFlow.screenshotUrl;
  if (state.preview?.screen === screen && state.preview?.previewId) return previewScreenshotUrl(state.preview.previewId);
  if (screen?.screenId) return '/api/screens/' + encodeURIComponent(screen.screenId) + '/screenshot/full';
  return '';
}
```

When `latestScreen()` returns a `focusedScreen` from `state.session.screens`, the reference comparison `state.preview?.screen === screen` is false (different objects), so it falls through to `/api/screens/:screenId/screenshot/full`. This endpoint is already implemented in `ArtifactRoutes.kt` and works correctly.

## UX Behavior After Fix

| State | Preview image | Overlay pins |
|-------|--------------|--------------|
| Live preview (no annotation focused) | Live device screen | None |
| Saved annotation clicked | That annotation's saved screenshot | All annotations from that screen |
| Annotation deselected (clearSelection) | Returns to live device screen | None |
| Annotate mode active (addItemsFlow) | Frozen capture screenshot | Pending annotation pins |

## Files Changed

| File | Change |
|------|--------|
| `fixthis-mcp/src/main/console/preview.js` | Fix `latestScreen()` priority |
| `fixthis-mcp/src/main/console/rendering.js` | Guard saved overlay behind `focusedSavedItemId` + screen filter |
| `fixthis-mcp/src/main/resources/console/app.js` | Rebuild from sources (same changes applied to bundle) |

`annotations.js` is not changed for this fix (the existing `focusSavedEvidenceItem()` call chain already triggers the right renders with the above fixes).

## Out of Scope

- Screen-change detection / modal / banner
- Multi-screen session model changes
- Any server-side changes
