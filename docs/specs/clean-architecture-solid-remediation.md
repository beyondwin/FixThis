# Spec — Clean Architecture and SOLID Remediation

Status: Proposed
Scope: `:fixthis-compose-core`, `:fixthis-mcp`, `:fixthis-cli`,
`:fixthis-compose-sidekick`, browser feedback console
Related plan: [`../plans/clean-architecture-solid-remediation.md`](../plans/clean-architecture-solid-remediation.md)

## Background

FixThis already has a reasonable Clean Architecture direction:

- `:fixthis-compose-core` is the inner module.
- `:fixthis-compose-sidekick`, `:fixthis-cli`, and `:fixthis-mcp` depend on
  core rather than the other way around.
- `ModuleBoundaryTest` prevents `compose-core` from importing Android, MCP,
  CLI, Gradle, or sidekick packages.
- Session, annotation, snapshot, source matching, target reliability, and
  formatting logic are testable on the JVM.

The remaining design debt is not a broken module graph. The problem is that
several policy-rich concepts still sit in adapter-facing DTOs or broad
orchestration classes. That makes the dependency direction look clean while
SRP, OCP, ISP, and DIP are uneven inside the modules.

The most important examples are:

- `fixthis-compose-core/.../domain/annotation/Annotation.kt` imports
  `FixThisNode`, `SourceCandidate`, `TargetEvidence`, and
  `TargetReliability` from `compose.core.model`. Those model types are also
  serialized wire/export contracts.
- `fixthis-compose-core/.../model/Models.kt` mixes schema fields, app/activity
  wire metadata, screenshots, semantics nodes, source candidates, and target
  reliability in one package.
- `fixthis-mcp/.../session/FeedbackSessionStore.kt` owns in-memory state,
  current-session selection, event-log write-ahead, replay, persistence, item
  sequencing, handoff state transitions, and artifact deletion.
- `fixthis-mcp/.../tools/FixThisToolDispatcher.kt` and
  `fixthis-compose-sidekick/.../bridge/BridgeServer.kt` use string switch
  dispatch. Adding one tool or bridge method requires editing central
  dispatcher code.
- `fixthis-cli/.../BridgeClient.kt` handles device selection, session-token
  reads, ADB forwarding, socket protocol, protocol validation, and screenshot
  artifact pulls.
- `fixthis-mcp/src/main/console/rendering.js` and `annotations.js` still
  rely on implicit global functions even though reducer/domain files have
  started moving toward explicit state boundaries.

This spec defines a staged remediation. It is intentionally evolutionary:
public protocol fields, persisted JSON field names, MCP tool names, and the
debug-only Android runtime contract must stay compatible.

## Goals

- Keep the existing module dependency direction and strengthen the internal
  boundaries inside each module.
- Separate domain concepts from wire/export DTOs in `:fixthis-compose-core`.
- Move policy-rich annotation/session behavior behind explicit use cases.
- Split large orchestration classes by responsibility without changing public
  behavior.
- Replace central string-switch dispatchers with handler registries that are
  open for extension and closed for central edits.
- Narrow interfaces so consumers depend only on the bridge capabilities they
  need.
- Add architecture tests that prevent regression after the refactor.

## Non-Goals

- No protocol version bump unless a later task discovers a real compatibility
  requirement. All planned changes are internal.
- No persisted MCP JSON field rename. Fields such as `items`, `screens`,
  `itemId`, `screenId`, `targetEvidence`, `targetReliability`, and
  `sourceCandidates` remain compatibility contracts.
- No release build support. FixThis remains debug-build only.
- No replacement of the HTTP console server, ADB transport, or MCP JSON-RPC
  protocol library.
- No migration to a dependency-injection framework. Constructor injection and
  small factories are enough.
- No visual redesign of the feedback console.

## Architectural Principles

### Dependency Rule

Inner policy code may not depend on outer delivery details. The dependency
direction should be:

