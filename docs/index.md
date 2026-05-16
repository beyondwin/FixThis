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
| Understand the product and prompt rationale in one place | [Concept and handoff rationale](product/concept-and-handoff-rationale.md) |
| Understand what FixThis is and is not | [Product concept](product/README.md) |
| Understand why key product/technical choices were made | [Decision rationale](product/decision-rationale.md) |
| See current scope and planned work | [Roadmap](product/roadmap.md) |
| Fix setup or device problems | [Troubleshooting](guides/troubleshooting.md) |

## Product and Design

- [Concept and handoff rationale](product/concept-and-handoff-rationale.md) —
  self-contained overview of the product concept, major trade-offs, and compact
  prompt design.
- [Product concept](product/README.md) — current concept, target users,
  principles, scope, and non-goals.
- [Decision rationale](product/decision-rationale.md) — maintained summary of
  the major product and architecture trade-offs.
- [Roadmap](product/roadmap.md) — V1 scope, high-priority follow-up work, and
  explicitly deferred areas.
- [Design rationale](design/README.md) — maintained explanations for important
  workflow designs that are not API contracts.
- [Handoff prompt rationale](design/handoff-prompt-rationale.md) — why compact
  prompts include target summaries, source candidates, IDs, confidence, warning
  signals, and server-rendered parity.

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
  active-session fences on session/preview events

## Contribute and Release

- [Contributing guide](../CONTRIBUTING.md) — local checks before opening a PR
- [Release notes](releases/README.md) — GitHub Release summaries, including
  the latest public release and current unreleased changes
- [Release readiness checklist](contributing/release-readiness.md) — current
  public channels and remaining package targets
- [Release process](contributing/release-process.md) — SemVer policy,
  CHANGELOG, version-bump checklist
- [Required PR checks tracker](contributing/required-checks.md) — observation
  windows before advisory checks become branch-protection requirements
- [Connected test triage](contributing/connected-tests.md) — nightly
  instrumented-test policy and flake handling

---

> Found a broken link or stale claim? Open an issue or PR — see the
> [Contributing guide](../CONTRIBUTING.md).
