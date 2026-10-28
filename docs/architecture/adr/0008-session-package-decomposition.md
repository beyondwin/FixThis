# ADR-0008: Session Package Decomposition

- Status: Accepted
- Date: 2026-06-06

## In short

Split `fixthis-mcp/.../session/` by job. The root keeps only
`FeedbackSessionService`. New code goes in a sub-package. A new import
across groups needs this ADR and `SessionPackageBoundaryTest` in the same
change.

Module boundaries from ADR-0001 and ADR-0002 are unchanged.

## Context

`session/` grew to 57 flat files mixing persistence, drafts, target
evidence, edit-surface analysis, handoff rendering, preview cache, and
source freshness. The only size backstop was
`ArchitectureHotspotBudgetTest`. That caps file length, not mixing.

## Decision

Group files by job:

```text
lifecycle/{store,event}   persist and replay the session
draft                     unsaved comments
target                    what was selected
editsurface               where a visual change is likely rendered
handoff                   Markdown/JSON for agents
preview                   frozen frames and screenshots
source                    host source freshness
connection                device/bridge recovery
dto, domain               shared models and pure display helpers
```

The split uses packages, not extra Gradle modules. Module splits here add
build cost without payoff.

Edit-surface logic stays in MCP for now because it still depends on MCP
DTOs in `SessionDtoModels.kt`. This ADR does not move those DTOs into
`compose-core`.

Later packages, not part of the original split:

- `verification` — claim receipts. Must not import `lifecycle.store`.
- `runtime` — host diagnostics capture. Not in the original forbid table.

## Consequences

- Flat dumps in the `session` root fail the layout guard.
- Cross-group imports follow the rule table below.
- Hotspot budgets track the new paths.

## Alternatives Considered

- Keep the flat package and rely on line budgets. Rejected: size caps do
  not stop mixed responsibilities.
- Extract sub-packages into Gradle modules. Rejected: extra modules are
  not worth it at this size.

## Exceptions

These are an allow-list, not a pattern to copy. `SessionPackageBoundaryTest`
enforces them.

| Id | Allowed extra import | Why | How to retire |
| --- | --- | --- | --- |
| E1 | `preview` → `lifecycle.store` and `target` | Preview capture also saves the session and builds target evidence. | Depend on ports, not concrete store/target services. `preview` → `handoff` stays forbidden. |
| E3 | `lifecycle/event` → `handoff` delivery/batch models | Events store those models as state, not rendering code. | Move the models down and forbid the import. |
| E4 | `lifecycle/store` → the same handoff models | Same shared-state coupling as E3. | Same retirement as E3. |

E2 was retired on 2026-10-28. Shared quoting and bounds helpers now live in
`dto/DisplayFormatting.kt`; both target summaries and handoff renderers use
them. Source-path and session-specific formatting remains in `handoff`.
`target` → `handoff` is forbidden by `SessionPackageBoundaryTest`.

Forbidden after that allow-list:

- `editsurface` must not import `lifecycle.store`, `handoff`, `preview`, or
  `connection`.
- `handoff` must not import `lifecycle.store`, `preview`, or `connection`.
- `preview` must not import `handoff`.
- `target` must not import `lifecycle.store`, `handoff`, `preview`, or
  `connection`.
- `source` must not import `lifecycle.store`, `handoff`, `preview`, or `target`.
- `lifecycle/event` must not import `preview` or `connection`.
- `lifecycle/store` must not import `connection` or `target`.
- `draft` must not import `connection`.
- `connection` must not import `handoff`, `preview`, or `target`.
- `verification` must not import `lifecycle.store`.

A later ADR may move `FeedbackDelivery` and `FeedbackHandoffBatch` into a
lower package to retire E3–E4. Tighten the test in the same commit.
