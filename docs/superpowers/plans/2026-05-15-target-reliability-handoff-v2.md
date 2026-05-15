# Target Reliability Handoff v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add target reliability metadata so FixThis handoffs clearly distinguish strong Compose targets from visual-only, stale, or possible interop targets.

**Architecture:** Add pure reliability models and calculators in `:fixthis-compose-core`, persist the derived result as an optional `targetReliability` sibling field on each MCP `AnnotationDto`, then render concise confidence/warning lines in handoff Markdown and console rows. Sidekick already sends root bounds and semantics nodes, so interop-like coverage can be computed from existing snapshot evidence without a bridge protocol bump.

**Tech Stack:** Kotlin/JVM, Kotlin serialization, Jetpack Compose sample app, Node.js console tests, existing Gradle and npm test runners.

---

## Scope Check

The approved spec spans core models, MCP persistence/rendering, console presentation, sample coverage, and docs. These are not independent products: each layer carries the same reliability metadata from capture to handoff. The tasks below are phased so Task 1 through Task 5 produce useful handoff metadata before console and sample polish land.

## File Structure

- Create `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculator.kt` for pure confidence/warning calculation.
- Create `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculatorTest.kt` for core coverage.
- Modify `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt` to add serializable reliability models and optional `FixThisAnnotation.targetReliability`.
- Modify `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/annotation/Annotation.kt` so the domain annotation carries the same optional reliability object.
- Modify `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt` to render reliability for direct core formatting.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt` and `SessionDomainMappers.kt` to persist and map `targetReliability`.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt` to derive reliability when items are built.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt` to add force-save and fingerprint-unavailable warnings at commit time.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt` and `FeedbackQueueFormatter.kt` to render concise reliability lines.
- Modify `fixthis-mcp/src/main/console/annotations.js`, `fixthis-mcp/src/main/console/rendering.js`, and `fixthis-mcp/src/main/resources/console/styles.css` to show compact warning badges.
- Modify `sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt` to include an AndroidView interop fixture.
- Modify `docs/guides/agents.md`, `docs/reference/output-schema.md`, and `docs/reference/mcp-tools.md` to explain `targetReliability`.

## Task 1: Core Reliability Model And Calculator

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculator.kt`
- Create: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculatorTest.kt`

- [ ] **Step 1: Write the failing core calculator test**

Create `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculatorTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.compose.core.target

import io.github.beyondwin.fixthis.compose.core.model.EvidenceQuality
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.IdentityHint
import io.github.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.github.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetEvidence
import io.github.beyondwin.fixthis.compose.core.model.TargetKind
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityInput
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetReliabilityCalculatorTest {
    @Test
    fun highConfidenceForMeaningfulNodeWithStrictIdentityAndClearSource() {
        val result = TargetReliabilityCalculator.calculate(
            TargetReliabilityInput(
                targetKind = TargetKind.NODE,
                selectedNode = meaningfulNode(),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/java/HomeScreen.kt",
                        line = 42,
                        score = 0.92,
                        matchReasons = listOf("selected testTag convention composable"),
                        confidence = SelectionConfidence.HIGH,
                        scoreMargin = 0.41,
                    ),
                ),
                targetEvidence = TargetEvidence(
                    identityHint = IdentityHint(
                        composableNameHint = "HomeScreen",
                        source = IdentityHintSource.TEST_TAG_CONVENTION,
                        confidence = IdentityHintConfidence.HIGH,
                    ),
                    evidenceQuality = EvidenceQuality.STRUCTURED,
                ),
                screenFingerprintAvailable = true,
            ),
        )

        assertEquals(TargetConfidence.HIGH, result.confidence)
        assertTrue(result.reasons.contains(TargetReliabilityReason.STRICT_COMPOSABLE_IDENTITY))
        assertTrue(result.reasons.contains(TargetReliabilityReason.STRONG_SOURCE_CANDIDATE))
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun lowConfidenceForVisualAreaWithNoMeaningfulComposeCoverage() {
        val result = TargetReliabilityCalculator.calculate(
            TargetReliabilityInput(
                targetKind = TargetKind.AREA,
                selectedNode = null,
                nearbyNodes = emptyList(),
                sourceCandidates = emptyList(),
                semanticCoverage = TargetReliabilityCalculator.coverageFor(
                    roots = listOf(FixThisRect(0f, 0f, 400f, 800f)),
                    meaningfulNodes = emptyList(),
                    targetBounds = FixThisRect(32f, 120f, 260f, 220f),
                ),
                screenFingerprintAvailable = true,
            ),
        )

        assertEquals(TargetConfidence.LOW, result.confidence)
        assertTrue(result.warnings.contains(TargetReliabilityWarning.VISUAL_AREA_ONLY))
        assertTrue(result.warnings.contains(TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET))
        assertTrue(result.warnings.contains(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP))
    }

    @Test
    fun lowConfidenceWhenSourceCandidateIsStaleOrAmbiguous() {
        val result = TargetReliabilityCalculator.calculate(
            TargetReliabilityInput(
                targetKind = TargetKind.NODE,
                selectedNode = meaningfulNode(),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/java/QueueScreen.kt",
                        score = 0.51,
                        confidence = SelectionConfidence.MEDIUM,
                        scoreMargin = 0.03,
                        riskFlags = listOf(SourceCandidateRisk.AMBIGUOUS),
                        stale = true,
                        staleReason = "excerpt mismatch",
                    ),
                ),
                screenFingerprintAvailable = false,
            ),
        )

        assertEquals(TargetConfidence.LOW, result.confidence)
        assertTrue(result.warnings.contains(TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN))
        assertTrue(result.warnings.contains(TargetReliabilityWarning.SOURCE_INDEX_STALE))
        assertTrue(result.warnings.contains(TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE))
    }

    @Test
    fun sensitiveNodeAddsWarningWithoutLeakingSensitiveText() {
        val result = TargetReliabilityCalculator.calculate(
            TargetReliabilityInput(
                targetKind = TargetKind.NODE,
                selectedNode = meaningfulNode(
                    text = listOf("<redacted-password>"),
                    editableText = "<redacted-password>",
                    isSensitive = true,
                ),
                sourceCandidates = emptyList(),
            ),
        )

        assertTrue(result.warnings.contains(TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED))
        assertTrue(result.reasons.none { it.name.contains("PASSWORD", ignoreCase = true) })
    }

    private fun meaningfulNode(
        text: List<String> = listOf("Pay now"),
        editableText: String? = null,
        isSensitive: Boolean = false,
    ): FixThisNode = FixThisNode(
        uid = "compose:0:merged:10",
        composeNodeId = 10,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(10f, 20f, 140f, 80f),
        text = text,
        editableText = editableText,
        role = "Button",
        testTag = "comp:HomeScreen:primary",
        isSensitive = isSensitive,
    )
}
```

