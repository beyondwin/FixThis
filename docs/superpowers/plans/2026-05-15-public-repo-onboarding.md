# Public Repo Onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rework FixThis public repository onboarding so Android Compose developers using AI coding agents can reach a sample-to-agent handoff quickly and understand the repository structure without reading reference docs first.

**Architecture:** This is a documentation-only change. README becomes the public first-success path, `docs/getting-started/` becomes the step-by-step onboarding layer, `docs/reference/` remains the canonical contract layer, and `AGENTS.md` stays agent-facing. No runtime code, MCP schema, bridge protocol, console behavior, or Gradle behavior changes.

**Tech Stack:** Markdown documentation, existing `scripts/check-doc-consistency.mjs`, GitHub-flavored Markdown anchors, existing docs under `README.md`, `AGENTS.md`, and `docs/`.

---

## Pre-Flight Notes

- The worktree may already contain user edits. At plan-writing time, `README.md` had an unstaged change adding browser-console selection language. Do not revert or overwrite unrelated user edits. Preserve useful user edits when restructuring README.
- This plan intentionally does not modify production code.
- Use current console vocabulary in public onboarding paths: **Annotate**, **Add annotation**, **Copy Prompt**, **Save to MCP**.
- Do not rename persisted JSON fields or document any schema changes.

## File Structure

Modify:

- `README.md` — public first-success entrypoint. Reorder around product value, "Works Today", sample-to-agent Quick Start, path table, trust notes, compact module map, and roadmap.
- `docs/getting-started/try-the-sample.md` — detailed sample walkthrough ending at Copy Prompt or Save to MCP, with explicit success state.
- `docs/getting-started/add-to-your-app.md` — own-app integration guide with pre-publication/composite-build reality, debug-only behavior, bootstrap path, and next links.
- `docs/guides/agents.md` — deeper agent workflow reference. Add an upfront pointer to the new getting-started guide and keep advanced details here.
- `docs/index.md` — role-based docs map that includes the new agent connection guide.
- `AGENTS.md` — keep agent-facing; update links so human first-run details point to README/getting-started and agent connection details point to the new guide.

Create:

- `docs/getting-started/connect-your-agent.md` — first-run agent connection guide for Claude Code, Codex, and chat-style agents.

Do not modify:

- `docs/reference/cli.md`
- `docs/reference/mcp-tools.md`
- `docs/reference/output-schema.md`
- `docs/reference/feedback-console-contract.md`
- `docs/reference/bridge-protocol.md`
- `docs/reference/compatibility.md`
- `docs/reference/privacy.md`
- production Kotlin/JS/Gradle files

## Task 1: Baseline Documentation Drift Check

**Files:**
- Read: `README.md`
- Read: `AGENTS.md`
- Read: `docs/index.md`
- Read: `docs/getting-started/try-the-sample.md`
- Read: `docs/getting-started/add-to-your-app.md`
- Read: `docs/guides/agents.md`

- [ ] **Step 1: Record current worktree status**

Run:

```bash
git status --short
```

Expected: note any pre-existing unstaged files. If `README.md` is already modified, inspect and preserve the user's edits.

- [ ] **Step 2: Inspect current public workflow vocabulary**

Run:

```bash
rg -n "Send Agent|Save snapshot|Add to Pending|Add/Save|Copy Prompt|Save to MCP|Annotate|Add annotation" README.md AGENTS.md docs/getting-started docs/guides docs/index.md
```

Expected: current docs use `Copy Prompt`, `Save to MCP`, `Annotate`, and `Add annotation` in active getting-started paths. Any stale terms in active docs should be updated in later tasks. Historical archive hits are not relevant here because this command excludes `docs/archive`.

- [ ] **Step 3: Run existing doc consistency check before edits**

Run:

```bash
node scripts/check-doc-consistency.mjs
```

Expected: `All doc-consistency rules pass.`

