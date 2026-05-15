# Bridge Protocol Safety Net — Design Spec

**Date:** 2026-05-10
**Status:** Design (companion plan: `plans/2026-05-10-bridge-protocol-safety-net.md`)
**Predecessor:** stale-binary-detection (commit `9f8711d`, merged 2026-05-10) — added the staleness banner, `/api/server-version`, `BridgeStatus.sidekickBuildEpochMs`, `BridgeProtocol.VERSION` 1.0 → 1.1.

---

## 1. Why this exists

The stale-binary-detection feature shipped 4 mirror sites for `BridgeProtocol.VERSION` and a string-equality protocol-compat check on the console. After merging the feature alongside concurrent main-branch work (`installEpochMillis` field added in parallel), three latent issues became visible:

- **R1 — wording is directional but compare is symmetric.** `checkProtocolCompat(status)` uses `status.bridgeProtocolVersion !== MinimumSupportedProtocolVersion`. The banner says **"Bridge protocol vN is too old"** regardless of which side is actually older. With the next bump (1.1 → 1.2), an older console seeing a newer sidekick will incorrectly say the sidekick is "too old."
- **R2 — 4-site sync is a rule, not a check.** `BridgeProtocol.VERSION` (sidekick), `BridgeProtocolVersion` (CLI), `BridgeProtocolVersion` (mcp/ServerVersionRoutes), and `MinimumSupportedProtocolVersion` (staleness.js) must all hold the same string at all times. CLAUDE.md "Bridge Protocol Compatibility" documents this for humans, but no automated check fails when one is forgotten. Silent drift will surface only at runtime, on a user's device.
- **R3 — two timestamps with similar names.** `BridgeStatus` now carries both `installEpochMillis` (PackageManager `lastUpdateTime`) and `sidekickBuildEpochMs` (BuildInfo build time, minute-rounded). The names look almost identical to a reader; their semantics differ. Without explicit doc, future contributors are likely to use the wrong one when adding a new staleness check.

This design fixes all three in a single coordinated PR. None block today's correctness; each prevents a class of future bug.

## 2. Goals

- **G1 — Direction-aware protocol mismatch UI.** The console's banner correctly identifies which side is older. Comparison logic uses numeric component-wise compare on a `major.minor` shape so a future patch like 1.2, 2.0, 1.10 works correctly.
- **G2 — Compile/CI-time guarantee that all 4 sites match.** A single forgotten mirror site fails the build, with a diagnostic that lists every site and its current value. Running `:fixthis-mcp:test` catches it in standard CI.
- **G3 — Self-documenting `BridgeStatus`.** A reader can tell from KDoc alone what `installEpochMillis` vs `sidekickBuildEpochMs` mean, what populates them, and what each is used for.

Non-goals: semver pre-release/build metadata (`1.1-rc1`, `1.1+build`) — out of scope; current values are pure `major.minor`.

## 3. Current state (post stale-binary-detection)

### Mirror sites

```
fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocol.kt:12
    const val VERSION: String = "1.1"

fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/BridgeClient.kt:31
    private const val BridgeProtocolVersion = "1.1"

fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ServerVersionRoutes.kt:8
    private const val BridgeProtocolVersion = "1.1"

fixthis-mcp/src/main/console/staleness.js:57
    const MinimumSupportedProtocolVersion = '1.1';
```

Sync rule lives at `CLAUDE.md` "Bridge Protocol Compatibility" — currently human-only.

### `checkProtocolCompat` today (`fixthis-mcp/src/main/console/staleness.js:60-73`)

```javascript
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

The headline assumes the SIDEKICK side is older than the console. Fires symmetrically.

### `BridgeStatus` today (`fixthis-compose-sidekick/.../bridge/BridgeServer.kt:209-249`)

```kotlin
@Serializable
data class BridgeStatus(
    val activity: String? = null,
    val rootsCount: Int,
    val sidekickVersion: String,
    val bridgeProtocolVersion: String,
    val sourceIndexAvailable: Boolean,
    val capabilities: BridgeCapabilities = BridgeCapabilities(),
    val screenInteractive: Boolean? = null,
    val keyguardLocked: Boolean? = null,
    val appForeground: Boolean? = null,
    val pictureInPicture: Boolean? = null,
    val installEpochMillis: Long? = null,
    val sidekickBuildEpochMs: Long? = null,
) { … secondary 5-arg constructor … }
```

No KDoc on either Long? field.

## 4. Target design

### 4.1 Numeric protocol compare + directional wording (G1)

`checkProtocolCompat` is replaced by component-wise numeric compare on the `major.minor` parsed shape. Three outcomes:

| Reported vs Expected | Action | Banner severity | Banner intent |
|---|---|---|---|
| equal | silent | — | — |
| reported < expected | render | critical | sidekick (server side) is older — reinstall APK |
| reported > expected | render | critical | console (client side) is older — restart fixthis-mcp + hard reload |

The hash component embeds direction so a user dismissing one direction's banner does not silently dismiss the other.

#### New helper functions (top-level in staleness.js)

```javascript
// Parse "1.1" -> [1, 1]; "1" -> [1]; invalid -> null.
function parseProtocolVersion(s) {
  if (typeof s !== 'string') return null;
  const parts = s.split('.').map((token) => Number(token));
  if (parts.length === 0) return null;
  if (parts.some((n) => !Number.isFinite(n))) return null;
  return parts;
}

