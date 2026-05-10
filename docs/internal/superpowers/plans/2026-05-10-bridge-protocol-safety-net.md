# Bridge Protocol Safety Net — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three preventive fixes layered onto the stale-binary-detection feature (commit `9f8711d`):
1. Direction-aware protocol-mismatch UI (R1) so future VERSION bumps don't ship misleading banner copy.
2. CI-time sync test (R2) so a forgotten mirror site fails the build with a diagnostic.
3. KDoc on `BridgeStatus.installEpochMillis` and `BridgeStatus.sidekickBuildEpochMs` (R3) so future contributors don't confuse the two timestamps.

**Architecture:** No wire format changes. Console-side only for R1; new Kotlin unit test for R2; pure docstring delta for R3.

**Tech Stack:** Vanilla ES module JS console (`fixthis-mcp/src/main/console/staleness.js`); Kotlin/JUnit5 + kotlin.test for the sync test (`:fixthis-mcp:test` runtime); Kotlin source for KDoc.

**Reference design:** `docs/superpowers/specs/2026-05-10-bridge-protocol-safety-net-design.md`
**Predecessor:** `docs/superpowers/plans/2026-05-10-stale-binary-detection.md` (merged 2026-05-10 as commit `9f8711d`).

---

## File Structure

### Modify

- `fixthis-mcp/src/main/console/staleness.js` — replace `checkProtocolCompat`, add `parseProtocolVersion` + `compareProtocolVersion`.
- `fixthis-mcp/src/main/resources/console/app.js` — auto-regenerated.
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt` — replace `applyConnectionStatusCallsCheckProtocolCompat` (still ok), add a directional-wording substring test.
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt` — add KDoc on the two epoch fields of `BridgeStatus` (no semantic change).

### Create

- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/BridgeProtocolVersionSyncTest.kt` — new Kotlin unit test enforcing the 4-site mirror sync.

### Run (no commit, validation only)

- `node scripts/build-console-assets.mjs`
- `./gradlew :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest`
- `node --check fixthis-mcp/src/main/resources/console/app.js`
- `git diff --check`

---

# Task 1 — Numeric protocol compare + directional wording (R1)

**Files:**
- Modify: `fixthis-mcp/src/main/console/staleness.js`
- Modify (auto-regenerated): `fixthis-mcp/src/main/resources/console/app.js`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `FeedbackConsoleServerTest.kt` (next to the existing `stalenessExposesMinimumProtocolVersion`):

```kotlin
@Test
fun stalenessExposesProtocolVersionParser() {
    val html = FeedbackConsoleAssets.indexHtml
    assertTrue(html.contains("function parseProtocolVersion"),
        "must expose parseProtocolVersion helper")
    assertTrue(html.contains("function compareProtocolVersion"),
        "must expose compareProtocolVersion helper")
}

@Test
fun protocolCompatBannersBothDirections() {
    val html = FeedbackConsoleAssets.indexHtml
    val body = javascriptFunctionBody(html, "checkProtocolCompat")
    assertTrue(body.contains("protocol-sidekick-old-"),
        "must produce sidekick-old-direction hash")
    assertTrue(body.contains("protocol-console-old-"),
        "must produce console-old-direction hash")
    assertTrue(body.contains("compareProtocolVersion("),
        "must call compareProtocolVersion")
    assertFalse(
        body.contains("=== MinimumSupportedProtocolVersion") ||
            body.contains("!== MinimumSupportedProtocolVersion"),
        "must NOT compare via string equality (use numeric compareProtocolVersion instead)",
    )
}
```

- [ ] **Step 2: Run, verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*staleness*' --tests '*protocolCompat*' -i
```

