# FixThis Residual Risk Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining FixThis residual risks after the local fast and trust evidence profiles passed on `main`.

**Architecture:** Keep the closure incremental. CLI environment detection stays in `fixthis-cli`; workflow observation stays in docs and Node release checks; detekt ratcheting is narrow and budget-backed; v1 evidence depth is additive across core source matching, MCP rendering, console SSE evidence, and release-gate documentation.

**Tech Stack:** Kotlin/JVM, JUnit, Gradle, Node.js `node:test`, Bash, Markdown docs, Graphify.

---

## Scope Check

This is an umbrella closure with four independent tracks. Each task below is
mergeable on its own:

- Task 1 closes ADB discovery drift between CLI and evidence scripts.
- Task 2 closes required-check observation drift.
- Task 3 closes one static-analysis debt slice and ratchets the budget.
- Tasks 4-6 close the v1 evidence-depth path.
- Task 7 performs final evidence, docs, and graph refresh.

Do not add new package channels or new agent config writers while executing this
plan. Keep Copy Prompt as the supported path for agents that do not have a
verified file-based MCP writer.

## File Structure

### Task 1 - CLI ADB Discovery

- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/Adb.kt`
  - Add default SDK candidate discovery for macOS and Linux.
  - Keep current env and `local.properties` precedence.
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/AdbTest.kt`
  - Add tests for macOS and Linux default SDK fallback.
  - Keep command allow-list tests unchanged.

### Task 2 - Required-Check Observation Snapshot

- Create: `docs/contributing/required-checks-observation.md`
  - Record refresh command, local date, summary table, and next action.
- Modify: `scripts/check-release-readiness.mjs`
  - Enforce that the snapshot exists and contains the refresh command.
- Modify: `scripts/required-checks-observation-test.mjs`
  - Add a release-readiness fixture or direct assertion for the new rule if this
    test file already owns observation policy checks.

### Task 3 - Detekt Baseline Ratchet

- Modify one narrow `fixthis-mcp` hotspot selected by current detekt output.
- Modify: `config/detekt/baseline-budget.json`
  - Lower only the budget whose actual baseline count dropped.
- Modify or create focused tests only if the refactor changes behavior.

### Task 4 - Source Matching Evidence Depth

- Modify: `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`
- Modify: `fixtures/source-matching/manifest.json`
- Modify: `scripts/source-matching-fixtures-test.mjs`

### Task 5 - Interop Handoff And Console Rendering

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatter.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
- Modify related console presentation files only after the server-side compact
  handoff contract is green.
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatterTest.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`

### Task 6 - SSE And v1 Release Gate

- Modify: `scripts/console-reliability-report.mjs`
- Modify: `scripts/console-reliability-report-test.mjs`
- Modify: `docs/contributing/release-readiness.md`
- Modify: `scripts/check-release-readiness.mjs`
- Modify: `scripts/release-gate.mjs`
- Modify: `scripts/release-gate-test.mjs`

### Task 7 - Final Verification

- Modify: `docs/releases/unreleased.md`
- Modify: `CHANGELOG.md`
- Run `graphify update .`.
- Do not commit generated `graphify-out/`, `.fixthis/`, or `build/` artifacts.

## Task 1: Normalize CLI ADB Discovery

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/Adb.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/AdbTest.kt`

- [ ] **Step 1: Add failing macOS default SDK test**

Add this test to `AdbTest` after
`defaultExecutableUsesProjectLocalPropertiesSdkDirWhenEnvironmentIsMissing`:

```kotlin
    @Test
    fun defaultExecutableUsesMacosDefaultSdkDirWhenEnvironmentAndLocalPropertiesAreMissing() {
        val home = Files.createTempDirectory("fixthis-home-").toFile().apply {
            deleteOnExit()
        }
        val adb = File(home, "Library/Android/sdk/platform-tools/adb").apply {
            parentFile.mkdirs()
            writeText("")
            setExecutable(true)
            deleteOnExit()
        }

        assertEquals(
            adb.absolutePath,
            Adb.defaultAdbExecutable(
                projectRoot = null,
                environment = mapOf("HOME" to home.absolutePath),
                osName = "Mac OS X",
            ),
        )
    }
```

