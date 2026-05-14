# Console Harness Automation — Design

**Date:** 2026-05-14
**Scope:** Promote the four `scripts/console-*` harnesses from manual exploration aids to fully automated checks, and extend them with three new scenarios (network outage, slow handoff, multi-tab) that today are never exercised.
**Status:** Design draft. Implementation plan: [`docs/superpowers/plans/2026-05-14-console-harness-automation-implementation.md`](../plans/2026-05-14-console-harness-automation-implementation.md).

---

## 1. Problem

The FixThis feedback console ships four browser-facing harnesses under `scripts/`:

| Harness | Purpose | Today |
| --- | --- | --- |
| `console-availability-test.mjs` | Unit-level behavior of availability widgets via `node --test` | **Automated.** Wired into `console-js` CI job. |
| `console-blocked-harness.mjs` | Drive the "device blocked" UX with Playwright MCP from outside, prints URL and parks. | **Manual.** No assertions, never run in CI. |
| `console-responsive-stress.mjs` | Iterate viewports with Playwright, capture screenshots, dump to `output/playwright/`. | **Manual.** Has Playwright dependency, never run in CI. |
| `console-browser-smoke.mjs` | End-to-end console smoke against a fake bridge: device list, screen pick, annotate, handoff, history. | **Manual.** Runs locally on demand; not part of `npm test` or CI. |

The user-facing failure modes most likely to slip through this gap:

1. **Network outage during a handoff.** The console must survive a bridge HTTP outage that arrives after the user selected a screen but before they sent the prompt. The current smoke never disconnects the fake bridge mid-session.
2. **Slow handoff (P95 backend response > 5 s).** The console must keep the UI responsive (no UI freeze, the "Sending" affordance must stay informative). The current smoke uses synchronous responses that resolve in microseconds.
3. **Multi-tab collisions.** Two browser tabs sharing a `consoleToken` must not corrupt each other's draft workspace or session selection. The current smoke opens exactly one page.

These three scenarios are not exotic — they were each cited in post-merge stabilization notes (`2026-05-10-handoff-lifecycle-ux.md`, `2026-05-13-responsive-error-agent-feedback-ux-detailed-spec.md`, `2026-05-12-post-v0.1.0-stabilization-implementation.md`) — but no automated check pins them down.

**A note on "4 harnesses."** The brief lists four manual harnesses but only three filenames. `console-availability-test.mjs` already lives in `node --test` (the existing automated lane). This design treats the three Playwright-touching harnesses as the manual set to automate, and notes the availability test as the existing pattern to extend.

---

## 2. Goals & Non-Goals

### Goals

- **G1:** Run the four currently-implementable scenarios (`happy-path`, `network-outage`, `slow-handoff`, `multi-tab`) on a scheduled (nightly) CI job that fails the build on regression. The remaining scenarios listed in §3.2 are Phase 2 followups.
- **G2:** Add three new scenarios layered on top of the existing fake-bridge fixture: `network-outage`, `slow-handoff`, `multi-tab`. (`happy-path` is the existing smoke baseline; the five Phase 2 scenarios — `blocked-welcome`, `blocked-permission-prompt`, `responsive-stress`, `smoke-handoff`, `smoke-history-drawer` — remain out of scope for Phase 1.)
- **G3:** Define a scenario matrix that crosses (scenario × viewport × handoff state) and yields concrete assertions per cell.
- **G4:** Keep PR latency unchanged — the nightly Playwright job must not gate PR merges.
- **G5:** Surface screenshots and traces from failing nightly runs as GitHub Actions artifacts.

### Non-Goals

- **NG1:** Replace the existing `console-js` `node --test` lane. Unit-level coverage stays in `node:test`.
- **NG2:** Run Playwright on every PR. The wall-clock cost (1.5 GB browser install, 6–8 minute test run) outweighs the per-PR benefit for now.
- **NG3:** Hit a real `:fixthis-mcp` server or a real Android emulator. The fake bridge fixture is the contract.
- **NG4:** Multi-browser parity (Firefox, WebKit). Chromium-only is sufficient for the first iteration; bridge-protocol-version checks already pin the contract.
- **NG5:** Mobile Safari emulation. Viewports are emulated via Chromium device descriptors.

---

## 3. Design

### 3.1 Architecture overview

