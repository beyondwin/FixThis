# Console Routes Test Decomposition — Design Spec

> Status: Draft (2026-05-14)
> Owner: Console / MCP server
> Companion plan: [`docs/superpowers/plans/2026-05-14-console-routes-test-decomposition-implementation.md`](../plans/2026-05-14-console-routes-test-decomposition-implementation.md)

## 1. Problem

The single test file
`fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
has grown to **2897 lines / 100 `@Test` methods** while the production code it
exercises has already been split across many route modules:

> The per-row counts below are approximate (some prefixes overlap module
> boundaries). The plan's per-test ledger is the authoritative partition;
> see §3.1 for the agreed per-cluster totals.

| Production file | Concern | Tests still living in `ConsoleFeedbackItemRoutesTest.kt` |
|---|---|---|
| `FeedbackItemRoutes.kt` | PUT/POST/DELETE on `/api/items/*` | ~18 |
| `SessionRoutes.kt` | `/api/sessions`, `/api/session`, `/api/session/open\|close` | ~8 |
| `HandoffPreviewRoutes.kt` + `MarkHandedOffRoutes.kt` + agent flow | `/api/handoff/preview`, `/api/items/handed-off`, `/api/agent/handoff`, `agentHandoffs*` | ~19 |
| `FeedbackConsoleAssets.kt` | served `index.html` / inlined JS contract | **34** (`consoleHtml*`, `consoleUsesOptionASelect*`) |
| (cross-cutting) | sessions polling, mutation lock, merge logic, history pips | ~13 |
| ETag | `apiSessions*Etag`, `apiSession*Etag` | 6 |
| Navigation | `navigationApi*` | 2 |

The `consoleHtml*` / `consoleUsesOptionASelect*` cluster (34 tests after
partitioning, including `consoleHtmlContainsSessionsPolling` and
`consoleHtmlDeclaresPollingGlobals` which match the `^consoleHtml` rule first)
does not exercise routes at all — they assert that the static `index.html`
payload (and the inlined `app.js` it serves) contain expected markers, IDs, JS
function signatures, and CSS class names. They live with
`FeedbackConsoleAssets.kt` / `console/index.html` / bundled `console/app.js`.

> **Naming boundary (avoid confusion with the existing `ConsoleHttpEtagTest`):**
> - `ConsoleHttpEtagTest` (existing): exercises generic ETag/`If-None-Match`
>   handling on the console HTTP layer (asset routes and similar) — it predates
>   this refactor and is unchanged.
> - `ConsoleEtagRoutesTest` (proposed, new): exercises the `/api/sessions` and
>   `/api/session` endpoints' ETag round-trip behavior specifically. The two
>   files have non-overlapping subjects; this file extracts only the
>   `apiSessions*Etag` / `apiSession*Etag` / `apiSessionWithoutCurrent*` tests
>   from the legacy file.

### Evidence

- File line count:
  `wc -l fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
  → `2897`
- Total `@Test` count: `grep -c "@Test" ConsoleFeedbackItemRoutesTest.kt` → `100`
- `consoleHtml*` cluster:
  `grep -c "fun consoleHtml" ConsoleFeedbackItemRoutesTest.kt` → `33`
- Sibling, already-decomposed route test files in the same package:
  - `ConsoleAssetRoutesTest.kt`
  - `ConsoleConnectionRoutesTest.kt`
  - `ConsolePreviewRoutesTest.kt`
  - `ConsoleArtifactRoutesSessionScopeTest.kt`
  - `ConsoleHttpEtagTest.kt`
  - `FeedbackConsoleServerTest.kt`
  These exist precisely because their route modules were extracted; the
  feedback-item / session / handoff routes were never given the same treatment.

### Impact

1. **Slow test feedback loop.** Compiling and running the 2897-line file
   serializes 100 test classes worth of `FeedbackConsoleServer` start/stop
   boilerplate; running a single test still pulls in the whole compilation unit.
2. **Discoverability cliff.** Contributors looking for "where do I add a test
   for `/api/handoff/preview`?" land on a 2897-line file that is also where
   HTML contract tests live, and where polling JS contract tests live. There
   is no signal that those concerns are different.
3. **Merge conflict surface.** Any feedback-item, session, handoff, ETag,
   asset, or polling change touches the same file, so concurrent branches
   collide frequently.
4. **Hidden coupling encouragement.** Because helpers (`store`, `service`,
   `server` setup blocks) are duplicated inline across every test, a small
   change to one helper (e.g., FakeIds order, default package name) is easy
   to miss in the other 99.
5. **Misleading file name.** The file claims to test
   `ConsoleFeedbackItemRoutes`, but only ~18 of 100 tests do that. Reviewers
   asked to look at "the feedback item routes tests" do not see polling,
   handoff, HTML, or session tests.

## 2. Goals / Non-Goals

### Goals

- Split `ConsoleFeedbackItemRoutesTest.kt` into 6 focused test files, each
  aligned 1:1 with its production module, named consistently with the existing
  decomposed siblings (`Console<RouteModule>RoutesTest.kt`).
- Move the 33 `consoleHtml*` tests into `ConsoleAssetContractTest.kt` (new
  file) alongside the existing `ConsoleAssetRoutesTest.kt`, because they
  contractually test the bundled asset payload.
- Move cross-cutting polling, merge, mutation-lock JS contract tests to a
  separate `ConsoleSessionsPollingContractTest.kt` (these are JS-source string
  assertions, not HTTP tests).
- Extract shared setup into a single reusable `ConsoleRouteTestFixtures.kt`
  helper inside the existing `io.beyondwin.fixthis.mcp.fixtures` package so
  no duplication regresses.
- Preserve **every** `@Test` (no consolidation, no deletion, no renaming) — the
  goal is structural, not behavioral.
- Keep the existing package (`io.beyondwin.fixthis.mcp.console`) so that
  Gradle filter expressions used in CI (`--tests
  "io.beyondwin.fixthis.mcp.console.*"`) continue to match.
- Reduce per-file size to ≤ 800 lines so a developer can scan a whole file
  in one screen-scroll session.

### Non-Goals

- **No test rewrites.** Tests are moved verbatim; bodies are not modified
  except to adjust helper imports.
- **No new coverage.** This refactor does not add or remove behavioral
  assertions.
- **No change to production routes.** `FeedbackItemRoutes.kt`,
  `SessionRoutes.kt`, `HandoffPreviewRoutes.kt`, `MarkHandedOffRoutes.kt`,
  `FeedbackConsoleAssets.kt`, and `FeedbackConsoleServer.kt` are untouched.
- **No test parallelism changes.** Gradle's existing test executor settings
  are left alone; the goal is structural clarity, not faster wall-clock CI
  (though there will be modest parallelism wins for free). Splitting one
  previously-serial file into seven means Gradle's `maxParallelForks` /
  `forkEvery` settings can now run the resulting classes concurrently. This
  is safe because every test uses `FeedbackConsoleServer(... port = 0)`
  (kernel-assigned port) and the server lifecycle is fully scoped to each
  `ConsoleSessionFixture` (`use { ... }`) — no global singletons, no
  fixed ports, no shared on-disk state outside per-test temp dirs. The
  `ConsoleRouteTestFixtures` helper preserves this scoping by construction.
- **BridgeProtocolVersionSyncTest** is unaffected by this refactor; it lives
  outside the `console` package and will still run as part of the existing
  `:fixthis-mcp:test` suite verify step.
- **No assertion-style migration.** We do not move from `kotlin.test` to
  JUnit5-style or Kotest in this pass.

## 3. Design

> **TDD exemption — behavior-preserving move.** This refactor is exempt from
> the project's red-first TDD discipline because it makes no behavioral
> changes: every `@Test` body is copied byte-for-byte. The verify gate is the
> existing tests passing verbatim after the move (same total count, same
> per-test names, same green status). The plan codifies this with a Gradle
> test-count invariant and a ledger-completeness grep at every checkpoint.
> The single exception is Task 2's helper-introduction step, which does
> follow red→green because the helper file is genuinely new code.

### 3.1 Target file layout

After the refactor, `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/`
contains:

```
ConsoleFeedbackItemRoutesTest.kt          (~600 lines, 18 tests)
  └ item PATCH / batch save / items API / clearDraft / deleteScreen / preview save in progress
ConsoleSessionRoutesTest.kt               (~400 lines, 8 tests)
  └ /api/sessions, /api/session, /api/session/open|close, server error, package filter
ConsoleHandoffRoutesTest.kt               (~650 lines, 19 tests)
  └ /api/agent/handoff, /api/handoff/preview, /api/items/handed-off, staleAfterHandoff fields, saveToMcp toast
ConsoleSessionsPollingContractTest.kt     (~250 lines, 13 tests)
  └ JS-source string assertions for sessions polling, mutation lock, merge helpers, history pips, prompt actions
ConsoleAssetContractTest.kt               (~750 lines, 34 tests)
  └ consoleHtml* + consoleUsesOptionASelect* assertions over served index.html and bundled app.js
ConsoleEtagRoutesTest.kt                  (~250 lines, 6 tests)
  └ apiSessions/apiSession ETag round-trip
ConsoleNavigationRoutesTest.kt            (~100 lines, 2 tests)
  └ navigationApi*
```

Per-cluster totals (must sum to 100): 18 + 8 + 19 + 13 + 34 + 6 + 2 = **100**.

> **Source of truth:** the plan's per-test ledger
> (`docs/superpowers/work-notes/2026-05-14-console-test-decomposition-ledger.md`,
> created in plan Task 1) is the authoritative partition. If a count here ever
> drifts from the ledger rows, the ledger wins and this section must be
> updated to match.

Total: **7 files** replacing 1, with the largest still at ~750 lines — under
the 800-line target. The 800-line target derives from the project's existing
hotspot budget convention for test files; future overflow on any one cluster
is itself a signal to split again rather than to relax the budget.

Helper file:

```
fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/fixtures/ConsoleRouteTestFixtures.kt
  fun newConsoleSessionFixture(...): ConsoleSessionFixture
  data class ConsoleSessionFixture(
      val service: FeedbackSessionService,
      val store: FeedbackSessionStore,
      val server: FeedbackConsoleServer,
      val client: ConsoleHttpTestClient,
  )
```

### 3.2 Cluster boundary rules

Mechanical assignment based on the test function name prefix:

| Prefix regex | Destination file |
|---|---|
| `^itemPatch\|^batchItems\|^batchSave\|^itemsApi\|^savingDraft\|^clearDraft\|^previewSaveInProgress\|^deleteScreen` | `ConsoleFeedbackItemRoutesTest.kt` |
| `^sessionsApi\|^openSessionApi\|^closeSessionApi\|^sessionApi` | `ConsoleSessionRoutesTest.kt` |
| `^agentHandoff\|^handoffPreviewEndpoint\|^markHandedOffEndpoint\|^sessionResponseStaleAfterHandoff\|^saveToMcpToast` | `ConsoleHandoffRoutesTest.kt` |
| `^pollSessions\|^sessionsPolling\|^startSessionsPolling\|^visibilityChange\|^withMutationLock\|^mutationsAreWrapped\|^mergeSession\|^historyPip\|^promptActions` | `ConsoleSessionsPollingContractTest.kt` |
| `^consoleHtml\|^consoleUsesOptionASelect` | `ConsoleAssetContractTest.kt` |
| `^apiSessions.*Etag\|^apiSession.*Etag\|^apiSessionWithoutCurrent` | `ConsoleEtagRoutesTest.kt` |
| `^navigationApi` | `ConsoleNavigationRoutesTest.kt` |

Every test in the legacy file matches exactly one row; the regex set is a
partition. The implementation plan codifies this as a one-time `grep` ledger
used to verify nothing was lost.

### 3.3 ASCII diagram

```
                    ConsoleFeedbackItemRoutesTest.kt (2897 lines, 100 tests)
                                       │
              ┌──────────┬──────────┬──┴───────┬──────────┬──────────┬───────────┐
              │          │          │          │          │          │           │
              ▼          ▼          ▼          ▼          ▼          ▼           ▼
        FeedbackItem  Session   Handoff   Polling    Asset      Etag      Navigation
            18          8        19         13         34         6            2
        (HTTP)       (HTTP)    (HTTP)    (JS-src)  (HTML+JS)   (HTTP)       (HTTP)
              │          │          │          │          │          │           │
              └──────────┴──────────┴──────────┴──────────┴──────────┴───────────┘
                                              │
                                              ▼
                       fixtures/ConsoleRouteTestFixtures.kt
                       (single shared setup helper)
```

### 3.4 Shared fixture surface

Reading the existing 100 tests reveals four boilerplate shapes repeated
verbatim. The new helper exposes one factory per shape:

```kotlin
// io.beyondwin.fixthis.mcp.fixtures
object ConsoleRouteTestFixtures {
    /** Minimal in-memory session + service + started server. */
    fun newConsoleSessionFixture(
        clock: () -> Long = FakeLongs(100L, 200L, 300L, 400L, 500L).next,
        idGenerator: () -> String = FakeIds("session-1", "item-1").next,
        bridge: FixThisBridge = FakeFixThisBridge(),
        defaultPackageName: String = "io.beyondwin.fixthis.sample",
        projectRoot: String = "/repo",
    ): ConsoleSessionFixture

    /** Variant with a temp dir project root (for handoff prompt rendering). */
    fun newConsoleSessionFixtureWithTempRoot(
        prefix: String = "fixthis-routes-test",
        ...
    ): ConsoleSessionFixture
}