- [ ] **Step 4: Commit nothing**

Do not commit this baseline task. It only establishes the starting state.

## Task 2: Restructure README Around 5-Min Agent Handoff

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Replace the introduction and quick facts**

Edit the top of `README.md` after the badges and hero image so the first screen leads with agent handoff value and current support facts.

Use this structure, preserving the existing hero image line:

```markdown
# FixThis for Android Compose

[badges stay here]

![FixThis Studio — point at any Jetpack Compose UI element, annotate, hand off AI-ready context to your coding agent](docs/assets/fixthis-studio-hero.png)

Point at a Jetpack Compose UI, write the change you want, and hand Claude,
Codex, Cursor, or another coding agent the source context it needs.

FixThis adds a debug-only sidekick to a Compose app, mirrors the current screen
into a local browser console, and turns your UI annotations into a compact
agent handoff with screenshot bounds, semantics context, source candidates, and
target-confidence warnings.

## Works Today

- Try the bundled sample app in about five minutes.
- Use **Copy Prompt** with Cursor, ChatGPT, or any chat-style coding agent.
- Use **Save to MCP** with Claude Code or Codex after running the bootstrap script.
- Runs locally over ADB and `127.0.0.1`; FixThis makes no external API calls.
- Debug builds only. Jetpack Compose only.
- External Gradle artifacts are not published yet; your own app needs composite-build or local repository wiring for now.
```

Keep the existing user-added paragraph about clicking a component or dragging a visual area if it is still useful, but place it after the first value paragraph or fold it into the console workflow description. Do not remove the point that drag selection covers spacing, empty room, interop content, and non-component regions.

- [ ] **Step 2: Rename Quick Start and make the done state explicit**

Replace the current `## Quick Start (sample app, ~5 min)` section with:

```markdown
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
3. Type the requested change.
4. Click **Add annotation**.
5. Click **Copy Prompt** for any chat-style agent, or **Save to MCP** for
   Claude Code / Codex.

You are done when the console shows a numbered annotation and you have either
copied compact Markdown or saved a local MCP handoff.
```

When inserting the fenced command block inside Markdown, ensure the nested code fence is valid in the actual file. Do not paste the outer plan fence markers.

- [ ] **Step 3: Add Pick Your Path navigation**

Replace the current "Documentation" table near the middle of README with a shorter path table:

```markdown
## Pick Your Path

| Goal | Start here |
| --- | --- |
| Try FixThis without touching your app | [Quick Start with the sample](docs/getting-started/try-the-sample.md) |
| Add FixThis to your Compose debug build | [Add FixThis to your app](docs/getting-started/add-to-your-app.md) |
| Connect Claude Code, Codex, Cursor, or a chat agent | [Connect your agent](docs/getting-started/connect-your-agent.md) |
| Learn the browser console workflow | [Feedback console tour](docs/guides/feedback-console-tour.md) |
| Diagnose setup problems | [Troubleshooting](docs/guides/troubleshooting.md) |
| Inspect CLI, MCP, or JSON contracts | [Documentation index](docs/index.md) |
| Contribute | [Contributing guide](CONTRIBUTING.md) |
```

The full detailed documentation table should live in `docs/index.md`, not README.

- [ ] **Step 4: Add compact trust note**

Add a short `## Trust and Privacy` section after Pick Your Path:

```markdown
## Trust and Privacy

FixThis is local-first: the sidekick talks to the desktop tools over ADB, the
browser console binds to localhost, and **Save to MCP** writes local files under
`.fixthis/`. FixThis does not call an external AI API.

Screenshots may still contain sensitive pixels. Review copied prompts or local
artifacts before sharing them outside your machine, and do not commit
`.fixthis/`.

Details: [Privacy](docs/reference/privacy.md), [Security](SECURITY.md), and
[Threat model](docs/reference/threat-model.md).
```

- [ ] **Step 5: Keep Scope, Status, Roadmap, License concise**

