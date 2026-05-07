# Stable Target Evidence V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add stable, Compose-version-tolerant target evidence and Markdown detail modes so FixThis agent handoff can identify repeated UI targets and source-candidate confidence without depending on Compose tooling internals.

**Architecture:** Keep the stable path based on existing semantics nodes, strict test-tag conventions, occurrence counting over captured merged semantics nodes, and existing source candidates. Add `targetEvidence` as nullable additive data on annotation models, compute it during capture, and expose it through existing JSON while making `detailMode` affect only Markdown. Keep Compose tooling based composable identity out of the default implementation and advertise capabilities instead of gating by exact Compose versions.

**Tech Stack:** Kotlin, kotlinx.serialization, JUnit4, Android Gradle Plugin, Jetpack Compose semantics, existing FixThis CLI/MCP/sidekick modules.

---

## Source Decision

Implement the architecture decision recorded in:

```text
docs/adr/0006-stable-target-evidence-open-source-compatibility.md
```

This plan deliberately does not implement `LocalInspectionTables`, `CompositionData.mapTree`, `parseSourceInformation`, call-site inference, definition inference, or wrapper inference.

## Compatibility Rules

- Do not remove or rename existing serialized fields.
- Make all new serialized fields nullable or give them defaults.
- Do not bump `BridgeProtocol.VERSION` for this work.
- Keep existing public formatter functions and add overloads instead of replacing signatures.
- `detailMode` changes only Markdown. JSON stays complete.
- If evidence cannot be computed, return `targetEvidence = null` or omit only the unavailable nested field.
- Occurrence basis is `captured_merged_semantics_nodes`; do not claim whole-device or all-window coverage.
- Sensitive/password nodes must not use redacted text as an occurrence signature.

## File Structure

Create:

```text
fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/TargetEvidenceModels.kt
fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/identity/TestTagConvention.kt
fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/identity/IdentityHintFactory.kt
fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/identity/OccurrenceCalculator.kt
fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactory.kt
fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/DetailMode.kt
fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/model/TargetEvidenceModelTest.kt
fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/identity/TestTagConventionTest.kt
fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/identity/IdentityHintFactoryTest.kt
fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/identity/OccurrenceCalculatorTest.kt
fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactoryTest.kt
```

Modify:

```text
fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt
fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt
fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt
fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/capture/AnnotationCaptureController.kt
fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisOverlayController.kt
fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt
fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionDtoModels.kt
fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionDomainMappers.kt
fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt
fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt
fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/annotation/Annotation.kt
sample/src/main/java/io/beyondwin/fixthis/sample/components/StudioHeader.kt
sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt
docs/output-schema.md
docs/mcp.md
```

## Task 1: Add Target Evidence Wire Models

**Files:**

- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/TargetEvidenceModels.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/model/TargetEvidenceModelTest.kt`

- [x] **Step 1: Write serialization compatibility tests**

Create `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/model/TargetEvidenceModelTest.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TargetEvidenceModelTest {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        explicitNulls = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun legacyAnnotationWithoutTargetEvidenceStillDecodes() {
        val annotation = json.decodeFromString<FixThisAnnotation>(
            """
            {
              "schemaVersion": "1.0",
              "id": "annotation-1",
              "createdAtEpochMillis": 100,
              "platform": "android-compose",
              "app": {
                "packageName": "io.beyondwin.fixthis.sample",
                "debuggable": true
              },
              "activity": {
                "className": "io.beyondwin.fixthis.sample.MainActivity"
              },
              "tap": {
                "xInWindow": 20.0,
                "yInWindow": 30.0
              },
              "selection": {
                "kind": "SEMANTICS_NODE",
                "confidence": "HIGH",
                "selectedUid": "compose:0:merged:7",
                "source": "TAP_SELECT"
              },
              "selectedNode": {
                "uid": "compose:0:merged:7",
                "composeNodeId": 7,
                "rootIndex": 0,
                "treeKind": "MERGED",
                "boundsInWindow": {
                  "left": 0.0,
                  "top": 0.0,
                  "right": 100.0,
                  "bottom": 48.0
                },
                "text": ["Sign In"],
                "role": "Button",
                "testTag": "comp:AppPrimaryButton:primary"
              },
              "userComment": "Button color is too muted"
            }
            """.trimIndent(),
        )

        assertNull(annotation.targetEvidence)
        assertEquals("annotation-1", annotation.id)
    }

    @Test
    fun annotationWithTargetEvidenceEncodesAndDecodes() {
        val annotation = baseAnnotation().copy(
            targetEvidence = TargetEvidence(
                identityHint = IdentityHint(
                    composableNameHint = "AppPrimaryButton",
                    variantHint = "primary",
                    stableLabel = "Button Sign In",
                    source = IdentityHintSource.TEST_TAG_CONVENTION,
                    confidence = IdentityHintConfidence.HIGH,
                ),
                occurrence = Occurrence(
                    signature = OccurrenceSignature(
                        type = OccurrenceSignatureType.IDENTITY_HINT,
                        value = "AppPrimaryButton:primary",
                    ),
                    count = 2,
                    selectedOrdinal = 1,
                ),
                sourceInterpretation = SourceInterpretation(
                    topCandidate = SourceCandidateSummary(
                        file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                        line = 42,
                        confidence = SelectionConfidence.HIGH,
                    ),
                    reasonSummary = listOf("selected testTag convention composable"),
                    caution = null,
                ),
                evidenceQuality = EvidenceQuality.STRUCTURED,
                screenshotKinds = listOf("full", "crop"),
                warnings = emptyList(),
            ),
        )

        val encoded = json.encodeToString(annotation)
        val decoded = json.decodeFromString<FixThisAnnotation>(encoded)

        assertTrue(encoded.contains("\"targetEvidence\""))
        assertNotNull(decoded.targetEvidence)
        assertEquals("AppPrimaryButton", decoded.targetEvidence?.identityHint?.composableNameHint)
        assertEquals(2, decoded.targetEvidence?.occurrence?.count)
    }

    private fun baseAnnotation(): FixThisAnnotation =
        FixThisAnnotation(
            id = "annotation-1",
            createdAtEpochMillis = 100,
            app = AppInfo(packageName = "io.beyondwin.fixthis.sample", debuggable = true),
            activity = ActivityInfo(className = "io.beyondwin.fixthis.sample.MainActivity"),
            tap = TapPoint(xInWindow = 20f, yInWindow = 30f),
            selection = SelectionInfo(
                kind = SelectionKind.SEMANTICS_NODE,
                confidence = SelectionConfidence.HIGH,
                selectedUid = "compose:0:merged:7",
                source = SelectionSource.TAP_SELECT,
            ),
            selectedNode = FixThisNode(
                uid = "compose:0:merged:7",
                composeNodeId = 7,
                rootIndex = 0,
                treeKind = TreeKind.MERGED,
                boundsInWindow = FixThisRect(0f, 0f, 100f, 48f),
                text = listOf("Sign In"),
                role = "Button",
                testTag = "comp:AppPrimaryButton:primary",
            ),
            userComment = "Button color is too muted",
        )
}
```

- [x] **Step 2: Run the new test to verify it fails**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests 'io.beyondwin.fixthis.compose.core.model.TargetEvidenceModelTest'
```

Expected: FAIL because `targetEvidence`, `TargetEvidence`, `IdentityHint`, `Occurrence`, and related types do not exist.

- [x] **Step 3: Add target evidence models**

