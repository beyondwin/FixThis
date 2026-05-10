# ADR-0003: Feedback Item To Annotation Naming

- Status: Accepted
- Date: 2026-05-06

## Context

The architecture work introduced `Annotation`, `Snapshot`, and `Session` as domain names while preserving existing persisted session JSON names such as `items`, `screens`, `itemId`, and `screenId`. Existing public tool names and some service method names still use feedback terminology for compatibility.

## Decision

Use `Annotation` for the feedback-domain model name and DTO-facing names such as `AnnotationDto`, while preserving existing MCP JSON field names and compatibility aliases at the integration boundary.

## Consequences

- Domain code uses a consistent annotation vocabulary.
- Persisted sessions and MCP clients keep their existing wire contract.
- Boundary code carries compatibility terminology where existing APIs still expose it.

## Alternatives Considered

- Rename every feedback term in one pass. Rejected because that would mix domain cleanup with wire and API compatibility risk.
- Keep `FeedbackItem` as the domain name. Rejected because it does not match the shared annotation concept used by the new domain package.
