# Public Repository Onboarding Design

Status: Approved
Date: 2026-05-15
Scope: README, getting-started docs, agent docs, docs index, repository navigation
Audience: Android Compose developers who already use AI coding agents

## Summary

FixThis is now public, so the repository needs to help a new visitor decide
quickly whether the project is useful, safe to try, and easy to run. The
primary reader is not a generic Android library consumer. The primary reader is
an Android Compose developer who already uses Claude Code, Codex, Cursor, or a
chat-style coding agent and wants to hand UI feedback to that agent with less
ambiguity.

The repository should therefore optimize for one first success path:

> Run the bundled sample, annotate one Compose UI target, and produce an agent
> handoff through Copy Prompt or Save to MCP in about five minutes.

This design reorganizes documentation and repository navigation around that
path. It does not change runtime behavior, MCP schemas, bridge protocol,
console behavior, Gradle wiring, or product features.

## Goals

- Make the README communicate the product, constraints, and first successful
  handoff within the first screen and first Quick Start.
- Make the sample app Quick Start end at an agent handoff, not merely at a
  launched app or browser console.
- Separate human onboarding, agent setup, reference contracts, and contributor
  information so each topic has a clear source of truth.
- Surface public-repo trust signals early: debug-only, Compose-only,
  local-only, no external API calls, screenshot/privacy caveats, and current
  external-publication status.
- Preserve existing detailed reference docs and link to them instead of
  duplicating CLI flags, MCP signatures, or schema details across multiple
  guides.

## Non-Goals

- No production code changes.
- No console UI changes.
- No MCP tool, persisted JSON, or bridge protocol changes.
- No Maven Central or Gradle Plugin Portal publishing in this pass.
- No rewrite of architecture docs or historical design records.
- No broad contribution-program work beyond improving repository navigation.

## Reader Model

The primary reader already understands Android development and is likely to
understand Compose, Gradle, ADB, and coding agents. They should not have to read
the architecture overview before trying FixThis.

They need fast answers to these questions:

- What does FixThis do for my coding-agent workflow?
- Can I try it today?
- What are the limitations?
- Is it local and debug-only?
- What command do I run first?
- How do I connect Claude Code, Codex, Cursor, or a chat-style agent?
- Where do I go if setup fails?

## Recommended Approach

Use a "5-Min Agent Handoff" structure as the main repository path.

README should become the public first-success document. It should lead with the
agent handoff value, show the current support constraints, and then drive the
reader through the sample app to a copied prompt or saved MCP handoff.

Trust information should be present but compact. The first page should not open
with a long security model, but it must make the local-only and debug-only
contract clear before the reader runs the tool.

Contributor and architecture material stays available through links. It should
not compete with the first-run path.

## README Design

The README first screen should prioritize judgment and action:

1. Product title and one-sentence value proposition.
2. Hero screenshot or product image, kept because this is a visual UI feedback
   tool.
3. "Works Today" or equivalent quick facts:
   - bundled sample runs now;
   - Copy Prompt works with any chat-style agent;
   - Save to MCP works with Claude Code and Codex;
   - runs locally over ADB and localhost;
   - debug builds only;
   - Jetpack Compose only;
   - external Gradle artifacts are not published yet.
4. "Quick Start: Sample App to Agent Handoff" with commands and a concrete done
   state.
5. "Pick Your Path" table linking to the correct next document.
6. Compact trust and privacy note.
7. Short module map and roadmap, with detailed architecture and reference
   content linked out.

The Quick Start done state should be explicit:

> You are done when the console shows a numbered annotation and you have either
> copied a Markdown prompt or saved a local MCP handoff for an MCP-aware agent.

The README should not duplicate full CLI option lists, MCP tool signatures,
output schema, or troubleshooting matrices.

## Documentation Structure

### README.md

README is the first-user success document.

It owns:

- the concise product explanation;
- the support and limitation snapshot;
- the 5-minute sample-to-handoff path;
- the main navigation table;
- short trust notes;
- a lightweight module map and roadmap.

It links out for everything detailed.

### docs/getting-started/try-the-sample.md

This is the detailed sample walkthrough.

It owns:

- prerequisites for the bundled sample;
- clone/build/doctor/run commands;
- console actions from Annotate through Add annotation;
- Copy Prompt and Save to MCP success paths;
- the visible success state;
- troubleshooting links for ADB, lock screen, bridge attach, and console
  connection failures.

### docs/getting-started/add-to-your-app.md

This is the guide for attaching FixThis to a developer's own Compose debug app.

It owns:

- current pre-publication status;
- composite-build or project-dependency wiring until Gradle artifacts are
  published;
- plugin application;
- debug-only sidekick behavior;
- applicationId selection;
- bootstrap command using the app's package;
- links to compatibility and release readiness.

### docs/getting-started/connect-your-agent.md

Create this new getting-started guide for agent connection.

It owns:

- Claude Code bootstrap, restart, and `fixthis_open_feedback_console`;
- Codex bootstrap, restart, and MCP queue usage;
- Cursor, ChatGPT, and chat-style agents through Copy Prompt;
- the difference between Copy Prompt and Save to MCP;
- the queue lifecycle for MCP-aware agents: read, claim, resolve;
- the instruction that setup details live in the CLI reference.