```
┌──────────────────────┐      ┌────────────────────────────┐
│ scripts/console-     │      │ scripts/console-fixture/   │
│   browser-smoke.mjs  │──────│   fakeBridgeServer.mjs     │
│   blocked-harness.mjs│      │   scenarios/               │
│   responsive-stress  │      │     networkOutage.mjs      │
│   harness.mjs (new)  │      │     slowHandoff.mjs        │
└──────────┬───────────┘      │     multiTab.mjs           │
           │                  └────────────────────────────┘
           │
           ▼
   Playwright (Chromium)
           │
           ▼
   assertions + screenshots + traces
           │
           ▼
   GitHub Actions artifacts
```

- **`scripts/console-fixture/fakeBridgeServer.mjs`** is extracted from the inline `http.createServer` blocks duplicated across the three existing harnesses. It exposes `createFixture({ scenario, scenarioOptions })` and returns `{ url, close, applyScenario }`.
- **`scripts/console-fixture/scenarios/*.mjs`** each export a function `(fixture, options) => void` that mutates the fixture's request handlers and timing. The fixture is the only shared state.
- **`scripts/console-harness.mjs`** is the entry point CI calls. It iterates `(scenario × viewport)` from a matrix table and writes a single JUnit-style XML output to `output/playwright/results.xml`.

### 3.2 Scenario matrix

Each cell is a single Playwright `test.step`. Cell selection is driven by `process.env.FIXTHIS_HARNESS_MATRIX` (CSV of scenario keys); the default in nightly CI is `all`.

**Phase 1 (active in this design — 4 scenarios × 4 viewports = 16 cells minus the one `multi-tab` mobile cell skipped below = 15 active cells):**

| Scenario \ Viewport | `mobile-390` | `breakpoint-900` | `compact-1024` | `desktop-1280` |
| --- | --- | --- | --- | --- |
| `happy-path` | smoke | smoke | smoke | smoke |
| **`network-outage` (NEW)** | required | required | required | required |
| **`slow-handoff` (NEW)** | required | required | required | required |
| **`multi-tab` (NEW)** | skipped | required | required | required |

`required` cells must produce a passing assertion. `smoke` cells assert the page loads and emits no console errors. `skipped` cells are not exercised in Phase 1.

**Phase 2 followups (out of scope for this design — owners TBD):**

| Phase 2 scenario | Notes |
| --- | --- |
| `blocked-welcome` | Drives the WELCOME blocked state; needs harness wiring for the blocked sub-states. |
| `blocked-permission-prompt` | Permission-prompt blocked state; depends on `blocked-welcome` plumbing. |
| `responsive-stress` | Screenshot-diff sweep at the 4 viewports against checked-in baselines under `scripts/console-fixture/baseline-screenshots/<viewport>/<scenario>.png` with `maxDiffPixelRatio: 0.005`. Baseline generation procedure deferred to Phase 2. |
| `smoke-handoff` | Existing manual smoke step; promote after Phase 1 stabilizes. |
| `smoke-history-drawer` | History drawer assertions; covered today by `console-availability-test.mjs` at unit level. |

These will be picked up in a follow-up plan once Phase 1 has run cleanly on nightly cadence for at least two weeks.

#### 3.2.1 Scenario: `network-outage`

- **Trigger:** After the page loads and the user has selected a screen, the fixture begins returning `HTTP 503` from `/api/handoff` and `ECONNREFUSED` from `/api/session/*` polls.
- **Assertions:**
  - The "Send to agent" button enters a disabled state with the copy `Reconnecting…` within 2 seconds.
  - The pending annotation banner shows the recovery hint (uses existing `pendingItemRecovery` logic).
  - When the fixture is restored after 3 seconds, the next poll succeeds and the banner clears.
  - No uncaught Promise rejection appears in the browser console.

#### 3.2.2 Scenario: `slow-handoff`

- **Trigger:** `/api/handoff` accepts the POST after a 6-second artificial delay. Delay is injected through the fixture's config-injection API — `runScenario({ scenario: 'slow-handoff', overrides: { handoffDelayMs: 6000 } })` — rather than by monkey-patching the static scenarios map. Unit tests and ad-hoc runs may pass a shorter `handoffDelayMs` via the same override hook.
- **Assertions:**
  - The "Sending…" affordance appears within 200 ms.
  - The page does not exceed 100 ms of jank as measured by `performance.getEntriesByType('longtask')`.
  - On resolution, the history drawer shows the new entry with the "Sent" badge.
  - The send button re-enables for the next annotation.

#### 3.2.3 Scenario: `multi-tab`

