# V1 Trust Evidence And Required Checks Umbrella Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make FixThis V1 source-trust evidence and required-check readiness discoverable, verifiable, and clearly separated from maintainer-only GitHub admin actions.

**Architecture:** Repair the fixture-lab navigation contract first, then make release-readiness consume that fixed evidence entry point, then update required-check tracking to separate observed workflow readiness from actual branch-protection enforcement. Keep existing scripts as the source of truth; add only narrow documentation assertions where a new cross-link becomes part of the release contract.

**Tech Stack:** Markdown docs, Node.js 20 ESM scripts with `node:test`, npm script wrappers, GitHub Actions observation through `gh`.

## Global Constraints

- Do not add new package channels such as PyPI or Docker.
- Do not add release-build support.
- Do not claim XML/View or WebView exact source ownership.
- Do not add WebView DOM inspection.
- Do not add new Android runtime behavior.
- Do not change bridge protocol, MCP persisted JSON, output-schema fields, generated source-index schema, or compact handoff grammar.
- Do not claim GitHub branch protection settings changed unless a maintainer changed them outside the repo and recorded the fact.
- Do not commit `.fixthis/`, `build/reports/fixthis-source-matching/`, or `graphify-out/`.

---

## File Structure

- `docs/index.md` is the maintained documentation directory and must link to the source-matching fixture lab.
- `docs/guides/source-matching-fixture-lab.md` explains local fixture-lab usage, artifacts, runtime strictness, and trust classifications.
- `docs/contributing/release-readiness.md` names the release claims and evidence commands that consume source-trust fixture evidence.
- `scripts/check-release-readiness.mjs` pins release-readiness documentation contracts.
- `docs/contributing/required-checks-observation.md` records the latest workflow-level observation snapshot from `npm run checks:observation -- --json`.
- `docs/contributing/required-checks.md` tracks branch-protection readiness and enforcement status.
- `scripts/required-checks-observation-test.mjs` pins the distinction between observation evidence, ready-to-require status, and enforcement.

---

### Task 1: Restore Fixture-Lab Navigation And Guide Contract

**Files:**
- Modify: `docs/index.md`
- Modify: `docs/guides/source-matching-fixture-lab.md`
- Test: `scripts/source-matching-fixtures-test.mjs`

**Interfaces:**
- Consumes: existing fixture-lab documentation test `docs explain that fixture lab is local-only and gitignored`.
- Produces: a maintained docs-index link to `docs/guides/source-matching-fixture-lab.md` and clearer guide text for future fixture-lab work.

- [ ] **Step 1: Reproduce the current contract failure**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: FAIL in `docs explain that fixture lab is local-only and gitignored` with `docs/index.md` missing `source-matching-fixture-lab.md`.

- [ ] **Step 2: Add the fixture lab to the docs index**

In `docs/index.md`, add this bullet in the `## Architecture and Design` section immediately after the `Architecture overview` bullet:

```markdown
- [Source matching fixture lab](guides/source-matching-fixture-lab.md) —
  local-only source-index and runtime-trust evidence for shared-component,
  visual-area, interop-risk, and confidence-cap regressions
```

The resulting section should read:

```markdown
## Architecture and Design

- [Project map](guides/project-map.md) - compact repository map for humans and agents: module responsibilities, first files, task routes, validation commands, and artifact boundaries
- [Architecture overview](architecture/overview.md) — module map, runtime
  flow, source-matching pipeline
- [Source matching fixture lab](guides/source-matching-fixture-lab.md) —
  local-only source-index and runtime-trust evidence for shared-component,
  visual-area, interop-risk, and confidence-cap regressions
- [Architecture Decision Records](architecture/adr/README.md) — durable
  decisions and their rationale
- [Console state sync](architecture/console-state-sync-design.md) — shipped
  `/api/events` SSE Phase 1, with ETag polling retained as fallback and
  active-session fences on session/preview events
```

- [ ] **Step 3: Tighten the fixture-lab guide introduction**

In `docs/guides/source-matching-fixture-lab.md`, replace the opening paragraphs through `It is not a release gate, not a CI requirement, and not part of the public install path.` with:

```markdown
# Source Matching Fixture Lab

The source matching fixture lab is a local-only developer tool for checking
whether FixThis source-index and source-hint changes remain trustworthy on
external Compose apps and on the bundled sample app.

Use this guide when a change touches source matching, target reliability,
edit-surface confidence, shared-component call-site guidance, visual-area
caveats, AndroidView/WebView boundary context, or release evidence that cites
those behaviors.

It is not a release gate, not a CI requirement, not a branch-protection setting,
and not part of the public install path. Release-readiness docs can cite its
commands as local evidence, but the lab itself does not publish artifacts or
change any public package channel.
```

- [ ] **Step 4: Add explicit classification guidance**

