# ADR-0003: Feedback Item To Annotation Naming

- Status: Accepted
- Date: 2026-05-06

## In short

In code, say **annotation**. On disk and in MCP JSON, keep `items`,
`itemId`, `screens`, and `screenId`. Do not rename persisted fields.

## Context

Domain code uses `Annotation`, `Snapshot`, and `Session`. Persisted session
JSON still uses `items`, `screens`, `itemId`, and `screenId`. Some public
tool names still say feedback, for compatibility.

## Decision

Use `Annotation` for the domain model and DTO-facing names such as
`AnnotationDto`. Keep existing MCP JSON field names and compatibility
aliases at the integration boundary.

## Consequences

- Domain code uses one vocabulary.
- Persisted sessions and MCP clients keep their wire contract.
- Boundary code still says "feedback" where existing APIs expose it.

## Alternatives Considered

- Rename every feedback term in one pass. Rejected: that mixes domain
  cleanup with wire and API compatibility risk.
- Keep `FeedbackItem` as the domain name. Rejected: it does not match the
  shared annotation concept in the domain package.
