# ADR-0009: Feedback Target Type Polymorphism

- Status: Accepted
- Date: 2026-06-06

## Context

`FeedbackTargetType` (`AREA`/`NODE`) behavior was decided by ~8 independent
`when (targetType)` arms across `FeedbackTargetValidator` and
`TargetEvidenceService`, plus two further package-level `when` sites in
`TargetSummaryFormatter` (the fallback summary label) and
`TargetBoundaryContextFormatter` (target bounds). Adding or changing a target
kind meant editing every arm (shotgun surgery, low cohesion). Target/evidence is
an active change hotspot.

## Decision

Introduce an `internal sealed interface FeedbackTargetStrategy` in
`session/target` with stateless `AreaTargetStrategy` / `NodeTargetStrategy`
implementations that own per-kind behavior, including the per-kind fallback
summary label (`fallbackSummaryLine`). Exactly two type-discriminating sites are
permitted: `FeedbackTargetType.strategy()` (wire enum → strategy) and
`AnnotationTargetDto.targetType()` (persisted DTO → enum). The shared
`boundsInWindow` is hoisted onto the `AnnotationTargetDto` interface to remove
the last incidental `when`. `FeedbackTargetStrategyTest` enforces that every
enum value resolves to a matching strategy and to the correct fallback label.

## Consequences

- A new target kind is one new `FeedbackTargetStrategy` implementation plus two
  adapter arms — the evidence/validation/summary pipeline is closed to the
  change (OCP).
- The two adapter functions are the only sanctioned target-kind `when` sites in
  the package; a third is a design regression caught by the invariant grep in
  the verification task.
- Behavior, error messages, summary labels, evidence ordering, and persisted
  JSON are unchanged.

## Alternatives Considered

- Put behavior on the `FeedbackTargetType` enum itself. Rejected: the enum is a
  `@Serializable` wire model in `console`; coupling it to `SnapshotDto`,
  `FixThisNode`, and evidence ranking would invert the dependency direction.
- Leave the `when` arms and rely on the exhaustive-`when` compiler check.
  Rejected: exhaustiveness catches a missing arm but not the scattering itself;
  cohesion and OCP still suffer.
