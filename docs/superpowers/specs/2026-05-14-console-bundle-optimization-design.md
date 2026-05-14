# Console JS Bundle Optimization — Design Spec

> Status: Draft (2026-05-14)
> Owner: Feedback console
> Companion plan: [`docs/superpowers/plans/2026-05-14-console-bundle-optimization-implementation.md`](../plans/2026-05-14-console-bundle-optimization-implementation.md)

## 1. Problem

### 1.1 Current state

`scripts/build-console-assets.mjs` concatenates **27 hand-listed source files**
from `fixthis-mcp/src/main/console/` plus an injected build header, writes the
result to `fixthis-mcp/src/main/resources/console/app.js`, and ships that file
as-is to the browser.

Measured on the current `main` (commit `3135f88`):

```
$ wc -c fixthis-mcp/src/main/resources/console/app.js
  228237  bytes  (~223 KiB)
$ wc -l fixthis-mcp/src/main/resources/console/app.js
    4932 lines
$ wc -c fixthis-mcp/src/main/console/*.js | tail -1
  179870 total                       # raw source bytes before concat
```

The 48 KiB delta between source and shipped bundle is entirely whitespace from
the legacy 12-space indentation block every module inherits (every line begins
with `            ` — see `fixthis-mcp/src/main/console/state.js:1-100`).

### 1.2 What hurts

1. **No minification.** Identifiers and whitespace are shipped raw. Browser
   parse + compile time on cold load is dominated by lexer work that adds no
   value, especially for users opening the console on a phone tether.
2. **Hand-maintained source list.** `scripts/build-console-assets.mjs:8-36`
   declares 27 file names in an array; the script aborts if a new `.js` lands
   on disk without being added to that array
   (`scripts/build-console-assets.mjs:43-51`). That guard is good, but the
   ordering is hand-curated, fragile, and silently couples module load order
   to declaration order ("later modules can reference earlier ones" —
   `scripts/build-console-assets.mjs:48`).
3. **Build header position is a magic splice.**
   `scripts/build-console-assets.mjs:80-82` injects the header between index 0
   and index 1 of the concatenated stream. Any reorder of `state.js` /
   `staleness.js` breaks the assertion implicitly.
4. **Duplicated `draftXxx.js` boilerplate.** Seven sibling files
   (`draftWorkspace.js`, `draftWorkspaceHistory.js`, `draftPorts.js`,
   `draftStorageAdapter.js`, `draftApiAdapter.js`, `draftUseCases.js`,
   `draftCommandQueue.js`) repeat the same module shape: an IIFE-ish leading
   block, a set of helpers, an export-via-globals tail. Concatenation flattens
   this into a single shared scope where `let` declarations across modules
   silently collide if names overlap.
5. **No source map.** When a production browser throws, the stack trace points
   into the 4932-line generated `app.js`; the contributor must mentally map
   back to one of 27 source files.
6. **Cache poisoning on every build.** The build header rewrites
   `ConsoleBuildEpochMs` rounded to the minute
   (`scripts/build-console-assets.mjs:54`), so two contributors regenerating
   in the same minute produce identical output, but any rebuild outside that
   window changes bytes the browser must re-fetch. There is no content hash
   in the served URL; cache busting is implicit.

### 1.3 Evidence files

- Build script: `scripts/build-console-assets.mjs:1-101`
- Bundle output: `fixthis-mcp/src/main/resources/console/app.js` (228,237 B)
- Source modules (27 total): `fixthis-mcp/src/main/console/*.js`
- Top three largest sources:
  - `rendering.js` — 931 lines
  - `annotations.js` — 753 lines
  - `history.js` — 383 lines
- Indentation overhead sample: `state.js:1-100` — every meaningful line begins
  with 12 leading spaces, which the bundle preserves.

### 1.4 Impact

- **First-load latency.** Cold load currently transfers ~223 KiB
  uncompressed. The console runs on the same MCP server the user already
  hits, so transfer time is small, but parse/compile on lower-end Android
  WebViews adds noticeable jank.
- **Asset contract tests are brittle.** 33 tests in
  `ConsoleFeedbackItemRoutesTest.kt` (post-Item-3 split:
  `ConsoleAssetContractTest.kt`) assert on raw substrings of `app.js`. Any
  minification breaks them all at once unless the build pipeline emits a
  parallel non-minified "contract" copy or those tests are rewritten to read
  the unbundled source.
