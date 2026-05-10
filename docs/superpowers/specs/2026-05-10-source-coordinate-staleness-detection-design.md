# Source Coordinate Staleness Detection — Design

**Date:** 2026-05-10
**Status:** Approved (pending implementation)
**Owner:** kws
**Related plan:** `docs/superpowers/plans/2026-05-10-source-coordinate-staleness-detection.md`

---

## 1. Problem

FixThis ships source coordinates (`SourceCandidate.file` / `line`) to AI agents so they can edit the right file when responding to feedback items. The chain is:

```
host code  →  Gradle (build time)  →  fixthis-source-index.json (APK asset)
                                                ↓ installDebug
                                              device APK
                                                ↓ runtime read by sidekick
                                              bridge protocol
                                                ↓ ADB
                                              MCP host (fixthis-mcp)
                                                ↓ tool result
                                              AI agent  →  edits host code
```

Coordinates are only meaningful while every link in the chain points at the same revision. The chain breaks when one specific link is missed: **the user edits host code but does not run `installDebug`**. Then:

- The **device** is still running the old APK.
- The **sidekick** still serves the old `fixthis-source-index.json` (and caches it in memory: `BridgeServer.kt:364 cachedSourceIndexResult`).
- The **MCP** delivers `SourceCandidate` rows with old `file:line` pairs.
- The **agent** edits old line numbers, blowing away or corrupting work the user just did, or "fixes" a function that no longer exists at that line.

### 1.1 Symptom catalog

| Failure | What the agent sees | What actually happens |
|---|---|---|
| Composable renamed | `file: Foo.kt, line: 42, symbols: ["OldName"]` | Foo.kt:42 is now `NewName` — the agent edits the wrong symbol or fabricates a function. |
| File split / moved | `file: SignInScreen.kt, line: 88` | The screen now lives in `auth/SignInScreen.kt`; the original path is empty. |
| Lines added above target | `file: Settings.kt, line: 60` | The intended block is now at line 75. The agent edits an unrelated `Text(...)` at line 60. |
| Whole feature added | `Save to MCP` shows yesterday's UI text | Agent reasons over text that no longer exists; verifications loop. |

These are silent failures. There is no current signal back to the agent that the coordinates are doubtful, so the agent treats every match as authoritative.

### 1.2 Why this is high-impact

- The most common workflow for this project is "edit a Composable, run feedback, edit again." The user *will* forget `installDebug` regularly.
- Failures here are not loud (no exception, no missing file). They produce **bad code edits** that may even pass type-check.
- The agent has no other reliable signal — `fixthis_verify_ui_change` checks live screen text, not source freshness, and only fires when the agent already chose to verify.

## 2. Goals

- Give the agent a **per-candidate** signal (`stale: true | false | null`) so it can downrank or skip stale coordinates and look the symbol up by name instead.
- Give the agent a **global** signal on `fixthis_status` (`installStale`, `installStaleHint`, `newerSourceFiles`) so it can decide upfront to ask the user to reinstall.
- Add **zero** Gradle plugin coupling. The check must work for any FixThis-enabled debug build that's already shipping.
- Stay **non-breaking**: nullable fields, `kotlinx.serialization` defaults; older sidekicks/older clients gracefully observe nulls.

## 3. Non-goals

- Auto-installing the debug APK from MCP. (Out of scope; environment / signing assumptions vary too much for external projects.)
- Flagging *new* host source files that aren't in the index yet. (Out of scope; covered indirectly by per-candidate "file not found" path.)
- Comparing live Compose semantics text against source-index text. (Different problem; noisy.)
- Changing the bridge protocol version or any persisted MCP JSON field names (`items`, `screens`, `itemId`, `screenId`).
- Wiring `stale` into the `FeedbackQueueFormatter` Markdown output. (Tracked separately — see §11.)

## 4. Alternatives considered

### 4.1 Hash comparison (rejected)

Have Gradle bake a digest into `fixthis-build-info.json`, sidekick expose it, MCP compare against the latest host-built digest.