Create `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/TargetEvidenceModels.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TargetEvidence(
    val identityHint: IdentityHint? = null,
    val occurrence: Occurrence? = null,
    val sourceInterpretation: SourceInterpretation? = null,
    val evidenceQuality: EvidenceQuality = EvidenceQuality.BASIC,
    val screenshotKinds: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
enum class EvidenceQuality {
    BASIC,
    STRUCTURED,
}

@Serializable
data class IdentityHint(
    val composableNameHint: String? = null,
    val variantHint: String? = null,
    val stableLabel: String? = null,
    val source: IdentityHintSource = IdentityHintSource.NONE,
    val confidence: IdentityHintConfidence = IdentityHintConfidence.LOW,
)

@Serializable
enum class IdentityHintSource {
    TEST_TAG_CONVENTION,
    SEMANTICS,
    NONE,
}

@Serializable
enum class IdentityHintConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

@Serializable
data class Occurrence(
    val basis: String = "captured_merged_semantics_nodes",
    val signature: OccurrenceSignature,
    val count: Int,
    val selectedOrdinal: Int,
)

@Serializable
data class OccurrenceSignature(
    val type: OccurrenceSignatureType,
    val value: String,
)

@Serializable
enum class OccurrenceSignatureType {
    IDENTITY_HINT,
    TEST_TAG,
    ROLE_PLUS_TEXT,
    ROLE_PLUS_CONTENT_DESCRIPTION,
}

@Serializable
data class SourceInterpretation(
    val topCandidate: SourceCandidateSummary? = null,
    val reasonSummary: List<String> = emptyList(),
    val caution: String? = null,
)

@Serializable
data class SourceCandidateSummary(
    val file: String,
    val line: Int? = null,
    val confidence: SelectionConfidence,
)
```

- [x] **Step 4: Add nullable targetEvidence to FixThisAnnotation**

Modify `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt`:

```kotlin
@Serializable
data class FixThisAnnotation(
    val schemaVersion: String = "1.0",
    val id: String,
    val createdAtEpochMillis: Long,
    val platform: String = "android-compose",
    val app: AppInfo,
    val activity: ActivityInfo,
    val tap: TapPoint,
    val selection: SelectionInfo,
    val selectedNode: FixThisNode? = null,
    val candidatesAtPoint: List<ScoredFixThisNode> = emptyList(),
    val scopeCandidates: List<ScopeCandidate> = emptyList(),
    val nearbyNodes: List<FixThisNode> = emptyList(),
    val sourceCandidates: List<SourceCandidate> = emptyList(),
    val searchHints: List<String> = emptyList(),
    val screenshot: ScreenshotInfo? = null,
    val userComment: String,
    val targetEvidence: TargetEvidence? = null,
    val errors: List<FixThisError> = emptyList()
)
```

- [x] **Step 5: Run the model tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests 'io.beyondwin.fixthis.compose.core.model.TargetEvidenceModelTest'
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt \
  fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/TargetEvidenceModels.kt \
  fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/model/TargetEvidenceModelTest.kt
git commit -m "feat: add target evidence annotation model"
```

## Task 2: Add Test Tag Convention And Identity Hint Factory

**Files:**

- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/identity/TestTagConvention.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/identity/IdentityHintFactory.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/identity/TestTagConventionTest.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/identity/IdentityHintFactoryTest.kt`

- [x] **Step 1: Write TestTagConvention tests**

Create `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/identity/TestTagConventionTest.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TestTagConventionTest {
    @Test
    fun parsesStrictCompTag() {
        val parsed = TestTagConvention.parse("comp:AppPrimaryButton:primary")

        assertEquals("AppPrimaryButton", parsed?.composableName)
        assertEquals("primary", parsed?.variant)
    }

    @Test
    fun rejectsPartialOrUnanchoredTags() {
        assertNull(TestTagConvention.parse("comp:Foo"))
        assertNull(TestTagConvention.parse("comp::primary"))
        assertNull(TestTagConvention.parse("comp:Foo:"))
        assertNull(TestTagConvention.parse("xcomp:Foo:primary"))
        assertNull(TestTagConvention.parse("comp:Foo:primary:extra"))
        assertNull(TestTagConvention.parse("studio:tool:select"))
    }

    @Test
    fun acceptsVariantHyphenAndUnderscore() {
        val parsed = TestTagConvention.parse("comp:QueueRow:empty_state")

        assertEquals("QueueRow", parsed?.composableName)
        assertEquals("empty_state", parsed?.variant)
    }
}
```

- [x] **Step 2: Write IdentityHintFactory tests**

Create `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/identity/IdentityHintFactoryTest.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.identity

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.beyondwin.fixthis.compose.core.model.TreeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IdentityHintFactoryTest {
    @Test
    fun usesCompTestTagAsHighConfidenceIdentity() {
        val hint = IdentityHintFactory.from(node(testTag = "comp:AppPrimaryButton:primary", text = listOf("Sign In"), role = "Button"))

        assertEquals("AppPrimaryButton", hint?.composableNameHint)
        assertEquals("primary", hint?.variantHint)
        assertEquals("Button Sign In", hint?.stableLabel)
        assertEquals(IdentityHintSource.TEST_TAG_CONVENTION, hint?.source)
        assertEquals(IdentityHintConfidence.HIGH, hint?.confidence)
    }

    @Test
    fun fallsBackToSemanticsWhenNoConventionExists() {
        val hint = IdentityHintFactory.from(node(testTag = "plainTag", text = listOf("Pay now"), role = "Button"))

        assertNull(hint?.composableNameHint)
        assertEquals("Button Pay now", hint?.stableLabel)
        assertEquals(IdentityHintSource.SEMANTICS, hint?.source)
        assertEquals(IdentityHintConfidence.MEDIUM, hint?.confidence)
    }

    @Test
    fun returnsNullForNullNode() {
        assertNull(IdentityHintFactory.from(null))
    }

    private fun node(
        testTag: String? = null,
        text: List<String> = emptyList(),
        role: String? = null,
    ): FixThisNode =
        FixThisNode(
            uid = "compose:0:merged:1",
            composeNodeId = 1,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(0f, 0f, 100f, 48f),
            text = text,
            role = role,
            testTag = testTag,
        )
}
```

- [x] **Step 3: Run tests to verify they fail**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests 'io.beyondwin.fixthis.compose.core.identity.*'
```

Expected: FAIL because the new identity classes do not exist.

- [x] **Step 4: Implement TestTagConvention**

Create `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/identity/TestTagConvention.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.identity

object TestTagConvention {
    private val pattern = Regex("^comp:([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9_-]+)$")

    data class Parsed(
        val composableName: String,
        val variant: String,
    )

    fun parse(testTag: String?): Parsed? =
        testTag
            ?.let(pattern::matchEntire)
            ?.let { match -> Parsed(composableName = match.groupValues[1], variant = match.groupValues[2]) }
}
```

- [x] **Step 5: Implement IdentityHintFactory**

Create `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/identity/IdentityHintFactory.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.identity

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.IdentityHint
import io.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.beyondwin.fixthis.compose.core.model.IdentityHintSource

object IdentityHintFactory {
    fun from(node: FixThisNode?): IdentityHint? {
        if (node == null) return null

        TestTagConvention.parse(node.testTag)?.let { parsed ->
            return IdentityHint(
                composableNameHint = parsed.composableName,
                variantHint = parsed.variant,
                stableLabel = node.stableSemanticLabel(),
                source = IdentityHintSource.TEST_TAG_CONVENTION,
                confidence = IdentityHintConfidence.HIGH,
            )
        }

        val label = node.stableSemanticLabel()
        return label?.let {
            IdentityHint(
                stableLabel = it,
                source = IdentityHintSource.SEMANTICS,
                confidence = IdentityHintConfidence.MEDIUM,
            )
        }
    }

    private fun FixThisNode.stableSemanticLabel(): String? =
        listOfNotNull(
            role?.clean(),
            text.firstOrNull()?.clean(),
            contentDescription.firstOrNull()?.clean(),
            testTag?.clean()?.let { "#$it" },
        )
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .takeUnless { it.isBlank() }

    private fun String.clean(): String = trim().replace(Regex("\\s+"), " ")
}
```

- [x] **Step 6: Run identity tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests 'io.beyondwin.fixthis.compose.core.identity.*'
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/identity \
  fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/identity
git commit -m "feat: add stable target identity hints"
```

