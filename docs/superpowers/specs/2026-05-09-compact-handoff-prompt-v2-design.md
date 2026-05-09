# Compact Handoff Prompt v2 — Clarity, Multi-Candidate, and Instance Disambiguation Design

**Date:** 2026-05-09
**Status:** Draft for review
**Related work:**
- `docs/superpowers/specs/2026-05-08-source-candidate-confidence-handoff-design.md` — v1 of the compact `src?` token format
- `docs/superpowers/plans/2026-05-08-source-candidate-confidence-handoff.md` — v1 implementation plan
- `docs/feedback-console-contract.md` — handoff schema doc to update
- `docs/mcp.md` — `SourceCandidate` field documentation to update

**Primary modules:** `fixthis-mcp` (CompactHandoffRenderer.kt, prompt.js, FormatterExtensions.kt)
**Secondary modules:** `fixthis-compose-core` (no schema change required; one optional matcher fix to populate `scoreMargin`)

---

## Purpose

The v1 compact handoff (shipped 2026-05-08) gave agents a single-line `src? file:line conf; why=...; risk=...` token and a top-level verification rule. Real-session evidence collected on 2026-05-09 (session `a8483865-fd81-4075-a313-2a44d7a6c1d4`) exposes three classes of friction that v2 must address:

1. **Token-shape friction.** `src?` reads like a question, `bounds=l,t,r,b` is unlabeled, and the `Node` prefix on every target line is unvarying noise.
2. **Information loss in the renderer.** `compactSourceLine` emits only `sourceCandidates[0]` and drops `matchedTerms`, `evidenceStrength`, `caution`, `ranking`, and the runner-up candidates — even though the data is already on `SourceCandidate`. Agents cannot judge whether the top hint is decisive or near a tie.
3. **No instance disambiguation.** When N markers on the same screen all resolve to the same composable signature (e.g. `MetricCard("summary")` rendered from a `LazyColumn`), the prompt shows N visually identical `src?` lines that differ only in `bounds`. Agents cannot tell whether (a) the matcher is collapsing distinct call sites, (b) the markers genuinely refer to one shared list-rendered call site, or (c) the markers are duplicates. The composition `path` field on `FixThisNode` is already collected (different leaf node IDs per instance) and is sufficient to derive an `instance i/N` index — but the renderer drops it.

The v2 design fixes prompt clarity, recovers the dropped metadata, and adds explicit instance + collision signals. It is a **renderer-side change** in the common case; the only runtime change is a small matcher fix to populate `SourceCandidate.scoreMargin`, which is already a declared field but is empirically `null` in all observed sessions.

## Concrete Evidence

Session `a8483865-fd81-4075-a313-2a44d7a6c1d4` (4 items, 1 screen `MainActivity`):

| item | comment | testTag | path leaf | bounds (top) | candidates emitted |
|---|---|---|---|---|---|
| 0 | "이건 레드" | `comp:MetricCard:summary` | `node:73` | 212 | 4 (HomeScreen.kt:44, MetricCard.kt:17, StudioHeader.kt:27, …) |
| 1 | "이건 블루" | `comp:MetricCard:summary` | `node:91` | 668 | 5 (HomeScreen.kt:44, MetricCard.kt:17, FixThisDemoData.kt:121, …) |
| 2 | "이건 보라" | `comp:MetricCard:summary` | `node:82` | 440 | 3 (HomeScreen.kt:44, MetricCard.kt:17, FixThisDemoData.kt:58) |
| 3 | "" | `comp:MetricCard:summary` | `node:73` | 212 | 4 (same as item 0) |

Three signals visible in the data but absent from the prompt:

1. **Per-item instance differs** (path leaves `node:73 / node:91 / node:82` are distinct).
2. **Items 0 and 3 share the same instance** (both `node:73`, same bounds) — this is a true duplicate, the user pinned the same card twice.
3. **3 + alternative candidates** carry real signal (e.g., `MetricCard.kt:17` is the composable definition vs `HomeScreen.kt:44` is the call site). v1 hides them entirely.

`scoreMargin` is `null` on every candidate despite the field existing on `SourceCandidate` since v1.

## Current State

`CompactHandoffRenderer.render(session)` (CompactHandoffRenderer.kt:4-68) produces:

```
# FixThis Feedback Handoff

Rule: source hints are candidates; verify screenshot, target, and code before editing.

- Package: `io.beyondwin.fixthis.sample`
- Feedback Items: `3`

Screen 4ce1eaa3-1e20-4da0-b3be-1a5c806fa934: MainActivity
screenshot: /…/4ce1eaa3-…-full.png

1. [marker 1] 이건 레드
   target: Node "comp:MetricCard:summary" bounds=28,212 - 692,419
   src? src/main/java/.../HomeScreen.kt:44 medium; why=tag+compTag+nearbyTag
…
```

Data already on the wire but **not** rendered in compact mode:

| DTO field | File | Current renderer use |
|---|---|---|
| `SnapshotScreenshotDto.width / height` | `SessionDtoModels.kt:63-64` | none |
| `SnapshotDto.activityName` | `SessionDtoModels.kt:41` | none (only `displayName`) |
| `AnnotationDto.sourceCandidates[1..]` | `SessionDtoModels.kt:77` | none (only `[0]`) |
| `AnnotationDto.severity` | `SessionDtoModels.kt:80` | none |
| `AnnotationDto.sequenceNumber` | `SessionDtoModels.kt:82` | sort key only, not displayed |
| `SourceCandidate.scoreMargin` | `Models.kt:106` | none (also `null` on wire — see Open Questions) |
| `SourceCandidate.matchedTerms` | `Models.kt:102` | none |
| `SourceCandidate.evidenceStrength` | `Models.kt:107` | none |
| `SourceCandidate.caution` | `Models.kt:109` | none |
| `SourceCandidate.ranking` | `Models.kt:105` | none |
| `FixThisNode.path` | `Models.kt:83` | none |
| `AnnotationDto.targetEvidence.sourceInterpretation` | `SessionDtoModels.kt:88` | none in compact (PRECISE only) |

## Goals

- Replace the bare `src?` token with a label readers parse without disambiguation.
- Render bounds with explicit axis labels and disambiguate them from the screen viewport.
- Render up to **N=3** source candidates per item (configurable constant), with renderer-derived `margin` between rank 1 and rank 2.
- Render an `instance i/N` label whenever multiple items on a screen share `(file:line, testTag)` — derived from `path` leaf grouping.
- Render a `note: duplicate of marker M` line when two items share both `(file:line, testTag)` AND identical `path` leaves.
- Preserve the v1 top-level rule, single-marker layout, overlap-group layout, screenshot reference, and JSON wire format.
- Keep the JS console (`prompt.js`) and Kotlin renderer (`CompactHandoffRenderer.kt`) byte-identical for the source-line portion (the existing parity rule from v1 stands).
- Populate `SourceCandidate.scoreMargin` in `SourceMatcher` (one-line fix) so the data field declared in v1 is no longer dead.

## Non-Goals

- Do not change `DetailMode.PRECISE` or `DetailMode.FULL` Markdown.
- Do not change `SourceCandidate` JSON schema (no new required fields).
- Do not change `FixThisNode.path` representation in the runtime (leaf IDs stay opaque numerics; instance index is derived in the renderer).
- Do not refactor `SourceMatcher` ranking logic or change confidence thresholds beyond populating `scoreMargin`.
- Do not capture pin click coordinates separately from selection bounds (would require a runtime-side change in the annotation pipeline; deferred).
- Do not embed source snippets in the prompt (deferred until token-budget impact is measured).
- Do not embed git commit / build hash (deferred; out of scope).
- Do not modify user app code in `sample/src/main` to add testTags.

## Design Principles

1. **Surface what exists before collecting more.** Every item except `scoreMargin` already has the data on the wire. Phase plan reflects this: renderer-only changes ship first.
2. **Single source of truth for token shape.** Kotlin and JS renderers must produce identical source-line bytes (existing v1 parity test extended in v2).
3. **Cap density.** A handoff prompt with 10 items × 5 candidates × 4 lines = unreadable. Hard cap candidates at 3, summarize matched terms in ≤4 tokens, never wrap.
4. **Diagnostic signals over heuristic confidence.** Show enough raw evidence (margin, runners-up, instance grouping) that an agent can disagree with the matcher, rather than relying on a single `medium` label.
5. **Backward compatible.** Existing session JSONs (including the legacy fixture in v1) must still render — fields are optional and absent fields render as the v1 line minus the new tokens.

## Output Format Specification