- ✗ Requires Gradle plugin + sidekick + MCP coordination.
- ✗ The MCP host doesn't generally know **where** the latest build artifact lives — `build/generated/fixthis/<variant>/...` differs per module and per project.
- ✗ Tells you the build is stale, but not **which coordinates** to distrust.

### 4.2 Auto-reinstall (rejected for V1)

Run `installDebug` automatically before `fixthis_open_feedback_console` or as part of `fixthis run`.

- ✗ External projects have unknown variant names, signing configs, and Gradle wrappers.
- ✗ Side effect — touches device state without explicit user intent.
- Could be revisited as opt-in, but not the path to robustness.

### 4.3 Live semantics vs index (rejected)

Compare on-screen text against `SourceIndex.entries[*].text` to detect drift.

- ✗ Many entries have no text (containers, icons).
- ✗ Strings localize, change at runtime, etc. — high false-positive rate.
- ✗ Doesn't tell you about renamed Composables.

### 4.4 Per-candidate excerpt verification + APK install-time mtime check (chosen)

Two host-side checks running independently:

- **Per-candidate**: Re-read the host file at `(file, line)`, compare the trimmed live line against the index `excerpt` (also stored trimmed by the Gradle plugin).
- **Global**: Compare each indexed file's `lastModified()` against the device APK's `PackageManager.lastUpdateTime`. Count newer files.

Why this wins:

- ✓ **No build-system coupling.** The sidekick already knows its own install time via `PackageManager`. The host already knows where its source files live.
- ✓ **Per-candidate granularity.** Even a partial drift (e.g., agent edited only one screen) lights up only the affected candidates.
- ✓ **Self-correcting affordance.** A `stale: true` candidate tells the agent: "do not edit by file:line; locate the symbol by name first."
- ✓ **Cheap.** Stat + read-one-line per candidate. Source indexes are O(100s) of files in real projects.

## 5. Architecture

```
                         host (`fixthis-mcp` process)
   ┌────────────────────────────────────────────────────────────┐
   │                                                            │
   │  fixthis_status              feedback / capture / read     │
   │       │                              │                     │
   │       ▼                              ▼                     │
   │  HostSourceFreshnessProbe   TargetEvidenceService          │
   │       │                              │                     │
   │       │                  SourceCandidateStalenessChecker   │
   │       │                              │                     │
   │       │                              ▼                     │
   │       │                    per-candidate stale flag        │
   │       │                                                    │
   │       └─── installStale signal ──► tool result             │
   │                                                            │
   └─────────▲──────────────────────────────────────────────────┘
             │ ADB bridge (existing)
   ┌─────────┴──────────────────────────────────────────────────┐
   │ Android sidekick                                           │
   │  • BridgeStatus.installEpochMillis ← pm.lastUpdateTime     │
   │  • readSourceIndex (existing, cached)                      │
   └────────────────────────────────────────────────────────────┘
```

### 5.1 Layering principle

- The **sidekick** is a dumb data source — it adds one nullable field (`installEpochMillis`) to the existing status payload. It does **not** make freshness decisions.
- The **MCP** is the policy layer — both checks run there. The MCP knows the `projectRoot` (host CWD) and can stat the live source.
- No protocol bump, no new endpoint. Both checks reuse `bridge.status()` and `bridge.readSourceIndex()`.

### 5.2 Trust model

The chosen comparison is `live source line == index excerpt`. This trusts:

- **Gradle's excerpt extraction** is deterministic for a given source line (it's `line.trim()` — see `GenerateFixThisSourceIndexTask.kt:284`).
- **The user has not done a partial sync** where the source-index is from a newer build than the source files. (Would be unusual; happens only if someone copies the APK from elsewhere. Out of scope.)

If the live line matches the excerpt, the index entry is at minimum *line-accurate*. It does not guarantee semantic correctness, but it eliminates the most common silent failure (file/line drift).

## 6. Sidekick changes

### 6.1 `BridgeStatus.installEpochMillis` (one new nullable field)

```kotlin
@Serializable
data class BridgeStatus(
    // existing fields…
    val installEpochMillis: Long? = null,
)
```

