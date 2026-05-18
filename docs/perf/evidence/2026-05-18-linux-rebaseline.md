# 2026-05-18 — Linux runner rebaseline

`docs/perf/baseline-2026-05-16.json` was captured on a developer macOS
machine (darwin / arm64 / Apple M4 Pro / 49 GB / Node 25 / JDK 25). The
`perf-report` workflow runs on `ubuntu-latest` (linux / x64 / AMD EPYC /
16 GB / Node 20 / JDK 21), so every nightly run flagged 100–400%
"regressions" purely from hardware delta. Once the missing Playwright
Chromium install on the perf runner was fixed, the comparator finally
reached the regression step and exposed this latent mismatch.

The new active baseline `docs/perf/baseline-2026-05-18-linux.json` was
captured on the `ubuntu-latest` runner during the
[v0.6.0 perf-report re-run](https://github.com/beyondwin/FixThis/actions/runs/26029161438)
(commit `1858c7f8`). It is the source of truth for the CI perf gate going
forward.

Local Mac runs are expected to outpace the Linux baseline; the comparator
emits an environment warning rather than failing, so contributor measurements
remain informational. Re-baseline by re-running `perf-report` on `main`,
downloading the artifact, and replacing this file in a reviewed commit.