## Task 3: Add Occurrence Calculator

**Files:**

- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/identity/OccurrenceCalculator.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/identity/OccurrenceCalculatorTest.kt`

- [x] **Step 1: Write occurrence tests**

Create `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/identity/OccurrenceCalculatorTest.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.identity

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.IdentityHint
import io.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.beyondwin.fixthis.compose.core.model.OccurrenceSignatureType
import io.beyondwin.fixthis.compose.core.model.TreeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OccurrenceCalculatorTest {
    @Test
    fun countsAndRanksNodesWithSameIdentityHint() {
        val first = node(uid = "a", testTag = "comp:AppPrimaryButton:primary", top = 0f)
        val second = node(uid = "b", testTag = "comp:AppPrimaryButton:primary", top = 60f)
        val hint = IdentityHint(
            composableNameHint = "AppPrimaryButton",
            variantHint = "primary",
            source = IdentityHintSource.TEST_TAG_CONVENTION,
            confidence = IdentityHintConfidence.HIGH,
        )

        val occurrence = OccurrenceCalculator.calculate(
            selectedNode = second,
            nodes = listOf(first, second),
            identityHint = hint,
        )

        assertEquals(OccurrenceSignatureType.IDENTITY_HINT, occurrence?.signature?.type)
        assertEquals("AppPrimaryButton:primary", occurrence?.signature?.value)
        assertEquals(2, occurrence?.count)
        assertEquals(2, occurrence?.selectedOrdinal)
    }

    @Test
    fun fallsBackToExactTestTag() {
        val first = node(uid = "a", testTag = "plainTag", top = 0f)
        val second = node(uid = "b", testTag = "plainTag", top = 60f)

        val occurrence = OccurrenceCalculator.calculate(
            selectedNode = first,
            nodes = listOf(first, second),
            identityHint = null,
        )

        assertEquals(OccurrenceSignatureType.TEST_TAG, occurrence?.signature?.type)
        assertEquals("plainTag", occurrence?.signature?.value)
        assertEquals(2, occurrence?.count)
        assertEquals(1, occurrence?.selectedOrdinal)
    }

    @Test
    fun avoidsRedactedTextSignatureForSensitiveNodes() {
        val selected = node(uid = "password", text = listOf("<redacted>"), role = "TextField", isSensitive = true)

        val occurrence = OccurrenceCalculator.calculate(
            selectedNode = selected,
            nodes = listOf(selected),
            identityHint = null,
        )

        assertNull(occurrence)
    }

    @Test
    fun returnsNullWhenSelectedNodeIsNull() {
        assertNull(OccurrenceCalculator.calculate(selectedNode = null, nodes = emptyList(), identityHint = null))
    }

    private fun node(
        uid: String,
        testTag: String? = null,
        text: List<String> = emptyList(),
        role: String? = null,
        top: Float = 0f,
        isSensitive: Boolean = false,
    ): FixThisNode =
        FixThisNode(
            uid = uid,
            composeNodeId = uid.hashCode(),
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(0f, top, 100f, top + 48f),
            text = text,
            role = role,
            testTag = testTag,
            isSensitive = isSensitive,
        )
}
```

- [x] **Step 2: Run occurrence test to verify it fails**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests 'io.beyondwin.fixthis.compose.core.identity.OccurrenceCalculatorTest'
```

Expected: FAIL because `OccurrenceCalculator` does not exist.

- [x] **Step 3: Implement OccurrenceCalculator**

Create `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/identity/OccurrenceCalculator.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.identity

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.IdentityHint
import io.beyondwin.fixthis.compose.core.model.Occurrence
import io.beyondwin.fixthis.compose.core.model.OccurrenceSignature
import io.beyondwin.fixthis.compose.core.model.OccurrenceSignatureType

object OccurrenceCalculator {
    fun calculate(
        selectedNode: FixThisNode?,
        nodes: List<FixThisNode>,
        identityHint: IdentityHint?,
    ): Occurrence? {
        if (selectedNode == null) return null

        val signature = selectedNode.signature(identityHint) ?: return null
        val matching = nodes
            .filter { node -> node.signature(identityHint) == signature }
            .sortedWith(
                compareBy<FixThisNode> { it.boundsInWindow.top }
                    .thenBy { it.boundsInWindow.left }
                    .thenBy { it.uid },
            )
        val ordinal = matching.indexOfFirst { it.uid == selectedNode.uid }.takeIf { it >= 0 }?.plus(1) ?: return null

        return Occurrence(
            signature = signature,
            count = matching.size,
            selectedOrdinal = ordinal,
        )
    }

    private fun FixThisNode.signature(identityHint: IdentityHint?): OccurrenceSignature? {
        val composableName = identityHint?.composableNameHint?.takeUnlessBlank()
        val variant = identityHint?.variantHint?.takeUnlessBlank()
        val parsedTag = TestTagConvention.parse(testTag)
        if (
            composableName != null &&
            variant != null &&
            parsedTag?.composableName == composableName &&
            parsedTag.variant == variant
        ) {
            return OccurrenceSignature(
                type = OccurrenceSignatureType.IDENTITY_HINT,
                value = "$composableName:$variant",
            )
        }

        testTag?.takeUnlessBlank()?.let { tag ->
            return OccurrenceSignature(type = OccurrenceSignatureType.TEST_TAG, value = tag)
        }

        if (isPassword || isSensitive) return null

        val roleValue = role?.takeUnlessBlank() ?: return null
        text.firstOrNull()?.takeUnlessBlank()?.let { value ->
            return OccurrenceSignature(type = OccurrenceSignatureType.ROLE_PLUS_TEXT, value = "$roleValue:$value")
        }
        contentDescription.firstOrNull()?.takeUnlessBlank()?.let { value ->
            return OccurrenceSignature(type = OccurrenceSignatureType.ROLE_PLUS_CONTENT_DESCRIPTION, value = "$roleValue:$value")
        }
        return null
    }

    private fun String.takeUnlessBlank(): String? =
        trim().takeUnless { it.isBlank() }
}
```

- [x] **Step 4: Run occurrence tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests 'io.beyondwin.fixthis.compose.core.identity.OccurrenceCalculatorTest'
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/identity/OccurrenceCalculator.kt \
  fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/identity/OccurrenceCalculatorTest.kt
git commit -m "feat: add stable occurrence evidence"
```

## Task 4: Add Source Interpretation And Convention-Aware Source Matching

**Files:**

- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactory.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactoryTest.kt`
- Modify test: `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`

- [x] **Step 1: Write source interpretation tests**

Create `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactoryTest.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceInterpretationFactoryTest {
    @Test
    fun summarizesTopCandidate() {
        val interpretation = SourceInterpretationFactory.from(
            sourceCandidates = listOf(
                SourceCandidate(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                    line = 42,
                    score = 1.0,
                    matchedTerms = listOf("AppPrimaryButton"),
                    matchReasons = listOf("selected testTag convention composable"),
                    confidence = SelectionConfidence.HIGH,
                ),
            ),
        )

        assertEquals("sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt", interpretation.topCandidate?.file)
        assertEquals(42, interpretation.topCandidate?.line)
        assertEquals(SelectionConfidence.HIGH, interpretation.topCandidate?.confidence)
        assertEquals(listOf("selected testTag convention composable"), interpretation.reasonSummary)
        assertNull(interpretation.caution)
    }

    @Test
    fun returnsCautionWhenNoCandidateExists() {
        val interpretation = SourceInterpretationFactory.from(emptyList())

        assertNull(interpretation.topCandidate)
        assertEquals("No source candidate was available from current evidence.", interpretation.caution)
    }
}
```

- [x] **Step 2: Add SourceMatcher convention test**

Append this test to `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`:

```kotlin
@Test
fun conventionTestTagCanMatchComposableSymbol() {
    val matcher = SourceMatcher(
        SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                    line = 12,
                    symbols = listOf("AppPrimaryButton"),
                    excerpt = "@Composable fun AppPrimaryButton(...)",
                ),
            ),
        ),
    )

    val matches = matcher.match(
        selectedNode = node(
            uid = "button",
            text = listOf("Sign In"),
            testTag = "comp:AppPrimaryButton:primary",
        ),
        nearbyNodes = emptyList(),
        activityName = "io.beyondwin.fixthis.sample.MainActivity",
    )

    assertEquals("sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt", matches.first().file)
    assertTrue(matches.first().matchReasons.contains("selected testTag convention composable"))
}
```

- [x] **Step 3: Run source tests to verify failures**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests 'io.beyondwin.fixthis.compose.core.source.SourceInterpretationFactoryTest' --tests 'io.beyondwin.fixthis.compose.core.source.SourceMatcherTest'
```

Expected: FAIL because `SourceInterpretationFactory` is missing and `SourceMatcher` does not add convention reasons.

- [x] **Step 4: Implement SourceInterpretationFactory**

Create `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactory.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.source

import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.SourceCandidateSummary
import io.beyondwin.fixthis.compose.core.model.SourceInterpretation

object SourceInterpretationFactory {
    fun from(sourceCandidates: List<SourceCandidate>): SourceInterpretation {
        val top = sourceCandidates.firstOrNull()
            ?: return SourceInterpretation(caution = "No source candidate was available from current evidence.")

        return SourceInterpretation(
            topCandidate = SourceCandidateSummary(
                file = top.file,
                line = top.line,
                confidence = top.confidence,
            ),
            reasonSummary = top.matchReasons.take(5),
            caution = when {
                top.confidence.name == "LOW" -> "Top source candidate has low confidence; verify before editing."
                else -> null
            },
        )
    }
}
```

- [x] **Step 5: Add convention-aware scoring to SourceMatcher**

Modify `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`:

```kotlin
import io.beyondwin.fixthis.compose.core.identity.TestTagConvention
```

Inside `score(...)`, directly after the existing selected testTag block:

```kotlin
selectedNode.testTag?.let { term ->
    TestTagConvention.parse(term)?.let { parsed ->
        rawScore += addIfMatches(
            matches = entry.matchesConventionComposable(parsed.composableName),
            term = parsed.composableName,
            reason = "selected testTag convention composable",
            score = 65.0,
            matchedTerms = matchedTerms,
            matchReasons = matchReasons,
            scoredEvidence = scoredEvidence,
        )
    }
}
```

Add this helper near the existing `matchesTestTag` helper:

```kotlin
private fun SourceIndexEntry.matchesConventionComposable(composableName: String): Boolean =
    matchesAny(composableName, symbols + listOf(file) + listOfNotNull(excerpt))
```

- [x] **Step 6: Run source tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests 'io.beyondwin.fixthis.compose.core.source.SourceInterpretationFactoryTest' --tests 'io.beyondwin.fixthis.compose.core.source.SourceMatcherTest'
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt \
  fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactory.kt \
  fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceInterpretationFactoryTest.kt \
  fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt
git commit -m "feat: add source interpretation evidence"
```

## Task 5: Compute Target Evidence During Capture

**Files:**

- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/capture/AnnotationCaptureController.kt`
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisOverlayController.kt`
- Modify test: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/capture/AnnotationCaptureControllerTest.kt`
- Modify test: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisOverlayControllerTest.kt`

- [x] **Step 1: Add capture controller test for target evidence**

Append to `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/capture/AnnotationCaptureControllerTest.kt`:

```kotlin
@Test
fun captureAddsTargetEvidenceForSelectedNode() {
    val selected = node(
        uid = "primary",
        bounds = rect(0f, 0f, 160f, 48f),
        text = listOf("Sign In"),
        role = "Button",
        testTag = "comp:AppPrimaryButton:primary",
        actions = listOf("OnClick"),
    )
    val duplicate = node(
        uid = "secondary",
        bounds = rect(0f, 80f, 160f, 128f),
        text = listOf("Sign In"),
        role = "Button",
        testTag = "comp:AppPrimaryButton:primary",
        actions = listOf("OnClick"),
    )

    val annotation = controller.capture(
        AnnotationCaptureInput(
            app = appInfo(),
            activity = activityInfo(),
            tap = TapPoint(10f, 10f),
            nodes = listOf(selected, duplicate),
            sourceIndex = SourceIndex(
                entries = listOf(
                    SourceIndexEntry(
                        file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                        line = 12,
                        symbols = listOf("AppPrimaryButton"),
                    ),
                ),
            ),
            userComment = "Button color is too muted",
        ),
    )

    assertEquals("AppPrimaryButton", annotation.targetEvidence?.identityHint?.composableNameHint)
    assertEquals(2, annotation.targetEvidence?.occurrence?.count)
    assertEquals(1, annotation.targetEvidence?.occurrence?.selectedOrdinal)
    assertEquals("sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt", annotation.targetEvidence?.sourceInterpretation?.topCandidate?.file)
}
```

- [x] **Step 2: Run capture test to verify it fails**

Run:

```bash
./gradlew :fixthis-compose-sidekick:test --tests 'io.beyondwin.fixthis.compose.sidekick.capture.AnnotationCaptureControllerTest.captureAddsTargetEvidenceForSelectedNode'
```

Expected: FAIL because capture does not populate `targetEvidence`.

- [x] **Step 3: Compute evidence in AnnotationCaptureController**

Modify imports in `AnnotationCaptureController.kt`:

```kotlin
import io.beyondwin.fixthis.compose.core.identity.IdentityHintFactory
import io.beyondwin.fixthis.compose.core.identity.OccurrenceCalculator
import io.beyondwin.fixthis.compose.core.model.EvidenceQuality
import io.beyondwin.fixthis.compose.core.model.TargetEvidence
import io.beyondwin.fixthis.compose.core.source.SourceInterpretationFactory
```

After `sourceCandidates` is computed and before returning `FixThisAnnotation`, add:

```kotlin
val identityHint = IdentityHintFactory.from(captureSelection.selectedNode)
val occurrence = OccurrenceCalculator.calculate(
    selectedNode = captureSelection.selectedNode,
    nodes = input.nodes,
    identityHint = identityHint,
)
val sourceInterpretation = SourceInterpretationFactory.from(sourceCandidates)
val targetEvidence = TargetEvidence(
    identityHint = identityHint,
    occurrence = occurrence,
    sourceInterpretation = sourceInterpretation,
    evidenceQuality = if (identityHint != null || occurrence != null || sourceCandidates.isNotEmpty()) {
        EvidenceQuality.STRUCTURED
    } else {
        EvidenceQuality.BASIC
    },
    warnings = buildList {
        if (captureSelection.selection.kind == SelectionKind.VISUAL_AREA) {
            add("Occurrence is not applicable for visual area selections.")
        }
        if (captureSelection.selectedNode == null && input.areaBoundsInWindow == null) {
            add("No selected semantics node was available for target evidence.")
        }
    },
)
```

Pass `targetEvidence = targetEvidence` in the returned `FixThisAnnotation`.

- [x] **Step 4: Thread source index into bridge-driven overlay capture**

Modify `FixThisOverlayController.kt` so bridge-driven capture can pass a real source index while manual overlay capture keeps using `SourceIndex()`.

Add a property:

```kotlin
private var activeSourceIndex: SourceIndex = SourceIndex()
```

Change `startFeedbackCapture` signature:

```kotlin
internal suspend fun startFeedbackCapture(
    timeoutMillis: Long,
    pollMillis: Long = 100L,
    sourceIndex: SourceIndex = SourceIndex(),
): FeedbackCaptureWaitResult {
```

Inside the `try` block, before `startSelection()`:

```kotlin
activeSourceIndex = sourceIndex
```

Inside the `finally` block:

```kotlin
activeSourceIndex = SourceIndex()
feedbackCaptureInFlight = false
```

