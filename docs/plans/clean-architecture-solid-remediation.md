# Clean Architecture and SOLID Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans` to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor FixThis internals so domain policy, adapter contracts,
session persistence, bridge transport, and console presentation have clear
Clean Architecture and SOLID boundaries without changing public behavior.

**Architecture:** Keep the existing module dependency direction. Strengthen
boundaries inside modules by introducing domain-native types, application
use cases, capability-specific ports, handler registries, and focused storage
collaborators. Public MCP tools, bridge methods, persisted JSON fields, and
console runtime behavior remain compatible.

**Tech Stack:** Kotlin/JVM 21, Android library module, kotlinx.serialization,
JUnit/kotlin.test, Gradle, Node 20 ES modules, existing console test harness.

Spec: [`../specs/clean-architecture-solid-remediation.md`](../specs/clean-architecture-solid-remediation.md)

---

## File Map

### `:fixthis-compose-core`

- Create:
  - `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/ui/DomainRect.kt`
  - `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/ui/SemanticsNodeSnapshot.kt`
  - `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/evidence/AnnotationEvidence.kt`
  - `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/evidence/SourceHint.kt`
  - `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/evidence/TargetReliabilityAssessment.kt`
  - `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/DomainContractMappers.kt`
  - `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/usecase/feedback/*.kt`
- Modify:
  - `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/annotation/Annotation.kt`
  - `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/snapshot/Snapshot.kt`
  - existing selection/source/target callers only where they cross the new
    mapper boundary.
- Test:
  - `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/model/DomainContractMappersTest.kt`
  - `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/usecase/feedback/*Test.kt`

### `:fixthis-mcp`

- Create:
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionStateCache.kt`
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionMutationService.kt`
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionWriteAheadLog.kt`
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionRecoveryService.kt`
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionArtifactJanitor.kt`
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/handlers/*.kt`
- Modify:
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt`
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionDomainMappers.kt`
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisToolDispatcher.kt`
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt`
  - architecture tests under `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/`
- Test:
  - focused tests for new session collaborators
  - handler tests for representative MCP tools

### `:fixthis-compose-sidekick`

- Create:
  - `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeRequestRouter.kt`
  - `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeMethodHandler.kt`
  - `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/handlers/*.kt`
- Modify:
  - `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`
- Test:
  - existing bridge tests plus router tests for unknown method and token flow.

### `:fixthis-cli`

- Create:
  - `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/bridge/BridgeSessionReader.kt`
  - `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/bridge/BridgeProtocolClient.kt`
  - `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/bridge/BridgeTransport.kt`
  - `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/bridge/AdbForwardingBridgeTransport.kt`
  - `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/bridge/ScreenshotArtifactDownloader.kt`
  - `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/bridge/DeviceSelectionState.kt`
- Modify:
  - `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt`
- Test:
  - split existing `BridgeClientTest` scenarios into collaborator tests.

### Feedback Console

- Create:
  - `fixthis-mcp/src/main/console/presentation/annotationListView.js`
  - `fixthis-mcp/src/main/console/presentation/selectionOverlayView.js`
  - `fixthis-mcp/src/main/console/presentation/annotationDetailView.js`
  - `fixthis-mcp/src/main/console/presentation/promptReadinessView.js`
  - `fixthis-mcp/src/main/console/viewmodel/annotationPresentation.js`
  - `fixthis-mcp/src/main/console/viewmodel/reliabilityPresentation.js`
- Modify:
  - `fixthis-mcp/src/main/console/rendering.js`
  - `fixthis-mcp/src/main/console/annotations.js`
  - console build/test scripts only if they need explicit module entries.
- Test:
  - existing Node console tests and browser smoke tests.

## Tasks

### Task 0: Characterization and Architecture Guardrails

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture/DispatchArchitectureTest.kt`

- [ ] **Step 1: Add failing domain/contract boundary test**

Add this test to `ModuleBoundaryTest`:

```kotlin
@Test
fun composeCoreDomainDoesNotImportContractModels() {
    val forbidden = Regex("""^import io\.beyondwin\.fixthis\.compose\.core\.model\.""")
    val offenders = kotlinFiles("fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain")
        .flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (forbidden.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: $line" else null
            }
        }

    assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
}
```

- [ ] **Step 2: Verify the new test fails before CAS-1**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*ModuleBoundaryTest.composeCoreDomainDoesNotImportContractModels'
```

Expected: FAIL with imports from `Annotation.kt` and `Snapshot.kt`.

- [ ] **Step 3: Add post-remediation hotspot budgets**

In `ArchitectureHotspotBudgetTest`, add or update the target budgets:

```kotlin
"fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt" to 250,
"fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt" to 180,
"fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt" to 260,
"fixthis-mcp/src/main/console/rendering.js" to 200,
```

Keep these budgets disabled behind a helper until each phase lands:

```kotlin
private fun remediationBudgetEnabled(path: String): Boolean =
    File(root, path).isFile && System.getenv("FIXTHIS_STRICT_ARCH_BUDGETS") == "true"
```

Use strict budgets in CI only after the matching task lands.

- [ ] **Step 4: Add dispatch architecture tests**

