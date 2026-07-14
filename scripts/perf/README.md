# Performance Measurement Harness

This directory contains the build/test benchmark harness. See
[`../../docs/superpowers/specs/2026-05-16-build-test-performance-measurement-design.md`](../../docs/superpowers/specs/2026-05-16-build-test-performance-measurement-design.md)
for the design.

## Quick start

```bash
# Run all scenarios (slow — 20–40 minutes depending on hardware)
npm run perf:bench

# Run one scenario, fewer iterations
npm run perf:bench -- --scenario warm-mcp-test --iterations 3

# Compare against the committed baseline
npm run perf:compare -- docs/perf/baseline-2026-05-18-linux.json output/perf/run-<timestamp>.json
```

## Output

Every run writes `output/perf/run-<ISO-timestamp>.json` (gitignored).
The schema is documented in `perf-scenarios.json` and the parser
output is documented in `parse-gradle-profile.mjs`.

## Regression rule

A scenario is flagged `REGRESS` when:
- `median_current - median_baseline > max(2 * hypot(stddev_baseline, stddev_current), 0.02 * median_baseline)` (combined noise band)
- AND `(median_current - median_baseline) / median_baseline * 100 > scenario.regress_threshold_pct`

Improvements use the symmetric rule against `improve_threshold_pct`.
Everything else is `NEUTRAL`.

## Hardware variance

The JSON records OS, architecture, CPU model and core count, RAM, JDK, and Node
versions. When comparing across environments the comparator reports apparent
regressions and improvements as advisory and does not fail. A hard regression
or confirmed improvement requires matching environment fingerprints;
re-baseline locally for a fair comparison.