// Returns negative / 0 / positive. Shorter array is right-padded with 0.
function compareProtocolVersion(a, b) {
  const len = Math.max(a.length, b.length);
  for (let i = 0; i < len; i++) {
    const ai = a[i] ?? 0;
    const bi = b[i] ?? 0;
    if (ai !== bi) return ai - bi;
  }
  return 0;
}
```

#### Replacement `checkProtocolCompat`

```javascript
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

#### Test coverage

- `stalenessExposesMinimumProtocolVersion` (existing) — keep as-is; `parseProtocolVersion` and `compareProtocolVersion` are also asserted-by-substring in a new test.
- New: `protocolCompatHandlesBothDirections` — bundle contains both `protocol-sidekick-old-` and `protocol-console-old-` hash format substrings, and contains `parseProtocolVersion` + `compareProtocolVersion` declarations.

### 4.2 Mirror-site sync test (G2)

A new Kotlin test in `fixthis-mcp` reads the four mirror files as text and asserts the extracted version strings are all equal. The test runs as part of `:fixthis-mcp:test` (already invoked by CI workflow `.github/workflows/ci.yml` line 33-37 area), so a forgotten bump fails the build with a diagnostic naming each file and its current value.

#### Why a Kotlin test, not a Node script

- `:fixthis-mcp:test` is already in CI; no new workflow step needed.
- Test infrastructure is already used by FeedbackConsoleServerTest to read `staleness.js` source via the existing repo-root walker (`generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }`).
- Diagnostics use Kotlin's `assertEquals` with a custom message — easier than rolling a Node JSON-stdout protocol.

#### Sync test (target shape)

```kotlin
package io.github.beyondwin.fixthis.mcp.console

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
                File(root, "fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocol.kt"),
                Regex("""const val VERSION: String = "([^"]+)""""),
            ),
            MirrorSite(
                "BridgeClient.kt",
                File(root, "fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/BridgeClient.kt"),
                Regex("""const val BridgeProtocolVersion = "([^"]+)""""),
            ),
            MirrorSite(
                "ServerVersionRoutes.kt",
                File(root, "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ServerVersionRoutes.kt"),
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
                ?: error("Mirror site ${site.label} (${site.file}) does not match regex ${site.regex.pattern}")
            site.label to match.groupValues[1]
        }

        val unique = extracted.values.toSet()
        assertEquals(
            1,
            unique.size,
            "Bridge protocol version mismatch across mirror sites: $extracted",
        )
    }

    private data class MirrorSite(val label: String, val file: File, val regex: Regex)
}
```

#### Failure mode

When a contributor bumps `BridgeProtocol.VERSION` to "1.2" but forgets `staleness.js`:

```
Bridge protocol version mismatch across mirror sites: {BridgeProtocol.kt=1.2, BridgeClient.kt=1.2, ServerVersionRoutes.kt=1.2, staleness.js=1.1}
```

Diagnostic names every site and its observed value. Fix is obvious from the message.

#### Why regex-based extraction

The sync test is for *correctness contract drift*, not for *code style*. Regex matches the constant declaration and rejects subtle changes that would invalidate the extraction (renamed const, changed quote style). If the extraction regex stops matching after a refactor, the test halts with a "does not match regex" error — a soft failure that prompts the refactorer to update the regex along with the rename. This is the desired behavior.

### 4.3 KDoc on BridgeStatus epoch fields (G3)

`installEpochMillis` and `sidekickBuildEpochMs` get explicit KDoc explaining source, units, and downstream consumer.

