# ADR-0001: Use Clean Architecture Layering

- Status: Accepted
- Date: 2026-05-06

## In short

Put shared rules in `compose-core`. Keep MCP JSON, Android UI, files, and
the bridge in the outer modules. Translate at the edges.

## Context

FixThis has UI, MCP, CLI, capture, persistence, and Gradle plugin work.
Domain rules must not depend on MCP JSON, Android UI, or `.fixthis/` layout.

## Decision

`compose-core` owns pure domain models, repository contracts, and use cases.
Outer modules own DTOs, UI state, persistence, bridge, and presentation.

## Consequences

- Domain behavior is unit-testable on the JVM.
- MCP JSON changes need explicit mapper changes.
- More mapper code exists at module boundaries.

## Alternatives Considered

- Keep models in MCP and import them from UI code. Rejected: UI state and
  wire format would stay coupled.
- Move everything into a new shared module. Rejected: `compose-core` is
  already pure Kotlin and already used by the relevant modules.
