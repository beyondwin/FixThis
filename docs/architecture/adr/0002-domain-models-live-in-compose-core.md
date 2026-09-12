# ADR-0002: Domain Models Live In Compose Core

- Status: Accepted
- Date: 2026-05-06

## In short

Annotation, snapshot, and session models live in `compose-core`. MCP JSON
names, file paths, and UI-only state stay outside.

The old in-app overlay module is gone. This decision still holds for MCP,
CLI, and the desktop console.

## Context

Annotation, snapshot, and session concepts are shared across capture, MCP,
CLI, and the desktop console. `compose-core/domain` holds these models, IDs,
and repository contracts without depending on MCP DTOs or persistence.
`compose-core/usecase` holds application use cases that depend on those
contracts.

## Decision

Feedback domain models live in `fixthis-compose-core`. MCP DTOs, JSON field
names, persistence paths, and UI-only state stay in outer modules.

## Consequences

- Domain rules can be tested without MCP or Android UI.
- Outer modules translate through explicit mappers.
- `compose-core` must stay free of MCP, CLI, and file-system dependencies.

## Alternatives Considered

- Keep domain objects beside MCP persistence models. Rejected: persisted
  JSON names and domain naming would stay coupled.
- Put domain models in the retired overlay module. Rejected then, and the
  overlay is gone now. MCP and CLI still need the same concepts without
  depending on Compose UI.