- [ ] **Step 2: Run the core calculator test to verify it fails**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*TargetReliabilityCalculatorTest" --no-daemon
```

Expected: FAIL with unresolved references for `TargetReliabilityCalculator`, `TargetReliabilityInput`, `TargetKind`, `TargetConfidence`, `TargetReliabilityReason`, and `TargetReliabilityWarning`.

- [ ] **Step 3: Add reliability models**

In `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt`, add `targetReliability` to `FixThisAnnotation` immediately after `targetEvidence`:

```kotlin
    val targetEvidence: TargetEvidence? = null,
    val targetReliability: TargetReliability? = null,
)
```

At the end of the same file, add these serializable models:

```kotlin
@Serializable
data class TargetReliability(
    val confidence: TargetConfidence = TargetConfidence.UNKNOWN,
    val reasons: List<TargetReliabilityReason> = emptyList(),
    val warnings: List<TargetReliabilityWarning> = emptyList(),
)

@Serializable
enum class TargetConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN,
}

@Serializable
enum class TargetKind {
    NODE,
    AREA,
}

@Serializable
data class SemanticCoverage(
    val rootBounds: FixThisRect? = null,
    val overlappingMeaningfulNodeCount: Int = 0,
    val nearestMeaningfulNodeDistancePx: Float? = null,
)

@Serializable
data class TargetReliabilityInput(
    val targetKind: TargetKind,
    val selectedNode: FixThisNode? = null,
    val nearbyNodes: List<FixThisNode> = emptyList(),
    val sourceCandidates: List<SourceCandidate> = emptyList(),
    val targetEvidence: TargetEvidence? = null,
    val semanticCoverage: SemanticCoverage = SemanticCoverage(),
    val screenFingerprintAvailable: Boolean = true,
    val forcedFingerprintMismatch: Boolean = false,
)

@Serializable
enum class TargetReliabilityReason {
    STRICT_COMPOSABLE_IDENTITY,
    MEANINGFUL_COMPOSE_NODE,
    STRONG_SOURCE_CANDIDATE,
    MEDIUM_SOURCE_CANDIDATE,
    VISUAL_AREA_SELECTION,
    LEGACY_OR_MISSING_EVIDENCE,
    REDACTED_TEXT_REDUCED_EVIDENCE,
}

@Serializable
enum class TargetReliabilityWarning {
    VISUAL_AREA_ONLY,
    NO_MEANINGFUL_COMPOSE_TARGET,
    POSSIBLE_VIEW_INTEROP,
    LOW_SOURCE_CANDIDATE_MARGIN,
    SOURCE_INDEX_STALE,
    SCREEN_FINGERPRINT_MISMATCH_FORCED,
    SCREEN_FINGERPRINT_UNAVAILABLE,
    SENSITIVE_TEXT_REDACTED,
}

fun TargetReliability.withWarnings(extra: Collection<TargetReliabilityWarning>): TargetReliability = copy(
    confidence = if (extra.any { it.reducesConfidence() }) TargetConfidence.LOW else confidence,
    warnings = (warnings + extra).distinct(),
)

private fun TargetReliabilityWarning.reducesConfidence(): Boolean = when (this) {
    TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE,
    TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED,
    -> false
    TargetReliabilityWarning.VISUAL_AREA_ONLY,
    TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET,
    TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP,
    TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN,
    TargetReliabilityWarning.SOURCE_INDEX_STALE,
    TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED,
    -> true
}
```

- [ ] **Step 4: Add the calculator**

Create `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculator.kt`:

```kotlin
package io.github.beyondwin.fixthis.compose.core.target

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.github.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.github.beyondwin.fixthis.compose.core.model.SemanticCoverage
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetKind
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityInput
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.compose.core.model.withWarnings
import kotlin.math.sqrt

object TargetReliabilityCalculator {
    private const val LOW_MARGIN_THRESHOLD = 0.15

    fun calculate(input: TargetReliabilityInput): TargetReliability {
        val reasons = buildReasons(input)
        val warnings = buildWarnings(input)
        val baseConfidence = confidenceFor(input, reasons, warnings)
        return TargetReliability(
            confidence = baseConfidence,
            reasons = reasons.distinct(),
            warnings = warnings.distinct(),
        )
    }

    fun addWarnings(
        reliability: TargetReliability?,
        warnings: Collection<TargetReliabilityWarning>,
    ): TargetReliability = (reliability ?: TargetReliability()).withWarnings(warnings)

    fun coverageFor(
        roots: List<FixThisRect>,
        meaningfulNodes: List<FixThisNode>,
        targetBounds: FixThisRect,
    ): SemanticCoverage {
        val rootBounds = roots.firstOrNull { root -> root.containsCenterOf(targetBounds) } ?: roots.firstOrNull()
        val overlapping = meaningfulNodes.count { node -> node.boundsInWindow.intersects(targetBounds) }
        val nearest = meaningfulNodes
            .map { node -> node.boundsInWindow.centerDistanceTo(targetBounds) }
            .minOrNull()
            ?.let { sqrt(it).toFloat() }
        return SemanticCoverage(
            rootBounds = rootBounds,
            overlappingMeaningfulNodeCount = overlapping,
            nearestMeaningfulNodeDistancePx = nearest,
        )
    }

    private fun buildReasons(input: TargetReliabilityInput): List<TargetReliabilityReason> = buildList {
        val identity = input.targetEvidence?.identityHint
        if (
            identity?.source == IdentityHintSource.TEST_TAG_CONVENTION &&
            identity.confidence == IdentityHintConfidence.HIGH
        ) {
            add(TargetReliabilityReason.STRICT_COMPOSABLE_IDENTITY)
        }
        if (input.selectedNode?.hasMeaningfulSemantic() == true) {
            add(TargetReliabilityReason.MEANINGFUL_COMPOSE_NODE)
        }
        val top = input.sourceCandidates.firstOrNull()
        if (top?.confidence == SelectionConfidence.HIGH && !top.hasLowMargin()) {
            add(TargetReliabilityReason.STRONG_SOURCE_CANDIDATE)
        } else if (top != null && top.confidence != SelectionConfidence.NONE) {
            add(TargetReliabilityReason.MEDIUM_SOURCE_CANDIDATE)
        }
        if (input.targetKind == TargetKind.AREA) {
            add(TargetReliabilityReason.VISUAL_AREA_SELECTION)
        }
        if (input.selectedNode?.isSensitive == true) {
            add(TargetReliabilityReason.REDACTED_TEXT_REDUCED_EVIDENCE)
        }
        if (isEmpty()) {
            add(TargetReliabilityReason.LEGACY_OR_MISSING_EVIDENCE)
        }
    }

