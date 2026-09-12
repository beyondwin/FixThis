# ADR-0009: Feedback Target Type Polymorphism

- Status: Accepted
- Date: 2026-06-06

## In short

Node vs area behavior lives in one strategy class each. Only two `when`
sites map wire types to those strategies. Adding a third `when` on
`targetType` in `session/target` is a regression.

## Context

`FeedbackTargetType` (`AREA` / `NODE`) behavior was copied across about
eight `when (targetType)` arms in `FeedbackTargetValidator` and
`TargetEvidenceService`, plus two more in summary formatters. Changing a
target kind meant editing every arm.

## Decision

Add an `internal sealed interface FeedbackTargetStrategy` in
`session/target`, with `AreaTargetStrategy` and `NodeTargetStrategy`.
Each owns per-kind behavior, including the fallback summary label.

Exactly two type-mapping sites are allowed:

- `FeedbackTargetType.strategy()` — wire enum → strategy
- `AnnotationTargetDto.targetType()` — persisted DTO → enum

Shared `boundsInWindow` sits on the `AnnotationTargetDto` interface so
formatters do not need another `when`. `FeedbackTargetStrategyTest`
checks that every enum value has a matching strategy and label.

## Consequences

- A new target kind is one new strategy plus the two mapping sites.
- A third `when` on target kind in this package is a design bug.
- Behavior, error messages, summary labels, evidence ordering, and
  persisted JSON stay the same.

## Alternatives Considered

- Put behavior on the `FeedbackTargetType` enum. Rejected: the enum is a
  serializable wire model in `console`. Coupling it to snapshots and
  evidence ranking would reverse the dependency.
- Leave the scattered `when` arms. Rejected: the compiler catches a
  missing arm, not the scattering.