In `docs/guides/source-matching-fixture-lab.md`, add this section immediately before `## Re-Pinning`:

```markdown
## Classification Rules

Treat fixture outcomes as evidence classifications, not as a single pass/fail
bucket:

- Product failure: a committed source-index or runtime-trust expectation fails
  in a prepared fixture environment.
- Documentation drift: a guide, docs index, or release-readiness reference no
  longer points to the supported fixture-lab entry point.
- Environment downgrade: non-strict runtime evidence cannot run because Android
  SDK, ADB, a ready device, or app runtime prerequisites are unavailable.
- Strict runtime failure: strict runtime evidence is requested and those runtime
  prerequisites are unavailable.
- Fixture drift: a pinned external repository no longer contains the expected
  path, module graph, launch state, or semantics target.
- Caveated pass: FixThis cannot prove exact edit ownership but preserves the
  required caveat, such as `SHARED_COMPONENT`, `VISUAL_AREA_ONLY`,
  `POSSIBLE_VIEW_INTEROP`, boundary context, or a low/medium confidence cap.

Do not weaken a case to make a report green. A high-confidence result is a
failure when the case exists to prove caution, even if the source path looks
plausible.
```

- [ ] **Step 5: Verify the fixture-lab documentation contract**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS, including `docs explain that fixture lab is local-only and gitignored`.

- [ ] **Step 6: Commit Task 1**

Run:

```bash
git add docs/index.md docs/guides/source-matching-fixture-lab.md
git commit -m "docs: restore source matching fixture lab navigation"
```

Expected: commit succeeds with only the two documentation files staged.

---

### Task 2: Cross-Link Source Trust Evidence From Release Readiness

**Files:**
- Modify: `scripts/check-release-readiness.mjs`
- Modify: `docs/contributing/release-readiness.md`

**Interfaces:**
- Consumes: fixture-lab entry point from Task 1 at `../guides/source-matching-fixture-lab.md`.
- Produces: release-readiness assertions that source-trust evidence remains discoverable from V1 trust sections.

- [ ] **Step 1: Add a failing release-readiness assertion**

In `scripts/check-release-readiness.mjs`, add this check immediately after rule `R49.v1-sse-fallback-evidence`:

```js
requireIncludes(
  'R60.source-matching-fixture-lab-link',
  'docs/contributing/release-readiness.md',
  '../guides/source-matching-fixture-lab.md',
);
```

Run:

```bash
node scripts/check-release-readiness.mjs
```

Expected: FAIL `R60.source-matching-fixture-lab-link` because `docs/contributing/release-readiness.md` does not yet link to the fixture-lab guide.

- [ ] **Step 2: Link the fixture lab from v1 residual-risk evidence**

In `docs/contributing/release-readiness.md`, add this paragraph immediately after the `v1 Residual Risk Closure Gate` evidence table:

```markdown
The source-matching fixture lab is the maintained local evidence entry point
for the source-trust row above. Start with
[Source matching fixture lab](../guides/source-matching-fixture-lab.md) when
refreshing shared-component, visual-area, interop-risk, recommended-edit-site,
or confidence-cap evidence. The lab writes local reports under
`build/reports/fixthis-source-matching/`; do not commit those reports.
```

- [ ] **Step 3: Link the fixture lab from V1 trust/install evidence**

In `docs/contributing/release-readiness.md`, add this paragraph immediately after the `V1 Trust, Install, And Inner-Loop Evidence` evidence table:

```markdown
For the source-trust row, use the
[source matching fixture lab](../guides/source-matching-fixture-lab.md) to
interpret fixture classifications. A caveated pass is acceptable only when the
handoff preserves the expected caution signal and avoids a high-confidence
exact-ownership claim for risky evidence.
```

- [ ] **Step 4: Verify release-readiness assertions**

Run:

```bash
node scripts/check-release-readiness.mjs
```

Expected: PASS, including `R60.source-matching-fixture-lab-link`.

- [ ] **Step 5: Commit Task 2**

Run:

```bash
git add scripts/check-release-readiness.mjs docs/contributing/release-readiness.md
git commit -m "docs: link fixture lab from release readiness"
```

Expected: commit succeeds with the release checker and release-readiness doc staged.

---

### Task 3: Separate Required-Check Observation From Enforcement

**Files:**
- Modify: `scripts/required-checks-observation-test.mjs`
- Modify: `docs/contributing/required-checks-observation.md`
- Modify: `docs/contributing/required-checks.md`

**Interfaces:**
- Consumes: `npm run checks:observation -- --json` workflow-level output.
- Produces: docs that distinguish observation evidence, ready-to-require status, and actual enforcement status.

- [ ] **Step 1: Add a failing documentation contract test**

In `scripts/required-checks-observation-test.mjs`, append:

```js
test("required-checks tracker separates observation readiness from enforcement", () => {
  const tracker = readFileSync("docs/contributing/required-checks.md", "utf8");

  assert.match(tracker, /Observation evidence/);
  assert.match(tracker, /Ready to require/);
  assert.match(tracker, /Enforcement status/);
  assert.match(tracker, /admin action pending/i);
  assert.match(tracker, /workflow-level observation/i);
});
```

Run:

```bash
npm run checks:observation:test
```

Expected: FAIL because `docs/contributing/required-checks.md` still has the older three-column readiness model.

- [ ] **Step 2: Refresh the observation snapshot**

Replace the contents of `docs/contributing/required-checks-observation.md` with:

````markdown
# Required Checks Observation Snapshot

Last refreshed: 2026-07-05

Refresh command:

```bash
npm run checks:observation -- --json
```

This snapshot is a maintainer aid. GitHub Actions and
`docs/contributing/required-checks.md` remain the source of truth for branch
protection. The script observes workflow-level success streaks; it does not
prove that every individual job or sub-check inside a workflow has a separate
branch-protection status name.

| Workflow | First green in sample | Consecutive green | Required | Ready | Latest run |
| --- | --- | ---: | ---: | --- | --- |
| `.github/workflows/ci.yml` | 2026-05-19T04:01:48Z | 12 | 7 | yes | https://github.com/beyondwin/FixThis/actions/runs/28699201895 |
| `.github/workflows/codeql.yml` | 2026-05-25T09:53:31Z | 17 | 7 | yes | https://github.com/beyondwin/FixThis/actions/runs/28699201898 |
| `.github/workflows/connected-tests.yml` | 2026-06-15T09:52:57Z | 20 | 14 | yes | https://github.com/beyondwin/FixThis/actions/runs/28698007108 |
| `.github/workflows/nightly-compat.yml` | 2026-05-12T06:08:10Z | 8 | 1 | yes | https://github.com/beyondwin/FixThis/actions/runs/28425957564 |

Next action: update `docs/contributing/required-checks.md` only where the
workflow-level observation is enough for the branch-protection decision. For
sub-checks inside `ci.yml`, confirm the exact GitHub status-check names before
marking individual rows ready to require.
````

- [ ] **Step 3: Replace the tracker intro and table**

In `docs/contributing/required-checks.md`, replace the paragraphs from `This document tracks...` through the current table with:

```markdown
This document tracks the observation windows for the workflows listed in the
"Required PR checks" table in [`CONTRIBUTING.md`](../../CONTRIBUTING.md). The
actual GitHub branch-protection enable is a maintainer admin action.

This tracker separates three states:

- **Observation evidence:** `npm run checks:observation -- --json` has enough
  consecutive green workflow runs.
- **Ready to require:** the observed evidence is enough for a maintainer to add
  the check to branch protection.
- **Enforcement status:** the GitHub repository setting has actually been
  changed. `admin action pending` means repo evidence is ready but the setting
  has not been recorded here as flipped.

The observation script currently reports workflow-level observation. Rows for
individual commands inside `.github/workflows/ci.yml` must not be promoted
solely from aggregate workflow-level observation unless the maintainer also
confirms the exact GitHub status-check names to require.

| Check | Workflow or status source | Observation evidence | Ready to require? | Enforcement status |
|---|---|---|---|---|
| Build + unit tests | `.github/workflows/ci.yml` baseline job | pre-existing | yes | required |
| Kotlin formatting | `./gradlew spotlessCheck` in ci.yml | `.github/workflows/ci.yml` workflow-level observation ready: 12/7 green | no - status-check name confirmation required before individual promotion | not enforced |
| Static analysis | `./gradlew detekt` in ci.yml | `.github/workflows/ci.yml` workflow-level observation ready: 12/7 green | no - status-check name confirmation required before individual promotion | not enforced |
| Console asset bundle | `node scripts/build-console-assets.mjs --check` in ci.yml | `.github/workflows/ci.yml` workflow-level observation ready: 12/7 green | no - status-check name confirmation required before individual promotion | not enforced |
| Console JS harnesses | `npm run console:test:all` in ci.yml | `.github/workflows/ci.yml` workflow-level observation ready: 12/7 green | no - status-check name confirmation required before individual promotion | not enforced |
| CodeQL | `.github/workflows/codeql.yml` | workflow observation ready: 17/7 green | yes, after maintainer confirms the latest analysis is present in GitHub Security | admin action pending |
| Nightly connected tests | `.github/workflows/connected-tests.yml` | workflow observation ready: 20/14 green | no - scheduled device workflow still requires separate maintainer discussion before branch protection | informational only |
| Compatibility matrix scheduled | `.github/workflows/nightly-compat.yml` | workflow observation ready: 8/1 green | no - scheduled compatibility workflow still requires separate maintainer discussion before branch protection | informational only |
```