### docs/guides/agents.md

Keep this guide as a deeper agent workflow reference, or turn it into a short
redirect-style guide that points first-time users to
`docs/getting-started/connect-your-agent.md`.

It should not compete with the getting-started path. If content remains here,
it should focus on deeper operational details such as target reliability
warnings, queue semantics, and agent behavior across claimed/resolved items.

### AGENTS.md

AGENTS.md is an agent-facing entrypoint, not a human tutorial.

It owns:

- MCP tool index;
- bootstrap command;
- feedback workflow summary;
- constraints that agents must respect;
- `.fixthis/` artifact warning;
- canonical doc links.

It should avoid repeating long README Quick Start prose.

### docs/index.md

The docs index is a role-based map.

It should route readers by task:

- try the sample;
- add FixThis to my app;
- connect my agent;
- use the console;
- troubleshoot;
- understand safety/privacy;
- inspect API/schema details;
- contribute.

## Canonical Ownership Rules

To prevent public-doc drift, detailed contracts should have one canonical home:

- CLI flags and commands: `docs/reference/cli.md`
- MCP tool signatures and return shapes: `docs/reference/mcp-tools.md`
- persisted JSON schema: `docs/reference/output-schema.md`
- console behavior contract: `docs/reference/feedback-console-contract.md`
- bridge protocol: `docs/reference/bridge-protocol.md`
- compatibility matrix: `docs/reference/compatibility.md`
- privacy: `docs/reference/privacy.md`
- security model: `SECURITY.md` and `docs/reference/threat-model.md`
- contributor local checks: `CONTRIBUTING.md`
- release readiness: `docs/contributing/release-readiness.md`

Other docs may summarize these topics, but they should link to the canonical
owner instead of restating detailed option lists or schemas.

## Trust Signals

The README and getting-started pages should make these facts visible early:

- FixThis is debug-build-only.
- FixThis currently supports Jetpack Compose targets only.
- FixThis makes no external API calls.
- The browser console and MCP server are local to the developer machine.
- `.fixthis/` contains local screenshots, session metadata, and handoff
  artifacts and must not be committed.
- Screenshots may contain sensitive UI pixels; users should review before
  sharing copied prompts or artifacts outside their machine.
- External Gradle artifacts are not yet published, so external app integration
  currently requires repository wiring rather than one-line Maven coordinates.

These should be short early notes, with longer discussion linked to privacy,
security, threat model, and release readiness docs.

## Validation Criteria

The documentation change is successful when a new visitor can infer the
following from README alone:

- FixThis is for Jetpack Compose debug apps.
- The core workflow is AI-agent handoff from UI annotations.
- The bundled sample can be tried now.
- Using FixThis in a separate app currently requires composite-build or
  equivalent local repository wiring.
- The tool is local-only and debug-only.
- The next document to read depends on whether they want to try the sample,
  attach their app, connect an agent, or inspect reference details.

The Quick Start is successful only if it reaches handoff creation:

- a numbered annotation exists in the console; and
- the user has either copied compact Markdown or saved a local MCP handoff.

Automated/document checks for the implementation pass should include:

```bash
node scripts/check-doc-consistency.mjs
git diff --check
```

If README, AGENTS.md, CONTRIBUTING.md, or `package.json` are edited, the doc
consistency checker is required by the contributor contract.

Manual review should also search for stale public workflow vocabulary and
replace or contextualize it where needed:

```bash
rg -n "Send Agent|Save snapshot|Add to Pending|Add/Save" README.md AGENTS.md docs
```

The search may still find historical archive files or reference text that is
intentionally documenting older vocabulary. Current getting-started paths should
use the current console language: Annotate, Add annotation, Copy Prompt, and
Save to MCP.

## Rollout Plan

Implement as a documentation-only branch:

1. Restructure README first, preserving the hero image and badges.
2. Add `docs/getting-started/connect-your-agent.md`.
3. Update `docs/getting-started/try-the-sample.md` and
   `docs/getting-started/add-to-your-app.md` to align with the new README path.
4. Convert or trim `docs/guides/agents.md` so it no longer competes with the
   new getting-started guide.
5. Update `docs/index.md` around role-based navigation.
6. Keep AGENTS.md focused on agent instructions and canonical links.
7. Run the validation commands and fix link or anchor drift.

## Risks

- The README could become too long. Mitigation: keep detailed command variants,
  schemas, and troubleshooting in linked docs.
- The new agent connection guide could duplicate `docs/guides/agents.md`.
  Mitigation: make `connect-your-agent.md` the first-run guide and keep
  `guides/agents.md` for deeper behavior or route it to the new guide.
- Pre-publication Gradle wiring may discourage users. Mitigation: state it
  plainly and provide a clear "sample works now" path plus release-readiness
  link.
- Archive and superpowers docs may still contain old product vocabulary.
  Mitigation: treat current README/getting-started/reference docs as the public
  contract; archives remain historical.

## Approval

The user approved this direction in four sections:

- repository information architecture;
- README first-screen structure;
- document responsibility split;
- work scope and validation criteria.