- [ ] **Step 2: Add failing Linux default SDK test**

Add this test immediately after the macOS test:

```kotlin
    @Test
    fun defaultExecutableUsesLinuxDefaultSdkDirWhenEnvironmentAndLocalPropertiesAreMissing() {
        val home = Files.createTempDirectory("fixthis-linux-home-").toFile().apply {
            deleteOnExit()
        }
        val adb = File(home, "Android/Sdk/platform-tools/adb").apply {
            parentFile.mkdirs()
            writeText("")
            setExecutable(true)
            deleteOnExit()
        }

        assertEquals(
            adb.absolutePath,
            Adb.defaultAdbExecutable(
                projectRoot = null,
                environment = mapOf("HOME" to home.absolutePath),
                osName = "Linux",
            ),
        )
    }
```

- [ ] **Step 3: Run focused test and verify failure**

Run:

```bash
./gradlew :fixthis-cli:test --tests "*AdbTest.defaultExecutableUsesMacosDefaultSdkDirWhenEnvironmentAndLocalPropertiesAreMissing" --tests "*AdbTest.defaultExecutableUsesLinuxDefaultSdkDirWhenEnvironmentAndLocalPropertiesAreMissing" --no-daemon
```

Expected: FAIL because `defaultAdbExecutable` does not accept `osName` and does
not check default SDK directories.

- [ ] **Step 4: Implement SDK candidate helpers**

In `Adb.kt`, replace the `defaultAdbExecutable` function and
`adbExecutableName` helper with this implementation:

```kotlin
        fun defaultAdbExecutable(
            projectRoot: File? = null,
            environment: Map<String, String> = System.getenv(),
            osName: String = System.getProperty("os.name"),
        ): String {
            sdkCandidates(projectRoot, environment, osName).forEach { sdkDir ->
                val adb = File(sdkDir, "platform-tools/${adbExecutableName(osName)}")
                if (adb.exists()) return adb.absolutePath
            }
            return "adb"
        }

        private fun sdkCandidates(
            projectRoot: File?,
            environment: Map<String, String>,
            osName: String,
        ): List<String> {
            val home = environment["HOME"]?.takeIf { it.isNotBlank() }
                ?: environment["USERPROFILE"]?.takeIf { it.isNotBlank() }
            return listOfNotNull(
                environment["ANDROID_HOME"]?.takeIf { it.isNotBlank() },
                environment["ANDROID_SDK_ROOT"]?.takeIf { it.isNotBlank() },
                projectRoot?.localPropertiesSdkDir(),
                defaultSdkDir(home, osName),
            ).distinct()
        }

        private fun defaultSdkDir(home: String?, osName: String): String? {
            if (home.isNullOrBlank()) return null
            return when {
                osName.startsWith("Mac", ignoreCase = true) -> File(home, "Library/Android/sdk").absolutePath
                osName.startsWith("Linux", ignoreCase = true) -> File(home, "Android/Sdk").absolutePath
                else -> null
            }
        }

        private fun adbExecutableName(osName: String): String =
            if (osName.startsWith("Windows", ignoreCase = true)) "adb.exe" else "adb"
```

- [ ] **Step 5: Run focused test and verify pass**

Run:

```bash
./gradlew :fixthis-cli:test --tests "*AdbTest.defaultExecutableUsesMacosDefaultSdkDirWhenEnvironmentAndLocalPropertiesAreMissing" --tests "*AdbTest.defaultExecutableUsesLinuxDefaultSdkDirWhenEnvironmentAndLocalPropertiesAreMissing" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Run full CLI tests**

Run:

```bash
./gradlew :fixthis-cli:test --no-daemon
```

Expected: PASS.

- [ ] **Step 7: Commit Task 1**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/Adb.kt \
  fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/AdbTest.kt
git commit -m "fix(cli): normalize default adb discovery"
```

## Task 2: Add Required-Check Observation Snapshot

