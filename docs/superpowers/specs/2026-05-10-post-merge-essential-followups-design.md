# Post-Merge Essential Followups — Design

**Date:** 2026-05-10
**Status:** Design for plan `plans/2026-05-10-post-merge-essential-followups.md`
**Companion to:** `specs/2026-05-10-annotation-pin-visibility-by-anchor-open-risks.md`

This spec details the three items the followup plan implements: R1+R3 manual smoke (Phase 0), H1 dependency comment (Phase A), H2 drift detector (Phase B). Items deliberately excluded by the plan are not detailed here — see the open-risks doc for their rationale.

---

## 1. Phase 0 — R1 + R3 manual smoke

### 1.1 Why this is a phase, not a checklist item

The annotation-pin-visibility-by-anchor merge (`d27f674`) shipped on automated test passing alone. The merge's value is browser-console UX, which automated tests cannot directly measure. Three of the four remaining R items (R2/R3/R4) explicitly depend on R1's outcome to know whether they are dormant assumptions or active bugs.

If R1 surfaces a regression, polishing H1/H2 in the same PR would couple unrelated risks. So Phase 0 is a hard gate: pass before A/B, or split into a new plan.

### 1.2 R3 verification mechanics

R3's hypothesis is "no two distinct logical screens share a `compose:rootIndex:treeKind:nodeId` uid." It is currently only argued from generality of the NavHost pattern. Single-NavHost apps (the common case) reuse `rootIndex=0` across screens, so the only thing keeping the assumption true is composition dispose→recreate reassigning `nodeId`. That is plausible but not guaranteed.

A single intersection check during the smoke run gives one quantitative data point per session for the cost of 3 lines in devtools:

```js
const a = new Set(visibleScreenNodeUids(screenAtIndex(0)));
const b = new Set(visibleScreenNodeUids(screenAtIndex(1)));
[...a].filter(x => b.has(x))
```

Empty result → R3 holds for this app at this moment. Non-empty result → R3 is broken in production and demands a backend-level screen identifier (the F2 follow-up named in the original spec).

### 1.3 What "outcome" looks like

The plan's Phase C records one of:
- "S1–S11 pass, R3 intersection empty (date, app build hash)"
- "Regression in S<n> with details, R3 intersection result, follow-up plan filed"

No code change either way — Phase 0 is recorded in CHANGELOG and in the open-risks doc resolution markers.

---

## 2. Phase A — H1 HTML/CSS dependency comment

### 2.1 Verified state of dependencies

Direct read of source files confirms:

| Site | What it does |
|---|---|
| `fixthis-mcp/src/main/console/connection.js:147` | Writes `${baseDetails}\nReconnecting feedback updates…` into `connectionDetailsBody.textContent` |
| `fixthis-mcp/src/main/resources/console/index.html:62` | `<pre id="connectionDetailsBody">…</pre>` |
| `fixthis-mcp/src/main/resources/console/styles.css:180` | `.connection-details pre { white-space: pre-wrap; … }` |

The `\n` becomes a visible line break only because the element is `<pre>` *and* the CSS preserves wrap. Drop either and the sub-line collapses inline. JS unit tests assert string contents, so the regression is silent.

### 2.2 Exact edit

Replace lines 142–152 of `fixthis-mcp/src/main/console/connection.js` with:

```js
if (state.connection.sessionsPollingPaused) {
  // Surface a sub-line indicating sessions polling is paused after consecutive failures.
  // Uses connectionDetailsBody as the secondary message channel so it does NOT
  // replace the bridge/device headline above.
  //
  // Layout dependency (do NOT remove without updating both files together):
  //   - index.html: connectionDetailsBody must be <pre>
  //   - styles.css: .connection-details pre { white-space: pre-wrap; }
  // The \n below renders as a visible line break only under those two conditions.
  // If either changes, the sub-line collapses inline (silent visual regression
  // — JS tests still pass because they assert string contents, not layout).
  const baseDetails = connectionDetailsText(status);
  connectionDetailsBody.textContent = baseDetails
    ? baseDetails + '\nReconnecting feedback updates…'
    : 'Reconnecting feedback updates…';
} else {
  connectionDetailsBody.textContent = connectionDetailsText(status);
}
```

The only change is the inserted comment block. Behavior is unchanged.

### 2.3 Why a comment, not a test assertion

Open-risks H1 listed two options: comment (A) or HTML/CSS test assertion (B). Option B was rejected because:
- It would assert a specific element tag and class name, locking a styling decision into a test file far from the source. Future restructures would need to touch both.
- The byte-equality test already catches removal of the comment itself if a contributor edits `connection.js` and forgets to rebuild — so the comment is "load-bearing" to a small degree.
- A comment colocates the dependency with the line that depends on it. A future reader removing the `<pre>` will grep for `connectionDetailsBody` and find the comment in their first hit.

### 2.4 Verification

After the edit, the byte-equality assertion in `FeedbackConsoleServerTest.kt` will fail until `node scripts/build-console-assets.mjs` regenerates `app.js`. This is the desired safety net — there is no other automatic check that the comment block survives.

---

## 3. Phase B — H2 module-array drift detector

