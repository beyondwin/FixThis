# Project Improvement Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stabilize the existing FixThis V1 product by aligning the console contract, adding repeatable verification, improving onboarding and smoke diagnostics, reducing MCP console risk, and preparing evidence/security/release readiness without changing existing public behavior unexpectedly.

**Architecture:** Keep the current MCP-console-first architecture. Implement improvements in ordered, compatibility-preserving slices: contract and tests first, CI/onboarding second, MCP modularization third, evidence/security/release readiness last. Existing MCP tool names, HTTP endpoint paths, persisted session JSON fields, debug-only scope, local-first privacy guarantees, and `--console-assets-dir` behavior remain stable unless a later ADR explicitly changes them.

**Tech Stack:** Kotlin/JVM, Android Gradle Plugin, Gradle/JUnit tests, kotlinx.serialization, Clikt, `com.sun.net.httpserver.HttpServer`, vanilla HTML/CSS/JavaScript console assets, Node syntax checks, shell smoke script, GitHub Actions.

---

## Source Documents

- Design: `docs/superpowers/specs/2026-05-08-project-improvement-stabilization-design.md`
- Proposal: `docs/project-improvement-proposals-2026-05-07.md`
- UX status: `docs/design-feedback-console-ux.md`
- Zero setup proposal: `docs/design-zero-setup.md`
- MCP docs: `docs/mcp.md`
- PRD: `docs/fixthis_prd.md`
- Privacy: `docs/privacy.md`
- Troubleshooting: `docs/troubleshooting.md`

## Execution Rules

- Use a new implementation branch or worktree, preferably `codex/project-improvement-stabilization`.
- Keep each task independently reviewable and commit after each task.
- Preserve unrelated local changes. If `fixthis-mcp/src/main/resources/console/app.js` or `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt` contains unrelated uncommitted work, read and preserve it before editing.
- Update checkbox state in this file as tasks complete.
- After each task, run the listed validation command and record PASS, FAIL, or SKIPPED with the reason.
- For connected-device checks, SKIPPED is acceptable only with an explicit category. Connected-device state categories include `SKIPPED_NO_DEVICE`, `SKIPPED_UNAUTHORIZED_DEVICE`, `SKIPPED_OFFLINE_DEVICE`, `SKIPPED_LOCKED_DEVICE`, `SKIPPED_WIRELESS_ADB_LOST`, or `SKIPPED_MULTIPLE_DEVICES`; use `SKIPPED_HOST_ONLY` only for intentional host-only/manual smoke skips.
- Do not run broad process cleanup such as `killall node`, `pkill node`, `killall chrome`, or `pkill playwright`.

## Current Baseline

- Current analyzed branch: `main`
- Detailed design commit: `c0cdb26 docs: add project improvement detailed design`
- Repo-local `AGENTS.md` or `CLAUDE.md`: none found during plan creation.
- Existing PR template: `.github/pull_request_template.md`
- Existing required CI workflow: none found during plan creation.
- Existing root `CONTRIBUTING.md`, `CHANGELOG.md`, `LICENSE`, `SECURITY.md`: none found during plan creation.
- Largest hot spots:
  - `fixthis-mcp/src/main/resources/console/app.js`
  - `fixthis-mcp/src/main/resources/console/styles.css`
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`
  - `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

## Target File Structure

### New Docs And CI

- Create: `docs/feedback-console-contract.md`
- Create: `CONTRIBUTING.md`
- Create: `.github/workflows/ci.yml`
- Create: `docs/release-readiness.md`
- Modify: `README.md`
- Modify: `docs/mcp.md`
- Modify: `docs/design-feedback-console-ux.md`
- Modify: `docs/fixthis_prd.md`
- Modify: `docs/privacy.md`
- Modify: `docs/troubleshooting.md`
- Modify: `.github/pull_request_template.md`

### CLI Setup And Cleanup

- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/Main.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt`
- Create: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/AndroidSdkLocator.kt`
- Create: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/McpConfigEntry.kt`
- Create: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/AgentConfigWriter.kt`
- Create: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/CodexConfigWriter.kt`
- Create: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/ClaudeConfigWriter.kt`
- Create: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/CleanCommand.kt`
- Create or modify tests under `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/`

### MCP Session And Console

- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/ConsoleConnectionService.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/PreviewCaptureService.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleHttp.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleRoutes.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/SessionRoutes.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/DeviceRoutes.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConnectionRoutes.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/PreviewRoutes.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ArtifactRoutes.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`
- Modify tests under `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/`

### Console Assets

- Create: `fixthis-mcp/src/main/console/state.js`
- Create: `fixthis-mcp/src/main/console/api.js`
- Create: `fixthis-mcp/src/main/console/connection.js`
- Create: `fixthis-mcp/src/main/console/devices.js`
- Create: `fixthis-mcp/src/main/console/preview.js`
- Create: `fixthis-mcp/src/main/console/annotations.js`
- Create: `fixthis-mcp/src/main/console/history.js`
- Create: `fixthis-mcp/src/main/console/prompt.js`
- Create: `fixthis-mcp/src/main/console/rendering.js`
- Create: `fixthis-mcp/src/main/console/shortcuts.js`
- Create: `fixthis-mcp/src/main/console/main.js`
- Create: `scripts/build-console-assets.mjs`
- Modify generated resource: `fixthis-mcp/src/main/resources/console/app.js`

### Evidence And Sample Coverage

- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceIndex.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt`
- Modify: `fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt`
- Create: `sample/fixthis-coverage.json`
- Modify: `sample/src/androidTest/java/io/beyondwin/fixthis/sample/SampleAppSmokeTest.kt`

---

## Task 0: Prepare The Implementation Workspace

**Files:**
- No source file changes.

- [x] **Step 1: Confirm the starting state**

Run:

```bash
git status --short
git branch --show-current
git log --oneline --decorate -5
```

Expected: branch is `main` or a dedicated implementation branch. If the working tree is dirty, identify unrelated changes and preserve them.

- [x] **Step 2: Create an implementation branch or worktree**

Run from the original repo when using a branch:

```bash
git switch -c codex/project-improvement-stabilization
```

Run from the original repo when using a separate worktree:

```bash
git worktree add /Users/kws/.config/superpowers/worktrees/FixThis/project-improvement-stabilization -b codex/project-improvement-stabilization
```

Expected: a dedicated branch named `codex/project-improvement-stabilization`.

- [x] **Step 3: Run the baseline checks**

Run:

```bash
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

Expected: PASS, or a captured baseline failure with raw output and root-cause notes before proceeding.

Baseline notes:
- `git status --short`: PASS, clean before Task 0 edits.
- `git branch --show-current`: PASS, `codex/project-improvement-stabilization`.
- Worktree verification: PASS, existing linked worktree at `/Users/kws/.config/superpowers/worktrees/FixThis/project-improvement-stabilization`; no second worktree created.
- First Gradle attempt: FAIL_BASELINE_ENV, Android SDK location not found because this ignored worktree did not yet have `local.properties`.
- Root cause resolution: added ignored worktree-local `local.properties` with the same SDK path as the original checkout; not committed.
- `./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test`: PASS after SDK configuration. Existing project-owned deprecation warnings are captured for Task 3.
- `./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist`: PASS after SDK configuration.
- `node --check fixthis-mcp/src/main/resources/console/app.js`: PASS.
- `git diff --check`: PASS.

HANDOFF CHECKPOINT:
- Task 0 ran in the integration worktree only.
- Branch is `codex/project-improvement-stabilization` at `a4ed51a` before plan-tracking commit.
- No source files were changed.
- Initial Gradle failure was environment-only: missing ignored SDK config in the worktree.
- Added ignored `local.properties` locally and reran the required baseline.
- Gradle tests, assemble/installDist, Node syntax, and whitespace checks passed.

- [x] **Step 4: Commit only if branch metadata docs were changed**

Expected: no commit for Task 0 unless a workspace handoff note is intentionally added.

## Task 1: Lock The Feedback Console Product Contract

**Files:**
- Create: `docs/feedback-console-contract.md`
- Modify: `README.md`
- Modify: `docs/mcp.md`
- Modify: `docs/design-feedback-console-ux.md`
- Modify: `docs/fixthis_prd.md`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Add the contract document**

Create `docs/feedback-console-contract.md` with this content as the initial contract:

```markdown
# Feedback Console Contract

**Status:** Current V1 Studio contract
**Owner surface:** `fixthis-mcp`

## Canonical Labels

| Surface | DOM id | Label |
| --- | --- | --- |
| Prompt copy | `copyPromptButton` | `Copy Prompt` |
| Agent handoff | `sendAgentButton` | `Send Agent` |
| Canvas select tool | `selectToolButton` | `Select` |
| Canvas annotate tool | `annotateToolButton` | `Annotate` |
| Add pending annotation | `addItemButton` | `Add annotation` |
| Exit annotate mode | `cancelAddFlowButton` | `Exit Annotate` |
| Clear current selection | `clearSelectionButton` | `Clear Selection` |
| Clear draft feedback | `clearDraftButton` | `Clear Draft` |
| Refresh devices | `refreshDevicesButton` | `Refresh devices` |
| Clear FixThis device selection | `disconnectDeviceButton` | `Clear selection` |

## Mode Semantics

- Select mode is the normal preview mode. Preview clicks navigate the debug app when the bridge is ready.
- Annotate mode freezes the latest preview so the user can select Compose nodes or draw visual areas.
- Stale preview state keeps the last preview visible while live bridge actions are disabled.
- Draft/history view shows persisted local feedback groups and sent handoff history.

## Persistence Semantics

- `Annotate` starts targeting and freezes the latest available preview. It does not write a session item by itself.
- `Add annotation` creates a browser-side pending annotation.
- `Copy Prompt` persists written pending annotations when needed, then copies compact agent-facing prompt text.
- `Send Agent` persists written pending annotations when needed, then creates a local handoff batch for MCP tools.
- `Clear Draft` deletes unsent draft feedback after confirmation.
- Live preview frames are transient. Persisted `screens` are evidence snapshots, not every preview frame.

## Device Semantics

- `Clear selection` clears only FixThis's active device selection and owned bridge resources.
- `Clear selection` must not run `adb disconnect`, detach USB, or affect Wi-Fi ADB outside FixThis-owned resources.

## Privacy Semantics

- `Send Agent` stores a local handoff batch.
- FixThis does not upload screenshots, comments, prompt text, source hints, or target evidence by default.
```

- [x] **Step 2: Update public docs to link the contract**

In `README.md`, `docs/mcp.md`, `docs/design-feedback-console-ux.md`, and `docs/fixthis_prd.md`, replace stale `Add` / `Save` / `Copy` / `Send` flow language with the canonical terms from the contract. Keep historical design docs unchanged unless they present themselves as current status.

Example replacement sentence:

```markdown
The current console contract is documented in `docs/feedback-console-contract.md`; the shipped workflow uses `Annotate`, `Add annotation`, `Copy Prompt`, and `Send Agent`.
```

- [x] **Step 3: Add or update console contract assertions**

In `FeedbackConsoleServerTest.kt`, add a focused HTML contract test if one does not already exist:

```kotlin
@Test
fun browserConsoleUsesCurrentFeedbackContractLabels() {
    val server = server()
    try {
        val index = java.net.URI(server.start()).toURL().readText()

        assertTrue(index.contains("Copy Prompt"))
        assertTrue(index.contains("Send Agent"))
        assertTrue(index.contains("Select"))
        assertTrue(index.contains("Annotate"))
        assertTrue(index.contains("Exit Annotate"))
        assertTrue(index.contains("Add annotation"))
        assertTrue(index.contains("Clear Draft"))
        assertTrue(index.contains("Clear selection"))
        assertFalse(index.contains("Save snapshot"))
        assertFalse(index.contains("Add to Pending"))
    } finally {
        server.stop()
    }
}
```

Use existing server helper names if the test file already has a helper equivalent to `server()`.

- [x] **Step 4: Run targeted validation**

Run:

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest'
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add docs/feedback-console-contract.md README.md docs/mcp.md docs/design-feedback-console-ux.md docs/fixthis_prd.md fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "docs: align feedback console contract"
```

Validation notes:
- `./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest'`: PASS. Existing `URL(String)` deprecation warnings remain outside the new contract test; Task 3 owns URL helper cleanup.
- `node --check fixthis-mcp/src/main/resources/console/app.js`: PASS.
- `git diff --check`: PASS.
- Spec review fix: strengthened the focused HTML contract test to assert the documented stable DOM ids as exact `id="..."` strings, then reran the same validation set before amending the Task 1 commit.
- Second spec review fix: extended the same focused test to assert every documented canonical label, including both `Clear Selection` and device `Clear selection`, plus `Refresh devices`; reran the validation set before amending again.
- Quality review fix: paired each documented DOM id with its intended contract label in the same served `<button>` element snippet, then reran the validation set before amending again.

HANDOFF CHECKPOINT:
- Task 1 stayed in `/Users/kws/.config/superpowers/worktrees/FixThis/project-improvement-stabilization`.
- Added `docs/feedback-console-contract.md` as the canonical V1 console contract.
- Current docs now reference `Annotate`, `Add annotation`, `Copy Prompt`, and `Send Agent`.
- Historical PRD background was left historical; current/mainline PRD text was aligned.
- Added focused HTML assertions that pair every canonical label with its stable DOM id, plus stale-label absence checks.
- Required validation passed before the amended Task 1 commit.
- Existing test-file `URL(String)` warnings remain for Task 3.

## Task 2: Add CI And Contributor Verification Baseline

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `CONTRIBUTING.md`
- Modify: `.github/pull_request_template.md`
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-05-08-project-improvement-stabilization-implementation.md`

- [x] **Step 1: Add GitHub Actions workflow**

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  pull_request:
  push:
    branches:
      - main

jobs:
  baseline:
    name: Baseline verification
    runs-on: ubuntu-latest
    timeout-minutes: 45

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up Java
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Make Gradle executable
        run: chmod +x ./gradlew

      - name: Run unit tests
        run: ./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test

      - name: Build sample and distributions
        run: ./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist

      - name: Check console JavaScript syntax
        run: node --check fixthis-mcp/src/main/resources/console/app.js

      - name: Check whitespace
        run: git diff --check
```

- [x] **Step 2: Add contributor guide**

Create `CONTRIBUTING.md`:

```markdown
# Contributing

## Required Local Checks

Run these before opening a pull request:

```bash
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

## Connected Device Checks

Connected-device verification is manual until the project has a reliable device or emulator runner. When it is skipped, record one of these categories in the pull request:

- `SKIPPED_NO_DEVICE`
- `SKIPPED_UNAUTHORIZED_DEVICE`
- `SKIPPED_LOCKED_DEVICE`
- `SKIPPED_WIRELESS_ADB_LOST`

## Local Artifacts

`.fixthis/feedback-sessions/`, `.fixthis/preview-cache/`, `.fixthis/artifacts/`, and `.fixthis/smoke-reports/` can contain screenshots or local feedback. Do not commit or share them casually.

## Compatibility Checklist

- Existing persisted sessions still decode.
- MCP JSON field names are unchanged unless the pull request explains a migration.
- CLI commands keep their current flags and output shape unless the pull request explains the break.
- Existing Compose public APIs keep source compatibility or the pull request explains the break.
- New coroutine code does not hold monitor locks around disk or bridge I/O.
```

- [x] **Step 3: Align PR template verification language**

Update `.github/pull_request_template.md` so the verification table includes the same required local checks and explicit SKIPPED reason rule from `CONTRIBUTING.md`.

- [x] **Step 4: Link contributor guide from README**

Add a `CONTRIBUTING.md` link under README `More detail`.

- [x] **Step 5: Run docs and local baseline validation**

Run:

```bash
git diff --check
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
node --check fixthis-mcp/src/main/resources/console/app.js
```

Expected: PASS locally.

- [x] **Step 6: Commit**

```bash
git add .github/workflows/ci.yml CONTRIBUTING.md .github/pull_request_template.md README.md docs/superpowers/plans/2026-05-08-project-improvement-stabilization-implementation.md
git commit -m "ci: add baseline project verification"
```

Validation notes:
- `git diff --check`: PASS.
- `./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test`: PASS.
- `./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist`: PASS.
- `node --check fixthis-mcp/src/main/resources/console/app.js`: PASS.
- Quality review fix: CI checkout now uses `fetch-depth: 0`, and CI whitespace validation checks committed ranges with `origin/${BASE_REF}...HEAD` for pull requests or `${BEFORE}..${SHA}` for pushes, with fallbacks for missing/all-zero `BEFORE`.
- Revalidation after quality review fix: `git diff --check`: PASS; `node --check fixthis-mcp/src/main/resources/console/app.js`: PASS.

HANDOFF CHECKPOINT:
- Task 2 stayed in `/Users/kws/.config/superpowers/worktrees/FixThis/project-improvement-stabilization`.
- Added the pull request/main-branch CI baseline with Java 21 and Android SDK setup.
- Quality review fix made the CI whitespace check range-aware so committed whitespace errors in PRs are checked.
- Added `CONTRIBUTING.md` with required local checks and connected-device skip categories.
- PR template now names the required checks and requires explicit connected-device skip reasons.
- README `More detail` links the contributor guide.
- Required local validation passed before the Task 2 commit, and YAML/docs-only revalidation passed before amend.
- Connected-device checks remain manual; no device CI was added.

## Task 3: Remove Project-Owned Test Deprecation Warnings

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionServiceTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionPersistenceTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/McpProtocolTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Add temp directory helpers in affected test files**

Replace local `createTempDir(prefix = "fixthis-v2-service-")` calls with a helper using `kotlin.io.path.createTempDirectory`.

Example helper:

```kotlin
private fun tempDir(prefix: String): File =
    kotlin.io.path.createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }
```

Example replacement:

```kotlin
val root = tempDir("fixthis-v2-service-")
```

- [x] **Step 2: Add an HTTP helper for console tests**

In `FeedbackConsoleServerTest.kt`, add a helper near the bottom of the file:

```kotlin
private class ConsoleHttpTestClient(private val baseUrl: String) {
    fun get(path: String = "/"): String =
        java.net.URI(baseUrl + path).toURL().readText()

    fun connection(path: String, method: String = "GET", body: String? = null): java.net.HttpURLConnection {
        val connection = java.net.URI(baseUrl + path).toURL().openConnection() as java.net.HttpURLConnection
        connection.requestMethod = method
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return connection
    }
}
```

Use the helper to replace `URL(server.url)` and `URL("${server.url}/path")` construction.

- [x] **Step 3: Run targeted tests and inspect warnings**

Run:

```bash
./gradlew :fixthis-mcp:test --warning-mode all
```

Expected: PASS and no project-owned warnings for `createTempDir` or `URL(String)`.

Validation notes:
- `./gradlew :fixthis-mcp:test --warning-mode all`: PASS.
- Warning log scan: PASS, no project-owned `createTempDir` or `URL(String)` warnings found.
- `git diff --check`: PASS.

- [x] **Step 4: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp docs/superpowers/plans/2026-05-08-project-improvement-stabilization-implementation.md
git commit -m "test: remove mcp deprecation warnings"
```

HANDOFF CHECKPOINT:
- Task 3 test cleanup completed in the integration worktree only.
- Replaced affected project-owned `createTempDir` test calls with local `tempDir` helpers.
- Replaced console-test `URL(...)` construction with `ConsoleHttpTestClient`.
- No production code changed.
- Targeted MCP tests passed with `--warning-mode all`.
- Warning output was inspected for `createTempDir` and `URL(String)`.
- `git diff --check` passed.
- Next task remains Task 4; not started.

## Task 4: Implement Zero-Setup MCP Config Writers

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt`
- Create: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/AndroidSdkLocator.kt`
- Create: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/McpConfigEntry.kt`
- Create: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/AgentConfigWriter.kt`
- Create: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/CodexConfigWriter.kt`
- Create: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/ClaudeConfigWriter.kt`
- Create: tests under `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/`
- Modify: `README.md`
- Modify: `docs/mcp.md`
- Modify: `docs/design-zero-setup.md`
- Modify: `docs/troubleshooting.md`

- [x] **Step 1: Add writer model tests**

Create `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands/AgentConfigWriterTest.kt`:

```kotlin
package io.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigWriterTest {
    private val entry = McpConfigEntry(
        serverName = "fixthis",
        command = "/repo/fixthis-cli/build/install/fixthis/bin/fixthis",
        args = listOf("mcp", "--package", "io.beyondwin.fixthis.sample", "--project-dir", "/repo"),
        env = mapOf("ANDROID_HOME" to "/Users/kws/Library/Android/sdk"),
    )

    @Test
    fun codexMergeReplacesOnlyFixThisSection() {
        val current = """
            [mcp_servers.playwright]
            command = "npx"
            args = ["-y", "@playwright/mcp"]

            [mcp_servers.fixthis]
            command = "old"
        """.trimIndent()

        val merged = CodexConfigWriter().merge(current, entry)

        assertTrue(merged.contains("[mcp_servers.playwright]"))
        assertTrue(merged.contains("[mcp_servers.fixthis]"))
        assertTrue(merged.contains("[mcp_servers.fixthis.env]"))
        assertTrue(merged.contains("ANDROID_HOME = \"/Users/kws/Library/Android/sdk\""))
        assertFalse(merged.contains("command = \"old\""))
    }

    @Test
    fun claudeMergePreservesOtherServers() {
        val current = """{"mcpServers":{"playwright":{"command":"npx","args":["-y","@playwright/mcp"]}}}"""

        val merged = ClaudeConfigWriter().merge(current, entry)

        assertTrue(merged.contains("\"playwright\""))
        assertTrue(merged.contains("\"fixthis\""))
        assertTrue(merged.contains("\"ANDROID_HOME\""))
    }
}
```

- [x] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew :fixthis-cli:test --tests '*AgentConfigWriterTest'
```

Expected: FAIL because writer classes do not exist.

RED notes:
- On session start, Task 4 writer files and `AgentConfigWriterTest.kt` already existed as uncommitted work in this worktree, so the requested missing-class RED could not be reproduced from the live state.
- Initial exact command `./gradlew :fixthis-cli:test --tests '*AgentConfigWriterTest'`: PASS because implementation files were already present.
- Additional focused RED added for a separated stale Codex env section: FAIL, `AgentConfigWriterTest > codexMergeRemovesSeparatedStaleFixThisEnv`, `4 tests completed, 1 failed`, assertion at `AgentConfigWriterTest.kt:81`.
- After updating `CodexConfigWriter`, the same focused command passed.
- Quality-review RED: `./gradlew :fixthis-cli:test --tests '*AgentConfigWriterTest' --tests '*SetupCommandTest'` failed with 4 expected failures for commented Codex headers, quoted Codex headers, invalid server names, and target-all partial writes on invalid Claude JSON.
- Quality-review GREEN: the same focused command passed after server-name validation, normalized Codex table matching, and target-all preflight planning.
- Atomic-write RED: `./gradlew :fixthis-cli:test --tests '*SetupCommandTest.atomicWriterLeavesExistingConfigWhenMoveFails'` failed at compile time because `AtomicConfigFileWriter` did not exist.
- Atomic-write GREEN: the same focused command passed after adding same-directory temp-file write, atomic move with fallback, and temp cleanup on failure.
- Final-review RED: after adding single-quoted Codex table coverage, `./gradlew :fixthis-cli:test --tests '*AgentConfigWriterTest'` failed with 2 expected failures for `[mcp_servers.'fixthis']` replacement and stale `[mcp_servers.'fixthis'.env]` removal.
- Final-review GREEN: `CodexConfigWriter` now normalizes TOML literal single-quoted dotted key segments the same as bare keys for target matching; `./gradlew :fixthis-cli:test --tests '*AgentConfigWriterTest'` passed.
- Final validation: `./gradlew :fixthis-cli:test --tests '*AgentConfigWriterTest' --tests '*SetupCommandTest'` passed; `git diff --check c9139ba..HEAD` passed.
- Durability/doc review RED: added focused `AtomicConfigFileWriter` coverage for temp-file force-before-move and parent-directory force-after-move. Initial focused test command failed at compile time because the writer did not expose force hooks; after adding hooks, the unsupported parent-directory force fallback test failed until fallback handling wrapped the force call itself.
- Durability/doc review GREEN: `AtomicConfigFileWriter` now writes UTF-8 bytes to a same-directory temp file, forces the temp file before move, preserves atomic move with `AtomicMoveNotSupportedException` fallback, and best-effort forces parent-directory metadata after a successful move without corrupting/truncating the moved config when directory fsync is unsupported.
- Durability/doc review docs fix: `docs/design-zero-setup.md` success output now matches implemented `SetupCommand` output (`Wrote ... MCP config: ...`) and no longer advertises summary rows, checkmarks, or restart guidance.

- [x] **Step 3: Add core writer model and interface**

Create `McpConfigEntry.kt`:

```kotlin
package io.beyondwin.fixthis.cli.commands

internal data class McpConfigEntry(
    val serverName: String,
    val command: String,
    val args: List<String>,
    val env: Map<String, String> = emptyMap(),
)
```

Create `AgentConfigWriter.kt`:

```kotlin
package io.beyondwin.fixthis.cli.commands

import java.io.File

internal interface AgentConfigWriter {
    val name: String
    fun configFile(projectRoot: File, userHome: File = File(System.getProperty("user.home"))): File
    fun merge(current: String?, entry: McpConfigEntry): String
}
```

- [x] **Step 4: Implement Codex writer**

Create `CodexConfigWriter.kt`:

```kotlin
package io.beyondwin.fixthis.cli.commands