**Files:**
- Create: `docs/contributing/required-checks-observation.md`
- Modify: `scripts/check-release-readiness.mjs`
- Test: `scripts/required-checks-observation-test.mjs` or a new
  `scripts/check-release-readiness-test.mjs` if the rule is easier to isolate.

- [ ] **Step 1: Create observation snapshot**

Create `docs/contributing/required-checks-observation.md` with this content,
updating the table values from the actual command output when executing:

~~~~markdown
# Required Checks Observation Snapshot

Last refreshed: 2026-06-02

Refresh command:

```bash
npm run checks:observation -- --json
```

This snapshot is a maintainer aid. GitHub Actions and
`docs/contributing/required-checks.md` remain the source of truth for branch
protection.

| Workflow | Consecutive green | Required | Ready | Latest run |
| --- | ---: | ---: | --- | --- |
| `.github/workflows/ci.yml` | 0 | 7 | no | refresh pending |
| `.github/workflows/codeql.yml` | 0 | 7 | no | refresh pending |
| `.github/workflows/connected-tests.yml` | 0 | 14 | no | refresh pending |
| `.github/workflows/nightly-compat.yml` | 0 | 1 | no | refresh pending |

Next action: run the refresh command with `gh` authenticated, then copy the
observed streaks into this table and update `docs/contributing/required-checks.md`
only for workflows that satisfy their observation window.
~~~~

- [ ] **Step 2: Add release-readiness rule**

In `scripts/check-release-readiness.mjs`, add these checks after the existing
required-check observation rule block or after the V1 evidence runner rules:

```js
requireIncludes(
  'R35.required-checks-observation-snapshot',
  'docs/contributing/required-checks-observation.md',
  'npm run checks:observation -- --json',
);
requireIncludes(
  'R36.required-checks-observation-policy-link',
  'docs/contributing/required-checks-observation.md',
  'docs/contributing/required-checks.md',
);
```

If `R35` or `R36` already exists, use the next available rule numbers and keep
the rule names stable.

- [ ] **Step 3: Run release readiness**

Run:

```bash
node scripts/check-release-readiness.mjs
```

Expected: PASS, including the new observation snapshot rules.

- [ ] **Step 4: Run observation tests**

Run:

```bash
npm run checks:observation:test
```

Expected: PASS.

- [ ] **Step 5: Commit Task 2**

```bash
git add docs/contributing/required-checks-observation.md scripts/check-release-readiness.mjs
git commit -m "docs(ci): add required check observation snapshot"
```

## Task 3: Ratchet One `fixthis-mcp` Detekt Budget Slice

**Files:**
- Modify one focused `fixthis-mcp/src/main/kotlin/...` file selected by detekt.
- Modify: `config/detekt/baseline-budget.json`

- [ ] **Step 1: Capture current detekt findings**

Run:

```bash
./gradlew :fixthis-mcp:detekt --no-daemon
```

Expected: If detekt passes with the baseline, inspect
`config/detekt/baseline-fixthis-mcp.xml` and choose a low-risk finding class
such as `MaxLineLength`, `LongMethod`, or duplicated literal cleanup. Do not
start with session persistence or event replay logic unless a focused test
already covers the exact behavior.

- [ ] **Step 2: Pick one narrow finding group**

Run:

```bash
rg -n "<ID>(MaxLineLength|LongMethod|ComplexMethod)" config/detekt/baseline-fixthis-mcp.xml | sed -n '1,20p'
```

Expected: choose one group affecting one file. Record the chosen rule and file
in the commit body if the change is not obvious.

- [ ] **Step 3: Refactor without behavior change**

Apply the smallest refactor that removes the selected baseline entry:

- For `MaxLineLength`, introduce a local val or split string construction.
- For `LongMethod`, extract a private helper in the same file.
- For duplicate string literals, introduce a private constant in the same file.

Do not move public APIs and do not rename persisted JSON fields.

- [ ] **Step 4: Run focused tests for the touched area**

Use the touched file to select tests:

```bash
./gradlew :fixthis-mcp:test --no-daemon
```

Expected: PASS. If the selected detekt cleanup touches only formatting or a
private constant, the full module test is the focused behavioral guard for this
task.

