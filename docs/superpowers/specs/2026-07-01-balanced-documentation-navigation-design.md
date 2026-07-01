# Balanced Documentation Navigation Design

## Summary

FixThis already has rich documentation, but the navigation layer does not make
the current reading path obvious enough for both human developers and coding
agents. The improvement should add a thin, durable documentation navigation
layer instead of moving large parts of the docs tree.

The chosen direction is a balanced docs navigation layer: keep the existing
README, guides, reference contracts, architecture docs, and historical planning
artifacts, but clarify which documents are entry points, which are source of
truth contracts, and which are historical context.

## Goals

- Help a new developer understand the product, module map, first files to open,
  and validation commands without reading every long-form guide.
- Help an agent quickly identify the correct source-of-truth documents and avoid
  treating historical planning documents as current contracts.
- Preserve current docs URLs where practical and avoid a broad directory
  migration.
- Reduce repeated setup wording in top-level docs by routing detailed CLI and
  MCP setup to maintained getting-started and reference documents.
- Add lightweight checks so the navigation layer does not drift after future
  CLI, docs, or agent-workflow changes.

## Non-Goals

- Do not rewrite every documentation page.
- Do not move `docs/superpowers/`, `docs/specs/`, or `docs/plans/` in this
  pass.
- Do not change CLI, MCP, bridge, persisted JSON, Gradle plugin, or runtime
  behavior.
- Do not make Graphify a product dependency or source of truth.
- Do not add strict prose-style linting. The drift checks should validate
  structure and links, not writing style.

## Current Problems

The existing documentation set is useful but hard to traverse:

- `README.md`, `AGENTS.md`, `MCP.md`,
  `docs/getting-started/connect-your-agent.md`, and
  `docs/getting-started/add-to-your-app.md` repeat setup concepts such as
  `fixthis install-agent`, `fixthis init`, `./scripts/bootstrap-mcp.sh`,
  `./gradlew fixthisSetup`, **Copy Prompt**, and **Save to MCP**.
- `docs/superpowers/` contains many historical specs and plans. Those files are
  valuable for project memory, but broad search and Graphify queries can surface
  them before current reference contracts.
- `docs/guides/fullstack-tooling-handover.md` is a strong deep-dive handover,
  but it is too long to be the first map for most humans or agents.
- `docs/reference/*` already acts as the compatibility contract layer, but the
  docs navigation should label that role more explicitly.

## Chosen Approach

Add a documentation navigation layer:

```text
README.md
  -> product understanding, supported paths, primary quick starts

AGENTS.md
  -> repository-internal agent read order, constraints, source-of-truth priority

docs/index.md
  -> full documentation hub split by reader and task

docs/guides/project-map.md
  -> shared developer/agent map with modules, first files, flows, and checks

docs/guides/agents.md
  -> how to use FixThis as an agent workflow

docs/reference/*
  -> compatibility contracts and stable CLI/MCP/schema/protocol surfaces

docs/superpowers/*
docs/specs/*
docs/plans/*
  -> historical planning and implementation context unless explicitly linked
     from current maintained docs
```

This keeps existing documentation discoverable while creating a small number of
obvious entry points.

## Document-Level Design

### `README.md`

Role: external visitor and first-time user entry point.

Planned changes:

- Keep the product explanation, Works Today list, representative Quick Starts,
  Pick Your Path, module map, status, privacy, and roadmap links.
- Reduce detailed install repetition where possible and point to:
  - `docs/getting-started/add-to-your-app.md`
  - `docs/getting-started/connect-your-agent.md`
  - `docs/reference/cli.md`
- Add a small "How to read the docs" section that routes users, agents, and
  maintainers to the right next document.
- Keep the `AGENTS.md` link and clarify that agents working inside this repo
  should read it before changing files.

### `AGENTS.md`

Role: top-level execution rules for agents working inside this repository.

Planned changes:

- Add a "Read Order" near the top:
  1. `AGENTS.md`
  2. `docs/index.md`
  3. `docs/guides/project-map.md`
  4. Task-specific guide or reference contract
  5. `docs/superpowers/*` only for historical design context
- State that `docs/reference/*` and current implementation sources override
  historical planning docs.
- Clarify that `docs/superpowers/*` is a planning archive, not the current API,
  CLI, MCP, bridge, or persisted JSON contract.
- Keep existing MCP tool index, constraints, Graphify guidance, and module map.
- Preserve the rule that Graphify is advisory context and behavior changes must
  be verified against actual Kotlin, JavaScript, Gradle, and Markdown sources.

### `docs/index.md`

Role: canonical documentation hub.

Planned changes:

- Rework `Start Here` around reader/task paths:
  - New user
  - External app developer
  - Coding agent
  - FixThis maintainer
  - Release maintainer
- Place `docs/guides/project-map.md` at the top of architecture/maintainer
  navigation.
- Label `Reference Contracts` as source-of-truth compatibility documents.
- Add a `Historical Planning` section that explains the roles of
  `docs/superpowers/`, `docs/specs/`, and `docs/plans/`.

### `docs/guides/project-map.md`

Role: compact shared repo map for humans and agents.

Required content:

- One-sentence product definition.
- Current runtime flow from debug Compose app to desktop CLI/MCP, browser
  console, local handoff, and agent queue.
- Module responsibility map for:
  - `:app`
  - `:fixthis-compose-core`
  - `:fixthis-compose-sidekick`
  - `fixthis-gradle-plugin`
  - `:fixthis-cli`
  - `:fixthis-mcp`
- For each module: responsibility, forbidden dependencies, first files to open,
  common change types, and focused validation commands.
- Task-oriented table for common work:
  - external app setup
  - agent/MCP workflow
  - compact handoff/output schema
  - source matching and target reliability
  - bridge protocol and Android runtime
  - console UI/session lifecycle
  - release readiness
- Source-of-truth priority:
  1. current implementation
  2. `docs/reference/*` for stable contracts
  3. `CONTRIBUTING.md` and release docs for checks
  4. `docs/guides/*` and architecture docs for workflow explanation
  5. `docs/superpowers/*`, `docs/specs/*`, and `docs/plans/*` for historical
     context
- Artifact warnings for `.fixthis/`, `graphify-out/`, build outputs, generated
  source-matching fixtures, and local screenshots.

This document should link to `docs/guides/fullstack-tooling-handover.md` for the
long-form explanation instead of duplicating the whole handover.

### Existing Getting-Started, Guide, And Reference Docs

Role: keep their current job, but reduce unnecessary repetition.

Planned changes:

- Keep how-to instructions in getting-started pages.
- Keep compatibility contracts in reference pages.
- Keep workflow explanations in guide pages.
- Prefer one canonical setup sequence per scenario, then link to the relevant
  reference page for flags and edge cases.
- Do not weaken the existing statements that FixThis is debug-only,
  Compose-only, local-first, and that `.fixthis/` should not be committed.

## Drift Prevention

Reuse existing checks:

```bash
node scripts/check-doc-consistency.mjs
bash scripts/check-docs-cli-surface.sh
git diff --check
```

Extend `scripts/check-doc-consistency.mjs` with structural checks:

- `README.md`, `AGENTS.md`, and `docs/index.md` link to
  `docs/guides/project-map.md`.
- `AGENTS.md` or `docs/index.md` describes `docs/superpowers` as historical
  planning context.
- `docs/index.md` includes both `Reference Contracts` and
  `Historical Planning` sections.
- `docs/guides/project-map.md` mentions all primary modules:
  - `:app`
  - `:fixthis-compose-core`
  - `:fixthis-compose-sidekick`
  - `fixthis-gradle-plugin`
  - `:fixthis-cli`
  - `:fixthis-mcp`

These checks should be structural and low-maintenance. They should not enforce
wording, headings beyond required section presence, or prose style.

## Acceptance Criteria

- A new developer can reach the project map, setup guide, reference contracts,
  and validation commands from `README.md` or `docs/index.md` within three
  clicks.
- An agent can read `AGENTS.md` and immediately know the read order,
  source-of-truth priority, Graphify boundary, and historical-docs boundary.
- The new `project-map.md` gives module-level first files and focused validation
  commands without replacing the long-form handover guide.
- Setup wording is less duplicated at the top level and points to canonical
  getting-started/reference docs for details.
- `docs/superpowers/` is clearly labeled as historical planning context.
- Existing and added documentation consistency checks pass.

## Verification Plan

Run after implementing the documentation changes:

```bash
node scripts/check-doc-consistency.mjs
bash scripts/check-docs-cli-surface.sh
git diff --check
```

If implementation changes source code, run `graphify update .` afterward and do
not stage `graphify-out/`. For a docs-only implementation, Graphify is optional
unless the agent instructions or touched docs require a graph refresh.

## Implementation Notes

- Keep the implementation scoped to Markdown and the docs consistency checker
  unless a broken docs/CLI contract is discovered.
- Prefer editing existing docs in place over moving files.
- Keep `docs/guides/fullstack-tooling-handover.md` as the deep maintainer guide.
- Keep `docs/reference/*` contract wording precise; do not shorten reference
  documents just to reduce line count.
- Avoid adding generated tables that require custom tooling to maintain.
