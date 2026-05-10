# Source Coordinate Staleness Detection — Follow-ups Design

**Date:** 2026-05-10
**Status:** Approved (pending implementation)
**Owner:** kws
**Parent feature:**
- Spec: `docs/superpowers/specs/2026-05-10-source-coordinate-staleness-detection-design.md`
- Plan: `docs/superpowers/plans/2026-05-10-source-coordinate-staleness-detection.md`
**Related plan:** `docs/superpowers/plans/2026-05-10-source-coordinate-staleness-followups.md`

---

## 1. Problem

The source-coordinate staleness detection feature shipped in merge commit `eebc675` (2026-05-10). 5-module regression passes (621 tests, 0 failures). However, the orchestrator run logged **seven categories of residual risk** that the parent spec deliberately deferred or that emerged during execution:

| # | Category | Origin |
|---|----------|--------|
| C1 | Real-device E2E unverified — only unit-tested with temp directories | Parent spec §13 acceptance 1–3 |
| C2 | `projectRoot` misconfiguration produces silent false positives | Parent spec §14 R1, §10 row "projectRoot is wrong" |
| C3 | `fixthis_status` stat I/O scaling | Parent spec §14 R3 |
| C4 | APK install epoch vs file mtime clock skew | Parent spec §14 R4 |
| C5 | Reviewer advisories left as notes, not fixed | Orchestrator state.json `review_notes` for tasks 2/3/5/6 |
| C6 | Stale signal not surfaced in handoff Markdown | Parent spec §11 future work, §16 OQ #3 |
| C7 | Operational hygiene — worktree cleanup, stash leftovers | Orchestrator final summary "Cleanup Status" |

Each category has a different severity and a different "right" disposition (implement now, defer with tracking, discard). This spec triages all seven and designs the implementable subset.

### 1.1 Why a single follow-up document?

The seven items share one root cause (a feature shipped with deliberately narrow scope) and benefit from coordinated triage: deciding whether to invest in C2 detection logic, for example, depends on whether C6 (handoff rendering) makes the false-positive blast radius smaller. Splitting them into seven separate plans hides those couplings.

The plan file (`-followups.md`) groups items into work packages so a future executor can pick a coherent slice without rereading every parent doc.

## 2. Goals

- Eliminate the **highest-leverage** residual risks within ~1 day of work: items with measurable user impact (C1 E2E verification, C6 handoff rendering, C2 silent false-positive guard).
- Quietly close items that have shipped-and-passing equivalents (C5 cosmetic test cleanup).
- Defer items that are speculative or premature optimization (C3 stat scaling, C4 clock skew tolerance) with an explicit "revisit when X observed" trigger.
- Resolve operational hygiene (C7) immediately as a checklist, not as code.
- Preserve the parent spec's compatibility contract: nullable additive fields only, no bridge protocol bump, no rename of persisted fields.

## 3. Non-goals

- Re-architecting the per-candidate or global staleness checks. The two-checker layering (host-only, no Gradle plugin coupling) ships intact.
- Build-time content digest in `fixthis-build-info.json` — parent spec §15 explicitly defers this; it is a future plan, not a follow-up.
- CLI subcommand `fixthis stale` — same reason.
- Auto-reinstall on `fixthis_open_feedback_console` — opt-in feature, separate plan.
- Index-aware new-file detection (walks `sample/src` for files not in the index) — parent §15.
- Changing `fixthis_status` JSON field names. `installStale*` are now public surface.

## 4. Per-category disposition

### 4.1 C1 — Real-device E2E unverified (IMPLEMENT)

**Severity:** medium. The unit tests exercise the staleness algorithms over temp directories; they do not exercise:
- ADB transport of the `installEpochMillis` field through `fixthis_status` and `bridge.status()`.
- Sidekick's `PackageManager.getPackageInfo(...).lastUpdateTime` returning a meaningful timestamp on a real device after `installDebug`.
- The `cold-launch` step required to refresh `cachedSourceIndexResult` (`BridgeServer.kt:364`).
- The full chain: edit-source → `fixthis_status` → `installStale: true` → `installDebug` → cold launch → `installStale: false`.

