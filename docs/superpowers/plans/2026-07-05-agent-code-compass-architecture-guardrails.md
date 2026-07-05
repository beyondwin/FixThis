# Agent Code Compass Architecture Guardrails Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an agent-facing code compass and strengthen FixThis architecture guardrails so maintainers and AI agents can find the right code paths and understand boundary violations before changing behavior.

**Architecture:** This is a navigation and governance pass around the existing module architecture. The compass routes agents to maintained docs, source files, boundaries, and checks; ADR-0008 records the tolerated `session` exceptions; architecture tests remain the executable guardrails with clearer failure output.

**Tech Stack:** Markdown docs; Kotlin/JVM architecture tests under `:fixthis-mcp`; existing Gradle test runner; existing doc consistency and Graphify commands.

## Global Constraints

- No MCP tool schema changes.
- No persisted feedback-session JSON field renames or migrations.
- No bridge protocol changes.
- No CLI command or flag behavior changes.
- No feedback console workflow or compact handoff format changes.
- No source-matching score policy or target-reliability policy changes.
- No broad package rewrite, Gradle module split, or product runtime behavior change.
- No committed `.fixthis/`, `graphify-out/`, Android build output, screenshots, local fixture workspaces, or generated reports.
- `docs/architecture/agent-code-compass.md` is a router, not a new source of truth.
- Stable contracts remain in `docs/reference/*`; architecture decisions remain in ADRs; implementation truth remains in current Kotlin, JavaScript, Gradle, shell, and Markdown sources.

---

## File Structure

- Create `docs/architecture/agent-code-compass.md`
  - Responsibility: agent-facing route table for common FixThis code-change tasks.
  - It must link to maintained docs and source/test entry points rather than duplicating detailed contracts.
- Modify `docs/guides/project-map.md`
  - Responsibility: keep the existing compact module map and work-route table, with a link to the new compass and an architecture/guardrails work route.
- Modify `docs/architecture/adr/0008-session-package-decomposition.md`
  - Responsibility: clarify current `session` package exceptions, allowed directions, retirement paths, and the process for new exceptions.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/SessionPackageBoundaryTest.kt`
  - Responsibility: keep current `session` dependency restrictions but express rules as data and emit actionable failure messages.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt`
  - Responsibility: keep current module import restrictions and emit architecture-aware messages.
- Modify `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt`
  - Responsibility: keep current line-budget ratchets and emit guidance for budget failures.
- Modify `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStore.kt`
  - Responsibility: remove duplicate delegate construction around nullable `compactionFailureSink` without changing behavior.

---

### Task 1: Agent Code Compass And Project Map Route

**Files:**
- Create: `docs/architecture/agent-code-compass.md`
- Modify: `docs/guides/project-map.md`

**Interfaces:**
- Consumes: existing `AGENTS.md` read order, existing `docs/guides/project-map.md` work-route shape, existing `docs/reference/*` contracts.
- Produces: `docs/architecture/agent-code-compass.md`, which later docs and tests can cite as the agent navigation route.

- [ ] **Step 1: Create the compass document**

Create `docs/architecture/agent-code-compass.md` with this exact content:

```markdown
# Agent Code Compass

This page is a routing guide for coding agents and maintainers changing FixThis
source code. It does not replace reference contracts, ADRs, or the current
implementation. Use it to choose the first files and checks for a task, then
verify behavior against source and reference docs.

## Source-Of-Truth Order

1. Current Kotlin, JavaScript, Gradle, shell, and Markdown implementation.
2. `docs/reference/*` for stable CLI, MCP, bridge, output schema, privacy,
   compatibility, and console contracts.
3. `docs/architecture/adr/*` for durable architecture decisions.
4. `docs/guides/project-map.md` and this compass for navigation.
5. `docs/superpowers/*`, `docs/specs/*`, and `docs/plans/*` only as historical
   planning context unless a maintained doc, ADR, or source file points to them.

## Global Boundaries

- `:fixthis-compose-core` must not depend on Android UI, MCP, CLI, Gradle plugin,
  sidekick runtime, browser DTOs, or `.fixthis/` paths.
- Persisted MCP JSON field names such as `items`, `screens`, `itemId`,
  `screenId`, `targetEvidence`, `targetReliability`, and `sourceCandidates` are
  compatibility contracts.
- Bridge protocol changes require `docs/reference/bridge-protocol.md` and the
  bridge/client implementation to move together.
- The Android app does not host MCP or HTTP; desktop `fixthis-mcp` owns the
  local console, MCP tools, session store, and feedback queue.
- Do not commit `.fixthis/`, `graphify-out/`, build outputs, generated fixture
  workspaces, screenshots, or reports unless a maintained doc explicitly says a
  checked-in artifact is required.

## Task Routes

| Work type | Read first | First source files | Boundaries | Focused checks |
| --- | --- | --- | --- | --- |
| Source matching and target reliability | `docs/reference/source-matching.md`, `docs/reference/output-schema.md` | `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`, `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculator.kt` | Keep source scoring pure; do not move MCP DTOs or `.fixthis/` storage into `compose-core`. | `./gradlew :fixthis-compose-core:test --tests '*SourceMatcher*' --no-daemon`, `npm run source-matching:fixtures:test` |
| MCP session lifecycle, persistence, and queue | `docs/reference/output-schema.md`, `docs/reference/mcp-tools.md`, `docs/architecture/adr/0008-session-package-decomposition.md` | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`, `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStore.kt` | Preserve persisted JSON names and ADR-0008 package direction; new `session` dependencies need a rule and ADR update. | `./gradlew :fixthis-mcp:test --tests '*session*' --no-daemon`, `./gradlew :fixthis-mcp:test --tests '*architecture*' --no-daemon` |
| Compact handoff and agent prompt output | `docs/reference/feedback-console-contract.md`, `docs/design/handoff-prompt-rationale.md`, `docs/reference/output-schema.md` | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/CompactHandoffRenderer.kt`, `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/FeedbackQueueFormatter.kt`, `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt` | Keep JSON complete and Markdown compact; do not rename persisted fields or split Copy Prompt and Save to MCP formats. | `npm run handoff:eval:test`, `./gradlew :fixthis-mcp:test --tests '*Handoff*' --no-daemon` |
| Browser console UI and state sync | `docs/reference/feedback-console-contract.md`, `docs/architecture/console-state-sync-design.md` | `fixthis-mcp/src/main/console/app.js`, `fixthis-mcp/src/main/console/events.js`, `fixthis-mcp/src/main/console/state.js`, `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt` | Keep `/api/events` primary with fallback polling retained; browser DTO changes must match route tests and contract docs. | `npm run console:test:fast`, `node scripts/build-console-assets.mjs --check`, `./gradlew :fixthis-mcp:test --tests '*console*' --no-daemon` |
| CLI setup and external app installation | `docs/reference/cli.md`, `docs/reference/agent-setup-schema.md`, `docs/getting-started/add-to-your-app.md` | `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentCommand.kt`, `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt`, `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InitCommand.kt` | Keep CLI help, docs, and install-agent JSON schema aligned; do not hide required user action in setup reports. | `./gradlew :fixthis-cli:test --no-daemon`, `bash scripts/check-docs-cli-surface.sh`, `npm run docs:agent-bootstrap:test` |
| Android bridge and sidekick runtime | `docs/reference/bridge-protocol.md`, `docs/architecture/overview.md`, `docs/guides/troubleshooting.md` | `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`, `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/BridgeClient.kt` | Debug builds only; no release runtime support; bridge protocol changes are coordinated and additive when possible. | `./gradlew :fixthis-compose-sidekick:testDebugUnitTest :fixthis-cli:test --no-daemon` |
| Gradle plugin and source-index generation | `docs/reference/source-matching.md`, `docs/reference/compatibility.md` | `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/FixThisGradlePlugin.kt`, `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt` | Debug variant wiring only; generated assets stay under build output; no running-device state in the plugin. | `./gradlew :fixthis-gradle-plugin:test --no-daemon`, `npm run source-matching:fixtures:test` |
| Release readiness and evidence scripts | `docs/contributing/release-readiness.md`, `docs/contributing/required-checks.md`, `CONTRIBUTING.md` | `scripts/check-release-readiness.mjs`, `scripts/run-release-evidence.mjs`, `scripts/required-checks-observation.mjs`, `package.json` | Do not claim branch-protection admin changes from repo-only evidence; distinguish pass, deferred, fixture drift, and admin action pending. | `node scripts/check-release-readiness.mjs`, `npm run checks:observation -- --json`, `npm run release:check` |
| Architecture and guardrails | `docs/architecture/adr/README.md`, `docs/architecture/adr/0001-use-clean-architecture-layering.md`, `docs/architecture/adr/0008-session-package-decomposition.md` | `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt`, `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/SessionPackageBoundaryTest.kt`, `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt` | Tighten rules when cleanup removes exceptions; add ADR text before allowing new exception directions. | `./gradlew :fixthis-mcp:test --tests '*architecture*' --no-daemon`, `git diff --check` |

## When A Boundary Test Fails

1. Read the failing assertion message and the cited ADR or guide.
2. Prefer removing the dependency, moving shared helpers to a lower package, or
   splitting a hotspot file.
3. If a new dependency direction is genuinely necessary, update the relevant ADR
   in the same change as the test rule.
4. Do not raise hotspot budgets silently. If a file shrinks, lower its budget in
   the same commit.
```

- [ ] **Step 2: Link the compass from the project map intro**

In `docs/guides/project-map.md`, replace the second paragraph:

```markdown
Use this page when you need to understand where to start. For the long-form maintainer explanation, read [Fullstack/tooling handover](fullstack-tooling-handover.md). For stable API, CLI, MCP, bridge, and persisted JSON behavior, prefer the reference docs under [`docs/reference/`](../reference/).
```

with:

```markdown
Use this page when you need to understand where to start. For the long-form
maintainer explanation, read [Fullstack/tooling handover](fullstack-tooling-handover.md).
For task-by-task agent navigation before editing, read
[Agent code compass](../architecture/agent-code-compass.md). For stable API,
CLI, MCP, bridge, and persisted JSON behavior, prefer the reference docs under
[`docs/reference/`](../reference/).
```

- [ ] **Step 3: Add the architecture/guardrails work route**

In `docs/guides/project-map.md`, add this row to the `## Work Routes` table after the `Release readiness` row:

```markdown
| Architecture and guardrails | `docs/architecture/agent-code-compass.md`, `docs/architecture/adr/README.md`, `docs/architecture/adr/0008-session-package-decomposition.md` | `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt`, `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/SessionPackageBoundaryTest.kt`, `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt` | `./gradlew :fixthis-mcp:test --tests '*architecture*' --no-daemon`, `git diff --check` |
```

- [ ] **Step 4: Add the compass to Next Reads**

In `docs/guides/project-map.md`, add this bullet immediately after the `Architecture overview` bullet in `## Next Reads`:

```markdown
- [Agent code compass](../architecture/agent-code-compass.md) for task-by-task source, boundary, and check routing before code edits.
```

- [ ] **Step 5: Run documentation validation**

Run:

```bash
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected:

```text
PASS
```

If `check-doc-consistency.mjs` prints additional success text, keep it; the command must exit `0`.

- [ ] **Step 6: Commit Task 1**

```bash
git add docs/architecture/agent-code-compass.md docs/guides/project-map.md
git commit -m "docs: add agent code compass"
```

---

### Task 2: ADR-0008 Exception Register

**Files:**
- Modify: `docs/architecture/adr/0008-session-package-decomposition.md`

**Interfaces:**
- Consumes: existing ADR-0008 exception labels E1-E4 and current `SessionPackageBoundaryTest` relaxed directions.
- Produces: explicit allowed directions and retirement paths for Task 3's failure messages.

- [ ] **Step 1: Replace the ADR-0008 Exceptions section**

In `docs/architecture/adr/0008-session-package-decomposition.md`, replace the entire `## Exceptions` section with:

```markdown
## Exceptions

`SessionPackageBoundaryTest` enforces the intra-`session` dependency direction.
The following pre-existing dependencies surfaced when the boundary test was first
run against the completed decomposition. Each is a genuine, justified coupling
that cannot be removed without further refactoring beyond the scope of this move.

These exceptions are an allow-list, not a pattern to copy. A new cross-package
dependency requires both an ADR update and a matching `SessionPackageBoundaryTest`
rule change in the same commit.

| Exception | Currently allowed direction | Why it is tolerated | Retirement path |
| --- | --- | --- | --- |
| E1 | `preview` -> `lifecycle.store`; `preview` -> `target` | `PreviewCaptureService` orchestrates preview capture, session persistence, screenshot promotion, and target evidence through `FeedbackSessionStore` and `TargetEvidenceService`. `PreviewSaveReservationTracker` and `ScreenshotArtifactPromoter` also reference store types. | Extract a preview workflow port or lower application-service boundary so preview capture can depend on interfaces rather than concrete store/target services. `preview` -> `handoff` remains forbidden. |
| E2 | `target` -> selected `handoff` formatting helpers | `TargetBoundaryContextFormatter` and `TargetSummaryFormatter` reuse `compactQuotedValue`, `formatBounds`, `formatBox`, and `inlineSafe` from `handoff/FormatterExtensions.kt`. The coupling is rendering-helper reuse, not target policy depending on handoff workflow. | Move shared formatting helpers to a lower `session/dto`, `session/domain`, or dedicated formatting package and forbid `target` -> `handoff` again. |
| E3 | `lifecycle/event` -> selected `handoff` models | Session event and mutation payloads carry `FeedbackDelivery` and `FeedbackHandoffBatch` from `handoff/FeedbackHandoffModels.kt` as event-sourced state. These are shared state models, not handoff rendering behavior. | Move shared delivery and batch state models to a lower `session/dto` or `session/domain` package and forbid `lifecycle/event` -> `handoff` again. |
| E4 | `lifecycle/store` -> selected `handoff` models | `FeedbackSessionSummary`, `FeedbackSessionStoreDraftDeduplication`, and `FeedbackSessionStoreDelegate` read `FeedbackDelivery` to classify and count persisted items. This is the same shared state-model coupling as E3. | Move shared delivery state to a lower `session/dto` or `session/domain` package and forbid `lifecycle/store` -> `handoff` again. |

Current forbidden directions after the exception register:

- `editsurface` must not import `lifecycle.store`, `handoff`, `preview`, or
  `connection`.
- `handoff` must not import `lifecycle.store`, `preview`, or `connection`.
- `preview` must not import `handoff`.
- `target` must not import `lifecycle.store`, `preview`, or `connection`.
- `source` must not import `lifecycle.store`, `handoff`, `preview`, or `target`.
- `lifecycle/event` must not import `preview` or `connection`.
- `lifecycle/store` must not import `connection` or `target`.
- `draft` must not import `connection`.
- `connection` must not import `handoff`, `preview`, or `target`.

A future ADR may lift the shared `handoff` domain models (`FeedbackDelivery`,
`FeedbackHandoffBatch`) and `FormatterExtensions` into a lower package to retire
E2-E4. When that happens, tighten `SessionPackageBoundaryTest` in the same
commit as the model/helper move.
```

- [ ] **Step 2: Run markdown checks**

Run:

```bash
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected: both commands exit `0`.

- [ ] **Step 3: Commit Task 2**

```bash
git add docs/architecture/adr/0008-session-package-decomposition.md
git commit -m "docs(adr): clarify session boundary exceptions"
```

---

### Task 3: Architecture Test Failure Messages

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/SessionPackageBoundaryTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt`

**Interfaces:**
- Consumes: ADR-0008 exception labels and existing architecture guard behavior.
- Produces: same pass/fail semantics with more actionable assertion messages.

- [ ] **Step 1: Replace `SessionPackageBoundaryTest.kt` with table-driven rules**

Replace the full contents of `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/SessionPackageBoundaryTest.kt` with:

```kotlin
package io.github.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SessionPackageBoundaryTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }
    private val session = "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session"

    private data class BoundaryRule(
        val sourceGroup: String,
        val forbiddenGroups: List<String>,
        val reason: String,
        val allowedException: String? = null,
    ) {
        val forbiddenImportRegex: Regex = Regex(
            """^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(${forbiddenGroups.joinToString("|")})\.""",
        )
    }

    private val rules = listOf(
        BoundaryRule(
            sourceGroup = "editsurface",
            forbiddenGroups = listOf("lifecycle\\.store", "handoff", "preview", "connection"),
            reason = "edit-surface analysis consumes DTO/core evidence and must not reach into storage, handoff rendering, preview capture, or connection recovery",
        ),
        BoundaryRule(
            sourceGroup = "handoff",
            forbiddenGroups = listOf("lifecycle\\.store", "preview", "connection"),
            reason = "handoff rendering must not orchestrate persistence, preview capture, or connection recovery",
        ),
        BoundaryRule(
            sourceGroup = "preview",
            forbiddenGroups = listOf("handoff"),
            reason = "preview capture may orchestrate store/target services under ADR-0008 E1 but must not depend on handoff rendering",
            allowedException = "ADR-0008 E1 allows preview -> lifecycle.store and preview -> target only",
        ),
        BoundaryRule(
            sourceGroup = "target",
            forbiddenGroups = listOf("lifecycle\\.store", "preview", "connection"),
            reason = "target evidence must stay independent of persistence, preview capture, and connection recovery",
            allowedException = "ADR-0008 E2 currently tolerates target -> handoff formatting helpers only",
        ),
        BoundaryRule(
            sourceGroup = "source",
            forbiddenGroups = listOf("lifecycle\\.store", "handoff", "preview", "target"),
            reason = "source freshness/path resolution must not orchestrate persistence, handoff rendering, preview capture, or target evidence",
        ),
        BoundaryRule(
            sourceGroup = "lifecycle/event",
            forbiddenGroups = listOf("preview", "connection"),
            reason = "event-sourced lifecycle code must not depend on preview capture or connection recovery",
            allowedException = "ADR-0008 E3 currently tolerates lifecycle/event -> handoff state models only",
        ),
        BoundaryRule(
            sourceGroup = "lifecycle/store",
            forbiddenGroups = listOf("connection", "target"),
            reason = "session storage must not depend on connection recovery or target evidence services",
            allowedException = "ADR-0008 E4 currently tolerates lifecycle/store -> handoff state models only",
        ),
        BoundaryRule(
            sourceGroup = "draft",
            forbiddenGroups = listOf("connection"),
            reason = "draft workflow must not depend on connection recovery",
        ),
        BoundaryRule(
            sourceGroup = "connection",
            forbiddenGroups = listOf("handoff", "preview", "target"),
            reason = "connection recovery must not depend on handoff rendering, preview capture, or target evidence",
        ),
    )

    @Test
    fun sessionPackagesRespectDocumentedDependencyDirection() {
        val violations = rules.flatMap(::violationsFor)

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("Session package dependency boundary violated.")
                appendLine("Read docs/architecture/adr/0008-session-package-decomposition.md before adding a new direction.")
                appendLine("Remove the import, move shared code to a lower package, or update ADR-0008 and this rule in the same commit.")
                appendLine()
                append(violations.joinToString(separator = "\n"))
            },
        )
    }

    private fun violationsFor(rule: BoundaryRule): List<String> = File(root, "$session/${rule.sourceGroup}").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (rule.forbiddenImportRegex.containsMatchIn(line)) {
                    buildString {
                        append("${file.relativeTo(root)}:${index + 1}: $line")
                        append("\n  sourceGroup=${rule.sourceGroup}")
                        append("\n  forbidden=${rule.forbiddenGroups.joinToString()}")
                        append("\n  reason=${rule.reason}")
                        rule.allowedException?.let { append("\n  allowedException=$it") }
                    }
                } else {
                    null
                }
            }
        }.toList()
}
```

- [ ] **Step 2: Replace `ModuleBoundaryTest.kt` helper logic**

Replace the full contents of `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt` with:

```kotlin
package io.github.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ModuleBoundaryTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }
    private val coreDomainSourceRoot =
        "fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain"

    @Test
    fun composeCoreDoesNotImportOuterModulesOrAndroid() {
        assertNoForbiddenImports(
            paths = listOf("fixthis-compose-core/src/main"),
            forbidden = Regex("""^import (android|androidx|io\.github\.beyondwin\.fixthis\.(mcp|cli|gradle|compose\.sidekick))"""),
            boundaryName = "compose-core purity",
            guidance = "compose-core owns pure domain/source/target/format policies and must not depend on Android, MCP, CLI, Gradle plugin, sidekick runtime, browser DTOs, or .fixthis paths. Move adapter code outward or add a lower pure model instead.",
        )
    }

    @Test
    fun composeCoreTargetPoliciesDoNotImportMcpSessionDtos() {
        assertNoForbiddenImports(
            paths = listOf("fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target"),
            forbidden = Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\."""),
            boundaryName = "target policy purity",
            guidance = "target reliability/evidence policy must stay pure. Map MCP session DTOs at the outer module boundary instead of importing them into compose-core.",
        )
    }

    @Test
    fun composeCoreDomainDoesNotImportContractModels() {
        assertNoForbiddenImports(
            paths = listOf(coreDomainSourceRoot),
            forbidden = Regex("""^import io\.github\.beyondwin\.fixthis\.compose\.core\.model\."""),
            boundaryName = "domain model independence",
            guidance = "compose-core/domain contains domain IDs, entities, and ports. Keep contract/export models in compose-core/model and translate at explicit boundaries.",
        )
    }

    @Test
    fun sidekickGradlePluginAndSampleDoNotImportMcpOrCli() {
        assertNoForbiddenImports(
            paths = listOf(
                "fixthis-compose-sidekick/src/main",
                "fixthis-gradle-plugin/src/main",
                "sample/src/main",
            ),
            forbidden = Regex("""^import io\.github\.beyondwin\.fixthis\.(mcp|cli)"""),
            boundaryName = "outer module direction",
            guidance = "sidekick, Gradle plugin, and sample code must not depend on MCP or CLI internals. Share only through compose-core or explicit bridge/CLI contracts.",
        )
    }

    private fun assertNoForbiddenImports(
        paths: List<String>,
        forbidden: Regex,
        boundaryName: String,
        guidance: String,
    ) {
        val offenders = paths.flatMap(::kotlinFiles)
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (forbidden.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: $line" else null
                }
            }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("Module boundary violated: $boundaryName.")
                appendLine(guidance)
                appendLine("Read docs/architecture/adr/0001-use-clean-architecture-layering.md and docs/guides/project-map.md.")
                appendLine()
                append(offenders.joinToString(separator = "\n"))
            },
        )
    }

    private fun kotlinFiles(path: String): List<File> = File(root, path)
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()
}
```

- [ ] **Step 3: Improve `ArchitectureHotspotBudgetTest.kt` assertion output**

In `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt`, replace the offenders block and assertion:

```kotlin
        val offenders = budgets.mapNotNull { (path, maxLines) ->
            val file = File(root, path)
            val lines = file.readLines().size
            if (lines > maxLines) "$path has $lines lines, budget is $maxLines" else null
        }

        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
```

with:

```kotlin
        val offenders = budgets.mapNotNull { (path, maxLines) ->
            val file = File(root, path)
            val lines = file.readLines().size
            if (lines > maxLines) HotspotOverflow(path = path, lines = lines, budget = maxLines) else null
        }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("Architecture hotspot budget exceeded.")
                appendLine("These budgets are ratchets. If a file shrinks, lower the matching budget in the same commit.")
                appendLine("If a file grows, prefer splitting responsibilities or recording a justified architecture decision instead of silently raising the budget.")
                appendLine("For task routing, read docs/architecture/agent-code-compass.md.")
                appendLine()
                append(offenders.joinToString(separator = "\n") { "${it.path} has ${it.lines} lines, budget is ${it.budget}" })
            },
        )
```

Then add this data class just before `private fun remediationBudgetEnabled`:

```kotlin
    private data class HotspotOverflow(
        val path: String,
        val lines: Int,
        val budget: Int,
    )
```

- [ ] **Step 4: Run focused architecture tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*architecture*' --no-daemon
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Run formatting check for touched Kotlin tests**

Run:

```bash
./gradlew spotlessCheck --no-daemon
git diff --check
```

Expected: both commands exit `0`.

- [ ] **Step 6: Commit Task 3**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/SessionPackageBoundaryTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt
git commit -m "test(mcp): explain architecture guardrail failures"
```

---

### Task 4: FeedbackSessionStore Delegate Construction Cleanup

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStore.kt`

**Interfaces:**
- Consumes: `FeedbackSessionStoreDelegate` constructor parameter `compactionFailureSink: ((sessionId: String, cause: Throwable) -> Unit)? = null`.
- Produces: identical `FeedbackSessionStore` public behavior with a single delegate construction path.

- [ ] **Step 1: Simplify the delegate initialization**

In `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStore.kt`, replace:

```kotlin
    private val delegate = if (compactionFailureSink == null) {
        FeedbackSessionStoreDelegate(
            clock = clock,
            idGenerator = idGenerator,
            persistence = persistence,
            eventLogWriterProvider = eventLogWriterProvider,
            eventLogReaderProvider = eventLogReaderProvider,
            eventLogCompactorProvider = eventLogCompactorProvider,
            eventLogCompactionThreshold = eventLogCompactionThreshold,
        )
    } else {
        FeedbackSessionStoreDelegate(
            clock = clock,
            idGenerator = idGenerator,
            persistence = persistence,
            eventLogWriterProvider = eventLogWriterProvider,
            eventLogReaderProvider = eventLogReaderProvider,
            eventLogCompactorProvider = eventLogCompactorProvider,
            eventLogCompactionThreshold = eventLogCompactionThreshold,
            compactionFailureSink = compactionFailureSink,
        )
    }
```

with:

```kotlin
    private val delegate = FeedbackSessionStoreDelegate(
        clock = clock,
        idGenerator = idGenerator,
        persistence = persistence,
        eventLogWriterProvider = eventLogWriterProvider,
        eventLogReaderProvider = eventLogReaderProvider,
        eventLogCompactorProvider = eventLogCompactorProvider,
        eventLogCompactionThreshold = eventLogCompactionThreshold,
        compactionFailureSink = compactionFailureSink,
    )
```

- [ ] **Step 2: Run session store tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackSessionStore*' --no-daemon
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: Run architecture tests again**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*architecture*' --no-daemon
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 4: Commit Task 4**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStore.kt
git commit -m "refactor(mcp): simplify session store delegate wiring"
```

---

### Task 5: Final Verification And Graphify Refresh

**Files:**
- Verify only; do not commit `graphify-out/`, `.fixthis/`, `build/`, or reports.

**Interfaces:**
- Consumes: all prior tasks.
- Produces: final verification evidence and a clean worktree aside from intentionally uncommitted local Graphify artifacts.

- [ ] **Step 1: Run full MCP tests**

Run:

```bash
./gradlew :fixthis-mcp:test --no-daemon
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 2: Run documentation consistency**

Run:

```bash
node scripts/check-doc-consistency.mjs
```

Expected: command exits `0`. If it prints rule names, all rules must be pass messages, not `FAIL`.

- [ ] **Step 3: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output and exit `0`.

- [ ] **Step 4: Refresh Graphify**

Run:

```bash
graphify update .
```

Expected: command exits `0`. Do not stage `graphify-out/`.

- [ ] **Step 5: Confirm final changed set**

Run:

```bash
git status --short
```

Expected: only intended tracked source/docs changes are committed. `graphify-out/` may be dirty or ignored, but it must not be staged.

- [ ] **Step 6: Final no-op commit check**

Run:

```bash
git log --oneline -5
```

Expected: the most recent commits include:

```text
refactor(mcp): simplify session store delegate wiring
test(mcp): explain architecture guardrail failures
docs(adr): clarify session boundary exceptions
docs: add agent code compass
```

Do not create an extra verification-only commit.