`AndroidBridgeEnvironment.status()` populates it via:

```kotlin
private fun readInstallEpochMillis(): Long? = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
}.getOrNull()
```

`runCatching` because the call can throw on package-not-found in pathological cases (uninstalled while we're being queried) — we degrade to `null`, which the MCP treats as "inconclusive" (see §7.2).

### 6.2 Why `lastUpdateTime`, not `firstInstallTime`

- `lastUpdateTime` advances on every `installDebug` (or any APK reinstall). This is the precise event that matters.
- `firstInstallTime` only changes on uninstall+reinstall, which is too coarse.

### 6.3 Why nullable

- An older sidekick that doesn't ship this change still emits a valid `BridgeStatus` JSON; deserialization on the host produces `installEpochMillis = null`.
- The MCP treats `null` as "skip global check, render inconclusive reason" rather than asserting. Keeps mixed-version setups (host upgraded, device not) safe.

### 6.4 Why no Gradle change

We chose `pm.lastUpdateTime` over a build-time digest precisely because it removes the need to write/read a digest from `fixthis-build-info.json`. The sidekick already has `Context`; everything else falls out.

If we ever want a stronger signal (e.g., agent should refuse to edit even if mtime is the same but content drifted), a content digest baked at build time is the natural follow-up. Not now.

## 7. MCP changes

### 7.1 `SourceCandidate` schema additions

```kotlin
@Serializable
data class SourceCandidate(
    // existing fields…
    val stale: Boolean? = null,
    val staleReason: String? = null,
)
```

| `stale` | meaning | `staleReason` |
|---|---|---|
| `null` | not verifiable (no excerpt, no line, e.g. XML resource entry) | `null` |
| `false` | live line matches index excerpt, coord is line-accurate | `null` |
| `true` | line differs, file is missing, line out of range, or path escapes root | one of: `excerpt mismatch`, `file not found on host`, `line out of range`, `path escapes project root: <path>`, `file too large to verify` |

`stale` is intentionally **not** rolled into existing `riskFlags` / `caution`. Those describe *match quality* (was the matching algorithm confident?). `stale` describes *coordinate freshness* (does this file:line still exist on the host?). They are independent axes; a candidate can be both `AMBIGUOUS` and stale.

### 7.2 `fixthis_status` schema additions

```jsonc
{
  // existing fields preserved…
  "installStale": false,                     // boolean, never null
  "installStaleReason": "12 of 134 indexed source files changed after the installed APK was built",
  "installStaleHint": "Run ./gradlew :app:installDebug then cold-launch the app",
  "installedAtEpochMillis": 1700000000000,   // present iff sidekick supplied it
  "newerSourceFiles": [                      // up to 3 example paths
    "sample/src/main/kotlin/com/example/SignInScreen.kt"
  ]
}
```

`installStale` is the load-bearing field — agents key off it. `installStaleReason` is the human-readable summary (also used on inconclusive paths). `installStaleHint` is added **only** when `installStale = true` to keep the response compact otherwise.

### 7.3 Inconclusive cases (`installStale = false`, but with a reason)

- Sidekick is older and omitted `installEpochMillis` → `reason: "install epoch unavailable; older sidekick"`.
- Source index is unavailable on the device → `reason: "source index not available"`.
- Reading the source index failed → `reason: "source index could not be read"`.

In all three cases, freshness cannot be evaluated; the boolean defaults to `false` (don't false-alarm) but the reason discloses why. The agent can treat this as "I don't know — proceed cautiously" rather than "definitely fresh."

## 8. Data flow — `fixthis_status` example

```
agent ──► fixthis_status(packageName)
                                │
            FixThisTools.call ──┤
                                │
                bridge.status() ──► sidekick ──► BridgeStatus { installEpochMillis = T }
                                │
                bridge.readSourceIndex() ──► SourceIndex { entries[...] }
                                │
                HostSourceFreshnessProbe.evaluate(index, T)
                                │
                                ├─ files = entries.map { it.file }.distinct()
                                ├─ for each file:
                                │    resolve under projectRoot, must be inside root
                                │    if file.lastModified() > T: count it
                                ├─ if count > 0:
                                │    reason = "$count of $total indexed source files changed…"
                                └─ return HostSourceFreshnessResult(...)
                                │
                build response JSON with installStale*, installedAtEpochMillis, newerSourceFiles
                                │
            ◄──── tool result
```

## 9. Data flow — per-candidate validation

```
console.AddAnnotation
   │
   ▼
TargetEvidenceService.buildFeedbackItem(...)
   │
   ▼
sourceCandidatesFor(sourceIndex, selectedNode, nearbyNodes, activityName)
   │
   ├─ raw = SourceMatcher.match(...)            // existing matching
   │
   └─ stalenessChecker.annotate(raw, sourceIndex)
        │
        ├─ key candidates by (file, line) → entry lookup in sourceIndex
        ├─ for each candidate:
        │    if entry.excerpt blank or candidate.line null → leave stale = null
        │    resolve candidate.file under projectRoot
        │    read line N, trim → liveLine
        │    if liveLine == entry.excerpt.trim() → stale = false
        │    else → stale = true with specific reason
        └─ return list with annotations
```

## 10. Edge cases & decisions

| Case | Decision | Rationale |
|---|---|---|
| Index entry has `excerpt = null` (some XML entries can) | leave `stale = null` | We have nothing to compare. Don't false-alarm. |
| Candidate `line` is `null` (XML resource match) | leave `stale = null` | Same as above. |
| Path traversal in entry (`../foo.kt`) | `stale = true`, reason "path escapes project root" | Defensive — never read outside `projectRoot`. |
| File symlink → out of root | resolved canonical path is checked → "path escapes" | Same defensive rule. |
| File > 1 MB | `stale = true`, reason "file too large to verify" | Avoid reading huge files for one line; large source files are anomalous and warrant attention anyway. |
| Trailing whitespace / CRLF differences | both sides `trim()` before compare | Excerpt is stored trimmed; live line is trimmed. CRLF gets normalized by `bufferedReader().readLine()`. |
| Index has thousands of entries | acceptable; stat + 1 line read each, ~ms total | Real projects ≪ 10k entries. Optimize when seen. |
| Indexed file present but with a fresh mtime | counted in `newerFileCount`; per-candidate may still be `false` if the changed lines don't include this candidate | These are independent signals by design — see §11. |
| `projectRoot` is wrong (CLI invoked from elsewhere) | every file appears missing → all candidates stale, `installStale = true` with high count | Document in `docs/troubleshooting.md`. No automatic detection — too many false positives. |
| Clock skew between APK install timestamp and file mtime | both come from the same host (host clock + ADB pull), so divergence is rare | If it surfaces in practice, add tolerance window (e.g., 5 s). Not now. |

## 11. Why two signals, not one

- **Per-candidate** is the most actionable: tells the agent which row to distrust.
- **Global** is the proactive heads-up: tells the agent (or user) before they request any feedback that the install is out of date.

They're not redundant:

- A user could edit only file A, then ask about feedback that points to file B. Per-candidate marks A's candidates `stale = true` if any. Global marks `installStale = true` so the agent can suggest reinstall up front, even when no candidate has been retrieved yet.
- A user could rename a Composable in file A but not save, then build/install, then ask. Live source matches the saved version; per-candidate sees agreement. Global sees no newer files. Both correctly say "fresh." (No false alarm.)

## 12. Compatibility

| Component | Behavior with old peer |
|---|---|
| Old sidekick (no `installEpochMillis`) + new host | `installStale = false`, `installStaleReason = "install epoch unavailable; older sidekick"`. Per-candidate check still works (host-only). |
| New sidekick + old host | Old host ignores the new `installEpochMillis` field (kotlinx.serialization `ignoreUnknownKeys`). No degradation. |
| Old `SourceCandidate` consumers (e.g. external scripts reading saved JSON) | New `stale`/`staleReason` are additive, defaulted; old consumers ignore them. |
| Saved feedback session JSON files (`.fixthis/...`) | Forward-compatible: writing `stale = null` produces the same JSON as before because `kotlinx` skips defaults. |

No bridge protocol version bump. No persisted JSON field rename. Both are explicit constraints from `AGENTS.md`.

## 13. Acceptance criteria

The implementation is done when **all** of the following hold:

1. Editing a Composable name, then calling `fixthis_status` without reinstalling, returns:
   - `installStale: true`
   - `installStaleReason` mentioning the modified file count
   - `installStaleHint` advising `:app:installDebug`
   - `newerSourceFiles` containing at least the edited file's relative path
2. Calling `fixthis_capture_screen` + reading feedback for the same screen returns `SourceCandidate` rows where the candidate pointing at the renamed function has `stale: true` and `staleReason: "excerpt mismatch"`.
3. After running `:app:installDebug` and cold-launching the app:
   - `fixthis_status` returns `installStale: false`, `installStaleReason: null`, no hint.
   - The same `SourceCandidate` query returns `stale: false`.
4. Running against an older sidekick (locally simulated by stubbing `installEpochMillis = null` in tests) returns `installStale: false` with the inconclusive reason.
5. All existing module test suites pass:
   ```
   ./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test \
             :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
   ```
6. No persisted JSON in `.fixthis/` written before this change fails to load. (Existing serialization tests cover this; the implementation plan adds explicit "legacy JSON" tests for the new fields.)

## 14. Risks & mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `projectRoot = File(".").canonicalFile` is the wrong directory when the CLI is invoked oddly | medium | high (every candidate appears stale) | Documented in `docs/troubleshooting.md`. The `installStaleReason` already contains the count; "12 of 12" is a strong tell. Optional follow-up: log a warning when 100% of indexed files appear missing. |
| Excerpt comparison normalization drift (Gradle changes how excerpts are stored) | low | medium (false positives or false negatives) | Task 3 integration test exercises a real `excerpt` produced by the same code path the Gradle plugin uses. Any Gradle-side normalization change must update both ends in the same PR. |
| Stat I/O latency on `fixthis_status` | low | low | Capped by index size; no observed projects > 10k entries. If it becomes a problem, cache by `installEpochMillis` (re-stat only when the epoch changes). |
| Sidekick change requires sample app rebuild | high (this is the whole point) | n/a | Documented; the project's existing `fixthis-smoke.sh` already rebuilds. |
| `getPackageInfo` deprecation on SDK 33+ | low | low | `runCatching` swallows; if it ever throws, we get `null` and the global check goes inconclusive. SDK-conditional override is a future polish. |

## 15. Future work (deliberately deferred)

- **Build-time content digest** in `fixthis-build-info.json` for a stronger global signal that doesn't depend on file mtime accuracy. Useful for environments where mtimes get rewritten (CI caches, source control checkouts).
- **`stale` rendering in `FeedbackQueueFormatter`** so that `fixthis_read_feedback` Markdown output marks stale candidates inline (e.g., `~~Foo.kt:42~~ (stale: excerpt mismatch)`). The data is already exposed; this is purely a formatter change.
- **CLI subcommand `fixthis stale`** that runs both checks against a chosen package and prints a report — useful for human triage outside an MCP session.
- **Auto-reinstall opt-in** on `fixthis_open_feedback_console` behind an explicit flag (`--auto-install`).
- **Index-aware new-file detection** that walks the `sample/src` tree (or a Gradle-declared source root) and reports files not present in the index. Catches the "added a new screen" case that the current design misses.

## 16. Open questions

1. Should `fixthis_status` populate `installStaleHint` with module-aware text, e.g. `"./gradlew :myapp:installDebug"`? — Out of scope for V1; we don't have a reliable way to recover the user's variant from the MCP context. Punted to follow-up.
2. Should saved feedback items capture `stale` at the time of capture, or recompute on every read? — Recompute on every read (consistent with the current behavior of recomputing `targetEvidence` from live sources). Saved JSON does not include stale flags.
3. Should the `stale = true` flag downrank candidates in `SourceMatcher` (i.e. push them below clean ones)? — Out of scope for V1; the agent can do this on the consumer side. Worth revisiting once we have feedback from real sessions.
