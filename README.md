# FixThis for Android Compose

[![CI](https://github.com/beyondwin/FixThis/actions/workflows/ci.yml/badge.svg)](https://github.com/beyondwin/FixThis/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![JDK 21](https://img.shields.io/badge/JDK-21-orange.svg)](https://adoptium.net/)
[![Node 20+](https://img.shields.io/badge/Node-20%2B-339933.svg)](https://nodejs.org/)
[![Compose](https://img.shields.io/badge/Jetpack_Compose-2025.01.01-4285F4.svg)](https://developer.android.com/jetpack/compose)

![FixThis Studio — point at any Jetpack Compose UI element, annotate, hand off AI-ready context to your coding agent](docs/assets/fixthis-studio-hero.png)

Point at a Jetpack Compose UI, write the change you want, and hand Claude,
Codex, Cursor, or another coding agent the source context it needs.

FixThis adds a debug-only sidekick to a Compose app, mirrors the current screen
into a local browser console, and turns your UI annotations into a compact
agent handoff with screenshot bounds, semantics context, source candidates, and
target-confidence warnings.

In the browser console, a single click selects the nearest Compose UI
component, and a drag selects any visual area when the target is spacing, empty
room, interop content, or another region that is not a clean component.

## Works Today

- Try the bundled sample app in about five minutes.
- Install the published desktop CLI/MCP package with Homebrew on macOS or from
  npm / GitHub Releases on macOS/Linux.
- Add FixThis to an external Android app with the published Gradle plugin:
  `io.github.beyondwin.fixthis.compose`.
- Let Claude Code or Codex configure the sample MCP server with `./scripts/bootstrap-mcp.sh --sample`.
- Let an agent configure your Android app with `fixthis install-agent`.
- Use **Copy Prompt** with Cursor, ChatGPT, or any chat-style coding agent.
- Use **Save to MCP** with Claude Code or Codex after running the bootstrap script.
- Runs locally over ADB and `127.0.0.1`; FixThis makes no external API calls.
- Debug builds only. Jetpack Compose only.

## Quick Start: Agent Installs FixThis in Your App

### Claude Code / Codex Bootstrap Prompt

FixThis is debug-only and Jetpack Compose only. Paste this prompt into Claude
Code or Codex from the root of a Jetpack Compose Android app:

```text
Install FixThis in this project and configure it for this agent.

Use this order:
1. Run `fixthis install-agent --project-dir . --target all`.
2. Run `fixthis doctor --project-dir . --json`.
3. Use the doctor JSON readiness result as the source of truth.
4. If MCP config was written, tell me to restart Claude Code or Codex before calling `fixthis_open_feedback_console`.

Do not configure release builds. Do not commit `.fixthis/`.
```

The agent should run:

```bash
# macOS package-manager path
brew install beyondwin/tools/fixthis

# Node/npm path
npm install -g @beyondwin/fixthis

# macOS/Linux fallback path
curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
  | bash -s -- --version v1.2.0

fixthis install-agent --project-dir . --target all
fixthis doctor --project-dir . --json
```

If Homebrew already has FixThis installed, run
`brew update && brew upgrade beyondwin/tools/fixthis` and verify the active
binary with `fixthis --version`.

`fixthis install-agent` patches the detected Android app module with the
published Gradle plugin, writes MCP config for Claude Code / Codex, writes
`.fixthis/project.json`, and writes `.fixthis/agent-setup.*` handoff files.
If doctor reports `NEEDS_INSTALL` or generated metadata is missing, run
`./gradlew fixthisSetup` as a recovery step and rerun
`fixthis doctor --project-dir . --json`. Restart Claude Code or Codex after
MCP config is written, then call `fixthis_open_feedback_console`.

The published Gradle plugin coordinates:

```kotlin
plugins {
    id("io.github.beyondwin.fixthis.compose") version "1.2.0"
}
```

The plugin adds the debug-only sidekick dependency automatically, generates
FixThis project metadata, and keeps release builds out of scope.

## Quick Start: Sample App to Agent Handoff

```bash
git clone <this-repo> && cd FixThis
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis doctor --package io.github.beyondwin.fixthis.sample
fixthis-cli/build/install/fixthis/bin/fixthis run --package io.github.beyondwin.fixthis.sample
```

`fixthis run` installs the sample debug APK, launches it, attaches the
sidekick bridge, and opens FixThis Studio at `http://127.0.0.1:<port>`.

In the console:

1. Click **Annotate**.
2. Click a Compose UI element, or drag a visual area.
3. Type the requested change in the annotation detail.
4. Repeat click/drag for any other changes on this screen.
5. Click **Copy Prompt** for any chat-style agent, or **Save to MCP** for
   Claude Code / Codex.

You are done when the console shows a numbered annotation and you have either
copied compact Markdown or saved a local MCP handoff.

Maintainers can validate that same real Copy Prompt path across the runtime
fixtures with a connected emulator or device:

```bash
npm run real-copy-prompt:smoke -- --strict
```

## Pick Your Path

| Goal | Start here |
| --- | --- |
| Try FixThis without touching your app | [Quick Start with the sample](docs/getting-started/try-the-sample.md) |
| Add FixThis to your Compose debug build | [Add FixThis to your app](docs/getting-started/add-to-your-app.md) |
| Connect Claude Code, Codex, Cursor, or a chat agent | [Connect your agent](docs/getting-started/connect-your-agent.md) |
| Let an agent bootstrap MCP from the repo | [MCP bootstrap](MCP.md) |
| Learn the browser console workflow | [Feedback console tour](docs/guides/feedback-console-tour.md) |
| Understand the product concept and handoff rationale | [Concept and handoff rationale](docs/product/concept-and-handoff-rationale.md) |
| Diagnose setup problems | [Troubleshooting](docs/guides/troubleshooting.md) |
| Inspect CLI, MCP, or JSON contracts | [Documentation index](docs/index.md) |
| Contribute | [Contributing guide](CONTRIBUTING.md) |

## Why FixThis vs. just sending a screenshot?

Modern coding agents already accept screenshots and accessibility trees.
FixThis adds the missing handoff structure:

- **Pin to source, not pixels.** Top-3 ranked source-file candidates with line numbers, match reasons, and a margin score — the agent edits the right call site instead of guessing which composable rendered which pixel.
- **Route visual edits to the likely surface.** `editSurface` hints carry role
  tokens for call sites, component definitions, copy/data, layout/style,
  visual-area work, and interop risk, so a style request is not forced through
  the same path as a text-source match.
- **Stable target identity.** Instance grouping (`instance i/N`), duplicate-marker detection, and overlap-group hints keep N visually identical cards distinguishable.
- **Screen integrity checks.** Frozen previews carry a screen fingerprint; if the app rotates, changes window mode, or otherwise moves to a different screen before saving, FixThis asks you to re-capture, force-save, or cancel.
- **Honest target confidence.** Handoffs can mark visual-only, stale, or
  possible AndroidView/WebView targets so agents know when to verify rather
  than trust source hints directly.
- **Retry-safe local batches.** Slow or retried `Copy Prompt` / `Save to MCP`
  saves reuse browser draft ids, so duplicate requests do not create duplicate
  agent work.
- **Batched, structured handoff.** One prompt can carry many annotations across many screens, each with its own bounds, severity, and source pin.

If your screen has a single obvious target with clear text, a plain screenshot may already be enough. FixThis pays off when the UI is dense, list-rendered, or labeled mostly by composable name.

## Module Map

| Module | Role |
| --- | --- |
| `:app` (`sample/`) | Validation sample app |
| `:fixthis-compose-core` | Pure Kotlin domain |
| `:fixthis-compose-sidekick` | Debug Android runtime |
| `:fixthis-gradle-plugin` | Source-index generation and debug DI |
| `:fixthis-cli` | Desktop CLI |
| `:fixthis-mcp` | stdio MCP server and local HTTP feedback console |

Product and architecture details live in
[Concept and handoff rationale](docs/product/concept-and-handoff-rationale.md),
[Product concept](docs/product/README.md),
[Decision rationale](docs/product/decision-rationale.md), and
[Architecture overview](docs/architecture/overview.md).

## Status

FixThis has public artifacts for the agent-first path:

- Gradle plugin: `io.github.beyondwin.fixthis.compose`
- Maven artifacts: `io.github.beyondwin:fixthis-compose-sidekick` and
  `io.github.beyondwin:fixthis-compose-core`
- Homebrew tap: `brew install beyondwin/tools/fixthis`
- CLI/MCP package: GitHub Release asset `fixthis-cli-mcp-vX.Y.Z.tar.gz`
- npm wrapper: `npm install -g @beyondwin/fixthis`
- MCP Registry entry: `io.github.beyondwin/fixthis`

The live release dashboard is
[Release readiness](docs/contributing/release-readiness.md). It lists current
coordinates, verification commands, and registry follow-ups.

Current `main` may contain changes after the latest tag. See
[`CHANGELOG.md`](CHANGELOG.md#unreleased) and
[release notes](docs/releases/README.md) before cutting another release.

Agents working inside this repository should also read [AGENTS.md](AGENTS.md).

## Trust and Privacy

FixThis is local-first: the sidekick talks to the desktop tools over ADB, the
browser console binds to localhost, and **Save to MCP** writes local files under
`.fixthis/`. FixThis does not call an external AI API.

Screenshots may still contain sensitive pixels. Review copied prompts or local
artifacts before sharing them outside your machine, and do not commit
`.fixthis/`.

Details: [Privacy](docs/reference/privacy.md), [Security](SECURITY.md), and
[Threat model](docs/reference/threat-model.md).

## Roadmap

FixThis V1 stays intentionally narrow: Jetpack Compose debug builds, local ADB
transport, MCP-first handoff, best-effort source candidates, and no cloud
upload.

The detailed roadmap lives in [Roadmap](docs/product/roadmap.md). It covers V1
scope, public artifact release work, deeper interop awareness, SSE-driven
console state sync, smarter source matching, and future agent integrations.

## License

[MIT License](LICENSE). See also [`NOTICE`](NOTICE) for third-party attribution.