```text
domain models/policies
  <- application use cases/ports
  <- adapters: MCP DTO, CLI, Android sidekick, HTTP console, filesystem, ADB
```

`compose-core` may still expose serializable contract models while migration
is in progress, but domain classes must not use those contract models directly
after CAS-1.

### Stable Contracts at Boundaries

Existing bridge/MCP/session JSON names are external contracts. Refactoring must
move mapping code around those contracts, not mutate the contracts themselves.

### Small Interfaces

Consumers should depend on capability-specific ports:

- package resolution
- device selection
- app launch
- screen inspection
- source-index read
- navigation
- screenshot artifact download

The existing broad `FixThisBridge` facade may remain as an adapter aggregate,
but new service code should depend on the smallest required port.

### Policy Before Mechanism

Domain and application code should express decisions such as:

- whether an annotation is editable
- how sequence numbers advance
- whether a handoff batch can be created
- how target reliability is computed
- how stale evidence affects handoff confidence

Mechanisms such as JSON encoding, ADB forwarding, event-log files, and HTTP
responses should wrap those decisions rather than own them.

## Detailed Requirements

### CAS-1 — Split Domain Models from Contract Models

**Current state**

`Annotation` and `Snapshot` live under `compose.core.domain`, but they use
`FixThisNode`, `FixThisRect`, `SourceCandidate`, `TargetEvidence`,
`TargetReliability`, and `FixThisError` from `compose.core.model`.
`compose.core.model` also contains serializable export structures such as
`FixThisAnnotation`, `AppInfo`, `ActivityInfo`, `TapPoint`, and
`ScreenshotInfo`.

**Problem**

The domain layer is pure Kotlin at the module level, but it is not cleanly
separated from the serialization/export contract. As the JSON schema evolves,
domain objects inherit compatibility pressure that belongs in mappers.

**Contract**

Create domain-native value types for annotation evidence:

- `DomainRect`
- `SemanticsNodeSnapshot`
- `SemanticsTreeKind`
- `SourceHint`
- `AnnotationEvidence`
- `TargetReliabilityAssessment`
- `SnapshotArtifact`
- `DomainError`

Update `Annotation`, `AnnotationTarget`, `Snapshot`, and `SnapshotRoot` to use
domain-native types only. Keep existing `compose.core.model` classes as wire
and export contract models. Add explicit mappers between the two layers.

**Acceptance**

1. `fixthis-compose-core/src/main/kotlin/.../domain/**` no longer imports
   `io.github.beyondwin.fixthis.compose.core.model.*`.
2. Existing serialized MCP/session/output JSON remains byte-for-byte compatible
   for representative fixtures, except for ordering or formatting already
   tolerated by tests.
3. `FixThisMarkdownFormatter` and `FixThisJsonFormatter` continue to format
   contract models, not domain entities.
4. `SessionDomainMappersTest`, formatter tests, and MCP session serialization
   tests pass.

### CAS-2 — Promote Policy-Rich Workflows into Application Use Cases

**Current state**

`CreateAnnotationUseCase` and `SaveSnapshotUseCase` are thin wrappers. Most
real policy lives in MCP services such as `FeedbackDraftService`,
`AnnotationWorkflow`, `TargetEvidenceService`, and `FeedbackSessionStore`.

**Problem**

The outer MCP adapter owns business decisions. That makes behavior harder to
reuse from CLI or future adapters and makes tests depend on DTO-heavy session
fixtures.

**Contract**

Add application-level use cases under `compose-core/usecase/feedback`:

- `SaveCapturedSnapshotUseCase`
- `CreateFeedbackAnnotationUseCase`
- `SavePreviewFeedbackUseCase`
- `CreateHandoffBatchUseCase`
- `ResolveAnnotationUseCase`
- `ClaimAnnotationUseCase`

The use cases depend on repository ports and domain services. MCP services map
DTOs into use-case input objects, invoke the use case, and map results back to
DTOs.