    private fun buildWarnings(input: TargetReliabilityInput): List<TargetReliabilityWarning> = buildList {
        if (input.targetKind == TargetKind.AREA) {
            add(TargetReliabilityWarning.VISUAL_AREA_ONLY)
        }
        val hasMeaningfulTarget = input.selectedNode?.hasMeaningfulSemantic() == true ||
            input.nearbyNodes.any { node -> node.hasMeaningfulSemantic() } ||
            input.semanticCoverage.overlappingMeaningfulNodeCount > 0
        if (!hasMeaningfulTarget) {
            add(TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET)
        }
        if (
            input.targetKind == TargetKind.AREA &&
            input.semanticCoverage.rootBounds != null &&
            input.semanticCoverage.overlappingMeaningfulNodeCount == 0
        ) {
            add(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP)
        }
        if (input.sourceCandidates.firstOrNull()?.hasLowMargin() == true) {
            add(TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN)
        }
        if (input.sourceCandidates.any { candidate -> candidate.stale == true }) {
            add(TargetReliabilityWarning.SOURCE_INDEX_STALE)
        }
        if (!input.screenFingerprintAvailable) {
            add(TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE)
        }
        if (input.forcedFingerprintMismatch) {
            add(TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED)
        }
        if (input.selectedNode?.isSensitive == true) {
            add(TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED)
        }
    }

    private fun confidenceFor(
        input: TargetReliabilityInput,
        reasons: List<TargetReliabilityReason>,
        warnings: List<TargetReliabilityWarning>,
    ): TargetConfidence {
        if (warnings.any { warning -> warning.reducesConfidence() }) return TargetConfidence.LOW
        if (TargetReliabilityReason.STRICT_COMPOSABLE_IDENTITY in reasons &&
            TargetReliabilityReason.STRONG_SOURCE_CANDIDATE in reasons
        ) {
            return TargetConfidence.HIGH
        }
        if (TargetReliabilityReason.MEANINGFUL_COMPOSE_NODE in reasons || input.sourceCandidates.isNotEmpty()) {
            return TargetConfidence.MEDIUM
        }
        return TargetConfidence.UNKNOWN
    }

    private fun TargetReliabilityWarning.reducesConfidence(): Boolean = when (this) {
        TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE,
        TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED,
        -> false
        TargetReliabilityWarning.VISUAL_AREA_ONLY,
        TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET,
        TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP,
        TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN,
        TargetReliabilityWarning.SOURCE_INDEX_STALE,
        TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED,
        -> true
    }

    private fun SourceCandidate.hasLowMargin(): Boolean =
        scoreMargin != null && scoreMargin < LOW_MARGIN_THRESHOLD ||
            SourceCandidateRisk.AMBIGUOUS in riskFlags

    private fun FixThisRect.containsCenterOf(other: FixThisRect): Boolean {
        val x = (other.left + other.right) / 2f
        val y = (other.top + other.bottom) / 2f
        return contains(x, y)
    }

    private fun FixThisRect.intersects(other: FixThisRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    private fun FixThisRect.centerDistanceTo(other: FixThisRect): Double {
        val dx = ((left + right) / 2.0) - ((other.left + other.right) / 2.0)
        val dy = ((top + bottom) / 2.0) - ((other.top + other.bottom) / 2.0)
        return dx * dx + dy * dy
    }
}
```

- [ ] **Step 5: Run the core calculator test to verify it passes**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*TargetReliabilityCalculatorTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt \
  fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculator.kt \
  fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculatorTest.kt
git commit -m "feat(core): add target reliability calculator"
```

## Task 2: Core Annotation And Markdown Formatting

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/annotation/Annotation.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt`
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatterTest.kt`
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/TargetEvidenceModelTest.kt`

- [ ] **Step 1: Write serialization and formatter tests**

Append these tests to `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/TargetEvidenceModelTest.kt`, before `baseAnnotation()`:

```kotlin
    @Test
    fun decodesLegacyAnnotationWithoutTargetReliability() {
        val annotation = json.decodeFromString<FixThisAnnotation>(
            """
            {
              "schemaVersion": "1.0",
              "id": "annotation-1",
              "createdAtEpochMillis": 100,
              "platform": "android-compose",
              "app": {"packageName": "pkg", "debuggable": true},
              "activity": {"className": "MainActivity"},
              "tap": {"xInWindow": 10.0, "yInWindow": 11.0},
              "selection": {"kind": "VISUAL_AREA", "confidence": "LOW", "source": "AREA_SELECT"},
              "userComment": "Make this clearer"
            }
            """.trimIndent(),
        )

        assertNull(annotation.targetReliability)
    }

    @Test
    fun roundTripsTargetReliability() {
        val annotation = baseAnnotation().copy(
            targetReliability = TargetReliability(
                confidence = TargetConfidence.LOW,
                reasons = listOf(TargetReliabilityReason.VISUAL_AREA_SELECTION),
                warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
            ),
        )

        val encoded = json.encodeToString(annotation)
        val decoded = json.decodeFromString<FixThisAnnotation>(encoded)

        assertEquals(annotation.targetReliability, decoded.targetReliability)
        assertTrue(encoded.contains("\"targetReliability\""))
        assertTrue(encoded.contains("POSSIBLE_VIEW_INTEROP"))
    }
```

Append this test to `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatterTest.kt`:

```kotlin
    @Test
    fun compactModeIncludesTargetReliabilityWarning() {
        val markdown = FixThisMarkdownFormatter.format(
            annotation().copy(
                targetReliability = TargetReliability(
                    confidence = TargetConfidence.LOW,
                    reasons = listOf(TargetReliabilityReason.VISUAL_AREA_SELECTION),
                    warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
                ),
            ),
            DetailMode.COMPACT,
        )

        assertTrue(markdown.contains("Target confidence: low"))
        assertTrue(markdown.contains("possible AndroidView/WebView area"))
    }
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*TargetReliabilitySerializationTest" --tests "*FixThisMarkdownFormatterTest.compactModeIncludesTargetReliabilityWarning" --no-daemon
```

Expected: serialization test compiles after Task 1, formatter test FAILS because reliability is not rendered yet.

- [ ] **Step 3: Add reliability to the domain annotation**

In `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/annotation/Annotation.kt`, add the import:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
```

Add the field immediately after `targetEvidence`:

```kotlin
    val targetEvidence: TargetEvidence? = null,
    val targetReliability: TargetReliability? = null,
)
```

- [ ] **Step 4: Render reliability in core Markdown**

In `FixThisMarkdownFormatter.kt`, add imports:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
```

In `formatCompact`, call reliability rendering after `appendCompactTarget(annotation)`:

```kotlin
        appendCompactTarget(annotation)
        appendTargetReliability(annotation.targetReliability, compact = true)
```

In `formatPrecise`, call reliability rendering after `appendTargetEvidence(annotation, includeEmpty = true)`:

```kotlin
        appendTargetEvidence(annotation, includeEmpty = true)
        appendTargetReliability(annotation.targetReliability, compact = false)
```

In `formatFull`, call reliability rendering after `appendTargetEvidence(annotation, includeEmpty = false)`:

```kotlin
            appendTargetEvidence(annotation, includeEmpty = false)
            appendTargetReliability(annotation.targetReliability, compact = false)
```

Add these private helpers near `appendTargetEvidence`:

```kotlin
    private fun StringBuilder.appendTargetReliability(reliability: TargetReliability?, compact: Boolean) {
        if (reliability == null) return
        if (reliability.confidence == TargetConfidence.UNKNOWN && reliability.warnings.isEmpty()) return
        appendLine("- Target confidence: ${reliability.confidence.name.lowercase()}${reliability.reasonSummary()}")
        reliability.warnings.forEach { warning ->
            appendLine("- Warning: ${warning.message().markdownInline()}")
        }
        if (!compact && reliability.reasons.isNotEmpty()) {
            appendLine("- Reliability reasons: ${reliability.reasons.map { it.name.lowercase().replace('_', '-') }.markdownListValue()}")
        }
    }

