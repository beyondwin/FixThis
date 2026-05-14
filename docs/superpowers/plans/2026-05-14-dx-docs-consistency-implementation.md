# DX Documentation Consistency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve four DX documentation frictions (undocumented contributor scripts, drifting npm-script catalog, README/AGENTS quick-start mismatch, missing Node/Chromium minimums) by assigning Single-Source-of-Truth ownership per topic and updating every dependent file to link rather than restate.

**Architecture:** Documentation + `package.json` + one new diagnostic script. No Kotlin/Gradle changes. Each task changes one or two files, then runs a real command (script invocation, `npm run`, or grep) to verify the new state — no TDD, by spec design.

**Tech Stack:** Markdown, `package.json` scripts, plain Node.js (drift detector), Bash.

**Spec:** `docs/superpowers/specs/2026-05-14-dx-docs-consistency-design.md`

---

## File Map

| Action | Path |
|---|---|
| Modify | `package.json` |
| Create | `.npmrc` |
| Modify | `README.md` |
| Modify | `AGENTS.md` |
| Modify | `CONTRIBUTING.md` |
| Modify | `CLAUDE.md` |
| Modify | `.github/workflows/ci.yml` |
| Create | `scripts/check-doc-consistency.mjs` |
| Create | `scripts/console-tests.json` |
| Create | `scripts/run-console-tests.mjs` |

**Verification pattern for every task:** doc edit → runnable command → asserted output. No code-only commits without a paired verification command.

---

### Task 1: Add Prerequisites section to CONTRIBUTING.md

**Files:**
- Modify: `CONTRIBUTING.md`

**Cross-pair coordination (read first):** This plan and
`2026-05-14-setup-error-diagnostics-implementation.md` both modify
`docs/guides/troubleshooting.md`. **Merge order: this plan first, then
setup-error-diagnostics plan to avoid line-number drift.** The
setup-error-diagnostics plan appends a "Setup failures" section at the bottom
of the file; this plan touches a different section near the top, so landing
in this order keeps both diffs minimal and rebase-free.

- [ ] **Step 1: Locate the insertion point**

The new section goes immediately *before* `## Formatting` (currently CONTRIBUTING.md line 3). Confirm:

```bash
grep -n "^## Formatting" CONTRIBUTING.md
```

Expected: one match, line 3 (or wherever Formatting currently lives).

- [ ] **Step 2: Insert the Prerequisites section**

Insert the following block immediately before `## Formatting`:

```markdown
## Prerequisites

| Tool | Minimum | Notes |
|---|---|---|
| JDK | 21 | Adoptium Temurin recommended. |
| Android SDK + ADB | API 30+ | Required for `:app:assembleDebug` and connected smoke. |
| Node.js | 20.0.0 | Enforced via `package.json` `engines` and `.npmrc engine-strict=true`. Node 20.x LTS is supported through 2026-04; 22.x LTS also supported. Node 18.x is EOL. |
| Chromium | Bundled by Playwright 1.59 | `npx playwright install chromium` after `npm install`. macOS 11+ / Ubuntu 20.04+ required by Playwright's bundled Chromium. |

Run `npm install` and `npx playwright install chromium` once before running any `npm run console:*` script.

```

(Trailing blank line is intentional; it separates the new section from `## Formatting`.)

- [ ] **Step 3: Verify the section is reachable by anchor**

```bash
grep -n "^## Prerequisites" CONTRIBUTING.md
```

Expected: exactly one match. GitHub will render the anchor as `#prerequisites`.

- [ ] **Step 4: Confirm Node version really is ≥20.0.0 on this machine**

```bash
node --version
```