import java.io.File

internal class CodexConfigWriter : AgentConfigWriter {
    override val name: String = "codex"

    override fun configFile(projectRoot: File, userHome: File): File =
        userHome.resolve(".codex/config.toml")

    override fun merge(current: String?, entry: McpConfigEntry): String {
        val rendered = render(entry)
        val lines = current.orEmpty().lineSequence().toMutableList()
        val header = "[mcp_servers.${entry.serverName}]"
        val start = lines.indexOfFirst { it.trim() == header }
        if (start < 0) {
            return buildString {
                val existing = current.orEmpty().trimEnd()
                if (existing.isNotBlank()) appendLine(existing)
                if (existing.isNotBlank()) appendLine()
                append(rendered)
            }
        }
        var end = start + 1
        while (end < lines.size && !lines[end].trimStart().startsWith("[mcp_servers.")) {
            end += 1
        }
        val replacement = rendered.lines()
        val updated = lines.take(start) + replacement + lines.drop(end)
        return updated.joinToString("\n").trimEnd() + "\n"
    }

    private fun render(entry: McpConfigEntry): String =
        buildString {
            appendLine("[mcp_servers.${entry.serverName}]")
            appendLine("command = ${entry.command.tomlString()}")
            appendLine("args = [${entry.args.joinToString(", ") { it.tomlString() }}]")
            if (entry.env.isNotEmpty()) {
                appendLine()
                appendLine("[mcp_servers.${entry.serverName}.env]")
                entry.env.toSortedMap().forEach { (key, value) ->
                    appendLine("${key} = ${value.tomlString()}")
                }
            }
        }.trimEnd() + "\n"