    private fun TargetReliability.reasonSummary(): String {
        val labels = reasons.take(2).map { reason -> reason.name.lowercase().replace('_', '-') }
        return if (labels.isEmpty()) "" else " - ${labels.joinToString(" + ")}"
    }

    private fun TargetReliabilityWarning.message(): String = when (this) {
        TargetReliabilityWarning.VISUAL_AREA_ONLY -> "visual area only; verify screenshot and bounds"
        TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET -> "no meaningful Compose semantics node covered this target"
        TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP -> "possible AndroidView/WebView area; source candidates may not explain rendered pixels"
        TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN -> "source candidates are close; verify before editing"
        TargetReliabilityWarning.SOURCE_INDEX_STALE -> "source index may be stale"
        TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED -> "screen changed after capture; user force-saved this item"
        TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE -> "screen fingerprint unavailable; mismatch check was skipped"
        TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED -> "sensitive text was redacted from target evidence"
    }
```

- [ ] **Step 5: Run the core formatter and serialization tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*TargetReliabilitySerializationTest" --tests "*FixThisMarkdownFormatterTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/annotation/Annotation.kt \
  fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt \
  fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatterTest.kt \
  fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/model/TargetReliabilitySerializationTest.kt
git commit -m "feat(core): render target reliability in markdown"
```

## Task 3: MCP DTO, Mapping, And Legacy Session Compatibility

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDomainMappers.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetReliabilityDtoTest.kt`

- [ ] **Step 1: Write failing MCP DTO tests**

Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetReliabilityDtoTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetReliabilityDtoTest {
    @Test
    fun decodesLegacyAnnotationWithoutReliability() {
        val legacy = """
            {
              "itemId": "item-1",
              "screenId": "screen-1",
              "createdAtEpochMillis": 1,
              "updatedAtEpochMillis": 1,
              "target": {
                "type": "visual_area",
                "boundsInWindow": {"left": 1.0, "top": 2.0, "right": 3.0, "bottom": 4.0}
              },
              "comment": "Fix the chart"
            }
        """.trimIndent()

        val item = fixThisJson.decodeFromString(AnnotationDto.serializer(), legacy)

        assertEquals(null, item.targetReliability)
    }

    @Test
    fun roundTripsReliabilityAsSiblingOfTargetEvidence() {
        val item = AnnotationDto(
            itemId = "item-1",
            screenId = "screen-1",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
            comment = "Fix the chart",
            targetReliability = TargetReliability(
                confidence = TargetConfidence.LOW,
                reasons = listOf(TargetReliabilityReason.VISUAL_AREA_SELECTION),
                warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
            ),
        )

        val encoded = fixThisJson.encodeToString(AnnotationDto.serializer(), item)
        val decoded = fixThisJson.decodeFromString(AnnotationDto.serializer(), encoded)

        assertEquals(item.targetReliability, decoded.targetReliability)
        assertTrue(encoded.contains("\"targetReliability\""))
        assertTrue(encoded.contains("\"targetEvidence\"").not())
    }

    @Test
    fun domainMapperPreservesReliability() {
        val item = AnnotationDto(
            itemId = "item-1",
            screenId = "screen-1",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
            target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
            comment = "Fix the chart",
            targetReliability = TargetReliability(
                confidence = TargetConfidence.LOW,
                warnings = listOf(TargetReliabilityWarning.VISUAL_AREA_ONLY),
            ),
        )

        val roundTrip = item.toDomainAnnotation("session-1").toAnnotationDto()

        assertEquals(item.targetReliability, roundTrip.targetReliability)
    }
}
```

- [ ] **Step 2: Run the DTO tests to verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*TargetReliabilityDtoTest" --no-daemon
```

Expected: FAIL with unresolved `targetReliability` properties and mapper mismatch.

- [ ] **Step 3: Add the DTO field**

In `SessionDtoModels.kt`, add the import:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
```

Add this field to `AnnotationDto` immediately after `targetEvidence`:

```kotlin
    val targetEvidence: TargetEvidence? = null,
    val targetReliability: TargetReliability? = null,
)
```

- [ ] **Step 4: Preserve reliability in domain mappers**

In `SessionDomainMappers.kt`, add `targetReliability` to `AnnotationDto.toDomainAnnotation`:

```kotlin
    targetEvidence = targetEvidence,
    targetReliability = targetReliability,
)
```

Add it to `Annotation.toAnnotationDto`:

```kotlin
    targetEvidence = targetEvidence,
    targetReliability = targetReliability,
)
```

- [ ] **Step 5: Run the DTO tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*TargetReliabilityDtoTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit Task 3**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDomainMappers.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetReliabilityDtoTest.kt
git commit -m "feat(mcp): persist target reliability metadata"
```

## Task 4: Derive Reliability During Item Creation

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftServiceMismatchTest.kt`

- [ ] **Step 1: Add failing service tests**

Append these tests to `TargetEvidenceServiceTest.kt`. They use the existing private helpers `targetEvidenceService()`, `screenWith(...)`, and `node(...)` already defined in that test class:

```kotlin
    @Test
    fun buildFeedbackItemAddsLowReliabilityForVisualAreaWithoutMeaningfulCoverage() {
        val service = targetEvidenceService()
        val screen = screenWith()

        val item = service.buildFeedbackItem(
            screen = screen,
            sourceIndex = null,
            targetType = FeedbackTargetType.AREA,
            bounds = FixThisRect(20f, 120f, 260f, 220f),
            nodeUid = null,
            comment = "Fix native chart spacing",
            allowBlankComment = false,
            writtenStatus = AnnotationStatusDto.OPEN,
        )

        assertEquals(TargetConfidence.LOW, item.targetReliability?.confidence)
        assertTrue(item.targetReliability?.warnings.orEmpty().contains(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP))
    }

    @Test
    fun buildFeedbackItemAddsMediumReliabilityForMeaningfulNodeWithoutSourceIndex() {
        val service = targetEvidenceService()
        val selected = node(
            uid = "pay-button",
            text = listOf("Pay now"),
            role = "Button",
            testTag = "comp:CheckoutButton:primary",
        )
        val screen = screenWith(selected)

        val item = service.buildFeedbackItem(
            screen = screen,
            sourceIndex = null,
            targetType = FeedbackTargetType.NODE,
            bounds = selected.boundsInWindow,
            nodeUid = selected.uid,
            comment = "Round this button",
            allowBlankComment = false,
            writtenStatus = AnnotationStatusDto.OPEN,
        )

        assertEquals(TargetConfidence.MEDIUM, item.targetReliability?.confidence)
        assertTrue(
            item.targetReliability?.warnings.orEmpty()
                .contains(TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET)
                .not(),
        )
    }
```

Add required imports to the test file:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
```

Append this test to `FeedbackDraftServiceMismatchTest.kt`:

```kotlin
    @Test
    fun forceSavedMismatchAddsReliabilityWarningToSavedItems() = runBlocking {
        val fixture = draftFixture(
            ids = arrayOf("session-1", "preview-1", "screen-1", "item-1"),
            prefix = "fixthis-draft-mismatch-reliability-",
        )
        val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview = fixture.previewCaptureService.capturePreview(session)
        val reservation = fixture.draftService.preparePreviewFeedbackSave(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(validItem()),
            allowBlankComments = true,
        )

        val result = fixture.draftService.commitPreviewFeedbackSaveWithMetadata(
            reservation,
            PreviewSaveFingerprintCheck(
                frozenFingerprint = "frozen",
                currentFingerprint = "current",
                forceMismatchOverride = true,
                frozenFingerprintSource = "previewCache",
            ),
        )

        val saved = result.session.items.single()
        assertTrue(
            saved.targetReliability?.warnings.orEmpty()
                .contains(TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED),
        )
    }
```

Add required imports to `FeedbackDraftServiceMismatchTest.kt`:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import kotlin.test.assertTrue
```

- [ ] **Step 2: Run the focused tests to verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*TargetEvidenceServiceTest" --tests "*FeedbackDraftServiceMismatchTest.forceSavedMismatchAddsReliabilityWarningToSavedItems" --no-daemon
```

Expected: FAIL because item creation does not set `targetReliability`.

- [ ] **Step 3: Compute reliability in `TargetEvidenceService`**

In `TargetEvidenceService.kt`, add imports:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.TargetKind
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityInput
import io.github.beyondwin.fixthis.compose.core.target.TargetReliabilityCalculator
```

Add a constructor parameter:

```kotlin
    private val reliabilityCalculator: TargetReliabilityCalculator = TargetReliabilityCalculator,
```

In `buildFeedbackItem(...)`, assign `targetEvidence` and `targetReliability` before constructing the `AnnotationDto`:

```kotlin
        val targetEvidence = targetEvidenceFor(
            targetType = validatedTarget.targetType,
            selectedNode = validatedTarget.selectedNode,
            screen = screen,
            sourceCandidates = sourceCandidates,
        )
        val targetReliability = targetReliabilityFor(
            targetType = validatedTarget.targetType,
            selectedNode = validatedTarget.selectedNode,
            evidenceNodes = validatedTarget.evidenceNodes,
            storedBounds = validatedTarget.storedBounds,
            screen = screen,
            sourceCandidates = sourceCandidates,
            targetEvidence = targetEvidence,
        )
```

Use those variables in `AnnotationDto`:

```kotlin
            targetEvidence = targetEvidence,
            targetReliability = targetReliability,
```

Add this method near `targetEvidenceFor`:

```kotlin
    fun targetReliabilityFor(
        targetType: FeedbackTargetType,
        selectedNode: FixThisNode?,
        evidenceNodes: List<FixThisNode>,
        storedBounds: FixThisRect,
        screen: SnapshotDto,
        sourceCandidates: List<SourceCandidate>,
        targetEvidence: TargetEvidence?,
    ): TargetReliability {
        val roots = screen.roots.map { root -> root.boundsInWindow }
        val meaningfulNodes = screen.allNodes().filter { node -> node.hasMeaningfulSemantic() }
        return reliabilityCalculator.calculate(
            TargetReliabilityInput(
                targetKind = when (targetType) {
                    FeedbackTargetType.AREA -> TargetKind.AREA
                    FeedbackTargetType.NODE -> TargetKind.NODE
                },
                selectedNode = selectedNode,
                nearbyNodes = evidenceNodes,
                sourceCandidates = sourceCandidates,
                targetEvidence = targetEvidence,
                semanticCoverage = TargetReliabilityCalculator.coverageFor(
                    roots = roots,
                    meaningfulNodes = meaningfulNodes,
                    targetBounds = storedBounds,
                ),
                screenFingerprintAvailable = screen.fingerprint != null,
            ),
        )
    }
```

- [ ] **Step 4: Add force-save and fingerprint warnings in `FeedbackDraftService`**

In `FeedbackDraftService.kt`, add imports:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.compose.core.target.TargetReliabilityCalculator
```

Inside `commitPreviewFeedbackSaveWithMetadata`, after `feedbackItems` is built, replace the `val feedbackItems = ...` block with:

```kotlin
        val baseFeedbackItems = reservation.items.map { pending ->
            targetEvidenceService.buildFeedbackItem(
                screen = preview.snapshot.screen,
                sourceIndex = preview.sourceIndex,
                targetType = pending.targetType,
                bounds = pending.bounds,
                nodeUid = pending.nodeUid,
                comment = pending.comment,
                allowBlankComment = reservation.allowBlankComments,
                writtenStatus = pending.status,
                missingNodeContext = "preview",
            ).copy(
                label = pending.label?.takeIf { it.isNotBlank() },
                severity = pending.severity,
            )
        }
        val reliabilityWarnings = buildList {
            if (fingerprintCheck.forceMismatchOverride) {
                add(TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED)
            }
            if (fingerprintUnavailableReason != null) {
                add(TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE)
            }
        }
        val feedbackItems = baseFeedbackItems.map { item ->
            if (reliabilityWarnings.isEmpty()) {
                item
            } else {
                item.copy(
                    targetReliability = TargetReliabilityCalculator.addWarnings(
                        item.targetReliability,
                        reliabilityWarnings,
                    ),
                )
            }
        }
```

- [ ] **Step 5: Run focused MCP tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*TargetEvidenceServiceTest" --tests "*FeedbackDraftServiceMismatchTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit Task 4**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackDraftServiceMismatchTest.kt
git commit -m "feat(mcp): derive target reliability for feedback items"
```

## Task 5: Handoff Markdown And JSON Output

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt`

- [ ] **Step 1: Write failing handoff renderer tests**

Append this test to `CompactHandoffRendererTest.kt`:

```kotlin
    @Test
    fun compactHandoffRendersTargetReliabilityWarnings() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Diagnostics",
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(10f, 20f, 200f, 120f)),
                    comment = "Fix the native chart spacing",
                    sequenceNumber = 1,
                    targetReliability = TargetReliability(
                        confidence = TargetConfidence.LOW,
                        reasons = listOf(TargetReliabilityReason.VISUAL_AREA_SELECTION),
                        warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
                    ),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)

        assertTrue(markdown.contains("targetConfidence=low"))
        assertTrue(markdown.contains("possible AndroidView/WebView area"))
    }
