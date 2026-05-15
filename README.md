# FixThis for Android Compose

[![CI](https://github.com/beyondwin/FixThis/actions/workflows/ci.yml/badge.svg)](https://github.com/beyondwin/FixThis/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![JDK 21](https://img.shields.io/badge/JDK-21-orange.svg)](https://adoptium.net/)
[![Node 20+](https://img.shields.io/badge/Node-20%2B-339933.svg)](https://nodejs.org/)
[![Compose](https://img.shields.io/badge/Jetpack_Compose-2026.04.01-4285F4.svg)](https://developer.android.com/jetpack/compose)

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
- Let Claude Code or Codex configure the sample MCP server with `./scripts/bootstrap-mcp.sh --sample`.
- Use **Copy Prompt** with Cursor, ChatGPT, or any chat-style coding agent.
- Use **Save to MCP** with Claude Code or Codex after running the bootstrap script.
- Runs locally over ADB and `127.0.0.1`; FixThis makes no external API calls.
- Debug builds only. Jetpack Compose only.

## Quick Start: Sample App to Agent Handoff

```bash
git clone <this-repo> && cd FixThis
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis doctor --package io.beyondwin.fixthis.sample
fixthis-cli/build/install/fixthis/bin/fixthis run --package io.beyondwin.fixthis.sample
```

`fixthis run` installs the sample debug APK, launches it, attaches the
sidekick bridge, and opens FixThis Studio at `http://127.0.0.1:<port>`.

In the console:

1. Click **Annotate**.
2. Click a Compose UI element, or drag a visual area.
3. Type the requested change.
4. Click **Add annotation**.
5. Click **Copy Prompt** for any chat-style agent, or **Save to MCP** for
   Claude Code / Codex.

You are done when the console shows a numbered annotation and you have either
copied compact Markdown or saved a local MCP handoff.

## Pick Your Path

| Goal | Start here |
| --- | --- |
| Try FixThis without touching your app | [Quick Start with the sample](docs/getting-started/try-the-sample.md) |
| Add FixThis to your Compose debug build | [Add FixThis to your app](docs/getting-started/add-to-your-app.md) |
| Connect Claude Code, Codex, Cursor, or a chat agent | [Connect your agent](docs/getting-started/connect-your-agent.md) |
| Let an agent bootstrap MCP from the repo | [MCP bootstrap](MCP.md) |
| Learn the browser console workflow | [Feedback console tour](docs/guides/feedback-console-tour.md) |
| Diagnose setup problems | [Troubleshooting](docs/guides/troubleshooting.md) |
| Inspect CLI, MCP, or JSON contracts | [Documentation index](docs/index.md) |
| Contribute | [Contributing guide](CONTRIBUTING.md) |

## Trust and Privacy

FixThis is local-first: the sidekick talks to the desktop tools over ADB, the
browser console binds to localhost, and **Save to MCP** writes local files under
`.fixthis/`. FixThis does not call an external AI API.

Screenshots may still contain sensitive pixels. Review copied prompts or local
artifacts before sharing them outside your machine, and do not commit
`.fixthis/`.

Details: [Privacy](docs/reference/privacy.md), [Security](SECURITY.md), and
[Threat model](docs/reference/threat-model.md).

## Why FixThis vs. just sending a screenshot?

Modern coding agents already accept screenshots and accessibility trees. FixThis differs in three ways:

- **Pin to source, not pixels.** Top-3 ranked source-file candidates with line numbers, match reasons, and a margin score — the agent edits the right call site instead of guessing which composable rendered which pixel.
- **Stable target identity.** Instance grouping (`instance i/N`), duplicate-marker detection, and overlap-group hints keep N visually identical cards distinguishable.
- **Screen integrity checks.** Frozen previews carry a screen fingerprint; if the app rotates, changes window mode, or otherwise moves to a different screen before saving, FixThis asks you to re-capture, force-save, or cancel.
- **Honest target confidence.** Handoffs can mark visual-only, stale, or
  possible AndroidView/WebView targets so agents know when to verify rather
  than trust source hints directly.
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

Architecture details live in [Architecture overview](docs/architecture/overview.md).

## Status

> ⚠️ **Source release available.** FixThis has GitHub Releases for source
> snapshots, starting with [v0.1.0](docs/releases/v0.1.0.md), but it is not yet
> on Maven Central or the Gradle Plugin Portal. Current `main` can be tried
> directly from source.

External projects currently wire this repository via Gradle composite build
(`includeBuild`) or local repository setup until artifacts are released.

The live readiness dashboard is
[Release readiness](docs/contributing/release-readiness.md). It lists the
supported install paths today, future artifact coordinates, and the checks
required before public artifact instructions can replace source/composite
setup.

Current `main` also contains unreleased hardening after v0.1.0. See
[Unreleased changes since v0.1.0](docs/releases/unreleased.md) and
[`CHANGELOG.md`](CHANGELOG.md#unreleased) before cutting the next tag.

Agents working inside this repository should also read [AGENTS.md](AGENTS.md).

## Roadmap

FixThis V1 is intentionally narrow:

- **Jetpack Compose only.** View-based hierarchies (XML, AndroidView interop,
  web views) are **not detected** for source candidates.
- **Debug builds only.** Sidekick ships as a `debugImplementation` artifact and
  is omitted from release. No production runtime cost.
- **Local-only & ADB-only.** No cloud, no upload, no external network call.
  Artifacts live under `.fixthis/`.
- **No required testTags.** Smart Select prefers semantics, nearby labels, and
  composable-name conventions.
- **No AccessibilityService.** Inspection runs in-process inside the debug app
  via the sidekick.
- **MCP-first.** The browser feedback console is the primary surface; the
  Android app shows only a connection pill.
- **Source candidates are best-effort.** Up to 3 candidates plus a margin score
  so the agent can pick or verify.
- **Screenshots are pixel captures.** Editable / password text is redacted, but
  pixels may still contain sensitive content.

High-priority items beyond V1:

- **Maven Central / Gradle Plugin Portal release** — single-line install for external projects.
- **Deeper `AndroidView` / interop awareness** — FixThis now warns when a
  target may cross a View/WebView boundary; future work should expose richer
  subtree evidence instead of only lowering target confidence.
- **Finish SSE-driven console state sync** — Phase 1 now pushes session,
  device, connection, and preview updates over `/api/events`, with polling kept
  as fallback. Remaining work is to retire the preview polling loop and reduce
  redundant pull refreshes. Design in [console-state-sync-design.md](docs/architecture/console-state-sync-design.md).
- **Smarter source matching** — lift detection on `Layout` / `SubcomposeLayout` wrappers, recognize more composable-name conventions, and keep source confidence explainable as the matcher evolves.
- **More agents out of the box** — Cursor, Aider, and others currently work via Copy Prompt; first-class MCP / config writers can follow the Claude Code + Codex pattern.

Vote items up or contribute via [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

[MIT License](LICENSE). See also [`NOTICE`](NOTICE) for third-party attribution.
