# Device Control Clear Selection Design

**Date:** 2026-05-15
**Status:** Approved for implementation planning
**Owner surface:** `fixthis-mcp` feedback console
**Related:**
- `docs/reference/feedback-console-contract.md`
- `fixthis-mcp/src/main/resources/console/index.html`
- `fixthis-mcp/src/main/resources/console/styles.css`
- `fixthis-mcp/src/main/console/devices.js`

## Purpose

The feedback console top bar currently shows the selected Android device chip,
preview interval, refresh icon, and a full-width `Clear selection` text button.
The text button takes more visual weight than the action deserves and makes the
top bar feel busier than the rest of the Studio shell.

This design keeps device management fast while making clear selection feel like
a contextual device-chip action instead of a primary toolbar command.

## User Decision

Use the recommended chip-clear pattern:

- The selected device chip continues to own device selection, connection state,
  and the hidden native `select` picker.
- A small `x` icon button is attached to the right edge of the selected device
  chip.
- The existing refresh devices control remains a separate icon button.
- The existing full text `Clear selection` button is removed from the visible
  top bar.

## Current Behavior

The top bar exposes:

1. Device chip / picker.
2. Preview interval selector.
3. Refresh devices icon button.
4. `Clear selection` text button.

The clear action only clears FixThis's active device selection and owned bridge
resources. It does not run `adb disconnect`, detach USB, or affect Wi-Fi ADB
outside FixThis-owned resources.

## Desired Behavior

The top bar exposes:

1. Device chip / picker, including a contextual clear icon.
2. Preview interval selector.
3. Refresh devices icon button.

The clear icon is visually part of the selected device control. It uses a small
neutral `x` in the resting state and gains a stronger danger affordance on
hover/focus. This makes the action discoverable without letting it compete with
device status or refresh.

## Interaction Model

| User action | Result |
| --- | --- |
| Click the main device chip area | Opens the native device picker. |
| Change the selected device | Calls the existing device-select flow. |
| Click the chip `x` icon | Calls the existing `disconnectDevice()` flow. |
| Click the refresh icon | Calls the existing refresh-devices flow. |

The clear icon must not be covered by the invisible full-chip `select` element.
The device picker should remain clickable across the main chip area, while the
clear icon must receive its own pointer and keyboard events.

## Accessibility

- The clear icon keeps `id="disconnectDeviceButton"`. Keeping the id minimizes
  test and JS churn and preserves the existing event listener.
- The clear icon keeps:
  - `aria-label="Clear FixThis device selection"`
  - `title="Clear FixThis device selection"`
  - `type="button"`
- The visible `x` glyph is decorative and must not replace the accessible name.
- The clear icon must be keyboard reachable.
- Focus styling must be visible and at least as clear as the current top-bar
  button focus treatment.

## Visual Requirements

- The chip remains compact at desktop widths and keeps the existing selected,
  connected, connecting, unavailable, and none state colors.
- The clear icon appears as an attached trailing segment of the chip, not as a
  separate floating button.
- The clear segment uses a subtle divider from the main chip.
- Resting color is neutral text-secondary.
- Hover/focus color uses the existing red/error affordance.
- The control must fit compact breakpoints without reintroducing a text button.

## Implementation Boundaries

- Change only the feedback console static HTML/CSS and any minimal JS needed to
  preserve event behavior.
- Update console contract docs/tests only where they describe the visible
  top-bar label for `disconnectDeviceButton`.
- Do not change MCP tool contracts, API endpoints, or persisted feedback JSON.
- Do not change Android sidekick behavior.
- Do not add a frontend framework or icon library.
- Do not rename compatibility fields such as `items`, `screens`, `itemId`,
  `screenId`, `targetEvidence`, or `sourceCandidates`.

## Testing

Update or add focused coverage for:

- The console HTML still includes `id="disconnectDeviceButton"`.
- The clear control no longer exposes visible `Clear selection` text in the top
  bar.
- The clear control keeps the accessible label and title.
- Device selection, refresh, and disconnect endpoints remain wired:
  `/api/device/select`, `/api/devices`, and `/api/device/disconnect`.
- Responsive CSS does not force the clear action back into a fixed-width text
  button at compact widths.

Manual/browser verification should confirm:

- Clicking the main chip still opens the device picker.
- Clicking `x` clears the selected device.
- Clicking refresh still refreshes device state.
- The top bar no longer wraps awkwardly in the screenshot-sized layout.

## Out Of Scope

- Moving refresh into a menu.
- Adding an overflow `...` menu for a single clear action.
- Adding confirmation before clearing device selection.
- Changing canvas selection clearing (`clearSelectionButton` / `Clear
  Selection` in the inspector footer). This design only covers the top-bar
  device-selection clear action.