- **Diff readability.** PR reviewers see whitespace-only diffs to `app.js`
  on every Kotlin-unrelated commit because the build header bumps the epoch.

## 2. Goals / Non-Goals

### Goals

- Reduce the shipped `app.js` size to ≤ **170 KiB raw** (= 174,080 bytes;
  ≤ 63% of the current 275,445 B post-Item-1). Additionally, the gzipped output must be
  ≤ **40 KiB** (= 40,960 bytes), which is the user-facing transfer metric
  (vanilla JS compresses ~3-4× so this is a defensible ceiling).
  Measurement: `wc -c fixthis-mcp/src/main/resources/console/app.js` for raw
  and `gzip -c fixthis-mcp/src/main/resources/console/app.js | wc -c` for
  gzipped.
- Replace the 27-element hand-maintained `sources` array with a
  dependency-graph-driven order derived from explicit per-module annotations.
- Make the build deterministic for identical inputs (no minute-rounded epoch
  in the bundle — move it to a separate, smaller `console-build-meta.json`
  resource).
- Emit a source map (`app.js.map`) so production stack traces resolve to the
  27 source files.
- Preserve the `node scripts/build-console-assets.mjs --check` workflow
  exactly (used by CI in `CONTRIBUTING.md`).
- Keep the bundle as a **single classical-script file**. No ES modules in
  the browser, no dynamic `import()`, no need to change `index.html`.
- Keep all asset-contract tests (33) passing, possibly by reading the
  unbundled `console/` sources instead of `app.js`.

### Non-Goals

- **No TypeScript migration.** Pure JS only.
- **No build-time tree shaking that drops dead exports.** Every source file
  is currently considered live; manual cleanup is a separate task.
- **No content-hashed asset URLs.** We do not introduce `app.<sha>.js`
  filenames or rewrite `index.html` per build. The console is served with
  `Cache-Control: no-cache, must-revalidate` (forces an ETag re-check on
  every navigation), and `window.FixThisConsoleConfig.buildMeta` changes
  per build so the inline metadata in `index.html` never collides across
  builds. ETag is computed from the JAR resource bytes by the existing
  Ktor static handler.
- **No source-file consolidation.** The 27 files stay 27 files (the
  draft-workspace plan deliberately created 7 of them; we do not undo that).
- **No browser-side dynamic imports.** Code splitting is not required at
  ~100 KiB.

## 3. Design

### 3.1 Pipeline overview

```
fixthis-mcp/src/main/console/*.js  (27 files, ~180 KiB raw)
            │
            │  scripts/build-console-assets.mjs (rewritten)
            │   1. Read all *.js
            │   2. Parse `// @requires foo.js` directives (per-file headers)
            │   3. Topologically sort
            │   4. Concatenate with file-boundary comments
            │   5. Pipe through a minifier (esbuild, --minify --target=es2020)
            │   6. Emit  app.js + app.js.map
            │   7. Emit  console-build-meta.json (epoch + git sha)
            ▼
fixthis-mcp/src/main/resources/console/
    app.js                  (~100 KiB, minified, single classical script)
    app.js.map              (source map; only served via /__map endpoint for devs)
    console-build-meta.json (small, served at /__build, read by debug UIs)
```

Browser load: unchanged — `<script src="app.js">`.

### 3.2 Dependency declaration

Each source file gains an opt-in header comment listing its dependencies as
filenames (no path):

```js
// state.js
// @requires (none — root)
```

```js
// staleness.js
// @requires state.js
```

```js
// rendering.js
// @requires state.js, annotations.js, draftWorkspace.js
```

The build script parses these headers, builds a DAG, and topologically sorts.
Cycles are a build error. Files with no `// @requires` directive are treated
as `// @requires (none)` (i.e., root-eligible).

This replaces the implicit ordering currently encoded in
`scripts/build-console-assets.mjs:8-36`.

### 3.3 Module boundary preservation

Each concatenated module is wrapped in an IIFE-named comment:

```
//#region state.js
( ... state.js content ... )
//#endregion state.js
```

This is purely a debugging aid (the comments survive minification only as
`/*! state.js */` banners; see §3.5).

Module-private declarations stay shared in the global scope (current
behavior). The dependency graph just gives us a *correct order* for that
shared scope, not real isolation. Isolation is a job for Item 1
(state-machine expansion).