### v2 prompt grammar (BNF-ish)

```
prompt        = header rule package_line items_line "" screen_block+
header        = "# FixThis Feedback Handoff" "" 
rule          = "Rule: source hints are candidates; verify screenshot, target, and code before editing." ""
package_line  = "- Package: `" pkg "`"
items_line    = "- Feedback Items: `" count "`"
screen_block  = screen_header screenshot_line? viewport_line? activity_line? "" (overlap_block | item_block)+
screen_header = "Screen " short_id ": " display_name
short_id      = first 8 chars of UUID
screenshot_line = "screenshot: " path
viewport_line   = "viewport: " width "×" height          ; emitted iff screenshot has dims
activity_line   = "activity: " activity_name             ; emitted iff != display_name
overlap_block = "Overlap group " N " (resolve one marker at a time):" "" item_block+
item_block    = item_header target_line crop_line? source_block "" 
item_header   = N ". [marker " N "] " title              ; title may include severity prefix
target_line   = "  ui: " role " tag=" tag "  box=(" x1 "," y1 ")-(" x2 "," y2 ") [" w "×" h "]"
                 [ "  instance " i "/" total ]
                 [ "; targetRisk=overlap" ]
                 [ "; targetRisk=duplicate-of-marker-" M ]
crop_line     = "  crop: " path
source_block  = "  candidates:" candidate_line{1,3} caution_line?
candidate_line= "    ~ " file ":" line "  conf=" lvl "  margin=" margin "  matched=[" terms "]"
                                                                                ↑ first line only; runner-ups omit margin
caution_line  = "  note: " text                          ; emitted iff caution OR collision
```

### Worked example (replaces the "이건 레드/블루/보라" sample above)

```
# FixThis Feedback Handoff

Rule: source hints are candidates; verify screenshot, target, and code before editing.

- Package: `io.beyondwin.fixthis.sample`
- Feedback Items: `4`

Screen 4ce1eaa3: MainActivity
screenshot: /…/4ce1eaa3-…-full.png
viewport: 720×1480

1. [marker 1] 이건 레드
  ui: MetricCard tag=comp:MetricCard:summary  box=(28,212)-(692,419) [664×207]  instance 1/3
  candidates:
    ~ src/main/java/.../HomeScreen.kt:44   conf=medium  margin=0.30  matched=[testTag, compTag, nearbyTag]
    ~ src/main/java/.../MetricCard.kt:17   conf=medium
    ~ src/main/java/.../StudioHeader.kt:27 conf=low
  note: 3 markers map to same call site — likely list-rendered; disambiguate by instance index

2. [marker 2] 이건 블루
  ui: MetricCard tag=comp:MetricCard:summary  box=(28,668)-(692,875) [664×207]  instance 2/3
  candidates:
    ~ src/main/java/.../HomeScreen.kt:44   conf=medium  margin=0.30  matched=[testTag, compTag, nearbyTag]
    ~ src/main/java/.../MetricCard.kt:17   conf=medium
    ~ src/main/java/.../FixThisDemoData.kt:121 conf=low

3. [marker 3] 이건 보라
  ui: MetricCard tag=comp:MetricCard:summary  box=(28,440)-(692,647) [664×207]  instance 3/3
  candidates:
    ~ src/main/java/.../HomeScreen.kt:44   conf=medium  margin=0.30  matched=[testTag, compTag, nearbyTag]
    ~ src/main/java/.../MetricCard.kt:17   conf=medium

4. [marker 4] (no request provided)
  ui: MetricCard tag=comp:MetricCard:summary  box=(28,212)-(692,419) [664×207]; targetRisk=duplicate-of-marker-1
  candidates:
    ~ src/main/java/.../HomeScreen.kt:44   conf=medium  margin=0.30  matched=[testTag, compTag, nearbyTag]
```

Five new signals in the worked example, all derivable from existing wire data:

1. `viewport: 720×1480` — from `screen.screenshot.width/height`.
2. `box=(...)-(...)` with explicit `[w×h]` — replaces unlabeled `bounds=l,t-r,b`.
3. `instance 1/3` — derived by grouping items on same screen with same `(file:line, testTag)` and stable-ordering by `path` leaf string.
4. `candidates:` block with up to 3 entries and computed `margin` for rank 1.
5. `note:` line on collision; `targetRisk=duplicate-of-marker-N` when two items share both source and path leaves.