```

Append this test to `FeedbackQueueFormatterTest.kt`:

```kotlin
    @Test
    fun preciseMarkdownRendersTargetReliabilityWarnings() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Diagnostics",
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(10f, 20f, 200f, 120f)),
                    comment = "Fix the native chart spacing",
                    targetReliability = TargetReliability(
                        confidence = TargetConfidence.LOW,
                        warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
                    ),
                ),
            ),
        )

        val markdown = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)

        assertTrue(markdown.contains("Target confidence: low"))
        assertTrue(markdown.contains("possible AndroidView/WebView area"))
    }
```

Add imports to `CompactHandoffRendererTest.kt`:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
```

Add imports to `FeedbackQueueFormatterTest.kt`:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
```

- [ ] **Step 2: Run handoff tests to verify they fail**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest.compactHandoffRendersTargetReliabilityWarnings" --tests "*FeedbackQueueFormatterTest.preciseMarkdownRendersTargetReliabilityWarnings" --no-daemon
```

Expected: FAIL because no reliability lines are rendered.

- [ ] **Step 3: Render reliability in compact handoff**

In `CompactHandoffRenderer.kt`, add imports:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
```

Inside `appendCompactItem`, call reliability rendering after `appendCandidatesBlock(item, sourceRoot)`:

```kotlin
        appendCandidatesBlock(item, sourceRoot)
        appendReliabilityBlock(item.targetReliability)