Preserve the existing scope/status/roadmap/license content, but make sure it does not appear before Quick Start. Keep the pre-publication Maven/Plugin Portal warning in `Status`.

- [ ] **Step 6: Verify README anchors**

Run:

```bash
node scripts/check-doc-consistency.mjs
```

Expected: if `AGENTS.md` still links to `README.md#quick-start-sample-app-5-min`, this may fail because the heading changed. If it fails, update AGENTS in Task 6 to point to `README.md#quick-start-sample-app-to-agent-handoff`, then rerun in Task 7.

- [ ] **Step 7: Commit README restructuring**

Run:

```bash
git diff -- README.md
git add README.md
git commit -m "docs: center readme on agent handoff quick start"
```

Expected: commit includes only `README.md`.

## Task 3: Add First-Run Agent Connection Guide

**Files:**
- Create: `docs/getting-started/connect-your-agent.md`
- Modify: `docs/guides/agents.md`

- [ ] **Step 1: Create the new getting-started guide**

Create `docs/getting-started/connect-your-agent.md` with this content:

```markdown
# Connect Your AI Agent

FixThis supports two handoff modes:

| Agent style | Use | Setup |
| --- | --- | --- |
| Claude Code | **Save to MCP** | `./scripts/bootstrap-mcp.sh --package <applicationId> --target claude` |
| Codex | **Save to MCP** | `./scripts/bootstrap-mcp.sh --package <applicationId> --target codex` |
| Cursor, ChatGPT, or another chat-style agent | **Copy Prompt** | No MCP setup; paste the copied Markdown |

Both modes use the same evidence. **Copy Prompt** puts compact Markdown on your
clipboard. **Save to MCP** writes the same handoff locally so an MCP-aware
agent can read, claim, and resolve feedback items.

## Prerequisites

- Run the bundled sample or a Compose debug build with FixThis installed.
- Know the Android `applicationId` for the app you want to inspect.
- Keep ADB on your PATH and use an unlocked device or emulator.

For the sample app, the package is `io.github.beyondwin.fixthis.sample`.

## Claude Code

```bash
./scripts/bootstrap-mcp.sh --package <applicationId> --target claude
```

The script builds the local CLI/MCP distributions and writes project-local
Claude Code settings under `.claude/settings.json`. Restart Claude Code after
the script finishes.

In Claude Code, open the console:

```text
fixthis_open_feedback_console
```

After you click **Save to MCP** in the console, ask Claude Code:

```text
Read the latest FixThis handoff, claim the item, make the change, and mark it resolved when done.
```

## Codex

```bash
./scripts/bootstrap-mcp.sh --package <applicationId> --target codex
```

The script builds the local CLI/MCP distributions and writes Codex MCP config
to `~/.codex/config.toml`. Restart Codex after the script finishes.

In Codex, open the console:

```text
fixthis_open_feedback_console
```

After **Save to MCP**, ask Codex:

```text
Read the latest FixThis handoff, claim the item, make the change, and mark it resolved when done.
```

## Cursor, ChatGPT, and Chat-Style Agents

Use **Copy Prompt**:

1. Open FixThis Studio.
2. Click **Annotate**.
3. Select a UI element or drag a visual area.
4. Type the change request.
5. Click **Add annotation**.
6. Click **Copy Prompt**.
7. Paste the Markdown into your agent.

No MCP restart or config file is required for this mode.

## MCP Queue Lifecycle

MCP-aware agents should follow this lifecycle:

1. `fixthis_read_feedback` to read the compact Markdown and JSON evidence.
2. `fixthis_claim_feedback` before editing, so the console shows the item as in progress.
3. `fixthis_resolve_feedback` after editing, with `resolved`, `needs_clarification`, or `wont_fix`.

Full signatures live in the [MCP tools reference](../reference/mcp-tools.md).
CLI setup flags live in the [CLI reference](../reference/cli.md).

## Locality and Artifacts

FixThis does not call an external AI API. **Save to MCP** writes local handoff
files under `.fixthis/feedback-sessions/`, and **Copy Prompt** writes compact
Markdown to your clipboard. Do not commit `.fixthis/`.

## Next

- [Try the sample app](try-the-sample.md)
- [Add FixThis to your app](add-to-your-app.md)
- [Working with AI agents](../guides/agents.md)
- [Troubleshooting](../guides/troubleshooting.md)
```

