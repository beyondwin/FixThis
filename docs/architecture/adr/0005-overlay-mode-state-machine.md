# ADR-0005: Overlay Mode State Machine

- Status: Superseded — in-app overlay retired (2026-05-10)
- Date: 2026-05-06

## In short

Do not implement this. The `fixthis-compose-overlay` module is gone.
Selection and comments live in the desktop console (`fixthis-mcp`). The
Android sidekick only shows `MCP waiting` / `MCP connected` and hosts the
bridge.

This file is kept so the decision trail stays intact.

## Historical record

The rest of this page describes the retired in-app overlay. It is not the
current product.

### Context

Overlay mode represented user-flow states explicitly: idle, menu open,
selection, loading, selection review, commenting, exported, and error.
`OverlayStateMachine` validated transitions so callers did not encode mode
changes as scattered conditionals.

### Decision

Overlay mode changes went through an explicit state machine that accepted
only the supported transitions:

| Current state | Allowed next states |
| --- | --- |
| Idle | MenuOpen, Select |
| MenuOpen | Idle, Select |
| Select | Idle, Select, Loading, ReviewingSelection, Commenting, Error |
| Loading | ReviewingSelection, Commenting, Error |
| ReviewingSelection | Commenting, ReviewingSelection, Select, Idle, Error |
| Commenting | Idle, Commenting, Select, Exported, Error |
| Exported | Idle, Select |
| Error | Idle; if recoverable, any non-Error state |

### Consequences

- Overlay transitions were testable without Compose rendering.
- Invalid mode changes failed at the state-machine boundary.
- Callers had to route mode changes through that API.

### Alternatives Considered

- Keep direct mutable overlay mode updates in callers. Rejected: transition
  rules would stay implicit and duplicated.
- Model overlay progress with unrelated booleans. Rejected: selection,
  loading, and error combinations would drift out of sync.

### What replaced it

Desktop MCP console first. See [ADR-0007](0007-feedback-console-connection-recovery.md)
and the [architecture overview](../overview.md).
