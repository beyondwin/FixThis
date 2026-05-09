# FixThis AI Discoverability — Documentation Spec

## Goal

Surface FixThis's existing first-class AI integration (`fixthis setup --write --target all`, MCP server, feedback console) to Claude Code and Codex users who currently have no obvious entry point. No new capabilities are added; every change makes existing capability discoverable.

## Framing

FixThis already auto-configures Claude Code and Codex (`ClaudeConfigWriter`, `CodexConfigWriter`). The gap is discoverability: there is no `AGENTS.md`, no `CLAUDE.md`, and the `fixthis setup --write` command is buried at line 109 of README. A first-time user opening this repo in Claude Code or Codex gets zero contextual help.

## Out of Scope / Prerequisites

- **LICENSE selection** — a human decision. Gradle artifact publishing is blocked until a license is chosen, but all documentation in this spec can ship before that.
- **Published Gradle plugin/sidekick coordinates** — placeholders until artifacts are released. Docs should note this limitation in the "External Projects" section.
- **CODEX.md** — `AGENTS.md` at repo root is the universal entry point supported by both Codex and Claude Code. A separate `CODEX.md` is not needed.
- **Code changes** — handled in a separate spec (`2026-05-09-fixthis-setup-polish-spec.md`).

## Deliverables

| File | Status | Action |
|------|--------|--------|
| `AGENTS.md` | Does not exist | Create |
| `CLAUDE.md` | Does not exist | Create |
| `README.md` | Exists | Add one section near top |
| `docs/project-overview.en.md` | Does not exist | Create (Korean original preserved) |

---

## 1. AGENTS.md — Verbatim Content

File location: repo root `AGENTS.md`

```markdown
# FixThis

FixThis adds a debug-only sidekick to Jetpack Compose Android apps and exposes captured UI context to AI coding agents over MCP. The Android app shows only a connection status pill; all annotation, selection, and handoff happen in a desktop browser console.

## Prerequisites

- JDK 21+
- Android SDK with ADB (`adb devices` shows connected device or emulator)
- Target app is a **debug build** (`debugImplementation` only; release builds are not supported)

## Quick Start

```bash
# Build CLI and MCP distributions
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist

# Configure your AI agent (run once per project)
fixthis-cli/build/install/fixthis/bin/fixthis setup \
  --package <applicationId> \
  --write \
  --target all
```

`--target all` writes two config entries:
- **Claude** → project-local `.claude/settings.json` (only affects this project)
- **Codex** → user-global `~/.codex/config.toml` (affects all Codex sessions)

Preview what will be written without touching any files:

```bash
fixthis setup --package <applicationId> --write --target all --dry-run
```

## Build and Test

```bash
# Run all unit tests
./gradlew :fixthis-compose-core:test \
          :fixthis-cli:test \
          :fixthis-mcp:test \
          :fixthis-compose-sidekick:testDebugUnitTest \
          :fixthis-gradle-plugin:test

# Build sample app and CLI/MCP distributions
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist

# Smoke tests (requires connected unlocked device or emulator)
scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample

# Host-only smoke validation (no device required)
scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample --host-only
```

## Module Map

```
:app (sample/)            — validation sample app (Gradle path :app, sources in sample/)
:fixthis-compose-core     — pure Kotlin: domain models, use cases, formatters, source matching
:fixthis-compose-sidekick — debug Android runtime: bridge server, semantics inspection, screenshots
:fixthis-gradle-plugin    — source-index generation, debug dependency injection
:fixthis-cli              — desktop CLI: run, doctor, setup, mcp, console
:fixthis-mcp              — stdio MCP server + local HTTP feedback console
```

## MCP Tools

| Tool | What it does |
|------|-------------|
| `fixthis_status` | Check ADB bridge, package name, source-index availability |
| `fixthis_get_current_screen` | Inspect current Compose screen semantics and layout |
| `fixthis_verify_ui_change` | Check whether expected text is present on screen |
| `fixthis_open_feedback_console` | Open (or return URL of) the local browser feedback console |
| `fixthis_list_feedback_sessions` | List resumable feedback workspaces for the project |
| `fixthis_capture_screen` | Capture current screen into the active feedback session |
| `fixthis_navigate_app` | Perform one debug-only tap, swipe, or back action |
| `fixthis_list_feedback` | List feedback queue summaries and item counts |
| `fixthis_read_feedback` | Read feedback queue as JSON + compact Markdown |
| `fixthis_resolve_feedback` | Mark a feedback item resolved, needs-clarification, or not-fixed |

## Feedback Workflow

1. Call `fixthis_open_feedback_console` — opens browser at `localhost:<port>`.
2. Click **Start** on the connection card. FixThis finds the connected device and launches the app.
3. Navigate the app from the browser preview.
4. Click **Annotate** to freeze the latest screen preview.
5. Click a UI element or drag a visual area; type a comment.
6. Click **Add annotation** — creates a numbered pin in the overlay.
7. Repeat steps 5–6 for all feedback on this screen.
8. Click **Send Agent** — persists one evidence snapshot (screenshot + overlay + source candidates) and creates a local handoff batch.
9. Call `fixthis_read_feedback` to read the queue, then `fixthis_resolve_feedback` after making changes.

## Diagnostics

```bash
fixthis doctor --package <applicationId>
```

Common issues and remedies are in [docs/troubleshooting.md](docs/troubleshooting.md).

## Constraints — Read Before Making Changes

- **Debug builds only.** The sidekick installs only via `debugImplementation`. Never target release builds.
- **Jetpack Compose only.** No View-based or Flutter targets in V1.
- **Local-first.** FixThis makes no external API calls. `Send Agent` is local file persistence; screenshots stay on your machine.
- **Do not commit `.fixthis/`** — it contains screenshots, session metadata, and local artifacts.
- **Do not rename persisted MCP JSON fields.** Field names `items`, `screens`, `itemId`, `screenId`, `targetEvidence`, and `sourceCandidates` are compatibility contracts for persisted session files. Domain model names may differ; mappers translate at the boundary.
- **`:fixthis-compose-core` has no dependency on MCP, CLI, Android UI, or `.fixthis/` paths.** Outer modules translate explicitly.

## External Project Setup

> **Note:** Gradle plugin and sidekick library coordinates are not yet published to a public repository. External projects must wire this repository as a composite build until artifacts are released.

Once published:

```kotlin
// In settings.gradle.kts
pluginManagement {
    repositories { gradlePluginPortal() }
}

// In app/build.gradle.kts
plugins {
    id("io.beyondwin.fixthis.compose") version "<version>"
}
```
```

---

## 2. CLAUDE.md — Verbatim Content

File location: repo root `CLAUDE.md`

```markdown
# FixThis

See [AGENTS.md](AGENTS.md) for project overview, MCP setup, AI workflow, and constraints.

## Build Commands

```bash
# All unit tests
./gradlew :fixthis-compose-core:test \
          :fixthis-cli:test \
          :fixthis-mcp:test \
          :fixthis-compose-sidekick:testDebugUnitTest \
          :fixthis-gradle-plugin:test

# Build sample app
./gradlew :app:assembleDebug

# Build CLI + MCP distributions (required before fixthis setup or fixthis run)
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist

# JS console asset syntax check
node --check fixthis-mcp/src/main/resources/console/app.js

# Full lint+diff check
git diff --check
```

## Module Map

```
:app (sample/)            — validation sample app; Gradle path :app, sources in sample/
:fixthis-compose-core     — pure Kotlin domain: models, use cases, formatters, source matching
:fixthis-compose-sidekick — debug Android runtime: bridge server, semantics, screenshots
:fixthis-gradle-plugin    — source-index generation, debug dependency injection
:fixthis-cli              — desktop CLI: run, doctor, setup, mcp, console commands
:fixthis-mcp              — stdio MCP server + local HTTP feedback console
```

## Architecture Invariants

- `:fixthis-compose-core` has no knowledge of MCP, CLI, Android UI surfaces, or `.fixthis/` file layout. All modules translate their DTOs and state into core domain contracts explicitly.
- `:app` Gradle project path maps to `sample/` source directory.
- Persisted MCP JSON field names (`items`, `screens`, `itemId`, `screenId`) are compatibility contracts — do not rename them. See `session/SessionDtoModels.kt` and `console/AnnotationRequestModels.kt`.
- The Android sidekick does not host an MCP or HTTP server. Only the desktop `fixthis-mcp` process does.
- `Send Agent` in the browser console is local persistence only. It does not call any external AI API.

## Console UI Development

Pass `--console-assets-dir` to read HTML/CSS/JS directly from source instead of the packaged JAR:

```bash
fixthis-cli/build/install/fixthis/bin/fixthis console \
  --package io.beyondwin.fixthis.sample \
  --console-assets-dir "$PWD/fixthis-mcp/src/main/resources/console"