- [ ] **Step 4: Replace the update instructions**

In `docs/contributing/required-checks.md`, replace the `## How to Update This Table` section with:

````markdown
## How to Update This Table

Run:

```bash
npm run checks:observation -- --json
npm run checks:observation -- --require-ready connected-tests,nightly-compat
```

Copy the JSON output into
[`required-checks-observation.md`](required-checks-observation.md). Then update
this tracker as follows:

1. Put the workflow streak in **Observation evidence**.
2. Set **Ready to require?** to `yes` only when workflow-level evidence is
   sufficient for the exact branch-protection check being proposed.
3. Leave **Enforcement status** as `admin action pending` until a maintainer
   changes GitHub branch protection and records the flip here.
````

- [ ] **Step 5: Update notes for scheduled workflows and CodeQL**

In `docs/contributing/required-checks.md`, replace the first five bullets under `## Notes` with:

```markdown
- "Observation evidence" is generated by `scripts/required-checks-observation.mjs`
  from recent completed GitHub Actions workflow runs on `main`.
- The default observation window for PR-time workflows is 7 consecutive green
  completed runs.
- Scheduled device and compatibility workflows have longer or lower-cadence
  windows, but observation readiness does not automatically mean they should be
  branch-protection requirements.
- The CodeQL row is also gated on a maintainer confirming that the latest
  analysis is visible in the GitHub Security tab.
- For command-level rows inside `ci.yml`, confirm the exact GitHub status-check
  name before moving **Ready to require?** to `yes`.
```

Keep the existing Detekt / Gradle 10 note and detekt-baseline budget note below those bullets.

- [ ] **Step 6: Verify the observation docs contract**

Run:

```bash
npm run checks:observation:test
```

Expected: PASS, including `required-checks tracker separates observation readiness from enforcement`.

- [ ] **Step 7: Commit Task 3**

Run:

```bash
git add scripts/required-checks-observation-test.mjs docs/contributing/required-checks-observation.md docs/contributing/required-checks.md
git commit -m "docs: separate required check readiness from enforcement"
```

Expected: commit succeeds with the observation test and two required-check docs staged.

---

### Task 4: Final Verification And Release-Readiness Closeout

**Files:**
- Verify: `docs/index.md`
- Verify: `docs/guides/source-matching-fixture-lab.md`
- Verify: `docs/contributing/release-readiness.md`
- Verify: `docs/contributing/required-checks.md`
- Verify: `docs/contributing/required-checks-observation.md`
- Verify: `scripts/check-release-readiness.mjs`
- Verify: `scripts/required-checks-observation-test.mjs`

**Interfaces:**
- Consumes: all changes from Tasks 1-3.
- Produces: command-backed closeout evidence and a clean working tree.

- [ ] **Step 1: Run fixture-lab contract tests**

Run:

```bash
npm run source-matching:fixtures:test
```

Expected: PASS with `docs explain that fixture lab is local-only and gitignored`.

- [ ] **Step 2: Run release-readiness checks**

Run:

```bash
node scripts/check-release-readiness.mjs
```

Expected: PASS with `R60.source-matching-fixture-lab-link`.

- [ ] **Step 3: Run required-check observation tests**

Run:

```bash
npm run checks:observation:test
```

Expected: PASS with `required-checks tracker separates observation readiness from enforcement`.

- [ ] **Step 4: Refresh live observation output**

Run:

```bash
npm run checks:observation -- --json
```

Expected: JSON prints the four observed workflows. If values differ from the snapshot in Task 3, update `docs/contributing/required-checks-observation.md` with the new values before continuing.

- [ ] **Step 5: Run docs CLI-surface check**

Run:

```bash
bash scripts/check-docs-cli-surface.sh
```

Expected: PASS. This protects command references added or moved in docs.

- [ ] **Step 6: Check whitespace and artifact boundaries**

Run:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` prints nothing. `git status --short` shows only tracked source/doc/script files from this plan, not `.fixthis/`, `build/reports/fixthis-source-matching/`, or `graphify-out/`.

- [ ] **Step 7: Commit any final verification-driven adjustments**

If Step 4 changed the observation snapshot, run:

```bash
git add docs/contributing/required-checks-observation.md
git commit -m "docs: refresh required checks observation snapshot"
```

Expected: commit succeeds only when the snapshot changed. If Step 4 made no changes, skip this commit.

## Plan Self-Review

- Spec coverage: Task 1 covers fixture-lab navigation and guide clarity. Task 2 covers release-readiness cross-linking. Task 3 covers observed readiness versus branch-protection enforcement. Task 4 covers the specified verification commands and artifact boundaries.
- Forbidden-token scan: no unfinished-work markers or vague implementation steps remain.
- Type and interface consistency: all script names, doc paths, npm commands, and release-readiness rule names are defined before later tasks rely on them.
