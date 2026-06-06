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

## Exceptions

`SessionPackageBoundaryTest` enforces the intra-`session` dependency direction.
The following pre-existing dependencies surfaced when the boundary test was first
run against the completed decomposition. Each is a genuine, justified coupling
that cannot be removed without further refactoring beyond the scope of this move,
so the corresponding rule was relaxed to permit exactly the named import (and
nothing else):

- **E1 — `preview` → `lifecycle.store` and `preview` → `target`.**
  `PreviewCaptureService` is constructor-injected with `FeedbackSessionStore` and
  `TargetEvidenceService` and throws `FeedbackSessionException`
  (`PreviewSaveReservationTracker` / `ScreenshotArtifactPromoter` also reference
  the store). Preview capture is an application service that orchestrates session
  persistence and target evidence; the dependency on those two services is
  inherent to its role. `handoff` remains forbidden for `preview`.
- **E2 — `target` → `handoff`.** `TargetBoundaryContextFormatter` and
  `TargetSummaryFormatter` reuse the `internal` string/rect formatting extensions
  (`compactQuotedValue`, `formatBounds`, `formatBox`, `inlineSafe`) defined in
  `handoff/FormatterExtensions.kt`. These are shared rendering helpers; only the
  `handoff` token was removed for `target` (store/preview/connection stay
  forbidden).
- **E3 — `lifecycle/event` → `handoff`.** Session event/mutation payloads
  (`SessionMutation`, `SessionReducer`, `SessionEventPayloads`,
  `FeedbackSessionHandoffMutation`, etc.) carry the `FeedbackDelivery` enum and
  `FeedbackHandoffBatch` model from `handoff/FeedbackHandoffModels.kt` as
  event-sourced state. These are domain models, not handoff behavior; only the
  `handoff` token was removed for `lifecycle/event`.
- **E4 — `lifecycle/store` → `handoff`.** `FeedbackSessionSummary`,
  `FeedbackSessionStoreDraftDeduplication`, and `FeedbackSessionStoreDelegate`
  read the `FeedbackDelivery` enum to classify/count persisted items. This is the
  same `handoff` domain model as E3; only the `handoff` token was removed for
  `lifecycle/store`.

A future ADR may lift the shared `handoff` domain models (`FeedbackDelivery`,
`FeedbackHandoffBatch`) and `FormatterExtensions` into a lower `dto`/`domain`
package to retire E2–E4.