### 3.1 Verified state of `scripts/build-console-assets.mjs`

Current state (line 7–21):

```js
const sources = [
  'state.js',
  'api.js',
  'connection.js',
  'availability.js',
  'devices.js',
  'preview.js',
  'annotations.js',
  'history.js',
  'prompt.js',
  'rendering.js',
  'sessions-polling.js',
  'shortcuts.js',
  'main.js',
];
```

Two facts mitigate H2 partially:
- Order is explicit (not directory-iteration order), so reorder requires an intentional edit visible in diff.
- `readFileSync` throws on the missing-file direction — declaring `'foo.js'` without that file on disk fails build immediately.

What's missing is the **inverse direction**: a contributor adds `fixthis-mcp/src/main/console/new-feature.js`, forgets to register it in `sources`, and the new module is silently absent from the bundle. No test fails (the bundle is internally consistent), but the new feature does nothing in the browser.

### 3.2 Exact edit

Insert the following block between line 21 (close of `sources` array) and line 23 (start of `output` build), in `scripts/build-console-assets.mjs`:

```js
const sourceDir = resolve(root, 'fixthis-mcp/src/main/console');
const declared = new Set(sources);
const onDisk = readdirSync(sourceDir)
  .filter((name) => name.endsWith('.js'));

const undeclared = onDisk.filter((name) => !declared.has(name));
if (undeclared.length > 0) {
  console.error(
    `build-console-assets: ${undeclared.length} .js file(s) on disk are not declared in the sources array:\n` +
    undeclared.map((n) => `  - ${n}`).join('\n') +
    `\nAdd them explicitly to the sources array (order matters — later modules can reference earlier ones).`
  );
  process.exit(1);
}
```

Add `readdirSync` to the existing import on line 2:

```js
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
```

### 3.3 Why exit(1) instead of throw

The script already uses `process.exit(1)` for the `--check` failure case (line 35, 41). Following that convention keeps the error surface consistent for callers (CI, local dev, gradle integration if any).

### 3.4 Why not assert ordering invariants

H2 named two failure modes: reorder breaks runtime references, AND missing files. Reordering already requires an intentional human edit (the array is explicit), so it shows up in code review. Adding cross-module reference detection (parsing JS to extract function/global usage) would be high-effort and rarely-triggered. The drift detector covers the case that actually has zero existing safety net — adding a new file.

### 3.5 Verification

The change is to build-script behavior, not bundle output. After the edit:
- `node scripts/build-console-assets.mjs` must succeed with no output change (current 13 declared files = current 13 on-disk `.js` files).
- `FeedbackConsoleServerTest.kt` byte-equality assertion must still pass (the assertion runs before output assembly and does not affect the produced string).

Self-test (described in plan Task B.1 Step 2):
```bash
echo "// drift" > fixthis-mcp/src/main/console/__drift_test.js
node scripts/build-console-assets.mjs
# expected: exit 1 with message naming __drift_test.js
rm fixthis-mcp/src/main/console/__drift_test.js
node scripts/build-console-assets.mjs
# expected: exit 0, app.js byte-identical to pre-self-test
```

---

## 4. Testing strategy summary

| Phase | Automated coverage | Manual coverage |
|---|---|---|
| 0 (R1+R3) | None — that is precisely why this phase exists | Required (S1–S11 + intersection check) |
| A (H1)    | Bundle byte-equality test catches forgotten rebuild | Optional visual sanity during Phase 0 |
| B (H2)    | Self-test (declared in plan); bundle byte-equality holds | None needed |

No new test files are added by this plan. The existing `FeedbackConsoleServerTest.kt` covers what needs covering for A/B; nothing in the codebase can cover Phase 0.

---

## 5. Out of scope (mirrors the plan)

This spec details only Phase 0/A/B. The following items from the open-risks doc are explicitly NOT addressed and require separate plans on their respective triggers: **R2, R4, R5, H3, H4, H5, H6, H7, H8.** See `specs/2026-05-10-annotation-pin-visibility-by-anchor-open-risks.md` for each item's trigger condition and proposed direction.

H5 and H6 belong to the `kws-claude-multi-agent-executor` skill in the user's `~/.claude/skills/` tree, not this repo. Even if triggered, fixes for those go into the skill source, not into FixThis.

---

## 6. Risk register for *this* plan (small)

| Risk | Likelihood | Mitigation |
|---|---|---|
| Phase 0 surfaces unexpected regression and consumes the session before A/B | Medium | Plan explicitly suspends A/B in that case; A/B remain valid for a future session |
| Comment in connection.js drifts from reality on later refactor | Low | Comment is co-located with the line that depends on it; first grep hit |
| Drift detector false-positive on intentionally-excluded `.js` (e.g., test fixtures placed in console dir) | Very low | Convention: console source dir is for bundle modules only. If exception ever needed, allowlist via second `Set` |
| Manual smoke (Phase 0) recorded as passed but only S1–S5 actually exercised | Medium (human factors) | Plan Task 0.1 Step 2 names the specific source plan to follow; Phase C CHANGELOG entry asks for the date and a yes/no per scenario range |