```

Add these helpers:

```kotlin
    private fun StringBuilder.appendReliabilityBlock(reliability: TargetReliability?) {
        if (reliability == null) return
        val confidence = reliability.confidence.name.lowercase()
        if (reliability.warnings.isEmpty()) {
            appendLine("  targetConfidence=$confidence")
            return
        }
        appendLine("  targetConfidence=$confidence")
        reliability.warnings.forEach { warning ->
            appendLine("  warning: ${warning.message()}")
        }
    }

    private fun TargetReliabilityWarning.message(): String = when (this) {
        TargetReliabilityWarning.VISUAL_AREA_ONLY -> "visual area only; verify screenshot and bounds"
        TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET -> "no meaningful Compose semantics node covered this target"
        TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP -> "possible AndroidView/WebView area; source candidates may not explain rendered pixels"
        TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN -> "source candidates are close; verify before editing"
        TargetReliabilityWarning.SOURCE_INDEX_STALE -> "source index may be stale"
        TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED -> "screen changed after capture; user force-saved this item"
        TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE -> "screen fingerprint unavailable; mismatch check was skipped"
        TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED -> "sensitive text was redacted from target evidence"
    }
```

- [ ] **Step 4: Render reliability in precise/full queue Markdown**

In `FeedbackQueueFormatter.kt`, add imports:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
```

Inside `appendTarget`, after `appendTargetEvidence(item)` for both target branches, call:

```kotlin
                appendTargetReliability(item.targetReliability)
```

For the visual area branch, call the same helper before the area note:

```kotlin
                appendTargetReliability(item.targetReliability)
                appendLine("- Note: area selection only; verify screenshot and source candidates.")
```

Add helpers near `appendTargetEvidence`:

```kotlin
    private fun StringBuilder.appendTargetReliability(reliability: TargetReliability?) {
        if (reliability == null) return
        if (reliability.confidence == TargetConfidence.UNKNOWN && reliability.warnings.isEmpty()) return
        appendLine("- Target confidence: ${reliability.confidence.name.lowercase()}")
        reliability.warnings.forEach { warning ->
            appendLine("- Warning: ${warning.message()}")
        }
    }

    private fun TargetReliabilityWarning.message(): String = when (this) {
        TargetReliabilityWarning.VISUAL_AREA_ONLY -> "visual area only; verify screenshot and bounds"
        TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET -> "no meaningful Compose semantics node covered this target"
        TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP -> "possible AndroidView/WebView area; source candidates may not explain rendered pixels"
        TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN -> "source candidates are close; verify before editing"
        TargetReliabilityWarning.SOURCE_INDEX_STALE -> "source index may be stale"
        TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED -> "screen changed after capture; user force-saved this item"
        TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE -> "screen fingerprint unavailable; mismatch check was skipped"
        TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED -> "sensitive text was redacted from target evidence"
    }
```

- [ ] **Step 5: Run handoff tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*CompactHandoffRendererTest" --tests "*FeedbackQueueFormatterTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit Task 5**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt
git commit -m "feat(mcp): render target reliability in handoffs"
```

## Task 6: Console Warning Presentation

**Files:**
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Modify: `fixthis-mcp/src/main/console/rendering.js`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Create: `scripts/targetReliabilityPresentation-test.mjs`
- Modify: `scripts/console-tests.json`

- [ ] **Step 1: Write the console presentation test**

Create `scripts/targetReliabilityPresentation-test.mjs`:

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const annotationsSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/annotations.js'), 'utf8');
const renderingSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/rendering.js'), 'utf8');
const stylesSource = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/styles.css'), 'utf8');

test('prompt readiness counts reliability warnings without blocking save', () => {
  assert.match(annotationsSource, /function reliabilityWarnings\(item\)/);
  assert.match(annotationsSource, /const warningCount = annotations\.reduce/);
  assert.match(annotationsSource, /state: missing > 0 \? 'blocked' : \(warningCount > 0 \? 'warn' : 'ready'\)/);
});

test('annotation rows render target reliability badges', () => {
  assert.match(renderingSource, /function reliabilityBadgeHtml\(item\)/);
  assert.match(renderingSource, /ann-row-reliability/);
  assert.match(renderingSource, /targetReliability/);
});

test('target reliability CSS is present', () => {
  assert.match(stylesSource, /\.ann-row-reliability/);
  assert.match(stylesSource, /\.ann-row-reliability\[data-confidence="low"\]/);
});
```

