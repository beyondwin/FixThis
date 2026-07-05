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
that cannot be removed without further refactoring beyond the scope of this move.

These exceptions are an allow-list, not a pattern to copy. A new cross-package
dependency requires both an ADR update and a matching `SessionPackageBoundaryTest`
rule change in the same commit.

| Exception | Currently allowed direction | Why it is tolerated | Retirement path |
| --- | --- | --- | --- |
| E1 | `preview` -> `lifecycle.store`; `preview` -> `target` | `PreviewCaptureService` orchestrates preview capture, session persistence, screenshot promotion, and target evidence through `FeedbackSessionStore` and `TargetEvidenceService`. `PreviewSaveReservationTracker` and `ScreenshotArtifactPromoter` also reference store types. | Extract a preview workflow port or lower application-service boundary so preview capture can depend on interfaces rather than concrete store/target services. `preview` -> `handoff` remains forbidden. |
| E2 | `target` -> selected `handoff` formatting helpers | `TargetBoundaryContextFormatter` and `TargetSummaryFormatter` reuse `compactQuotedValue`, `formatBounds`, `formatBox`, and `inlineSafe` from `handoff/FormatterExtensions.kt`. The coupling is rendering-helper reuse, not target policy depending on handoff workflow. | Move shared formatting helpers to a lower `session/dto`, `session/domain`, or dedicated formatting package and forbid `target` -> `handoff` again. |
| E3 | `lifecycle/event` -> selected `handoff` models | Session event and mutation payloads carry `FeedbackDelivery` and `FeedbackHandoffBatch` from `handoff/FeedbackHandoffModels.kt` as event-sourced state. These are shared state models, not handoff rendering behavior. | Move shared delivery and batch state models to a lower `session/dto` or `session/domain` package and forbid `lifecycle/event` -> `handoff` again. |
| E4 | `lifecycle/store` -> selected `handoff` models | `FeedbackSessionSummary`, `FeedbackSessionStoreDraftDeduplication`, and `FeedbackSessionStoreDelegate` read `FeedbackDelivery` to classify and count persisted items. This is the same shared state-model coupling as E3. | Move shared delivery state to a lower `session/dto` or `session/domain` package and forbid `lifecycle/store` -> `handoff` again. |

Current forbidden directions after the exception register:

- `editsurface` must not import `lifecycle.store`, `handoff`, `preview`, or
  `connection`.
- `handoff` must not import `lifecycle.store`, `preview`, or `connection`.
- `preview` must not import `handoff`.
- `target` must not import `lifecycle.store`, `preview`, or `connection`.
- `source` must not import `lifecycle.store`, `handoff`, `preview`, or `target`.
- `lifecycle/event` must not import `preview` or `connection`.
- `lifecycle/store` must not import `connection` or `target`.
- `draft` must not import `connection`.
- `connection` must not import `handoff`, `preview`, or `target`.

A future ADR may lift the shared `handoff` domain models (`FeedbackDelivery`,
`FeedbackHandoffBatch`) and `FormatterExtensions` into a lower package to retire
E2-E4. When that happens, tighten `SessionPackageBoundaryTest` in the same
commit as the model/helper move.