Create `DispatchArchitectureTest.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DispatchArchitectureTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }

    @Test
    fun mcpToolHandlersDoNotUseCentralToolSwitches() {
        val offenders = kotlinFiles("fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/handlers")
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (Regex("""when\s*\(\s*name\s*\)|"fixthis_[a-z_]+\"\s*->""").containsMatchIn(line)) {
                        "${file.relativeTo(root)}:${index + 1}: $line"
                    } else {
                        null
                    }
                }
            }
        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }

    @Test
    fun sidekickBridgeHandlersDoNotUseCentralMethodSwitches() {
        val offenders = kotlinFiles(
            "fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/handlers",
        ).flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (Regex("""when\s*\(\s*method\s*\)|"(heartbeat|status|inspectCurrentScreen)"\s*->""").containsMatchIn(line)) {
                    "${file.relativeTo(root)}:${index + 1}: $line"
                } else {
                    null
                }
            }
        }
        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }

    private fun kotlinFiles(path: String): List<File> {
        val dir = File(root, path)
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
```

- [ ] **Step 5: Run baseline architecture tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*Architecture*'
```

Expected: all existing tests pass except the intentionally failing
`composeCoreDomainDoesNotImportContractModels` until Task 1 is complete.

- [ ] **Step 6: Commit guardrails**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture
git commit -m "test: add architecture guardrails for clean layering"
```

### Task 1: Split Domain Models from Contract Models

**Files:**
- Create: core domain UI/evidence files listed in File Map
- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/DomainContractMappers.kt`
- Modify: `Annotation.kt`, `Snapshot.kt`, `SessionDomainMappers.kt`
- Test: `DomainContractMappersTest.kt`, existing formatter and mapper tests

- [ ] **Step 1: Add domain-native UI values**

Create `DomainRect.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.domain.ui

data class DomainRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}
```

Create `SemanticsNodeSnapshot.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.domain.ui

enum class SemanticsTreeKind {
    MERGED,
    UNMERGED,
}

data class SemanticsNodeSnapshot(
    val uid: String,
    val composeNodeId: Int,
    val rootIndex: Int,
    val treeKind: SemanticsTreeKind,
    val boundsInWindow: DomainRect,
    val text: List<String> = emptyList(),
    val editableText: String? = null,
    val contentDescription: List<String> = emptyList(),
    val role: String? = null,
    val testTag: String? = null,
    val stateDescription: String? = null,
    val selected: Boolean? = null,
    val enabled: Boolean = true,
    val actions: List<String> = emptyList(),
    val isPassword: Boolean = false,
    val isSensitive: Boolean = false,
    val path: List<String> = emptyList(),
    val rawProperties: Map<String, String> = emptyMap(),
) {
    fun hasMeaningfulSemantic(): Boolean = text.isNotEmpty() ||
        editableText != null ||
        contentDescription.isNotEmpty() ||
        role != null ||
        testTag != null ||
        actions.isNotEmpty() ||
        stateDescription != null
}
```

- [ ] **Step 2: Add domain evidence values**

Create `SourceHint.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.domain.evidence

enum class SourceHintConfidence {
    HIGH,
    MEDIUM,
    LOW,
    NONE,
}

data class SourceHint(
    val file: String,
    val line: Int? = null,
    val score: Double,
    val matchedTerms: List<String> = emptyList(),
    val matchReasons: List<String> = emptyList(),
    val confidence: SourceHintConfidence,
    val ranking: Int? = null,
    val scoreMargin: Double? = null,
    val stale: Boolean? = null,
    val staleReason: String? = null,
)
```

Create `AnnotationEvidence.kt` and
`TargetReliabilityAssessment.kt` with domain names mirroring the existing
contract values:

```kotlin
package io.beyondwin.fixthis.compose.core.domain.evidence

