# FixThis Feedback Target Type Polymorphism Design Spec

Date: 2026-06-06
Status: Draft for review
Scope: `fixthis-mcp` `session/target` package. Replace the `FeedbackTargetType`
(`AREA`/`NODE`) enum `when`-switches that are scattered across the target
pipeline with a polymorphic strategy, so target-kind behavior lives in one place
per kind and a new kind is an additive change.
Related implementation plan:
[`../plans/2026-06-06-target-type-polymorphism.md`](../plans/2026-06-06-target-type-polymorphism.md)

## Motivation (design lens)

This refactor applies three principles from 조영호's *오브젝트 — 기초편*
(Inflearn course 334416), read on 2026-06-06:

- **다형성 패턴 (4-4 / 4-5):** "동일한 메시지를 전송했을 때 수신 객체의 타입에
  따라 다르게 동작하는 것이 다형성." Type-based conditionals scattered across
  methods are the smell; the fix is to send one message and let the type decide.
- **변경 보호 패턴 (4-5):** "변하는 부분을 식별하고 그 주변에 안정적인 추상화를
  도입한 뒤, 구체 타입 대신 추상화에 의존하도록 협력자를 변경한다." The thing
  that varies here is *the kind of feedback target*; the abstraction that
  protects callers from it is the strategy interface.
- **응집도 / "중요한 것은 변경" (6-2 / 6-5):** "서로 다른 이유로 변경되는 코드가
  섞여 있으면 응집도가 낮다 … 중요한 것은 변경이다." Today, adding or changing a
  target kind forces edits in ~8 unrelated `when` arms. After the change, the
  per-kind logic is cohesive (one object per kind) and the rest of the pipeline
  is closed to that change (OCP).

This area qualifies under the "중요한 것은 변경" caveat — target/evidence is an
active change hotspot (recent source-matching, edit-surface, and per-role
confidence work all flow through `TargetEvidenceService`), so the abstraction
earns its keep rather than adding speculative complexity.

## Analysis Method

Commands and reads used (2026-06-06, branch `main`):

```bash
# Enumerate the type-switch surface
grep -rn "when *(" --include='*.kt' fixthis-mcp/src/main \
  | grep -iE "targetType|target)"
# Enum definition + all usages
grep -rn "enum class FeedbackTargetType" fixthis-mcp/src/main
grep -rln "FeedbackTargetType" fixthis-mcp/src/main
# DTO target shape
sed -n '178,187p' fixthis-mcp/.../session/dto/SessionDtoModels.kt
# Test fixtures + public API to preserve
cat fixthis-mcp/src/test/.../session/FeedbackTargetValidatorTest.kt
cat fixthis-mcp/.../session/target/TargetEvidenceService.kt
cat fixthis-mcp/.../session/target/FeedbackTargetValidator.kt
```

## Current Architecture Baseline

`FeedbackTargetType` is the wire/request enum, declared in the `console`
package and serialized in `AddAnnotationRequest`:

```kotlin
// fixthis-mcp/.../mcp/console/AnnotationRequestModels.kt
@Serializable
enum class FeedbackTargetType {
    @SerialName("area") AREA,
    @SerialName("node") NODE,
}
```

The persisted target is a separate sealed DTO (the storage contract):

```kotlin
// fixthis-mcp/.../session/dto/SessionDtoModels.kt
@Serializable
sealed interface AnnotationTargetDto {
    @Serializable @SerialName("semantics_node")
    data class Node(val nodeUid: String, val boundsInWindow: FixThisRect) : AnnotationTargetDto
    @Serializable @SerialName("visual_area")
    data class Area(val boundsInWindow: FixThisRect) : AnnotationTargetDto
}
```

### The switch surface (the problem, in change terms)

Every target-kind decision is an independent `when (targetType)` arm. Adding a
third kind (e.g. a multi-node selection or a gesture region) means finding and
editing all of these — classic shotgun surgery / low cohesion:

| # | Site | What the arms decide |
|---|------|----------------------|
| 1 | `FeedbackTargetValidator.kt:62` `selectedNodeFor` | AREA→null; NODE→resolve node by uid (throws if missing) |
| 2 | `FeedbackTargetValidator.kt:77` `evidenceNodesFor` | AREA→area evidence ranking; NODE→nearby-node ranking |
| 3 | `TargetEvidenceService.kt:118` `sourceSelectedNode` | AREA→`evidenceNodes.first`; NODE→`selectedNode` |
| 4 | `TargetEvidenceService.kt:122` `sourceNearbyNodes` | AREA→`evidenceNodes.drop(1)`; NODE→`evidenceNodes` |
| 5 | `TargetEvidenceService.kt:139` `target` | AREA→`AnnotationTargetDto.Area`; NODE→`AnnotationTargetDto.Node` |
| 6 | `TargetEvidenceService.kt:181` `targetEvidenceFor` | AREA→`TargetKind.AREA`; NODE→`TargetKind.NODE` |
| 7 | `TargetEvidenceService.kt:202` `targetReliabilityFor` | AREA→`TargetKind.AREA`; NODE→`TargetKind.NODE` |
| 8 | `TargetEvidenceService.kt:236` `withRefreshedSourceEvidence` | DTO `Area`→AREA; `Node`→NODE (reverse map) |
| 9 | `TargetEvidenceService.kt:240` bounds | DTO `Area`/`Node`→`boundsInWindow` (interface lacks the property) |

Site 9 is a pure representational gap: both DTO branches carry `boundsInWindow`
but the sealed interface does not expose it, forcing a `when`.

The evidence-ranking logic the arms call (`areaEvidenceNodes`,
`nodeEvidenceNodes`, `AreaEvidenceNode`, and the geometry helpers
`intersects` / `intersectionArea` / `centerDistanceTo`) currently lives as
private members of `FeedbackTargetValidator`.

## Design

### The abstraction: `FeedbackTargetStrategy`

A new `internal sealed interface FeedbackTargetStrategy` in the `target` package
owns every per-kind decision. Two stateless implementations, `AreaTargetStrategy`
and `NodeTargetStrategy`, carry the behavior currently split across the arms.
The kind-specific evidence ranking moves out of the validator and into the
strategies, where it is cohesive with the rest of that kind's behavior.

```kotlin
internal sealed interface FeedbackTargetStrategy {
    val type: FeedbackTargetType
    val targetKind: TargetKind
    fun resolveSelectedNode(screen: SnapshotDto, nodeUid: String?, missingNodeContext: String): FixThisNode?
    fun evidenceNodes(screen: SnapshotDto, storedBounds: FixThisRect, selectedNode: FixThisNode?): List<FixThisNode>
    fun sourceSelectedNode(target: ValidatedFeedbackTarget): FixThisNode?
    fun sourceNearbyNodes(target: ValidatedFeedbackTarget): List<FixThisNode>
    fun annotationTarget(target: ValidatedFeedbackTarget): AnnotationTargetDto
}
```

### The two sanctioned discriminating sites (변경 보호 boundary)