**Disposition:** add an opt-in extension to `scripts/fixthis-smoke.sh` (`--check-staleness`) that runs the round-trip on a real connected device. The smoke script already exists and is the project's E2E hook (referenced in parent spec §14 R4 mitigation). Adding a flag rather than a new script keeps the entry point single.

**Acceptance:** with `--check-staleness` and a connected device, the script:
1. Runs `:app:installDebug`, cold-launches the app, captures baseline `fixthis_status`. Asserts `installStale: false`.
2. Touches a tracked source file (sets mtime to `now + 60_000`). Re-runs `fixthis_status`. Asserts `installStale: true`, `newerSourceFiles` contains the touched path.
3. Re-runs `:app:installDebug` + cold launch. Re-runs `fixthis_status`. Asserts `installStale: false`.

The script reverts the touched file's mtime in a `trap` so failures don't leave the workspace dirty.

### 4.2 C2 — `projectRoot` misconfiguration silent false positive (IMPLEMENT, narrow)

**Severity:** medium. If the MCP CLI is launched from the wrong directory, *every* indexed file appears missing and `installStale = true` with `newerFileCount = 0` and `totalIndexedFiles = N` — the existing reason string "$N of $N indexed source files changed…" is technically wrong; nothing changed, the host can't see anything.

The parent spec accepted this (documented in `docs/troubleshooting.md`) on the grounds that the count "12 of 12" is a strong tell. But the agent reads the reason string, not the counts; it sees "indexed source files changed" and proceeds with reinstall advice.

**Disposition:** add a single sanity check inside `HostSourceFreshnessProbe.evaluate(...)`: if `totalIndexedFiles > 0` AND every file resolves to a path that doesn't exist on disk (the resolved canonical file is not a regular file), return a distinct `inconclusive` result with reason `"projectRoot may be misconfigured: 0 of <total> indexed files exist on host"`. This sits next to the existing inconclusive paths (`source index not available`, `install epoch unavailable; older sidekick`) and reuses the same `installStale = false` branch.

**Why not log or exception?** The probe must never crash `fixthis_status`. Returning a distinct reason is the only honest signal.

**Edge case:** new project with empty source index → `totalIndexedFiles == 0` → existing code path returns; this guard never trips.

**Acceptance:** unit test in `HostSourceFreshnessProbeTest` sets up a `projectRoot` that has zero of the indexed files present. Asserts `installStale = false` and `reason.contains("projectRoot may be misconfigured")`.

### 4.3 C3 — Stat I/O scaling (DEFER, with trigger)

**Severity:** low. Real projects observed have O(100) source-index entries; stat is microseconds each. The `fixthis_status` call is interactive (agent-triggered, not in a tight loop), so even O(10k) entries would add ~10ms — imperceptible.

**Disposition:** **defer**. Add a tracked note in this spec's §15 Future Work. **Trigger to revisit:** if `fixthis_status` ever exceeds 500ms p95 in real use, or if the source index grows past 5,000 entries.

**Why not implement caching now?** Premature. Cache invalidation by `installEpochMillis` would require remembering the prior epoch in MCP state, which is a new concept; not worth the complexity for a microsecond saving.

### 4.4 C4 — Clock skew between APK install and file mtime (DEFER, monitor)

**Severity:** low. Both timestamps come from the same host (the device receives the APK via ADB, the host wrote the source files) — divergence is rare. The parent spec §14 R4 explicitly punted: "If it surfaces in practice, add tolerance window (e.g., 5 s)."

**Disposition:** **defer**. The `>` comparison stays strict. **Trigger to revisit:** the first user report of "I just reinstalled and `installStale` is still true."

### 4.5 C5 — Reviewer advisories from the orchestrator run (IMPLEMENT, batched)