- [ ] **Step 2: Trim `docs/guides/agents.md` introduction**

Modify the top of `docs/guides/agents.md` so it points first-run users to the new guide:

```markdown
# Working with AI Agents

For first-time setup, start with
[Connect Your AI Agent](../getting-started/connect-your-agent.md). This guide
keeps the deeper workflow details: queue behavior, claim/resolve semantics,
shared mode behavior, and target reliability warnings.
```

Keep the existing mode table and deep sections below unless they become exact duplicates. Do not remove the target reliability warning section.

- [ ] **Step 3: Run link and anchor check**

Run:

```bash
node scripts/check-doc-consistency.mjs
```

Expected: pass, or fail only for README/AGENTS anchors that Task 6 will update. Do not ignore other failures.

- [ ] **Step 4: Commit agent guide split**

Run:

```bash
git diff -- docs/getting-started/connect-your-agent.md docs/guides/agents.md
git add docs/getting-started/connect-your-agent.md docs/guides/agents.md
git commit -m "docs: add agent connection quick start"
```

Expected: commit includes only the new guide and the agents guide update.

## Task 4: Align Sample and Own-App Getting Started Guides

**Files:**
- Modify: `docs/getting-started/try-the-sample.md`
- Modify: `docs/getting-started/add-to-your-app.md`

- [ ] **Step 1: Update sample guide title and success state**

In `docs/getting-started/try-the-sample.md`, keep the prerequisites table, but make the opening and run section emphasize handoff:

```markdown
# Quick Start — Sample App to Agent Handoff

The fastest way to understand FixThis is to create one real handoff from the
bundled sample app. No changes to your own app are required.
```

After the console steps, add:

```markdown
## Done State

The quick start is complete when:

- the console shows at least one numbered annotation pin;
- **Copy Prompt** has placed compact Markdown on your clipboard, or **Save to MCP** has persisted a local handoff;
- your agent can either receive the pasted Markdown or call `fixthis_read_feedback`.
```

- [ ] **Step 2: Add the agent connection link to sample guide**

In "What's next", include the new guide:

```markdown
- [Connect your AI agent](connect-your-agent.md) — Claude Code, Codex, Cursor,
  and chat-style agents
```

Keep existing links to add-to-your-app, feedback console tour, and troubleshooting.

- [ ] **Step 3: Make own-app guide explicit about current publication state**

In `docs/getting-started/add-to-your-app.md`, keep the warning but rewrite the first paragraph to be direct:

```markdown
> **Pre-publication status:** The bundled sample works now. A separate app can
> use FixThis today through Gradle composite-build or local project wiring.
> Maven Central and Gradle Plugin Portal coordinates are not published yet.
```

Do not show placeholder coordinates as something users can copy. If a future-coordinate snippet remains, label it as future-only and non-copyable.

- [ ] **Step 4: Add own-app success state**

Add a "Done State" section near the end of `docs/getting-started/add-to-your-app.md`:

```markdown
## Done State

Your app integration is working when:

- `fixthis doctor --package <applicationId>` reports a reachable debug app and sidekick bridge;
- `fixthis_open_feedback_console` or `fixthis console --package <applicationId>` opens FixThis Studio;
- one annotation can be saved through **Copy Prompt** or **Save to MCP**;
- release builds do not include the sidekick.
```

- [ ] **Step 5: Commit getting-started alignment**

Run:

```bash
node scripts/check-doc-consistency.mjs
git diff -- docs/getting-started/try-the-sample.md docs/getting-started/add-to-your-app.md
git add docs/getting-started/try-the-sample.md docs/getting-started/add-to-your-app.md
git commit -m "docs: clarify getting started handoff paths"
```

Expected: doc consistency passes before commit. Commit includes only the two getting-started files.

## Task 5: Update Documentation Index Around Reader Tasks

**Files:**
- Modify: `docs/index.md`

- [ ] **Step 1: Rewrite the top-level task map**

Keep the intro, then replace the task sections with role-based sections:

```markdown
## Start Here

| I want to... | Read |
| --- | --- |
| Try FixThis without changing my app | [Quick Start — sample app](getting-started/try-the-sample.md) |
| Add FixThis to my Compose debug build | [Add FixThis to your app](getting-started/add-to-your-app.md) |
| Connect Claude Code, Codex, Cursor, or ChatGPT | [Connect your AI agent](getting-started/connect-your-agent.md) |
| Learn the browser annotation workflow | [Feedback console tour](guides/feedback-console-tour.md) |
| Fix setup or device problems | [Troubleshooting](guides/troubleshooting.md) |
```

- [ ] **Step 2: Add canonical reference ownership**

Add a section:

```markdown
## Reference Contracts

- [CLI reference](reference/cli.md) — command flags and setup modes.
- [MCP tools reference](reference/mcp-tools.md) — tool signatures and return shapes.
- [Output schema](reference/output-schema.md) — persisted JSON fields and handoff shape.
- [Feedback console contract](reference/feedback-console-contract.md) — console behavior and compact prompt grammar.
- [Bridge protocol](reference/bridge-protocol.md) — sidekick/desktop wire protocol.
- [Compatibility matrix](reference/compatibility.md) — tested and minimum toolchain axes.
- [Privacy](reference/privacy.md), [Security](../SECURITY.md), and [Threat model](reference/threat-model.md) — local artifacts, trust boundaries, and security model.
```

- [ ] **Step 3: Preserve contribution and archive links**

Keep links to `CONTRIBUTING.md`, release readiness, release process, architecture overview, ADRs, archive, and superpowers docs. Reword only to avoid competing with first-run docs.

- [ ] **Step 4: Commit docs index update**

Run:

```bash
node scripts/check-doc-consistency.mjs
git diff -- docs/index.md
git add docs/index.md
git commit -m "docs: route docs index by reader task"
```

Expected: doc consistency passes before commit.

## Task 6: Tighten AGENTS.md Links Without Turning It Into a Human Tutorial

**Files:**
- Modify: `AGENTS.md`

- [ ] **Step 1: Update README quick-start anchor**

If README heading changed to `Quick Start: Sample App to Agent Handoff`, update the AGENTS link:

```markdown
**Trying FixThis from scratch on the sample app?** Start at the README's
[Quick Start](README.md#quick-start-sample-app-to-agent-handoff) for the
human-driven flow (build → doctor → run → console → agent handoff).
```

- [ ] **Step 2: Link agent connection guide**

Replace the deep-dive sentence:

```markdown
Agent integration deep dive (Claude Code / Codex / Cursor specifics):
[`docs/guides/agents.md`](docs/guides/agents.md).
```

with:

```markdown
First-run agent setup:
[`docs/getting-started/connect-your-agent.md`](docs/getting-started/connect-your-agent.md).
Deeper agent workflow notes:
[`docs/guides/agents.md`](docs/guides/agents.md).
```

- [ ] **Step 3: Keep constraints unchanged**

Do not remove or weaken these existing constraints:

- debug builds only;
- Jetpack Compose only;
- local-first;
- do not commit `.fixthis/`;
- do not rename persisted MCP JSON fields;
- `:fixthis-compose-core` boundary;
- bridge protocol coordination.

- [ ] **Step 4: Commit AGENTS link update**