The wire enum and the persisted DTO are external contracts; mapping them into
the polymorphic world is unavoidable adapter work (the lecture's 표현적 차이).
The design permits **exactly two** type-discriminating sites and forbids a third:

```kotlin
// 1. wire enum -> strategy (the change-protection boundary)
internal fun FeedbackTargetType.strategy(): FeedbackTargetStrategy = when (this) {
    FeedbackTargetType.AREA -> AreaTargetStrategy
    FeedbackTargetType.NODE -> NodeTargetStrategy
}

// 2. persisted DTO -> wire enum (refresh path adapter)
internal fun AnnotationTargetDto.targetType(): FeedbackTargetType = when (this) {
    is AnnotationTargetDto.Area -> FeedbackTargetType.AREA
    is AnnotationTargetDto.Node -> FeedbackTargetType.NODE
}
```

Switch site #9 is removed entirely by hoisting the shared property onto the
sealed interface (the interface should expose what both members already have):

```kotlin
sealed interface AnnotationTargetDto {
    val boundsInWindow: FixThisRect              // hoisted
    data class Node(val nodeUid: String, override val boundsInWindow: FixThisRect) : AnnotationTargetDto
    data class Area(override val boundsInWindow: FixThisRect) : AnnotationTargetDto
}
```

### Call-site collapse

- `FeedbackTargetValidator` keeps only kind-invariant work (blank-comment guard,
  finite/positive bounds, bounds-inside-screenshot) and delegates the two
  varying steps: `selection.targetType.strategy().resolveSelectedNode(...)` and
  `.evidenceNodes(...)`.
- `TargetEvidenceService` replaces arms #3–#7 with `strategy.sourceSelectedNode`,
  `.sourceNearbyNodes`, `.annotationTarget`, and `strategy.targetKind`.
- `withRefreshedSourceEvidence` uses `target.targetType()` (adapter #2) and
  `target.boundsInWindow` (now a polymorphic property).

### Design invariant (enforced)

> Within `session/target`, the only `when` over `FeedbackTargetType` or over
> `AnnotationTargetDto` subtypes are the two adapter functions above. Any per-kind
> behavior decision must be a `FeedbackTargetStrategy` method.

A guard test, `FeedbackTargetStrategyTest`, asserts every enum value resolves to
a strategy whose `type`/`targetKind` agree, so adding an enum constant without a
strategy fails compilation-or-test, not silently at runtime:

```kotlin
@Test fun everyTargetTypeResolvesToAMatchingStrategy() {
    FeedbackTargetType.entries.forEach { type ->
        val strategy = type.strategy()
        assertEquals(type, strategy.type)
    }
}
```

## Behavior Preservation

This is a **behavior-preserving** refactor. Guarantees:

- All thrown messages are byte-identical (`"Node feedback requires nodeUid"`,
  `"Selected node does not exist on $missingNodeContext: $uid"`,
  `"evidenceNodesFor(NODE) requires a non-null selectedNode resolved upstream"`,
  `"ValidatedFeedbackTarget(targetType=NODE) must carry a non-null selectedNode"`).
- Evidence ranking order (overlap → overlap-area → center-distance → area → uid
  for AREA; center-distance → area → uid for NODE) and `MaxEvidenceNodes = 8`
  are moved verbatim, not re-derived.
- `AnnotationTargetDto` JSON stays identical: `boundsInWindow` is hoisted as an
  `override val` on the existing constructor properties, so serialized field
  names and `@SerialName` discriminators (`semantics_node` / `visual_area`) are
  unchanged. (Persisted-JSON field names are a compatibility contract — see
  `CLAUDE.md` / ADR-0006.)
- Public types `ValidatedFeedbackTarget`, `FeedbackTargetSelection`,
  `FeedbackTargetValidationOptions`, `FeedbackTargetValidationRequest`, and the
  `FeedbackTargetValidator` / `TargetEvidenceService` method signatures are
  unchanged. Strategy types are `internal`.

Existing suites are the regression oracle: `FeedbackTargetValidatorTest`,
`TargetEvidenceServiceTest`, `TargetReliabilityDtoTest`, plus the full
`:fixthis-mcp` suite (~801 tests).

## Affected Files

| File | Change |
|------|--------|
| `session/target/FeedbackTargetStrategy.kt` | **Create** — sealed interface, `AreaTargetStrategy`, `NodeTargetStrategy`, `strategy()` / `targetType()` adapters, moved ranking + geometry helpers |
| `session/target/FeedbackTargetValidator.kt` | **Modify** — delegate node resolution + evidence to strategy; drop two `when` arms and the moved private helpers |
| `session/target/TargetEvidenceService.kt` | **Modify** — replace arms #3–#7 with strategy calls; refresh path uses `targetType()` + polymorphic `boundsInWindow` |
| `session/dto/SessionDtoModels.kt` | **Modify** — hoist `val boundsInWindow` onto `AnnotationTargetDto` |
| `session/target/FeedbackTargetStrategyTest.kt` (test) | **Create** — strategy unit + exhaustiveness guard |
| `docs/architecture/adr/0009-feedback-target-type-polymorphism.md` | **Create** — record the decision + invariant |

## Risks & Mitigations

- **Risk:** Moving evidence ranking changes ordering subtly. **Mitigation:** copy
  the comparators verbatim; `FeedbackTargetValidatorTest`'s overlap/fallback
  ordering assertions catch drift.
- **Risk:** Hoisting `boundsInWindow` breaks serialization. **Mitigation:** it is
  the same property, only the interface declaration is added; covered by existing
  round-trip/persistence tests. Verify with the full matrix.
- **Risk:** `detekt` complains about new file/`when`. **Mitigation:** two adapters
  only; run `detekt` + `spotless` in verification.

## Out of Scope

- No new target kind is added; this only makes adding one cheap.
- No wire/enum rename, no `FeedbackTargetType` behavior (it stays a pure
  `@Serializable` data enum in `console`).
- The `FeedbackSessionStoreDelegate` cohesion split (separately identified) is a
  different spec.

## ADR

Decision recorded in
`docs/architecture/adr/0009-feedback-target-type-polymorphism.md` (created by the
plan): use an internal strategy in `session/target`; permit exactly two
type-adapter sites; enforce with `FeedbackTargetStrategyTest`.
