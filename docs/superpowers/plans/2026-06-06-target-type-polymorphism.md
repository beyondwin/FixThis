# Feedback Target Type Polymorphism Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `FeedbackTargetType` (`AREA`/`NODE`) `when`-switches scattered across the `session/target` pipeline with a polymorphic `FeedbackTargetStrategy`, so each target kind's behavior is cohesive and adding a kind is additive (OCP).

**Architecture:** Introduce an `internal sealed interface FeedbackTargetStrategy` with stateless `AreaTargetStrategy` / `NodeTargetStrategy` implementations that own per-kind behavior (node resolution, evidence ranking, source-node selection, `AnnotationTargetDto` construction, `TargetKind`). The wire enum maps to a strategy at one place (`FeedbackTargetType.strategy()`); the persisted DTO maps to the enum at one place (`AnnotationTargetDto.targetType()`); switch site #9 is removed by hoisting `boundsInWindow` onto the sealed `AnnotationTargetDto` interface. Behavior-preserving.

**Tech Stack:** Kotlin, kotlin.test (JUnit), Gradle (`:fixthis-mcp` module), detekt, spotless. All work is in `fixthis-mcp` except the spec/ADR docs.

Design spec: `docs/superpowers/specs/2026-06-06-target-type-polymorphism-design.md`

---

## File Structure

- Modify `fixthis-mcp/.../session/dto/SessionDtoModels.kt` — hoist `val boundsInWindow` onto `AnnotationTargetDto`.
- Create `fixthis-mcp/.../session/target/FeedbackTargetStrategy.kt` — sealed interface, two impls, `strategy()` / `targetType()` adapters, moved ranking + geometry helpers.
- Modify `fixthis-mcp/.../session/target/FeedbackTargetValidator.kt` — delegate to strategy; delete moved helpers.
- Modify `fixthis-mcp/.../session/target/TargetEvidenceService.kt` — replace arms #3–#7 + refresh path with strategy calls.
- Create `fixthis-mcp/src/test/.../session/FeedbackTargetStrategyTest.kt` — strategy unit + exhaustiveness guard.
- Create `docs/architecture/adr/0009-feedback-target-type-polymorphism.md`.

### Reference facts (verified against current code, 2026-06-06)

- `FeedbackTargetType` (enum, `console/AnnotationRequestModels.kt`): `AREA`, `NODE`.
- `AnnotationTargetDto` (`session/dto/SessionDtoModels.kt:179`): sealed; `Node(nodeUid, boundsInWindow)`, `Area(boundsInWindow)`. Interface does NOT currently expose `boundsInWindow`.
- `ValidatedFeedbackTarget(targetType, selectedNode, storedBounds, evidenceNodes)` lives in `FeedbackTargetValidator.kt`.
- `TargetKind` (core): `AREA`, `NODE`.
- `FixThisNode.hasMeaningfulSemantic()` is a member (no import needed).
- Evidence ranking helpers `areaEvidenceNodes`, `nodeEvidenceNodes`, `AreaEvidenceNode`, `MaxEvidenceNodes`, and file-private extensions `allNodes()`, `intersects()`, `intersectionArea()`, `centerDistanceTo()` currently live in `FeedbackTargetValidator.kt`.
- Test fixtures (`FeedbackTargetValidatorTest.kt`): `screenWith(vararg nodes)`, `node(uid, bounds, text)`.
- Exact error messages to preserve: `"Node feedback requires nodeUid"`, `"Selected node does not exist on $missingNodeContext: $uid"`, `"evidenceNodesFor(NODE) requires a non-null selectedNode resolved upstream"`, `"ValidatedFeedbackTarget(targetType=NODE) must carry a non-null selectedNode"`.
- Module build/test commands: `CONTRIBUTING.md` (`./gradlew :fixthis-mcp:test`, full matrix, detekt, spotless).

---

## Task 1: Hoist `boundsInWindow` onto `AnnotationTargetDto`

Removes switch site #9 by making the shared property polymorphic.

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/dto/SessionDtoModels.kt:179-187`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/AnnotationTargetDtoTest.kt`

- [ ] **Step 1: Write the failing test**