**Severity:** low. Code-quality nits flagged by Combined Reviewers as advisory, not blocking:

| File | Issue |
|------|-------|
| `SourceCandidateStalenessCheckerTest.kt` | Missing test for `file too large to verify` (1 MB cap exists in code, no test) |
| `SourceCandidateStalenessCheckerTest.kt` | `org.junit.Test` import vs sibling `kotlin.test.Test` convention |
| `SourceCandidateStalenessCheckerTest.kt` | `tempDir()` helper omits the project's conventional `prefix: String` parameter |
| `TargetEvidenceServiceTest.kt:91` | Temp prefix typo `"fixthis-staleness-tes-"` (missing `t`) |
| `HostSourceFreshnessProbeTest.kt` | Inconclusive test asserts `reason.contains("install epoch")` instead of exact spec string |
| `FixThisToolsStatusTest.kt` | Test 2 named `omits installStale` but `installStale=false` is actually present in the payload |

**Disposition:** **implement**. Bundle into one cleanup task. Each is a 1–3 line edit; collectively they take ~15 minutes and meaningfully improve test signal. Adding the 1MB-cap test is the only one with new behavior coverage.

### 4.6 C6 — Stale flag not surfaced in handoff Markdown (IMPLEMENT, highest user value)

**Severity:** medium-high. The parent spec §11 explicitly notes:
> per-candidate `stale = true`인 케이스가 prompt(`fixthis_read_feedback`의 markdown 출력)에 반영되어 에이전트가 알아챌 수 있는지 — 안 보인다면 후속으로 `FeedbackQueueFormatter` 출력에 stale 마커를 넣을 plan을 따로 만든다.

Today the data is exposed in the JSON, but most agents consume the Markdown handoff (`CompactHandoffRenderer`, `FeedbackQueueFormatter`). They never see `stale`. The user-visible benefit of the entire feature is therefore partially blocked.

**Disposition:** **implement**. Add stale rendering to both formatters:

- `CompactHandoffRenderer.appendCandidatesBlock(...)` — the rank-1 candidate's stale state should appear inline in the candidate line. Format proposal: append ` ⚠ stale: <reason>` to the existing line when `candidate.stale == true`. Keep `false` and `null` invisible to avoid noise on the typical good case.
- `FeedbackQueueFormatter.kt:110` — same idea: append ` ⚠ stale: <reason>` after the existing confidence chunk when `candidate.stale == true`.

**Why not strikethrough (`~~Foo.kt:42~~`)?** Markdown strikethrough renders inconsistently in agent UIs; a textual prefix is reliable. The `⚠` glyph is the project's convention for caution markers (consistent with existing risk-flag rendering).

**Why only when `true`?** False = freshly verified, redundant to label. Null = unverifiable, agents shouldn't be punished for XML resource entries.

**Acceptance:** golden-file or substring-match tests for both formatters confirm the stale marker appears for `stale = true` candidates and is absent for `stale = false` and `stale = null`.

### 4.7 C7 — Operational cleanup (NON-CODE)

**Severity:** trivial. Worktree exists, stash exists, neither is a code concern.

**Disposition:** one-shot checklist in the plan file under "Operational tasks." Not a Task in the sub-agent sense — these are user-executed shell commands with verification.

## 5. Architecture

```
                        FixThisTools.fixthis_status
                                 │
              ┌──────────────────┼──────────────────┐
              ▼                  ▼                  ▼
      HostSource              (existing)        (existing)
      FreshnessProbe          per-candidate     per-candidate
      (extended C2)           via TargetEvidence stale flag
              │                  │                  │
              ▼                  ▼                  ▼
                         Handoff renderers
                         (extended C6)
                ┌──────────────────────────────┐
                │ CompactHandoffRenderer       │  ← stale marker on rank-1 line
                │ FeedbackQueueFormatter       │  ← stale marker per-candidate
                └──────────────────────────────┘
                                 │
                                 ▼
                          AI agent prompt
```

