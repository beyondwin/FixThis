# FixThis Repository Agent Kit Design

Date: 2026-07-19

## Summary

FixThis should make its repository-specific operating knowledge executable for
coding agents without depending on one maintainer's global Codex setup. The
first version introduces a small repository Agent Kit: a concise root
`AGENTS.md` contract, narrowly scoped nested guidance, repo-discovered
maintainer skills, a deterministic task and verification router, and semantic
contract tests that keep those surfaces aligned with the commands that the
repository actually supports.

This work does not add or modify `~/.codex`, project `.codex/config.toml`,
Codex hooks, or a development MCP registration. Those remain possible later
layers. The checked-in kit must work as ordinary repository content for Codex
and remain useful to other coding agents and human maintainers.

## Current State

FixThis already has unusually strong agent-facing material:

- root `AGENTS.md` and a smaller `CLAUDE.md` entry point;
- `docs/index.md`, `docs/guides/project-map.md`, and
  `docs/architecture/agent-code-compass.md` for source and validation routing;
- focused, changed-only, pre-push, full, release, and connected-device gates;
- architecture boundary tests and release-reality checks;
- installable workflows under `.codex-plugin/skills/`, including external-app
  integration skills and one FixThis-maintainer release smoke;
- contract tests for documentation links and selected plugin wording.

The gap is not a lack of documentation. The gap is that an agent must infer
which guidance matters, which command is current, and what evidence is required
before declaring completion. Most rules are descriptive rather than
machine-checkable.

The live audit found concrete examples:

- `.codex-plugin/skills/fixthis-release-smoke/SKILL.md` can omit the canonical
  `android:proof` entry point while its current contract test still passes;
- the root `AGENTS.md` does not require a branch, dirty-tree, upstream, and
  worktree preflight even though concurrent worktrees are common in this repo;
- `PASS`, `DEFERRED`, `SKIPPED`, and environmental failure are described in
  several places but are not one reusable completion contract;
- `.codex-plugin` installable skills and prospective automatically discovered
  repository-maintainer skills have different activation and audience rules,
  but that distinction is not encoded in tests;
- the available verification matrix is rich enough that an agent can run too
  little, run an unnecessarily expensive suite, or cite stale earlier output.

## Goals

- Make a fresh coding agent choose the correct maintained docs, source files,
  and focused checks with one deterministic entry point.
- Require repository-state preflight before edits without blocking safe work in
  an existing dirty worktree.
- Preserve module, persisted-schema, bridge, debug-only, artifact, and release
  boundaries at the closest useful guidance scope.
- Give agents reusable maintainer workflows without putting project procedure
  in personal `~/.codex` state.
- Turn command and guidance drift into failing tests.
- Standardize completion evidence so a deferred Android check cannot be
  reported as a pass and an old test result cannot prove a new diff.
- Keep the root guidance concise and prevent context growth from becoming a new
  failure mode.

## Non-Goals

- No changes to `~/.codex`, personal memories, global skills, installed
  plugins, or global MCP servers.
- No checked-in project `.codex/config.toml`, Codex hook, agent role, sandbox,
  approval, model, or reasoning default.
- No automatic commit, push, merge, release, server termination, emulator
  creation, or destructive Git recovery.
- No replacement for `CONTRIBUTING.md`, reference contracts, ADRs, CI, Gradle,
  or existing evidence runners.
- No duplication of complete command matrices inside every `AGENTS.md` or
  skill.
- No claim that repository guidance alone can enforce host permissions or
  modify an untrusted Codex environment.

## Design Principles

### Repository Source Of Truth

The kit points to maintained repository contracts instead of copying them.
Current implementation and `docs/reference/*` remain authoritative. Historical
files under `docs/superpowers/*`, `docs/specs/*`, and `docs/plans/*` can explain
past decisions but cannot silently override current behavior.

### Guidance, Workflow, And Enforcement Are Separate

- `AGENTS.md` states durable rules and routes.
- `.agents/skills/*` describes repeatable maintainer workflows.
- scripts compute task impact and verification requirements.
- tests and CI detect drift.

No one layer should contain the whole system.

### Narrowest Useful Scope

Nested guidance is added only when a subtree has a boundary that materially
differs from the root. It must not restate repository-wide rules. When the same
mistake occurs across modules, fix the root contract or router rather than
copying text into every module.