Create `AnnotationTargetDtoTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import kotlin.test.Test
import kotlin.test.assertEquals

class AnnotationTargetDtoTest {
    @Test
    fun boundsInWindowIsReadableThroughTheSealedInterface() {
        val rect = FixThisRect(1f, 2f, 3f, 4f)
        val targets: List<AnnotationTargetDto> = listOf(
            AnnotationTargetDto.Area(rect),
            AnnotationTargetDto.Node(nodeUid = "n1", boundsInWindow = rect),
        )
        targets.forEach { assertEquals(rect, it.boundsInWindow) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-mcp:test --tests "*AnnotationTargetDtoTest"`
Expected: FAIL — `boundsInWindow` unresolved on `AnnotationTargetDto`.

- [ ] **Step 3: Hoist the property**

In `SessionDtoModels.kt`, replace the sealed interface body:

```kotlin
@Serializable
sealed interface AnnotationTargetDto {
    val boundsInWindow: FixThisRect

    @Serializable
    @SerialName("semantics_node")
    data class Node(val nodeUid: String, override val boundsInWindow: FixThisRect) : AnnotationTargetDto

    @Serializable
    @SerialName("visual_area")
    data class Area(override val boundsInWindow: FixThisRect) : AnnotationTargetDto
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew :fixthis-mcp:test --tests "*AnnotationTargetDtoTest" --tests "*SessionPersistence*" --tests "*SessionDomainMappers*"`
Expected: PASS (serialization unchanged — same property, only interface declaration added).

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/dto/SessionDtoModels.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/AnnotationTargetDtoTest.kt
git commit -m "refactor(mcp): expose boundsInWindow on AnnotationTargetDto interface"
```

---

## Task 2: Create `FeedbackTargetStrategy` (interface, impls, adapters, moved helpers)

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/target/FeedbackTargetStrategy.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackTargetStrategyTest.kt`

- [ ] **Step 1: Write the failing test**

Create `FeedbackTargetStrategyTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TargetKind
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotRootDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import io.github.beyondwin.fixthis.mcp.session.target.ValidatedFeedbackTarget
import io.github.beyondwin.fixthis.mcp.session.target.strategy
import io.github.beyondwin.fixthis.mcp.session.target.targetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackTargetStrategyTest {
    @Test
    fun everyTargetTypeResolvesToAMatchingStrategy() {
        FeedbackTargetType.entries.forEach { type ->
            assertEquals(type, type.strategy().type)
        }
    }

    @Test
    fun targetKindMatchesType() {
        assertEquals(TargetKind.AREA, FeedbackTargetType.AREA.strategy().targetKind)
        assertEquals(TargetKind.NODE, FeedbackTargetType.NODE.strategy().targetKind)
    }

    @Test
    fun dtoTargetTypeAdapterRoundTrips() {
        val rect = FixThisRect(0f, 0f, 1f, 1f)
        assertEquals(FeedbackTargetType.AREA, AnnotationTargetDto.Area(rect).targetType())
        assertEquals(FeedbackTargetType.NODE, AnnotationTargetDto.Node("n", rect).targetType())
    }

    @Test
    fun areaResolvesNoSelectedNode() {
        val strategy = FeedbackTargetType.AREA.strategy()
        assertNull(strategy.resolveSelectedNode(screenWith(), nodeUid = null, missingNodeContext = "screen"))
    }

    @Test
    fun nodeResolveRequiresUid() {
        val error = assertFailsWith<IllegalArgumentException> {
            FeedbackTargetType.NODE.strategy().resolveSelectedNode(screenWith(), nodeUid = null, missingNodeContext = "screen")
        }
        assertEquals("Node feedback requires nodeUid", error.message)
    }

    @Test
    fun nodeResolveReportsMissingNodeWithContext() {
        val error = assertFailsWith<IllegalArgumentException> {
            FeedbackTargetType.NODE.strategy().resolveSelectedNode(screenWith(), nodeUid = "missing", missingNodeContext = "preview")
        }
        assertEquals("Selected node does not exist on preview: missing", error.message)
    }

    @Test
    fun annotationTargetMatchesKind() {
        val rect = FixThisRect(5f, 5f, 50f, 50f)
        val selected = node("sel", rect, listOf("Save"))
        val area = FeedbackTargetType.AREA.strategy().annotationTarget(
            ValidatedFeedbackTarget(FeedbackTargetType.AREA, null, rect, emptyList()),
        )
        val nodeTarget = FeedbackTargetType.NODE.strategy().annotationTarget(
            ValidatedFeedbackTarget(FeedbackTargetType.NODE, selected, rect, emptyList()),
        )
        assertTrue(area is AnnotationTargetDto.Area)
        assertTrue(nodeTarget is AnnotationTargetDto.Node && nodeTarget.nodeUid == "sel")
    }

    private fun screenWith(vararg nodes: FixThisNode): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 100L,
        displayName = "Checkout",
        screenshot = SnapshotScreenshotDto(width = 400, height = 400, desktopFullPath = "/tmp/screen.png"),
        roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 400f, 400f), mergedNodes = nodes.toList())),
    )

    private fun node(uid: String, bounds: FixThisRect, text: List<String> = emptyList()): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
    )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-mcp:test --tests "*FeedbackTargetStrategyTest"`