**Acceptance**

1. The rules for blank comments, unknown screen IDs, sequence-number
   assignment, sent/draft transitions, and resolved status transitions are
   tested in core use-case tests.
2. MCP tests keep coverage for mapping and route behavior but no longer need
   to be the only source of truth for those domain rules.
3. Public MCP tool and console behavior is unchanged.

### CAS-3 — Split `FeedbackSessionStore` by Responsibility

**Current state**

`FeedbackSessionStore` is a large synchronized facade that owns state cache,
current-session tracking, mutation construction, event-log write-ahead,
persistence, replay, compaction, and artifact cleanup.

**Problem**

The class violates SRP and makes changes to event replay or annotation
mutation risky. A single edit can affect persistence, recovery, handoff, and
artifact deletion.

**Contract**

Keep the public `FeedbackSessionStore` API temporarily, but make it a small
facade over focused collaborators:

- `SessionStateCache`: in-memory sessions and current-session selection.
- `SessionMutationService`: pure mutation construction and validation.
- `SessionWriteAheadLog`: append event before state mutation.
- `SessionRecoveryService`: boot replay, skipped-session tracking, checkpoint
  recovery.
- `SessionArtifactJanitor`: deletion of screen artifacts and obsolete files.

`SessionReducer` and event-log classes should remain pure where possible.
File operations must be outside reducers.

**Acceptance**

1. `FeedbackSessionStore.kt` is below 250 lines and has no direct
   `deleteRecursively()` call.
2. Replay code is not interleaved with mutation code.
3. Pure mutation behavior is unit-tested without filesystem persistence.
4. Event-log and artifact tests still cover crash/replay behavior.

### CAS-4 — Handler-Based MCP Tool Dispatch

**Current state**

`FixThisToolDispatcher` owns a `Map<String, ToolHandler>` plus argument
parsing, session lookup, payload building, error normalization, freshness
checks, console opening, and feedback mutations.

**Problem**

Adding or changing a tool requires editing a central class. The class depends
on many services and violates OCP and ISP.

**Contract**

Introduce:

```kotlin
internal interface McpToolHandler {
    val name: String
    suspend fun handle(arguments: JsonObject): JsonObject
}
```

Create one handler per public MCP tool under
`fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/handlers/`.
Shared argument parsing can live in small helpers, but individual handlers own
their request contract and response payload.

`FixThisToolDispatcher` becomes a registry lookup plus common error wrapper.

**Acceptance**

1. Adding a new MCP tool does not require editing a `when` or central handler
   map in `FixThisToolDispatcher`.
2. Each handler depends only on ports/services it uses.
3. Existing `McpToolRegistry` metadata remains in sync with handler names.
4. Existing MCP protocol tests and tool tests pass.

### CAS-5 — Handler-Based Sidekick Bridge Routing

**Current state**

`BridgeServer.handleRequest` switches on string method names and directly
calls `BridgeEnvironment`, `BridgeScreenshotReader`, and verification logic.

**Problem**

Bridge transport, authentication, routing, method parsing, and method behavior
live in one class. Adding a bridge method requires editing the core server.

**Contract**

Introduce:

```kotlin
internal interface BridgeMethodHandler {
    val method: String
    suspend fun handle(params: JsonObject): JsonElement
}
```

`BridgeServer` should own only socket lifecycle, frame IO, token validation,
and router invocation. `BridgeRequestRouter` resolves handlers by method name.

**Acceptance**

1. `BridgeServer.kt` is below 180 lines.
2. `verifyUiChange`, `readScreenshot`, `performNavigation`, and
   `captureScreenSnapshot` are method handlers.
3. Unknown method and unauthorized token behavior is unchanged.
4. Sidekick bridge unit tests pass.

### CAS-6 — Split CLI Bridge Client by Transport Responsibility

**Current state**