### Fresh Evidence Before Completion

An agent may use earlier results to choose a debugging path, but completion
requires commands run against the final relevant diff. Device-dependent checks
must report their actual state and residual risk.

## Architecture

```text
User task
  -> root AGENTS.md operational contract
  -> repo maintainer skill
  -> agent task router
       -> maintained docs and first source files
       -> focused checks
       -> broad gate and connected-proof requirements
  -> implementation or review
  -> verification evidence report
  -> semantic guidance contract tests
```

## Components

### Root `AGENTS.md`

Refactor the root file into a compact operating contract. It should retain the
product boundary and the required read order, then add four explicit phases.

#### Start

- inspect `git status --short --branch`, current branch and upstream, recent
  log, and `git worktree list --porcelain`;
- preserve unrelated dirty state and identify whether another worktree already
  owns the task;
- classify the request as explanation, diagnosis, implementation, review,
  release, connected proof, or feedback-queue work;
- run the task router before broad source exploration.

#### Change

- use the route's maintained references and current implementation;
- prefer focused tests and the smallest compatible change;
- preserve public and persisted contracts unless the task explicitly changes
  them;
- do not edit generated artifacts manually when a canonical generator exists;
- do not touch `.fixthis/` artifacts or personal/global agent configuration.

#### Verify

- run the focused checks selected for the touched surfaces;
- run the broader changed-only or full gate required by task risk;
- distinguish `PASS`, `FAIL`, `DEFERRED`, and `SKIPPED` with a reason;
- require `android:proof -- --strict` only where the maintained contract says
  connected product-path proof is part of done.

#### Finish

- re-read the final diff and repository state;
- report commands and fresh results, not only “tests pass”;
- state residual risks and local-versus-remote Git status;
- commit, merge, push, resolve feedback, or stop services only when the user
  requested that action.

Human-facing Quick Start detail should stay in `README.md` and maintained
guides. The root agent file should link there rather than carrying the entire
product tutorial in every agent context. The refactored root file has a maximum
of 130 physical lines; a contract test enforces that budget.

### Nested Guidance

Add small nested files only for the first high-value boundaries:

| Path | Local responsibility |
| --- | --- |
| `fixthis-compose-core/AGENTS.md` | Pure Kotlin boundary, schema-neutral domain behavior, focused domain tests. |
| `fixthis-compose-sidekick/AGENTS.md` | Debug-only Android runtime, bridge coordination, device-test limits. |
| `fixthis-mcp/AGENTS.md` | Session persistence, browser/server ownership, console bundle regeneration, compact-handoff parity. |
| `scripts/AGENTS.md` | Deterministic scripts, stable exit codes, report artifacts, cross-platform shell/Node expectations. |

Do not add files to `fixthis-cli`, `fixthis-gradle-plugin`, or `sample` in the
first pass. Their routing remains sufficiently covered by the project map and
root contract. Add a nested file later only after a repeated, local failure
demonstrates value. Each first-pass nested file has a maximum of 60 physical
lines.

### Repository Maintainer Skills

Create repo-discovered skills under `.agents/skills/`. They are for working on
the FixThis repository and must remain distinct from `.codex-plugin/skills/`,
which are installable workflows. The plugin's install, feedback, and evidence
skills target external Android apps; its existing release-smoke skill is the
explicit maintainer-only exception and must say that it runs from a FixThis
source checkout.

#### `fixthis-repository-change`

Use for implementation, diagnosis, or review inside this checkout. The skill:

1. performs state preflight;
2. calls the task router;
3. reads only the returned maintained references plus relevant source;
4. chooses focused tests before broad gates;
5. produces the standard evidence report.

It must not embed the full routing matrix. The script and maintained project map
remain the data sources.

#### `fixthis-release-maintenance`

Use for release, publication, compatibility, or public-install claims. It:

- separates repository checks from live registry reality;
- uses `release:reality`, `evidence:release`, `release:check`, and strict
  connected proof according to the maintained release contract;
- requires exact public evidence before saying an artifact is published;
- reports deferred external channels without treating them as product test
  failures.

This skill does not publish, tag, push, or edit downstream repositories unless
the user explicitly requests those state changes.