### 3.4 Minifier choice: esbuild

We adopt `esbuild` as a dev-dependency. Justification:

- **Single tool, single binary, zero plugins.** Adding a `package.json`
  devDependency is the only delivery surface.
- **Source maps for free** (`--sourcemap=external`).
- **No transformation** — esbuild preserves identifier names by default and
  only renames locals when `--minify` is given. We use `--minify-whitespace`
  + `--minify-syntax` but **not** `--minify-identifiers`, because the asset
  contract tests grep for function names like `withMutationLock` and
  `mergeSessionIntoState`. Keeping public identifiers makes those tests
  survive minification without modification.
- **Target `es2020`** matches what the rest of the codebase assumes
  (`?.`, `??`, async/await, top-level template literals).

The choice is reversible — if `esbuild` ever becomes a concern, the same
pipeline accepts a `terser` swap behind one CLI shell-out.

### 3.5 What "minified but contract-stable" means

Tests in `ConsoleAssetContractTest.kt` (post-Item-3 split) call helpers like
`javascriptFunctionBody("withMutationLock")` and assert on substrings of the
returned text. To keep these working we:

1. **Disable identifier minification** for top-level (script-scoped)
   functions. esbuild's default with `--minify-whitespace --minify-syntax`
   does exactly this.
2. **Preserve `// @preserve` banners**, used at the top of every file to
   keep the filename trace.
3. Add a **post-minification check** in the build script that re-greps the
   minified output for a known set of "contract symbols" (e.g.,
   `withMutationLock`, `pollSessionsTick`, `mergeSessionIntoState`,
   `MaxConsecutivePollFailures`, `DefaultLivePreviewIntervalMs`). If any is
   missing, the build aborts with a clear message naming which symbol the
   minifier dropped.
4. Add a **runtime-reachability assertion** (`node:vm` sandbox or
   Playwright `page.evaluate`) that loads the *bundled* `app.js` and
   verifies each contract symbol is `typeof !== 'undefined'` on the
   sandbox `globalThis`. Pure string-presence is insufficient: a minifier
   can keep the *name* in a banner while inlining the *value* such that
   the symbol is unreachable at runtime.

### 3.6 Build-meta sidecar

Move the epoch / git-sha metadata out of the JS bundle:

Before (`scripts/build-console-assets.mjs:68`):

```js
const buildHeader = `// build-header\nconst ConsoleBuildEpochMs = ${epochMs};\nconst ConsoleBuildGitSha = '${gitSha}';\n`;
```

After: emit `fixthis-mcp/src/main/resources/console/console-build-meta.json`:

```json
{
  "buildEpochMs": 1715692800000,
  "gitSha": "3135f88"
}
```

Add a 3-line shim at the top of `state.js` (or a new tiny `meta.js`) that
reads the JSON via `fetch('/api/console/build-meta')` (a new server route)
or via an injected `<script>` block inlined by the existing
`FeedbackConsoleAssets.kt`. The server already inlines a config object —
`window.FixThisConsoleConfig` (see `state.js:155-157`); we just add
`buildMeta` to it server-side.

Concrete server change (one site): `FeedbackConsoleAssets.kt` reads
`console-build-meta.json` from the resource bundle and inlines
`window.FixThisConsoleConfig.buildMeta = {...}` into `index.html` at serve
time. The browser-side `ConsoleBuildEpochMs` / `ConsoleBuildGitSha` const
declarations are removed; any debug UI references them via
`FixThisConsoleConfig.buildMeta.buildEpochMs` instead.

### 3.7 Diagram

```
                ┌─────────────────────────────────────────┐
                │ fixthis-mcp/src/main/console/  (27 .js) │
                │   each file: // @requires <deps>        │
                └──────────────────┬──────────────────────┘
                                   │
                                   ▼
       ┌─────────────────────────────────────────────────────┐
       │ scripts/build-console-assets.mjs                    │
       │   1. read + parse dep headers                       │
       │   2. topological sort                               │
       │   3. concat with //#region banners                  │
       │   4. esbuild --minify-whitespace --minify-syntax    │
       │   5. assert contract symbols still present          │
       │   6. emit app.js + app.js.map                       │
       │   7. emit console-build-meta.json                   │
       └──────────────────┬──────────────────────────────────┘
                          │
       ┌──────────────────┴──────────────────┐
       ▼                                     ▼
