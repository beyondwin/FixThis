# Evidence: Gradle `-Xmx` tuning rejected (2026-05-16)

Tried `-Xmx4096m` and `-Xmx6144m` against `docs/perf/baseline-2026-05-16.json`.
Outcome: no scenario reached the >=5% median improvement threshold; all verdicts
were NEUTRAL on both values. Both values trended slightly *slower* than baseline
on warm Gradle scenarios, consistent with larger JVM heap incurring higher startup
overhead for incremental up-to-date builds that are not heap-bound.

Recommendation: keep `-Xmx2048m` until a different optimization (Kotlin
daemon args, AGP build features) creates heap-ceiling pressure that
justifies revisiting.

## 4096m (-Xmx4096m) results vs baseline

| Scenario | Baseline (median) | 4G (median) | Delta | Verdict |
| --- | --- | --- | --- | --- |
| warm-mcp-test | 5369 ms | 5469 ms | +100 ms (+1.86%) | NEUTRAL |
| installdist-mcp | 5385 ms | 5751 ms | +366 ms (+6.8%) | NEUTRAL |
| console-test-fast | 452 ms | 454 ms | +2 ms (+0.44%) | NEUTRAL |
| console-test-all | 609 ms | 596 ms | -13 ms (-2.13%) | NEUTRAL |

compare-perf exit code: 0

## 6144m (-Xmx6144m) results vs baseline

| Scenario | Baseline (median) | 6G (median) | Delta | Verdict |
| --- | --- | --- | --- | --- |
| warm-mcp-test | 5369 ms | 5615 ms | +246 ms (+4.58%) | NEUTRAL |
| installdist-mcp | 5385 ms | 5509 ms | +124 ms (+2.3%) | NEUTRAL |
| console-test-fast | 452 ms | 455 ms | +3 ms (+0.66%) | NEUTRAL |
| console-test-all | 609 ms | 632 ms | +23 ms (+3.78%) | NEUTRAL |

compare-perf exit code: 0

## Interpretation

The measured workloads (incremental warm builds with most tasks UP-TO-DATE) are
dominated by JVM startup and Gradle configuration overhead, not heap allocation.
Raising the heap limit from 2048m to 4096m or 6144m does not reduce that overhead
and may add marginal startup cost. The 2048m ceiling is adequate for these builds.

## Raw reports preserved at

- `/tmp/perf-xmx-4g-report.md` (not committed)
- `/tmp/perf-xmx-6g-report.md` (not committed)

The harness ships with empirical proof of its gate — a negative result is a
successful outcome of this plan.