data class ConsoleSessionFixture(
    val service: FeedbackSessionService,
    val store: FeedbackSessionStore,
    val server: FeedbackConsoleServer,
    val client: ConsoleHttpTestClient,
) : AutoCloseable {
    override fun close() = server.stop()
}
```

Existing fixtures (`FakeIds`, `FakeLongs`, `ConsoleHttpTestClient`,
`FakeFixThisBridge`, `SequencedFingerprintBridge`, ...) stay where they are
and are reused.

### 3.5 Migration unit

The legacy file is removed only after every test has a green sibling in the
new file. **`git mv` policy:** use `git mv` only for true renames (file A
becomes file B with substantially the same content); use plain
create + delete for *splits* (extracting a cluster of tests out of a file
that continues to exist). Rationale: `git mv` is just rename detection plus
content similarity, and Git's similarity heuristic only fires when one
endpoint clearly inherits from the other. For a split, neither destination
"inherits"; spurious `git mv` invocations either no-op or produce confusing
blame.

Concretely:
- Tasks 3–8 (cluster extractions) use **create + delete** — no `git mv`.
- The final step (Task 9) keeps `ConsoleFeedbackItemRoutesTest.kt` at its
  existing path with a smaller test set; no rename is needed and `git mv` is
  not used.

This means `git blame` on the extracted clusters will start from the
extraction commit. The plan's R2 risk note documents that tradeoff.

## 4. Migration Strategy

### Phase 0 — Inventory (Task 1)

Run a one-time ledger script that emits all 100 test method names plus their
assigned destination file (using §3.2). Commit this ledger as
`docs/superpowers/work-notes/2026-05-14-console-test-decomposition-ledger.md`
so reviewers can verify completeness.

### Phase 1 — Helper extraction (Task 2)

Add `ConsoleRouteTestFixtures.kt` and verify by replacing one *small* helper
block in the existing 2897-line file with a call to the new factory. Run the
full test suite. **No tests are moved yet.**

### Phase 2 — Move clusters smallest-first (Tasks 3–8)

Each task moves exactly one cluster, in this order (smallest first to keep
review diff small):

1. Navigation (2 tests) → `ConsoleNavigationRoutesTest.kt`
2. ETag (6 tests) → `ConsoleEtagRoutesTest.kt`
3. Session routes (8 tests) → `ConsoleSessionRoutesTest.kt`
4. Polling/contract (13 tests) → `ConsoleSessionsPollingContractTest.kt`
5. Handoff routes (19 tests) → `ConsoleHandoffRoutesTest.kt`
6. Asset contract (34 tests) → `ConsoleAssetContractTest.kt`

After each move:
- Run `./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.*"`
- Verify the test count emitted by Gradle equals the pre-move count.