In `captureSelection`, replace:

```kotlin
sourceIndex = SourceIndex(),
```

with:

```kotlin
sourceIndex = activeSourceIndex,
```

- [x] **Step 5: Pass source index from AndroidBridgeEnvironment**

Modify `BridgeServer.kt` in `AndroidBridgeEnvironment.startFeedbackCapture`:

```kotlin
val sourceIndex = readSourceIndex().sourceIndex ?: SourceIndex()
val result = controller.startFeedbackCapture(
    timeoutMillis = timeoutMillis,
    sourceIndex = sourceIndex,
)
```

Keep manual overlay flows unchanged.

- [x] **Step 6: Add screenshot kinds after capture screenshot is stored**

In `FixThisOverlayController.captureSelection`, after `val screenshot = screenshotCapturer.capture(...)`, replace:

```kotlin
val annotation = annotationWithoutScreenshot.copy(screenshot = screenshot)
```

with:

```kotlin
val annotation = annotationWithoutScreenshot.copy(
    screenshot = screenshot,
    targetEvidence = annotationWithoutScreenshot.targetEvidence?.copy(
        screenshotKinds = screenshot.availableKinds(),
    ),
)
```

Add this private extension near the bottom of the file:

```kotlin
private fun ScreenshotInfo.availableKinds(): List<String> =
    buildList {
        if (!fullPath.isNullOrBlank() || !desktopFullPath.isNullOrBlank()) add("full")
        if (!cropPath.isNullOrBlank() || !desktopCropPath.isNullOrBlank()) add("crop")
    }
```

- [x] **Step 7: Run capture and overlay tests**

Run:

```bash
./gradlew :fixthis-compose-sidekick:test --tests 'io.beyondwin.fixthis.compose.sidekick.capture.AnnotationCaptureControllerTest' --tests 'io.beyondwin.fixthis.compose.sidekick.overlay.FixThisOverlayControllerTest'
```

Expected: PASS.

- [x] **Step 8: Commit**

```bash
git add fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/capture/AnnotationCaptureController.kt \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisOverlayController.kt \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt \
  fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/capture/AnnotationCaptureControllerTest.kt \
  fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/overlay/FixThisOverlayControllerTest.kt
git commit -m "feat: compute target evidence during capture"
```

## Task 6: Persist Target Evidence Through Domain And MCP DTOs

**Files:**

- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/annotation/Annotation.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionDtoModels.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionDomainMappers.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionPersistenceTest.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`

- [x] **Step 1: Add persistence test for old session JSON**

Append to `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionPersistenceTest.kt`:

```kotlin
@Test
fun loadSessionWithoutTargetEvidenceStillWorks() {
    val root = temporaryFolder.newFolder("project")
    val paths = FeedbackSessionPaths(root)
    val sessionDirectory = paths.sessionDirectory("session-1")
    assertTrue(sessionDirectory.mkdirs())
    paths.sessionFile("session-1").writeText(
        """
        {
          "schemaVersion": "1.0",
          "sessionId": "session-1",
          "packageName": "io.beyondwin.fixthis.sample",
          "projectRoot": "${root.absolutePath.replace("\\", "\\\\")}",
          "createdAtEpochMillis": 100,
          "updatedAtEpochMillis": 100,
          "screens": [],
          "items": [],
          "handoffBatches": [],
          "status": "active"
        }
        """.trimIndent(),
    )

    val loaded = FeedbackSessionPersistence(paths).load("session-1")

    assertEquals("session-1", loaded.sessionId)
    assertEquals(0, loaded.items.size)
}
```

- [x] **Step 2: Run persistence test**

Run:

```bash
./gradlew :fixthis-mcp:test --tests 'io.beyondwin.fixthis.mcp.session.FeedbackSessionPersistenceTest.loadSessionWithoutTargetEvidenceStillWorks'
```

Expected: PASS before and after model changes. This guards the old-session path.

- [x] **Step 3: Add targetEvidence to domain Annotation**

Modify `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/annotation/Annotation.kt`:

```kotlin
import io.beyondwin.fixthis.compose.core.model.TargetEvidence
```

Add to `data class Annotation`:

```kotlin
val targetEvidence: TargetEvidence? = null,
```

Place it after `screenshotCrop` and before `comment` to keep evidence fields grouped.

- [x] **Step 4: Add targetEvidence to AnnotationDto**

Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionDtoModels.kt`:

```kotlin
import io.beyondwin.fixthis.compose.core.model.TargetEvidence
```

Add to `data class AnnotationDto`:

```kotlin
val targetEvidence: TargetEvidence? = null,
```

Place it after `screenshotCrop` and before `comment`.

- [x] **Step 5: Map targetEvidence in both directions**

Modify `SessionDomainMappers.kt`.

In `AnnotationDto.toDomainAnnotation(...)`, add:

```kotlin
targetEvidence = targetEvidence,
```

In `Annotation.toAnnotationDto()`, add:

```kotlin
targetEvidence = targetEvidence,
```

- [x] **Step 6: Run MCP session tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests 'io.beyondwin.fixthis.mcp.session.*'
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/annotation/Annotation.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionDtoModels.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionDomainMappers.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionPersistenceTest.kt
git commit -m "feat: persist target evidence in feedback sessions"
```

## Task 7: Add Markdown Detail Modes

**Files:**

- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/DetailMode.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatterTest.kt`

- [x] **Step 1: Add formatter tests for detail modes**

Append to `FixThisMarkdownFormatterTest.kt`:

```kotlin
@Test
fun compactModeIncludesRequestTargetTopSourceAndOmitsNearbyDetails() {
    val markdown = FixThisMarkdownFormatter.format(annotationWithTargetEvidence(), DetailMode.COMPACT)

    assertTrue(markdown.contains("# FixThis Feedback"))
    assertTrue(markdown.contains("Request:"))
    assertTrue(markdown.contains("Target:"))
    assertTrue(markdown.contains("AppPrimaryButton"))
    assertTrue(markdown.contains("Occurrence: 1/2"))
    assertTrue(markdown.contains("Top Source:"))
    assertFalse(markdown.contains("Nearby context"))
}

@Test
fun preciseModeIncludesTopThreeSourcesAndEvidenceWarnings() {
    val markdown = FixThisMarkdownFormatter.format(annotationWithTargetEvidence(), DetailMode.PRECISE)

    assertTrue(markdown.contains("## Target Evidence"))
    assertTrue(markdown.contains("Identity:"))
    assertTrue(markdown.contains("Occurrence: 1/2"))
    assertTrue(markdown.contains("## Source Candidates"))
}

@Test
fun fullModeKeepsExistingVerboseSections() {
    val markdown = FixThisMarkdownFormatter.format(annotationWithTargetEvidence(), DetailMode.FULL)

    assertTrue(markdown.contains("## Selection"))
    assertTrue(markdown.contains("## Selected UI"))
    assertTrue(markdown.contains("## Nearby context"))
    assertTrue(markdown.contains("## Source candidates"))
}
```

Add this helper above the existing `annotation(...)` helper in the same test file:

```kotlin
private fun annotationWithTargetEvidence(): FixThisAnnotation {
    val selectedNode = node(
        uid = "pay-button",
        text = listOf("Sign In"),
        role = "Button",
        testTag = "comp:AppPrimaryButton:primary",
        actions = listOf("OnClick"),
    )
    return annotation(
        userComment = "Button color is too muted",
        selectedNode = selectedNode,
        sourceCandidates = listOf(
            SourceCandidate(
                file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                line = 42,
                score = 1.0,
                matchedTerms = listOf("AppPrimaryButton"),
                matchReasons = listOf("selected testTag convention composable"),
                confidence = SelectionConfidence.HIGH,
            ),
        ),
    ).copy(
        targetEvidence = TargetEvidence(
            identityHint = IdentityHint(
                composableNameHint = "AppPrimaryButton",
                variantHint = "primary",
                stableLabel = "Button Sign In",
                source = IdentityHintSource.TEST_TAG_CONVENTION,
                confidence = IdentityHintConfidence.HIGH,
            ),
            occurrence = Occurrence(
                signature = OccurrenceSignature(
                    type = OccurrenceSignatureType.IDENTITY_HINT,
                    value = "AppPrimaryButton:primary",
                ),
                count = 2,
                selectedOrdinal = 1,
            ),
        ),
    )
}
```