Expected: FAIL — `FeedbackTargetStrategy`, `strategy()`, `targetType()` unresolved.

- [ ] **Step 3: Create the strategy file**

Create `FeedbackTargetStrategy.kt` (ranking + geometry helpers moved verbatim from `FeedbackTargetValidator.kt`):

```kotlin
package io.github.beyondwin.fixthis.mcp.session.target

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TargetKind
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto

/**
 * Per-kind behavior for a feedback target. The wire enum [FeedbackTargetType] is
 * mapped to a strategy at exactly one place ([strategy]); the persisted
 * [AnnotationTargetDto] is mapped to the enum at one place ([targetType]). Every
 * other target-kind decision dispatches through this interface, so a new kind is
 * one new implementation, not edits across scattered `when` blocks.
 *
 * Invariant (see docs/superpowers/specs/2026-06-06-target-type-polymorphism-design.md
 * and ADR-0009): no third `when` over target kind may exist in this package.
 */
internal sealed interface FeedbackTargetStrategy {
    val type: FeedbackTargetType
    val targetKind: TargetKind

    fun resolveSelectedNode(screen: SnapshotDto, nodeUid: String?, missingNodeContext: String): FixThisNode?

    fun evidenceNodes(screen: SnapshotDto, storedBounds: FixThisRect, selectedNode: FixThisNode?): List<FixThisNode>

    fun sourceSelectedNode(target: ValidatedFeedbackTarget): FixThisNode?

    fun sourceNearbyNodes(target: ValidatedFeedbackTarget): List<FixThisNode>

    fun annotationTarget(target: ValidatedFeedbackTarget): AnnotationTargetDto
}

internal object AreaTargetStrategy : FeedbackTargetStrategy {
    override val type = FeedbackTargetType.AREA
    override val targetKind = TargetKind.AREA

    override fun resolveSelectedNode(screen: SnapshotDto, nodeUid: String?, missingNodeContext: String): FixThisNode? = null

    override fun evidenceNodes(screen: SnapshotDto, storedBounds: FixThisRect, selectedNode: FixThisNode?): List<FixThisNode> =
        areaEvidenceNodes(screen, storedBounds)

    override fun sourceSelectedNode(target: ValidatedFeedbackTarget): FixThisNode? = target.evidenceNodes.firstOrNull()

    override fun sourceNearbyNodes(target: ValidatedFeedbackTarget): List<FixThisNode> = target.evidenceNodes.drop(1)

    override fun annotationTarget(target: ValidatedFeedbackTarget): AnnotationTargetDto =
        AnnotationTargetDto.Area(target.storedBounds)
}

internal object NodeTargetStrategy : FeedbackTargetStrategy {
    override val type = FeedbackTargetType.NODE
    override val targetKind = TargetKind.NODE

    override fun resolveSelectedNode(screen: SnapshotDto, nodeUid: String?, missingNodeContext: String): FixThisNode {
        val uid = nodeUid?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Node feedback requires nodeUid")
        return screen.allNodes().firstOrNull { node -> node.uid == uid }
            ?: throw IllegalArgumentException("Selected node does not exist on $missingNodeContext: $uid")
    }

    override fun evidenceNodes(screen: SnapshotDto, storedBounds: FixThisRect, selectedNode: FixThisNode?): List<FixThisNode> =
        nodeEvidenceNodes(
            screen,
            requireNotNull(selectedNode) {
                "evidenceNodesFor(NODE) requires a non-null selectedNode resolved upstream"
            },
        )

    override fun sourceSelectedNode(target: ValidatedFeedbackTarget): FixThisNode? = target.selectedNode

    override fun sourceNearbyNodes(target: ValidatedFeedbackTarget): List<FixThisNode> = target.evidenceNodes

    override fun annotationTarget(target: ValidatedFeedbackTarget): AnnotationTargetDto {
        val nodeForTarget = requireNotNull(target.selectedNode) {
            "ValidatedFeedbackTarget(targetType=NODE) must carry a non-null selectedNode"
        }
        return AnnotationTargetDto.Node(nodeUid = nodeForTarget.uid, boundsInWindow = target.storedBounds)
    }
}

internal fun FeedbackTargetType.strategy(): FeedbackTargetStrategy = when (this) {
    FeedbackTargetType.AREA -> AreaTargetStrategy
    FeedbackTargetType.NODE -> NodeTargetStrategy
}

internal fun AnnotationTargetDto.targetType(): FeedbackTargetType = when (this) {
    is AnnotationTargetDto.Area -> FeedbackTargetType.AREA
    is AnnotationTargetDto.Node -> FeedbackTargetType.NODE
}

private const val MaxEvidenceNodes = 8

private data class AreaEvidenceNode(
    val node: FixThisNode,
    val overlaps: Boolean,
    val overlapArea: Float,
    val centerDistance: Float,
)

private fun areaEvidenceNodes(screen: SnapshotDto, bounds: FixThisRect): List<FixThisNode> {
    val evidenceNodes = screen.allNodes()
        .asSequence()
        .filter { it.hasMeaningfulSemantic() }
        .map { node ->
            AreaEvidenceNode(
                node = node,
                overlaps = node.boundsInWindow.intersects(bounds),
                overlapArea = node.boundsInWindow.intersectionArea(bounds),
                centerDistance = node.boundsInWindow.centerDistanceTo(bounds),
            )
        }
        .toList()
    val hasOverlappingEvidence = evidenceNodes.any { it.overlaps }
    return evidenceNodes
        .asSequence()
        .filter { evidence -> if (hasOverlappingEvidence) evidence.overlaps else true }
        .sortedWith(
            compareByDescending<AreaEvidenceNode> { it.overlaps }
                .thenByDescending { it.overlapArea }
                .thenBy { it.centerDistance }
                .thenBy { it.node.boundsInWindow.area }
                .thenBy { it.node.uid },
        )
        .map { it.node }
        .distinctBy { it.uid }
        .take(MaxEvidenceNodes)
        .toList()
}

private fun nodeEvidenceNodes(screen: SnapshotDto, selectedNode: FixThisNode): List<FixThisNode> = screen.allNodes()
    .asSequence()
    .filter { it.uid != selectedNode.uid }
    .filter { it.rootIndex == selectedNode.rootIndex }
    .filter { it.hasMeaningfulSemantic() }
    .sortedWith(
        compareBy<FixThisNode> { it.boundsInWindow.centerDistanceTo(selectedNode.boundsInWindow) }
            .thenBy { it.boundsInWindow.area }
            .thenBy { it.uid },
    )
    .distinctBy { it.uid }
    .take(MaxEvidenceNodes)
    .toList()

private fun SnapshotDto.allNodes(): List<FixThisNode> = roots.flatMap { root -> root.mergedNodes + root.unmergedNodes }

private fun FixThisRect.intersects(other: FixThisRect): Boolean =
    left < other.right && right > other.left && top < other.bottom && bottom > other.top

private fun FixThisRect.intersectionArea(other: FixThisRect): Float {
    val width = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
    val height = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
    return width * height
}

private fun FixThisRect.centerDistanceTo(other: FixThisRect): Float {
    val dx = ((left + right) / 2f) - ((other.left + other.right) / 2f)
    val dy = ((top + bottom) / 2f) - ((other.top + other.bottom) / 2f)
    return dx * dx + dy * dy
}
```