### Task And Verification Router

Add `scripts/agent-task-router.mjs` as a read-only command. It accepts a task
kind, optional changed-file input, and optional base reference. It outputs a
stable JSON object and a compact Markdown view.

Example interface:

```bash
npm run agent:route -- --task console --base origin/main
npm run agent:route -- --changed --json
```

The JSON contract is:

```json
{
  "schemaVersion": "1.0",
  "repositoryState": {
    "branch": "main",
    "upstream": "origin/main",
    "dirty": false,
    "worktreeCount": 1
  },
  "routes": [],
  "focusedChecks": [],
  "broadGate": null,
  "connectedProof": {
    "required": false,
    "command": "npm run android:proof -- --strict",
    "reason": null
  },
  "warnings": []
}
```

The router does not execute tests or mutate files. It maps task kinds and paths
to existing maintained docs, first source files, and canonical npm/Gradle
commands. Multiple touched surfaces produce a deduplicated union ordered from
cheap focused checks to broader gates.

Unknown paths do not yield an empty success. They produce a warning and the
safe fallback `npm run ci:local:changed`, leaving the agent to inspect the
change and add a route if the pattern recurs.

### Completion Evidence Contract

Agent-facing workflows use one small result shape:

| Field | Meaning |
| --- | --- |
| `command` | Exact command or manual check. |
| `status` | `PASS`, `FAIL`, `DEFERRED`, or `SKIPPED`. |
| `evidence` | Relevant output summary or report path. |
| `reason` | Required for `DEFERRED` and `SKIPPED`. |
| `residualRisk` | What remains unproved. |

`DEFERRED` means the repository supports the check but the current environment
cannot execute it. `SKIPPED` means the check was intentionally not run.
Neither is equivalent to `PASS`. A failure caused by code remains `FAIL`; an
unavailable or locked device is classified using the stable Android proof
failure surface.

### Semantic Contract Tests

Extend agent-guidance testing beyond keyword presence.

Tests must verify:

- every command named by root or nested guidance exists in `package.json`,
  Gradle tasks used by the repository, or an explicit allowlist of shell
  commands;
- router routes point to files that exist and commands that match the project
  map or a canonical command registry;
- high-risk route invariants remain true, including connected Android proof,
  console bundle checks, persisted schema checks, and release-reality checks;
- `.codex-plugin/skills/fixthis-release-smoke` names the current release and
  connected-proof entry points;
- the plugin's external-app integration skills never import repository-only
  maintainer procedures, while `fixthis-release-smoke` declares its maintainer
  audience and uses only canonical FixThis checkout commands;
- repo maintainer skills never instruct users of an external Android project to
  run FixThis's internal Gradle or release matrix;
- root guidance stays at or below 130 physical lines and each first-pass
  nested file stays at or below 60 physical lines;
- nested files do not duplicate prohibited root sections;
- docs continue to label historical planning sources as non-authoritative.

Use a data-driven route registry consumed by both the router and its tests.
Avoid scraping free-form prose as the only source of command truth.

## Data Flow

### Ordinary Code Change

1. Agent reads root guidance and performs repository-state preflight.
2. The maintainer skill calls the router with the task and changed paths.
3. Router returns maintained references, source entry points, focused checks,
   and any broad or connected gate.
4. Agent reads the returned references, implements the smallest change, and
   runs focused checks.
5. Agent reruns routing against the final diff so newly touched surfaces are
   included.
6. Agent runs the required broad gate and records completion evidence.
7. Agent rechecks Git state and reports requested closeout actions separately.

### Release Work

1. Release skill establishes the intended version and public channels.
2. Repository verification and live publication reality are collected as
   separate evidence classes.
3. Connected proof runs only when the release contract requires it and reports
   an exact failure/deferred code when unavailable.
4. The final report distinguishes local `HEAD`, remote branch state, tags,
   package registries, and downstream distribution repositories.

## Safety And Operational Blind Spots

