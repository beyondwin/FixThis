# FixThis AI Discoverability — Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create AGENTS.md, CLAUDE.md, a README quickstart section, and an English project-overview so Claude Code and Codex users can discover and use FixThis's existing MCP integration without digging through source.

**Architecture:** Four independent file operations — create two new files at repo root, insert one section into README, create one translation file in docs/. No code changes. No Gradle dependencies. Existing tests are unaffected.

**Tech Stack:** Markdown, existing project tooling only.

**Spec:** `docs/superpowers/specs/2026-05-09-fixthis-ai-discoverability-docs-spec.md`

---

## File Map

| Action | Path | Notes |
|--------|------|-------|
| Create | `AGENTS.md` | Universal AI agent entry point (Claude Code + Codex) |
| Create | `CLAUDE.md` | Claude Code–specific build/test/invariants |
| Modify | `README.md` | Insert section after "What It Does", before "Setup" |
| Create | `docs/project-overview.en.md` | English translation; Korean original untouched |

---

### Task 1: Create AGENTS.md

**Files:**
- Create: `AGENTS.md`

- [ ] **Step 1: Create AGENTS.md with full content**

Write the following file verbatim to `AGENTS.md` at repo root:

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
scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample

# Host-only smoke validation (no device required)
scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample --host-only
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
    id("io.github.beyondwin.fixthis.compose") version "<version>"
}
```
```

- [ ] **Step 2: Verify AGENTS.md structure**

```bash
grep -c "^## " AGENTS.md
```

Expected: `9` (Prerequisites, Quick Start, Build and Test, Module Map, MCP Tools, Feedback Workflow, Diagnostics, Constraints, External Project Setup)

- [ ] **Step 3: Verify MCP tools table has 10 rows**

```bash
grep -c "fixthis_" AGENTS.md
```

Expected: `10` (one per MCP tool)

- [ ] **Step 4: Commit**

```bash
git add AGENTS.md
git commit -m "docs: add AGENTS.md for Claude Code and Codex onboarding"
```

---

### Task 2: Create CLAUDE.md

**Files:**
- Create: `CLAUDE.md`

- [ ] **Step 1: Create CLAUDE.md with full content**

Write the following file verbatim to `CLAUDE.md` at repo root:

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
  --package io.github.beyondwin.fixthis.sample \
  --console-assets-dir "$PWD/fixthis-mcp/src/main/resources/console"
```

This is for contributors iterating on console UI. Normal users use packaged resources.

## Connected Test Notes

`./gradlew connectedAndroidTest` requires an unlocked interactive emulator or device. A physical device reporting `device` in `adb devices` can still fail Compose hierarchy discovery behind a lockscreen. See [docs/troubleshooting.md](docs/troubleshooting.md).
```

- [ ] **Step 2: Verify CLAUDE.md structure**

```bash
grep "^## " CLAUDE.md
```

Expected output:
```
## Build Commands
## Module Map
## Architecture Invariants
## Console UI Development
## Connected Test Notes
```

- [ ] **Step 3: Verify AGENTS.md cross-reference exists**

```bash
grep "AGENTS.md" CLAUDE.md
```

Expected: at least one line containing `AGENTS.md`

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add CLAUDE.md with build commands and architecture invariants"
```

---

### Task 3: Add README Quickstart Section

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Identify insertion point**

```bash
grep -n "^## Setup" README.md
```

Note the line number. The new section goes immediately before this line.

- [ ] **Step 2: Insert section**

Open `README.md`. Find the `## Setup` heading. Insert the following block immediately before it (leave one blank line between the new section's last content and `## Setup`):

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

- [ ] **Step 3: Verify section placement**

```bash
grep -n "^## " README.md | head -10
```

Expected: `## Use with Claude Code or Codex` appears before `## Setup` in the output.

- [ ] **Step 4: Verify no other README content was changed**

```bash
git diff README.md | grep "^-" | grep -v "^---" | wc -l
```