```

This is for contributors iterating on console UI. Normal users use packaged resources.

## Connected Test Notes

`./gradlew connectedAndroidTest` requires an unlocked interactive emulator or device. A physical device reporting `device` in `adb devices` can still fail Compose hierarchy discovery behind a lockscreen. See [docs/troubleshooting.md](docs/troubleshooting.md).
```

---

## 3. README.md — Change Specification

### Location

Insert a new section **immediately after the "What It Does" section** (after line 22 in the current file, before the "## Setup" heading at line 24).

### Section Title

```
## Use with Claude Code or Codex
```

### Verbatim Content to Insert

```markdown
## Use with Claude Code or Codex

FixThis has built-in support for auto-configuring Claude Code and Codex via the `fixthis setup --write` command.

```bash
# 1. Build CLI and MCP distributions
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist

# 2. Configure your AI agent (run once per project)
fixthis-cli/build/install/fixthis/bin/fixthis setup \
  --package <applicationId> \
  --write \
  --target all
```

`--target all` writes:
- **Claude Code** → project-local `.claude/settings.json`
- **Codex** → user-global `~/.codex/config.toml`

Add `--dry-run` to preview config before writing. After setup, open the feedback console from your agent:

```
fixthis_open_feedback_console
```

See [AGENTS.md](AGENTS.md) for the full AI workflow.

```

### Notes

- Do not remove or modify any other README sections.
- The existing "## Setup" section remains; it covers Gradle dependency setup for the library itself, which is distinct from the MCP/AI agent setup.

---

## 4. docs/project-overview.en.md — Translation Spec

### Goal

Provide an English version of `docs/project-overview.md` for international contributors and AI agents parsing the docs. The Korean original (`docs/project-overview.md`) is preserved unchanged.

### Section Map (Korean heading → English heading)

| Korean | English |
|--------|---------|
| 한 줄 요약 | One-Line Summary |
| 현재 범위 | Current Scope |
| 모듈 지도 | Module Map |
| (subsections remain as-is, they are already English labels) | — |
| Runtime Flow | Runtime Flow (unchanged, already English + Mermaid) |
| Feedback Console Flow | Feedback Console Flow (unchanged) |
| Local Files And Artifacts | Local Files And Artifacts (already English) |
| 개발 명령 | Development Commands |
| 문서 읽는 순서 | Recommended Reading Order |
| 자주 헷갈리는 지점 | Common Confusions |

### Translation Rules

1. Translate Korean prose to English. Code blocks, shell commands, Mermaid diagrams, and file paths remain unchanged.
2. Opening paragraph changes from: `"이 문서는 현재 저장소의 실제 코드 기준으로..."` → `"This document is an onboarding reference based on the current repository code. For product requirements and long-term design rationale, see [Product requirements](fixthis_prd.md) and [Technical design](fixthis_technical_design.md)."`
3. Section `문서 읽는 순서` (Recommended Reading Order): last bullet point changes from `docs/superpowers/plans/` and `docs/superpowers/specs/` description in Korean to: `"docs/superpowers/plans/ and docs/superpowers/specs/ contain implementation history and work-order records. For current architecture, the above documents and ADRs take precedence."`
4. Section `자주 헷갈리는 지점` (Common Confusions): translate each bullet point.
5. Add a header note at the top of the file: `<!-- English translation of docs/project-overview.md — Korean original is the source of truth -->`
6. Add a "See Also" link at the bottom pointing to the Korean original.

### Scope Limit

Translate all sections in one pass. If the translation is incomplete, stop at a section boundary and note `<!-- TODO: translate from here -->` rather than leaving half-translated prose.

---

## Acceptance Criteria

- [ ] `AGENTS.md` exists at repo root, contains all sections: Prerequisites, Quick Start, Build and Test, Module Map, MCP Tools, Feedback Workflow, Diagnostics, Constraints, External Project Setup.
- [ ] `CLAUDE.md` exists at repo root, contains: Build Commands, Module Map, Architecture Invariants, Console UI Development, Connected Test Notes.
- [ ] README `## Use with Claude Code or Codex` section exists immediately before `## Setup`.
- [ ] `docs/project-overview.en.md` exists with all sections translated (no Korean prose remaining).
- [ ] `docs/project-overview.md` is unchanged.
- [ ] No new Gradle dependencies, no code changes.
- [ ] `./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test` passes unchanged.