    private fun String.tomlString(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
```

- [x] **Step 5: Implement Claude writer**

Create `ClaudeConfigWriter.kt`:

```kotlin
package io.beyondwin.fixthis.cli.commands

import io.beyondwin.fixthis.cli.fixThisJson
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

internal class ClaudeConfigWriter : AgentConfigWriter {
    override val name: String = "claude"

    override fun configFile(projectRoot: File, userHome: File): File =
        projectRoot.resolve(".claude/settings.json")

    override fun merge(current: String?, entry: McpConfigEntry): String {
        val root = current
            ?.takeIf { it.isNotBlank() }
            ?.let { fixThisJson.parseToJsonElement(it).jsonObject }
            ?: JsonObject(emptyMap())
        val existingServers = root["mcpServers"]?.jsonObject ?: JsonObject(emptyMap())
        val mergedServers = JsonObject(existingServers + (entry.serverName to entry.toClaudeJson()))
        val mergedRoot = JsonObject(root + ("mcpServers" to mergedServers))
        return fixThisJson.encodeToString(JsonObject.serializer(), mergedRoot) + "\n"
    }

    private fun McpConfigEntry.toClaudeJson(): JsonElement =
        buildJsonObject {
            put("command", JsonPrimitive(command))
            put("args", buildJsonArray { args.forEach { add(JsonPrimitive(it)) } })
            if (env.isNotEmpty()) {
                put("env", buildJsonObject {
                    env.toSortedMap().forEach { (key, value) -> put(key, JsonPrimitive(value)) }
                })
            }
        }
}
```

- [x] **Step 6: Add Android SDK locator**

Create `AndroidSdkLocator.kt`:

```kotlin
package io.beyondwin.fixthis.cli.commands

import java.io.File

internal object AndroidSdkLocator {
    data class SdkLocation(val home: File, val adb: File, val source: String)

    fun find(env: Map<String, String> = System.getenv(), userHome: File = File(System.getProperty("user.home"))): SdkLocation? =
        candidates(env, userHome).firstOrNull { it.adb.isFile && it.adb.canExecute() }

    private fun candidates(env: Map<String, String>, userHome: File): Sequence<SdkLocation> = sequence {
        yieldSdk(env["ANDROID_HOME"], "ANDROID_HOME")
        yieldSdk(env["ANDROID_SDK_ROOT"], "ANDROID_SDK_ROOT")
        yieldSdk(userHome.resolve("Library/Android/sdk").absolutePath, "macOS default")
        yieldSdk(userHome.resolve("Android/Sdk").absolutePath, "Linux default")
        env["LOCALAPPDATA"]?.let { yieldSdk(File(it, "Android/Sdk").absolutePath, "Windows default") }
        yieldSdk(userHome.resolve(".android/sdk").absolutePath, "fallback")
    }

    private suspend fun SequenceScope<SdkLocation>.yieldSdk(path: String?, source: String) {
        val home = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        yield(SdkLocation(home = home, adb = home.resolve("platform-tools/adb"), source = source))
    }
}
```

- [x] **Step 7: Wire SetupCommand flags**

Modify `SetupCommand.kt` to add:

```kotlin
private val write by option("--write", help = "Write MCP config to agent settings files").flag(default = false)
private val dryRun by option("--dry-run", help = "Print planned writes without modifying files").flag(default = false)
private val target by option("--target", help = "Agent config target").choice("codex", "claude", "all").default("all")
private val serverName by option("--server-name", help = "MCP server name to write").default("fixthis")
```

Build `McpConfigEntry` from the resolved package, project root, detected SDK, and current `McpExecutableLocator`. Keep `buildMcpClientConfig(resolvedPackage, root)` output unchanged when `write == false`.

- [x] **Step 8: Run validation**

Run:

```bash
./gradlew :fixthis-cli:test
./gradlew :fixthis-cli:installDist
fixthis-cli/build/install/fixthis/bin/fixthis setup --package io.beyondwin.fixthis.sample --project-dir "$PWD"
fixthis-cli/build/install/fixthis/bin/fixthis setup --package io.beyondwin.fixthis.sample --project-dir "$PWD" --write --target codex --dry-run
git diff --check
```

Expected: tests pass, default setup still prints JSON, dry-run prints target path and rendered config without writing.

Validation notes:
- Atomic-write focused revalidation `./gradlew :fixthis-cli:test --tests '*SetupCommandTest.atomicWriterLeavesExistingConfigWhenMoveFails'`: PASS.
- Quality-review focused revalidation `./gradlew :fixthis-cli:test --tests '*AgentConfigWriterTest' --tests '*SetupCommandTest'`: PASS.
- `./gradlew :fixthis-cli:test`: PASS.
- `./gradlew :fixthis-cli:installDist`: PASS.
- `fixthis-cli/build/install/fixthis/bin/fixthis setup --package io.beyondwin.fixthis.sample --project-dir "$PWD"`: PASS; default JSON output remained `command = "fixthis"` with `args = ["mcp", ...]`.
- `fixthis-cli/build/install/fixthis/bin/fixthis setup --package io.beyondwin.fixthis.sample --project-dir "$PWD" --write --target codex --dry-run`: PASS; printed target path `/Users/kws/.codex/config.toml` and rendered merged config with `ANDROID_HOME`.
- `git diff --check`: PASS.
- Durability/doc review validation `./gradlew :fixthis-cli:test --tests '*SetupCommandTest' --tests '*AgentConfigWriterTest'`: PASS.
- Durability/doc review validation `./gradlew :fixthis-cli:installDist`: PASS.
- Durability/doc review validation `git diff --check c9139ba..HEAD`: PASS before amending the Task 4 commit.

HANDOFF CHECKPOINT:
- Task 4 worked only in the shared worktree.
- Branch stayed `codex/project-improvement-stabilization`.
- Default setup JSON behavior is unchanged.
- `--write --target all` now preflights all merges before writing.
- Config writes now use same-directory temp files, temp-file force before move, atomic move when supported with non-atomic fallback, and best-effort parent-directory force after successful move.
- Codex writer handles commented, double-quoted, single-quoted, and separated target tables, including stale env sections.
- Invalid MCP server names fail with `CliktError` before writing.
- Claude writer merges under `mcpServers` and preserves other servers.
- SDK detection adds `ANDROID_HOME` only when adb is executable.
- Zero-setup design docs show the implemented `Wrote ... MCP config: ...` success output.
- `local.properties` remains ignored and untracked.
- Task 5 was not started.

- [x] **Step 9: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/commands README.md docs/mcp.md docs/design-zero-setup.md docs/troubleshooting.md docs/superpowers/plans/2026-05-08-project-improvement-stabilization-implementation.md
git commit -m "feat: add zero setup mcp config writers"
```

## Task 5: Add Connected Smoke Harness

**Files:**
- Create: `scripts/fixthis-smoke.sh`
- Modify: `CONTRIBUTING.md`
- Modify: `docs/troubleshooting.md`

- [x] **Step 1: Add host-only smoke script**

Create `scripts/fixthis-smoke.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="io.beyondwin.fixthis.sample"
HOST_ONLY="false"
NO_BUILD="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --package)
      PACKAGE_NAME="$2"
      shift 2
      ;;
    --host-only)
      HOST_ONLY="true"
      shift
      ;;
    --no-build)
      NO_BUILD="true"
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="${ROOT_DIR}/.fixthis/smoke-reports"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
REPORT_MD="${REPORT_DIR}/${TIMESTAMP}.md"
mkdir -p "${REPORT_DIR}"

record() {
  printf '%s\n' "$1" | tee -a "${REPORT_MD}"
}

cd "${ROOT_DIR}"
record "# FixThis Smoke Report"
record ""
record "- Package: ${PACKAGE_NAME}"
record "- Host only: ${HOST_ONLY}"
record "- No build: ${NO_BUILD}"
record "- Java: $(java -version 2>&1 | head -1)"
record "- ADB: $(command -v adb || true)"

if [[ "${NO_BUILD}" != "true" ]]; then
  ./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
fi

if [[ "${HOST_ONLY}" == "true" ]]; then
  record "- Result: SKIPPED_HOST_ONLY"
  exit 0
fi

ADB_OUTPUT="$(adb devices -l || true)"
record ""
record "## adb devices -l"
record '```text'
record "${ADB_OUTPUT}"
record '```'

if ! printf '%s\n' "${ADB_OUTPUT}" | grep -q $'\tdevice'; then
  if printf '%s\n' "${ADB_OUTPUT}" | grep -q "unauthorized"; then
    record "- Result: SKIPPED_UNAUTHORIZED_DEVICE"
  elif printf '%s\n' "${ADB_OUTPUT}" | grep -q "offline"; then
    record "- Result: SKIPPED_OFFLINE_DEVICE"
  else
    record "- Result: SKIPPED_NO_DEVICE"
  fi
  exit 0
fi

./gradlew :app:installDebug
fixthis-cli/build/install/fixthis/bin/fixthis doctor --package "${PACKAGE_NAME}" --project-dir "${ROOT_DIR}" | tee -a "${REPORT_MD}"
record "- Result: PASS"
```

- [x] **Step 2: Make the script executable**

Run:

```bash
chmod +x scripts/fixthis-smoke.sh
```

- [x] **Step 3: Run host-only validation**

Run:

```bash
scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample --host-only
```

Expected: report is created under `.fixthis/smoke-reports/` and result is `SKIPPED_HOST_ONLY`.

- [x] **Step 4: Document smoke categories**

Update `CONTRIBUTING.md` and `docs/troubleshooting.md` with the command and categories:

```markdown
Connected smoke:

```bash
scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample
```

Expected connected-device skip categories include `SKIPPED_NO_DEVICE`, `SKIPPED_UNAUTHORIZED_DEVICE`, `SKIPPED_OFFLINE_DEVICE`, and `SKIPPED_HOST_ONLY`.
```

- [x] **Step 5: Commit**

```bash
git add scripts/fixthis-smoke.sh CONTRIBUTING.md docs/troubleshooting.md
git commit -m "test: add connected smoke harness"
```

Task 5 validation notes:

- `bash -n scripts/fixthis-smoke.sh`: PASS.
- `scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample --host-only`: PASS, report `/Users/kws/.config/superpowers/worktrees/FixThis/project-improvement-stabilization/.fixthis/smoke-reports/20260507T190913Z.md`, result `SKIPPED_HOST_ONLY`.
- Review cleanup validation: `scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample --host-only --no-build` passed with `SKIPPED_HOST_ONLY` and wrote suffixed reports under `.fixthis/smoke-reports/`; two parallel runs in the same second wrote distinct JSON reports `20260507T191410Z-pid14289-1500025148.json` and `20260507T191410Z-pid14309-240865872.json`.
- Connected-device smoke was not run for Task 5 because no authorized, unlocked device was established before validation. Residual risk: ready-device install, launch, lockscreen detection, and `fixthis doctor` paths still need one connected-device run and may report `SKIPPED_NO_DEVICE`, `SKIPPED_UNAUTHORIZED_DEVICE`, `SKIPPED_OFFLINE_DEVICE`, `SKIPPED_LOCKED_DEVICE`, `SKIPPED_WIRELESS_ADB_LOST`, or `SKIPPED_MULTIPLE_DEVICES` depending on local ADB state.

## Task 6: Extract ConsoleConnectionService From FeedbackSessionService

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/ConsoleConnectionService.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/ConsoleConnectionServiceTest.kt`

- [x] **Step 1: Add focused connection service tests**

RED recorded: `./gradlew :fixthis-mcp:test --tests '*ConsoleConnectionServiceTest'`
failed in `:fixthis-mcp:compileTestKotlin` because `ConsoleConnectionService`
and its extracted methods did not exist yet. An earlier attempt landed the test
file in the original checkout and produced a no-tests-found result; that file
was removed and the exact RED was rerun in this worktree.

Create `ConsoleConnectionServiceTest.kt` with tests covering:

```kotlin
@Test
fun noReadyDevicesMapsToCheckPhone() = runTest {
    val service = connectionService(
        devices = listOf(AdbDevice("device-1", "unauthorized")),
    )

    val status = service.connectionStatus(session())

    assertEquals(ConsoleConnectionState.CHECK_PHONE, status.state)
    assertEquals(ConsoleConnectionAction.TRY_AGAIN, status.primaryAction)
}

@Test
fun singleReadyDeviceWithoutSelectionMapsToWelcome() = runTest {
    val service = connectionService(
        devices = listOf(AdbDevice("device-1", "device")),
    )

    val status = service.connectionStatus(session())

    assertEquals(ConsoleConnectionState.WELCOME, status.state)
    assertEquals(ConsoleConnectionAction.START, status.primaryAction)
}

@Test
fun launchAppSelectsOnlyReadyDeviceForWelcomeState() = runTest {
    val bridge = FakeFixThisBridge(devicesOverride = listOf(AdbDevice("device-1", "device")))
    val service = ConsoleConnectionService(bridge)

    service.launchAppForSession(session())

    assertEquals(listOf("io.beyondwin.fixthis.sample"), bridge.launchedPackages)
    assertEquals("device-1", bridge.selectedDeviceSerial())
}
```

Use local helper methods in the test file for `session()` and `connectionService(devices = listOf(AdbDevice("device-1", "device")))`.

- [x] **Step 2: Extract connection logic**

Move these responsibilities from `FeedbackSessionService` into `ConsoleConnectionService`:

- `devices()`
- `selectedDeviceSerial()`
- `selectDevice(serial)`
- `disconnectDevice()`
- `connectionStatus(session)`
- `launchAppForSession(session)`

Keep `FeedbackSessionService` public methods as delegating wrappers:

```kotlin
fun devices(): List<AdbDevice> = connectionService.devices()

fun selectedDeviceSerial(): String? = connectionService.selectedDeviceSerial()

fun selectDevice(serial: String) = connectionService.selectDevice(serial)

fun disconnectDevice() = connectionService.disconnectDevice()

suspend fun connectionStatus(): ConsoleConnectionStatus =
    connectionService.connectionStatus(currentSession())

suspend fun launchAppForCurrentSession(): ConsoleConnectionStatus =
    connectionService.launchAppForSession(currentSession())
```

- [x] **Step 3: Run validation**

```bash
./gradlew :fixthis-mcp:test --tests '*ConsoleConnectionServiceTest' --tests '*FeedbackSessionServiceTest'
```

Expected: PASS.

Actual: PASS on 2026-05-08 with:

```bash
./gradlew :fixthis-mcp:test --tests '*ConsoleConnectionServiceTest' --tests '*FeedbackSessionServiceTest'
```

- [x] **Step 4: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/ConsoleConnectionService.kt fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/ConsoleConnectionServiceTest.kt
git commit -m "refactor: extract console connection service"
```

## Task 7: Extract Preview, Draft, And Target Evidence Services

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/PreviewCaptureService.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`
- Test: new focused service tests under `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/`

- [x] **Step 1: Extract PreviewCaptureService with existing behavior pinned**

Create tests that pin:

- live preview capture writes preview cache
- exact preview screenshot lookup works
- evicted preview returns the same error currently used by `FeedbackSessionService`

Then move preview methods and helper code into `PreviewCaptureService`. Keep wrappers in `FeedbackSessionService`:

```kotlin
suspend fun capturePreview(sessionId: String): FeedbackPreviewSnapshot =
    previewCaptureService.capturePreview(store.getSession(sessionId))

fun previewScreenshotFile(sessionId: String, previewId: String): File =
    previewCaptureService.previewScreenshotFile(sessionId, previewId)
```

RED/GREEN: RED was generated by adding `PreviewCaptureServiceTest` before the service existed; `:fixthis-mcp:compileTestKotlin` failed on unresolved `PreviewCaptureService`/`TargetEvidenceService` references. GREEN passed with `./gradlew :fixthis-mcp:test --tests '*PreviewCaptureServiceTest' --tests '*TargetEvidenceServiceTest' --tests '*FeedbackDraftServiceTest'`. Note: exact "evicted preview returns error" behavior conflicts with the existing full-suite console contract `previewIdScreenshotRouteServesEvictedPreviewPngFromDiskArtifact`; the focused test therefore pins the existing evicted-preview disk fallback and adds a meaningful `PREVIEW_NOT_FOUND` test for previews missing from both live cache and disk.

- [x] **Step 2: Extract TargetEvidenceService with focused tests**

Create tests that pin:

- node target evidence includes identity hint when strict `comp:` tag exists
- visual area evidence remains low confidence
- source candidate warnings are preserved

Move source matching and `TargetEvidence` construction helper methods into `TargetEvidenceService`.

RED/GREEN: RED was generated by adding `TargetEvidenceServiceTest` before the service existed; `:fixthis-mcp:compileTestKotlin` failed on unresolved `TargetEvidenceService` and `targetEvidenceFor` references. GREEN passed with the focused service test command above.

- [x] **Step 3: Extract FeedbackDraftService with focused tests**

Create tests that pin:

- blank pending comments are allowed only in explicit blank-save paths
- written pending comments are persisted before `Copy Prompt` or `Send Agent`
- preview promotion happens once per frozen preview save

Move `savePreviewFeedbackItems`, draft clearing, and draft item construction logic into `FeedbackDraftService`.

RED/GREEN: RED was generated by adding `FeedbackDraftServiceTest` before the service existed; `:fixthis-mcp:compileTestKotlin` failed on unresolved `FeedbackDraftService` and draft save/handoff methods. GREEN passed with the focused service test command above.

- [x] **Step 4: Run focused and full MCP tests**

```bash
./gradlew :fixthis-mcp:test
git diff --check
```

Expected: PASS and `FeedbackSessionService.kt` is a thin facade.

Validation: PASS on `./gradlew :fixthis-mcp:test --tests '*PreviewCaptureServiceTest' --tests '*TargetEvidenceServiceTest' --tests '*FeedbackDraftServiceTest' --tests '*FeedbackSessionServiceTest'`, PASS on `./gradlew :fixthis-mcp:test`, and PASS on `git diff --check`.

- [x] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session
git commit -m "refactor: split feedback session services"
```

Review-fix RED: `./gradlew :fixthis-mcp:test --tests '*FeedbackDraftServiceTest'` failed as expected after adding focused coverage for add-feedback validation short-circuiting and preview-specific missing-node errors.

Review-fix GREEN: `FeedbackDraftService.addFeedbackItem` now prevalidates comment, node existence, and bounds before source-index reads; preview draft saves pass the `preview` missing-node context through shared target validation.

Review-fix validation: PASS on `./gradlew :fixthis-mcp:test --tests '*FeedbackDraftServiceTest' --tests '*TargetEvidenceServiceTest' --tests '*FeedbackSessionServiceTest'`, PASS on `./gradlew :fixthis-mcp:test`, and PASS on `git diff --check 6080d08..HEAD`.

Final review-fix RED: added focused `FeedbackDraftServiceTest` coverage proving restarted/fallback preview saves reject missing-node, invalid-bounds, and blank-comment pending items before any source-index IO; the new test initially failed because `fallbackPreviewRecord` read source index before validation.

Final review-fix GREEN: fallback preview saves now prevalidate pending items with preview-specific missing-node context before constructing `fallbackPreviewRecord`, preserving cached preview save behavior while keeping invalid fallback items from incrementing `FakeFixThisBridge.readSourceIndexCount`.

## Task 8: Split FeedbackConsoleServer Into Route Families

**Files:**
- Create route and HTTP helper files under `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [x] **Step 1: Add ConsoleHttp helpers**

Create `ConsoleHttp.kt` with shared method, JSON, text, bytes, query, and body decode helpers. Move existing behavior without changing response status codes.

Required helper signatures:

```kotlin
internal fun HttpExchange.requireMethod(method: String, block: () -> Unit)
internal fun HttpExchange.queryParameter(name: String): String?
internal fun HttpExchange.queryBoolean(name: String): Boolean
internal fun HttpExchange.sendText(statusCode: Int, text: String, contentType: String)
internal fun HttpExchange.sendBytes(statusCode: Int, bytes: ByteArray, contentType: String)
internal fun HttpExchange.sendNoContent()
```

- [x] **Step 2: Add route interface and route table**

Create `ConsoleRoutes.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange

internal interface ConsoleRoute {
    fun matches(path: String): Boolean
    fun handle(exchange: HttpExchange)
}

internal class ConsoleRouteTable(private val routes: List<ConsoleRoute>) {
    fun handle(exchange: HttpExchange): Boolean {
        val route = routes.firstOrNull { it.matches(exchange.requestURI.path) } ?: return false
        route.handle(exchange)
        return true
    }
}
```

- [x] **Step 3: Move endpoint groups one at a time**

Move these groups in separate edits, running tests after each group:

- `SessionRoutes`
- `DeviceRoutes`
- `ConnectionRoutes`
- `PreviewRoutes`
- `FeedbackItemRoutes`
- `ArtifactRoutes`

Route paths and methods must stay identical to the current server.

- [x] **Step 4: Run route validation**

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest'
./gradlew :fixthis-mcp:test
```

Expected: PASS.

Task 8 notes:
- RED: added `routeTableDispatchesFirstMatchingRoute` before `ConsoleRoute`/`ConsoleRouteTable` existed; `./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest.routeTableDispatchesFirstMatchingRoute'` failed at `:fixthis-mcp:compileTestKotlin` with unresolved `ConsoleRouteTable`/`ConsoleRoute`.
- GREEN: after adding `ConsoleHttp.kt`, `ConsoleRoutes.kt`, and the route family files, `./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest.routeTableDispatchesFirstMatchingRoute'` passed.
- Validation PASS: `./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest'`.
- Validation PASS: `./gradlew :fixthis-mcp:test`.
- Validation PASS: `git diff --check`.

- [x] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "refactor: split feedback console routes"
```

## Task 9: Modularize Browser Console JavaScript

**Files:**
- Create JS modules under `fixthis-mcp/src/main/console/`
- Create: `scripts/build-console-assets.mjs`
- Create: `scripts/console-browser-smoke.mjs`
- Modify generated: `fixthis-mcp/src/main/resources/console/app.js`
- Modify tests as needed under `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/`

- [x] **Step 1: Add deterministic asset build script**

Create `scripts/build-console-assets.mjs`:

```javascript
#!/usr/bin/env node
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sources = [
  'state.js',
  'api.js',
  'connection.js',
  'devices.js',
  'preview.js',
  'annotations.js',
  'history.js',
  'prompt.js',
  'rendering.js',
  'shortcuts.js',
  'main.js',
];

const output = sources
  .map((name) => {
    const path = resolve(root, 'fixthis-mcp/src/main/console', name);
    return `// ${name}\n${readFileSync(path, 'utf8').trimEnd()}\n`;
  })
  .join('\n');

const target = resolve(root, 'fixthis-mcp/src/main/resources/console/app.js');
if (process.argv.includes('--check')) {
  if (!existsSync(target)) {
    console.error('Generated console app.js is missing. Run node scripts/build-console-assets.mjs.');
    process.exit(1);
  }
  const current = readFileSync(target, 'utf8');
  if (current !== output) {
    console.error('Generated console app.js is out of date. Run node scripts/build-console-assets.mjs.');
    process.exit(1);
  }
  process.exit(0);
}

mkdirSync(dirname(target), { recursive: true });
writeFileSync(target, output);
```

- [x] **Step 2: Split app.js into modules without behavior changes**

Move code by responsibility:

- state variables and constants into `state.js`
- `requestJson` and clipboard helpers into `api.js`
- connection card and stale state into `connection.js`
- device selection into `devices.js`
- preview rendering and polling into `preview.js`
- annotation state and payload building into `annotations.js`
- session lists and sent history into `history.js`
- prompt formatting and prompt actions into `prompt.js`
- DOM render functions into `rendering.js`
- global shortcuts into `shortcuts.js`
- event binding and boot into `main.js`

Keep globals inside the same IIFE pattern if the current `app.js` uses one. Do not introduce npm dependencies.

- [x] **Step 3: Generate and check assets**

Run:

```bash
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
for file in fixthis-mcp/src/main/console/*.js fixthis-mcp/src/main/resources/console/app.js scripts/build-console-assets.mjs scripts/console-browser-smoke.mjs; do node --check "$file"; done
npx --yes --package=playwright -- node scripts/console-browser-smoke.mjs
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest'
```

Expected: PASS.

- RED: Added `generatedConsoleAppMatchesConsoleSourceModules`; it failed because `fixthis-mcp/src/main/console/state.js` did not exist.
- GREEN: Added deterministic source modules and `scripts/build-console-assets.mjs`, regenerated `fixthis-mcp/src/main/resources/console/app.js`, and the focused asset-contract test passed.
- Validation:
  - `node scripts/build-console-assets.mjs --check`: PASS.
  - `for file in fixthis-mcp/src/main/console/*.js fixthis-mcp/src/main/resources/console/app.js; do node --check "$file"; done`: PASS.
  - `./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest.generatedConsoleAppMatchesConsoleSourceModules'`: PASS.
  - `./gradlew :fixthis-mcp:test`: PASS.
  - `git diff --check`: PASS.
- Review-fix RED:
  - Missing generated target reproduction failed before the fix with `ENOENT` from `readFileSync(target)`, proving write mode could not regenerate `fixthis-mcp/src/main/resources/console/app.js` when absent.
  - Browser smoke command failed before the fix with `MODULE_NOT_FOUND` because `scripts/console-browser-smoke.mjs` did not exist.
- Review-fix GREEN:
  - `scripts/build-console-assets.mjs` now reads the generated target only in `--check`, reports a missing target as check failure, and creates the output directory before writing, so write mode can regenerate a missing `app.js`.
  - Added `scripts/console-browser-smoke.mjs`, a local deterministic Playwright smoke that serves committed console HTML/CSS/JS with fake API responses and exercises ready/reconnect/stale connection behavior, device selection, select/annotate flow, agent handoff, and session history switching.
  - `npx --yes --package=playwright -- node scripts/console-browser-smoke.mjs`: PASS.
- Final quality-review fix:
  - Tightened the browser smoke fake `/api/connection` selected-device payload to the real `ConsoleConnectionDevice` schema (`serial`, `state`, `label`, `selected`) while keeping `/api/devices` on the `ConsoleDevice` schema.
  - Added smoke assertions for the selected connection-device schema, the rendered device label after connection refresh, invalid select-mode tap navigation returning 400, and successful select-mode tap navigation returning HTTP 200 before counting the call.
  - Focused revalidation after this fix: `node --check scripts/console-browser-smoke.mjs`: PASS; `npx --yes --package=playwright -- node scripts/console-browser-smoke.mjs`: PASS.
- Final browser-smoke contract fixes:
  - `/api/connection` now returns connection-schema `devices`, and the console device label helper accepts that schema's `label` field.
  - The fake `/api/items/batch` validates the real save request shape and rejects invalid payloads before mutating session state; smoke covers missing `previewId`.
  - The fake `/api/navigation` rejects unsupported fields from the real route allowlist before counting navigation; smoke covers unsupported fields and bad coordinates.
  - Smoke now exercises Copy Prompt before Send Agent with a Playwright clipboard stub and verifies both prompts include the annotation comment.
- Task 9 review-failure fix:
  - Updated the stale console HTML contract test to assert the real connection-device fallback order (`label` first) while preserving normal device fallback through Wi-Fi ADB serial shortening.
  - Focused validation after this fix: `./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest.consoleHtmlShortensWifiAdbSerialsForNormalDeviceLabels'`: PASS.
  - Console suite validation after this fix: `./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest'`: PASS.
  - Asset/smoke validation after this fix: `node scripts/build-console-assets.mjs --check`: PASS; `npx --yes --package=playwright -- node scripts/console-browser-smoke.mjs`: PASS.
  - Diff validation after this fix: `git diff --check 10ba965..HEAD`: PASS.

- [x] **Step 4: Commit**

```bash
git add scripts/build-console-assets.mjs scripts/console-browser-smoke.mjs fixthis-mcp/src/main/console fixthis-mcp/src/main/resources/console/app.js fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console
git commit -m "refactor: modularize console javascript assets"
```

## Task 10: Add Source Index v2 Typed Signals

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceIndex.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt`
- Modify: `fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt`
- Modify: `fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt`

- [ ] **Step 1: Add model tests for additive decode**

Add a test in `GenerateFixThisSourceIndexTaskTest.kt` or a core source-index test that decodes v1 JSON without `signals` and v2 JSON with `signals`.

- [ ] **Step 2: Add source signal models**

Extend `SourceIndex.kt`:

```kotlin
@Serializable
data class SourceSignal(
    val kind: SourceSignalKind,
    val value: String,
    val confidenceWeight: Double,
)

@Serializable
enum class SourceSignalKind {
    COMPOSABLE_SYMBOL,
    UI_TEXT,
    STRING_RESOURCE,
    TEST_TAG,
    STRICT_COMP_TEST_TAG,
    CONTENT_DESCRIPTION,
    ROLE,
    ACTIVITY_NAME,
    ARBITRARY_STRING_LITERAL,
}
```

Add fields to `SourceIndexEntry`:

```kotlin
val signals: List<SourceSignal> = emptyList(),
val packageName: String? = null,
val className: String? = null,
```

- [ ] **Step 3: Emit typed signals in the Gradle task**

In `GenerateFixThisSourceIndexTask.kt`, update `SourceIndexEntryBuilder` so each existing v1 list still writes, and each match also adds a `SourceSignal`.

Rules:

- `Text("Pay now")` -> `UI_TEXT`
- XML `<string>` value -> `UI_TEXT` and `STRING_RESOURCE`
- `testTag("comp:Name:variant")` -> `STRICT_COMP_TEST_TAG` and `TEST_TAG`
- other `testTag` -> `TEST_TAG`
- `contentDescription` -> `CONTENT_DESCRIPTION`
- quoted literals not in a recognized UI call -> `ARBITRARY_STRING_LITERAL`

- [ ] **Step 4: Adjust source matching confidence**

In `SourceMatcher.kt`, prefer `signals` when present. Lower the weight of `ARBITRARY_STRING_LITERAL` relative to `UI_TEXT`, `TEST_TAG`, and `CONTENT_DESCRIPTION`. Preserve current matching behavior when `signals` is empty.

- [ ] **Step 5: Run validation**

```bash
./gradlew :fixthis-gradle-plugin:test :fixthis-compose-core:test :fixthis-mcp:test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt fixthis-gradle-plugin/src/test/kotlin/io/beyondwin/fixthis/gradle/GenerateFixThisSourceIndexTaskTest.kt
git commit -m "feat: add typed source index signals"
```

## Task 11: Add Sample Evidence Coverage Fixture

**Files:**
- Create: `sample/fixthis-coverage.json`
- Modify: `sample/src/androidTest/java/io/beyondwin/fixthis/sample/SampleAppSmokeTest.kt`
- Modify: `README.md`

- [ ] **Step 1: Add coverage fixture**

Create `sample/fixthis-coverage.json`:

```json
{
  "schemaVersion": "1.0",
  "applicationId": "io.beyondwin.fixthis.sample",
  "scenes": [
    { "name": "strict_comp_tags", "expectedTags": ["comp:StudioHeader:root", "comp:HomePrimaryAction:primary", "comp:MetricCard:summary"] },
    { "name": "form_inputs", "expectedText": ["Queue", "Describe the issue"] },
    { "name": "dropdown_menu", "expectedText": ["Project", "Priority"] },
    { "name": "dialog", "expectedText": ["Close project"] },
    { "name": "canvas_visual_area", "expectedContentDescriptions": ["Semantic signal timeline"] },
    { "name": "disabled_controls", "expectedText": ["blocked state"] },
    { "name": "long_text", "expectedText": ["weak-semantics"] }
  ]
}
```

Adjust text values only to match exact strings already present in the sample app. Do not remove a coverage category.

- [ ] **Step 2: Add Android smoke assertions**

Extend `SampleAppSmokeTest.kt` to assert the core tags and visible scene labels listed in the fixture. Use existing Compose test APIs such as `onNodeWithText`, `onNodeWithTag`, and `onAllNodesWithTag`.

- [ ] **Step 3: Run validation**

```bash
./gradlew :app:assembleDebug :fixthis-gradle-plugin:test
```

If a connected device is available, run:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Expected: assemble and Gradle plugin tests pass. Connected test may be SKIPPED with a device category.

- [ ] **Step 4: Commit**

```bash
git add sample/fixthis-coverage.json sample/src/androidTest/java/io/beyondwin/fixthis/sample/SampleAppSmokeTest.kt README.md
git commit -m "test: add sample evidence coverage fixture"
```

## Task 12: Harden Local Console Mutations And Add Artifact Cleanup

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`
- Modify or create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleHttp.kt`
- Modify: `fixthis-mcp/src/main/resources/console/app.js`
- Modify JS source modules if Task 9 has landed
- Modify: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/Main.kt`
- Create: `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/CleanCommand.kt`
- Test: CLI and MCP console tests
- Modify: `docs/privacy.md`
- Modify: `docs/troubleshooting.md`

- [ ] **Step 1: Add console token tests**

Add tests to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun mutatingApiRequiresConsoleToken() {
    val server = server()
    try {
        server.start()
        val connection = java.net.URI("${server.url}/api/items/draft").toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "DELETE"

        assertEquals(403, connection.responseCode)
    } finally {
        server.stop()
    }
}
```

Add a companion test proving the browser-served token allows mutation through the shared JS request helper or a test-only exposed token accessor.

- [ ] **Step 2: Implement token and origin guard**

Generate a token in `FeedbackConsoleServer` at construction:

```kotlin
private val consoleToken: String = java.util.UUID.randomUUID().toString()
```

Require it for mutating `/api/*` methods:

```kotlin
private fun HttpExchange.requireConsoleMutationAllowed(token: String) {
    val origin = requestHeaders.getFirst("Origin")
    if (origin != null && !origin.startsWith("http://127.0.0.1:") && !origin.startsWith("http://localhost:")) {
        throw FeedbackConsoleHttpException(403, "Forbidden origin")
    }
    val supplied = requestHeaders.getFirst("X-FixThis-Console-Token")
    if (supplied != token) {
        throw FeedbackConsoleHttpException(403, "Missing console token")
    }
}
```

Inject the token into HTML through the existing asset render path and update the JS `requestJson` helper to send `X-FixThis-Console-Token` on mutating requests.

- [ ] **Step 3: Add clean command tests**

Create `CleanCommandTest.kt` to verify dry-run output for:

- `.fixthis/feedback-sessions`
- `.fixthis/preview-cache`
- `.fixthis/artifacts`
- `.fixthis/smoke-reports`

- [ ] **Step 4: Implement CleanCommand**

Add `fixthis clean --project-dir <path> [--dry-run] [--older-than-days <n>]`. First implementation deletes only known `.fixthis` local artifact directories and never deletes `.fixthis/project.json`.

- [ ] **Step 5: Run validation**

```bash
./gradlew :fixthis-cli:test :fixthis-mcp:test
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console fixthis-mcp/src/main/resources/console fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console docs/privacy.md docs/troubleshooting.md
git commit -m "feat: harden console mutations and clean artifacts"
```

## Task 13: Add Release Readiness Docs And MCP Compatibility Fixtures

**Files:**
- Create: `docs/release-readiness.md`
- Create: `SECURITY.md`
- Create or modify: `CHANGELOG.md`
- Modify: `README.md`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/McpProtocolTest.kt`

- [ ] **Step 1: Add release readiness doc**

Create `docs/release-readiness.md`:

```markdown
# Release Readiness

## Current Status

FixThis is ready for local debug use in this repository. External release requires the items below to be completed first.

## Required Before External Release

- Project owner selects and adds a root `LICENSE`.
- CI baseline is required on pull requests.
- `CONTRIBUTING.md` documents local verification.
- `CHANGELOG.md` records user-visible changes.
- `SECURITY.md` documents the local-first debug security model.
- README compatibility matrix is complete.

## Compatibility Matrix

| Surface | Supported |
| --- | --- |
| Android UI toolkit | Jetpack Compose debug builds |
| Release builds | Not supported |
| AccessibilityService | Not used |
| MCP workflow | Desktop stdio server plus localhost console |
| Screenshots | Local artifacts only |
| External AI API calls | Not made by FixThis |
```

- [ ] **Step 2: Add SECURITY.md**

Create `SECURITY.md`:

```markdown
# Security

FixThis is a local-first debug tool for Android Jetpack Compose apps.

## Supported Scope

- Debug builds only.
- Local desktop MCP process.
- ADB access to the developer's own device or emulator.
- Local feedback session artifacts under `.fixthis/`.

## Not Supported

- Production feedback collection.
- Remote console hosting.
- Inspecting other apps.
- Uploading screenshots or feedback by default.

## Reporting

Report security issues privately to the project owner before publishing details.
```

- [ ] **Step 3: Add CHANGELOG.md and README links**

Create `CHANGELOG.md` if it does not already exist:

```markdown
# Changelog

## Unreleased

- Added project stabilization implementation planning for console contract, CI, onboarding, MCP maintainability, evidence quality, local security, and release readiness.
```

Link `docs/release-readiness.md` and `SECURITY.md` from README `More detail`.

- [ ] **Step 4: Add MCP compatibility tests**

Extend `McpProtocolTest.kt` with fixture-style tests for:

- initialize
- notifications
- `tools/list`
- `tools/call`
- `resources/list`
- cancellation
- EOF behavior
- invalid message recovery

Keep existing protocol behavior unchanged.

- [ ] **Step 5: Run validation**

```bash
./gradlew :fixthis-mcp:test :fixthis-cli:test :fixthis-gradle-plugin:test
git diff --check
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add docs/release-readiness.md SECURITY.md CHANGELOG.md README.md fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/McpProtocolTest.kt
git commit -m "docs: add release readiness and mcp fixtures"
```

If the project owner has not chosen a license, do not add a root `LICENSE` in this task. Keep the release blocker explicit in `docs/release-readiness.md`.

## Task 14: Final Documentation Sync And Full Verification

**Files:**
- Modify docs touched by shipped behavior:
  - `README.md`
  - `docs/mcp.md`
  - `docs/design-feedback-console-ux.md`
  - `docs/design-zero-setup.md`
  - `docs/privacy.md`
  - `docs/troubleshooting.md`
  - `docs/project-improvement-proposals-2026-05-07.md`
  - `docs/superpowers/specs/2026-05-08-project-improvement-stabilization-design.md`
  - this implementation plan

- [ ] **Step 1: Sync documentation with actual behavior**

Update docs to match exactly what landed. Do not document unimplemented work as complete. Mark unimplemented release items as blockers or future work.

- [ ] **Step 2: Run docs checks**

Run:

```bash
git diff --check
rg -n "Add to Pending|Save snapshot|legacy pending label" README.md docs fixthis-mcp/src/main/resources/console/index.html
```

Expected: no stale current-contract wording in current docs. Historical docs may retain old terms only when clearly framed as historical.

- [ ] **Step 3: Run full verification**

Run:

```bash
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample --host-only
git diff --check
```

If a connected Android device is available, also run:

```bash
scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample
```

Expected: full local checks pass. Connected smoke may report an explicit SKIPPED category.

- [ ] **Step 4: Record verification results in this plan**

Add a short final verification note under this task with commands and PASS, FAIL, or SKIPPED results.

- [ ] **Step 5: Commit final docs sync**

```bash
git add README.md docs .github scripts fixthis-cli fixthis-compose-core fixthis-gradle-plugin fixthis-mcp sample CONTRIBUTING.md SECURITY.md CHANGELOG.md
git commit -m "docs: sync project stabilization results"
```

## Final Completion Criteria

- Console contract is documented and reflected in README, MCP docs, UX status, PRD, and tests.
- Required CI exists and mirrors local baseline checks.
- Project-owned test deprecation warnings for `createTempDir` and `URL(String)` are removed.
- `fixthis setup` default JSON behavior is preserved, and `--write --dry-run` works for Codex and Claude targets.
- Connected smoke harness creates local reports and explicit skip categories.
- MCP session service has focused collaborators for connection, preview, draft, and target evidence workflows.
- Console server route families are separated with stable endpoint paths and response codes.
- Console JS has source modules and a deterministic generated `app.js`.
- Source Index v2 typed signals are additive and confidence-aware.
- Sample evidence coverage fixture pins core validation scenes.
- Local console mutation endpoints require a console token and reject unexpected mutating origins.
- `fixthis clean` or equivalent artifact cleanup exists and preserves `.fixthis/project.json`.
- Release readiness docs and MCP compatibility fixtures exist.
- Full verification passes, with connected-device checks either passing or explicitly skipped with category.
