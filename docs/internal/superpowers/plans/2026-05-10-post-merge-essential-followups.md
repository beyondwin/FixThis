# Post-Merge Essential Followups Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Address only the items from `specs/2026-05-10-annotation-pin-visibility-by-anchor-open-risks.md` that are (a) in-scope for this repo, (b) actionable now without external triggers, and (c) prevent silent regression or block trust in the prior two merges (`d27f674` annotation-pin-visibility-by-anchor, `1040988` mcp-handoff-followups).

**Scope decision (explicit exclusions):**
| Excluded | Reason |
|---|---|
| R2, R4, R5 | Wait on R1 outcome or user reports |
| H3 (Reconnecting spinner) | Bundle into next console UI work, not standalone |
| H4 (Playwright E2E) | Infrastructure decision, separate scope |
| H5, H6 | Orchestrator skill area (`kws-claude-multi-agent-executor`), not this repo |
| H7, H8 | Trigger-based; single-PR ROI low |

**What remains (3 phases):**
- **Phase 0: R1 + R3 manual smoke** — gates trust in `d27f674`. If this fails, suspend Phases A/B and open a R2/R3 plan.
- **Phase A: H1 HTML/CSS dependency comment** in `connection.js` (~5 lines)
- **Phase B: H2 module-array drift detector** in `scripts/build-console-assets.mjs` (~15 lines)

**Architecture:** No production code logic changes. Phase 0 is operational verification. Phase A adds inline comments documenting an external-element dependency. Phase B extends the existing explicit-array bundling with a "file on disk but not declared" assertion (the inverse direction is already implicitly enforced by `readFileSync` throwing). No backend, MCP-tool, HTTP-payload, Kotlin, or runtime JS behavior changes.

**Tech Stack:**
- Phase 0: Manual browser console + devtools assertions
- Phase A: Vanilla ES module JS (comment-only edit)
- Phase B: Node.js build script (added directory-scan + Set diff)
- Verification: Existing `FeedbackConsoleServerTest.kt` byte-equality assertion catches any unintended bundle drift from A or B

**Reference spec:** `docs/superpowers/specs/2026-05-10-post-merge-essential-followups-design.md`

---

## File Structure

### Phase 0 — Manual smoke gate (no files modified)
Operational verification only. Outcome documented in `CHANGELOG.md` (Phase C). On regression, this plan is suspended.

### Phase A — H1 dependency comment
**Modify:**
- `fixthis-mcp/src/main/console/connection.js`
- `fixthis-mcp/src/main/resources/console/app.js` — auto-regenerated bundle

### Phase B — H2 drift detector
**Modify:**
- `scripts/build-console-assets.mjs`

No bundle change expected (script behavior change only). Existing `FeedbackConsoleServerTest.kt` byte-equality assertion is the safety net.

### Phase C — Verification + docs
**Run:**
- `node scripts/build-console-assets.mjs`
- `./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test`

**Modify:**
- `CHANGELOG.md`
- `docs/superpowers/specs/2026-05-10-annotation-pin-visibility-by-anchor-open-risks.md` (mark R1/R3/H1/H2 as resolved with date + commit pointer)

---

# Phase 0 — Manual smoke gate (R1 + R3)

## Task 0.1: Run pin-visibility smoke against `d27f674`

This phase is operational, not code. It must succeed before A/B ship — otherwise we'd be polishing modules whose anchor-aware filter premise might be broken in production.

**Files:** none modified.

- [ ] **Step 1: Build CLI + MCP and launch console with source assets**
```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis console \
  --package io.beyondwin.fixthis.sample \
  --console-assets-dir "$PWD/fixthis-mcp/src/main/resources/console"
```
Open the printed URL in a browser and connect a debug build of the sample app on a device or emulator.

- [ ] **Step 2: Execute S1–S11 from the original plan**
Reference: `docs/superpowers/plans/2026-05-10-annotation-pin-visibility-by-anchor.md` Task 4.

S10 is the intentional limitation (area-only annotation does not survive screen change); confirm it behaves as documented, not as a bug.

- [ ] **Step 3: R3 nodeUid intersection check (run during S2 or S3, after navigating between two distinct screens)**

In browser devtools console:
```js
const a = new Set(visibleScreenNodeUids(screenAtIndex(0)));
const b = new Set(visibleScreenNodeUids(screenAtIndex(1)));
[...a].filter(x => b.has(x))
// expected: []  (empty array → R3 collision-free assumption holds)
```

If `screenAtIndex` is not in scope, substitute the helper used by the rendering layer to enumerate captured screens (see `rendering.js`).

- [ ] **Step 4: Outcome routing**
- ✅ All S1–S11 pass + intersection empty → record outcome line in CHANGELOG (Phase C), proceed to Phase A.
- ❌ Any S failure or non-empty intersection → STOP. Suspend this plan. Draft new R2 (hysteresis) or R3 (backend screen-id) plan based on observed signal. Phases A and B remain valid but ship in a separate session under a new plan to avoid bundling unrelated risk.

---

# Phase A — H1 HTML/CSS dependency comment

## Task A.1: Document `<pre>` + `pre-wrap` dependency in connection.js