Run:

```bash
node scripts/check-doc-consistency.mjs
git diff -- AGENTS.md
git add AGENTS.md
git commit -m "docs: point agents at onboarding guide"
```

Expected: doc consistency passes before commit.

## Task 7: Final Drift Search and Documentation Validation

**Files:**
- Validate: `README.md`
- Validate: `AGENTS.md`
- Validate: `docs/index.md`
- Validate: `docs/getting-started/try-the-sample.md`
- Validate: `docs/getting-started/add-to-your-app.md`
- Validate: `docs/getting-started/connect-your-agent.md`
- Validate: `docs/guides/agents.md`

- [ ] **Step 1: Search stale workflow vocabulary**

Run:

```bash
rg -n "Send Agent|Save snapshot|Add to Pending|Add/Save" README.md AGENTS.md docs/getting-started docs/guides docs/index.md
```

Expected: no hits in active onboarding docs. If there are hits, update the text to current vocabulary or explain why the hit is historical/reference-only. This command excludes `docs/archive` and `docs/superpowers`.

- [ ] **Step 2: Check all new links and known doc consistency rules**

Run:

```bash
node scripts/check-doc-consistency.mjs
```

Expected: all rules pass.

- [ ] **Step 3: Check markdown whitespace**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 4: Inspect final diff**

Run:

```bash
git status --short
git diff --stat HEAD
git diff -- README.md docs/getting-started/try-the-sample.md docs/getting-started/add-to-your-app.md docs/getting-started/connect-your-agent.md docs/guides/agents.md docs/index.md AGENTS.md
```

Expected: only documentation files from this plan are modified. If unrelated user edits exist, do not stage them.

- [ ] **Step 5: Final commit**

If any validation-only fixes remain unstaged, commit them:

```bash
git add README.md AGENTS.md docs/index.md docs/getting-started/try-the-sample.md docs/getting-started/add-to-your-app.md docs/getting-started/connect-your-agent.md docs/guides/agents.md
git commit -m "docs: validate public onboarding links"
```

If all previous task commits already contain every change and the worktree is clean except unrelated user edits, skip this commit.

## Task 8: Final Review Report

**Files:**
- Read: `README.md`
- Read: `docs/getting-started/connect-your-agent.md`
- Read: `docs/index.md`

- [ ] **Step 1: Summarize changed navigation**

Prepare a short final report covering:

- README now leads with sample-to-agent handoff;
- new `connect-your-agent.md` first-run guide exists;
- sample and own-app docs define done states;
- docs index routes by reader task;
- AGENTS remains agent-facing with updated links.

- [ ] **Step 2: Include verification results**

Include exact commands run and status:

```text
node scripts/check-doc-consistency.mjs — pass
git diff --check — pass
rg stale workflow vocabulary — no active onboarding hits
```

- [ ] **Step 3: Mention non-goals preserved**

State that no production code, MCP schema, bridge protocol, console behavior, or Gradle behavior changed.

## Self-Review

Spec coverage:

- README first screen and Quick Start: Task 2.
- `connect-your-agent.md`: Task 3.
- Sample and own-app getting-started alignment: Task 4.
- Agents guide responsibility split: Task 3.
- Docs index role-based routing: Task 5.
- AGENTS link cleanup while preserving constraints: Task 6.
- Trust/local/debug/pre-publication notes: Tasks 2, 3, and 4.
- Validation commands and stale vocabulary search: Task 7.
- Documentation-only scope: all tasks avoid production code.

Placeholder scan:

- No task contains a placeholder instruction. Every file path, command, and expected result is explicit.

Type/name consistency:

- Current console vocabulary is consistent across tasks: Annotate, Add annotation, Copy Prompt, Save to MCP.
- New guide path is consistently `docs/getting-started/connect-your-agent.md`.
- README anchor target is consistently `README.md#quick-start-sample-app-to-agent-handoff`.