fixthis-mcp/src/main/resources/console/      Kotlin: FeedbackConsoleAssets.kt
   app.js                                       reads console-build-meta.json
   app.js.map                                   inlines into index.html as
   console-build-meta.json                      window.FixThisConsoleConfig.buildMeta
```

### 3.8 Size budget

| Component | Estimate |
|---|---|
| Raw source (27 files) | 179,870 B |
| Concat + indentation-strip | ~140 KiB |
| esbuild `--minify-whitespace --minify-syntax` (raw) | ~95–105 KiB |
| esbuild output (gzipped) | ~28–35 KiB |
| Source map (separate, dev-only) | ~250 KiB (not on hot path) |
| `console-build-meta.json` | < 200 B |

Two budgets enforced by the build script:

- **Raw budget:** `app.js` ≤ **170 KiB** (= 174,080 bytes). This is what
  `wc -c` reports on disk. (Recalibrated 2026-05-14 to absorb the Item-1
  state-machine expansion: 27 source files / 180 KiB raw → 39 source files /
  273 KiB raw. The empirical floor under spec-mandated config —
  `minifyWhitespace + minifySyntax`, `minifyIdentifiers: false` per §3.4 —
  measures ~161 KiB; 170 KiB gives ~5% headroom.)
- **Gzip budget:** `gzip -c app.js | wc -c` ≤ **40 KiB** (= 40,960 bytes).
  This is the user-facing transfer metric (the MCP server gzip-encodes
  responses by default for browsers that advertise `Accept-Encoding: gzip`).
  The gzip budget is unchanged — empirical measurement is ~38 KiB.

If either budget is exceeded, the build script fails with a clear error
naming the offending budget and the actual measurement.

### 3.9 Considered & rejected: indentation-strip-only

A simpler alternative was considered: skip esbuild entirely and just
trim the leading 12-space indentation block from every line during
concatenation. Measured savings: ~48 KiB → bundle drops to ~180 KiB.
That alone misses the raw budget (170 KiB) and the gzip budget. esbuild's
`--minify-syntax` additionally performs dead-code elimination on
conditionally-unreachable branches, collapses sequence expressions, and
inlines single-use IIFEs — all of which the indentation-only approach
forfeits. We accept the one extra dev-dependency (`esbuild`) in exchange
for hitting both budgets with margin.

## 4. Migration Strategy

### Phase 0 — Add esbuild as devDependency

Already on `package.json`'s `devDependencies` (`playwright`); add `esbuild`:

```bash
npm install --save-dev esbuild@^0.21.0
```

Commit `package.json` and `package-lock.json` separately. CI's existing
`npm install` step picks it up.

### Phase 1 — Annotate dependencies (non-shipping change)

Add `// @requires` comments to all 27 files. Each file's first non-blank
line becomes either `// @requires (none)` or `// @requires a.js, b.js`. The
build script does **not** consume these yet — they are documentation only at
this phase. Verify by hand that the existing hand-curated order
(`scripts/build-console-assets.mjs:8-36`) is a valid topological order of the
declared edges.

### Phase 2 — Swap to graph-driven order

Rewrite the build script's source-resolution step to parse `// @requires`
headers and compute a topological order. The resulting concatenation must
produce **the same bytes** as the legacy script for the current source
state. This is verified by:

```bash
node scripts/build-console-assets.mjs --check     # before
git checkout -- fixthis-mcp/src/main/resources/console/app.js
node scripts/build-console-assets.mjs             # after swap
git diff --stat fixthis-mcp/src/main/resources/console/app.js
```

Expected: no diff (modulo the rounded-minute build header).

### Phase 3 — Move build-meta into sidecar

Add `console-build-meta.json` emission. Remove the `buildHeader` splice from
the JS bundle. Update `FeedbackConsoleAssets.kt` to read the JSON and inline
into `window.FixThisConsoleConfig`. Remove any browser-side reference to
`ConsoleBuildEpochMs` / `ConsoleBuildGitSha` (search & replace —
`grep -rn ConsoleBuildEpochMs fixthis-mcp/src/main/console/`).

The asset contract tests that assert on the build-header line shape
(`consoleHtmlContainsSessionsPolling` — see legacy test list) must be
audited; they assert on JS structure that does not reference the build
header.

