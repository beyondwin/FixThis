# ADR-0007: Feedback Console Owns Connection Recovery

## Status

Accepted

## Context

FixThis's mainline UX is the MCP feedback console. The Android app-side UI is intentionally limited to the non-interactive `MCP waiting` / `MCP connected` status pill. Before resilient connection recovery, users had to infer whether failures came from ADB, multiple devices, a closed app, a dropped bridge, or an unsupported build.

Connection failures must not discard feedback work. Pending browser annotations, saved evidence, and the last preview are more important than immediately clearing state after a device or bridge drop.

## Decision

The desktop browser console owns the connection recovery loop.

- `GET /api/connection` diagnoses device and bridge state and returns a user-facing `ConsoleConnectionStatus`.
- `POST /api/app/launch` launches the selected or only ready debug app only when the current state can be recovered by opening the app.
- The public console states are `WELCOME`, `READY`, `OPEN_APP`, `STARTING`, `RECONNECT`, `CHOOSE_DEVICE`, `CHECK_PHONE`, and `UNSUPPORTED_BUILD`.
- The browser shows one primary action at a time: `START`, `CAPTURE`, `OPEN_APP`, `RECONNECT`, `TRY_AGAIN`, or `CHOOSE_DEVICE`.
- Technical causes stay under Details as `deviceState`, `bridgeState`, and `rawError`.
- The Android app remains status-only; it does not gain reconnect buttons, feedback controls, or a device picker.
- On disconnect, the console keeps pending draft work and the last preview visible, marks the preview stale, disables live bridge actions, and resumes polling after `READY`.

## Consequences

Users recover from common ADB/app/bridge failures inside the same browser surface where feedback work happens. The app-side sidekick remains small and debug-only.

The MCP service owns more orchestration: device enumeration, selected-device validation, bridge heartbeat diagnosis, app launch, and mapping low-level failures into recovery states. This keeps local HTTP console contracts separate from persisted feedback-session JSON.

Wireless debugging and secure lockscreen behavior remain Android/ADB constraints. When ADB cannot rediscover a device or a locked phone prevents inspection, the console can surface `CHECK_PHONE` details but cannot repair the device state itself.

## Alternatives Considered

- Add reconnect controls to the Android app. Rejected because it expands the app-side UI beyond status-only and makes recovery harder for agent/browser workflows.
- Keep recovery in CLI commands only. Rejected because the user loses context while working in the browser console.
- Clear draft work on disconnect. Rejected because transient connection failures should not destroy user feedback.
