# ADR-0008: Session Package Decomposition

- Status: Accepted
- Date: 2026-06-06

## Context

`fixthis-mcp/.../mcp/session/` grew to 57 flat files (6,728 lines) mixing
session persistence/event-sourcing, draft workflow, target evidence,
edit-surface analysis, handoff rendering, preview cache, and host source
freshness. The only structural backstop was the per-file line-count ratchet in
`ArchitectureHotspotBudgetTest`. Module-level boundaries (ADR-0001, ADR-0002)
are healthy and unchanged.

## Decision

Group `session` files into responsibility sub-packages: `lifecycle/{store,event}`,
`draft`, `target`, `editsurface`, `handoff`, `preview`, `source`, `connection`,
`dto` (plus existing `domain`). The `session` root keeps only the
`FeedbackSessionService` facade. Intra-`session` dependency direction is enforced
by `SessionPackageBoundaryTest`. The split uses packages, not Gradle modules,
because module granularity here adds DI/build overhead without payoff. The
edit-surface core-domain lift is deferred because the current implementation
depends on MCP DTOs from `SessionDtoModels.kt`.

## Consequences

- New `session` code must declare a sub-package; the layout guard fails a flat
  root dump.
- Cross-group imports are constrained by an explicit rule table.
- `ArchitectureHotspotBudgetTest` path keys track the new locations.
- The edit-surface package is isolated enough for a later ADR-0002 cleanup, but
  this ADR does not move MCP DTOs into `compose-core`.

## Alternatives Considered

- Keep the flat package and rely on line budgets. Rejected: budgets cap size,
  not cohesion, so the flat dump keeps growing.
- Extract sub-packages into separate Gradle modules. Rejected: DI/build overhead
  exceeds the benefit at this size (see multi-module guidance).
