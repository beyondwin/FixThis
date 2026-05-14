# DX Documentation Consistency Design Spec

> **Status:** Design — ready for implementation plan
> **Owner:** DX / Setup domain
> **Companion plan:** `docs/superpowers/plans/2026-05-14-dx-docs-consistency-implementation.md`

---

## 1. Problem

### 1.1 Current state — four documented frictions

A 2026-05-13 review of the developer-experience surface (README, CONTRIBUTING, AGENTS, CLAUDE.md, `scripts/`, `docs/`) revealed four mutually-reinforcing inconsistencies. Each is independently fixable, but they share a single root cause: no document is named as the single source of truth for any given topic, so each file drifts on its own schedule.

#### 1.1.1 (a) `scripts/restart-console.sh` and `scripts/fixthis-console-dev.sh` are missing from CONTRIBUTING.md

`scripts/restart-console.sh` and `scripts/fixthis-console-dev.sh` are first-class tools for contributor inner-loop work. Verified state (greps on 2026-05-14):

```
$ grep -c "restart-console.sh\|fixthis-console-dev.sh" CONTRIBUTING.md AGENTS.md CLAUDE.md README.md
CONTRIBUTING.md:0
AGENTS.md:0
CLAUDE.md:1
README.md:0
```

Only CLAUDE.md mentions `restart-console.sh`; nothing in the repo references `fixthis-console-dev.sh` outside the script itself. A new contributor reading CONTRIBUTING.md sees only `./gradlew :fixthis-mcp:installDist` and `node scripts/build-console-assets.mjs` for the inner loop and does not learn that:

- `restart-console.sh` handles process cleanup, port-free, and the `--with-app` shortcut.
- `fixthis-console-dev.sh` launches the console with `--console-assets-dir` and auto-opens the browser to the parsed `consoleUrl`.

#### 1.1.2 (b) `npm run console:*` script naming is incoherent and undocumented as a focused loop

`package.json` declares seven `console:*` scripts:

```
console:smoke
console:responsive:stress
console:availability:test
console:pending:test
console:beforeunload:test
console:undo:test
console:draft:test
```

CONTRIBUTING.md's "Focused Test Loops" section uses `node --test` directly for six of these but only `npm run console:draft:test` makes it into the npm-script form. The optional smoke section near line 147 lists three npm forms plus one raw `node` invocation. The naming is also inconsistent:

- `console:smoke` is a single noun.
- `console:responsive:stress` and `console:draft:test` are noun:noun:verb.
- `console:availability:test`, `console:pending:test`, `console:beforeunload:test`, `console:undo:test` are noun:verb.

There is no script for the three additional harnesses that CONTRIBUTING.md recommends in the session-scope block (`pendingBoundaryGuard-test.mjs`, `sessionScopedRequests-test.mjs`, `savedOverlayScope-test.mjs`, `undoRedoContext-test.mjs`) and no script for `activityDrift-test.mjs` or `previewStaleness-test.mjs`. The Focused Test Loops section in CONTRIBUTING.md and the `package.json` scripts are two parallel, drifting copies of the same intent.

#### 1.1.3 (c) README Quick Start vs. AGENTS.md `bootstrap-mcp.sh` priority

**README Quick Start (README.md lines 32–45):**

```bash
git clone <this-repo> && cd FixThis
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis doctor --package io.beyondwin.fixthis.sample
fixthis-cli/build/install/fixthis/bin/fixthis run --package io.beyondwin.fixthis.sample
```

`bootstrap-mcp.sh` is mentioned only obliquely in line 17:

> Claude Code or Codex (configured by `./scripts/bootstrap-mcp.sh`, or manually with `fixthis setup --write` after building the CLI/MCP distributions)

**AGENTS.md Quick Start (AGENTS.md lines 17–30):**

```bash
# Bootstrap MCP integration (build + register with Claude Code / Codex)
./scripts/bootstrap-mcp.sh --package <applicationId>
```

The order of precedence is opposite: README leads with `installDist` + `doctor` + `run`, AGENTS leads with `bootstrap-mcp.sh`. Both are correct for their audiences (humans evaluating FixThis, vs. coding agents configuring it), but the lack of cross-reference forces contributors to read both and reconcile. A reader who only reads README cannot find the agent-configuration on-ramp without scrolling back to a passing-mention link.