- **Trigger:** Playwright opens two `Page`s in a single `BrowserContext` (via `context.newPage()` twice) pointing at the same fixture URL with the same `consoleToken`. Two pages of the same context are required because the DOM `storage` event only fires in *other* pages of the same origin — never in the page that performed the write. The assertions below must read the cross-tab signal on the *receiver* page (Tab B when Tab A writes, and vice versa).
- **Assertions:**
  - Tab A creates a draft annotation; Tab B's draft-workspace list shows the same draft after the next poll.
  - Tab A sends the draft; Tab B's status reflects "sent" within one poll cycle.
  - Tab A and Tab B's `localStorage` snapshots agree on the active session id.
  - No "draft-workspace conflict" warning appears in either tab's console (the invariant test under `scripts/draftWorkflowInvariant-test.mjs` already covers the logic; this scenario asserts the rendered UX).

### 3.3 Fixture extraction

Today each of the three Playwright-touching harnesses has its own copy of:

```javascript
const indexHtml = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/index.html'), 'utf8');
const stylesCss = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/styles.css'), 'utf8');
const appJs = readFileSync(resolve(root, 'fixthis-mcp/src/main/resources/console/app.js'), 'utf8');
const pageHtml = indexHtml
  .replace('<!-- FIXTHIS_STYLES -->', `<style>${stylesCss}</style>`)
  .replace('<!-- FIXTHIS_SCRIPT -->', `<script>${appJs}</script>`);
```

The extracted module `scripts/console-fixture/fakeBridgeServer.mjs` will export:

```javascript
export async function startFakeBridge(options) {
  // options.scenario: keyof SCENARIOS
  // options.now: number (epoch ms anchor)
  // options.devices: array
  // returns { url, close, applyScenario, getRequestLog }
}
```

Per-scenario behavior lives in `scripts/console-fixture/scenarios/*.mjs`. The three existing harness scripts become thin wrappers that import `startFakeBridge` and the scenario module they need.

### 3.4 CI integration

A new workflow file `.github/workflows/console-harness-nightly.yml`:

- **Trigger:** Nightly-only; no PR gating. `schedule: cron: '17 9 * * *'` (09:17 UTC daily, off-peak for the maintainer in KST/PST) plus `workflow_dispatch` for manual runs. The PR `paths`-filtered trigger is intentionally omitted so that G4 (PR latency unchanged) holds: PRs that touch console assets are validated by the existing `console-js` lane and any later promotion to PR gating must clear an explicit re-design.
- **Job:** `console-harness`, single Chromium job.
- **Cache:** `actions/cache@v4` keyed on `package-lock.json` and a `playwright --version` echo so the 300 MB browser install is reused across nightly runs.
- **Steps:**
  1. `actions/checkout@v4`
  2. `actions/setup-node@v4` with Node 22.
  3. `npm ci`
  4. `npx playwright install --with-deps chromium`
  5. `node scripts/console-harness.mjs --matrix all --output output/playwright`
  6. `actions/upload-artifact@v4` for `output/playwright/**` on failure.
- **Outcome:** Failure is reported via the GitHub status check, but the workflow is **not** marked required for PR merges. It is required for `main`'s nightly green badge.

### 3.5 Reporting

- JUnit XML (`output/playwright/results.xml`) consumed by `dorny/test-reporter@v1` for inline annotations.
- Screenshots on failure: `output/playwright/screenshots/<scenario>__<viewport>.png`.
- Playwright traces on failure: `output/playwright/traces/<scenario>__<viewport>.zip`.
- Console logs on failure: `output/playwright/console/<scenario>__<viewport>.log`.

All four artifacts upload to a single `console-harness-failure-<run-id>` artifact bundle when the harness exits non-zero.

### 3.6 Local developer flow

```bash
# Run the full matrix:
npm run console:harness

# Run a single scenario across all viewports:
FIXTHIS_HARNESS_MATRIX=network-outage npm run console:harness

# Run a single scenario at one viewport (the legacy entry points still work):
node scripts/console-browser-smoke.mjs --scenario slow-handoff --viewport mobile-390
```

The legacy entry points stay functional so muscle memory is preserved.

---

## 4. CI Integration Details

### 4.1 Workflow file layout

