# Feedback Console Contract

**Status:** Current V1 Studio contract
**Owner surface:** `fixthis-mcp`

## Canonical Labels

| Surface | DOM id | Label |
| --- | --- | --- |
| Prompt copy | `copyPromptButton` | `Copy Prompt` |
| Agent handoff | `sendAgentButton` | `Send Agent` |
| Canvas select tool | `selectToolButton` | `Select` |
| Canvas annotate tool | `annotateToolButton` | `Annotate` |
| Add pending annotation | `addItemButton` | `Add annotation` |
| Exit annotate mode | `cancelAddFlowButton` | `Exit Annotate` |
| Clear current selection | `clearSelectionButton` | `Clear Selection` |
| Clear draft feedback | `clearDraftButton` | `Clear Draft` |
| Refresh devices | `refreshDevicesButton` | `Refresh devices` |
| Clear FixThis device selection | `disconnectDeviceButton` | `Clear selection` |

## Mode Semantics

- Select mode is the normal preview mode. Preview clicks navigate the debug app when the bridge is ready.
- Annotate mode freezes the latest preview so the user can select Compose nodes or draw visual areas.
- Stale preview state keeps the last preview visible while live bridge actions are disabled.
- Draft/history view shows persisted local feedback groups and sent handoff history.

## Persistence Semantics

- `Annotate` starts targeting and freezes the latest available preview. It does not write a session item by itself.
- `Add annotation` creates a browser-side pending annotation.
- `Copy Prompt` persists written pending annotations when needed, then copies compact agent-facing prompt text.
- `Send Agent` persists written pending annotations when needed, then creates a local handoff batch for MCP tools.
- `Clear Draft` deletes unsent draft feedback after confirmation.
- Live preview frames are transient. Persisted `screens` are evidence snapshots, not every preview frame.

## Device Semantics

- `Clear selection` clears only FixThis's active device selection and owned bridge resources.
- `Clear selection` must not run `adb disconnect`, detach USB, or affect Wi-Fi ADB outside FixThis-owned resources.

## Privacy Semantics

- `Send Agent` stores a local handoff batch.
- FixThis does not upload screenshots, comments, prompt text, source hints, or target evidence by default.

## Compact handoff schema

Rule: source hints are candidates; verify screenshot, target, and code before editing.

### Screen header

```
Screen <id>: <displayName>
screenshot: <path>
```

The `screenshot:` line is optional and omitted when no screenshot artifact is available for the screen.

### Item lines

```
N. [marker N] <title>
   target: <role> "<label>" bounds=left,top,right,bottom[; targetRisk=overlap]
   src? <file>:<line> <confidence>; why=<token>+<token>; risk=<token>
```

The `src?` line is optional and absent when no source candidates are available for the item.

- `N` — 1-based annotation number matching the numbered overlay marker.
- `target:` — semantic role, accessibility label, and window-pixel bounding box at default density 1.0.
- `targetRisk=overlap` — present when the target participates in an overlap group (see below).
- `src?` — top source candidate: file path relative to project root, 1-based line number, and confidence level.
- `why=` — `+`-joined reason tokens explaining why the candidate was selected.
- `risk=` — a single risk token summarizing the candidate's caution category.
- Confidence is lowercase: `high`, `medium`, `low`, or `none`.

### Reason-token mapping

| Reason string | Token |
| --- | --- |
| `selected text` | `text` |
| `selected contentDescription` | `contentDescription` |
| `selected testTag` | `tag` |
| `selected testTag convention composable` | `compTag` |
| `selected role` | `role` |
| `nearby text` | `nearbyText` |
| `nearby contentDescription` | `nearbyContentDescription` |
| `nearby testTag` | `nearbyTag` |
| `nearby role` | `nearbyRole` |
| `activity` | `activity` |
| `selected stringResource` | `stringRes` |
| `arbitrary literal` | `literal` |
| `legacy fallback` | `legacy` |

### Overlap groups

When two or more annotations on the same screen have targets that overlap (visual-area intersection IoSA >= 0.25, or weak-label center distance <= 24dp at default density 1.0), they are collected into an explicit overlap group:

```
Overlap group N (resolve one marker at a time):
```

Each item in the group carries `targetRisk=overlap` on its `target:` line. Resolve the group one marker at a time to avoid editing the wrong composable. Coordinate space is window pixels at default density 1.0; the 24dp center-distance fallback is conservative on high-density screens.