The follow-up changes are **localized**: C2 lives entirely inside `HostSourceFreshnessProbe`; C6 lives entirely inside the two formatters. No new modules, no protocol changes, no plumbing.

## 6. Compatibility

| Change | Risk | Mitigation |
|---|---|---|
| C2 new reason string | Reading agents may text-match `"changed after the installed APK was built"`; new reason wouldn't match. | The new reason starts with `"projectRoot may be misconfigured"` — unambiguous and orthogonal. Agents that match on substring still see `installStale = false` and don't take destructive reinstall action. |
| C6 Markdown additions | Existing handoff consumers may expect a fixed format. | Stale marker is appended after the existing line, never replaces. Old golden tests for non-stale cases continue to pass. |
| C5 test cleanup | None — test-only edits. | n/a |

No bridge protocol bump. No persisted JSON field changes. New `BridgeStatus` field count unchanged from the parent feature.

## 7. Acceptance criteria

The follow-up is done when **all** of:

1. **C1**: `scripts/fixthis-smoke.sh --check-staleness` runs the three-phase round-trip on a connected device and exits 0.
2. **C2**: `HostSourceFreshnessProbeTest` includes a `projectRoot misconfigured` test that asserts the distinct inconclusive reason. The probe never crashes when zero indexed files exist on host.
3. **C5**: All six advisories from §4.5 are addressed; `:fixthis-mcp:test` and `:fixthis-compose-sidekick:testDebugUnitTest` continue to pass.
4. **C6**: `CompactHandoffRendererTest` and `FeedbackQueueFormatterTest` (or new `*StaleRenderingTest.kt` if the existing tests are too dense to extend) verify stale-marker rendering for the three states.
5. Full 5-module regression passes (current baseline `tests=621 failures=0`).
6. Operational checklist (C7) actions completed by the user — tracked in plan file but not part of code acceptance.

## 8. Risks introduced by the follow-up itself

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| C6 stale marker visually clutters compact prompt for users with many false-positives (e.g., a freshly-checked-out branch where every file's mtime is "now") | medium | low–medium | Marker only shows when `stale == true`. A freshly-checked-out branch where nothing has been edited would have `false` (live line matches excerpt). The pathological case is a user who edited every file then ran `fixthis_status` — by definition they should see the warning. |
| C2 false negative (probe miscategorises a real staleness as misconfiguration) | low | medium | The guard fires only when *every* indexed file is missing. A partial drift (some files edited) hits the existing "$N of $M changed" path. |
| Smoke script (C1) leaves repository in a dirty state if it crashes | low | low | `trap` reverts file mtime on script exit. Test plan §4.1 acceptance includes a "kill mid-script" simulation. |

## 9. Future work (still deferred after this follow-up)

- **C3** — stat I/O optimisation. Trigger: `fixthis_status` p95 > 500 ms or index > 5000 entries.
- **C4** — clock-skew tolerance window. Trigger: first user report.
- **Build-time content digest** in `fixthis-build-info.json`. Tracked in parent spec §15.
- **CLI subcommand `fixthis stale`**. Tracked in parent spec §15.
- **Auto-reinstall opt-in**. Tracked in parent spec §15.
- **Index-aware new-file detection**. Tracked in parent spec §15.

## 10. Open questions

1. Should the C6 stale marker include the file path again (`⚠ stale: excerpt mismatch (sample/.../Foo.kt)`), or rely on the candidate line's existing path? — **Decision:** rely on the existing line; duplicating the path bloats the prompt.
2. Should the C2 misconfiguration detection log a warning to MCP stderr in addition to the JSON reason? — **Decision:** no. MCP is JSON-only; stderr noise risks pollutes agent transcripts. The reason string is the channel.
3. Should C5's "missing 1MB-cap test" also exercise the boundary (exactly 1 MB)? — **Decision:** yes. Off-by-one in `> MaxBytesToRead` vs `>=` is a likely failure mode.
