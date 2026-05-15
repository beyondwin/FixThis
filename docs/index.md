# FixThis Documentation

FixThis is a debug-only sidekick for Jetpack Compose Android apps that hands UI
context to AI coding agents — point at a UI element in a desktop browser
console, type what you want, and the agent gets a structured prompt with the
target nailed down.

This page is a directory. Start with the [project README](../README.md) for the
pitch, scope, and sample-to-agent Quick Start.

## Start Here

| I want to... | Read |
| --- | --- |
| Try FixThis without changing my app | [Quick Start — sample app](getting-started/try-the-sample.md) |
| Add FixThis to my Compose debug build | [Add FixThis to your app](getting-started/add-to-your-app.md) |
| Connect Claude Code, Codex, Cursor, or ChatGPT | [Connect your AI agent](getting-started/connect-your-agent.md) |
| Learn the browser annotation workflow | [Feedback console tour](guides/feedback-console-tour.md) |
| Fix setup or device problems | [Troubleshooting](guides/troubleshooting.md) |

## Reference Contracts

- [CLI reference](reference/cli.md) — command flags and setup modes.
- [MCP tools reference](reference/mcp-tools.md) — tool signatures and return shapes.
- [Output schema](reference/output-schema.md) — persisted JSON fields and handoff shape.
- [Feedback console contract](reference/feedback-console-contract.md) — console behavior and compact prompt grammar.
- [Bridge protocol](reference/bridge-protocol.md) — sidekick/desktop wire protocol.
- [Compatibility matrix](reference/compatibility.md) — tested and minimum toolchain axes.
- [Privacy](reference/privacy.md), [Security](../SECURITY.md), and [Threat model](reference/threat-model.md) — local artifacts, trust boundaries, and security model.

## Architecture and Design

- [Architecture overview](architecture/overview.md) — module map, runtime
  flow, source-matching pipeline
- [Architecture Decision Records](architecture/adr/README.md) — durable
  decisions and their rationale
- [Console state sync](architecture/console-state-sync-design.md) — shipped
  `/api/events` SSE Phase 1, with ETag polling retained as fallback and
  remaining preview-polling follow-ups

## Contribute and Release

- [Contributing guide](../CONTRIBUTING.md) — local checks before opening a PR
- [Release notes](releases/README.md) — GitHub Release summaries, including
  unreleased changes since v0.1.0
- [Release readiness checklist](contributing/release-readiness.md) — what's
  blocking external publish
- [Release process](contributing/release-process.md) — SemVer policy,
  CHANGELOG, version-bump checklist

## Historical / archived

- [`archive/`](archive/README.md) — original Korean PRD, decisions log, and
  technical design (2026-05-03 era). Preserved for the design-trail history;
  no longer reflects current behavior.
- [`superpowers/`](superpowers/) — implementation plans and design specs
  produced by the maintainers' AI-assisted workflow. Companion specs and
  plans for hardening tracks live under [`specs/`](specs/) and
  [`plans/`](plans/). These are design records, not current API contracts;
  reference docs, release notes, and tests take precedence when they differ.

---

> Found a broken link or stale claim? Open an issue or PR — see the
> [Contributing guide](../CONTRIBUTING.md).
