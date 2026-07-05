# V1 Trust Evidence And Required Checks Umbrella Design

Date: 2026-07-05
Status: Approved design - pending implementation plan

## Goal

Close the next FixThis V1 quality gap by making source-trust evidence and
required-check readiness easier to find, verify, and act on.

This umbrella runs in two ordered tracks:

1. Source Matching Fixture Lab / Trust Evidence cleanup.
2. Required Checks / Release Gate readiness cleanup.

The product outcome is not a new runtime capability. The outcome is a tighter
evidence path: maintainers and agents should be able to see which source-trust
contracts are covered, which release claims consume them, and which branch
protection actions still require maintainer admin work.

## Current Context

The repository already has the major pieces:

- `fixtures/source-matching/manifest.json` declares source-index and
  runtime-trust cases for Reply, Jetsnack, Now in Android, and the local sample.
- `scripts/source-matching-fixtures.mjs` runs fixture preparation, source-index
  checks, runtime trust checks, classification, and reports.
- `docs/guides/source-matching-fixture-lab.md` documents local fixture lab use.
- `docs/contributing/release-readiness.md` names V1 trust, residual-risk, and
  release-gate evidence commands.
- `docs/contributing/required-checks.md` tracks observation windows before
  branch protection changes.
- `scripts/check-release-readiness.mjs` and `scripts/required-checks-observation.mjs`
  already provide machine-checkable readiness evidence.

The immediate observed gap is documentation drift:

```bash
npm run source-matching:fixtures:test
```

currently reaches the fixture-lab documentation contract and fails because
`docs/index.md` does not link to `source-matching-fixture-lab.md`. The release
readiness checker passes, and required-check observation output reports ready
workflow evidence, so the next step is evidence alignment rather than new
package channels or broader platform support.

## Scope

In scope:

- Restore the source-matching fixture-lab documentation contract.
- Make fixture lab discovery explicit from the maintained docs index.
- Tighten fixture-lab guide language around local-only artifacts, report paths,
  strict runtime behavior, and failure classification.
- Cross-link fixture-lab trust evidence from release-readiness docs where V1
  trust and residual-risk claims already depend on it.
- Update required-check readiness documentation so observed readiness, branch
  protection readiness, and actual maintainer-admin flips are separated.
- Keep the A track before the B track so release/required-check cleanup consumes
  a fixed trust-evidence entry point.

Out of scope:

- New package channels such as PyPI or Docker.
- Release-build support.
- XML/View or WebView exact source ownership.
- WebView DOM inspection.
- New Android runtime behavior.
- Bridge protocol, MCP persisted JSON, or output-schema field renames.
- Actual GitHub branch protection settings. Those require maintainer admin
  action outside the repository.

## Architecture

The umbrella keeps current module boundaries. It is primarily documentation and
release-evidence alignment.

Track A owns source-trust evidence navigation:

```text
fixtures/source-matching/manifest.json
-> scripts/source-matching-fixtures.mjs
-> source-index / runtime-trust classification
-> build/reports/fixthis-source-matching/
-> docs/guides/source-matching-fixture-lab.md
-> docs/index.md
-> docs/contributing/release-readiness.md
```

Track B owns check-readiness interpretation:

```text
npm run source-matching:fixtures:test
node scripts/check-release-readiness.mjs
npm run checks:observation -- --json
-> docs/contributing/release-readiness.md
-> docs/contributing/required-checks.md
-> CONTRIBUTING.md local PR checklist, if needed
```

The implementation should avoid creating a parallel release gate. Existing
scripts remain the source of truth. Documentation should point to them and
describe the meaning of their output.

## Components

### 1. Fixture Lab Navigation

`docs/index.md` should expose `docs/guides/source-matching-fixture-lab.md`
from the maintained navigation surface. This fixes the current failing contract
and gives future agents a stable first read for fixture-lab work.

The link should appear where maintainers look for architecture, tooling, or
release-evidence guidance. It should not be buried only in historical planning.

### 2. Fixture Lab Guide Tightening

`docs/guides/source-matching-fixture-lab.md` should clearly answer:

- When to use the lab.
- Which commands run static source-index checks versus runtime trust checks.
- Why `.fixthis/eval-fixtures/` and `build/reports/fixthis-source-matching/`
  are local generated artifacts.
- Why the lab is local-only evidence and not itself a public package channel or
  branch-protection setting.
- How to interpret `pass`, `fail`, environment downgrade, fixture drift, and
  caveated trust outcomes.
- Why schema-v2 manifest cases must use supported fields instead of stale
  observation-only fields.