```yaml
name: Console harness (nightly)

on:
  schedule:
    - cron: '17 9 * * *'
  workflow_dispatch: {}
  # No pull_request trigger — G4 forbids PR gating.

concurrency:
  group: console-harness-${{ github.ref }}
  cancel-in-progress: true

jobs:
  console-harness:
    runs-on: ubuntu-latest
    timeout-minutes: 25
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: 'npm'
      - run: npm ci
      - id: pw-version
        run: |
          PW_VERSION=$(node -e "const p=require('./package-lock.json');\
            const pkg=p.packages && (p.packages['node_modules/playwright']||p.packages['node_modules/playwright-core']);\
            console.log(pkg.version)")
          echo "version=$PW_VERSION" >> "$GITHUB_OUTPUT"
      - name: Cache Playwright browsers
        uses: actions/cache@v4
        with:
          path: ~/.cache/ms-playwright
          key: playwright-${{ runner.os }}-${{ steps.pw-version.outputs.version }}-${{ hashFiles('package-lock.json') }}
      - run: npx playwright install --with-deps chromium
      - name: Bridge protocol version sync
        run: ./gradlew :fixthis-mcp:test --tests "*BridgeProtocolVersionSyncTest"
      - run: node scripts/console-harness.mjs --matrix all --output output/playwright
      - if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: console-harness-failure-${{ github.run_id }}
          path: output/playwright/**
          retention-days: 14
```

### 4.2 Coexistence with existing `console-js` job

The new workflow is independent. `console-js` keeps its current `node --test` scope. The new nightly workflow does not block merges, satisfying G4.

### 4.3 Branch protection

The aggregate `baseline` job in `.github/workflows/ci.yml` is unchanged. `console-harness` is informational at first. After two clean weeks of nightly runs, we will reconsider promoting it to required for `main` pushes only (still not for PRs).

---

## 5. Test Strategy

### 5.1 Levels

| Level | Today | After this design |
| --- | --- | --- |
| Unit | `node --test scripts/*-test.mjs` (covers availability, undo/redo, draft workspace, etc.) | Unchanged. |
| Component | None for console; existing `EventLogWriterTest` etc. cover Kotlin side. | The harness extension covers the JS-side "component" gap by exercising fake bridge responses against the real bundled `app.js`. |
| End-to-end smoke | `scripts/console-browser-smoke.mjs` (manual). | Promoted to nightly CI with the three new scenarios. |
| Visual regression | `scripts/console-responsive-stress.mjs` dumps screenshots locally. | Promoted; baselines checked in; `maxDiffPixelRatio: 0.005`. |

### 5.2 Pre-commit checks

The fixture extraction adds two new `node --test` files:

- `scripts/console-fixture/fakeBridgeServer-test.mjs` — asserts that `startFakeBridge` honors `applyScenario`, that the request log captures POST bodies, and that closing the fixture frees the port.
- `scripts/console-fixture/scenarios-test.mjs` — drives each scenario module against a stub fixture and asserts the request-handler diff is what the scenario advertises.

These both run inside the existing `console-js` CI job, since they are fast (< 1 s combined) and infrastructure-level.

### 5.3 Local TDD loop

```bash
# Iterate on a single scenario:
node --test scripts/console-fixture/scenarios-test.mjs

# Iterate on the harness driver:
node scripts/console-harness.mjs --matrix network-outage --viewport mobile-390 --headed
```

### 5.4 Determinism