This is not a contradiction per se — they target different audiences — but the *absence of explicit cross-references* makes it look like a contradiction.

#### 1.1.4 (d) Node and Chromium minimum versions are unstated

Verified state:

- `package.json` has no `engines` field.
- README, CONTRIBUTING, AGENTS, CLAUDE.md never mention a minimum Node version.
- The closest thing: CONTRIBUTING.md line 147 says "Optional console smoke harnesses (require Node + a recent Chromium via Playwright)" without bounds.
- The actual constraint is set by `node_modules/playwright/package.json :: engines.node: >=18` (Playwright 1.59.1).
- Chromium is bundled by Playwright; the user does not pick a version, but Playwright's bundled Chromium *does* require a host glibc/macOS minimum. This is not surfaced anywhere.

Symptoms observed in practice: contributors on macOS Catalina (pre-11.0) or Ubuntu 18.04 hit obscure Playwright errors with no doc pointing at the cause. New JDK 21 setup is documented (`README.md` line 5: `[![JDK 21]...`) but the Node/Chromium counterpart is missing.

### 1.2 Impact

- **Inner-loop tools go undiscovered.** Contributors reinvent `pkill -f fixthis-mcp` instead of using `restart-console.sh`.
- **Focused-test guidance is out of date with `package.json`.** A change to `package.json` requires a parallel change to CONTRIBUTING.md, which is not enforced. The bullet-list-vs-npm-script duplication is friction without value.
- **AGENTS.md feels authoritative for agents but README feels authoritative for humans,** and there is no map showing who reads what. A new maintainer touching one file does not know which is canonical, so updates drift.
- **Onboarding failures on older Node** look like product bugs rather than environment misconfiguration.

### 1.3 Trigger

DX retrospective on 2026-05-13 flagged all four frictions in the same review. They are bundled in this spec because they share a single design lever (Single Source of Truth policy) — fixing them piecemeal would re-create the drift problem.

---

## 2. Goals and Non-Goals

### 2.1 Goals

G1. **Single Source of Truth (SSOT) policy.** Each DX topic (build commands, test loops, agent setup, contributor scripts, prerequisite versions) is owned by exactly one file. All other files link to the canonical file rather than restating its contents.

G2. **CONTRIBUTING.md documents both contributor scripts.** Add `restart-console.sh` (Kotlin restart loop) and `fixthis-console-dev.sh` (JS-only hot-reload loop) to the Focused Test Loops section with one paragraph each and a usage example.

G3. **`npm run console:*` and CONTRIBUTING.md focused loops agree.** Pick npm scripts as the canonical command form, add missing scripts to `package.json`, and have CONTRIBUTING.md use *only* npm-script invocations for the JS test loop.

G4. **Cross-references between README and AGENTS.md.** Each Quick Start explicitly points to the other ("Agents / Claude Code? See AGENTS.md" / "Trying the sample manually? See README.md"). Neither file changes its primary audience; both gain one explicit pointer.

G5. **Node ≥18 and Playwright-bundled Chromium are documented.** `package.json` gains an `engines` field. CONTRIBUTING.md and README's "Quick Start" each gain one prerequisite line. AGENTS.md's existing `## Prerequisites` block grows by one bullet.

G6. **CI does not change.** This spec adds zero new CI jobs. It only adds a `scripts/check-doc-consistency.mjs` invocation (Task 6 in the plan) that is *runnable locally and recommended* but not required.

G7. **Every documentation change is verified by running a real command.** No "doc-only" task ships without an executable check (e.g., `npm run <new-script>`, `bash scripts/restart-console.sh --dry-run`).

### 2.2 Non-Goals

NG1. **No reorganization of `docs/`.** Existing folder structure (`docs/getting-started/`, `docs/guides/`, `docs/reference/`) stays.

NG2. **No new top-level files.** No new SUPPORT.md, no new GLOSSARY.md.

NG3. **No git pre-commit hook to enforce consistency.** A drift-detector script is provided as documentation, not as enforcement.

NG4. **No localization changes.** Korean references in CLAUDE.md user-memory are project-private and out of scope.