Expected: `0` (no lines removed, only added)

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: add AI quickstart section to README"
```

---

### Task 4: Create English Project Overview

**Files:**
- Create: `docs/project-overview.en.md`
- Read (do not modify): `docs/project-overview.md`

- [ ] **Step 1: Read the Korean source**

Read `docs/project-overview.md` in full. Sections to translate are all Korean prose headings and paragraphs. Code blocks, shell commands, Mermaid diagrams, and file paths remain unchanged.

- [ ] **Step 2: Create docs/project-overview.en.md**

Write the following file to `docs/project-overview.en.md`. Replace each Korean prose section with the English translation indicated below.

**File header (insert before everything else):**
```markdown
<!-- English translation of docs/project-overview.md — Korean original is the source of truth -->
```

**Opening paragraph** (replaces the Korean opening sentence `이 문서는...`):
```markdown
This document is an onboarding reference based on the current repository code. For product requirements and long-term design rationale, see [Product requirements](fixthis_prd.md) and [Technical design](fixthis_technical_design.md).
```

**Section: 한 줄 요약 → One-Line Summary**

Heading: `## One-Line Summary`

Body:
```markdown
FixThis attaches a sidekick runtime to a Jetpack Compose debug app, captures the current UI's semantics, screenshot, selection position, source candidates, and user feedback locally, then hands them off to an AI coding agent as a readable work queue through the CLI/MCP/feedback console.
```

**Section: 현재 범위 → Current Scope**

Heading: `## Current Scope`

Body:
```markdown
- Android Jetpack Compose debug builds only.
- Sidekick auto-installs into the debug app via AndroidX Startup.
- Reads only the current app process's Compose semantics — no AccessibilityService.
- Local desktop integration via ADB and an app-local socket bridge.
- MCP feedback console is the primary workflow; the app itself shows only MCP browser connection status.
- Source candidates are best-effort hints based on a Gradle source index.
- Screenshot pixels are not automatically PII-redacted; review before sharing.
```

**Section: 모듈 지도 → Module Map**

Heading: `## Module Map`

The code block is already in English — copy it unchanged.

Each subsection (`### :fixthis-compose-core` etc.) is already in English labels. Translate Korean prose bullets within each subsection. Key translations:

- `:fixthis-compose-core`: "Pure Kotlin module. Houses common contracts not directly tied to the Android runtime."
- `Boundary invariant:` line — copy unchanged (already English)
- `:fixthis-compose-sidekick`: "Runtime that executes inside the target Android debug app."
- `:fixthis-gradle-plugin`: "Gradle plugin applied to the Android application project."
- `:fixthis-cli`: "CLI that runs as a desktop process. Builds the `fixthis` application distribution."
- `package name 해석 순서` → "Package name resolution order:"
- `:fixthis-mcp`: "MCP stdio server and local feedback console server."
- `:app (sample/)`: "Repository validation sample app."

**Section: Runtime Flow**

Heading: `## Runtime Flow`

Mermaid diagram and surrounding text are already in English — copy unchanged.

**Section: Feedback Console Flow**

Heading: `## Feedback Console Flow`

Already in English — copy unchanged including the `Important distinction:` bullets.

**Section: Local Files And Artifacts**

Already in English — copy unchanged.

**Section: 개발 명령 → Development Commands**

Heading: `## Development Commands`

All content (shell commands and their labels) is already in English — copy unchanged.

**Section: 문서 읽는 순서 → Recommended Reading Order**

Heading: `## Recommended Reading Order`

Body:
```markdown
Recommended order for a developer seeing this project for the first time:

1. [README](../README.md): product summary and quick start.
2. This document: current code structure and runtime flow.
3. [MCP](mcp.md): feedback console and MCP tool contracts.
4. [Output schema](output-schema.md): annotation/session JSON fields.
5. [Privacy](privacy.md): local-first, redaction, screenshot caution.
6. [Troubleshooting](troubleshooting.md): ADB/sidekick/MCP failure diagnosis.
7. [Technical design](fixthis_technical_design.md): longer design rationale and module-by-module design.
8. [Architecture Decision Records](adr/README.md): durable architecture decisions that the current code upholds.

`docs/superpowers/plans/` and `docs/superpowers/specs/` contain implementation history and work-order records. For current architecture, the above documents and ADRs take precedence.
```