### Phase 4 — Add the minifier step

Wire `esbuild` into the pipeline. The script does:

```js
import { build } from 'esbuild';
await build({
  stdin: { contents: concatenated, loader: 'js' },
  bundle: false,
  minify: false,
  minifyWhitespace: true,
  minifySyntax: true,
  minifyIdentifiers: false,
  target: ['es2020'],
  sourcemap: 'external',
  sourcefile: 'app.js',
  outfile: target,
  legalComments: 'inline',
});
```

`bundle: false` because we already concatenated.

### Phase 5 — Contract-symbol guard

After minification, the script greps the output for a hard-coded set:

```js
const CONTRACT_SYMBOLS = [
  'withMutationLock', 'pollSessionsTick', 'mergeSessionIntoState',
  'startSessionsPolling', 'MaxConsecutivePollFailures',
  'DefaultLivePreviewIntervalMs', 'PreviewIntervalStorageKey',
  // ... (full list derived from ConsoleAssetContractTest.kt assertions)
];
```

For each, run `output.includes(symbol)`; on first miss, throw with a message
like `"esbuild dropped contract symbol 'mergeSessionIntoState'. Add @preserve banner or stop minifying."`.

### Phase 6 — Size budget guard

After minification, the script asserts `output.length <= 170 * 1024` (170 KiB).
On overflow, abort and print the actual size and the largest sources by
post-minification length.

### Phase 7 — Update asset contract tests

The 33 `consoleHtml*` tests live in `ConsoleAssetContractTest.kt` (after
Item 3). They currently load the served `app.js` and assert on substrings.
The minified bundle preserves *function names* but not *whitespace and
comments*. Tests that assert on whitespace-bearing patterns
(e.g., `expect("function withMutationLock(fn) {")` ) must be relaxed to
assert on the function *name* only (`expect.contains("withMutationLock")`).

The cleanest re-implementation is to introduce a helper in
`io.beyondwin.fixthis.mcp.fixtures` that reads the **unbundled** source
files from `src/main/console/<name>.js` directly instead of grepping the
minified bundle:

```kotlin
fun unbundledConsoleSource(name: String): String =
    Files.readString(Path.of("src/main/console").resolve(name))
```

The 33 tests can keep their assertions verbatim if they switch from
`bundledConsoleJs()` to `unbundledConsoleSource("state.js")` (or whichever
module each one targeted). This is a one-line change per test.

### Phase 8 — Update CONTRIBUTING.md

The current "console rebundle" recipe is:

```bash
node scripts/build-console-assets.mjs
node scripts/build-console-assets.mjs --check
```

After the change, the same two commands must continue to work. Documentation
adds a one-liner explaining the size budget guard and how to read the source
map when debugging production stack traces.

### Backwards compatibility

- The single shipped `app.js` URL is unchanged.
- `node scripts/build-console-assets.mjs --check` continues to be the CI
  guard.
- `index.html` does not change.
- The console route `FeedbackConsoleAssets.kt` is the only Kotlin file
  touched.

### Merge-gate size regression guard

CI must fail any PR that pushes the bundle past either budget. The
existing `.github/workflows/ci.yml` (the project's Gradle/Node GH Actions
workflow) gains a step that runs after the standard `npm install`:

```yaml
      - name: Verify console bundle budgets
        run: |
          FIXTHIS_BUNDLE_REPRODUCIBLE=1 node scripts/build-console-assets.mjs --check
          RAW=$(wc -c < fixthis-mcp/src/main/resources/console/app.js | tr -d ' ')
          GZ=$(gzip -c fixthis-mcp/src/main/resources/console/app.js | wc -c | tr -d ' ')
          echo "raw=$RAW bytes (budget 174080), gzip=$GZ bytes (budget 40960)"
          test "$RAW" -le 174080 || { echo "FAIL: raw bundle $RAW > 174080 (170 KiB)"; exit 1; }
          test "$GZ"  -le 40960  || { echo "FAIL: gzip bundle $GZ > 40960 (40 KiB)"; exit 1; }
```

This guard is independent of the Node test in `console:build:test` — it
runs even if a contributor disables the test script, so the merge gate
cannot be silently weakened.

## 5. Test Strategy

### 5.1 Build-script unit tests

Add `scripts/build-console-assets-test.mjs` (Node `node:test`):

