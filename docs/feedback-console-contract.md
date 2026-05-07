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
