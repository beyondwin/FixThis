# ADR-0001: Use Clean Architecture Layering

- Status: Accepted
- Date: 2026-05-06

## Context

PointPatch now has UI, MCP, CLI, capture, persistence, and Gradle plugin responsibilities. Domain concepts must not depend on MCP JSON, Android UI, or file-system layout.

## Decision

`compose-core` owns pure domain models, repository contracts, and use cases; outer modules own DTOs, UI state, persistence, bridge, and presentation.

## Consequences

- Domain behavior becomes unit-testable on the JVM.
- MCP JSON changes require explicit mapper changes.
- More mapper code exists at module boundaries.

## Alternatives Considered

- Keep models in MCP and import them from UI code. Rejected because UI state and wire format would remain coupled.
- Move everything into a new shared module. Rejected because `compose-core` is already pure Kotlin and already depended on by the relevant modules.