### Field-by-field rationale

| New field | Source | Why it helps the agent |
|---|---|---|
| `viewport: WxH` | `SnapshotScreenshotDto.width/height` | Lets the agent interpret pixel bounds (e.g. is x=692 right edge or middle?) without opening the screenshot. |
| `ui: <role> tag=<tag>` | merged from `selectedNode.role` + `selectedNode.testTag` | Replaces unvarying `Node "tag"` prefix; aligns the high-signal token (testTag) at end. |
| `box=(x,y)-(x,y) [w×h]` | `target.boundsInWindow` | Explicit axes; `[w×h]` is a one-multiplication summary the agent uses for size/aspect reasoning. |
| `instance i/N` | grouping pass over items per screen | Fastest path for the agent to disambiguate list-rendered widgets without re-reading source. |
| `candidates:` block (≤3) | `sourceCandidates.take(3)` | Reveals runner-ups (often the composable definition vs the call site), which is what an agent needs to choose the right edit point. |
| `margin=` on rank 1 | `cand[0].scoreMargin` if non-null else `cand[0].score − cand[1].score` | Quantifies decisiveness; agent can heuristically downgrade `medium + margin=0.02` mentally. |
| `matched=[...]` | `cand[0].matchedTerms` (truncated to 4) | What text actually matched — distinguishes a tag-match from a coincidental literal-match. |
| `note: caution` | `cand[0].caution` | Already-computed warning the matcher emitted; v1 dropped it in compact. |
| `note: collision` | derived per-screen | Tells agent `multiple markers → same call site` is a known signal, not a bug. |
| `targetRisk=duplicate-of-marker-N` | derived per-screen | Surfaces true marker duplication so the agent does not double-resolve. |

### Token shape changes

| v1 | v2 | Reason |
|---|---|---|
| `src?` | `~ ` (tilde + space) at start of each candidate line, under a `candidates:` header | `~` is conventional for "approximate"; header carries the meaning so the per-line marker can be lightweight. |
| `bounds=L,T - R,B` | `box=(L,T)-(R,B) [W×H]` | Matches typical `(x,y)` notation and adds size. |
| `target: Node "tag"` | `ui: <role> tag=<tag>` | Drops unvarying `Node` noise; `tag=` is parseable. |
| `confidence` lowercase | unchanged | Same as v1 contract. |
| `why=` token | `matched=[...]` | Renamed for clarity; `matched` describes what was found, `why` was meta. |
| `risk=` token | `targetRisk=` (target side) and `note: ...` (source side) | Splits target-vs-source risks; v1 conflated them. |

The `Rule:` line, screen header, screenshot line, overlap-group block, and crop line are unchanged.

## Data Sources and Derivations

### From DTOs (no runtime change)

- `SnapshotScreenshotDto.width / height` → `viewport:`
- `SnapshotDto.activityName` → `activity:` (suppressed if equals `displayName`)
- `AnnotationDto.sourceCandidates` → `candidates:` block (slice 0..3)
- `AnnotationDto.selectedNode.path` → input to instance grouping
- `AnnotationDto.selectedNode.role` and `.testTag` → `ui:` line
- `AnnotationDto.severity` → if `HIGH`, prepend `[!]` to title (low-noise visual prefix)

### Renderer-derived

- `instance i/N`: per screen, group items by `(top_candidate.fileWithLine, selectedNode.testTag)`, sort each group by `selectedNode.path` joined string, assign `i = ordinal+1`, `N = group.size`. Suppressed for groups of size 1.
- `margin` on rank 1: prefer `cand[0].scoreMargin` if non-null; else compute `cand[0].score − cand[1].score`; else omit.
- `note: collision`: emitted on first item of each `instance` group when group size ≥ 2 and items are NOT in an overlap group (overlap and collision are different signals — overlap = visually adjacent on screen, collision = same source mapping).
- `targetRisk=duplicate-of-marker-N`: when two items share BOTH `(file:line, testTag)` AND identical `path` leaves AND identical `boundsInWindow`. The earlier item (by `sequenceNumber`) is the canonical, later items get the marker reference.

### Runtime change (one line in matcher)

`SourceMatcher.toCandidate()` (SourceMatcher.kt:437-465) currently does not populate `scoreMargin`. The fix is to compute it from the sorted score list inside the call site that produces `List<SourceCandidate>` and assign to rank 1's candidate. This is additive and safe; no contract change.