`BridgeClient` selects devices, reads `session.json` through `run-as`, manages
ADB port forwards, opens TCP sockets, writes/reads frames, validates protocol
versions, launches apps, reads source indexes, and pulls screenshot artifacts.

**Problem**

The class is hard to reason about and test because protocol, transport, ADB,
and artifact behavior are coupled.

**Contract**

Keep `BridgeClient` as the public facade used by CLI and MCP adapters. Move
implementation into:

- `BridgeSessionReader`
- `BridgeProtocolClient`
- `BridgeTransport`
- `AdbForwardingBridgeTransport`
- `ScreenshotArtifactDownloader`
- `DeviceSelectionState`

`BridgeClient` composes these collaborators and exposes the existing public
methods.

**Acceptance**

1. `BridgeClient.kt` is below 260 lines.
2. Protocol frame tests target `BridgeProtocolClient`.
3. ADB forward cleanup tests target `AdbForwardingBridgeTransport`.
4. Screenshot pull tests target `ScreenshotArtifactDownloader`.
5. Existing CLI and MCP bridge tests pass.

### CAS-7 — Make Feedback Console Modules Explicit

**Current state**

The console has reducer/domain files, but large files such as `rendering.js`
and `annotations.js` still rely on global symbols and `@requires` comments.

**Problem**

Implicit global dependencies make the browser console hard to change safely.
Rendering, selection view models, annotation state, and DOM event binding are
still mixed.

**Contract**

Convert console source files under `fixthis-mcp/src/main/console` to explicit
ES module imports/exports while keeping the bundled `resources/console/app.js`
output contract. Split large files by role:

- `presentation/annotationListView.js`
- `presentation/selectionOverlayView.js`
- `presentation/annotationDetailView.js`
- `presentation/promptReadinessView.js`
- `viewmodel/annotationPresentation.js`
- `viewmodel/reliabilityPresentation.js`

Rendering functions receive state/view models and DOM ports explicitly.

**Acceptance**

1. No source file uses `@requires` comments as dependency management.
2. `rendering.js` is removed or reduced to a small composition module below
   200 lines.
3. Console tests and browser smoke tests pass.
4. Generated resource bundle still exposes the same runtime entry point.

### CAS-8 — Strengthen Architecture Guardrails

**Current state**

Boundary tests and hotspot budget tests exist, but the hotspot budget mostly
records existing large files. It does not yet enforce the new internal
boundaries after this remediation.

**Contract**

Add tests for:

- domain package must not import contract model package
- MCP route/handler code must not import Android or sidekick code
- bridge server must not depend on MCP/CLI code
- new handler directories must not contain central `when` dispatch on tool or
  method names
- hotspot budgets decrease after each phase

**Acceptance**

1. Architecture tests fail if domain imports contract model classes.
2. Architecture tests fail if `FeedbackSessionStore.kt`, `BridgeClient.kt`,
   `BridgeServer.kt`, or `rendering.js` grow beyond the new budgets.
3. CI runs the architecture tests as part of existing JVM/Node verification.

## Rollout Strategy

The work should land in phases:

1. Guardrails and characterization tests.
2. Domain/contract split.
3. Core use-case promotion.
4. MCP session-store split.
5. MCP and bridge handler dispatch.
6. CLI bridge split.
7. Console module split.

Each phase should be behavior-preserving from the user's perspective. The
largest compatibility risk is CAS-1 because it touches mapping between domain,
DTO, and formatter models. Land that phase before broad MCP store changes so
later tasks build on the new boundary.

## Verification Matrix

Run these checks before marking the remediation complete:

```bash
./gradlew :fixthis-compose-core:test
./gradlew :fixthis-mcp:test
./gradlew :fixthis-cli:test
./gradlew :fixthis-compose-sidekick:test
npm run console:test:fast
./scripts/verify-ci-local.sh --fast
```

For final integration, run:

```bash
./scripts/verify-ci-local.sh --full
```
