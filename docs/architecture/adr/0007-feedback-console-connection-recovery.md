# ADR-0007: Feedback Console Owns Connection Recovery

- Status: Accepted

## In short

The desktop browser reconnects the device and bridge. The Android app stays
a status pill. Drafts and the last preview survive a drop.

## Context

The main UX is the MCP console. The app only shows `MCP waiting` /
`MCP connected`. Before this, users had to guess whether a failure came
from ADB, multiple devices, a closed app, a dropped bridge, or an
unsupported build.

Connection failures must not throw away work. Pending comments, saved
evidence, and the last preview matter more than clearing state.

## Decision

The desktop browser owns the recovery loop.

- `GET /api/connection` diagnoses device and bridge state and returns a
  user-facing `ConsoleConnectionStatus`.
- `POST /api/app/launch` opens the selected or only ready debug app when
  that is enough to recover.
- Public console states: `WELCOME`, `READY`, `OPEN_APP`, `STARTING`,
  `RECONNECT`, `CHOOSE_DEVICE`, `CHECK_PHONE`, `UNSUPPORTED_BUILD`.
- One primary action at a time: `START`, `CAPTURE`, `OPEN_APP`,
  `RECONNECT`, `TRY_AGAIN`, or `CHOOSE_DEVICE`.
- Technical causes stay under Details as `deviceState`, `bridgeState`, and
  `rawError`.
- The Android app stays status-only. No reconnect buttons, feedback
  controls, or device picker.
- On disconnect, the console keeps pending drafts and the last preview,
  marks the preview stale, disables live bridge actions, and resumes
  polling after `READY`.

## Consequences

Users recover inside the same browser where they annotate. The sidekick
stays small and debug-only.

The MCP service owns more orchestration: device list, selected-device
checks, bridge heartbeat, app launch, and mapping low-level failures to
recovery states. Local HTTP console contracts stay separate from persisted
session JSON.

Wireless debugging and lockscreens remain Android/ADB limits. The console
can show `CHECK_PHONE` details. It cannot unlock the phone.

## Alternatives Considered

- Add reconnect controls to the Android app. Rejected: expands app UI past
  status-only and hurts agent/browser workflows.
- Keep recovery in CLI commands only. Rejected: the user loses context.
- Clear draft work on disconnect. Rejected: a blip should not destroy
  comments.