- [ ] **Step 5: Regenerate or verify baseline count**

Run:

```bash
./gradlew :fixthis-mcp:detekt --no-daemon
npm run detekt:baseline:check
```

Expected: detekt passes. If the budget check prints an improved
`config/detekt/baseline-fixthis-mcp.xml: <count>/421`, lower the
`config/detekt/baseline-budget.json` value for
`config/detekt/baseline-fixthis-mcp.xml` to `<count>`.

- [ ] **Step 6: Re-run budget check**

Run:

```bash
npm run detekt:baseline:check
```

Expected: PASS with `ok`, not `improved`, for the lowered budget.

- [ ] **Step 7: Commit Task 3**

```bash
git add fixthis-mcp/src/main/kotlin config/detekt/baseline-budget.json config/detekt/baseline-fixthis-mcp.xml
git commit -m "refactor(mcp): ratchet detekt baseline"
```

## Task 4: Lock Source Matching Evidence Depth Into The v1 Gate

**Files:**
- Modify only if the audit finds a missing assertion:
  `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`
- Modify: `fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt`
- Modify: `fixtures/source-matching/manifest.json`
- Modify: `scripts/source-matching-fixtures-test.mjs`

- [ ] **Step 1: Audit existing shared-component and layout-renderer tests**

Run:

```bash
rg -n "ranksSharedComponentCallSitesBySelectionEvidenceAndKeepsMediumConfidence|layout renderer|SHARED_COMPONENT_CALL_SITE" fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt
```

Expected: output includes the existing shared-component call-site ranking test
and layout-renderer coverage. If either coverage area is absent, add the missing
test before continuing.

- [ ] **Step 2: Add missing source-depth assertion only if audit fails**

If the audit shows missing shared-component call-site ranking coverage, add a
test to `SourceMatcherTest` using the existing `PrimaryButton` pattern. Required
assertions:

```kotlin
assertEquals(
    listOf(
        SourceLocationRef(file = "ui/ScreenB.kt", line = 13, mostLikely = true, recommendedEditSite = true),
        SourceLocationRef(file = "ui/ScreenA.kt", line = 42, mostLikely = false),
    ),
    candidate.callSites,
)
assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
assertTrue(candidate.riskFlags.contains(SourceCandidateRisk.SHARED_COMPONENT))
```

If this exact assertion already exists, do not duplicate it.

- [ ] **Step 3: Run focused source matcher test**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon
```

Expected: PASS if coverage already exists. If a new missing assertion was added,
expect the first run to fail only when implementation is also missing.

- [ ] **Step 4: Implement only if the new assertion fails**

If a new assertion fails, update `SourceMatcher.kt` so shared-component call-site
evidence ranks the best call site first while keeping the owner definition's
`SHARED_COMPONENT` caution and medium cap intact.

Implementation constraints:

- Do not change persisted JSON fields.
- Do not raise shared definition confidence above medium.
- Do not add a hard source-line guarantee to handoff text.

- [ ] **Step 5: Add fixture contract coverage**

Extend `fixtures/source-matching/manifest.json` with one source-index case for
the shared-component call-site recommendation. Then update
`scripts/source-matching-fixtures-test.mjs` so the manifest validation accepts
the expected call-site assertion fields used by the new case.

- [ ] **Step 6: Run source trust checks**

Run:

```bash
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon
npm run source-matching:fixtures:test
```

Expected: both PASS.

- [ ] **Step 7: Commit Task 4**

```bash
git add fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt \
  fixthis-compose-core/src/test/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcherTest.kt \
  fixtures/source-matching/manifest.json scripts/source-matching-fixtures-test.mjs
git commit -m "test(source): lock v1 source evidence depth"
```

## Task 5: Render Interop Boundary Evidence

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatter.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatterTest.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`

- [ ] **Step 1: Add failing formatter test**

Add a test to `TargetBoundaryContextFormatterTest` that builds an interop-risk
annotation with host, ancestor, and context nodes. Assert the compact line
contains three ordered context kinds:

```kotlin
assertTrue(line.contains("host="))
assertTrue(line.contains("ancestor="))
assertTrue(line.contains("context="))
```

