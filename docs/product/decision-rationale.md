# Decision Rationale

Durable records: [ADR index](../architecture/adr/README.md).

FixThis is:

```text
MCP feedback console first
+ local AI context export
+ debug app sidekick for runtime evidence
```

The Android runtime stays small. The desktop console owns annotation,
selection, persistence, and handoff.

## Compose-only

V1 supports Jetpack Compose. Semantics give a coherent runtime signal.
XML/View and WebView targets are not source-mapped. FixThis can warn at an
interop boundary; it does not pretend a Compose candidate rendered those
pixels.

## Debug-only

Screenshots, UI text, semantics, and source hints can be sensitive. Install
through `debugImplementation`, verify debuggable before starting the bridge,
and keep handoff files under `.fixthis/`.

## No required `testTag`

Tags help, especially `comp:<ComposableName>:<variant>`, but a project can
start without them. Targeting is best-effort. The prompt carries confidence
so the agent knows when to verify.

## No AccessibilityService

The product is the debug app under development, not device-wide control.

## Desktop MCP console first

The browser console is the primary UX. The app shows `MCP waiting` /
`MCP connected`. That keeps the sidekick small and the queue on the desktop.
The old in-app overlay module is gone; see
[ADR-0005](../architecture/adr/0005-overlay-mode-state-machine.md).

## Local-first

No default upload of screenshots, comments, source hints, or prompt text.
Save to MCP writes local files. Copy Prompt writes the clipboard.

## Best-effort source candidates

Up to three ranked candidates plus margin and warnings. Agents verify before
editing. See [source matching](../reference/source-matching.md).