- [ ] **Step 4: Run test to verify pass**

Run: `./gradlew :fixthis-mcp:test --tests "*FeedbackTargetStrategyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/target/FeedbackTargetStrategy.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackTargetStrategyTest.kt
git commit -m "feat(mcp): add polymorphic FeedbackTargetStrategy for target kinds"
```

---

## Task 3: Delegate `FeedbackTargetValidator` to the strategy

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/target/FeedbackTargetValidator.kt`
- Test (regression oracle, unchanged): `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackTargetValidatorTest.kt`

- [ ] **Step 1: Run the oracle test to confirm green baseline**

Run: `./gradlew :fixthis-mcp:test --tests "*FeedbackTargetValidatorTest"`
Expected: PASS (before edit).

- [ ] **Step 2: Replace the switch arms with strategy delegation**

In `FeedbackTargetValidator.kt`, change `validate` to resolve the strategy once, and delete `selectedNodeFor`, `evidenceNodesFor`, `areaEvidenceNodes`, `nodeEvidenceNodes`, `AreaEvidenceNode`, the `MaxEvidenceNodes` companion, and the file-bottom `allNodes`/`intersects`/`intersectionArea`/`centerDistanceTo` extensions (now in `FeedbackTargetStrategy.kt`). Keep the data classes (`ValidatedFeedbackTarget`, `FeedbackTargetSelection`, `FeedbackTargetValidationOptions`, `FeedbackTargetValidationRequest`) and the bounds validators:

```kotlin
class FeedbackTargetValidator {
    fun validate(request: FeedbackTargetValidationRequest): ValidatedFeedbackTarget {
        val selection = request.selection
        val options = request.options
        if (!options.allowBlankComment) {
            require(options.comment.isNotBlank()) { "Feedback comment must not be blank" }
        }
        val strategy = selection.targetType.strategy()
        val selectedNode = strategy.resolveSelectedNode(
            request.screen,
            selection.nodeUid,
            options.missingNodeContext,
        )
        val storedBounds = selectedNode?.boundsInWindow ?: selection.bounds
        validateFinitePositiveBounds(storedBounds)
        validateBoundsInsideScreenshot(request.screen, storedBounds)
        return ValidatedFeedbackTarget(
            targetType = selection.targetType,
            selectedNode = selectedNode,
            storedBounds = storedBounds,
            evidenceNodes = strategy.evidenceNodes(request.screen, storedBounds, selectedNode),
        )
    }