Use existing fixture builders in the test file. If no builder exists, create a
private helper in the test file only.

- [ ] **Step 2: Add failing compact handoff test**

Add a test to `CompactHandoffRendererTest` asserting an interop-risk item renders
the boundary context line and keeps source candidates framed as verification
context, not exact ownership.

Required assertions:

```kotlin
assertTrue(markdown.contains("targetBoundary="))
assertTrue(markdown.contains("boundaryContext="))
assertTrue(markdown.contains("source hints are candidates"))
```

- [ ] **Step 3: Run focused MCP tests and verify failure**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --tests "*CompactHandoffRendererTest" --no-daemon
```

Expected: FAIL on the new context rendering assertions.

- [ ] **Step 4: Implement formatter and renderer changes**

Update `TargetBoundaryContextFormatter.kt` to emit the richer context line for
interop-risk selections. Update `CompactHandoffRenderer.kt` only as needed to
include that line in compact handoff output.

Rules:

- Keep the line compact.
- Cap rendered context at three entries.
- Do not claim XML/View or WebView DOM source ownership.

- [ ] **Step 5: Run focused MCP tests and verify pass**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --tests "*CompactHandoffRendererTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit Task 5**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatter.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetBoundaryContextFormatterTest.kt \
  fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt
git commit -m "feat(handoff): render interop boundary context"
```

## Task 6: Close SSE And v1 Release Gate Evidence

**Files:**
- Modify: `scripts/console-reliability-report.mjs`
- Modify: `scripts/console-reliability-report-test.mjs`
- Modify: `docs/contributing/release-readiness.md`
- Modify: `scripts/check-release-readiness.mjs`
- Modify: `scripts/release-gate.mjs`
- Modify: `scripts/release-gate-test.mjs`

- [ ] **Step 1: Add failing reliability report assertion**

In `scripts/console-reliability-report-test.mjs`, add a test asserting healthy
SSE observations fail when session or preview fallback polling happens after the
healthy marker time. The expected failure must name the fallback endpoint.

Use the existing `buildConsoleReliabilityReport` helper and assert:

```js
assert.equal(report.status, "failed");
assert.match(report.failures.join("\n"), /fallback polling/i);
```

- [ ] **Step 2: Implement report classification**

In `scripts/console-reliability-report.mjs`, classify fallback session,
history, and preview requests after a healthy EventSource marker as failures
unless the observation explicitly marks EventSource disconnected.

- [ ] **Step 3: Add v1 release readiness section**

Append this section to `docs/contributing/release-readiness.md` near the other
claim manifests:

```markdown
## v1 Residual Risk Closure Gate

The residual-risk closure may be claimed only when each area below has matching
evidence from the release commit.

| Claim | Required evidence |
| --- | --- |
| CLI Android environment detection is consistent across env vars, project `local.properties`, and default SDK locations. | `./gradlew :fixthis-cli:test --tests "*AdbTest" --no-daemon`. |
| Source matching recommends specific shared-component call sites without raising shared definitions above medium confidence. | `./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon` and `npm run source-matching:fixtures:test`. |
| Interop handoffs render boundary context without exact XML/View or WebView ownership claims. | `./gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --tests "*CompactHandoffRendererTest" --no-daemon`. |
| Healthy SSE sessions do not use fallback session or preview polling. | `node --test scripts/console-reliability-report-test.mjs` and `npm run console:browser:reliability`. |
| Release and required-check readiness evidence is refreshable and documented. | `node scripts/check-release-readiness.mjs` and `npm run checks:observation -- --json`. |
```

- [ ] **Step 4: Add release-readiness rules**

In `scripts/check-release-readiness.mjs`, add rules requiring:

```js
requireIncludes(
  'R37.v1-residual-risk-closure-gate',
  'docs/contributing/release-readiness.md',
  '## v1 Residual Risk Closure Gate',
);
requireIncludes(
  'R38.v1-adb-discovery-evidence',
  'docs/contributing/release-readiness.md',
  '`./gradlew :fixthis-cli:test --tests "*AdbTest" --no-daemon`',
);
requireIncludes(
  'R39.v1-sse-fallback-evidence',
  'docs/contributing/release-readiness.md',
  '`npm run console:browser:reliability`',
);
```