### Phase 3 — Rename + slim down (Task 9)

Once all six clusters are extracted, the legacy file contains only the 18
feedback-item tests. Rename in place (no `git mv` needed — file name does
not change) and verify the 18 tests still pass. The file should now be
~600 lines.

### Phase 4 — Documentation sync (Task 10)

Update `CONTRIBUTING.md` and `docs/reference/feedback-console-contract.md`
references that point to the legacy file path (search both for
`ConsoleFeedbackItemRoutesTest`).

### Backwards compatibility

- No public API is touched. All test moves are file-internal.
- The Kotlin package
  (`io.beyondwin.fixthis.mcp.console`) is preserved, so any CI filter
  `--tests "io.beyondwin.fixthis.mcp.console.*"` keeps working.
- The test class names referenced in any IDE bookmarks, run configurations,
  or scripts will change. We do not migrate IDE state; contributors re-pin.

## 5. Test Strategy

This refactor is purely structural, so the verification strategy is:

### 5.1 Test count invariant

Before any change:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.*" --info \
  | grep -cE "^(.*ConsoleFeedbackItemRoutesTest|.*passed)$"
```

Record the number of tests reported under `ConsoleFeedbackItemRoutesTest` (100)
and the total console-package test count (baseline).

After each phase, the **total console-package test count must equal the
baseline + 0**. The number under `ConsoleFeedbackItemRoutesTest` decreases by
exactly the cluster size; the new file shows up with the same delta.

### 5.2 Ledger diff verification

The ledger from Phase 0 is the single source of truth for what was moved
where. The plan's final verification step grep-checks every test name from
the ledger appears in exactly one new file:

```bash
while read name dest; do
  count=$(grep -rE "fun $name\(" \
    fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ | wc -l)
  [ "$count" -eq 1 ] || echo "DUPLICATE or MISSING: $name (found $count)"