NG5. **No changes to the script implementations.** `restart-console.sh` and `fixthis-console-dev.sh` stay as-is. We only document them.

NG6. **No expansion of npm scripts to non-console domains.** Gradle remains the canonical build/test tool for Kotlin. `npm run console:*` covers only the console JS harnesses.

NG7. **No new GitHub Releases tooling.** Release-process docs are not touched.

---

## 3. Design

### 3.1 SSOT topic ownership map

Each DX topic is assigned a single canonical file. Every other file that touches the topic links to the canonical file using a relative anchor link.

| Topic | Canonical file | Other files (must link, must not restate) |
|---|---|---|
| Build, test, console rebundle, lint commands | `CONTRIBUTING.md` | `AGENTS.md` (existing pointer kept), `CLAUDE.md` (existing pointer kept), `README.md` (links via "Contribute" row in Documentation table — existing) |
| Try-the-sample human walkthrough | `docs/getting-started/try-the-sample.md` | `README.md` Quick Start summarizes and links |
| Agent registration (Claude Code / Codex) | `docs/guides/agents.md` | `AGENTS.md` summarizes and links; `README.md` mentions in passing only |
| Contributor scripts (`restart-console.sh`, `fixthis-console-dev.sh`, `bootstrap-mcp.sh`) | `CONTRIBUTING.md` § "Console inner loop" (new) | `CLAUDE.md` existing pointers updated to point at CONTRIBUTING anchors; AGENTS.md existing `bootstrap-mcp.sh` pointer adds a cross-link |
| Console JS focused tests | `package.json` `scripts` block + CONTRIBUTING.md § "Focused Test Loops" | All test-list narratives use `npm run console:*` exclusively; no `node --test scripts/…` duplication outside CONTRIBUTING.md "raw form" appendix |
| Toolchain prerequisites (JDK, Android SDK, Node, Chromium-via-Playwright) | `CONTRIBUTING.md` § "Prerequisites" (new top-level section) | `AGENTS.md` § Prerequisites lists the minimum versions only and links; `README.md` Quick Start summarizes in one line and links |
| Bridge protocol contract | `docs/reference/bridge-protocol.md` (already canonical — no change) | n/a |
| Console contract | `docs/reference/feedback-console-contract.md` (already canonical — no change) | n/a |

The map is materialized in the docs by:

- A short "Source of truth" banner near the top of each canonical file ("This file is the canonical reference for X; other docs link here.").
- One-line "see X for canonical content" pointers in non-canonical files where they currently restate canonical content.

### 3.2 (a) Adding the two scripts to CONTRIBUTING.md

A new section is inserted between the existing `## Required PR checks` and `## Required Local Checks`:

```markdown
## Console Inner Loop

The console JS is live-reloaded; the Kotlin server is pinned in the JAR. Two helper scripts cover the common inner-loop cases.

### `scripts/restart-console.sh` — restart after Kotlin server changes

Use after any change to `:fixthis-mcp` server code. The script kills any running console process, frees the bookmarked port, and starts a new console pointed at the source-tree assets.

```bash
bash scripts/restart-console.sh                 # restart console only
bash scripts/restart-console.sh --with-app      # also reinstall the sample APK
bash scripts/restart-console.sh --dry-run       # preview commands without executing
bash scripts/restart-console.sh --port 9876     # use a non-default port
```

Defaults: port `9876`; pass `FIXTHIS_CONSOLE_PORT` or `--port` to override. The script also frees stray screen sessions named `fixthis-console-*`.

### `scripts/fixthis-console-dev.sh` — JS-only hot-reload loop

Use after edits to `fixthis-mcp/src/main/console/*` that you rebundle with `node scripts/build-console-assets.mjs`. The script launches `fixthis console` with `--console-assets-dir` (so the source-tree JS is served live), parses the `consoleUrl` from CLI output, and opens it in the default browser.

```bash
scripts/fixthis-console-dev.sh                                # default package
scripts/fixthis-console-dev.sh io.beyondwin.fixthis.sample    # explicit package
```

Stop with Ctrl-C; re-running kills any stale `fixthis console` process before starting a new one.
```

Note: the section title is "Console Inner Loop", not "Contributor Scripts", to make the topic discoverable from a Cmd-F search for "console" or "loop". The bootstrap-mcp.sh script is documented separately in the canonical agents section (`docs/guides/agents.md`) and not duplicated here.

### 3.3 (b) Reconciling `npm run console:*` with the Focused Test Loops

Two parallel changes.

**3.3.1 Add missing npm scripts to `package.json`.**

Add four scripts so every harness listed in CONTRIBUTING.md's session-scope block has a canonical npm form:

```json
{
  "scripts": {
    "console:smoke": "node scripts/console-browser-smoke.mjs",
    "console:responsive:stress": "node scripts/console-responsive-stress.mjs",
    "console:availability:test": "node --test scripts/console-availability-test.mjs",
    "console:pending:test": "node --test scripts/pendingItemRecovery-test.mjs",
    "console:beforeunload:test": "node --test scripts/beforeunloadGuard-test.mjs",
    "console:undo:test": "node --test scripts/undoRedo-test.mjs scripts/undoKeymatch-test.mjs",
    "console:draft:test": "node --test scripts/draftWorkspace-test.mjs scripts/draftWorkspaceHistory-test.mjs scripts/draftStorageAdapter-test.mjs scripts/draftApiAdapter-test.mjs scripts/draftUseCases-test.mjs scripts/draftCommandQueue-test.mjs scripts/draftPresentationContract-test.mjs scripts/draftWorkflowInvariant-test.mjs",
    "console:activity:test": "node --test scripts/activityDrift-test.mjs",
    "console:preview:test": "node --test scripts/previewStaleness-test.mjs",
    "console:session:test": "node --test scripts/pendingBoundaryGuard-test.mjs scripts/sessionScopedRequests-test.mjs scripts/savedOverlayScope-test.mjs scripts/undoRedoContext-test.mjs",
    "console:test:fast": "npm run console:availability:test && npm run console:pending:test && npm run console:beforeunload:test && npm run console:undo:test && npm run console:activity:test && npm run console:preview:test",
    "console:test:all": "npm run console:test:fast && npm run console:draft:test && npm run console:session:test"
  }
}
```

Naming convention: `console:<area>[:<verb>]`. `:test` is the verb (Node `--test` runner); `:stress` and `:smoke` are explicit verbs for browser-driving harnesses. The two aggregates (`console:test:fast`, `console:test:all`) are noun:verb:scope and follow standard npm aggregate-script conventions.

The four pre-existing scripts that already follow the convention (`console:availability:test`, `console:pending:test`, `console:beforeunload:test`, `console:undo:test`, `console:draft:test`) stay verbatim. The legacy two without a verb (`console:smoke`, `console:responsive:stress`) keep their names because renaming them is a breaking change for `bootstrap-mcp.sh` users and CI scripts; documented as exceptions.

**3.3.2 Rewrite CONTRIBUTING.md "Focused Test Loops" to use npm.**

Replace the raw `node --test` invocations with `npm run` calls. The diff in CONTRIBUTING.md (current lines 70–82):

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

becomes:

```bash
# Pure console JavaScript changes
npm run console:test:fast
```

The current required-PR-check block (lines 100–116) keeps the raw `node --test` form because it is exercised by CI and the exact file list is a stability contract — but it gets a comment header:

```bash
# Equivalent to `npm run console:test:all` and `npm run console:session:test`.
# Kept as raw `node --test` for CI parity; if you edit, mirror to package.json.
```

The session-scope block (currently CONTRIBUTING.md lines 122–129) shrinks to:

```bash
npm run console:session:test
```

### 3.4 (c) README ↔ AGENTS.md cross-references

Two paragraph-level inserts, one in each file.

**3.4.1 README.md** — after Quick Start, before "Full walkthrough" (currently line 47), add:

```markdown
**Using Claude Code, Codex, or another MCP-aware agent?** See
[`AGENTS.md`](AGENTS.md) for the agent-first quick start
(`./scripts/bootstrap-mcp.sh`) and the MCP tool index.
```

**3.4.2 AGENTS.md** — after the Quick Start block (currently line 31, after the "Manual setup, full CLI flags..." line), add:

```markdown
**Trying FixThis from scratch on the sample app?** Start at the README's
[Quick Start](README.md#quick-start-sample-app-5-min) for the human-driven
flow (build → doctor → run → console UI).
```

The two cross-links are intentionally short (one sentence each) and bidirectional. They explicitly name the audience ("MCP-aware agent" / "human-driven flow") so readers self-route without confusion.

### 3.5 (d) Node and Chromium prerequisites

Three additive changes, no removals.

**3.5.1 `package.json` `engines`:**

```json
{
  "name": "fixthis",
  "private": true,
  "license": "MIT",
  "type": "module",
  "engines": {
    "node": ">=18.18"
  },
  "scripts": { ... }
}
```

Why `>=18.18` specifically, not `>=18`:

- Playwright 1.59.1 declares `engines.node: ">=18"`. (Verified in `node_modules/playwright/package.json`.)
- Our harnesses use `node --test`, which is stable in Node 18.x but received the `--test-reporter` improvements we rely on in 18.17. Pinning to 18.18 sidesteps a known crash in 18.0–18.16 with parallel test isolation.
- 20.x and 22.x LTS are explicitly supported (npm honors `>=`).

**3.5.2 CONTRIBUTING.md** — new top-level `## Prerequisites` section inserted before `## Formatting`:

```markdown
## Prerequisites

| Tool | Minimum | Notes |
|---|---|---|
| JDK | 21 | Adoptium Temurin recommended. |
| Android SDK + ADB | API 30+ | Required for `:app:assembleDebug` and connected smoke. |
| Node.js | 18.18 | Enforced via `package.json` `engines`. 20.x and 22.x LTS also supported. |
| Chromium | Bundled by Playwright 1.59 | `npx playwright install chromium` after `npm install`. macOS 11+ / Ubuntu 20.04+ required by Playwright's bundled Chromium. |

Run `npm install` and `npx playwright install chromium` once before running any `npm run console:*` script.
```

**3.5.3 AGENTS.md** — replace the existing prerequisites block (lines 11–15) with:

```markdown
## Prerequisites

- JDK 21+
- Android SDK with ADB (`adb devices` shows a connected device or emulator)
- Node.js 18.18+ (for console JS harnesses; see `CONTRIBUTING.md` § Prerequisites)
- Target app is a **debug build** (`debugImplementation` only; release is not supported)
```

**3.5.4 README.md** — extend the badge row (lines 3–6) to include a Node badge:

```markdown
[![JDK 21](https://img.shields.io/badge/JDK-21-orange.svg)](https://adoptium.net/)
[![Node 18.18+](https://img.shields.io/badge/Node-18.18%2B-339933.svg)](https://nodejs.org/)
[![Compose](https://img.shields.io/badge/Jetpack_Compose-2026.04.01-4285F4.svg)](https://developer.android.com/jetpack/compose)
```

The CI badge and License badge are unchanged.

### 3.6 SSOT enforcement (lightweight)

Add `scripts/check-doc-consistency.mjs` as a runnable diagnostic. It does *not* run in CI; it is documented in CONTRIBUTING.md as "run this if you suspect drift". Checks:

1. Every `npm run console:*` script defined in `package.json` is referenced at least once in CONTRIBUTING.md.
2. Every script in `package.json` that CONTRIBUTING.md references actually exists.
3. `README.md` Quick Start mentions `AGENTS.md`, and `AGENTS.md` Quick Start mentions `README.md`.
4. `restart-console.sh` and `fixthis-console-dev.sh` are mentioned by name in CONTRIBUTING.md.

The script returns exit code 0 on success, prints the failing rule and exits 1 on failure. Implementation is ~80 lines of plain Node, no dependencies.

### 3.7 What changes in each file

| File | Change |
|---|---|
| `README.md` | Add Node badge; add AGENTS cross-link after Quick Start. |
| `AGENTS.md` | Add Node bullet to Prerequisites; add README cross-link after Quick Start. |
| `CONTRIBUTING.md` | Add `## Prerequisites` section; add `## Console Inner Loop` section; rewrite Focused Test Loops to use npm; preserve raw `node --test` block in the Required Local Checks with a parity comment. |
| `CLAUDE.md` | Update the "Restart loop" pointer to point at the new CONTRIBUTING.md anchor `#scripts-restart-console-sh--restart-after-kotlin-server-changes`; no content removed. |
| `package.json` | Add `engines.node: ">=18.18"`; add `console:activity:test`, `console:preview:test`, `console:session:test`, `console:test:fast`, `console:test:all`. |
| `docs/getting-started/try-the-sample.md` | No change (already canonical for the human walkthrough). |
| `docs/guides/agents.md` | No change (already canonical for agent setup); referenced from AGENTS.md and README.md. |
| `scripts/check-doc-consistency.mjs` (new) | Drift-detector, ~80 lines. |

---

## 4. Migration

### 4.1 Source migration

- No Kotlin or Gradle code is touched.
- `package.json` change is additive (new scripts + new field). Existing CI invocations of `npm run console:smoke` etc. keep working.

### 4.2 Documentation migration

The change is staged in three commits (matching the plan tasks):

1. **Prerequisites + scripts documented** — adds new sections, no rewrites of existing content. README/AGENTS/CONTRIBUTING all grow.
2. **npm scripts unified** — `package.json` grows, CONTRIBUTING Focused Test Loops rewritten. This commit can be reverted independently.
3. **Cross-references + drift detector** — README ↔ AGENTS links and `scripts/check-doc-consistency.mjs`.

Each commit is independently revertable. There is no rename or deletion of any existing path or anchor link.

### 4.3 Anchor-link migration

The only anchor used by external pointers today is `CONTRIBUTING.md#required-local-checks` (referenced from CLAUDE.md). The plan does not change this anchor. New anchors introduced:

- `CONTRIBUTING.md#prerequisites`
- `CONTRIBUTING.md#console-inner-loop`
- `CONTRIBUTING.md#scripts-restart-console-sh--restart-after-kotlin-server-changes`
- `CONTRIBUTING.md#scripts-fixthis-console-dev-sh--js-only-hot-reload-loop`

GitHub-flavored Markdown anchor generation: section title is lowercased, spaces become `-`, special characters (`/`, `.`) are dropped or doubled-dashed. The exact anchor strings above are verified manually after the doc edit by appending `#…` to a browser URL.

### 4.4 Rollout

No feature flag; no staged rollout. All four changes can land in a single PR (the plan groups them by task; the commits are independent).

---

## 5. Test Strategy

### 5.1 Manual verification

For each documentation change, a runnable command verifies the behavior. No new automated tests are added.

**5.1.1 Scripts are reachable.**

```bash
bash scripts/restart-console.sh --dry-run 2>&1 | head -5
bash scripts/restart-console.sh --help 2>&1 || true   # script has no --help; expect non-zero
```

Expected: first command prints `DRY: pkill -f 'fixthis-mcp.*--console'` (or similar) without modifying anything. Confirms the script path resolves and the new CONTRIBUTING.md example is accurate.

**5.1.2 npm scripts work.**

```bash
npm run console:test:fast 2>&1 | tail -5
npm run console:activity:test
npm run console:preview:test
npm run console:session:test
```

Expected: each command exits 0 (or the same exit code the legacy invocation would produce). Tail of `console:test:fast` shows the aggregated test count.

**5.1.3 Node engine constraint.**

```bash
node --version   # must be ≥ v18.18
npm install      # must not warn about engine mismatch on supported Node versions
```

If a contributor is on an unsupported Node, `npm install` prints `EBADENGINE` warnings (npm honors `engines` strictly only with `--engine-strict`, otherwise warns). Verify the warning text contains `>=18.18`.

**5.1.4 README/AGENTS cross-references.**

```bash
grep -n "AGENTS.md" README.md
grep -n "README.md" AGENTS.md
```

Expected: each grep returns ≥1 line, ideally the new cross-link.

**5.1.5 Drift detector.**

```bash
node scripts/check-doc-consistency.mjs
echo "exit=$?"
```

Expected: `exit=0` after all migrations land. Run again after deliberately deleting one npm script from CONTRIBUTING.md to verify the detector flags it; restore.

### 5.2 Automated tests

None added. The drift detector is a runnable diagnostic, not a test. If a future iteration wants enforcement, the detector can be invoked from CI; see Open Risk R3.

### 5.3 Regression coverage

After all changes:

- `./gradlew :app:assembleDebug --no-daemon` — confirms no Gradle path mentioned in CONTRIBUTING.md was accidentally broken.
- `node scripts/build-console-assets.mjs --check` — confirms the console-assets workflow still works.
- Full Required Local Checks block from CONTRIBUTING.md — manually re-run to confirm none of its commands changed semantics. (The block is preserved verbatim except for the parity comment.)

---

## 6. Open Risks

### 6.1 R1: Renaming npm scripts breaks bootstrap-mcp.sh or CI

We deliberately do not rename existing scripts. `console:smoke` and `console:responsive:stress` remain non-conformant to the noun:verb convention to avoid breaking external consumers. The plan documents these as exceptions in CONTRIBUTING.md.

### 6.2 R2: Anchor links are fragile

GitHub renders section headings to anchors with rules that vary between markdown processors. The plan verifies anchor links by browser test, not by automation. If a heading is later renamed, anchors silently break. Mitigation: the drift detector's rule 3 (cross-references exist) catches missing target *files* but not missing target *anchors*. Anchor checking is deferred.

### 6.3 R3: Drift detector is not enforced

`scripts/check-doc-consistency.mjs` is opt-in. A future PR can add `console:*` references without keeping CONTRIBUTING.md in sync. Mitigation: documented as a recommended pre-PR step in CONTRIBUTING.md. If drift becomes a recurring problem, promote to CI in a follow-up PR.

### 6.4 R4: Node 18.18 minimum may be too aggressive

Some developers on shared infra (corp CI runners) may be locked to Node 18.16 LTS-extended. Mitigation: choose `>=18.18` over `>=18.17` because Node 18.18 was released 2023-09-18, two years before this spec. If feedback indicates issues, lower to `>=18.0` and document the known parallel-test crash.

### 6.5 R5: Chromium minimum host requirements are Playwright-version-dependent

Pinning "macOS 11+ / Ubuntu 20.04+" in CONTRIBUTING.md may drift when Playwright is upgraded. Mitigation: the line names the Playwright version explicitly (`Playwright 1.59`); a future Playwright bump in `package.json` triggers a CONTRIBUTING.md edit. Tracked in the Compatibility Checklist (CONTRIBUTING.md last section, already present).

### 6.6 R6: AGENTS.md cross-link discoverability

The new AGENTS.md → README.md cross-link is inserted under the Quick Start. Coding agents reading AGENTS.md sequentially may stop at the first runnable code block (`bootstrap-mcp.sh`) and never reach the cross-link. Mitigation: position the cross-link *after* the Quick Start (so it does not pre-empt the agent action) but before "Feedback Workflow" (so it appears within the first screen of scroll). Verified in the plan with a `grep -n` of the resulting AGENTS.md.

### 6.7 R7: README badge row grows long

Adding a fourth badge makes the row wrap on narrow screens. Mitigation: visual; not a correctness risk. If wrap is undesirable, the JDK badge could be moved to a "Prerequisites" line in the body, but that is a styling decision deferred to whoever lands the change.

---

## 7. References

- `README.md` lines 3–6 (badges), 17 (passing bootstrap-mcp mention), 32–47 (Quick Start) — current state.
- `AGENTS.md` lines 11–14 (Prerequisites), 17–30 (Quick Start) — current state.
- `CONTRIBUTING.md` lines 56–82 (Focused Test Loops), 84–117 (Required Local Checks), 147–155 (optional console smoke) — current state.
- `CLAUDE.md` lines 9–22 (pointers; includes the only mention of `restart-console.sh`) — current state.
- `package.json` lines 6–14 (scripts), absence of `engines` — current state.
- `node_modules/playwright/package.json` engines field — pinning rationale for Node 18.18.
- `scripts/restart-console.sh` — flags `--with-app`, `--dry-run`, `--no-open`, `--port`, default port `9876`.
- `scripts/fixthis-console-dev.sh` — accepts optional package argument, defaults to `io.beyondwin.fixthis.sample`.
- `scripts/bootstrap-mcp.sh` — already canonical in `docs/guides/agents.md` flow; not touched.
- `docs/guides/agents.md` — canonical agent-setup file; remains untouched.
