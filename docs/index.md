# FixThis Documentation

FixThis is a debug-only sidekick for Jetpack Compose Android apps that hands UI
context to AI coding agents — point at a UI element in a desktop browser
console, type what you want, and the agent gets a structured prompt with the
target nailed down.

This page is a directory. Pick the path that matches what you're trying to do.

## I'm new — what is this?

- **Start at the [project README](../README.md).** It has the pitch, the
  scope, and a 5-minute Quick Start against the bundled sample app.
- **Want the architecture in one page?** Read
  [`architecture/overview.md`](architecture/overview.md).

## I want to use FixThis

### Try it out (5 min, no app changes)

- [Quick Start with the sample app](getting-started/try-the-sample.md)
- [Troubleshooting](guides/troubleshooting.md) — ADB, lockscreen, bridge attach

### Use it on my own app

- [Add FixThis to your app](getting-started/add-to-your-app.md) — Gradle plugin,
  bootstrap, MCP setup
- [Feedback console tour](guides/feedback-console-tour.md) — annotate, pin to
  source, hand off
- [Working with AI agents](guides/agents.md) — Claude Code, Codex, Cursor,
  and any chat-style agent
- [CLI reference](reference/cli.md) — `fixthis run / doctor / setup / console / clean / mcp`

## I want to integrate at a deeper level

- [MCP tools reference](reference/mcp-tools.md) — every tool the MCP server
  exposes
- [Output schema](reference/output-schema.md) — JSON shape of annotations,
  sessions, and handoff batches
- [Feedback console contract](reference/feedback-console-contract.md) —
  console-side semantics and persisted shape
- [Bridge protocol](reference/bridge-protocol.md) — sidekick ↔ console wire
  protocol and version compatibility
- [Privacy](reference/privacy.md) — what stays local, what's redacted, what to
  review before sharing
- [Security model](../SECURITY.md) — local-first debug threat model
- [Threat model](reference/threat-model.md) — assets, trust boundaries,
  in-scope and out-of-scope adversaries, current mitigations, and open gaps

## I want to understand the design

- [Architecture overview](architecture/overview.md) — module map, runtime
  flow, source-matching pipeline
- [Architecture Decision Records](architecture/adr/README.md) — durable
  decisions and their rationale
- [Console state sync (forward-looking design)](architecture/console-state-sync-design.md) —
  SSE migration plan; not yet shipped

## I want to contribute

- [Contributing guide](../CONTRIBUTING.md) — local checks before opening a PR
- [Release notes](releases/README.md) — GitHub Release summaries
- [Release readiness checklist](contributing/release-readiness.md) — what's
  blocking external publish
- [Release process](contributing/release-process.md) — SemVer policy,
  CHANGELOG, version-bump checklist

## Historical / archived

- [`archive/`](archive/README.md) — original Korean PRD, decisions log, and
  technical design (2026-05-03 era). Preserved for the design-trail history;
  no longer reflects current behavior.
- [`internal/`](internal/README.md) — implementation plans and design specs
  produced by the maintainers' AI-assisted workflow. Not part of the public
  docs surface; kept for transparency.

---

> Found a broken link or stale claim? Open an issue or PR — see the
> [Contributing guide](../CONTRIBUTING.md).