## Acceptance Criteria

These are the testable behaviors the implementation plan must produce. Each maps to one or more tests in `CompactHandoffRendererTest.kt` and `PromptParityTest.kt`.

### AC-1: Token-shape changes are byte-stable

Given a fixture session with one item, one screenshot (with `width=720, height=1480`), and one source candidate, the rendered output MUST contain:

- `viewport: 720×1480` on the screen header block
- `ui: ` (not `target: Node`) at the start of the target line
- `box=(L,T)-(R,B) [W×H]` (not `bounds=L,T-R,B`)
- `candidates:` (not `src?`) heading the source block
- `~ <file>:<line>  conf=<lvl>  margin=<value>  matched=[...]` for rank 1

### AC-2: Multi-candidate rendering caps at 3 and orders by score

Given an item with 5 source candidates ranked by score, the prompt MUST render exactly 3 `~ ` lines in input order, and runner-up lines (rank 2, 3) MUST NOT include `margin=` or `matched=`.

### AC-3: Instance disambiguation for list-rendered widgets

Given 3 items on the same screen with identical `(top_candidate.fileWithLine, selectedNode.testTag)` and distinct `path` leaves, the prompt MUST:

- emit `instance 1/3`, `instance 2/3`, `instance 3/3` on the three target lines (in `path`-leaf string order)
- emit a single `note: ... list-rendered ...` on the first item only

### AC-4: Duplicate marker detection

Given 2 items on the same screen with identical `(top_candidate.fileWithLine, selectedNode.testTag, path leaves, boundsInWindow)`, the second item MUST carry `targetRisk=duplicate-of-marker-N` referring to the earlier item's marker number.

### AC-5: Backward-compat with v1 wire data

Given the v1 prompt-parity fixture (`fixthis-mcp/src/test/resources/parity/session.json` from the v1 plan), the renderer MUST produce a v2 prompt that:

- contains the `Rule:` line verbatim
- contains the package + count lines
- contains every original `screenshot:` path
- contains a `~ ` candidate line for every item (no nulls)
- emits no `margin=` token where `scoreMargin` is null AND there is only one candidate

### AC-6: Kotlin/JS parity for source lines

Existing v1 `PromptParityTest` MUST be extended so the new candidate lines, `instance` token, collision `note:`, and `targetRisk=duplicate-...` all produce byte-identical output between `CompactHandoffRenderer.kt` and `prompt.js`. Bounds-format divergence remains as documented in v1.

### AC-7: scoreMargin is populated in matcher output

After the matcher fix, `SourceMatcher.match(...)` MUST return rank-1 candidates with non-null `scoreMargin` whenever there is at least one rank-2 candidate. Existing tests that assert `scoreMargin == null` (if any) must be updated.

### AC-8: Token budget regression guard

The v1 plan introduced a prompt-size budget test. v2 MUST extend it: a 5-item screen with 3 candidates each MUST fit under `1.5×` the v1 5-item-with-1-candidate baseline (rough budget; refined in plan).

## Migration and Backward Compatibility