**Files:**
- Modify: `fixthis-mcp/src/main/console/connection.js`
- Build artifact: `fixthis-mcp/src/main/resources/console/app.js` (regenerated)

**Context (verified):**
- `connection.js:147` writes a multi-line string into `connectionDetailsBody.textContent`
- `index.html:62` declares `<pre id="connectionDetailsBody">`
- `styles.css:180` rules `.connection-details pre { white-space: pre-wrap; }`

If either the `<pre>` element or the CSS rule is dropped in a future refactor, the `\n` collapses to inline whitespace silently — JS unit tests still pass because they assert string contents, not rendered layout.

- [ ] **Step 1: Add comment block immediately above the multi-line write at `connection.js:147`**
See spec for exact comment text and placement.

- [ ] **Step 2: Rebuild bundle**
```bash
node scripts/build-console-assets.mjs
```

- [ ] **Step 3: Run console test suite**
```bash
./gradlew :fixthis-mcp:test
```
The byte-equality test (`generatedBundleMatchesSource` or equivalent) will catch any drift between source modules and the regenerated `app.js`.

- [ ] **Step 4: Visual sanity**
With the local console still running from Phase 0, force a polling failure (e.g., temporarily kill the device-side process) and confirm the "Reconnecting feedback updates…" sub-line still renders on its own line.

---

# Phase B — H2 module-array drift detector

## Task B.1: Add directory-scan assertion to `scripts/build-console-assets.mjs`

**Files:**
- Modify: `scripts/build-console-assets.mjs`

**Context (verified):**
The script already declares an explicit ordered `sources` array (line 7–21). The risk H2 describes — silent ReferenceError on module reorder — is partially mitigated. What is missing is the *inverse* direction: a contributor who adds a new file under `fixthis-mcp/src/main/console/` and forgets to add it to the array gets no warning; the new file is silently absent from the bundle.

The "missing on disk" direction is implicitly handled — `readFileSync` throws if a declared module is absent.

- [ ] **Step 1: Add directory-scan + Set diff before output build**
Insert between the `sources` array declaration and the `output` `.map(...)` call. See spec for exact code.

- [ ] **Step 2: Self-test the assertion**
- Run `node scripts/build-console-assets.mjs` — must succeed (no change vs current state).
- Temporarily create `fixthis-mcp/src/main/console/__drift_test.js` with `// drift` and re-run — must throw with a clear message naming the file.
- Remove the test file. Re-run — must succeed.

- [ ] **Step 3: Verify byte-equality**
```bash
./gradlew :fixthis-mcp:test --tests "*FeedbackConsoleServerTest*"
```
Bundle output must be byte-identical to before B (the assertion runs before output assembly and does not affect the produced string).

---

# Phase C — Verification + docs

## Task C.1: Full test sweep
- [ ] Run all module test suites:
```bash
./gradlew :fixthis-compose-core:test \
          :fixthis-cli:test \
          :fixthis-mcp:test \
          :fixthis-compose-sidekick:testDebugUnitTest \
          :fixthis-gradle-plugin:test
```
- [ ] Confirm baseline holds (currently 631/0 per memory). Document delta in CHANGELOG if test count changes.

## Task C.2: CHANGELOG entry

Add under appropriate section of `CHANGELOG.md`:

```markdown
- console: documented `<pre id="connectionDetailsBody">` + `white-space: pre-wrap`
  dependency in `connection.js` for the Reconnecting sub-line. Defensive comment
  only, no behavior change. (H1, prevents silent visual regression on HTML/CSS
  refactor.)
- build: `scripts/build-console-assets.mjs` now asserts every `.js` file in the
  console source directory is declared in the ordered `sources` array. Catches
  the case where a contributor adds a module file but forgets to register it.
  (H2, prevents silent absence from the bundle.)
- verification: completed manual pin-visibility smoke (S1–S11 of merge d27f674)
  with R3 nodeUid intersection check on <date>. (R1 + R3.)
```

## Task C.3: Mark items resolved in open-risks doc

Edit `docs/superpowers/specs/2026-05-10-annotation-pin-visibility-by-anchor-open-risks.md`:
- Under R1: append `**Resolved:** <date>, smoke commit <hash>.`
- Under R3: append `**Verified empty:** <date> via R1 step 3.`
- Under H1: append `**Resolved:** <date>, commit <hash>.`
- Under H2: append `**Resolved:** <date>, commit <hash>.`

Update the "통합 우선순위 (R + H)" section at file end accordingly.

---

# Out of Scope (explicit, not addressed by this plan)

| Item | Re-trigger condition |
|---|---|
| R2 hysteresis | R1 surfaces frame-flicker, or user reports same-screen pin blink |
| R4 area UX | User reports component+area mixed confusion |
| R5 plan/spec stale | Reader confusion reported (option A currently in effect) |
| H3 retry spinner | Next console UI work bundle, or telemetry signal |
| H4 Playwright | Infra adoption decision (separate session) |
| H5 local.properties prime | Next orchestrator run blocks on SDK path |
| H6 Reviewer prompt template | Next orchestrator run shows worktree-path drift |
| H7 visibility narrowing | Backend metric shows visibility-induced load |
| H8 test precision | Adjacent main.js refactor PR |

Re-evaluate the open-risks doc after this plan ships.
