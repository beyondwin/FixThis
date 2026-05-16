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
npm run perf:compare -- docs/perf/baseline-2026-05-16.json output/perf/run-<timestamp>.json
```

## Output

Every run writes `output/perf/run-<ISO-timestamp>.json` (gitignored).
The schema is documented in `perf-scenarios.json` and the parser
output is documented in `parse-gradle-profile.mjs`.

## Regression rule

A scenario is flagged `REGRESS` when:
- `median_current - median_baseline > max(2 * stddev_baseline, 0.02 * median_baseline)` (noise band)
- AND `(median_current - median_baseline) / median_baseline * 100 > scenario.regress_threshold_pct`

Improvements use the symmetric rule against `improve_threshold_pct`.
Everything else is `NEUTRAL`.

## Hardware variance

The JSON records OS, CPU model, RAM, JDK, and Node versions. When
comparing across machines the comparator prints a warning but does
not fail; re-baseline locally for a fair comparison.