| Blind spot | Design response |
| --- | --- |
| Existing dirty work or concurrent worktree | Preflight, preserve unrelated changes, and identify task ownership before editing. |
| Stale historical plan treated as current contract | Router returns maintained docs first and labels historical sources explicitly. |
| Generated console bundle edited or forgotten | MCP nested guidance and semantic route checks require generator plus `--check`. |
| Connected device reported as proof without product path | Canonical strict `android:proof` requirement and explicit status taxonomy. |
| Existing local server or emulator disrupted | No kill/restart action without scoped ownership or explicit user request. |
| Local commit confused with remote publication | Finish contract reports local, upstream, tag, registry, and downstream state separately. |
| Installable plugin skill confused with auto-discovered repo skill | Separate activation rules, explicit per-skill audiences, and cross-boundary tests. |
| New command silently drifts from docs and skills | Shared route registry and semantic contract tests. |
| Agent context bloats over time | Root budget, narrow nested files, and progressive skill disclosure. |
| Expensive full suite run too early | Focused-to-broad ordering; full release gate reserved for matching risk. |
| Environmental blocker hides a code failure | Stable `PASS`/`FAIL`/`DEFERRED`/`SKIPPED` classification with residual risk. |
| Personal Codex configuration leaks into repo setup | Explicit exclusion of `~/.codex` and project `.codex` in this version. |
| Local artifacts or secrets are staged | Existing ignore rules plus preflight/final staged-file inspection. |

## Validation

Implementation of this design must add focused tests for the router and
guidance contracts, then pass:

```bash
npm run agent:route:test
npm run docs:agent-guidance:test
npm run plugin:contract:test
node scripts/check-doc-consistency.mjs
bash scripts/check-docs-cli-surface.sh
npm run ci:local:changed
git diff --check
```

The exact new script names may be introduced by the implementation, but once
added they become canonical package commands and must be documented in
`CONTRIBUTING.md`. The implementation plan must include tests before changing
the corresponding guidance or router behavior.

## Rollout

### Phase 1: Contract And Drift Guard

- add the shared route registry and semantic tests;
- update the distributable plugin release skill to current commands;
- establish line budgets and audience-separation checks.

### Phase 2: Agent Guidance

- refactor root `AGENTS.md` around Start, Change, Verify, and Finish;
- add the four narrow nested guidance files;
- update documentation routing and contributor validation instructions.

### Phase 3: Maintainer Workflows

- add the two repo maintainer skills;
- add the read-only router and completion evidence rendering;
- validate representative console, CLI, core, Android, and release routes.

Do not add project `.codex` settings or hooks as part of these phases. Evaluate
them only after the repository-only kit has produced real usage evidence and a
specific unenforced failure remains.

## Success Criteria

- A fresh agent can identify first docs, first source files, and focused checks
  for every current project-map work type through one command.
- The router returns a safe non-empty fallback for an unknown changed path.
- A stale plugin or guidance command causes a focused test failure.
- Console, schema, bridge, release, and connected-proof routes retain their
  high-risk invariants in tests.
- Completion reports never equate deferred or skipped Android proof with pass.
- The root `AGENTS.md` is at most 130 physical lines and remains operational
  while maintained product tutorials stay discoverable.
- Repo maintainer skills and installable plugin skills cannot silently cross
  their declared per-skill audience boundary.
- No implementation file under `~/.codex` or project `.codex/` is created or
  modified.

## Alternatives Considered

### Keep Improving Only `AGENTS.md`

This has low initial cost but leaves command selection, completion evidence,
and semantic drift dependent on model interpretation. It does not address the
observed plugin test false negative.

### Add Full Project `.codex` Configuration And Hooks Now

Project configuration and hooks could enforce lifecycle behavior more strongly,
but they introduce trust, host-version, and Codex-specific operational
dependencies before the portable repository layer is proven. They are deferred,
not rejected.

### Put All Maintainer Workflows In `.codex-plugin`

The installable plugin already carries a narrow release-smoke workflow for
FixThis maintainers, but making every repository workflow plugin-dependent
would prevent automatic discovery in a fresh source checkout and would expose
architecture and contribution procedures to external-app users. Broad repo
workflows belong under `.agents/skills`; the existing plugin release skill stays
narrow, explicitly maintainer-only, and semantically checked.

## Decision

Proceed with the repository-only Agent Kit. Use portable agent guidance,
repo-discovered maintainer skills, deterministic routing, and semantic contract
tests first. Keep global and project Codex configuration out of scope until a
later, evidence-backed design explicitly approves it.