    private fun validateFinitePositiveBounds(bounds: FixThisRect) {
        val values = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        require(values.all { it.isFinite() }) { "Selection bounds must be finite" }
        require(bounds.right > bounds.left && bounds.bottom > bounds.top) {
            "Selection bounds must have positive size"
        }
    }

    private fun validateBoundsInsideScreenshot(screen: SnapshotDto, bounds: FixThisRect) {
        val width = screen.screenshot?.width?.toFloat() ?: return
        val height = screen.screenshot?.height?.toFloat() ?: return
        require(bounds.left >= 0f && bounds.top >= 0f && bounds.right <= width && bounds.bottom <= height) {
            "Selection bounds must be inside the screenshot"
        }
    }
}
```

Remove the now-unused imports (`FixThisNode` may still be needed by the data classes — keep imports that remain referenced; delete `TreeKind`-style unused ones if any). The data class declarations at the top of the file stay.

- [ ] **Step 3: Run the oracle test to verify still green**

Run: `./gradlew :fixthis-mcp:test --tests "*FeedbackTargetValidatorTest" --tests "*FeedbackTargetStrategyTest"`
Expected: PASS — behavior unchanged.

- [ ] **Step 4: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/target/FeedbackTargetValidator.kt
git commit -m "refactor(mcp): delegate target validation to FeedbackTargetStrategy"
```

---