Use the next available rule numbers if these are occupied.

- [ ] **Step 5: Wire release gate claims**

Update `scripts/release-gate.mjs` so the residual-risk claims map to the same
commands listed in the v1 gate. Update `scripts/release-gate-test.mjs` with
assertions that the gate report includes claim labels for ADB discovery,
source matching, interop boundary context, SSE fallback, and required-check
readiness.

- [ ] **Step 6: Run gate tests**

Run:

```bash
node --test scripts/console-reliability-report-test.mjs
npm run release:gate:test
node scripts/check-release-readiness.mjs
```

Expected: all PASS.

- [ ] **Step 7: Commit Task 6**

```bash
git add scripts/console-reliability-report.mjs scripts/console-reliability-report-test.mjs \
  docs/contributing/release-readiness.md scripts/check-release-readiness.mjs \
  scripts/release-gate.mjs scripts/release-gate-test.mjs
git commit -m "test(release): gate residual risk closure evidence"
```

## Task 7: Final Evidence, Docs, And Graph Refresh

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/releases/unreleased.md`
- Generated but not committed: `build/reports/**`
- Generated but not committed: `graphify-out/**`

- [ ] **Step 1: Update release notes**

Add concise unreleased entries for:

- CLI ADB discovery fallback hardening.
- Required-check observation snapshot.
- v1 residual-risk gate.
- Source/interop/SSE evidence closure.
- Detekt baseline ratchet.

- [ ] **Step 2: Run fast evidence**

Run:

```bash
npm run evidence:fast
```

Expected: PASS.

- [ ] **Step 3: Run trust evidence**

Run:

```bash
npm run evidence:trust -- --continue
```

Expected: PASS. If Android runtime prerequisites are unavailable, rerun without
strict runtime expectations and ensure runtime-only rows are deferred with exact
reason. If `--strict-runtime` is used, runtime rows must pass or the task fails.

- [ ] **Step 4: Run release gate**

Run:

```bash
npm run release:gate:test
node scripts/check-release-readiness.mjs
```

Expected: PASS.

- [ ] **Step 5: Run Gradle focus matrix**

Run:

```bash
./gradlew :fixthis-cli:test :fixthis-compose-core:test :fixthis-mcp:test :fixthis-mcp:detekt --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Refresh Graphify**

Run:

```bash
graphify update .
```

Expected: command completes. Do not commit `graphify-out/`.

- [ ] **Step 7: Check workspace hygiene**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors. `git status --short` should show only intended
tracked file changes plus ignored generated directories.

- [ ] **Step 8: Commit Task 7**

```bash
git add CHANGELOG.md docs/releases/unreleased.md
git commit -m "docs(release): record residual risk closure evidence"
```

## Final Verification Matrix

Run before asking for merge or release:

```bash
npm run evidence:fast
npm run evidence:trust -- --continue
npm run release:gate:test
node scripts/check-release-readiness.mjs
npm run detekt:baseline:check
./gradlew :fixthis-cli:test :fixthis-compose-core:test :fixthis-mcp:test :fixthis-mcp:detekt --no-daemon
git diff --check
graphify update .
```

Strict connected evidence, when Android SDK and a ready emulator/device are
available:

```bash
npm run source-matching:fixtures:runtime -- --strict
npm run real-copy-prompt:smoke -- --strict
npm run agent-loop:smoke -- --strict
npm run external-fixture:matrix -- --strict
```

## Plan Self-Review

- Spec coverage: every design goal maps to at least one task.
- Placeholder scan: no open placeholder tokens remain; commands and expected
  outcomes are explicit.
- Type consistency: Kotlin examples use existing `Adb.defaultAdbExecutable`,
  `AdbTest`, and JUnit style.
- Scope control: no new package channel or unverified agent writer is included.
- Runtime honesty: strict connected checks are pass-only; non-strict evidence can
  defer with exact reason.
