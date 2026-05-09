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
| `fixthis_status` | Check ADB bridge, package name, source-index availability; reports `installStale` when host source files are newer than the installed APK |
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
8. Click **Save to MCP** — persists one evidence snapshot (screenshot + overlay + source candidates) and creates a local handoff batch. Use **Copy Prompt** instead if you'd rather paste the compact Markdown directly into a chat-style agent without going through MCP.
9. Call `fixthis_read_feedback` to read the queue, then `fixthis_resolve_feedback` after making changes.

## Diagnostics

```bash
fixthis doctor --package <applicationId>
```

Common issues and remedies are in [docs/troubleshooting.md](docs/troubleshooting.md).

## Constraints — Read Before Making Changes

- **Debug builds only.** The sidekick installs only via `debugImplementation`. Never target release builds.
- **Jetpack Compose only.** No View-based or Flutter targets in V1.
- **Local-first.** FixThis makes no external API calls. `Save to MCP` is local file persistence; screenshots stay on your machine.
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