The guide should emphasize overclaim prevention: `SHARED_COMPONENT`,
`recommendedEditSite`, `VISUAL_AREA_ONLY`, `POSSIBLE_VIEW_INTEROP`,
boundary context, and confidence caps are trust contracts, not just test data.

### 3. Release Readiness Cross-Link

`docs/contributing/release-readiness.md` should keep existing claims but make
the source-trust evidence route easier to follow. The desired reader path is:

```text
release claim
-> evidence command
-> fixture-lab guide
-> manifest/report behavior
```

The docs should not add a new claim unless the implementation also adds a
corresponding script assertion. Prefer clarifying existing V1 trust,
residual-risk, and release-gate sections.

### 4. Required Checks Tracker Update

`docs/contributing/required-checks.md` should separate three states:

- Observation evidence is ready.
- Branch-protection setting is ready to flip.
- Branch-protection setting has actually been flipped by a maintainer.

The implementation must not claim a GitHub admin setting changed unless it was
changed outside the repo and the maintainer records that fact.

There is one nuance: current observation JSON is workflow-level, while the
tracker has some sub-check rows inside `.github/workflows/ci.yml`. The
implementation should either document the workflow-level mapping clearly or
extend the observation evidence before marking sub-rows ready. It must not
silently promote sub-check rows from unrelated evidence.

## Data Flow

Track A:

1. Maintainer or agent starts from `docs/index.md`.
2. The index points to the source-matching fixture-lab guide.
3. The guide points to `fixtures/source-matching/manifest.json` and the npm
   scripts.
4. The scripts produce source-index and runtime-trust reports under
   `build/reports/fixthis-source-matching/`.
5. Release-readiness docs reference those commands as evidence for source-trust
   claims.

Track B:

1. Maintainer runs release-readiness and observation commands.
2. Release-readiness verifies supported public channels and evidence command
   references.
3. Observation output records whether workflows have enough green runs.
4. Required-check docs record which workflows are ready, which are enforced,
   and which still require maintainer admin action.

## Error Handling

Classifications should stay precise:

- Product failure: a fixture trust contract fails, a release-readiness rule
  fails, or an observation script reports not ready.
- Documentation drift: a required guide or index link is missing while the
  product behavior is otherwise unchanged.
- Environment deferred: Android SDK, ADB, device, or app runtime prerequisites
  are missing in non-strict runtime evidence.
- Strict runtime failure: strict Android evidence is requested but runtime
  prerequisites are unavailable.
- Fixture drift: a pinned external fixture changed or no longer matches its
  expected source path or runtime target.
- Admin action pending: repo evidence says a check is ready, but GitHub branch
  protection has not been changed by a maintainer.

Documentation should avoid wording that turns a deferred runtime check into a
pass or an admin-action-pending check into an enforced setting.

## Testing

Expected verification for the implementation plan:

```bash
npm run source-matching:fixtures:test
node scripts/check-release-readiness.mjs
npm run checks:observation -- --json
```

If the implementation changes docs that mention CLI command flags, also run:

```bash
bash scripts/check-docs-cli-surface.sh
```

If the implementation changes observation-script logic rather than docs only,
also run the corresponding script tests:

```bash
npm run checks:observation:test
```

## Sequencing

1. Restore fixture-lab docs/index navigation and prove
   `npm run source-matching:fixtures:test` passes.
2. Tighten fixture-lab guide wording around local artifacts, strict/deferred
   runtime behavior, and trust classifications.
3. Cross-link source-trust evidence from release-readiness docs without adding
   unsupported claims.
4. Update required-checks tracker language to distinguish ready evidence,
   ready-to-flip status, and actual branch-protection enforcement.
5. Run the full verification set for the touched files.

## Compatibility

This umbrella does not change runtime protocols, generated source-index schema,
MCP tool signatures, persisted feedback-session JSON, bridge protocol fields,
or compact handoff grammar.

All documentation changes must respect the existing artifact boundary:

- Do not commit `.fixthis/`.
- Do not commit `build/reports/fixthis-source-matching/`.
- Do not commit `graphify-out/`.
- Do not describe local reports as public release artifacts unless a release
  issue explicitly attaches them.

## Acceptance Criteria

- `docs/index.md` links to `docs/guides/source-matching-fixture-lab.md`.
- `npm run source-matching:fixtures:test` passes.
- Fixture-lab documentation explains local-only generated paths, strict runtime
  behavior, supported commands, and trust failure classifications.
- Release-readiness docs keep source-trust evidence discoverable from the
  relevant V1 claim sections.
- Required-checks docs do not conflate observed readiness with maintainer admin
  branch-protection changes.
- Release-readiness verification still passes.