Add the test to `scripts/console-tests.json` under the `"draft"` group:

```json
"scripts/targetReliabilityPresentation-test.mjs"
```

- [ ] **Step 2: Run the console test to verify it fails**

Run:

```bash
node --test scripts/targetReliabilityPresentation-test.mjs
```

Expected: FAIL because the helper functions and CSS classes are absent.

- [ ] **Step 3: Add annotation-side warning helpers**

In `fixthis-mcp/src/main/console/annotations.js`, add these helpers after `annotationStatus(item)`:

```js
            function reliabilityWarnings(item) {
              return item?.targetReliability?.warnings || [];
            }

            function reliabilityConfidence(item) {
              return String(item?.targetReliability?.confidence || 'unknown').toLowerCase();
            }

            function reliabilityLabel(item) {
              const confidence = reliabilityConfidence(item);
              if (confidence === 'unknown') return '';
              const warnings = reliabilityWarnings(item);
              return warnings.length ? confidence + ' · ' + countLabel(warnings.length, 'warning', 'warnings') : confidence;
            }
```

In `promptReadinessState()`, after `const ready = ...`, add:

```js
              const warningCount = annotations.reduce((total, item) => total + reliabilityWarnings(item).length, 0);
```

Replace the ready return block with:

```js
                return {
                  state: missing > 0 ? 'blocked' : (warningCount > 0 ? 'warn' : 'ready'),
                  label: countLabel(ready.length, 'ready', 'ready') +
                    (missing > 0 ? ' · ' + countLabel(missing, 'missing comment', 'missing comments') : '') +
                    (missing === 0 && warningCount > 0 ? ' · ' + countLabel(warningCount, 'target warning', 'target warnings') : ''),
                  title: 'Ready to hand off ' + countLabel(ready.length, itemKind + ' annotation', itemKind + ' annotations') +
                    (missing > 0 ? '. ' + countLabel(missing, 'annotation needs', 'annotations need') + ' a comment.' : '') +
                    (missing === 0 && warningCount > 0 ? ' Reliability warnings will be included in the handoff.' : '.'),
                };
```

- [ ] **Step 4: Add rendering-side badges**

In `fixthis-mcp/src/main/console/rendering.js`, add these helpers near `sourceCandidateLine(candidate)`:

```js
            function reliabilityWarningLabel(warning) {
              const value = String(warning || '').toLowerCase();
              if (value === 'possible_view_interop') return 'Possible AndroidView/WebView';
              if (value === 'no_meaningful_compose_target') return 'No Compose target';
              if (value === 'visual_area_only') return 'Visual only';
              if (value === 'low_source_candidate_margin') return 'Low source margin';
              if (value === 'source_index_stale') return 'Stale source';
              if (value === 'screen_fingerprint_mismatch_forced') return 'Forced screen mismatch';
              if (value === 'screen_fingerprint_unavailable') return 'No fingerprint';
              if (value === 'sensitive_text_redacted') return 'Redacted';
              return value.replaceAll('_', ' ');
            }

            function reliabilityBadgeHtml(item) {
              const reliability = item?.targetReliability;
              if (!reliability || !reliability.confidence || reliability.confidence === 'UNKNOWN') return '';
              const confidence = String(reliability.confidence).toLowerCase();
              const warnings = reliability.warnings || [];
              const label = warnings.length ? reliabilityWarningLabel(warnings[0]) : confidence;
              return '<span class="ann-row-reliability" data-confidence="' + escapeHtml(confidence) + '">' +
                escapeHtml(label) +
              '</span>';
            }
```

In both pending and saved row body templates, insert `reliabilityBadgeHtml(item)` after the comment span:

```js
                      '<span class="ann-row-comment ' + (hasComment ? '' : 'empty-comment') + '">' + escapeHtml(commentText) + '</span>' +
                      reliabilityBadgeHtml(item) +
```

In `evidenceDetailsHtml(item)`, add reliability rows before `warnings`:

```js
              const reliability = item?.targetReliability || {};
              const reliabilityRows = reliability.confidence
                ? [['Target confidence', String(reliability.confidence).toLowerCase()]]
                    .concat((reliability.warnings || []).map((warning, index) => ['Target warning ' + (index + 1), reliabilityWarningLabel(warning)]))
                : [];
```

Change `bodyRows` to include the new rows:

```js
              const bodyRows = evidenceRows.concat(reliabilityRows, candidates, warnings);
```

- [ ] **Step 5: Add CSS**

Append this CSS to `fixthis-mcp/src/main/resources/console/styles.css`:

```css
.ann-row-reliability {
  align-self: flex-start;
  border: 1px solid var(--border-subtle);
  border-radius: 999px;
  color: var(--text-muted);
  display: inline-flex;
  font-size: 11px;
  font-weight: 650;
  line-height: 1;
  margin-top: 4px;
  max-width: 100%;
  padding: 4px 7px;
  white-space: normal;
}

.ann-row-reliability[data-confidence="low"] {
  border-color: rgba(242, 109, 109, .42);
  color: #f26d6d;
}

.ann-row-reliability[data-confidence="medium"] {
  border-color: rgba(230, 180, 90, .38);
  color: #d8a84f;
}

.ann-row-reliability[data-confidence="high"] {
  border-color: rgba(91, 180, 142, .38);
  color: #5bb48e;
}

.prompt-readiness[data-state="warn"] {
  border-color: rgba(230, 180, 90, .38);
  color: #d8a84f;
}
```

- [ ] **Step 6: Run console presentation tests**

Run:

```bash
node --test scripts/targetReliabilityPresentation-test.mjs
npm run console:test:all
node scripts/build-console-assets.mjs --check
```

Expected: all PASS.

- [ ] **Step 7: Commit Task 6**

```bash
git add fixthis-mcp/src/main/console/annotations.js \
  fixthis-mcp/src/main/console/rendering.js \
  fixthis-mcp/src/main/resources/console/styles.css \
  scripts/targetReliabilityPresentation-test.mjs \
  scripts/console-tests.json
git commit -m "feat(console): show target reliability warnings"
```

## Task 7: AndroidView-Like Sample Fixture