| Test | Verifies |
|---|---|
| `topo sort of declared edges matches legacy order` | The `@requires` annotations encode the same order that the hand-curated array used to. |
| `cycle in @requires aborts with named edges` | A synthetic cycle test fixture causes a non-zero exit with both edge names in the message. |
| `missing @requires header is treated as root` | A file with no header lands in the topological roots and does not error. |
| `contract symbol grep aborts when symbol missing` | Inject a synthetic minified output that drops `withMutationLock` and confirm the build script exits non-zero. |
| `size budget aborts when output > 170 KiB` | Stub a minifier that returns 250 KiB; confirm abort. |
| `--check returns 0 when output is in sync` | Idempotency test. |
| `--check returns non-zero when source file changed` | Self-test of the `--check` mode. |
| `build-meta sidecar emits JSON with epochMs and gitSha` | Schema lock for the meta file. |

### 5.2 Asset contract tests

The 33 `consoleHtml*` tests in `ConsoleAssetContractTest.kt` (post-Item-3)
keep their assertions. Their *source* changes from the bundled `app.js` to
the unbundled `console/<name>.js` files. The migration plan changes each
test's helper call exactly once.

### 5.3 Visual smoke

`scripts/console-browser-smoke.mjs` is run unchanged. It opens the served
console in headless Playwright and exercises the main flows. If
minification breaks a runtime expression, this surfaces it.

### 5.4 Manual size measurement

After the rewrite is on `main`:

```bash
node scripts/build-console-assets.mjs
wc -c fixthis-mcp/src/main/resources/console/app.js
gzip -c fixthis-mcp/src/main/resources/console/app.js | wc -c
```

Expected: raw ≤ **174,080 bytes** (170 KiB); gzipped ≤ **40,960 bytes**
(40 KiB).

### 5.5 Gradle test matrix

Full `./gradlew :fixthis-mcp:test` must pass. The minifier guards run inside
the Node build script; Gradle picks them up via the existing
`build-console-assets --check` step in CI.

## 6. Open Risks

### R1 — esbuild surprises on minify-syntax

**Risk:** `--minify-syntax` rewrites expressions in ways the asset contract
tests do not anticipate, even when identifier names are preserved
(e.g., `a === undefined` becomes `a===void 0`).

**Mitigation:**
- The contract-symbol guard catches dropped names but not rewritten
  expressions. The plan adds a small "rewrite tolerance" rule: each contract
  test asserts on the unbundled source, never on the minified bundle. The
  bundle is checked only for size and **runtime-reachable** symbol
  presence (a `node:vm` sandbox load proves the symbol is defined as a
  global, not just present as a string — see §3.5 step 4).

### R2 — Source map JAR exposure

**Risk:** `app.js.map` reveals the unminified source via DevTools to anyone
who can load the console, **and** is packaged into the shipped JAR via
Gradle's default `processResources` copy. The map is useful in dev but
should not increase the production JAR's footprint or expose internal
filenames in a release artifact.

**Mitigation:**
- The build script emits `app.js.map` into
  `fixthis-mcp/src/main/resources/console/` for local development use
  (DevTools picks it up automatically when the console is served via
  `--console-assets-dir`).
- Gradle's `processResources` task is configured to **exclude `**/*.map`**
  from the packaged JAR. Concrete snippet (Kotlin DSL,
  `fixthis-mcp/build.gradle.kts`):

  ```kotlin
  tasks.named<Copy>("processResources") {
      exclude("**/*.map")
  }
  ```

  This keeps the map available in dev (served from
  `--console-assets-dir`, which reads source files directly off the
  working tree) while ensuring the production JAR contains only `app.js`
  + `console-build-meta.json`. The exclusion is verified by a
  `ConsoleAssetContractTest` assertion that lists the console resources
  inside the built JAR and rejects any `*.map` entry.
- The build script also accepts `--no-sourcemap` to skip emission
  entirely for contributors who object on principle.

### R3 — Build-meta JSON race / `--check` non-determinism

**Risk:** A developer edits `state.js` and forgets to regenerate the bundle.
Today this is caught by `--check` because the JS bundle header changes. If
we move the header into a sidecar JSON, the JS bundle bytes are unchanged
for whitespace-only edits, so `--check` may pass while the sidecar is stale.
Furthermore, `buildEpochMs` and `gitSha` vary per build, so a naive
byte-compare of `console-build-meta.json` is non-deterministic and breaks
CI's `--check`.

