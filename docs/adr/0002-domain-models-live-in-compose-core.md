# ADR-0002: Domain Models Live In Compose Core

- Status: Accepted
- Date: 2026-05-06

## Context

Annotation, snapshot, and session concepts are shared across capture, MCP, CLI, and overlay flows. The implemented `compose-core/domain` packages contain these models, IDs, and repository contracts without depending on MCP DTOs or persistence. Adjacent `compose-core/usecase` packages contain application use cases that depend on those domain contracts.

## Decision

Feedback domain models live in `pointpatch-compose-core`, while MCP DTOs, JSON field names, persistence paths, and UI-only state stay in outer modules.

## Consequences

- Domain rules can be tested without MCP or Android UI infrastructure.
- Outer modules translate through explicit mapper boundaries.
- `compose-core` must stay free of MCP, CLI, and file-system dependencies.

## Alternatives Considered

- Keep domain objects beside MCP persistence models. Rejected because persisted JSON names and domain naming would remain coupled.
- Put domain models in the overlay module. Rejected because MCP and CLI code also need the same concepts without depending on Compose UI.