Add imports:

```kotlin
import io.beyondwin.fixthis.compose.core.model.IdentityHint
import io.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.beyondwin.fixthis.compose.core.model.Occurrence
import io.beyondwin.fixthis.compose.core.model.OccurrenceSignature
import io.beyondwin.fixthis.compose.core.model.OccurrenceSignatureType
import io.beyondwin.fixthis.compose.core.model.TargetEvidence
```

- [x] **Step 2: Run formatter tests to verify failures**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests 'io.beyondwin.fixthis.compose.core.format.FixThisMarkdownFormatterTest'
```

Expected: FAIL because `DetailMode` and detail-mode formatting do not exist.

- [x] **Step 3: Create DetailMode enum**

Create `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/DetailMode.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.format

enum class DetailMode {
    COMPACT,
    PRECISE,
    FULL;

    companion object {
        fun fromWire(value: String?): DetailMode =
            when (value?.trim()?.lowercase()) {
                null,
                "",
                "precise",
                -> PRECISE
                "compact" -> COMPACT
                "full" -> FULL
                else -> throw IllegalArgumentException("Unsupported detailMode: $value")
            }
    }
}
```

- [x] **Step 4: Add overload and route modes in FixThisMarkdownFormatter**

Modify `FixThisMarkdownFormatter`:

```kotlin
fun format(annotation: FixThisAnnotation): String =
    format(annotation, DetailMode.PRECISE)

fun format(annotation: FixThisAnnotation, detailMode: DetailMode): String =
    when (detailMode) {
        DetailMode.COMPACT -> formatCompact(annotation)
        DetailMode.PRECISE -> formatPrecise(annotation)
        DetailMode.FULL -> formatFull(annotation)
    }
```

Move the existing `buildString { ... }` body into:

```kotlin
private fun formatFull(annotation: FixThisAnnotation): String = buildString {
    appendLine("# FixThis Compose Feedback")
    appendLine()
    appendTargetEvidence(annotation)
    appendLine()
    appendLine("## User request")
    appendFreeFormBlock(annotation.userComment)
    appendLine()
    appendLine("## Selection")
    appendLine("- Kind: ${annotation.selection.kind}")
    appendLine("- Confidence: ${annotation.selection.confidence}")
    appendLine("- Source: ${annotation.selection.source}")
    annotation.selection.selectedUid?.let { appendLine("- Selected UID: ${it.markdownInline()}") }
    annotation.selection.areaBoundsInWindow?.let { appendLine("- Area: ${it.format()}") }
    appendLine("- Tap: ${annotation.tap.xInWindow},${annotation.tap.yInWindow}")
    appendLine()
    appendLine("## Selected UI")
    appendNode(annotation.selectedNode)
    appendLine()
    appendLine("## Nearby context")
    if (annotation.nearbyNodes.isEmpty()) {
        appendLine("- none")
    } else {
        annotation.nearbyNodes.forEach { node ->
            appendLine("- ${node.summary()} (${node.uid.markdownInline()}, ${node.treeKind}, ${node.boundsInWindow.format()})")
        }
    }
    appendLine()
    appendLine("## Source candidates")
    appendSourceCandidates(annotation.sourceCandidates, maxCandidates = Int.MAX_VALUE)
    appendLine()
    appendLine("## Search hints")
    if (annotation.searchHints.isEmpty()) {
        appendLine("- none")
    } else {
        annotation.searchHints.forEach { appendLine("- \"${it.markdownInline()}\"") }
    }
    appendLine()
    appendScreenshot(annotation)
    appendErrors(annotation)
}
```

Add compact and precise renderers:

```kotlin
private fun formatCompact(annotation: FixThisAnnotation): String = buildString {
    appendLine("# FixThis Feedback")
    appendLine()
    appendLine("Request:")
    appendLine(annotation.userComment.ifBlank { "(No request provided)" })
    appendLine()
    appendLine("Target:")
    appendCompactTarget(annotation)
    appendLine()
    appendLine("Top Source:")
    appendSourceCandidates(annotation.sourceCandidates, maxCandidates = 1)
}

private fun formatPrecise(annotation: FixThisAnnotation): String = buildString {
    appendLine("# FixThis Feedback")
    appendLine()
    appendLine("## Request")
    appendFreeFormBlock(annotation.userComment)
    appendLine()
    appendLine("## Target Evidence")
    appendTargetEvidence(annotation)
    appendLine()
    appendLine("## Selected UI")
    appendNode(annotation.selectedNode)
    appendLine()
    appendLine("## Source Candidates")
    appendSourceCandidates(annotation.sourceCandidates, maxCandidates = 3)
    appendLine()
    appendScreenshot(annotation)
    appendErrors(annotation)
}
```

Add helper renderers:

```kotlin
private fun StringBuilder.appendTargetEvidence(annotation: FixThisAnnotation) {
    val evidence = annotation.targetEvidence
    if (evidence == null) {
        appendLine("- Evidence: basic semantics only")
        return
    }
    evidence.identityHint?.let { hint ->
        val name = listOfNotNull(hint.composableNameHint, hint.variantHint).joinToString(":").ifBlank { "none" }
        appendLine("- Identity: ${name.markdownInline()} (${hint.source}, ${hint.confidence})")
        hint.stableLabel?.let { appendLine("- Label: ${it.markdownInline()}") }
    }
    evidence.occurrence?.let { occurrence ->
        appendLine("- Occurrence: ${occurrence.selectedOrdinal}/${occurrence.count} (${occurrence.signature.type}, ${occurrence.basis})")
    } ?: appendLine("- Occurrence: not available")
    evidence.sourceInterpretation?.caution?.let { appendLine("- Caution: ${it.markdownInline()}") }
    if (evidence.warnings.isNotEmpty()) {
        appendLine("- Warnings: ${evidence.warnings.markdownListValue()}")
    }
}

private fun StringBuilder.appendCompactTarget(annotation: FixThisAnnotation) {
    val evidence = annotation.targetEvidence
    val hint = evidence?.identityHint
    val identity = listOfNotNull(hint?.composableNameHint, hint?.variantHint)
        .joinToString(":")
        .takeUnless { it.isBlank() }
    if (identity != null) appendLine("- Identity: ${identity.markdownInline()}")
    evidence?.occurrence?.let { appendLine("- Occurrence: ${it.selectedOrdinal}/${it.count}") }
    appendNodeEvidence(annotation.selectedNode)
}
```

Reuse or add `appendSourceCandidates`, `appendScreenshot`, `appendErrors`, and `appendNodeEvidence` helpers by extracting code from the existing formatter. Keep `format(annotation)` as the compatibility entrypoint.

Task 7 note: `format(annotation)` intentionally routes to `DetailMode.FULL` to preserve the existing public formatter output; `DetailMode.fromWire(null)` remains `PRECISE` for wire defaults.

- [x] **Step 5: Run formatter tests**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests 'io.beyondwin.fixthis.compose.core.format.FixThisMarkdownFormatterTest'
```

Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/DetailMode.kt \
  fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt \
  fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatterTest.kt
git commit -m "feat: add feedback markdown detail modes"
```

## Task 8: Add detailMode To MCP Feedback Reading

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/McpProtocolTest.kt`

- [x] **Step 1: Add FeedbackQueueFormatter detail mode tests**

Append to `FeedbackQueueFormatterTest.kt`:

```kotlin
@Test
fun compactModeKeepsQueueMarkdownShort() {
    val selectedNode = FixThisNode(
        uid = "compose:0:merged:42",
        composeNodeId = 42,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(10f, 20f, 110f, 70f),
        text = listOf("Pay now"),
        role = "Button",
        testTag = "comp:AppPrimaryButton:primary",
    )
    val session = SessionDto(
        sessionId = "session-1",
        packageName = "io.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        items = listOf(
            AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 2L,
                updatedAtEpochMillis = 2L,
                target = AnnotationTargetDto.Node(selectedNode.uid, selectedNode.boundsInWindow),
                selectedNode = selectedNode,
                targetEvidence = TargetEvidence(
                    identityHint = IdentityHint(
                        composableNameHint = "AppPrimaryButton",
                        variantHint = "primary",
                        stableLabel = "Button Pay now",
                        source = IdentityHintSource.TEST_TAG_CONVENTION,
                        confidence = IdentityHintConfidence.HIGH,
                    ),
                    occurrence = Occurrence(
                        signature = OccurrenceSignature(
                            type = OccurrenceSignatureType.IDENTITY_HINT,
                            value = "AppPrimaryButton:primary",
                        ),
                        count = 2,
                        selectedOrdinal = 1,
                    ),
                ),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                        line = 42,
                        score = 0.95,
                        matchedTerms = listOf("AppPrimaryButton"),
                        matchReasons = listOf("selected testTag convention composable"),
                        confidence = SelectionConfidence.HIGH,
                    ),
                ),
                comment = "Increase button contrast",
                sequenceNumber = 1,
            ),
        ),
    )

    val markdown = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)

    assertTrue(markdown.contains("# FixThis Feedback Handoff"))
    assertTrue(markdown.contains("## Item 1"))
    assertTrue(markdown.contains("Target:"))
    assertFalse(markdown.contains("Nearby context"))
}

@Test
fun jsonDoesNotChangeWithDetailMode() {
    val session = SessionDto(
        sessionId = "session-1",
        packageName = "io.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        items = listOf(
            AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 2L,
                updatedAtEpochMillis = 2L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "Increase button contrast",
                sequenceNumber = 1,
            ),
        ),
    )

    val before = FeedbackQueueFormatter.toJson(session)
    FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)
    FeedbackQueueFormatter.toMarkdown(session, DetailMode.FULL)

    assertEquals(before, FeedbackQueueFormatter.toJson(session))
}
```

Import:

```kotlin
import io.beyondwin.fixthis.compose.core.format.DetailMode
import io.beyondwin.fixthis.compose.core.model.IdentityHint
import io.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.beyondwin.fixthis.compose.core.model.Occurrence
import io.beyondwin.fixthis.compose.core.model.OccurrenceSignature
import io.beyondwin.fixthis.compose.core.model.OccurrenceSignatureType
import io.beyondwin.fixthis.compose.core.model.TargetEvidence
import kotlin.test.assertEquals
```

- [x] **Step 2: Add MCP tool schema test**

In `McpProtocolTest.kt`, extend the existing tool listing assertion for `fixthis_read_feedback` to check that the input schema includes `detailMode`. Add an assertion near existing `fixthis_read_feedback` schema checks:

```kotlin
assertTrue(output.contains("\"detailMode\""))
assertTrue(output.contains("compact"))
assertTrue(output.contains("precise"))
assertTrue(output.contains("full"))
```

- [x] **Step 3: Run MCP tests to verify failures**

Run:

```bash
./gradlew :fixthis-mcp:test --tests 'io.beyondwin.fixthis.mcp.session.FeedbackQueueFormatterTest' --tests 'io.beyondwin.fixthis.mcp.McpProtocolTest'
```

Expected: FAIL because `FeedbackQueueFormatter.toMarkdown(session, detailMode)` and schema support do not exist.

- [x] **Step 4: Add overload to FeedbackQueueFormatter**

Modify `FeedbackQueueFormatter.kt`:

```kotlin
import io.beyondwin.fixthis.compose.core.format.DetailMode
```

Change:

```kotlin
fun toMarkdown(session: SessionDto): String = buildString {
```

to:

```kotlin
fun toMarkdown(session: SessionDto): String =
    toMarkdown(session, DetailMode.PRECISE)

fun toMarkdown(session: SessionDto, detailMode: DetailMode): String = buildString {
```

Update item rendering so source candidate count follows the mode:

```kotlin
appendLikelySource(
    sourceCandidates = item.sourceCandidates,
    target = item.target,
    maxCandidates = when (detailMode) {
        DetailMode.COMPACT -> 1
        DetailMode.PRECISE -> 3
        DetailMode.FULL -> Int.MAX_VALUE
    },
)
```

Change `appendLikelySource` signature:

```kotlin
private fun StringBuilder.appendLikelySource(
    sourceCandidates: List<SourceCandidate>,
    target: AnnotationTargetDto,
    maxCandidates: Int,
) {
```

Inside it, iterate:

```kotlin
sourceCandidates.take(maxCandidates).forEachIndexed { index, candidate ->
```

Add target evidence lines in `appendTarget(item)` after selected node evidence:

```kotlin
item.targetEvidence?.occurrence?.let { occurrence ->
    appendLine("- Occurrence: `${occurrence.selectedOrdinal}/${occurrence.count}`")
}
item.targetEvidence?.identityHint?.let { hint ->
    val identity = listOfNotNull(hint.composableNameHint, hint.variantHint).joinToString(":")
    if (identity.isNotBlank()) appendLine("- Identity: `${identity.inlineSafe()}`")
}
```

- [x] **Step 5: Parse detailMode in FixThisTools**

Modify `FixThisTools.kt` imports:

```kotlin
import io.beyondwin.fixthis.compose.core.format.DetailMode
```

In the `fixthis_read_feedback` call branch:

```kotlin
val detailMode = DetailMode.fromWire(arguments.stringParam("detailMode"))
val session = requestedSession(arguments).focusedOn(arguments.stringParam("itemId"))
toolResult(
    content = listOf(
        textContent(FeedbackQueueFormatter.toJson(session), "application/json"),
        textContent(FeedbackQueueFormatter.toMarkdown(session, detailMode), "text/markdown"),
    ),
)
```

In the `fixthis_read_feedback` tool definition, add:

```kotlin
"detailMode" to enumStringProperty(
    description = "Markdown detail level. JSON remains complete regardless of this value.",
    values = listOf("compact", "precise", "full"),
),
```

Add helper near `stringProperty`:

```kotlin
private fun enumStringProperty(description: String, values: List<String>): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}
```

- [x] **Step 6: Run MCP tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests 'io.beyondwin.fixthis.mcp.session.FeedbackQueueFormatterTest' --tests 'io.beyondwin.fixthis.mcp.McpProtocolTest'
```

Expected: PASS.

- [x] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt \
  fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/McpProtocolTest.kt
git commit -m "feat: add feedback handoff detail mode"
```

## Task 9: Add Bridge Capabilities Without Protocol Bump

**Files:**

- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`
- Test: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerTest.kt`

- [x] **Step 1: Add bridge status capability test**

Append to `BridgeServerTest.kt`:

```kotlin
@Test
fun statusReportsTargetEvidenceCapabilities() = runBlocking {
    val server = server()

    val response = server.handleRequestForTest(
        """{"id":"1","token":"token","method":"status"}""",
    )

    assertTrue(response.contains("\"targetEvidence\""))
    assertTrue(response.contains("\"detailModes\""))
    assertTrue(response.contains("\"composableIdentity\""))
    assertTrue(response.contains("\"bridgeProtocolVersion\": \"1.0\""))
}
```

- [x] **Step 2: Run bridge test to verify it fails**

Run:

```bash
./gradlew :fixthis-compose-sidekick:test --tests 'io.beyondwin.fixthis.compose.sidekick.bridge.BridgeServerTest.statusReportsTargetEvidenceCapabilities'
```