- **JSON schema:** unchanged. Existing sessions on disk continue to deserialize.
- **`DetailMode.PRECISE` and `DetailMode.FULL`:** unchanged.
- **v1 parity fixture:** repurposed as a v1→v2 migration test (renders both formats; v2 strictly contains v1's content modulo tokens).
- **Console UI behavior (`prompt.js`):** `currentAnnotationsPromptCompact()` keeps the same function signature and return type; only the body changes.
- **Agents already consuming v1 prompts:** the v2 prompt is strictly more verbose and uses different but equally parseable tokens. No breaking change for human agents; tool-using agents that parsed `src? ` literally will need a one-line update (documented in `feedback-console-contract.md`).

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| 3-candidate cap hides a relevant 4th candidate | Cap is configurable in renderer (constant); default 3 chosen for token budget. AC-8 keeps the budget honest. |
| Instance grouping by `path` leaf string is fragile if runtime changes path representation | Grouping is renderer-side; if path representation changes, only the renderer needs an update. The grouping function is unit-tested independently. |
| Collision `note:` over-fires for legitimate single-call-site list rendering | That IS the correct case to surface — agent benefits from knowing it's list-rendered. Wording emphasizes "likely" not "bug". |
| Renderer-computed margin diverges from any future matcher-emitted margin | After matcher fix (AC-7), renderer prefers wire `scoreMargin` over computed; falls back to computed only when null. |
| JS/Kotlin parity for instance grouping is fragile (sort stability differs) | Use string sort with explicit ordering rule; parity test fixture exercises ≥3 instances with adjacent path leaves. |
| Severity prefix `[!]` clutters titles for users who do not set severity | Severity is suppressed for the default `MED`; only `HIGH` shows `[!]`. (`LOW` shows nothing.) |
| `activity:` line confuses users when `activityName == displayName` | Suppress when equal; only emit when they differ. |

## Open Questions

1. **Should `matched=[...]` reuse v1 reason tokens (`tag`, `compTag`, `nearbyTag`)?** Proposed: yes — keep the v1 `FIXTHIS_REASON_TOKEN_MAP` and `reasonTokenFor()` mappings exactly; merge with `matchedTerms` as `[testTag:summary, compTag:MetricCard, nearbyTag]`. Confirms in plan Phase 2.
2. **Should we move `note:` lines under each item OR under the screen?** Proposed: under each item (closer to the data it qualifies); collision note under the FIRST item of each group. Alternative: a screen-level `notes:` block at the top of each screen. Plan picks per-item; if review feedback prefers screen-level, swap is local to one renderer function.
3. **`scoreMargin` matcher fix — landed in v2 plan or split out?** Proposed: include as Phase 0 of v2 plan (one-line change + one test). Splitting it would make v2 ship with a `margin=` token that is computed-only, never wire-sourced — wasteful given the fix is trivial.
4. **Crop image path — keep current "always emit" behavior?** Proposed: yes. The `crop:` line was added in v1 and is unchanged.
5. **Pin-click coordinates** (where the user actually tapped vs the resolved selection box) — defer entirely. Capturing this requires a runtime-side change in `AnnotationDto`. Open follow-up issue.

## Success Metrics

- Prompt verifier (manual review of 5 sessions including the `a8483865-fd81-4075` example) shows that for collision cases, an agent reading only the prompt can correctly state "this is list-rendered, edit the call site at line 44 and disambiguate by instance index" without opening the source.
- Token budget: ≤1.5× v1 baseline for typical 5-item screens.
- All v1 tests pass with v2 renderer in place. v2-specific tests (AC-1 through AC-8) all pass.
- No regressions in `FeedbackQueueFormatterTest`, `FeedbackSessionStoreTest`, or `FixThisMarkdownFormatterTest` (PRECISE/FULL untouched).

---

## Appendix A: Token mapping reference (unchanged from v1)

```
selected text                               → text
selected contentDescription                 → contentDescription
selected testTag                            → tag
selected testTag convention composable      → compTag
selected role                               → role
nearby text                                 → nearbyText
nearby contentDescription                   → nearbyContentDescription
nearby testTag                              → nearbyTag
nearby role                                 → nearbyRole
activity                                    → activity
selected stringResource                     → stringRes
arbitrary literal                           → literal
legacy fallback                             → legacy
```

These remain the canonical reason tokens. v2 reuses them inside `matched=[...]`.

## Appendix B: Renderer pseudocode for grouping

```kotlin
data class InstanceKey(val fileLine: String, val testTag: String?)

fun computeInstances(items: List<AnnotationDto>): Map<String, InstanceLabel> {
    return items
        .groupBy { item ->
            InstanceKey(
                fileLine = item.sourceCandidates.firstOrNull()?.fileWithLine() ?: "?",
                testTag  = item.selectedNode?.testTag,
            )
        }
        .filter { (_, group) -> group.size >= 2 }
        .flatMap { (_, group) ->
            val ordered = group.sortedBy { it.selectedNode?.path?.joinToString("/") ?: "" }
            ordered.mapIndexed { idx, item ->
                item.itemId to InstanceLabel(index = idx + 1, total = ordered.size)
            }
        }
        .toMap()
}

data class DuplicateKey(val fileLine: String, val testTag: String?, val pathLeaves: List<String>, val bounds: FixThisRect)

fun computeDuplicates(items: List<IndexedItem>): Map<String, Int /* refers-to marker */> {
    // Return mapping itemId -> earlier marker number
}
```

(Full implementation in plan Phase 3.)
