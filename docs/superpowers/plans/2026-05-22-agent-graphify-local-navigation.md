# Agent Graphify Local Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Graphify as a local-only agent navigation aid for FixThis without changing product runtime, MCP contracts, Android builds, or release behavior.

**Architecture:** Keep Graphify as an external local tool. Commit only ignore rules and docs that tell agents how to generate and query `graphify-out/` locally, while treating Graphify output as advisory and verifying contract-sensitive changes against source and reference docs.

**Tech Stack:** Markdown docs, `.gitignore`, `.graphifyignore`, Graphify CLI 0.8.x, existing FixThis docs structure.

---

## File Structure

- Modify `.gitignore`
  - Add `graphify-out/` under the agent/local tool state section so generated graphs, cache, memory, wiki, and HTML output never become commit candidates.
- Create `.graphifyignore`
  - Define FixThis-specific extraction exclusions for Git state, Android/Gradle build output, FixThis local artifacts, source-matching fixture workspaces, Node output, reports, caches, logs, and Graphify output.
- Modify `AGENTS.md`
  - Run or reproduce Graphify's standard Codex section.
  - Add a separate `## FixThis Graphify Boundaries` section immediately after it so future `graphify codex install` reruns replace only the Graphify-owned section and preserve the FixThis-specific addendum.
- Create `docs/guides/agent-graphify.md`
  - Explain local generation, focused query usage, fallback behavior, hook policy, and contract verification expectations.
- Modify `docs/index.md`
  - Link the guide from the Start Here table so agent-oriented docs remain discoverable.

This plan is intentionally docs-first. It does not touch Kotlin, JavaScript console code, MCP schemas, Gradle plugin behavior, or release automation.

---

### Task 1: Add Local Graph Output Guardrails

**Files:**
- Modify: `.gitignore`
- Create: `.graphifyignore`

- [ ] **Step 1: Run guardrail checks and confirm they fail before edits**

Run:

```bash
rg -n '^graphify-out/$' .gitignore
test -f .graphifyignore && rg -n '^(graphify-out/|\\.fixthis/|\\*\\*/build/)$' .graphifyignore
```

Expected: the first command exits `1` because `.gitignore` does not yet name `graphify-out/`; the second command exits `1` because `.graphifyignore` does not exist yet.

- [ ] **Step 2: Add Graphify output to `.gitignore`**

Insert `graphify-out/` in the `# Agent/local tool state` block after `.fixthis`:

```gitignore
# Agent/local tool state
.fixthis
graphify-out/
.claude/
```

Do not remove the existing `.fixthis` entry. The more specific `.fixthis/artifacts/`, `.fixthis/feedback-sessions/`, `.fixthis/preview-cache/`, and `.fixthis/smoke-reports/` entries can remain in the earlier FixThis local capture section.

- [ ] **Step 3: Create `.graphifyignore`**

Create `.graphifyignore` with this exact content:

```gitignore
# VCS and agent-local state
.git/
.claude/
.codex/
.cursor/
.mcp.json
.orchestrator/
.codex-orchestrator/
.remember/
.superpowers/
.worktrees/

# Graphify output must never feed back into extraction.
graphify-out/

# Gradle, Kotlin, Android, and generated build output
.gradle/
.kotlin/
build/
**/build/
local.properties
captures/
.externalNativeBuild/
.cxx/
*.apk
*.aab
*.hprof

# Node and test output
node_modules/
coverage/
.nyc_output/
playwright-report/
test-results/
output/

# FixThis local runtime, feedback, and fixture state
.fixthis/
.fixthis/eval-fixtures/
build/reports/fixthis-source-matching/

# Local secrets and machine-specific files
.env*
*.jks
*.keystore
*.p12
*.pfx
*.pem
*.key
*.gpg
*.kbx
key.properties
credentials.properties
secrets.properties
signing.properties
publish.properties
publishing.properties
gradle-publish.properties
maven-central.properties
npmrc.local
.npmrc.local

# Logs, editor backups, and OS files
.DS_Store
*.log
*.tmp
*.bak
*.orig
```

- [ ] **Step 4: Verify guardrails pass**

Run:

```bash
rg -n '^graphify-out/$' .gitignore
rg -n '^(graphify-out/|\\.fixthis/|\\*\\*/build/)$' .graphifyignore
```

Expected: `rg` prints matching lines from `.gitignore` and `.graphifyignore`, and both commands exit `0`.

- [ ] **Step 5: Commit Task 1**

Run:

```bash
git add .gitignore .graphifyignore
git commit -m "chore: ignore local graphify output"
```