Expected: FAIL because capabilities are not reported.

- [x] **Step 3: Add bridge capability models**

Modify `BridgeServer.kt`.

Add to `BridgeStatus`:

```kotlin
val capabilities: BridgeCapabilities = BridgeCapabilities(),
```

Add serializable models:

```kotlin
@Serializable
data class BridgeCapabilities(
    val targetEvidence: Boolean = true,
    val detailModes: List<String> = listOf("compact", "precise", "full"),
    val composableIdentity: Boolean = false,
)
```

Do not change `BridgeProtocol.VERSION`.

- [x] **Step 4: Run bridge tests**

Run:

```bash
./gradlew :fixthis-compose-sidekick:test --tests 'io.beyondwin.fixthis.compose.sidekick.bridge.BridgeServerTest'
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt \
  fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServerTest.kt
git commit -m "feat: report target evidence bridge capabilities"
```

## Task 10: Add Sample Golden Path Test Tags

**Files:**

- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/components/StudioHeader.kt`
- Modify: `sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt`
- Test: `sample/src/androidTest/java/io/beyondwin/fixthis/sample/SampleAppSmokeTest.kt`

- [ ] **Step 1: Add sample test-tag assertions**

Append to `SampleAppSmokeTest.kt`:

```kotlin
@Test
fun homeScreenExposesStableTargetEvidenceTags() {
    composeRule.onNodeWithTag("comp:StudioHeader:root").assertExists()
    composeRule.onNodeWithTag("comp:HomePrimaryAction:primary").assertExists()
    composeRule.onAllNodesWithTag("comp:MetricCard:summary").assertCountEquals(3)
}
```

Add imports if needed:

```kotlin
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
```

- [ ] **Step 2: Run sample smoke test to verify it fails**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.beyondwin.fixthis.sample.SampleAppSmokeTest
```

Expected: FAIL until tags are added. If no Android device or emulator is connected, record the environment limitation and run compile checks in Step 5.

- [ ] **Step 3: Add stable literal tags**

In `StudioHeader.kt`, add:

```kotlin
import androidx.compose.ui.platform.testTag
```

Apply:

```kotlin
modifier = modifier.testTag("comp:StudioHeader:root")
```

In `HomeScreen.kt`, add:

```kotlin
import androidx.compose.ui.platform.testTag
```

Apply to the primary action:

```kotlin
Modifier.testTag("comp:HomePrimaryAction:primary")
```

Apply to the three summary metric cards:

```kotlin
Modifier.testTag("comp:MetricCard:summary")
```

- [ ] **Step 4: Run sample compile tests**

Run:

```bash
./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin
```

Expected: PASS.

- [ ] **Step 5: Run instrumented test when a device is available**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.beyondwin.fixthis.sample.SampleAppSmokeTest
```

Expected: PASS when an Android device or emulator is connected. If unavailable, record "No connected Android device or emulator" in the final verification notes.

- [ ] **Step 6: Commit**

```bash
git add sample/src/main/java/io/beyondwin/fixthis/sample/components/StudioHeader.kt \
  sample/src/main/java/io/beyondwin/fixthis/sample/screens/HomeScreen.kt \
  sample/src/androidTest/java/io/beyondwin/fixthis/sample/SampleAppSmokeTest.kt
git commit -m "test: add stable target evidence sample tags"
```

## Task 11: Update Public Docs

**Files:**

- Modify: `docs/output-schema.md`
- Modify: `docs/mcp.md`
- Modify: `docs/design-target-evidence-handoff-review.md`

- [ ] **Step 1: Update output schema**

In `docs/output-schema.md`, add a `targetEvidence` section under annotation output:

```markdown
### `targetEvidence`

`targetEvidence` is optional additive evidence for agent handoff. It may be absent when the sidekick or capture path cannot compute structured evidence.

- `identityHint`: optional target identity derived from strict `comp:<ComposableName>:<variant>` test tags or stable semantics labels.
- `occurrence`: optional ordinal/count for the selected target, based on captured merged semantics nodes.
- `sourceInterpretation`: optional summary of the top source candidate and confidence caution.
- `evidenceQuality`: `BASIC` or `STRUCTURED`.
- `screenshotKinds`: screenshot artifact kinds available for the annotation, such as `full` and `crop`.
- `warnings`: human-readable caveats. Agents must treat these as confidence constraints.
```

- [ ] **Step 2: Update MCP docs**

In `docs/mcp.md`, update `fixthis_read_feedback`:

```markdown
`fixthis_read_feedback` accepts optional `detailMode`: `compact`, `precise`, or `full`.

`detailMode` affects only the Markdown content. The JSON content remains complete and includes all persisted session evidence, including optional `targetEvidence`.
```

- [ ] **Step 3: Correct the handoff review doc**

In `docs/design-target-evidence-handoff-review.md`, apply these corrections:

```markdown
- `ui-tooling-data` must not be required for Stable Target Evidence v1. If added later, declare it as an optional experimental dependency and follow the Compose BOM instead of pinning a separate default version.
- Sample golden-path tags must be introduced in the `sample/` app. Existing `StudioTestTags` live in `fixthis-compose-overlay` and do not prove sample handoff behavior.
- Semantics subtree rendering is out of scope for Stable Target Evidence v1 because current `FixThisNode.path` is not a stable UID parent chain.
- `BridgeProtocol.VERSION` should not be bumped for nullable additive evidence. If a future protocol break is needed, update both sidekick `BridgeProtocol.VERSION` and CLI `BridgeProtocolVersion`.
```

- [ ] **Step 4: Run doc whitespace check**

Run:

```bash
git diff --check -- docs/output-schema.md docs/mcp.md docs/design-target-evidence-handoff-review.md
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add docs/output-schema.md docs/mcp.md docs/design-target-evidence-handoff-review.md
git commit -m "docs: document stable target evidence handoff"
```

## Task 12: Full Verification

**Files:**

- No new files.
- Verify all changed modules.

- [ ] **Step 1: Run focused JVM tests**

Run:

```bash
./gradlew :fixthis-compose-core:test :fixthis-compose-sidekick:test :fixthis-mcp:test
```

Expected: PASS.

- [ ] **Step 2: Run sample compile**

Run:

```bash
./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin
```

Expected: PASS.

- [ ] **Step 3: Run connected tests when possible**

Run:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Expected: PASS with a connected Android device or emulator. If unavailable, record the exact Gradle/ADB message in final notes.

- [ ] **Step 4: Verify no protocol version bump happened**

Run:

```bash
rg -n 'BridgeProtocolVersion = "1.0"|const val VERSION: String = "1.0"' fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocol.kt
```

Expected: output includes both version constants still set to `1.0`.

- [ ] **Step 5: Verify no Compose tooling dependency was added to the stable path**

Run:

```bash
rg -n 'ui-tooling-data|LocalInspectionTables|parseSourceInformation|mapTree' fixthis-compose-core fixthis-compose-sidekick sample gradle/libs.versions.toml
```

Expected: no output for stable implementation files. Documentation may mention these names only as out-of-scope or experimental.

- [ ] **Step 6: Route verification failures back to the owning task**

If verification fails, do not create a catch-all commit. Return to the task that introduced the failing surface, apply the focused fix there, rerun that task's verification command, and use that task's commit step.

## Self-Review

- Spec coverage: The plan covers stable models, identity hints, occurrence, source interpretation, capture integration, MCP detail mode, capabilities, sample tags, and docs. Compose tooling identity is explicitly excluded.
- Compatibility: New serialized fields are nullable/defaulted. Existing formatter functions are preserved through overloads. Bridge protocol stays at `1.0`.
- Open-source posture: Core implementation avoids `ui-tooling-data`, `LocalInspectionTables`, `parseSourceInformation`, and exact Compose BOM gating.
- Remaining risk: Existing tests may use helper names that differ from snippets above. When applying snippets, keep each test in the style of its local file while preserving the asserted behavior.