Expected: `v20.0.0` or higher. If lower, the plan still proceeds (the prerequisite documents the requirement; meeting it is the contributor's responsibility), but record in the PR description what version was used for verification.

- [ ] **Step 5: Commit**

```bash
git add CONTRIBUTING.md
git commit -m "docs(contributing): document JDK/Node/Chromium prerequisites"
```

---

### Task 2: Add `engines.node` to package.json + new npm scripts

**Files:**
- Modify: `package.json`

- [ ] **Step 1: Show current state**

```bash
cat package.json
```

Confirm the current scripts list matches what the spec enumerated (7 scripts; no `engines` field). If it differs, escalate and update this plan before editing.

- [ ] **Step 2: Replace `package.json` with the new shape**

The full new content:

```json
{
  "name": "fixthis",
  "private": true,
  "license": "MIT",
  "type": "module",
  "engines": {
    "node": ">=20.0.0"
  },
  "scripts": {
    "console:smoke": "node scripts/console-browser-smoke.mjs",
    "console:responsive:stress": "node scripts/console-responsive-stress.mjs",
    "console:availability:test": "node scripts/run-console-tests.mjs availability",
    "console:pending:test": "node scripts/run-console-tests.mjs pending",
    "console:beforeunload:test": "node scripts/run-console-tests.mjs beforeunload",
    "console:undo:test": "node scripts/run-console-tests.mjs undo",
    "console:draft:test": "node scripts/run-console-tests.mjs draft",
    "console:activity:test": "node scripts/run-console-tests.mjs activity",
    "console:preview:test": "node scripts/run-console-tests.mjs preview",
    "console:session:test": "node scripts/run-console-tests.mjs session",
    "console:test:fast": "node scripts/run-console-tests.mjs availability pending beforeunload undo activity preview",
    "console:test:all": "node scripts/run-console-tests.mjs availability pending beforeunload undo activity preview draft session"
  },
  "devDependencies": {
    "playwright": "^1.59.1",
    "github-slugger": "^2.0.0"
  }
}
```

Also create `.npmrc` at the repo root containing exactly:

```
engine-strict=true
```

so `npm install` fails immediately on Node <20 instead of warning. The file is checked into git.

- [ ] **Step 3: Verify each new script resolves and runs**

```bash
npm run console:activity:test
npm run console:preview:test
npm run console:session:test
```

Expected: each command exits 0. The underlying `.mjs` files already exist (verify with `ls scripts/activityDrift-test.mjs scripts/previewStaleness-test.mjs scripts/pendingBoundaryGuard-test.mjs scripts/sessionScopedRequests-test.mjs scripts/savedOverlayScope-test.mjs scripts/undoRedoContext-test.mjs` — all should be present).

- [ ] **Step 4: Verify the aggregates compose correctly**

```bash
npm run console:test:fast
```

Expected: runs six sub-suites in sequence, exits 0. Look for six `# tests` summary lines or the equivalent reporter output.

```bash
npm run console:test:all
```

Expected: runs the fast set plus draft + session. Longer. Exits 0.

- [ ] **Step 5: Confirm engines warning behavior**

```bash
npm install --engine-strict 2>&1 | head -5
```

Expected on Node ≥18.18: no `EBADENGINE` warning. (npm exits 0 because Playwright is already installed.) If Node is older, `--engine-strict` exits non-zero. This is intentional — it demonstrates that the engine field is being consulted.

- [ ] **Step 6: Commit**

```bash
git add package.json .npmrc
git commit -m "build(npm): pin Node >=20, add engine-strict, switch console scripts to runner"
```

---

### Task 2B: Create scripts/console-tests.json and scripts/run-console-tests.mjs (SSOT for test groups)

**Files:**
- Create: `scripts/console-tests.json`
- Create: `scripts/run-console-tests.mjs`

- [ ] **Step 1: Create `scripts/console-tests.json`**

```json
{
  "availability": ["scripts/console-availability-test.mjs"],
  "pending": ["scripts/pendingItemRecovery-test.mjs"],
  "beforeunload": ["scripts/beforeunloadGuard-test.mjs"],
  "undo": ["scripts/undoRedo-test.mjs", "scripts/undoKeymatch-test.mjs"],
  "activity": ["scripts/activityDrift-test.mjs"],
  "preview": ["scripts/previewStaleness-test.mjs"],
  "draft": [
    "scripts/draftWorkspace-test.mjs",
    "scripts/draftWorkspaceHistory-test.mjs",
    "scripts/draftStorageAdapter-test.mjs",
    "scripts/draftApiAdapter-test.mjs",
    "scripts/draftUseCases-test.mjs",
    "scripts/draftCommandQueue-test.mjs",
    "scripts/draftPresentationContract-test.mjs",
    "scripts/draftWorkflowInvariant-test.mjs"
  ],
  "session": [
    "scripts/pendingBoundaryGuard-test.mjs",
    "scripts/sessionScopedRequests-test.mjs",
    "scripts/savedOverlayScope-test.mjs",
    "scripts/undoRedoContext-test.mjs"
  ]
}
```

- [ ] **Step 2: Create `scripts/run-console-tests.mjs`**

```js
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const groups = JSON.parse(fs.readFileSync(path.join(repoRoot, "scripts/console-tests.json"), "utf8"));
const requested = process.argv.slice(2);
if (requested.length === 0) {
  console.error("Usage: node scripts/run-console-tests.mjs <group> [<group> ...]");
  console.error("Known groups:", Object.keys(groups).join(", "));
  process.exit(2);
}
const files = [];
for (const g of requested) {
  if (!(g in groups)) {
    console.error(`Unknown group: ${g}`);
    process.exit(2);
  }
  files.push(...groups[g]);
}
const result = spawnSync(process.execPath, ["--test", ...files], { stdio: "inherit", cwd: repoRoot });
process.exit(result.status ?? 1);
```

- [ ] **Step 3: Verify the runner works for each defined group**

```bash
node scripts/run-console-tests.mjs availability
node scripts/run-console-tests.mjs activity preview
```

Expected: both exit 0.

- [ ] **Step 4: Commit**

```bash
git add scripts/console-tests.json scripts/run-console-tests.mjs
git commit -m "tools(console): single-source-of-truth test list + runner"
```

---

### Task 3: Add Console Inner Loop section to CONTRIBUTING.md

**Files:**
- Modify: `CONTRIBUTING.md`

- [ ] **Step 1: Locate the insertion point**

The new section goes between `## Required PR checks` and `## Required Local Checks`. Confirm:

```bash
grep -n "^## Required PR checks\|^## Required Local Checks" CONTRIBUTING.md
```

Expected: two matches, in that order. Insert the new section *after* `## Required PR checks` block (which is a table + paragraph) and *before* `## Required Local Checks`.

- [ ] **Step 2: Insert the new section**

Insert verbatim:

````markdown
## Console Inner Loop

The console JS is live-reloaded; the Kotlin server is pinned in the JAR. Two helper scripts cover the common inner-loop cases.

### `scripts/restart-console.sh` — restart after Kotlin server changes

Use after any change to `:fixthis-mcp` server code. The script kills any running console process, frees the bookmarked port (default `9876`), and starts a new console pointed at the source-tree assets.

```bash
bash scripts/restart-console.sh                 # restart console only
bash scripts/restart-console.sh --with-app      # also reinstall the sample APK
bash scripts/restart-console.sh --dry-run       # preview commands without executing
bash scripts/restart-console.sh --port 9876     # use a non-default port
```

Override the port with `FIXTHIS_CONSOLE_PORT` or `--port`. The script also frees stray `screen` sessions named `fixthis-console-*`.

### `scripts/fixthis-console-dev.sh` — JS-only hot-reload loop

Use after edits to `fixthis-mcp/src/main/console/*` that you rebundle with `node scripts/build-console-assets.mjs`. The script launches `fixthis console` with `--console-assets-dir` (so the source-tree JS is served live), parses the `consoleUrl` from CLI output, and opens it in the default browser.

```bash
scripts/fixthis-console-dev.sh                                # default package
scripts/fixthis-console-dev.sh io.beyondwin.fixthis.sample    # explicit package
```

Stop with Ctrl-C; re-running kills any stale `fixthis console` process before starting a new one.

````

- [ ] **Step 3: Run the dry-run path to verify the script's `--dry-run` flag is real**

```bash
bash scripts/restart-console.sh --dry-run 2>&1 | head -10
```

Expected: at least one line beginning with `DRY: ` (e.g., `DRY: pkill -f 'fixthis-mcp.*--console'`). If the script doesn't print any `DRY:` lines, the example is wrong — fix the doc to match actual behavior before continuing.

- [ ] **Step 4: Run `fixthis-console-dev.sh` with a fake package and immediate Ctrl-C**

```bash
timeout 3 bash scripts/fixthis-console-dev.sh io.beyondwin.fixthis.sample 2>&1 | head -5 || true
```

Expected: prints at least the line `Starting fixthis console for io.beyondwin.fixthis.sample…` before `timeout` kills it. If the CLI is not built yet (`fixthis-cli/build/install/fixthis/bin/fixthis` missing), the script prints `CLI not built. Run: ./gradlew …` and exits 1 — that is acceptable for the verification (it proves the doc example is accurate).

- [ ] **Step 5: Verify the anchors render**

```bash
grep -nE "^### \`scripts/(restart-console|fixthis-console-dev)\.sh\`" CONTRIBUTING.md
```

Expected: two matches.

- [ ] **Step 6: Commit**

```bash
git add CONTRIBUTING.md
git commit -m "docs(contributing): document Console Inner Loop scripts"
```

---

### Task 4: Rewrite the Focused Test Loops block to use npm scripts

**Files:**
- Modify: `CONTRIBUTING.md`

- [ ] **Step 1: Read the current Focused Test Loops block using anchor patterns**

Use anchor-based extraction instead of absolute line numbers (which drift after every prior edit). Two reliable forms:

```bash
# Show the Focused Test Loops section in full
awk '/^### Focused Test Loops/,/^### |^## /' CONTRIBUTING.md

# Locate boundaries
grep -nE "^### Focused Test Loops|^## Required Local Checks" CONTRIBUTING.md
```

Expected: the `awk` invocation prints the entire section. The `grep` locates the header lines wherever they currently live (no line-number assumption).

- [ ] **Step 2: Replace the "Pure console JavaScript changes" block**

Find the block under `# Pure console JavaScript changes` (use `grep -n "Pure console JavaScript changes" CONTRIBUTING.md` to locate it without committing to a line number):

```bash
# Pure console JavaScript changes
node --test \
  scripts/console-availability-test.mjs \
  scripts/pendingItemRecovery-test.mjs \
  scripts/beforeunloadGuard-test.mjs \
  scripts/undoRedo-test.mjs \
  scripts/undoKeymatch-test.mjs \
  scripts/activityDrift-test.mjs \
  scripts/previewStaleness-test.mjs
```

Replace with:

```bash
# Pure console JavaScript changes
npm run console:test:fast
```

- [ ] **Step 3: Replace the "Draft workspace state-machine changes" line**

Find under `# Draft workspace state-machine changes`:

```bash
# Draft workspace state-machine changes
npm run console:draft:test
```

This line already uses npm — no change. Leave it.

- [ ] **Step 4: Replace the session-scope block (anchor: the `pendingBoundaryGuard-test.mjs` line)**

Use `grep -n pendingBoundaryGuard-test.mjs CONTRIBUTING.md` to find the block; then replace the whole `node --test \\` … `undoRedoContext-test.mjs` chunk with:

```bash
npm run console:session:test
```

- [ ] **Step 5: Replace the Required Local Checks raw-form block with the runner**

Locate the block via `awk '/^## Required Local Checks/,/^## /' CONTRIBUTING.md`. The block currently restates a `node --test \\` invocation. Replace it with:

```bash
# All console JS tests (single source of truth is scripts/console-tests.json).
node scripts/run-console-tests.mjs availability pending beforeunload undo activity preview draft session
# Equivalent to `npm run console:test:all`; edit the JSON, not this command line.
```

- [ ] **Step 6: Verify the rewritten focused loops actually work**

```bash
npm run console:test:fast
npm run console:session:test
```

Expected: both exit 0. (`console:test:all` is implicitly verified by the union of these two plus `console:draft:test`, the third already-existing aggregate is covered in Task 2.)

- [ ] **Step 7: Confirm the Required Local Checks block still produces the same test count as before**

Now there is only one place that names the test files (`scripts/console-tests.json`), so the verification reduces to:

```bash
node scripts/run-console-tests.mjs availability pending beforeunload undo activity preview draft session
```

Expected: exits 0; summary line reports the union of all groups.

- [ ] **Step 8: Commit**

```bash
git add CONTRIBUTING.md
git commit -m "docs(contributing): use npm run aliases in focused test loops"
```

---

### Task 5: Cross-link README and AGENTS Quick Starts; add Node badge

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`

- [ ] **Step 1: Add the Node badge to README**

Find (README.md lines 3–6):

```markdown
[![CI](https://github.com/beyondwin/FixThis/actions/workflows/ci.yml/badge.svg)](https://github.com/beyondwin/FixThis/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![JDK 21](https://img.shields.io/badge/JDK-21-orange.svg)](https://adoptium.net/)
[![Compose](https://img.shields.io/badge/Jetpack_Compose-2026.04.01-4285F4.svg)](https://developer.android.com/jetpack/compose)
```

Insert one new badge line after the JDK badge:

```markdown
[![Node 20+](https://img.shields.io/badge/Node-20%2B-339933.svg)](https://nodejs.org/)
```

- [ ] **Step 2: Add the AGENTS cross-link after the Quick Start**

Find in README.md (currently around line 47):

```markdown
→ Full walkthrough: [Quick Start with the sample](docs/getting-started/try-the-sample.md)
→ Add to your own app: [Add FixThis to your app](docs/getting-started/add-to-your-app.md)
→ Anything broken? [Troubleshooting](docs/guides/troubleshooting.md)
```

Insert a blank line above it, then a new paragraph immediately above the first `→` line:

```markdown
**Using Claude Code, Codex, or another MCP-aware agent?** See
[`AGENTS.md`](AGENTS.md) for the agent-first quick start
(`./scripts/bootstrap-mcp.sh`) and the MCP tool index.

```

(One trailing blank line separates the paragraph from the `→` block.)

- [ ] **Step 2B: Add the "Agents: read end-to-end" banner as the first content line of AGENTS.md**

Locate the H1 (`grep -n "^# " AGENTS.md | head -1`). Immediately after the H1 (no blank line skipped), insert:

```markdown

> **Agents:** read this file end-to-end. Sub-anchors may be skipped on a
> first pass, but every top-level section is load-bearing for correct
> tool use.

```

(Blank lines around the blockquote are intentional.) Verify with `grep -n "Agents: read this file end-to-end" AGENTS.md` — expect exactly one match in the first 10 lines.

- [ ] **Step 3: Add the Node bullet to AGENTS Prerequisites**

Find (AGENTS.md lines 11–15):

```markdown
## Prerequisites

- JDK 21+
- Android SDK with ADB (`adb devices` shows a connected device or emulator)
- Target app is a **debug build** (`debugImplementation` only; release is not supported)
```

Replace with:

```markdown
## Prerequisites

- JDK 21+
- Android SDK with ADB (`adb devices` shows a connected device or emulator)
- Node.js 20.0+ LTS (for console JS harnesses; see `CONTRIBUTING.md` § Prerequisites)
- Target app is a **debug build** (`debugImplementation` only; release is not supported)
```

- [ ] **Step 4: Add the README cross-link after the AGENTS Quick Start**

Find in AGENTS.md (currently around line 31, after `Manual setup, full CLI flags...`):

```markdown
Manual setup, full CLI flags, and dry-run examples:
[`docs/reference/cli.md`](docs/reference/cli.md).
```

Append, separated by one blank line:

```markdown

**Trying FixThis from scratch on the sample app?** Start at the README's
[Quick Start](README.md#quick-start-sample-app-5-min) for the human-driven
flow (build → doctor → run → console UI).
```

- [ ] **Step 5: Verify the doc consistency invariants now hold**

```bash
grep -n "shields.io/badge/Node" README.md
node scripts/check-doc-consistency.mjs
```

Expected: grep returns one match (badge present); the drift detector exits 0 with every PASS rule, including the new R6 anchor-verify rule and R3 cross-reference rule. (The earlier draft of this plan ran `curl shields.io`; that check verified only that shields.io was up, which is irrelevant to the diff. Replaced with the actual drift detector.)

- [ ] **Step 6: Verify the cross-links exist and target real anchors**

```bash
grep -n "AGENTS.md" README.md | head
grep -n "README.md#quick-start-sample-app-5-min" AGENTS.md
```

Expected: ≥1 match each. The anchor `quick-start-sample-app-5-min` is GitHub's auto-anchor for the heading `## Quick Start (sample app, ~5 min)` (lowercased, `~` dropped, parens dropped, spaces → `-`, commas dropped). Cross-check by opening the rendered README on GitHub and clicking the anchor.

- [ ] **Step 7: Commit**

```bash
git add README.md AGENTS.md
git commit -m "docs: cross-link README and AGENTS Quick Starts + Node badge"
```

---

### Task 6: Refresh the CLAUDE.md restart-console pointer to a new CONTRIBUTING anchor

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Locate the current pointer**

```bash
grep -n "restart-console.sh" CLAUDE.md
```

Expected: one match in the "Restart loop after Kotlin changes" bullet (currently CLAUDE.md lines 12–15).

- [ ] **Step 2: Update the pointer to link CONTRIBUTING.md's new section**

Find the existing bullet:

```markdown
- **Restart loop after Kotlin changes** → run `bash scripts/restart-console.sh`
  (add `--with-app` to also reinstall the sample APK). Kotlin server code is
  pinned in the JAR; the console JS is live-reloaded under
  `--console-assets-dir`.
```

Replace with:

```markdown
- **Restart loop after Kotlin changes** → run `bash scripts/restart-console.sh`
  (add `--with-app` to also reinstall the sample APK). Kotlin server code is
  pinned in the JAR; the console JS is live-reloaded under
  `--console-assets-dir`. Full flag list in
  [`CONTRIBUTING.md` § Console Inner Loop](CONTRIBUTING.md#console-inner-loop).
```

- [ ] **Step 3: Verify the anchor resolves**

```bash
grep -n "^## Console Inner Loop" CONTRIBUTING.md
```

Expected: one match. GitHub anchor: `#console-inner-loop`.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude): link restart-console pointer to CONTRIBUTING anchor"
```

---

### Task 7: Create scripts/check-doc-consistency.mjs drift detector

**Files:**
- Create: `scripts/check-doc-consistency.mjs`

- [ ] **Step 1: Confirm parent directory and conventions**

```bash
ls scripts/ | head
```

Expected: existing `.mjs` files (e.g. `build-console-assets.mjs`). The new script follows the same naming + executable bit convention. We do not need to set executable on `.mjs` (invoked via `node …`), but the existing pattern uses no shebang.

- [ ] **Step 2: Write the script**

Create `scripts/check-doc-consistency.mjs` with:

```js
// Lightweight DX-docs drift detector.
// Run with: node scripts/check-doc-consistency.mjs
// Exit 0 on success; exit 1 with rule name on failure.
// No dependencies; runs on Node >=18.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const read = (rel) => fs.readFileSync(path.join(repoRoot, rel), "utf8");

const failures = [];

function check(rule, condition, detail) {
  if (!condition) failures.push(`FAIL ${rule}: ${detail}`);
  else console.log(`PASS ${rule}`);
}

// Rule 1: every `console:*` script in package.json appears in CONTRIBUTING.md.
const pkg = JSON.parse(read("package.json"));
const consoleScripts = Object.keys(pkg.scripts ?? {}).filter((k) => k.startsWith("console:"));
const contributing = read("CONTRIBUTING.md");
for (const script of consoleScripts) {
  check(
    `R1.${script}`,
    contributing.includes(script),
    `package.json defines '${script}' but CONTRIBUTING.md does not reference it.`,
  );
}

// Rule 2: every `npm run console:*` referenced in CONTRIBUTING.md exists.
const referenced = [...contributing.matchAll(/npm run (console:[\w:]+)/g)].map((m) => m[1]);
for (const script of referenced) {
  check(
    `R2.${script}`,
    consoleScripts.includes(script),
    `CONTRIBUTING.md references 'npm run ${script}' but package.json does not define it.`,
  );
}

// Rule 3: README links to AGENTS.md and vice versa.
const readme = read("README.md");
const agents = read("AGENTS.md");
check("R3.readme->agents", readme.includes("AGENTS.md"), "README.md does not link to AGENTS.md.");
check("R3.agents->readme", agents.includes("README.md"), "AGENTS.md does not link to README.md.");

// Rule 4: both contributor scripts are named in CONTRIBUTING.md.
check(
  "R4.restart-console",
  contributing.includes("scripts/restart-console.sh"),
  "CONTRIBUTING.md does not mention scripts/restart-console.sh.",
);
check(
  "R4.fixthis-console-dev",
  contributing.includes("scripts/fixthis-console-dev.sh"),
  "CONTRIBUTING.md does not mention scripts/fixthis-console-dev.sh.",
);

// Rule 5: package.json has engines.node defined.
check(
  "R5.engines",
  Boolean(pkg.engines && pkg.engines.node),
  "package.json missing engines.node field.",
);

// Rule 6: every *.md#anchor link in the DX docs resolves to an existing heading.
// Uses github-slugger to compute canonical anchors.
import GithubSlugger from "github-slugger";
const dxDocs = ["README.md", "AGENTS.md", "CONTRIBUTING.md", "CLAUDE.md"];
const docFiles = new Map();
for (const f of dxDocs) {
  try { docFiles.set(f, read(f)); } catch {}
}
const anchorLink = /\[[^\]]+\]\(([^)\s]+\.md)#([^)\s]+)\)/g;
for (const [src, content] of docFiles) {
  for (const m of content.matchAll(anchorLink)) {
    const [, targetPath, anchor] = m;
    const targetKey = path.normalize(targetPath);
    const target = docFiles.get(targetKey) ?? (fs.existsSync(path.join(repoRoot, targetPath)) ? read(targetPath) : null);
    if (!target) continue; // skip external/missing files; R3 covers cross-file presence
    const slugger = new GithubSlugger();
    const headings = [...target.matchAll(/^#+\s+(.+)$/gm)].map((mm) => slugger.slug(mm[1].trim()));
    check(
      `R6.${src}->${targetPath}#${anchor}`,
      headings.includes(anchor),
      `${src} links to ${targetPath}#${anchor} but no matching heading exists.`,
    );
  }
}

if (failures.length > 0) {
  console.error("\n" + failures.join("\n"));
  process.exit(1);
}
console.log("\nAll doc-consistency rules pass.");
```

- [ ] **Step 3: Run the script**

```bash
node scripts/check-doc-consistency.mjs
echo "exit=$?"
```

Expected: every rule prints `PASS Rx.…`, ending with `All doc-consistency rules pass.` and `exit=0`. If a `FAIL` appears, *the failing rule names a real drift* — fix the underlying doc, do not adjust the rule.

- [ ] **Step 4: Smoke-test the failure path**

Temporarily revert one piece of the migration to confirm the detector catches drift:

```bash
# Save current state
cp CONTRIBUTING.md CONTRIBUTING.md.bak
# Remove the only mention of fixthis-console-dev.sh
sed -i.tmp '/fixthis-console-dev.sh/d' CONTRIBUTING.md
# Re-run
node scripts/check-doc-consistency.mjs || echo "Detector returned $?"
# Restore
mv CONTRIBUTING.md.bak CONTRIBUTING.md
rm -f CONTRIBUTING.md.tmp
```

Expected: the second invocation prints `FAIL R4.fixthis-console-dev: …` and exits with `Detector returned 1`. After restore, re-running should pass.

- [ ] **Step 5: Document the drift detector as a REQUIRED local check**

In CONTRIBUTING.md, immediately after the new `## Console Inner Loop` section (from Task 3), insert:

```markdown
### Documentation consistency check (required)

After editing `package.json`, README, AGENTS.md, or this file, run:

```bash
node scripts/check-doc-consistency.mjs
```

The script verifies that npm scripts and CONTRIBUTING.md agree, that README ↔ AGENTS cross-links exist, that the contributor scripts are documented here, and that every `*.md#anchor` link resolves to a real heading (via `github-slugger`). It exits non-zero with a `FAIL Rx.…` line if any rule breaks.

```

Then **add the command to the Required Local Checks block** so it runs alongside the Gradle matrix and console asset check. The line goes adjacent to the existing `node scripts/build-console-assets.mjs --check` line:

```bash
node scripts/check-doc-consistency.mjs
```

- [ ] **Step 5B: Wire the drift detector into CI**

Edit `.github/workflows/ci.yml`. Add a new job (or extend an existing JS job) with the step:

```yaml
- name: Doc consistency check
  run: node scripts/check-doc-consistency.mjs
```

If a JS dependency install step does not already exist in that job, add `npm ci` before the new step so `github-slugger` is available.

- [ ] **Step 6: Re-run the detector after adding its own documentation**

```bash
node scripts/check-doc-consistency.mjs
echo "exit=$?"
```

Expected: still passes.

- [ ] **Step 7: Commit**

```bash
git add scripts/check-doc-consistency.mjs CONTRIBUTING.md
git commit -m "tools: add doc-consistency drift detector (opt-in, not CI-enforced)"
```

---

### Task 8: Final verification pass

**Files:** (none modified — verification only)

- [ ] **Step 1: Run the drift detector one more time**

```bash
node scripts/check-doc-consistency.mjs
```

Expected: all PASS, exit 0.

- [ ] **Step 2: Run the full set of new and renamed npm scripts**

```bash
npm run console:test:fast
npm run console:session:test
npm run console:activity:test
npm run console:preview:test
```

Expected: each exits 0.

- [ ] **Step 3: Verify the README badge renders (offline-tolerant)**

```bash
grep -nE "shields\.io/badge/(Node|JDK|License|Jetpack)" README.md
```

Expected: four matches (one per badge). Visual rendering verified manually by viewing the README on GitHub after push.

- [ ] **Step 4: Verify each canonical anchor exists**

```bash
grep -nE "^## Prerequisites|^## Console Inner Loop|^### Documentation consistency check" CONTRIBUTING.md
```

Expected: three matches. The corresponding GitHub anchors are `#prerequisites`, `#console-inner-loop`, `#documentation-consistency-check`.

- [ ] **Step 5: Re-run a partial Required Local Checks block to confirm no regression**

```bash
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
```

Expected: both exit 0. (Skip the full Gradle matrix here; this verifies only that the console workflow described in CONTRIBUTING.md still works after the doc rewrite.)

- [ ] **Step 6: Confirm git tree is clean except for tracked changes**

```bash
git status --short
```

Expected: clean (all six commits landed) or showing only intended uncommitted changes.

- [ ] **Step 7: No commit (verification-only task)**

If any PASS/FAIL above flipped, return to the relevant earlier task; do not silently patch here.

---

## Self-Review Checklist

- [x] G1 (SSOT policy) — Topic ownership table embedded in the spec; Tasks 1, 3 create the canonical sections; Tasks 5, 6 update consumers to link.
- [x] G2 (scripts documented) — Task 3 adds both `restart-console.sh` and `fixthis-console-dev.sh` to CONTRIBUTING.md with working examples.
- [x] G3 (npm catalog unified) — Task 2 adds missing scripts; Task 4 rewrites Focused Test Loops to npm form.
- [x] G4 (README ↔ AGENTS cross-refs) — Task 5 adds bidirectional cross-links.
- [x] G5 (Node + Chromium documented) — Task 1 (CONTRIBUTING table), Task 2 (`engines.node`), Task 5 (AGENTS bullet + README badge).
- [x] G6 (drift detector enforced) — Task 7 Step 5/5B add the drift detector to CONTRIBUTING.md Required Local Checks and to the CI workflow.
- [x] G7 (each doc change paired with verification) — Every task has a `Run …` step with asserted output.
- [x] No "TBD" / "similar to" / placeholders.
- [x] Commits independent and small (Tasks 1, 2, 3, 4, 5, 6, 7 each commit; Task 8 is verification only).
- [x] All file paths absolute or relative to repo root.