done < ledger.tsv
```

Expected: zero lines of output.

### 5.3 No-rewrite diff inspection

Each task's commit must show **only** moves and helper-call substitutions.
The plan instructs reviewers to verify with:

```bash
git diff HEAD~1 -- '*Test.kt' | grep -E "^[-+]" | grep -v "^[-+]import" \
  | grep -v "^[-+]package" | grep -v "^[-+]\s*$" | wc -l
```

This count should be approximately twice the moved-lines count (once removed,
once added) plus the helper-call delta. A large negative net should be
investigated.

### 5.4 Per-file smoke run

After each move, run only the new file:

```bash
./gradlew :fixthis-mcp:test --tests "io.beyondwin.fixthis.mcp.console.ConsoleNavigationRoutesTest"
```

Expected: 2 tests passed (for the navigation example).

### 5.5 Final full matrix

After Task 10:

```bash
./gradlew :fixthis-mcp:test
./gradlew check
```

Expected: PASS with the same total test count as the baseline.

## 6. Open Risks

### R1 — Helper extraction introduces hidden state sharing

**Risk:** A factory that is too eager (e.g., reusing a single
`FakeLongs(100, 200, 300, 400, 500)` instance across tests) would break tests
that rely on advancing the clock further.

**Mitigation:**
- The factory accepts the clock and id generator as constructor parameters
  (defaulted, but overridable per test).
- During Task 2, we replace **one** small block first and re-run the suite
  before any cluster move.

### R2 — Legacy-file inheritance choice splits git blame

**Risk:** If we keep the legacy file as `ConsoleFeedbackItemRoutesTest.kt`
(rather than `git mv` it to a renamed location), the 33 `consoleHtml*` tests
lose their authorship blame after extraction.

**Mitigation:**
- For each extraction commit, include a short `Co-Authored-By` line if the
  history shows a meaningful prior author who is still on the project.
- Per §3.5, splits use plain create + delete (not `git mv`) because no
  endpoint genuinely inherits from the legacy file; spurious `git mv` here
  fails Git's similarity heuristic and produces misleading blame. Authors
  are recovered via `git log --follow` on the legacy file plus the
  `Co-Authored-By` convention above.
- This is a tradeoff explicitly documented in the plan; reviewers can
  comment.

### R3 — JS-source assertions in the polling contract file mislabel themselves

**Risk:** Tests like `pollSessionsTickResetsFailureCounterOnSuccess` and
`mergeSessionIntoStatePreservesUserState` read the **bundled** `app.js` as a
string and assert function bodies contain expected literals. After the
console-bundle-optimization work (Item 2), those literal substrings may move
or minify, breaking many tests at once.

**Mitigation:**
- The new `ConsoleSessionsPollingContractTest.kt` file is a clear signal
  that these are JS-source contract tests; the
  console-bundle-optimization plan must include a step that re-runs this
  test file specifically.
- A docstring at the top of the new file lists each tested function name and
  the JS source file it lives in, so a future minification change has a
  cheat sheet.

### R4 — IDE run configurations break silently

**Risk:** Developers who pinned `ConsoleFeedbackItemRoutesTest` to their
IntelliJ run configurations will run the legacy file's reduced 18 tests and
miss regressions in the extracted ones.

**Mitigation:**
- Add a one-line note to `CONTRIBUTING.md` ("If you previously ran
  `ConsoleFeedbackItemRoutesTest` directly, run
  `io.beyondwin.fixthis.mcp.console.*` after this refactor.")
- The Gradle CI configuration already runs the whole package; no automated
  process degrades.

### R5 — Asset contract tests depend on built `app.js`

**Risk:** `consoleHtml*` tests load the served HTML/JS via
`FeedbackConsoleAssets`. If a contributor forgets to run
`node scripts/build-console-assets.mjs` before running this test file
in isolation, they may see false failures.

**Mitigation:**
- The file's KDoc header explicitly notes that running this file in
  isolation requires a fresh bundle, and points at `CONTRIBUTING.md`.
- A `@BeforeClass` comment block explains how `FeedbackConsoleAssets` loads
  the resource so test failures show a hint.

## 7. References

### Specs / plans

- This file's plan:
  `docs/superpowers/plans/2026-05-14-console-routes-test-decomposition-implementation.md`
- Sibling Item 2 (bundle optimization) that affects `app.js` shape:
  `docs/superpowers/specs/2026-05-14-console-bundle-optimization-design.md`
- Sibling Item 1 (state machine expansion) that affects JS source structure:
  `docs/superpowers/specs/2026-05-14-console-state-machine-expansion-design.md`

### Code locations

- Test file under refactor:
  `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleFeedbackItemRoutesTest.kt`
- Production route modules already split (target alignment):
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackItemRoutes.kt`
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/SessionRoutes.kt`
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/HandoffPreviewRoutes.kt`
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/MarkHandedOffRoutes.kt`
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleAssets.kt`
- Existing decomposed siblings (style reference):
  - `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleAssetRoutesTest.kt`
  - `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleConnectionRoutesTest.kt`
  - `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsolePreviewRoutesTest.kt`
  - `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleHttpEtagTest.kt`
- Shared fixtures package (helper destination):
  `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/fixtures/`