**Mitigation — chosen approach: reproducible-mode write normalization.**

When the environment variable `FIXTHIS_BUNDLE_REPRODUCIBLE=1` is set (CI
sets this), the build script writes a normalized sidecar at write-time:

```json
{ "buildEpochMs": 0, "gitSha": "reproducible" }
```

When the variable is unset (local dev), real values are written and the
sidecar reflects the actual build. `--check` always runs with
`FIXTHIS_BUNDLE_REPRODUCIBLE=1` set internally, so the on-disk normalized
sidecar matches a regenerated normalized sidecar byte-for-byte. The
compare-time `normalizeMeta()` helper is removed; normalization moves to
the write path so the stored bytes are the source of truth.

Local developers who want a real epoch/sha simply run the build script
without the env var. CI always commits the normalized form. The
`FeedbackConsoleAssets.kt` server-side reader falls back to the JVM clock
and `git rev-parse HEAD` (or "unknown") at *runtime* when the sidecar
contains the reproducible placeholder, so production users still see a
meaningful build identifier in `window.FixThisConsoleConfig.buildMeta`.

- `--check` verifies **all three** outputs (`app.js`, `app.js.map`,
  `console-build-meta.json`) are byte-equivalent to a fresh regeneration
  *under reproducible mode*.
- The sidecar is regenerated by the same build script invocation, so a
  forgotten `node scripts/build-console-assets.mjs` fails CI as before.

### R4 — Browser cache thrash on every esbuild output

**Risk:** Even when source is unchanged, esbuild's output can differ between
versions. Users get a fresh download every CI run.

**Mitigation:**
- The build is deterministic for a single esbuild version. We pin
  `esbuild` exactly in `package.json` (no `^` — exact version) and let CI
  update it manually.
- The shipped file is small enough (~100 KiB) that occasional thrash is
  acceptable.

### R5 — `// @requires` annotation drift

**Risk:** A new contributor adds a module without a `// @requires` header and
the build silently treats it as a root.

**Mitigation:**
- The build script **fails (non-zero exit) at build time** when any file
  in `fixthis-mcp/src/main/console/*.js` other than the entry point
  (`main.js`) lacks a `// @requires` header. The message names the offending
  file: `ERROR: fixthis-mcp/src/main/console/foo.js has no // @requires header.`
  Treating absence as WARN was rejected because WARN is not a CI failure
  and silent root-promotion is exactly the drift this risk describes.
- A complementary Node test (`scripts/build-console-assets-test.mjs`)
  greps every `console/*.js` (except the entry point) for at least one
  `// @requires` line, failing if any is missing. Run in CI's
  `console:build:test` script.

### R6 — Concurrent landing with Item 1 / Item 3

**Risk:** Item 1 (state-machine expansion) introduces new modules; Item 3
(test decomposition) renames test files. Both touch overlapping files.

**Mitigation:**
- The plan's Task 1 captures the file inventory; any new modules added by
  Item 1 land with their own `// @requires` headers.
- The contract-symbol list (§3.5) is a single Node constant; updating it
  when Item 1 introduces a new public symbol is a one-line change.
- Item 3's tests do not depend on the bundle bytes; they read either the
  HTML or the bundle as a string. After Phase 7 of this spec, they read
  the unbundled source instead.

## 7. References

### Specs / plans

- This file's plan:
  `docs/superpowers/plans/2026-05-14-console-bundle-optimization-implementation.md`
- Item 1 (state-machine expansion):
  `docs/superpowers/specs/2026-05-14-console-state-machine-expansion-design.md`
- Item 3 (test decomposition):
  `docs/superpowers/specs/2026-05-14-console-routes-test-decomposition-design.md`
- Prior bundling-related plan:
  `docs/superpowers/plans/2026-05-14-build-optimization-implementation.md`

### Code locations

- Build script: `scripts/build-console-assets.mjs:1-101`
- Output bundle: `fixthis-mcp/src/main/resources/console/app.js`
- Source modules: `fixthis-mcp/src/main/console/*.js`
- Kotlin asset server: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleAssets.kt`
- Asset contract tests (post Item 3 split):
  `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt`
- Existing console-rebundle docs:
  `CONTRIBUTING.md` (search for `build-console-assets`)
  `docs/reference/feedback-console-contract.md`