- Time anchored to `1_779_000_000_000` (same as today's smoke). Slow-handoff uses `Promise(setTimeout)` not `Date.now()` shifts, so the time anchor stays stable.
- Random data is seeded by deterministic counters (`item-1`, `item-2`, …) — there is no `Math.random()` in the fixture.
- Network outage is implemented by swapping the request handler, not by delaying responses, so retries land on a stable boundary.

### 5.5 Flake control

- Each Playwright assertion uses `expect.poll` or `locator.waitFor` with a 5-second timeout. No fixed `setTimeout` waits in test code.
- Three consecutive nightly runs must pass before the maintainer dashboard marks the harness "green for promotion to required."
- Failed runs auto-retry once. A retry recovery is logged in the artifact bundle but does not fail the run; a double-failure does.

---

## 6. Open Risks

### 6.1 CI cost

- **Playwright Chromium install:** ~300 MB, ~30 s warm-cache, ~90 s cold-cache. Mitigated by `actions/cache@v4`.
- **Wall-clock per nightly run:** budgeted at 25 minutes including install. Measured per-cell expectations for Phase 1 (4 scenarios × 4 viewports, minus the one `multi-tab/mobile-390` skip = 15 cells):

  | Scenario | Per-cell wall time | Viewports | Subtotal |
  | --- | --- | --- | --- |
  | `happy-path` | ~25 s | 4 | ~100 s |
  | `network-outage` | ~35 s (8 s outage window + asserts) | 4 | ~140 s |
  | `slow-handoff` | ~45 s (6 s artificial delay dominates) | 4 | ~180 s |
  | `multi-tab` | ~40 s (two pages + cross-tab poll) | 3 | ~120 s |
  | **Cell subtotal** | | | **~540 s (9 min)** |
  | Playwright install (cold) | | | ~90 s |
  | npm ci + checkout | | | ~60 s |
  | 20 % retry buffer on cells | | | ~110 s |
  | **Total** | | | **~13.3 min** |

  This fits within the 25-minute budget with ~11.7 minutes of headroom. If retries push us over budget, drop the viewport matrix to two (`mobile-390` + `desktop-1280`) which halves the cell subtotal.
- **Monthly cost on a free runner:** 30 runs × 25 min = 750 minutes; well under the 2000-minute monthly quota. PR-triggered runs are scoped via `paths` filter so they only fire when console assets change.

### 6.2 Flakiness

- **Risk:** Visual diff baselines drift under font or rendering library updates.
  - **Mitigation:** Bake the Playwright version (which pins the Chromium build) into the `actions/cache` key — e.g. `playwright-${{ runner.os }}-${{ steps.pw-version.outputs.version }}-${{ hashFiles('package-lock.json') }}`. Refresh baselines in a dedicated PR when fonts or Chromium change.
- **Risk:** `slow-handoff` test's 6-second artificial delay races with the 5-second `waitFor` timeout.
  - **Mitigation:** Raise `slow-handoff` waitFor to 10 seconds; assert that the Sending affordance appears within 200 ms (a much tighter sub-bound).
- **Risk:** `multi-tab` test depends on `localStorage` cross-tab `storage` events firing.
  - **Mitigation:** Use two `BrowserContext`s in the same process so they share `localStorage` via the same `userDataDir`-equivalent; alternative is a polling loop that does not assume `storage` events.

### 6.3 False sense of security

- **Risk:** The fixture and the real `:fixthis-mcp` bridge can drift apart, masking real protocol breaks.
  - **Mitigation:** The nightly workflow runs `./gradlew :fixthis-mcp:test --tests "*BridgeProtocolVersionSyncTest"` *before* the Playwright matrix. That JVM test already enforces equality across all four mirrored sites (`BridgeProtocol.kt`, console `MinimumSupportedProtocolVersion`, `BridgeClient.kt`, `ServerVersionRoutes.kt`); if it fails the harness aborts early, before fixture startup ever wires up a stale console bundle. A separate JS-side loader is therefore unnecessary at Phase 1; revisit only if we eventually run the harness in isolation from the Gradle toolchain.

### 6.4 Maintenance burden

- Three new scenario files plus a shared fixture plus a new workflow plus baseline screenshots.
- Mitigated by: (a) the fixture extraction removes duplication that already exists in three harnesses; (b) baseline screenshots only need a refresh when the underlying console UI changes deliberately; (c) the new workflow is < 40 lines.

### 6.5 Coverage gaps that remain after this design

- **Real Android device under load.** Out of scope — covered by manual connected-test loops.
- **Bridge protocol mismatch UX.** Covered by Kotlin-side tests and `console-availability-test.mjs`.
- **Long-session memory growth.** Not exercised here; would need a 24-hour test, which the nightly cadence cannot fit.

---

## 7. References

### Internal documents

- Implementation plan: [`docs/superpowers/plans/2026-05-14-console-harness-automation-implementation.md`](../plans/2026-05-14-console-harness-automation-implementation.md)
- Sibling merge gates: [`2026-05-14-test-speed-merge-gate-design.md`](2026-05-14-test-speed-merge-gate-design.md), [`2026-05-14-ci-console-precheck-merge-gate-design.md`](2026-05-14-ci-console-precheck-merge-gate-design.md), [`2026-05-14-build-optimization-merge-gate-design.md`](2026-05-14-build-optimization-merge-gate-design.md)
- Feedback console contract: `docs/reference/feedback-console-contract.md`
- Bridge protocol: `docs/reference/bridge-protocol.md`

### Related plans & specs

- `docs/superpowers/plans/2026-05-10-handoff-lifecycle-ux.md` — motivates the `network-outage` and `slow-handoff` scenarios.
- `docs/superpowers/specs/2026-05-13-responsive-error-agent-feedback-ux-detailed-spec.md` — visual-regression expectations for error states.
- `docs/superpowers/plans/2026-05-12-post-v0.1.0-stabilization-implementation.md` — references multi-tab UX as a known gap.
- `docs/superpowers/plans/2026-05-14-draft-workspace-state-machine.md` — invariant model that `multi-tab` exercises.

### External

- Playwright docs: https://playwright.dev/docs/intro
- GitHub Actions schedules: https://docs.github.com/en/actions/using-workflows/events-that-trigger-workflows#schedule
- `actions/cache@v4`: https://github.com/actions/cache
- `dorny/test-reporter@v1`: https://github.com/dorny/test-reporter

---

## Appendix A — Scenario coverage justification

Each new scenario is justified against a specific historical incident or stabilization note:

| Scenario | Historical reference | Failure mode prevented |
| --- | --- | --- |
| `network-outage` | `docs/superpowers/plans/2026-05-10-handoff-lifecycle-ux.md` §3 | Console freezes when bridge drops mid-session; pending banner does not recover |
| `slow-handoff` | `docs/superpowers/specs/2026-05-13-responsive-error-agent-feedback-ux-detailed-spec.md` §4.2 | Sending affordance disappears for 6 s, user sends twice |
| `multi-tab` | `docs/superpowers/plans/2026-05-12-post-v0.1.0-stabilization-implementation.md` Task 7 | Draft workspace state diverges across tabs, last writer silently wins |

## Appendix B — File inventory

**New files:**
- `scripts/console-fixture/fakeBridgeServer.mjs`
- `scripts/console-fixture/fakeBridgeServer-test.mjs`
- `scripts/console-fixture/scenarios/networkOutage.mjs`
- `scripts/console-fixture/scenarios/slowHandoff.mjs`
- `scripts/console-fixture/scenarios/multiTab.mjs`
- `scripts/console-fixture/scenarios-test.mjs`
- `scripts/console-fixture/baseline-screenshots/<viewport>/<scenario>.png` — **Phase 2.** Generated via `FIXTHIS_HARNESS_UPDATE_BASELINES=1 node scripts/console-harness.mjs --update-baselines`; the env-var gate prevents accidental CI overwrites. No baseline PNGs are committed in Phase 1; the `responsive-stress` scenario that consumes them is deferred.
- `scripts/console-harness.mjs`
- `.github/workflows/console-harness-nightly.yml`

**Modified files:**
- `scripts/console-browser-smoke.mjs` — delegates to `startFakeBridge`.
- `scripts/console-blocked-harness.mjs` — delegates to `startFakeBridge`.
- `scripts/console-responsive-stress.mjs` — delegates to `startFakeBridge`.
- `package.json` — add `console:harness` script and a `playwright` dependency bump if needed.
- `CONTRIBUTING.md` — document the nightly workflow and the local harness command.

## Appendix C — Local override knobs

| Env var | Effect |
| --- | --- |
| `FIXTHIS_HARNESS_MATRIX` | CSV of scenario keys; default `all`. |
| `FIXTHIS_HARNESS_VIEWPORTS` | CSV of viewport keys; default `all`. |
| `FIXTHIS_HARNESS_OUTPUT` | Output directory; default `output/playwright`. |
| `FIXTHIS_HARNESS_HEADED` | `1` to launch headed Chromium for debugging. |
| `FIXTHIS_HARNESS_TRACE` | `1` to always record traces, not just on failure. |

## Appendix D — Out-of-scope alternatives considered

1. **Cypress instead of Playwright.** Playwright is already a dependency; switching adds dependency churn for no incremental benefit.
2. **Run on every PR.** Discarded due to PR latency cost (G4). Reconsider after three months of stable nightly results.
3. **Headless Firefox/WebKit.** Discarded for the first iteration; revisit when a Firefox-specific bug is reported.
4. **Storybook for the console.** Console JS is currently bundled as one `app.js`; introducing Storybook would be a larger refactor than this design.
5. **Hosted CI provider (CircleCI, Buildkite).** GitHub Actions is already the source of truth; doubling providers raises maintenance cost.

## Appendix E — Backout

If the nightly job becomes a maintenance burden or repeatedly produces false positives:

1. Disable the workflow by setting `on: schedule:` to an empty list (preserves the workflow file for re-enabling).
2. Optionally revert the `paths`-triggered PR runs only, keeping the nightly cadence.
3. Worst case: revert the workflow file and the `scripts/console-harness.mjs` entry point. The legacy harness scripts and the new fixture module remain useful for manual exploration.
