# FixThis Docs

FixThis is a debug-only helper for Jetpack Compose. Point at a UI in a local
browser console, write the change, and hand an agent a compact prompt with the
target pinned.

Start with the [README](../README.md) if you just want to try it. Terms such
as sidekick, bridge, handoff, and source candidates:
[architecture overview](architecture/overview.md#words-we-use).

## Start Here

| You want to | Open |
| --- | --- |
| Try it without touching your app | [Sample quick start](getting-started/try-the-sample.md) |
| Add it to your Compose debug app | [Add to your app](getting-started/add-to-your-app.md) |
| Connect Claude Code, Codex, Cursor, or a chat agent | [Connect your agent](getting-started/connect-your-agent.md) |
| Use the browser console | [Console tour](guides/feedback-console-tour.md) |
| Fix a setup or device problem | [Troubleshooting](guides/troubleshooting.md) |
| Work inside this repo | [AGENTS.md](../AGENTS.md) and [project map](guides/project-map.md) |
| Ship a release | [Release readiness](contributing/release-readiness.md) |

## How It Fits Together

The Android app shows connection status. Selection, annotation, and handoff
happen in the desktop console. Desktop `fixthis-mcp` owns HTTP, MCP, session
state, and `.fixthis/`.

Short glossary and ownership map:
[architecture overview](architecture/overview.md#words-we-use).

Two handoff paths share the same evidence:

- **Copy Prompt** — paste Markdown into any chat agent.
- **Save to MCP** — local queue for Claude Code / Codex.

Do not commit `.fixthis/`.

## Product

- [Product](product/README.md) — what it is, who it is for, what V1 does not do
- [Decisions](product/decision-rationale.md) — the main trade-offs
- [Roadmap](product/roadmap.md) — shipped work and what is still deferred
- [Handoff prompt](design/handoff-prompt-rationale.md) — why the compact prompt looks like this

## Architecture

- [Overview](architecture/overview.md) — who owns what, glossary, runtime path
- [Project map](guides/project-map.md) — modules, first files, checks
- [Agent code compass](architecture/agent-code-compass.md) — task routes and boundaries
- [ADRs](architecture/adr/README.md) — decisions that still constrain the code
- [Console sync](architecture/console-state-sync-design.md) — live events, polling fallback
- [Source-matching lab](guides/source-matching-fixture-lab.md) — local fixture evidence

## Contribute

- [Contributing](../CONTRIBUTING.md) — local checks before a PR
- [Release process](contributing/release-process.md)
- [Release notes](releases/README.md)
- [Required PR checks](contributing/required-checks.md)
- [Connected tests](contributing/connected-tests.md)

## Reference Contracts

Stable CLI, MCP, bridge, JSON, and console behavior:

- [CLI](reference/cli.md), [exit codes](reference/cli-exit-codes.md)
- [Agent setup schema](reference/agent-setup-schema.md)
- [MCP tools](reference/mcp-tools.md)
- [Output schema](reference/output-schema.md)
- [Source matching](reference/source-matching.md)
- [Console contract](reference/feedback-console-contract.md)
- [Bridge protocol](reference/bridge-protocol.md)
- [Compatibility](reference/compatibility.md)
- [Privacy](reference/privacy.md), [Security](../SECURITY.md), [Threat model](reference/threat-model.md)

## Historical Planning

Frozen snapshots. Not current contracts. Do not implement from them.

- [docs/superpowers/](superpowers/README.md) — old agent specs, plans, notes
- [docs/specs/](specs/README.md) — older project specs
- [docs/plans/](plans/README.md) — older implementation plans

Prefer `docs/reference/` and the code when they disagree. Tagged GitHub
Release notes under [docs/releases/](releases/README.md) are frozen copies
of what shipped; edit [unreleased.md](releases/unreleased.md) for work after
the latest tag.
