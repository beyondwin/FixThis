# ADR-0005: Overlay Mode State Machine

- Status: Accepted
- Date: 2026-05-06

## Context

Overlay mode now represents the implemented user flow states explicitly: idle, menu open, selection, loading, selection review, commenting, exported, and error. The implemented `OverlayStateMachine` validates transitions among these modes so overlay callers do not encode mode changes as scattered conditional logic.

## Decision

Overlay mode changes go through an explicit state machine that accepts only the currently supported transitions among idle, menu open, selection, loading, reviewing selection, commenting, exported, and error states.

The implemented transition model is:

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

## Consequences

- Overlay transition behavior is testable without Compose rendering.
- Invalid mode changes fail at the state-machine boundary.
- Callers need to route mode changes through the state-machine API.

## Alternatives Considered

- Keep direct mutable overlay mode updates in callers. Rejected because transition rules would remain implicit and duplicated.
- Model overlay progress with unrelated booleans. Rejected because combinations of selection, loading, and error state would be easier to make inconsistent.