**Files:**
- Modify: `sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt`
- Modify: `docs/getting-started/try-the-sample.md`

- [ ] **Step 1: Add a failing sample-source assertion**

Create a temporary focused check by running:

```bash
rg -n "Native AndroidView target|AndroidView interop preview" sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt
```

Expected: exit 1 with no matches.

- [ ] **Step 2: Add the AndroidView fixture**

In `DiagnosticsScreen.kt`, add imports:

```kotlin
import android.graphics.Color
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.viewinterop.AndroidView
```

After the semantic signal timeline block, add:

```kotlin
        Text("AndroidView interop preview", style = MaterialTheme.typography.titleSmall)
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            factory = { context ->
                TextView(context).apply {
                    text = "Native AndroidView target"
                    contentDescription = "Native AndroidView target"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.rgb(49, 79, 124))
                    setPadding(24, 18, 24, 18)
                }
            },
        )
```

Add this note to `docs/getting-started/try-the-sample.md` in the sample coverage section:

```markdown
- The Diagnostics tab includes a native AndroidView fixture. Use it to verify
  that visual-area annotations over non-Compose pixels carry low-confidence or
  possible-interop handoff warnings instead of overclaiming Compose source
  precision.
```

- [ ] **Step 3: Run the sample assertion and build**

Run:

```bash
rg -n "Native AndroidView target|AndroidView interop preview" sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt
./gradlew :app:assembleDebug --no-daemon
```

Expected: `rg` prints two matches and Gradle PASS.

- [ ] **Step 4: Commit Task 7**

```bash
git add sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt \
  docs/getting-started/try-the-sample.md
git commit -m "test(sample): add AndroidView interop fixture"
```

## Task 8: Claude/Codex Docs And Schema Notes

**Files:**
- Modify: `docs/guides/agents.md`
- Modify: `docs/reference/output-schema.md`
- Modify: `docs/reference/mcp-tools.md`
- Modify: `docs/reference/privacy.md`
- Modify: `README.md`

- [ ] **Step 1: Add failing doc consistency assertions**

Run:

```bash
rg -n "targetReliability|Target confidence|possible AndroidView/WebView" docs README.md
```

Expected before edits: matches are limited to existing superpowers spec/plan files.

- [ ] **Step 2: Document agent behavior**

In `docs/guides/agents.md`, add this subsection near the feedback-reading workflow:

```markdown
### Target reliability warnings

Saved feedback items may include `targetReliability`. Treat it as the
confidence level for the UI target, not as a task priority.

- `HIGH`: source candidates are strong starting points, but still verify the
  screenshot and surrounding code before editing.
- `MEDIUM`: inspect the listed candidates before editing; the right call site
  may be nearby rather than the first candidate.
- `LOW`: use the screenshot, bounds, comment, and nearby UI labels first. Treat
  source candidates as hints.
- `POSSIBLE_VIEW_INTEROP`: the selected pixels may come from AndroidView,
  WebView, or another non-Compose boundary. Do not assume a Compose candidate
  rendered those pixels.
- `SCREEN_FINGERPRINT_MISMATCH_FORCED`: the user force-saved after the screen
  changed. Confirm the current UI before applying edits.
```

- [ ] **Step 3: Document schema**

In `docs/reference/output-schema.md`, add this optional field to the saved item schema section:

````markdown
#### `targetReliability` optional object

`targetReliability` is additive and optional. Older sessions omit it.

```json
{
  "confidence": "HIGH | MEDIUM | LOW | UNKNOWN",
  "reasons": ["STRICT_COMPOSABLE_IDENTITY"],
  "warnings": ["POSSIBLE_VIEW_INTEROP"]
}
```

It is a sibling of `targetEvidence`. `targetEvidence` stores captured target
facts; `targetReliability` stores FixThis's derived judgment over those facts.
````

- [ ] **Step 4: Document MCP output**

In `docs/reference/mcp-tools.md`, update the `fixthis_read_feedback` output notes with:

```markdown
`compactMarkdown` includes target confidence lines when reliability metadata is
present. Low-confidence or warning items remain actionable, but agents should
verify them before editing. JSON output includes the complete optional
`targetReliability` object on each item.
```

- [ ] **Step 5: Document privacy and README positioning**

In `docs/reference/privacy.md`, add:

```markdown
Target reliability warnings do not reveal redacted text. When sensitive text
reduces available evidence, FixThis reports `SENSITIVE_TEXT_REDACTED` rather
than the original value.
```

In `README.md`, add one sentence under "Why FixThis vs. just sending a screenshot?":

```markdown
- **Honest target confidence.** Handoffs can mark visual-only, stale, or
  possible AndroidView/WebView targets so agents know when to verify rather
  than trust source hints directly.
```

- [ ] **Step 6: Run doc checks**

Run:

```bash
rg -n "targetReliability|Target confidence|possible AndroidView/WebView" docs README.md
node scripts/check-doc-consistency.mjs
```

Expected: `rg` shows hits in docs and README outside only the superpowers spec; doc consistency PASS.

- [ ] **Step 7: Commit Task 8**

```bash
git add docs/guides/agents.md \
  docs/reference/output-schema.md \
  docs/reference/mcp-tools.md \
  docs/reference/privacy.md \
  README.md
git commit -m "docs: explain target reliability handoffs"
```

## Task 9: Final Verification

**Files:**
- Verify all changed files from Tasks 1-8

- [ ] **Step 1: Run full console checks**

Run:

```bash
npm run console:test:all
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
```

Expected: PASS.

- [ ] **Step 2: Run focused Gradle checks**

Run:

```bash
./gradlew \
  :fixthis-compose-core:test \
  :fixthis-mcp:test \
  :fixthis-compose-sidekick:testDebugUnitTest \
  :app:assembleDebug \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 3: Run formatting/static checks**

Run:

```bash
./gradlew spotlessCheck detekt --no-daemon
git diff --check
```

Expected: PASS.

- [ ] **Step 4: Inspect generated console bundle freshness**

Run:

```bash
git status --short
```

Expected: no unexpected generated bundle drift. If `fixthis-mcp/src/main/resources/console/app.js` changed because `node scripts/build-console-assets.mjs --check` reported stale assets, run:

```bash
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
git add fixthis-mcp/src/main/resources/console/app.js
git commit -m "chore(console): refresh bundled assets"
```

- [ ] **Step 5: Commit verification notes when no bundle commit was needed**

If Step 4 had no bundle drift, create the final verification commit only when there are tracked doc or test adjustments left:

```bash
git status --short
git add -u
git commit -m "chore: verify target reliability handoff"
```

Expected: commit succeeds only when `git status --short` listed tracked changes. When the worktree is clean, skip this commit command.
