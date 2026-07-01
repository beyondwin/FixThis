# FixThis Documentation

FixThis is a debug-only sidekick for Jetpack Compose Android apps that hands UI
context to AI coding agents — point at a UI element in a desktop browser
console, type what you want, and the agent gets a structured prompt with the
target nailed down.

This page is a directory. Start with the [project README](../README.md) for the
pitch, scope, and sample-to-agent Quick Start.

## Start Here

| Reader or task | Read first | Then read |
| --- | --- | --- |
| Try FixThis without changing your app | [Quick Start - sample app](getting-started/try-the-sample.md) | [Feedback console tour](guides/feedback-console-tour.md) |
| Add FixThis to an external Compose app | [Add FixThis to your app](getting-started/add-to-your-app.md) | [CLI reference](reference/cli.md), [Agent setup schema](reference/agent-setup-schema.md) |
| Connect Claude Code, Codex, Cursor, or ChatGPT | [Connect your AI agent](getting-started/connect-your-agent.md) | [Working with AI agents](guides/agents.md), [MCP tools reference](reference/mcp-tools.md) |
| Work inside this repository as an agent | [Project map](guides/project-map.md) | [AGENTS.md](../AGENTS.md), task-specific reference docs |
| Maintain FixThis architecture or tooling | [Project map](guides/project-map.md) | [Architecture overview](architecture/overview.md), [Fullstack/tooling handover](guides/fullstack-tooling-handover.md) |
| Change a compatibility contract | [Reference contracts](#reference-contracts) | The implementation files named by [Project map](guides/project-map.md) |
| Prepare or audit a release | [Release readiness checklist](contributing/release-readiness.md) | [Release process](contributing/release-process.md), [Contributing guide](../CONTRIBUTING.md) |
| Understand historical design context | [Historical planning](#historical-planning) | Current reference docs before changing behavior |

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
- [CLI exit codes](reference/cli-exit-codes.md) — the shared exit-code contract
  every `fixthis` command returns.
- [Agent setup handoff schema](reference/agent-setup-schema.md) —
  `.fixthis/agent-setup.json` fields written by `fixthis install-agent`.
- [MCP tools reference](reference/mcp-tools.md) — tool signatures and return shapes.
- [Output schema](reference/output-schema.md) — persisted JSON fields and handoff shape.
- [Source matching](reference/source-matching.md) — source-index schema and how
  the runtime matcher ranks candidates.
- [Feedback console contract](reference/feedback-console-contract.md) — console behavior and compact prompt grammar.
- [Bridge protocol](reference/bridge-protocol.md) — sidekick/desktop wire protocol.
- [Compatibility matrix](reference/compatibility.md) — tested and minimum toolchain axes.
- [Privacy](reference/privacy.md), [Security](../SECURITY.md), and [Threat model](reference/threat-model.md) — local artifacts, trust boundaries, and security model.

## Architecture and Design

- [Project map](guides/project-map.md) - compact repository map for humans and agents: module responsibilities, first files, task routes, validation commands, and artifact boundaries
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

## Historical Planning

- `docs/superpowers/specs/` and `docs/superpowers/plans/` contain approved design and implementation-plan artifacts from previous agentic work.
- `docs/specs/` and `docs/plans/` contain older project planning records that may still explain why a feature exists.
- These files are historical context. They are not the current source of truth for CLI flags, MCP tool shapes, bridge protocol fields, persisted JSON contracts, or release readiness unless a maintained guide, reference page, or source file explicitly points to them.

---

> Found a broken link or stale claim? Open an issue or PR — see the
> [Contributing guide](../CONTRIBUTING.md).