## Task 4: Rewire `TargetEvidenceService` to the strategy

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/target/TargetEvidenceService.kt`
- Test (regression oracle): `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt`

- [ ] **Step 1: Run the oracle test to confirm green baseline**

Run: `./gradlew :fixthis-mcp:test --tests "*TargetEvidenceServiceTest"`
Expected: PASS (before edit).

- [ ] **Step 2: Replace arms #3–#5 in `buildFeedbackItem`**

In `buildFeedbackItem(... validatedTarget ...)` replace the three `when` blocks (`sourceSelectedNode`, `sourceNearbyNodes`, `target`):

```kotlin
        val strategy = validatedTarget.targetType.strategy()
        val sourceSelectedNode = strategy.sourceSelectedNode(validatedTarget)
        val sourceNearbyNodes = strategy.sourceNearbyNodes(validatedTarget)
        val sourceCandidates = sourceCandidatesFor(sourceIndex, sourceSelectedNode, sourceNearbyNodes, screen.activityName)
        val targetEvidence = targetEvidenceFor(
            targetType = validatedTarget.targetType,
            selectedNode = validatedTarget.selectedNode,
            screen = screen,
            sourceCandidates = sourceCandidates,
        )
        val targetReliability = targetReliabilityFor(
            screen = screen,
            validatedTarget = validatedTarget,
            sourceCandidates = sourceCandidates,
            targetEvidence = targetEvidence,
        )
        val target = strategy.annotationTarget(validatedTarget)
```

- [ ] **Step 3: Replace arm #6 in `targetEvidenceFor`**

```kotlin
    fun targetEvidenceFor(
        targetType: FeedbackTargetType,
        selectedNode: FixThisNode?,
        screen: SnapshotDto,
        sourceCandidates: List<SourceCandidate>,
    ): TargetEvidence = TargetEvidenceFactory.build(
        TargetEvidenceInput(
            targetKind = targetType.strategy().targetKind,
            selectedNode = selectedNode,
            mergedNodes = screen.roots.flatMap { root -> root.mergedNodes },
            sourceCandidates = sourceCandidates,
            screenshotKinds = screen.screenshot.availableKinds(),
        ),
    )
```

- [ ] **Step 4: Replace arm #7 in `targetReliabilityFor`**

Replace the inline `targetKind = when (...) { ... }` with:

```kotlin
                targetKind = validatedTarget.targetType.strategy().targetKind,
```

- [ ] **Step 5: Replace arms #8–#9 in `withRefreshedSourceEvidence`**

```kotlin
    private fun AnnotationDto.withRefreshedSourceEvidence(
        screen: SnapshotDto,
        sourceCandidates: List<SourceCandidate>,
        sourceIndex: SourceIndex,
    ): AnnotationDto {
        val targetType = target.targetType()
        val bounds = target.boundsInWindow
        val evidence = targetEvidenceFor(
            targetType = targetType,
            selectedNode = selectedNode,
            screen = screen,
            sourceCandidates = sourceCandidates,
        )
        val reliability = targetReliabilityFor(
            screen = screen,
            validatedTarget = ValidatedFeedbackTarget(
                targetType = targetType,
                selectedNode = selectedNode,
                storedBounds = bounds,
                evidenceNodes = nearbyNodes,
            ),
            sourceCandidates = sourceCandidates,
            targetEvidence = evidence,
        )
        val refreshed = copy(
            sourceCandidates = sourceCandidates,
            targetEvidence = evidence,
            targetReliability = reliability,
        )
        return refreshed.copy(
            editSurfaceCandidates = EditSurfaceCandidateService.build(
                refreshed,
                screen,
                conventions = TestTagConventionSet.fromPatternStrings(sourceIndex.testTagConventions),
            ),
        )
    }
```

- [ ] **Step 6: Remove now-unused imports**

`TargetKind` is no longer referenced directly in `TargetEvidenceService.kt` (the strategy supplies it). Delete `import io.github.beyondwin.fixthis.compose.core.model.TargetKind` if the IDE/compiler flags it unused. Keep `AnnotationTargetDto` (still referenced by the `Area`/`Node` adapter usage is now in the strategy file — verify: if no longer referenced here, remove its import too).

- [ ] **Step 7: Run the oracle + module tests**

Run: `./gradlew :fixthis-mcp:test --tests "*TargetEvidenceServiceTest" --tests "*TargetReliabilityDtoTest"`
Expected: PASS — behavior unchanged.

- [ ] **Step 8: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/target/TargetEvidenceService.kt
git commit -m "refactor(mcp): dispatch target evidence through FeedbackTargetStrategy"
```

---

## Task 5: Record ADR-0009

**Files:**
- Create: `docs/architecture/adr/0009-feedback-target-type-polymorphism.md`
- Modify: `docs/architecture/adr/README.md` (append the index row)