data class AnnotationEvidence(
    val identity: IdentityEvidence? = null,
    val occurrence: OccurrenceEvidence? = null,
    val source: SourceEvidence? = null,
    val quality: EvidenceQuality = EvidenceQuality.BASIC,
    val screenshotKinds: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class IdentityEvidence(
    val composableNameHint: String? = null,
    val variantHint: String? = null,
    val stableLabel: String? = null,
    val source: IdentityEvidenceSource = IdentityEvidenceSource.NONE,
    val confidence: IdentityEvidenceConfidence = IdentityEvidenceConfidence.LOW,
)

enum class IdentityEvidenceSource { TEST_TAG_CONVENTION, SEMANTICS, NONE }
enum class IdentityEvidenceConfidence { HIGH, MEDIUM, LOW }
enum class EvidenceQuality { BASIC, STRUCTURED }

data class OccurrenceEvidence(
    val basis: String = "captured_merged_semantics_nodes",
    val signatureType: OccurrenceSignatureType,
    val signatureValue: String,
    val count: Int,
    val selectedOrdinal: Int,
)

enum class OccurrenceSignatureType {
    IDENTITY_HINT,
    TEST_TAG,
    ROLE_PLUS_TEXT,
    ROLE_PLUS_CONTENT_DESCRIPTION,
}

data class SourceEvidence(
    val topCandidate: SourceHintSummary? = null,
    val reasonSummary: List<String> = emptyList(),
    val caution: String? = null,
)

data class SourceHintSummary(
    val file: String,
    val line: Int? = null,
    val confidence: SourceHintConfidence,
)
```

```kotlin
package io.beyondwin.fixthis.compose.core.domain.evidence

data class TargetReliabilityAssessment(
    val confidence: TargetConfidence = TargetConfidence.UNKNOWN,
    val reasons: List<TargetReliabilityReason> = emptyList(),
    val warnings: List<TargetReliabilityWarning> = emptyList(),
)

enum class TargetConfidence { HIGH, MEDIUM, LOW, UNKNOWN }

enum class TargetReliabilityReason {
    STRICT_COMPOSABLE_IDENTITY,
    MEANINGFUL_COMPOSE_NODE,
    STRONG_SOURCE_CANDIDATE,
    MEDIUM_SOURCE_CANDIDATE,
    VISUAL_AREA_SELECTION,
    LEGACY_OR_MISSING_EVIDENCE,
    REDACTED_TEXT_REDUCED_EVIDENCE,
}

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
```

- [ ] **Step 3: Update domain entities**

In `Annotation.kt`, replace contract imports with domain imports:

```kotlin
import io.beyondwin.fixthis.compose.core.domain.evidence.AnnotationEvidence
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHint
import io.beyondwin.fixthis.compose.core.domain.evidence.TargetReliabilityAssessment
import io.beyondwin.fixthis.compose.core.domain.ui.DomainRect
import io.beyondwin.fixthis.compose.core.domain.ui.SemanticsNodeSnapshot
```

Change the relevant properties:

```kotlin
val target: AnnotationTarget,
val selectedNode: SemanticsNodeSnapshot? = null,
val nearbyNodes: List<SemanticsNodeSnapshot> = emptyList(),
val sourceCandidates: List<SourceHint> = emptyList(),
val targetEvidence: AnnotationEvidence? = null,
val targetReliability: TargetReliabilityAssessment? = null,
```

Change `AnnotationTarget` bounds to `DomainRect`.

In `Snapshot.kt`, replace `FixThisNode`, `FixThisRect`, and `FixThisError`
with `SemanticsNodeSnapshot`, `DomainRect`, and a new `DomainError`.

- [ ] **Step 4: Add contract mappers**

Create `DomainContractMappers.kt` with total, explicit enum mapping. Example:

```kotlin
package io.beyondwin.fixthis.compose.core.model

import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHint
import io.beyondwin.fixthis.compose.core.domain.evidence.SourceHintConfidence
import io.beyondwin.fixthis.compose.core.domain.ui.DomainRect
import io.beyondwin.fixthis.compose.core.domain.ui.SemanticsNodeSnapshot
import io.beyondwin.fixthis.compose.core.domain.ui.SemanticsTreeKind

fun DomainRect.toFixThisRect(): FixThisRect = FixThisRect(left, top, right, bottom)

fun FixThisRect.toDomainRect(): DomainRect = DomainRect(left, top, right, bottom)

fun SemanticsNodeSnapshot.toFixThisNode(): FixThisNode = FixThisNode(
    uid = uid,
    composeNodeId = composeNodeId,
    rootIndex = rootIndex,
    treeKind = when (treeKind) {
        SemanticsTreeKind.MERGED -> TreeKind.MERGED
        SemanticsTreeKind.UNMERGED -> TreeKind.UNMERGED
    },
    boundsInWindow = boundsInWindow.toFixThisRect(),
    text = text,
    editableText = editableText,
    contentDescription = contentDescription,
    role = role,
    testTag = testTag,
    stateDescription = stateDescription,
    selected = selected,
    enabled = enabled,
    actions = actions,
    isPassword = isPassword,
    isSensitive = isSensitive,
    path = path,
    rawProperties = rawProperties,
)

fun FixThisNode.toDomainSemanticsNode(): SemanticsNodeSnapshot = SemanticsNodeSnapshot(
    uid = uid,
    composeNodeId = composeNodeId,
    rootIndex = rootIndex,
    treeKind = when (treeKind) {
        TreeKind.MERGED -> SemanticsTreeKind.MERGED
        TreeKind.UNMERGED -> SemanticsTreeKind.UNMERGED
    },
    boundsInWindow = boundsInWindow.toDomainRect(),
    text = text,
    editableText = editableText,
    contentDescription = contentDescription,
    role = role,
    testTag = testTag,
    stateDescription = stateDescription,
    selected = selected,
    enabled = enabled,
    actions = actions,
    isPassword = isPassword,
    isSensitive = isSensitive,
    path = path,
    rawProperties = rawProperties,
)

fun SourceHint.toSourceCandidate(): SourceCandidate = SourceCandidate(
    file = file,
    line = line,
    score = score,
    matchedTerms = matchedTerms,
    matchReasons = matchReasons,
    confidence = when (confidence) {
        SourceHintConfidence.HIGH -> SelectionConfidence.HIGH
        SourceHintConfidence.MEDIUM -> SelectionConfidence.MEDIUM
        SourceHintConfidence.LOW -> SelectionConfidence.LOW
        SourceHintConfidence.NONE -> SelectionConfidence.NONE
    },
    ranking = ranking,
    scoreMargin = scoreMargin,
    stale = stale,
    staleReason = staleReason,
)
```

Add the reverse mapper and the evidence/reliability mappers in the same file
or split into `TargetEvidenceMappers.kt` if the file exceeds 220 lines.

- [ ] **Step 5: Update MCP session domain mappers**

In `SessionDomainMappers.kt`, map DTO contract values into domain-native
values using the new mapper functions. Keep DTO fields unchanged.

- [ ] **Step 6: Run domain and MCP tests**

Run:

```bash
./gradlew :fixthis-compose-core:test :fixthis-mcp:test --tests '*Domain*' --tests '*Mapper*'
```

Expected: PASS.

- [ ] **Step 7: Run architecture boundary test**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*ModuleBoundaryTest.composeCoreDomainDoesNotImportContractModels'
```

Expected: PASS.

- [ ] **Step 8: Commit domain split**

```bash
git add fixthis-compose-core/src/main/kotlin fixthis-compose-core/src/test/kotlin fixthis-mcp/src/main/kotlin fixthis-mcp/src/test/kotlin
git commit -m "refactor: separate domain models from contract models"
```

### Task 2: Promote Session and Annotation Policies into Core Use Cases

**Files:**
- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/usecase/feedback/*.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationWorkflow.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt`
- Test: core use-case tests plus existing MCP workflow tests

- [ ] **Step 1: Add use-case input/result models**

Create `FeedbackUseCaseModels.kt`:

```kotlin
package io.beyondwin.fixthis.compose.core.usecase.feedback

import io.beyondwin.fixthis.compose.core.domain.annotation.Annotation
import io.beyondwin.fixthis.compose.core.domain.annotation.AnnotationStatus
import io.beyondwin.fixthis.compose.core.domain.common.AnnotationId
import io.beyondwin.fixthis.compose.core.domain.common.SessionId
import io.beyondwin.fixthis.compose.core.domain.common.SnapshotId
import io.beyondwin.fixthis.compose.core.domain.snapshot.Snapshot

data class SaveCapturedSnapshotCommand(
    val sessionId: SessionId,
    val snapshot: Snapshot,
)

data class CreateFeedbackAnnotationCommand(
    val sessionId: SessionId,
    val snapshotId: SnapshotId,
    val draft: Annotation,
)

data class SavePreviewFeedbackCommand(
    val sessionId: SessionId,
    val snapshot: Snapshot,
    val drafts: List<Annotation>,
)

data class CreateHandoffBatchCommand(
    val sessionId: SessionId,
    val annotationIds: List<AnnotationId>,
    val markdownSnapshot: String,
)

data class ResolveAnnotationCommand(
    val sessionId: SessionId,
    val annotationId: AnnotationId,
    val status: AnnotationStatus,
    val summary: String?,
)
```

- [ ] **Step 2: Implement `SavePreviewFeedbackUseCase`**

Create a use case that validates non-empty drafts, ensures every draft points
to the saved snapshot, assigns sequence numbers through a repository method,
and returns the updated session.

Required repository port additions:

```kotlin
interface SessionRepository {
    suspend fun find(id: SessionId): Session?
    suspend fun save(session: Session): Session
}
```

Use immutable `Session.copy(...)` updates. Preserve current behavior:
blank comments are rejected; handoff paths persist only written draft items and
leave pin-only recovery handling to the console boundary.

- [ ] **Step 3: Implement handoff and resolve use cases**

Create `CreateHandoffBatchUseCase`:

```kotlin
class CreateHandoffBatchUseCase(
    private val sessions: SessionRepository,
    private val clock: () -> Long,
    private val idGenerator: () -> String,
) {
    suspend operator fun invoke(command: CreateHandoffBatchCommand): Session {
        require(command.annotationIds.isNotEmpty()) { "annotationIds must not be empty" }
        val session = sessions.find(command.sessionId)
            ?: throw IllegalArgumentException("Unknown session: ${command.sessionId.value}")
        val targetIds = command.annotationIds.map { it.value }.toSet()
        val annotations = session.annotations.filter { it.id.value in targetIds }
        require(annotations.isNotEmpty()) { "No matching draft annotations to hand off" }
        val now = clock()
        val batchId = idGenerator()
        val batch = SessionHandoffBatch(
            id = batchId,
            sequenceNumber = session.handoffBatches.size + 1,
            createdAtEpochMillis = now,
            annotationIds = annotations.map { it.id.value },
            markdownSnapshot = command.markdownSnapshot,
        )
        val updated = session.copy(
            annotations = session.annotations.map { annotation ->
                if (annotation.id.value in targetIds) {
                    annotation.copy(
                        delivery = AnnotationDelivery.SENT,
                        handoffBatchId = batchId,
                        sentAtEpochMillis = now,
                        updatedAtEpochMillis = now,
                    )
                } else {
                    annotation
                }
            },
            handoffBatches = session.handoffBatches + batch,
            status = SessionStatus.READY_FOR_AGENT,
            updatedAtEpochMillis = now,
        )
        return sessions.save(updated)
    }
}
```

Use the same pattern for `ResolveAnnotationUseCase` and
`ClaimAnnotationUseCase`.

- [ ] **Step 4: Add focused core tests**

Create tests that prove:

- saving preview feedback rejects empty item lists
- handoff marks only selected draft annotations as sent
- resolving supports `RESOLVED`, `WONT_FIX`, and `NEEDS_CLARIFICATION`
- claim changes status to `IN_PROGRESS` and stores the agent note
- sequence numbers are stable and monotonic

Run:

```bash
./gradlew :fixthis-compose-core:test --tests '*feedback*'
```

Expected: PASS.

- [ ] **Step 5: Wire MCP services to use cases**

In `AnnotationWorkflow` and `FeedbackDraftService`, keep DTO-specific preview
cache, fingerprint, screenshot promotion, and route concerns in MCP. Convert
the final save/handoff/resolve/claim decisions to use-case calls through
`McpSessionRepository`, `McpSnapshotRepository`, and
`McpAnnotationRepository`.

- [ ] **Step 6: Run MCP workflow tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*AnnotationWorkflowTest' --tests '*FeedbackDraftServiceTest' --tests '*FeedbackSessionServiceTest'
```

Expected: PASS.

- [ ] **Step 7: Commit use-case promotion**

```bash
git add fixthis-compose-core/src/main/kotlin fixthis-compose-core/src/test/kotlin fixthis-mcp/src/main/kotlin fixthis-mcp/src/test/kotlin
git commit -m "refactor: move feedback policies into core use cases"
```

### Task 3: Split `FeedbackSessionStore`

**Files:**
- Create: `SessionStateCache.kt`, `SessionMutationService.kt`,
  `SessionWriteAheadLog.kt`, `SessionRecoveryService.kt`,
  `SessionArtifactJanitor.kt`
- Modify: `FeedbackSessionStore.kt`
- Test: new collaborator tests and existing store/event-log tests

- [ ] **Step 1: Extract `SessionStateCache`**

Create:

```kotlin
package io.beyondwin.fixthis.mcp.session

internal class SessionStateCache {
    private val sessions = linkedMapOf<String, SessionDto>()
    private var currentSessionId: String? = null

    fun put(session: SessionDto) {
        sessions[session.sessionId] = session
        if (session.status != SessionStatusDto.CLOSED) currentSessionId = session.sessionId
        if (session.status == SessionStatusDto.CLOSED && currentSessionId == session.sessionId) currentSessionId = null
    }

    fun get(sessionId: String): SessionDto =
        sessions[sessionId] ?: throw FeedbackSessionException("Unknown feedback session: $sessionId")

    fun current(): SessionDto? = currentSessionId?.let(::get)

    fun all(): List<SessionDto> = sessions.values.toList()

    fun removeCurrentIf(sessionId: String) {
        if (currentSessionId == sessionId) currentSessionId = null
    }
}
```

- [ ] **Step 2: Extract pure mutations**

Create `SessionMutationService` with methods:

```kotlin
internal class SessionMutationService(
    private val clock: () -> Long,
    private val idGenerator: () -> String,
) {
    fun addScreen(session: SessionDto, screen: SnapshotDto): Pair<SessionDto, SnapshotDto>
    fun addScreenWithItems(session: SessionDto, screen: SnapshotDto, items: List<AnnotationDto>): SessionDto
    fun deleteScreen(session: SessionDto, screenId: String): SessionDto
    fun addItem(session: SessionDto, item: AnnotationDto): Pair<SessionDto, AnnotationDto>
    fun updateItemStatus(session: SessionDto, itemId: String, status: AnnotationStatusDto, summary: String?): Pair<SessionDto, AnnotationDto>
}
```

Move validation and `copy(...)` construction here. Do not perform event-log,
filesystem, or `sessions[...] = updated` operations inside this class.

- [ ] **Step 3: Extract write-ahead event wrapper**

Create `SessionWriteAheadLog`:

```kotlin
internal class SessionWriteAheadLog(
    private val journal: SessionEventJournal,
    private val eventLogJson: Json,
) {
    fun append(sessionId: String, type: String, payload: JsonObject) {
        journal.append(sessionId = sessionId, type = type, payload = payload)
    }
}
```

Keep event payload creation near store methods until all behavior is stable.
After the split, payload factories may move into `SessionEventPayloads.kt`.

- [ ] **Step 4: Extract artifact deletion**

Create:

```kotlin
internal class SessionArtifactJanitor(
    private val persistence: FeedbackSessionPersistence?,
) {
    fun deleteScreenArtifacts(sessionId: String, screenId: String) {
        persistence?.artifactPaths()
            ?.screenArtifactDirectory(sessionId, screenId)
            ?.deleteRecursively()
    }
}
```

`FeedbackSessionStore.deleteScreen` should call this after the state commit.
Reducers and replay code must not delete files.

- [ ] **Step 5: Update `FeedbackSessionStore` facade**

The facade should:

1. lock around cache access,
2. call `SessionMutationService`,
3. append write-ahead event,
4. persist and cache the updated session,
5. call artifact janitor only for file cleanup cases.

Target shape:

```kotlin
fun addItem(sessionId: String, item: AnnotationDto): AnnotationDto = synchronized(lock) {
    val session = cache.get(sessionId)
    val (updated, created) = mutations.addItem(session, item)
    writeAhead.append(sessionId, "addItem", payloads.addItem(sessionId, created))
    commitSessionMutation(session, updated)
    created
}
```

- [ ] **Step 6: Add collaborator tests**

Tests:

- `SessionStateCacheTest` for current-session selection
- `SessionMutationServiceTest` for item sequencing, unknown screen rejection,
  and delete-screen batch cleanup
- `SessionArtifactJanitorTest` for screen directory deletion

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*SessionStateCacheTest' --tests '*SessionMutationServiceTest' --tests '*SessionArtifactJanitorTest'
```

Expected: PASS.

- [ ] **Step 7: Run full session tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackSessionStore*' --tests '*EventLog*' --tests '*SigkillReplay*'
```

Expected: PASS.

- [ ] **Step 8: Enable strict store budget**

Set `FeedbackSessionStore.kt` budget to 250 lines without the environment
guard once the file is below target.

- [ ] **Step 9: Commit store split**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture
git commit -m "refactor: split feedback session store responsibilities"
```

### Task 4: Replace MCP Tool Dispatcher with Handlers

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/handlers/McpToolHandler.kt`
- Create: one handler file per MCP tool
- Modify: `FixThisToolDispatcher.kt`, `FixThisTools.kt`, `McpToolRegistry.kt` if metadata wiring needs handler names
- Test: MCP tool tests and new handler tests

- [ ] **Step 1: Add handler interface and context**

```kotlin
package io.beyondwin.fixthis.mcp.tools.handlers

import kotlinx.serialization.json.JsonObject

internal interface McpToolHandler {
    val name: String
    suspend fun handle(arguments: JsonObject): JsonObject
}
```

Create `McpToolHandlerContext`:

```kotlin
internal data class McpToolHandlerContext(
    val ports: FixThisToolBridgePorts,
    val services: FixThisToolDispatcherServices,
)
```

- [ ] **Step 2: Move one low-risk tool first**

Create `ListFeedbackSessionsHandler.kt`:

```kotlin
internal class ListFeedbackSessionsHandler(
    private val services: FixThisToolDispatcherServices,
) : McpToolHandler {
    override val name: String = "fixthis_list_feedback_sessions"

    override suspend fun handle(arguments: JsonObject): JsonObject {
        val sessions = services.feedbackService.listSessions(
            packageNameOverride = arguments.stringParam("packageName"),
            includeClosed = arguments.booleanParam("includeClosed") == true,
        )
        val payload = McpProtocol.json.encodeToJsonElement(FeedbackSessionList.serializer(), sessions).jsonObject
        return jsonToolResult(
            buildJsonObject {
                put("projectRoot", services.projectRoot.absolutePath)
                put("sessions", payload.getValue("sessions"))
                put("skippedSessions", payload.getValue("skippedSessions"))
            },
        )
    }
}
```

Move shared `jsonToolResult`, `stringParam`, and `booleanParam` helpers to
`ToolJson.kt` so handlers can use them.

- [ ] **Step 3: Update dispatcher to registry lookup**

`FixThisToolDispatcher` should become:

```kotlin
internal class FixThisToolDispatcher(
    handlers: List<McpToolHandler>,
) {
    private val handlersByName = handlers.associateBy { it.name }

    suspend fun call(name: String, arguments: JsonObject): JsonObject {
        val handler = handlersByName[name] ?: throw FixThisToolException("Unknown FixThis tool: $name")
        return bridgeToolResult { handler.handle(arguments) }
    }
}
```

Keep the existing `bridgeToolResult` behavior.

- [ ] **Step 4: Move remaining tools one by one**

Create handlers for:

- `fixthis_status`
- `fixthis_get_current_screen`
- `fixthis_verify_ui_change`
- `fixthis_open_feedback_console`
- `fixthis_capture_screen`
- `fixthis_navigate_app`
- `fixthis_list_feedback`
- `fixthis_read_feedback`
- `fixthis_resolve_feedback`
- `fixthis_claim_feedback`

After each handler move, run its focused tests:

```bash
./gradlew :fixthis-mcp:test --tests '*FixThisTools*'
```

- [ ] **Step 5: Run dispatcher architecture test**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*DispatchArchitectureTest*'
```

Expected: PASS.

- [ ] **Step 6: Commit MCP handlers**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp
git commit -m "refactor: route MCP tools through handlers"
```

### Task 5: Replace Sidekick Bridge Switch with Method Handlers

**Files:**
- Create: `BridgeMethodHandler.kt`, `BridgeRequestRouter.kt`,
  `bridge/handlers/*.kt`
- Modify: `BridgeServer.kt`
- Test: sidekick bridge tests

- [ ] **Step 1: Add method handler interface**

```kotlin
package io.beyondwin.fixthis.compose.sidekick.bridge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal interface BridgeMethodHandler {
    val method: String
    suspend fun handle(params: JsonObject): JsonElement
}
```

- [ ] **Step 2: Add router**

```kotlin
internal class BridgeRequestRouter(
    handlers: List<BridgeMethodHandler>,
) {
    private val handlersByMethod = handlers.associateBy { it.method }

    suspend fun route(method: String, params: JsonObject): JsonElement? =
        handlersByMethod[method]?.handle(params)
}
```

- [ ] **Step 3: Add handlers**

Examples:

```kotlin
internal class StatusBridgeHandler(
    private val environment: BridgeEnvironment,
    private val socketNameProvider: () -> String,
) : BridgeMethodHandler {
    override val method: String = "status"

    override suspend fun handle(params: JsonObject): JsonElement =
        BridgeProtocol.json.encodeToJsonElement(
            environment.status().copy(socketName = socketNameProvider()),
        )
}
```

```kotlin
internal class VerifyUiChangeBridgeHandler(
    private val environment: BridgeEnvironment,
) : BridgeMethodHandler {
    override val method: String = "verifyUiChange"

    override suspend fun handle(params: JsonObject): JsonElement {
        val expectedText = params.stringParam("expectedText")?.takeIf { it.isNotBlank() }
            ?: return BridgeProtocol.json.encodeToJsonElement(
                BridgeUiVerificationResult(
                    verified = false,
                    message = "No expectedText parameter was provided",
                ),
            )
        val inspection = environment.inspectCurrentScreen()
        val matched = inspection.roots
            .flatMap { it.mergedNodes + it.unmergedNodes }
            .flatMap { node -> node.text + node.contentDescription }
            .firstOrNull { value -> value.contains(expectedText, ignoreCase = true) }
        return BridgeProtocol.json.encodeToJsonElement(
            BridgeUiVerificationResult(
                verified = matched != null,
                expectedText = expectedText,
                matchedText = matched,
                message = if (matched == null) "Expected text was not found on the current screen" else null,
            ),
        )
    }
}
```

- [ ] **Step 4: Reduce `BridgeServer.handleRequest`**

`BridgeServer` should decode, authenticate, route, and wrap success/error:

```kotlin
val result = router.route(request.method, request.params)
    ?: return BridgeProtocol.error(request.id, "UNKNOWN_METHOD", "Unknown bridge method: ${request.method}")
BridgeProtocol.success(request.id, result)
```

- [ ] **Step 5: Run sidekick bridge tests**

```bash
./gradlew :fixthis-compose-sidekick:test --tests '*Bridge*'
```

Expected: PASS.

- [ ] **Step 6: Enable `BridgeServer.kt` budget**

Set strict budget to 180 lines once the switch is removed.

- [ ] **Step 7: Commit bridge handlers**

```bash
git add fixthis-compose-sidekick/src/main/kotlin fixthis-compose-sidekick/src/test/kotlin fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture
git commit -m "refactor: route sidekick bridge methods through handlers"
```

### Task 6: Split CLI `BridgeClient`

**Files:**
- Create: bridge collaborator files listed in File Map
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt`
- Test: split bridge client tests

- [ ] **Step 1: Extract device selection**

Create:

```kotlin
package io.beyondwin.fixthis.cli.bridge

class DeviceSelectionState {
    @Volatile
    private var selectedSerial: String? = null

    fun select(serial: String) {
        require(serial.isNotBlank()) { "Device serial must not be blank" }
        selectedSerial = serial
    }

    fun clear() {
        selectedSerial = null
    }

    fun selected(): String? = selectedSerial
}
```

- [ ] **Step 2: Extract session reader**

Create `BridgeSessionReader` that owns `runAsCat` and protocol validation:

```kotlin
class BridgeSessionReader(
    private val expectedProtocolVersion: String,
) {
    fun read(adb: AdbFacade, packageName: String): SidekickSession = runCatching {
        fixThisJson.decodeFromString(SidekickSession.serializer(), adb.runAsCat(packageName, SessionPath))
    }.getOrElse { error ->
        throw BridgeConnectionException("Unable to read FixThis sidekick session for $packageName: ${error.message}")
    }.also { session ->
        if (session.bridgeProtocolVersion != expectedProtocolVersion) {
            throw BridgeProtocolException(
                "Unsupported FixThis bridge protocol ${session.bridgeProtocolVersion}; expected $expectedProtocolVersion",
            )
        }
    }
}
```

Move `SidekickSession` to this package if needed, keeping serialization names.

- [ ] **Step 3: Extract protocol client**

Create `BridgeProtocolClient` with:

```kotlin
class BridgeProtocolClient(
    private val requestIds: AtomicInteger = AtomicInteger(0),
) {
    fun request(socket: BridgeSocket, session: SidekickSession, method: String, params: JsonObject): JsonObject
}
```

Move frame writing/reading and bridge response decoding here.

- [ ] **Step 4: Extract ADB forwarding transport**

Create `AdbForwardingBridgeTransport` that:

- allocates local port
- calls `adb.forward`
- tries socket-name fallbacks
- tracks cleanup through `ActiveBridgeRequest`
- removes the forward exactly once

Keep existing cancellation behavior.

- [ ] **Step 5: Extract screenshot artifact downloader**

Create `ScreenshotArtifactDownloader` that owns `readScreenshotArtifact`,
destination path sanitization, and `adb.pull`.

- [ ] **Step 6: Rebuild `BridgeClient` as facade**

`BridgeClient` should keep the public methods:

- `devices`
- `selectDevice`
- `disconnectDevice`
- `selectedDeviceSerial`
- `request`
- `resolvePackageName`
- `performNavigation`
- `readSourceIndex`
- `launchApp`
- `captureScreenSnapshot`

Internally it composes the new collaborators.

- [ ] **Step 7: Run CLI bridge tests**

```bash
./gradlew :fixthis-cli:test --tests '*BridgeClientTest' --tests '*Adb*'
```

Expected: PASS.

- [ ] **Step 8: Enable `BridgeClient.kt` budget**

Set strict budget to 260 lines once the facade is below target.

- [ ] **Step 9: Commit CLI split**

```bash
git add fixthis-cli/src/main/kotlin fixthis-cli/src/test/kotlin fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/architecture
git commit -m "refactor: split CLI bridge transport responsibilities"
```

### Task 7: Make Console Dependencies Explicit

**Files:**
- Create: presentation and viewmodel JS files listed in File Map
- Modify: `rendering.js`, `annotations.js`, `main.js`, build scripts if needed
- Test: Node console tests and smoke tests

- [ ] **Step 1: Extract view-model helpers**

Create `viewmodel/reliabilityPresentation.js`:

```javascript
export function reliabilityWarnings(item) {
  return item?.targetReliability?.warnings || [];
}

export function reliabilityConfidence(item) {
  return String(item?.targetReliability?.confidence || 'unknown').toLowerCase();
}

export function reliabilityWarningLabel(warning) {
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
```

Create `viewmodel/annotationPresentation.js` for `annotationSeverity`,
`lifecyclePhase`, `statusLabel`, and `statusClass`.

- [ ] **Step 2: Extract selection overlay view**

Create `presentation/selectionOverlayView.js` with explicit arguments:

```javascript
export function renderOverlayBox({ overlay, image, bounds, labelText, isDragPreview = false, isFocused = false, annotationIndex = null, extraClass = '', color = null, selectHandler }) {
  if (!bounds) return;
  const left = bounds.left * 100 / image.naturalWidth;
  const top = bounds.top * 100 / image.naturalHeight;
  const width = (bounds.right - bounds.left) * 100 / image.naturalWidth;
  const height = (bounds.bottom - bounds.top) * 100 / image.naturalHeight;
  const box = document.createElement('div');
  box.className = 'selection-box' + (isDragPreview ? ' drag-preview' : '') + (isFocused ? ' focused' : '') + (annotationIndex == null ? '' : ' annotation-pin') + (extraClass ? ' ' + extraClass : '');
  box.style.left = left + '%';
  box.style.top = top + '%';
  box.style.width = width + '%';
  box.style.height = height + '%';
  if (color) {
    box.style.setProperty('--selection-color', color);
  }
  if (annotationIndex != null && selectHandler) {
    box.setAttribute('role', 'button');
    box.setAttribute('aria-label', 'Select annotation ' + (annotationIndex + 1));
    box.tabIndex = 0;
    box.addEventListener('click', event => {
      event.stopPropagation();
      selectHandler(annotationIndex);
    });
  }
  overlay.appendChild(box);
  if (!labelText) return;
  const label = document.createElement('div');
  label.className = 'selection-label' + (isFocused ? ' focused' : '');
  label.style.left = left + '%';
  label.style.top = top + '%';
  label.textContent = labelText;
  overlay.appendChild(label);
}
```

Move the remaining overlay rendering into functions that receive all state and
callbacks explicitly.

- [ ] **Step 3: Extract annotation list/detail views**

Move pending list HTML and annotation detail HTML into presentation modules.
The functions should return DOM nodes or strings and receive helpers as
arguments, not read global `state`, `toolMode`, or `draftWorkspace` directly.

- [ ] **Step 4: Replace `@requires` with ES imports**

For every file in `fixthis-mcp/src/main/console`, replace dependency comments
with imports. Example:

```javascript
import { reliabilityWarnings, reliabilityWarningLabel } from './viewmodel/reliabilityPresentation.js';
import { statusLabel, statusClass } from './viewmodel/annotationPresentation.js';
```

Update the bundler entry if needed so `resources/console/app.js` is still
generated from the same public entry point.

- [ ] **Step 5: Run console tests**

```bash
npm run console:test:fast
npm run console:build:test
```

Expected: PASS.

- [ ] **Step 6: Run browser smoke if a local console target is available**

```bash
npm run console:smoke
```

Expected: PASS or documented skip if no emulator/console fixture is running.

- [ ] **Step 7: Enable console hotspot budget**

Once `rendering.js` is below 200 lines or removed, enable its strict budget.

- [ ] **Step 8: Commit console split**

```bash
git add fixthis-mcp/src/main/console fixthis-mcp/src/main/resources/console fixthis-mcp/src/test scripts package.json package-lock.json
git commit -m "refactor: make console presentation dependencies explicit"
```

## Final Verification

- [ ] Run JVM tests:

```bash
./gradlew :fixthis-compose-core:test :fixthis-mcp:test :fixthis-cli:test :fixthis-compose-sidekick:test
```

Expected: PASS.

- [ ] Run console tests:

```bash
npm run console:test:fast
```

Expected: PASS.

- [ ] Run fast local CI:

```bash
./scripts/verify-ci-local.sh --fast
```

Expected: PASS.

- [ ] Run strict architecture budgets:

```bash
FIXTHIS_STRICT_ARCH_BUDGETS=true ./gradlew :fixthis-mcp:test --tests '*Architecture*'
```

Expected: PASS.

- [ ] Run final full verification before PR:

```bash
./scripts/verify-ci-local.sh --full
```

Expected: PASS.

## Completion Criteria

- `compose-core/domain` has no dependency on `compose-core/model`.
- Core use-case tests own the main annotation/session policy coverage.
- `FeedbackSessionStore`, `BridgeServer`, `BridgeClient`, and console
  rendering files are under their strict budgets.
- MCP tool and sidekick bridge dispatch are handler-based.
- Existing MCP tool names, bridge methods, session JSON fields, and console
  user behavior remain compatible.
