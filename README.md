# FixThis for Android Compose

[![CI](https://github.com/beyondwin/FixThis/actions/workflows/ci.yml/badge.svg)](https://github.com/beyondwin/FixThis/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![JDK 21](https://img.shields.io/badge/JDK-21-orange.svg)](https://adoptium.net/)
[![Compose](https://img.shields.io/badge/Jetpack_Compose-2026.04.01-4285F4.svg)](https://developer.android.com/jetpack/compose)

![FixThis Studio — point at any Jetpack Compose UI element, annotate, hand off AI-ready context to your coding agent](docs/assets/fixthis-studio-hero.png)

Telling a coding agent *which* part of a complex Compose screen to change is harder than the change itself. "The second card from the bottom — the small chip on its right edge — make the corners rounder" is tedious to write, ambiguous to read, and the agent still has to guess where in the source tree to land.

**FixThis flips that around: point at the UI, type what you want, and the agent gets a structured prompt with the target nailed down.** Behind the screenshot we attach source-file candidates, the surrounding semantic tree, the activity, the bounds, and severity / status — the agent acts on a precise target instead of decoding a description.

Two handoff modes:

- **Copy Prompt** — copies a compact Markdown prompt to the clipboard. Paste into Claude, Codex, Cursor, or any chat-style agent. No setup beyond having FixThis running.
- **Save to MCP** — saves the annotation batch as a local handoff. Claude Code or Codex (configured by `./scripts/bootstrap-mcp.sh`, or manually with `fixthis setup --write` after building the CLI/MCP distributions) reads it on demand via MCP tools — no copy/paste round trip.

Both modes share the same compact prompt format and the same JSON evidence.

## Why FixThis vs. just sending a screenshot?

Modern coding agents already accept screenshots and accessibility trees. FixThis differs in three ways:

- **Pin to source, not pixels.** Top-3 ranked source-file candidates with line numbers, match reasons, and a margin score — the agent edits the right call site instead of guessing which composable rendered which pixel.
- **Stable target identity.** Instance grouping (`instance i/N`), duplicate-marker detection, and overlap-group hints keep N visually identical cards distinguishable.
- **Screen integrity checks.** Frozen previews carry a screen fingerprint; if the app rotates, changes window mode, or otherwise moves to a different screen before saving, FixThis asks you to re-capture, force-save, or cancel.
- **Batched, structured handoff.** One prompt can carry many annotations across many screens, each with its own bounds, severity, and source pin.

If your screen has a single obvious target with clear text, a plain screenshot may already be enough. FixThis pays off when the UI is dense, list-rendered, or labeled mostly by composable name.

## Quick Start (sample app, ~5 min)

```bash
git clone <this-repo> && cd FixThis
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis doctor --package io.beyondwin.fixthis.sample
fixthis-cli/build/install/fixthis/bin/fixthis run --package io.beyondwin.fixthis.sample
```

`fixthis run` installs the sample debug APK, attaches the sidekick bridge, and
opens the FixThis Studio console at `http://127.0.0.1:<port>`. Follow the
Connect → Preview → Annotate → Handoff progress, watch the readiness summary
near **Copy Prompt** / **Save to MCP**, then copy Markdown or save the local
MCP handoff for Claude Code or Codex.

→ Full walkthrough: [Quick Start with the sample](docs/getting-started/try-the-sample.md)
→ Add to your own app: [Add FixThis to your app](docs/getting-started/add-to-your-app.md)
→ Anything broken? [Troubleshooting](docs/guides/troubleshooting.md)

## Scope (V1)

FixThis is intentionally narrow today:

- **Jetpack Compose only.** View-based hierarchies (XML, AndroidView interop, web views) are **not detected** for source candidates.
- **Debug builds only.** Sidekick ships as a `debugImplementation` artifact and is omitted from release. No production runtime cost.
- **Local-only & ADB-only.** No cloud, no upload, no external network call. Artifacts live under `.fixthis/`.
- **No required testTags.** Smart Select prefers semantics + nearby labels + composable-name conventions.
- **No AccessibilityService.** Inspection runs in-process inside the debug app via the sidekick.
- **MCP-first.** The browser feedback console is the primary surface; the Android app shows only a connection pill.
- **Source candidates are best-effort.** Up to 3 candidates plus a margin score so the agent can pick or verify.
- **Screenshots are pixel captures.** Editable / password text is redacted, but pixels may still contain sensitive content.

## Documentation

→ **Full docs index: [`docs/index.md`](docs/index.md)**

| You want                          | Look here                                                            |
| --------------------------------- | -------------------------------------------------------------------- |
| Try it on the sample              | [`docs/getting-started/try-the-sample.md`](docs/getting-started/try-the-sample.md) |
| Add to your own app               | [`docs/getting-started/add-to-your-app.md`](docs/getting-started/add-to-your-app.md) |
| Use with Claude Code / Codex / Cursor | [`docs/guides/agents.md`](docs/guides/agents.md)                |
| Console walkthrough               | [`docs/guides/feedback-console-tour.md`](docs/guides/feedback-console-tour.md) |
| CLI flags                         | [`docs/reference/cli.md`](docs/reference/cli.md)                     |
| MCP tool list                     | [`docs/reference/mcp-tools.md`](docs/reference/mcp-tools.md)         |
| Output JSON shape                 | [`docs/reference/output-schema.md`](docs/reference/output-schema.md) |
| Bridge protocol                   | [`docs/reference/bridge-protocol.md`](docs/reference/bridge-protocol.md) |
| Compatibility (AGP / Kotlin / Compose) | [`docs/reference/compatibility.md`](docs/reference/compatibility.md) |
| Privacy & security                | [`docs/reference/privacy.md`](docs/reference/privacy.md), [`docs/reference/threat-model.md`](docs/reference/threat-model.md), [`SECURITY.md`](SECURITY.md) |
| Architecture                      | [`docs/architecture/overview.md`](docs/architecture/overview.md), [`docs/architecture/adr/`](docs/architecture/adr/) |
| Release notes                     | [`docs/releases/`](docs/releases/)                              |
| Contribute                        | [`CONTRIBUTING.md`](CONTRIBUTING.md), [`docs/contributing/release-process.md`](docs/contributing/release-process.md) |
| Troubleshoot                      | [`docs/guides/troubleshooting.md`](docs/guides/troubleshooting.md)   |

## Status

> ⚠️ **Source release available.** FixThis has GitHub Releases for source
> snapshots, starting with [v0.1.0](docs/releases/v0.1.0.md), but it is not yet
> on Maven Central or the Gradle Plugin Portal. External projects must wire this
> repository via Gradle composite build (`includeBuild`) until artifacts are
> released. See
> [Release readiness](docs/contributing/release-readiness.md) for the publishing
> checklist.
>
> Current `main` also contains unreleased hardening after v0.1.0. See
> [Unreleased changes since v0.1.0](docs/releases/unreleased.md) and
> [`CHANGELOG.md`](CHANGELOG.md#unreleased) before cutting the next tag.

## Roadmap

V1 is intentionally narrow. High-priority items beyond V1:

- **Maven Central / Gradle Plugin Portal release** — single-line install for external projects.
- **`AndroidView` / interop awareness** — at minimum a "View-based subtree" badge so the agent stops guessing at non-Compose pixels.
- **SSE-driven console state sync** — replace polling with server-pushed events. Design in [console-state-sync-design.md](docs/architecture/console-state-sync-design.md).
- **Smarter source matching** — lift detection on `Layout` / `SubcomposeLayout` wrappers, recognize more composable-name conventions, and keep source confidence explainable as the matcher evolves.
- **More agents out of the box** — Cursor, Aider, and others currently work via Copy Prompt; first-class MCP / config writers can follow the Claude Code + Codex pattern.

Vote items up or contribute via [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

[MIT License](LICENSE). See also [`NOTICE`](NOTICE) for third-party attribution.