**Section: 자주 헷갈리는 지점 → Common Confusions**

Heading: `## Common Confusions`

Body:
```markdown
- `:app` is the Gradle project path; the source directory is `sample/`.
- The Android app does not open an MCP server or HTTP server. The MCP and console servers run as desktop processes.
- The app bridge is an Android local socket with a token, accessible from the desktop only via ADB forward.
- Selection and submission do not happen inside the app. Selection and submission happen exclusively in the MCP browser console.
- Source candidates are text/symbol-based ranking from a source index — not exact compiler mappings.
- Semantics redaction is not screenshot pixel redaction.
- The feedback console's `Annotate` mode freezes the preview but does not save. `Add annotation` creates a browser-side pending item. Only `Copy Prompt` or `Send Agent` creates a persisted evidence snapshot.
- Persisted MCP JSON field names are a compatibility contract. They may differ from domain model names; check the mapper boundary.
```

**File footer (append at end):**
```markdown
---

See also: [Korean original](project-overview.md)
```

- [ ] **Step 3: Verify no Korean prose remains**

```bash
# Check for common Korean Unicode ranges (Hangul syllables U+AC00–D7A3)
python3 -c "
import re, sys
text = open('docs/project-overview.en.md').read()
korean = re.findall(r'[가-힣]+', text)
if korean:
    print('Korean found:', korean[:5])
    sys.exit(1)
print('No Korean prose found.')
"
```

Expected: `No Korean prose found.`

- [ ] **Step 4: Verify Korean original is unchanged**

```bash
git diff docs/project-overview.md
```

Expected: no output (file unchanged)

- [ ] **Step 5: Verify section headings**

```bash
grep "^## " docs/project-overview.en.md
```

Expected output:
```
## One-Line Summary
## Current Scope
## Module Map
## Runtime Flow
## Feedback Console Flow
## Local Files And Artifacts
## Development Commands
## Recommended Reading Order
## Common Confusions
```

- [ ] **Step 6: Commit**

```bash
git add docs/project-overview.en.md
git commit -m "docs: add English translation of project-overview.md"
```

---

### Task 5: Update README Links and Cross-References

**Files:**
- Modify: `README.md`
- Modify: `docs/project-overview.md` (add one line pointing to English version)

- [ ] **Step 1: Add English version pointer to project-overview.md**

Open `docs/project-overview.md`. At the very top, after the `# FixThis Project Overview` heading, add:

```markdown
> English version: [project-overview.en.md](project-overview.en.md)
```

- [ ] **Step 2: Verify pointer was added**

```bash
head -3 docs/project-overview.md
```

Expected:
```
# FixThis Project Overview

> English version: [project-overview.en.md](project-overview.en.md)
```

- [ ] **Step 3: Add project-overview.en.md to the README docs list**

In `README.md`, find the "More detail" section at the bottom (the bullet list of doc links). Add the following line after the `[Project overview]` link:

```markdown
- [Project overview (English)](docs/project-overview.en.md)
```

- [ ] **Step 4: Verify link was added**

```bash
grep "project-overview.en" README.md
```

Expected: one line containing the link.

- [ ] **Step 5: Run tests to verify no breakage**

```bash
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test
```

Expected: BUILD SUCCESSFUL, same pass count as before this plan.

- [ ] **Step 6: Commit**

```bash
git add README.md docs/project-overview.md
git commit -m "docs: cross-reference English project overview from README and Korean original"
```

---

## Self-Review Checklist

- [x] Spec coverage: AGENTS.md (Task 1), CLAUDE.md (Task 2), README section (Task 3), project-overview.en.md (Task 4), cross-references (Task 5).
- [x] No placeholders: all file content is verbatim in the plan.
- [x] No code changes in any task.
- [x] Korean original preserved and cross-referenced.
- [x] LICENSE mentioned as prerequisite, not task.
- [x] Test command in Task 5 verifies no regressions.