```kotlin
@Serializable
data class BridgeStatus(
    val activity: String? = null,
    val rootsCount: Int,
    val sidekickVersion: String,
    val bridgeProtocolVersion: String,
    val sourceIndexAvailable: Boolean,
    val capabilities: BridgeCapabilities = BridgeCapabilities(),
    val screenInteractive: Boolean? = null,
    val keyguardLocked: Boolean? = null,
    val appForeground: Boolean? = null,
    val pictureInPicture: Boolean? = null,
    /**
     * APK install/update timestamp in milliseconds (PackageManager `lastUpdateTime`).
     * Populated by [AndroidBridgeEnvironment.readInstallEpochMillis]; null if read fails.
     * Used to detect "the user reinstalled the sample APK" (e.g. for cache invalidation),
     * NOT for build-binary staleness — for that, see [sidekickBuildEpochMs].
     */
    val installEpochMillis: Long? = null,
    /**
     * Sidekick BUILD timestamp in milliseconds (minute-rounded, from `BuildInfo.BUILD_EPOCH_MS`).
     * Populated unconditionally by sidekick when this version of the protocol is in effect.
     * Compared by the console's `checkSidekickBuildEpoch()` against the bundled
     * `ConsoleBuildEpochMs`; drift > 5 min surfaces a "sample sidekick is older than console"
     * staleness banner. NOT the install time — for that, see [installEpochMillis].
     */
    val sidekickBuildEpochMs: Long? = null,
) {
    constructor(
        activity: String?,
        rootsCount: Int,
        sidekickVersion: String,
        bridgeProtocolVersion: String,
        sourceIndexAvailable: Boolean,
    ) : this(
        … (unchanged secondary constructor body) …
    )
}
```

KDoc is intentionally cross-referencing — each field points the reader at the other so confusion is immediately corrected.

## 5. Wire format compatibility

No wire format change. Both fields are already nullable Longs; the JSON shape on the wire is identical before and after this PR. The numeric-compare change is internal to the console; the `bridgeProtocolVersion` field on the wire continues to be a `string` exactly as before.

## 6. Versions of `MinimumSupportedProtocolVersion` going forward

After this PR, `MinimumSupportedProtocolVersion` is no longer just a "current version mirror" — it is the *minimum supported* version. The semantics shift slightly:

- **Before (`!==` compare):** any mismatch fires a banner. Effectively "must equal current."
- **After (numeric compare):** a sidekick reporting a version >= MinimumSupportedProtocolVersion is *accepted*. Only sidekicks reporting strictly less fire the "sidekick is too old" banner. Sidekicks reporting strictly more fire the "console is too old" banner instead.

This means MinimumSupportedProtocolVersion CAN be bumped slower than `BridgeProtocol.VERSION` IF additive non-breaking changes are made. Today that distinction is academic (we have no compatibility shims), but the door is now open. The CLAUDE.md sync rule still says "bump together" because today every change is treated as breaking.

A future spec could relax the sync rule to only mandate updates when the change is *known-breaking*. Out of scope for this PR.

## 7. Test strategy

| Invariant | Mechanism |
|---|---|
| `parseProtocolVersion` and `compareProtocolVersion` exist in bundle | substring presence in `FeedbackConsoleAssets.indexHtml` |
| `checkProtocolCompat` produces both direction hashes | substring presence (`protocol-sidekick-old-`, `protocol-console-old-`) |
| All 4 mirror sites equal | `BridgeProtocolVersionSyncTest.allMirrorSitesAgreeOnBridgeProtocolVersion` (Kotlin) |
| KDoc renders / no syntax error | compile passes (covered by existing Kotlin compile) |

Manual smoke (out of scope for automated): on next bump, install old APK, observe that the new (post-PR) console correctly says **"This console is older than sample app bridge protocol vX"** rather than the old (false) wording.

## 8. Out of scope

- Bumping `BridgeProtocol.VERSION` itself — this PR does not change the version. Mirror lint will protect future bumps; today's bumped value (1.1) stays.
- semver pre-release/build metadata.
- A non-breaking-change minimum-version policy (currently bump-together; relaxation deferred).
- CI lint as a separate Node script — chose Kotlin test for tighter integration.
- E2E browser-side test of the banner directional wording — uses the existing string-presence test pattern, which is sufficient given the rest of the bundle is also tested that way.

## 9. Open questions resolved

- **Q1 (compare scheme):** numeric component-wise compare on dot-split parts. Rejected: lexicographic (fails "1.10" vs "1.2"), semver (overengineered for major.minor format).
- **Q2 (lint location):** Kotlin test in `:fixthis-mcp:test`. Rejected: standalone Node script (extra CI step), Gradle plugin (premature).
- **Q3 (KDoc vs rename for R3):** KDoc only. Rejected: rename `installEpochMillis` (wire format compat across CLI + tests; cost outweighs name clarity gain).

---

## 10. Reading order for first-time implementer

1. §1, §3 — see *what is currently wrong*.
2. §4.1 — understand the new `checkProtocolCompat` semantics + helper functions.
3. §4.2 — Kotlin sync test shape and failure-mode example.
4. §4.3 — KDoc-only delta on BridgeStatus.
5. Companion plan (`plans/2026-05-10-bridge-protocol-safety-net.md`) — file-by-file task breakdown.