- [ ] **Step 1: Write the ADR**

```markdown
# ADR-0009: Feedback Target Type Polymorphism

- Status: Accepted
- Date: 2026-06-06

## Context

`FeedbackTargetType` (`AREA`/`NODE`) behavior was decided by ~8 independent
`when (targetType)` arms across `FeedbackTargetValidator` and
`TargetEvidenceService`. Adding or changing a target kind meant editing every
arm (shotgun surgery, low cohesion). Target/evidence is an active change hotspot.

## Decision

Introduce an `internal sealed interface FeedbackTargetStrategy` in
`session/target` with stateless `AreaTargetStrategy` / `NodeTargetStrategy`
implementations that own per-kind behavior. Exactly two type-discriminating
sites are permitted: `FeedbackTargetType.strategy()` (wire enum → strategy) and
`AnnotationTargetDto.targetType()` (persisted DTO → enum). The shared
`boundsInWindow` is hoisted onto the `AnnotationTargetDto` interface to remove
the last incidental `when`. `FeedbackTargetStrategyTest` enforces that every
enum value resolves to a matching strategy.

## Consequences

- A new target kind is one new `FeedbackTargetStrategy` implementation plus two
  adapter arms — the evidence/validation pipeline is closed to the change (OCP).
- The two adapter functions are the only sanctioned target-kind `when` sites in
  the package; a third is a design regression.
- Behavior, error messages, evidence ordering, and persisted JSON are unchanged.

## Alternatives Considered

- Put behavior on the `FeedbackTargetType` enum itself. Rejected: the enum is a
  `@Serializable` wire model in `console`; coupling it to `SnapshotDto`,
  `FixThisNode`, and evidence ranking would invert the dependency direction.
- Leave the `when` arms and rely on the exhaustive-`when` compiler check.
  Rejected: exhaustiveness catches a missing arm but not the scattering itself;
  cohesion and OCP still suffer.
```

- [ ] **Step 2: Append the README index row**

Add under the existing ADR list in `docs/architecture/adr/README.md`:

```markdown
- [ADR-0009](0009-feedback-target-type-polymorphism.md) — Feedback target type polymorphism (strategy over scattered `when`)
```

- [ ] **Step 3: Commit**

```bash
git add docs/architecture/adr/0009-feedback-target-type-polymorphism.md docs/architecture/adr/README.md
git commit -m "docs(architecture): add ADR-0009 feedback target type polymorphism"
```

---

## Task 6: Full verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full local matrix**

Run the required local checks from `CONTRIBUTING.md#required-local-checks`:

```bash
./gradlew :fixthis-mcp:test :fixthis-compose-core:test
./gradlew detekt
./gradlew spotlessCheck
git diff --check
```

Expected: all green; ~801+ `:fixthis-mcp` tests pass; no detekt/spotless violations.

- [ ] **Step 2: Confirm the invariant holds**

Run: `grep -rn "when *(" fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/target/ | grep -iE "targetType|AnnotationTargetDto"`
Expected: only the two adapter functions in `FeedbackTargetStrategy.kt`.

- [ ] **Step 3: Final commit (if verification produced formatting fixes)**

```bash
git add -A
git commit -m "chore(mcp): apply spotless formatting for target strategy refactor"
```

---

## Self-Review

- **Spec coverage:** Tasks 1–4 cover all 9 switch sites from the spec table (site 9 → Task 1; sites 1–2 → Task 3; sites 3–7 → Task 4; site 8 → Task 4 via `targetType()`). The invariant + guard test (spec §"Design invariant") → Task 2 + Task 6 Step 2. ADR (spec §ADR) → Task 5.
- **Placeholder scan:** every code step shows full code; no TBD/TODO.
- **Type consistency:** `strategy()`, `targetType()`, `FeedbackTargetStrategy`, `ValidatedFeedbackTarget`, `AnnotationTargetDto.boundsInWindow`, `TargetKind` used identically across Tasks 2–4 and the tests.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-06-target-type-polymorphism.md`. Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks.
2. **Inline Execution** — execute tasks in this session via executing-plans with checkpoints.

This plan is ≤6 tasks with linear coupling, so inline single-session execution is reasonable per the multi-agent guidance.