---

### Task 2: Add Agent-Facing Graphify Guidance

**Files:**
- Modify: `AGENTS.md`

- [ ] **Step 1: Confirm Graphify is available**

Run:

```bash
graphify --version
```

Expected: prints a version such as `graphify 0.8.14`. If this command is missing, install Graphify before continuing; do not edit AGENTS.md by guessing the generated standard section.

- [ ] **Step 2: Install the standard Codex section**

Run:

```bash
graphify codex install
```

Expected: command prints that the graphify section was written to `AGENTS.md`, or that it was already configured with no change.

- [ ] **Step 3: Verify the standard section exists**

Run:

```bash
rg -n '^## graphify$|graphify query "<question>"|graphify-out/GRAPH_REPORT.md' AGENTS.md
```

Expected: matches the `## graphify` heading, the focused query rule, and the report fallback rule.

- [ ] **Step 4: Add the FixThis addendum**

Insert this section immediately after the `## graphify` section and before the next existing `##` heading:

```markdown
## FixThis Graphify Boundaries

Graphify is an agent navigation aid for this repository, not a FixThis product
runtime dependency.

- Treat `graphify query`, `graphify path`, and `graphify explain` output as
  advisory context. Before changing behavior, verify against the actual Kotlin,
  JavaScript, Gradle, and Markdown sources.
- For MCP output compatibility, bridge protocol changes, source matching
  confidence, or persisted JSON fields, verify against `docs/reference/*` and
  the implementation before editing.
- Do not commit `.fixthis/`, `graphify-out/`, Android build output, local
  source-matching fixture workspaces, or generated Graphify cache/wiki/HTML.
- If Graphify is missing or `graphify-out/graph.json` is stale, continue with
  `rg`, direct source reads, and the canonical docs. Graphify failures do not
  block normal FixThis development.
- Use `graphify hook install` only as a personal opt-in. Manual
  `graphify update .` is the default workflow for this repo.
```

The addendum is a separate H2 section on purpose. Graphify's installer replaces only the `## graphify` section up to the next H2, so keeping the FixThis boundaries in their own H2 makes future installer reruns less likely to delete them.

- [ ] **Step 5: Verify the addendum exists and the standard section remains**

Run:

```bash
rg -n '^## graphify$|^## FixThis Graphify Boundaries$|source matching confidence|graphify hook install' AGENTS.md
```

Expected: matches both headings and the FixThis-specific source matching and hook policy lines.

- [ ] **Step 6: Commit Task 2**

Run:

```bash
git add AGENTS.md
git commit -m "docs: add graphify agent guidance"
```

---

### Task 3: Document The Local Graphify Workflow

**Files:**
- Create: `docs/guides/agent-graphify.md`
- Modify: `docs/index.md`

- [ ] **Step 1: Confirm the guide is not already linked**

Run:

```bash
test ! -f docs/guides/agent-graphify.md
! rg -n 'agent-graphify' docs/index.md
```

Expected: both commands exit `0`; the guide does not exist and the docs index does not link it yet.

- [ ] **Step 2: Create `docs/guides/agent-graphify.md`**

Create the guide with this exact content:

```markdown
# Agent Graphify Workflow

Graphify is a local navigation aid for agents working in the FixThis
repository. It is not part of the Android runtime, MCP server, CLI install
flow, release artifacts, or public contract.

Use Graphify when a task depends on broad codebase structure: where behavior
lives, how modules relate, which files participate in a workflow, or what path
connects two concepts.

## Local Output

Graphify writes generated data under `graphify-out/`. That directory is local
tool state and must not be committed.

Expected local outputs can include:

- `graphify-out/graph.json`
- `graphify-out/GRAPH_REPORT.md`
- `graphify-out/graph.html`
- `graphify-out/cache/`
- `graphify-out/memory/`
- `graphify-out/wiki/`

The repository keeps `.graphifyignore` so local artifacts, generated build
output, fixture workspaces, and Graphify output do not feed back into
extraction.

## Basic Commands

Generate or refresh the local graph:

```bash
graphify update .
```

Ask a focused architecture or codebase question:

```bash
graphify query "where is source matching implemented?"
```

Inspect a relationship:

```bash
graphify path "fixthis-gradle-plugin" "sourceCandidates"
```

Explain a node or concept:

```bash
graphify explain "FeedbackSessionService"
```

Prefer `query`, `path`, or `explain` for focused questions. Read
`graphify-out/GRAPH_REPORT.md` only for broad architecture review or when the
focused commands do not surface enough context.

## Verification Rules

Graphify output is advisory. Before making behavior changes, verify the answer
against the source, tests, and canonical docs.

Use the reference docs and implementation as the authority for:

- MCP tool outputs and persisted session JSON
- `items`, `screens`, `itemId`, `screenId`, `targetEvidence`,
  `targetReliability`, and `sourceCandidates`
- bridge protocol behavior
- source-index schema and source matching confidence
- debug-only Android runtime constraints
- release, package, and install flows

If Graphify conflicts with source or docs, the source and docs win.

## Hook Policy

Manual updates are the default:

```bash
graphify update .
```

Hooks are optional local automation:

```bash
graphify hook install
graphify hook status
graphify hook uninstall
```

Do not require hooks for contributors, CI, release work, or agent setup. This
repository has frequent generated-file churn, and Graphify output is a local
agent aid rather than a project artifact.

## Fallback

If `graphify` is not installed or `graphify-out/graph.json` does not exist,
continue with normal repo exploration:

```bash
rg "<symbol-or-contract>"
rg --files
```

Graphify failures should not block normal FixThis development.
```

- [ ] **Step 3: Link the guide from `docs/index.md`**

Add this row to the `## Start Here` table after `Connect Claude Code, Codex, Cursor, or ChatGPT`:

```markdown
| Navigate this repo with Graphify as an agent | [Agent Graphify workflow](guides/agent-graphify.md) |
```

The surrounding table should look like this:

```markdown
| Add FixThis to my Compose debug build | [Add FixThis to your app](getting-started/add-to-your-app.md) |
| Connect Claude Code, Codex, Cursor, or ChatGPT | [Connect your AI agent](getting-started/connect-your-agent.md) |
| Navigate this repo with Graphify as an agent | [Agent Graphify workflow](guides/agent-graphify.md) |
| Learn the browser annotation workflow | [Feedback console tour](guides/feedback-console-tour.md) |
```

- [ ] **Step 4: Verify guide content and index link**

Run:

```bash
rg -n '^# Agent Graphify Workflow$|graphify update \\.|Graphify output is advisory|^## Hook Policy$' docs/guides/agent-graphify.md
rg -n 'Agent Graphify workflow' docs/index.md
```

Expected: first command matches the guide title, refresh command, verification statement, and hook section; second command matches the index row.

- [ ] **Step 5: Commit Task 3**

Run:

```bash
git add docs/guides/agent-graphify.md docs/index.md
git commit -m "docs: document local graphify workflow"
```

---

### Task 4: Verify Local Graph Generation Stays Untracked

**Files:**
- No source edits expected

- [ ] **Step 1: Run whitespace validation**

Run:

```bash
git diff --check
```

Expected: no output and exit `0`.

- [ ] **Step 2: Confirm Graphify CLI is available**

Run:

```bash
graphify --version
```

Expected: prints a version such as `graphify 0.8.14`.

- [ ] **Step 3: Generate a local graph without clustering**

Run:

```bash
graphify update . --no-cluster
```

Expected: command completes and writes local output under `graphify-out/`. It may print extraction progress; it should not require Android SDK, ADB, Gradle builds, or a connected device.

- [ ] **Step 4: Verify generated output is ignored**

Run:

```bash
git status --short --ignored graphify-out
git status --short
```

Expected: the first command shows ignored `graphify-out/` output with `!!`; the second command does not show tracked or untracked `graphify-out/` files.

- [ ] **Step 5: Verify Graphify can answer a focused local query**

Run:

```bash
graphify query "where is source matching implemented?" --budget 1200
```

Expected: command returns a scoped answer from the local graph. Treat the result as smoke-test evidence only; do not edit product code based on it without source verification.

- [ ] **Step 6: Run final status check**

Run:

```bash
git status --short
git log --oneline -3
```

Expected: status is clean, and the latest commits include:

```text
docs: document local graphify workflow
docs: add graphify agent guidance
chore: ignore local graphify output
```

No final commit is needed for Task 4 because it is verification-only.

---

## Final Acceptance Checklist

- [ ] `graphify-out/` is ignored by Git.
- [ ] `.graphifyignore` excludes local/generated FixThis and Android artifacts.
- [ ] `AGENTS.md` contains Graphify's standard Codex section.
- [ ] `AGENTS.md` contains a separate FixThis-specific Graphify boundaries section.
- [ ] `docs/guides/agent-graphify.md` documents local usage, fallback, verification rules, and hook policy.
- [ ] `docs/index.md` links to the guide.
- [ ] `graphify update . --no-cluster` creates ignored local output.
- [ ] `git status --short` is clean after verification.