Expected: the new tests fail (helper functions don't exist; old `!==` is still present).

- [ ] **Step 3: Modify `staleness.js`**

Locate the existing block in `fixthis-mcp/src/main/console/staleness.js` near line 57-73:

```javascript
const MinimumSupportedProtocolVersion = '1.1';

function checkProtocolCompat(status) {
  const v = status?.bridgeProtocolVersion;
  if (typeof v !== 'string') return;
  if (v !== MinimumSupportedProtocolVersion) {
    renderStalenessBanner({
      severity: 'critical',
      headline: `Bridge protocol v${v} is too old`,
      detail: 'Reinstall the sample APK (./gradlew :app:installDebug) to update sidekick.',
      hash: `protocol-${v}`,
    });
  }
}
```

Replace it with the directional-compare implementation. Match the existing 12-space top-scope indentation:

```javascript
            const MinimumSupportedProtocolVersion = '1.1';

            // Parse "1.1" -> [1, 1]; "1" -> [1]; non-numeric / null / undefined -> null.
            function parseProtocolVersion(s) {
              if (typeof s !== 'string') return null;
              const parts = s.split('.').map((token) => Number(token));
              if (parts.length === 0) return null;
              if (parts.some((n) => !Number.isFinite(n))) return null;
              return parts;
            }

            // Returns negative / 0 / positive. Shorter array right-padded with 0.
            function compareProtocolVersion(a, b) {
              const len = Math.max(a.length, b.length);
              for (let i = 0; i < len; i++) {
                const ai = a[i] ?? 0;
                const bi = b[i] ?? 0;
                if (ai !== bi) return ai - bi;
              }
              return 0;
            }

            function checkProtocolCompat(status) {
              const reported = parseProtocolVersion(status?.bridgeProtocolVersion);
              const expected = parseProtocolVersion(MinimumSupportedProtocolVersion);
              if (!reported || !expected) return;
              const cmp = compareProtocolVersion(reported, expected);
              if (cmp === 0) return;
              if (cmp < 0) {
                renderStalenessBanner({
                  severity: 'critical',
                  headline: `Sample app bridge protocol v${status.bridgeProtocolVersion} is older than this console (expects v${MinimumSupportedProtocolVersion})`,
                  detail: 'Reinstall the sample APK (./gradlew :app:installDebug) to update sidekick.',
                  hash: `protocol-sidekick-old-${status.bridgeProtocolVersion}`,
                });
              } else {
                renderStalenessBanner({
                  severity: 'critical',
                  headline: `This console is older than sample app bridge protocol v${status.bridgeProtocolVersion} (expects v${MinimumSupportedProtocolVersion})`,
                  detail: 'Restart fixthis-mcp + hard reload the browser tab to update console.',
                  hash: `protocol-console-old-${status.bridgeProtocolVersion}`,
                });
              }
            }
```

Leave the `checkSidekickBuildEpoch` function below this block unchanged.

- [ ] **Step 4: Rebundle + retest**

```bash
node scripts/build-console-assets.mjs
node --check fixthis-mcp/src/main/resources/console/app.js
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: green. Both new tests PASS, the existing `applyConnectionStatusCallsCheckProtocolCompat` and `stalenessExposesMinimumProtocolVersion` continue to PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/staleness.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "$(cat <<'EOF'
feat(console): direction-aware protocol-version mismatch banner

Replaces the symmetric string-equality check in checkProtocolCompat with
numeric component-wise compare on the major.minor parsed shape, then picks
banner wording by direction:
  - sidekick reports older -> "Sample app bridge protocol vN is older..."
  - sidekick reports newer -> "This console is older than sample app..."

Adds parseProtocolVersion + compareProtocolVersion helpers exposed at top
scope. Hash component embeds the direction so dismiss does not silently
absorb both sides.

Task: 1 (bridge-protocol-safety-net, R1)
Risk: mid
Files: staleness.js, app.js, FeedbackConsoleServerTest.kt
EOF
)"
```

---

# Task 2 — Mirror-site sync test (R2)

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/BridgeProtocolVersionSyncTest.kt`

- [ ] **Step 1: Verify the four current values**

```bash
grep -rn "BridgeProtocolVersion\|MinimumSupportedProtocolVersion\|VERSION:.*String" \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocol.kt \
  fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt \
  fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ServerVersionRoutes.kt \
  fixthis-mcp/src/main/console/staleness.js
```

Expected output (all four equal "1.1"):

```
fixthis-compose-sidekick/.../bridge/BridgeProtocol.kt:12:    const val VERSION: String = "1.1"
fixthis-cli/.../BridgeClient.kt:31:private const val BridgeProtocolVersion = "1.1"
fixthis-mcp/.../ServerVersionRoutes.kt:8:private const val BridgeProtocolVersion = "1.1"
fixthis-mcp/src/main/console/staleness.js:57:            const MinimumSupportedProtocolVersion = '1.1';
```

If any value differs from the others, halt — do not proceed; the sync test will fail by design.

- [ ] **Step 2: Create the test file**

Write `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/BridgeProtocolVersionSyncTest.kt` with this exact body:

```kotlin
package io.beyondwin.fixthis.mcp.console

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class BridgeProtocolVersionSyncTest {
    @Test
    fun allMirrorSitesAgreeOnBridgeProtocolVersion() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }

        val sites = listOf(
            MirrorSite(
                "BridgeProtocol.kt",
                File(
                    root,
                    "fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocol.kt",
                ),
                Regex("""const val VERSION: String = "([^"]+)""""),
            ),
            MirrorSite(
                "BridgeClient.kt",
                File(root, "fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/BridgeClient.kt"),
                Regex("""const val BridgeProtocolVersion = "([^"]+)""""),
            ),
            MirrorSite(
                "ServerVersionRoutes.kt",
                File(
                    root,
                    "fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/ServerVersionRoutes.kt",
                ),
                Regex("""const val BridgeProtocolVersion = "([^"]+)""""),
            ),
            MirrorSite(
                "staleness.js",
                File(root, "fixthis-mcp/src/main/console/staleness.js"),
                Regex("""const MinimumSupportedProtocolVersion = '([^']+)'"""),
            ),
        )

        val extracted = sites.associate { site ->
            require(site.file.isFile) { "Mirror site file not found: ${site.file}" }
            val match = site.regex.find(site.file.readText())
                ?: error(
                    "Mirror site ${site.label} (${site.file}) does not match regex " +
                        "${site.regex.pattern} — has the constant been renamed? " +
                        "Update BridgeProtocolVersionSyncTest.kt accordingly.",
                )
            site.label to match.groupValues[1]
        }

        val unique = extracted.values.toSet()
        assertEquals(
            1,
            unique.size,
            "Bridge protocol version mismatch across mirror sites: $extracted. " +
                "All four sites must hold the same string. See CLAUDE.md " +
                "\"Bridge Protocol Compatibility\" for the bump rule.",
        )
    }

    private data class MirrorSite(val label: String, val file: File, val regex: Regex)
}
```

- [ ] **Step 3: Run, verify GREEN on the happy path**

```bash
./gradlew :fixthis-mcp:test --tests '*BridgeProtocolVersionSyncTest*' -i
```

Expected: 1 test PASS.

- [ ] **Step 4: Smoke-test the failure path**

Temporarily edit `fixthis-mcp/src/main/console/staleness.js` line 57 to `const MinimumSupportedProtocolVersion = '1.0';` (revert immediately after).

```bash
./gradlew :fixthis-mcp:test --tests '*BridgeProtocolVersionSyncTest*' -i
```

Expected: 1 test FAIL with diagnostic listing all 4 sites and showing `staleness.js=1.0` against `BridgeProtocol.kt=1.1, BridgeClient.kt=1.1, ServerVersionRoutes.kt=1.1`.

Revert the edit, re-run, confirm GREEN.

- [ ] **Step 5: Smoke-test the regex-rename path (optional but recommended)**

Temporarily rename the const in `staleness.js` to `const MinimumProtocolVersion = '1.1';` (note: drop the `Supported`).

```bash
./gradlew :fixthis-mcp:test --tests '*BridgeProtocolVersionSyncTest*' -i
```

Expected: test halts with `error("Mirror site staleness.js ... does not match regex ...")`. This is the desired soft-failure mode — the test asks the refactorer to update the regex along with the rename.

Revert.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/BridgeProtocolVersionSyncTest.kt
git commit -m "$(cat <<'EOF'
test(mcp): enforce bridge-protocol-version mirror-site sync at CI time

Adds BridgeProtocolVersionSyncTest, a Kotlin unit test that reads the four
mirror-site source files (BridgeProtocol.kt, BridgeClient.kt,
ServerVersionRoutes.kt, staleness.js), extracts each declared protocol
version via regex, and asserts all four are equal. Runs as part of
:fixthis-mcp:test so a forgotten bump fails standard CI with a diagnostic
naming each file and its observed value.

Implements R2 from the bridge-protocol-safety-net design.

Task: 2 (bridge-protocol-safety-net, R2)
Risk: mid
Files: BridgeProtocolVersionSyncTest.kt
EOF
)"
```

---

# Task 3 — KDoc on BridgeStatus epoch fields (R3)

**Files:**
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`

- [ ] **Step 1: Locate the BridgeStatus data class (line ~210)**

```bash
grep -n "data class BridgeStatus\|installEpochMillis\|sidekickBuildEpochMs" \
  fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt
```

Expected: lines around 210-225 (primary constructor) and 240-247 (secondary constructor pass-through).

- [ ] **Step 2: Insert KDoc above each of the two epoch fields**

In the `BridgeStatus` primary constructor, replace the two existing field declarations:

```kotlin
    val installEpochMillis: Long? = null,
    val sidekickBuildEpochMs: Long? = null,
```

With KDoc-annotated versions:

```kotlin
    /**
     * APK install/update timestamp in milliseconds (`PackageManager.lastUpdateTime`).
     * Populated by [AndroidBridgeEnvironment.readInstallEpochMillis]; null if read fails.
     * Used to detect "the user reinstalled the sample APK" (e.g. for cache invalidation),
     * NOT for build-binary staleness — for that, see [sidekickBuildEpochMs].
     */
    val installEpochMillis: Long? = null,
    /**
     * Sidekick BUILD timestamp in milliseconds (minute-rounded; from `BuildInfo.BUILD_EPOCH_MS`).
     * Populated unconditionally by sidekick when this version of the protocol is in effect.
     * Compared by the console's `checkSidekickBuildEpoch()` against the bundled
     * `ConsoleBuildEpochMs`; drift > 5 min surfaces a "sample sidekick is older than console"
     * staleness banner. NOT the install time — for that, see [installEpochMillis].
     */
    val sidekickBuildEpochMs: Long? = null,
```

Do NOT touch the secondary constructor block (lines ~228-247) or the `status()` function (~line 372). The KDoc is on field declarations only.

- [ ] **Step 3: Verify Kotlin compile passes**

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests '*BridgeServerTest*' -i
```

Expected: GREEN. KDoc is comments only — semantic shape is unchanged. The existing `bridgeStatusKeepsJvmConstructorCompatibility` test continues to PASS because the constructor parameter list is unchanged.

- [ ] **Step 4: Spot-check that KDoc renders for the IDE**

Open `BridgeStatus` in the IDE, hover over `installEpochMillis` — confirm the tooltip shows the KDoc body. (Optional verification step; non-blocking.)

- [ ] **Step 5: Commit**

```bash
git add fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt
git commit -m "$(cat <<'EOF'
docs(sidekick): clarify BridgeStatus.installEpochMillis vs sidekickBuildEpochMs

Adds KDoc to both Long? fields explaining source (PackageManager
lastUpdateTime vs BuildInfo.BUILD_EPOCH_MS), units (millis, minute-rounded
for the build epoch), and downstream consumer (cache invalidation vs
console staleness banner). Each KDoc cross-references the other so
confusion is immediately corrected.

Implements R3 from the bridge-protocol-safety-net design. No semantic
change; constructor compatibility test continues to pass.

Task: 3 (bridge-protocol-safety-net, R3)
Risk: low
Files: BridgeServer.kt
EOF
)"
```

---

# Task 4 — Final sweep + CHANGELOG entry

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Run full test suite**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test \
          :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
```

Expected: BUILD SUCCESSFUL. Test count delta: +3 from this PR (Task 1 adds 2, Task 2 adds 1, Task 3 adds 0).

- [ ] **Step 2: Lint**

```bash
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

Both must exit 0.

- [ ] **Step 3: CHANGELOG entry**

Prepend under `## Unreleased` → `### Changed` (the existing Phase 3 `### Changed` block from stale-binary-detection):

```markdown
- Console staleness banner now distinguishes "sample app sidekick is older than console" vs "this console is older than sample app sidekick" via numeric component-wise compare on the bridge protocol version, replacing the previous symmetric string equality. Banner copy and dismiss-hash include the direction. (R1 from bridge-protocol-safety-net.)
```

Prepend under `### Changed` (or under a new line in the existing `### Added`/`### Changed` block — pick what feels natural):

```markdown
- New `BridgeProtocolVersionSyncTest` unit test in `:fixthis-mcp:test` enforces that all four bridge-protocol-version mirror sites (`BridgeProtocol.kt`, `BridgeClient.kt`, `ServerVersionRoutes.kt`, `staleness.js`) hold the same string. A forgotten bump now fails standard CI with a diagnostic naming each file and its observed value. (R2 from bridge-protocol-safety-net.)
```

KDoc-only changes (R3) typically don't need a CHANGELOG entry; skip.

- [ ] **Step 4: Commit**

```bash
git add CHANGELOG.md
git commit -m "$(cat <<'EOF'
chore: CHANGELOG entry for bridge-protocol-safety-net

Task: 4 (bridge-protocol-safety-net)
Risk: low
Files: CHANGELOG.md
EOF
)"
```

---

## Self-Review

(Performed by the plan author before handing off.)

**Spec coverage:**
- G1 (direction-aware UI): Task 1 ✓
- G2 (CI sync test): Task 2 ✓
- G3 (KDoc clarity): Task 3 ✓

**Placeholder scan:** none. Every constant value, file path, and regex is concrete.

**Type/name consistency:**
- `parseProtocolVersion` and `compareProtocolVersion` — top-level in staleness.js, called only by `checkProtocolCompat`.
- `MinimumSupportedProtocolVersion` — unchanged identifier; semantics shift slightly (now "minimum supported" rather than "current") per spec §6. The string value `'1.1'` does not change.
- KDoc `[installEpochMillis]` / `[sidekickBuildEpochMs]` references resolve within the same file's scope.

**Forward-reference handling:**
- Task 1 changes only console-side; Task 2's sync test will pass before Task 1's diff lands because the regex captures `'1.1'` from the literal in staleness.js — value is unchanged.
- Task 3 KDoc references `[AndroidBridgeEnvironment.readInstallEpochMillis]` which exists in the same file. KDoc references `BuildInfo.BUILD_EPOCH_MS` and `ConsoleBuildEpochMs` as plain backticks (not links) since they're cross-module.

**Test coverage gaps (manual / E2E):**
- Banner directional wording's actual visual rendering (not just substring presence) — manual smoke at next bump.
- Sync test failure-mode diagnostic readability — covered by Task 2 Step 4 smoke (revert immediately).

**Open Questions resolved (per spec §9):**
- Q1 (compare scheme): numeric component-wise, dot-split.
- Q2 (lint location): Kotlin test in `:fixthis-mcp:test`.
- Q3 (KDoc vs rename): KDoc only.

**Risk re-assessment:**
- Task 1 — risk **mid**. Replaces an existing function used in heartbeat-path (called by `applyConnectionStatus`); regression in the helper-function shape would silently break protocol detection. Mitigated by direction-substring + compare-call assertion in tests.
- Task 2 — risk **mid**. New test that reads files via I/O; first-time run could fail due to repo-root walker quirks in CI vs local. Mitigated by reusing the same walker pattern that `generatedConsoleAppMatchesConsoleSourceModules` already uses.
- Task 3 — risk **low**. KDoc-only.
- Task 4 — risk **low**. CHANGELOG.

No additional fixes required.

---

## Execution Handoff

**Plan saved to `docs/superpowers/plans/2026-05-10-bridge-protocol-safety-net.md`. Two execution options:**

**1. Subagent-Driven** — Phase-per-task fresh subagent dispatch with review/verify gates. ~1.5–2 hrs total (4 tasks × ~25min each including review/verify cycles).

**2. Inline Execution** — Single session, ~60 min total (R1 ~20 min, R2 ~25 min, R3 ~10 min, R4 CHANGELOG ~5 min).

**3. Multi-agent-executor** — `/kws-skills:kws-claude-multi-agent-executor` with this plan + the design spec.

**Which approach?**
