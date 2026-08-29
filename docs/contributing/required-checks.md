# Required PR checks — readiness tracker

This document records live branch-protection contexts and observation windows
for non-required workflows listed in the "Required PR checks" table in
[`CONTRIBUTING.md`](../../CONTRIBUTING.md).

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
| `Gradle verification` | `.github/workflows/ci.yml` (`gradle-verification`) | live PR check | yes | required |
| `Console JavaScript` | `.github/workflows/ci.yml` (`console-js`) | live PR check | yes | required |
| `Analyze (java-kotlin)` | `.github/workflows/codeql.yml` | live PR check | yes | required |
| `Analyze (javascript-typescript)` | `.github/workflows/codeql.yml` | live PR check | yes | required |
| Nightly connected tests | `.github/workflows/connected-tests.yml` | workflow observation ready: 20/14 green | no - scheduled device workflow still requires separate maintainer discussion before branch protection | informational only |
| Compatibility matrix scheduled | `.github/workflows/nightly-compat.yml` | workflow observation ready: 8/1 green | no - scheduled compatibility workflow still requires separate maintainer discussion before branch protection | informational only |

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

## Notes

- Observation comes from `scripts/required-checks-observation.mjs` on `main`.
- PR-time window: 7 consecutive green runs. Scheduled workflows have longer
  windows and are not auto-promoted to branch protection.
- Live required checks: `Gradle verification`, `Console JavaScript`,
  `Analyze (java-kotlin)`, `Analyze (javascript-typescript)`.
- Detekt 1.23.7 still hits a Gradle 10 deprecation in
  `ReportingExtension.file(String)` during `:fixthis-mcp:detekt`. Re-check on
  the next Detekt before Gradle 10.
- `npm run detekt:baseline:check` ratchets baselines. Lower the budget when
  a count drops. Raising a budget needs a review explanation.
