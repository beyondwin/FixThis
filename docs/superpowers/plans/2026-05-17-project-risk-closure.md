# Project Risk Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the remaining project risks found in the 2026-05-17 whole-repo audit so release confidence is based on green executable gates rather than prose-only intent.

**Architecture:** Land the work in small, independently verifiable hardening slices. First restore the currently red local CI signal, then fix misleading workflow status capture, then harden release artifact integrity and long-tail quality gates without changing persisted MCP JSON, bridge protocol, or the debug-only product model.

**Tech Stack:** Kotlin/JVM 21, Gradle/AGP, Detekt, JUnit/kotlin.test, GitHub Actions YAML, Node.js 20 `node:test`, Bash, SHA-256 digest validation, Android connected/functional tests, Markdown docs.

**Related implementation details:** [`../specs/2026-05-17-project-risk-closure-implementation-details.md`](../specs/2026-05-17-project-risk-closure-implementation-details.md)

---

## File Structure

Likely modified Kotlin files:

- `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadiness.kt` - split factory ownership to remove `TooManyFunctions`.
- `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt` - use the new failure catalog for unknown readiness.
- `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFiles.kt` - extract long recovery strings.
- `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt` - extract readiness JSON insertion.
- `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt` - split a long nested assertion.
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewRoutes.kt` - delegate screenshot serving.
- Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewScreenshotResponder.kt` - own preview screenshot lookup and response.

Likely modified workflow/script files:

- `.github/workflows/perf-report.yml` - preserve comparator exit code through `tee`.
- `.github/workflows/release-cli-mcp.yml` - upload and attach `.sha256`.
- `.github/workflows/ci.yml` - run new cheap contract checks.
- `scripts/perf/perf-workflow-contract-test.mjs` - prevent perf workflow exit-code regression.
- `scripts/package-cli-release.sh` - generate SHA-256 sidecar.
- `scripts/package-cli-release-test.mjs` - cover SHA-256 sidecar.
- `scripts/install-fixthis.sh` - verify downloaded/local release archive checksum.
- `scripts/install-fixthis-test.mjs` - cover install checksum success and failure.
- `npm/fixthis/scripts/postinstall.js` - verify downloaded/local release archive checksum.
- `scripts/npm-wrapper-test.mjs` - cover npm postinstall checksum success and failure.
- `config/detekt/baseline-budget.json` - freeze current baseline counts.
- `scripts/check-detekt-baseline-budget.mjs` - enforce no baseline growth.
- `scripts/check-detekt-baseline-budget-test.mjs` - unit-test the ratchet.
- `scripts/required-checks-observation.mjs` - add JSON/readiness modes.
- `scripts/required-checks-observation-test.mjs` - unit-test workflow streak logic.

Likely modified docs:

- `docs/contributing/required-checks.md` - document executable observation output.
- `docs/contributing/connected-tests.md` - document promotion command and evidence.
- `docs/reference/compatibility.md` - document lower-bound pre-release gate.
- `docs/contributing/release-readiness.md` - require perf/checksum/observation evidence.
- `docs/contributing/release-process.md` - include checksum assets and pre-tag gates.

Optional heavier fixture files:

- Create `fixthis-gradle-plugin/src/functionalTest/resources/release-consumer-fixture/settings.gradle.kts`.
- Create `fixthis-gradle-plugin/src/functionalTest/resources/release-consumer-fixture/build.gradle.kts`.
- Create `fixthis-gradle-plugin/src/functionalTest/resources/release-consumer-fixture/app/build.gradle.kts`.
- Create `fixthis-gradle-plugin/src/functionalTest/resources/release-consumer-fixture/app/src/main/AndroidManifest.xml`.
- Create `fixthis-gradle-plugin/src/functionalTest/kotlin/io/github/beyondwin/fixthis/gradle/ReleaseConsumerFixtureTest.kt`.

Guardrails:

- Do not commit `.fixthis/`, `output/`, `build/`, `node_modules`, or local Android SDK files.
- Do not rename persisted MCP JSON fields.
- Do not bump `BridgeProtocol.VERSION`.
- Do not weaken debug-only release constraints.
- Do not add remote publish credentials.
- Do not hide current detekt findings with suppressions unless a task explicitly documents why the finding is a third-party false positive.

---

## Task 1: Restore Detekt-Clean Local CI

**Files:**
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadiness.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFiles.kt`
- Modify: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewRoutes.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewScreenshotResponder.kt`

- [ ] **Step 1: Reproduce the current failure**

Run:

```bash
./gradlew :fixthis-cli:detekt :fixthis-mcp:detekt --no-daemon
```

Expected before this task:

```text
:fixthis-cli:detekt FAILED
:fixthis-mcp:detekt FAILED
```

The findings should include `TooManyFunctions` in `FirstRunReadiness.kt` and
`PreviewRoutes.kt`, plus `MaxLineLength` findings in the CLI files.

- [ ] **Step 2: Split first-run failure readiness factories**

In `FirstRunReadiness.kt`, add a second catalog immediately after
`FirstRunReadinessCatalog`. Move `captureUnavailable`, `sessionMismatch`, and
`unknown` from `FirstRunReadinessCatalog` into the new object:

```kotlin
object FirstRunReadinessFailureCatalog {
    fun captureUnavailable(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.CAPTURE_UNAVAILABLE,
        cause = cause,
        verify = "Open the app foreground and tap Capture, or open doctor for the bridge log.",
        fix = "Reopen the app foreground and tap Retry capture, or open doctor for the bridge log.",
        nextAction = "Retry capture",
        details = details,
    )

    fun sessionMismatch(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.SESSION_MISMATCH,
        cause = cause,
        verify = "Compare the response sessionId with the active feedback session.",
        fix = "Refresh the active session or return to the matching history item.",
        nextAction = "Refresh session",
        details = details,
    )

    fun unknown(cause: String, details: Map<String, String> = emptyMap()) = FirstRunReadiness(
        state = FirstRunReadinessState.UNKNOWN_ERROR,
        cause = cause,
        verify = "fixthis doctor --project-dir . --json",
        fix = "Open diagnostic details and rerun the failed command with --verbose.",
        nextAction = "Open diagnostic details",
        details = details,
    )
}
```

Update `classifyBridgeFailure` in the same file:

```kotlin
else -> FirstRunReadinessFailureCatalog.unknown(
    cause = raw.ifBlank { "FixThis could not classify this first-run failure." },
    details = if (raw.isBlank()) emptyMap() else mapOf("rawError" to raw),
)
```

- [ ] **Step 3: Update first-run catalog call sites**

Update call sites that now point to the failure catalog:

```kotlin
// DoctorCommand.kt
else -> FirstRunReadinessFailureCatalog.unknown(
    cause = "Doctor check failed: $id",
    details = mapOf("check" to id),
)
```

```kotlin
// PreviewRoutes.kt, until the screenshot extraction below changes this block
readiness = FirstRunReadinessFailureCatalog.captureUnavailable(
    cause = "Capture returned semantics with no screenshot bytes.",
    details = buildMap {
        preview.screen.screenshot?.captureFailedReason
            ?.takeIf { it.isNotBlank() }
            ?.let { put("rawError", it) }
    },
)
```

Update tests importing `FirstRunReadinessCatalog.captureUnavailable` to use
`FirstRunReadinessFailureCatalog.captureUnavailable`. Concretely there are two
call sites in `FirstRunReadinessTest.kt` (the detailed-details case and the
default-empty-details case) plus the `classifyBridgeFailure` call inside
`FirstRunReadiness.kt` itself — verify with:

```bash
grep -rn "FirstRunReadinessCatalog\.\(captureUnavailable\|sessionMismatch\|unknown\)" --include="*.kt" .
```

The expected result after migration is an empty grep (only
`FirstRunReadinessFailureCatalog.*` references remain).

- [ ] **Step 4: Extract long recovery strings in `AgentSetupFiles.kt`**

Add constants near the top of the file:

```kotlin
private const val RecoveryNoAndroidContext =
    "Run from the Android repo root, or pass --allow-global to write the global codex config anyway."
private const val RecoveryNoAppModule =
    "Run ./gradlew projects to list modules; pass the correct --package."
private const val RecoveryReleaseOnlyVariant =
    "Add a debug variant; FixThis attaches debug builds only."
private const val RecoveryViewSystemMixed =
    "Module contains View-based activities; migrate to ComponentActivity + setContent."
private const val RecoveryMissingApplicationId =
    "No unique applicationId; run from app module or pass --package."
private const val ReadinessNeedsInstall =
    "Run `fixthis install-agent --project-dir . --target all`."
private const val ReadinessConfigRecoverable =
    "Run `fixthis install-agent --project-dir . --target all --dry-run`, inspect the diff, then rerun without --dry-run."
private const val ReadinessEnvBlocker =
    "Install missing local prerequisites, then run `fixthis doctor --project-dir . --json`."
private const val ReadinessUnsupportedBuild =
    "Install a debuggable build with the FixThis sidekick enabled."
private const val ReadinessNeedsAppLaunch =
    "Launch the debug app or click Start in the feedback console."
```

Replace the long `JsonPrimitive(...)` calls in `recovery`:

```kotlin
put("no-android-context", JsonPrimitive(RecoveryNoAndroidContext))
put("no-app-module", JsonPrimitive(RecoveryNoAppModule))
put("release-only-variant", JsonPrimitive(RecoveryReleaseOnlyVariant))
put("view-system-mixed", JsonPrimitive(RecoveryViewSystemMixed))
put("missing-application-id", JsonPrimitive(RecoveryMissingApplicationId))
put(
    "readinessRecovery",
    buildJsonObject {
        put("NEEDS_INSTALL", JsonPrimitive(ReadinessNeedsInstall))
        put("CONFIG_RECOVERABLE", JsonPrimitive(ReadinessConfigRecoverable))
        put("ENV_BLOCKER", JsonPrimitive(ReadinessEnvBlocker))
        put("UNSUPPORTED_BUILD", JsonPrimitive(ReadinessUnsupportedBuild))
        put("NEEDS_APP_LAUNCH", JsonPrimitive(ReadinessNeedsAppLaunch))
    },
)
```

- [ ] **Step 5: Extract readiness insertion in `InstallAgentJsonReport.kt`**

Add the import:

```kotlin
import kotlinx.serialization.json.JsonObjectBuilder
```

Add a private helper below `compactJson`:

```kotlin
private fun JsonObjectBuilder.putReadiness(readiness: FirstRunReadiness?) {
    readiness?.let {
        put(
            "readiness",
            compactJson.encodeToJsonElement(FirstRunReadiness.serializer(), it).jsonObject,
        )
    }
}
```

Replace both long inline readiness blocks with:

```kotlin
putReadiness(sk.readiness)
```

and:

```kotlin
putReadiness(e.readiness)
```

- [ ] **Step 6: Split the long assertion in `InstallAgentJsonReportTest.kt`**

Replace the nested readiness assertion with local values:

```kotlin
val readiness = skipped.getValue("readiness").jsonObject
val state = readiness.getValue("state").jsonPrimitive.content
assertEquals("CONFIG_RECOVERABLE", state)
```

- [ ] **Step 7: Create `PreviewScreenshotResponder.kt`**

Create `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewScreenshotResponder.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.console

import com.sun.net.httpserver.HttpExchange
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionException
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionPaths
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.SessionDto
import java.io.File

// NOTE: `isAllowedPngArtifact` already exists at top level in PreviewRoutes.kt
// (same package). Do NOT redeclare it here — Kotlin will fail with
// "conflicting overloads". Either keep the existing top-level function in
// PreviewRoutes.kt and import nothing extra here, or delete it from
// PreviewRoutes.kt and define it once below this class. The block at the end
// of this file is the *single* surviving definition; Step 8 must delete the
// original from PreviewRoutes.kt.
internal class PreviewScreenshotResponder(
    private val service: FeedbackSessionService,
) {
    fun sendExact(exchange: HttpExchange, previewId: String) {
        val explicitSessionId = exchange.queryParameter("sessionId")?.takeIf { it.isNotBlank() }
        val session = explicitSessionId?.let { service.getSession(it) } ?: service.requireCurrentSession()
        val screenshotFile = try {
            service.previewScreenshotFile(session.sessionId, previewId)
        } catch (error: FeedbackSessionException) {
            throw FeedbackConsoleHttpException(404, "Screenshot not found", cause = error)
        }
        exchange.sendBytes(200, screenshotFile.readBytes(), "image/png")
    }

    fun sendLatest(exchange: HttpExchange) {
        val session = exchange.queryParameter("sessionId")
            ?.takeIf { it.isNotBlank() }
            ?.let { service.getSession(it) }
            ?: service.requireCurrentSession()
        val projectRoot = File(session.projectRoot).canonicalFile
        val previewRoot = File(projectRoot, ".fixthis/preview-cache/${session.sessionId}").canonicalFile
        val persistedRoot = FeedbackSessionPaths(projectRoot).rootDirectory
        val legacyRoot = File(projectRoot, ".fixthis/artifacts").canonicalFile
        val roots = listOf(previewRoot, persistedRoot, legacyRoot)
        val screenshotFile = latestPreviewScreenshot(previewRoot, roots)
            ?: latestPersistedScreenshot(session, roots)
            ?: throw FeedbackConsoleHttpException(404, "Screenshot not found")

        exchange.sendBytes(200, screenshotFile.readBytes(), "image/png")
    }

    private fun latestPreviewScreenshot(previewRoot: File, allowedRoots: List<File>): File? {
        if (!previewRoot.isDirectory) return null
        return previewRoot
            .walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith("-full.png") }
            .map { file -> file.canonicalFile }
            .filter { file -> file.isAllowedPngArtifact(allowedRoots) }
            .maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.absolutePath })
    }

    private fun latestPersistedScreenshot(session: SessionDto, allowedRoots: List<File>): File? = session.screens
        .asReversed()
        .asSequence()
        .mapNotNull { screen -> screen.screenshot?.desktopFullPath?.let(::File) }
        .map { file -> file.canonicalFile }
        .firstOrNull { file -> file.isAllowedPngArtifact(allowedRoots) }
}

internal fun File.isAllowedPngArtifact(allowedRoots: List<File>): Boolean = isFile &&
    extension.lowercase() == "png" &&
    allowedRoots.any { root -> toPath().startsWith(root.toPath()) }
```

- [ ] **Step 8: Delegate screenshot routes from `PreviewRoutes.kt`**

In `PreviewRoutes`, add a property:

```kotlin
private val screenshots = PreviewScreenshotResponder(service)
```

Replace the screenshot branches:

```kotlin
"/api/preview/screenshot/full" -> exchange.requireMethod("GET") {
    screenshots.sendLatest(exchange)
}
```

and:

```kotlin
screenshots.sendExact(exchange, exchange.requestURI.path.previewIdFromScreenshotPath())
```

Delete the now-moved private methods from `PreviewRoutes.kt`:

```kotlin
private fun HttpExchange.sendPreviewScreenshot(previewId: String)
private fun HttpExchange.sendPreviewScreenshot()
private fun latestPreviewScreenshot(...)
private fun latestPersistedScreenshot(...)
```

Also delete the top-level `internal fun File.isAllowedPngArtifact(...)` from
`PreviewRoutes.kt` (currently at the bottom of the file) — it is moved verbatim
to `PreviewScreenshotResponder.kt`. Leaving both copies in the same package
produces a Kotlin "conflicting overloads" compile error.

Keep these utilities in `PreviewRoutes.kt` unless detekt still complains:

```kotlin
internal fun String.isPreviewFullScreenshotPath(): Boolean =
    split('/').size == 6 && startsWith("/api/preview/") && endsWith("/screenshot/full")

internal fun String.previewIdFromScreenshotPath(): String =
    URLDecoder.decode(split('/')[3], Charsets.UTF_8.name())
```

- [ ] **Step 9: Run focused verification**

Run:

```bash
./gradlew :fixthis-cli:detekt :fixthis-mcp:detekt --no-daemon
./gradlew :fixthis-cli:test \
  --tests "*FirstRunReadinessTest" \
  --tests "*InstallAgentJsonReportTest" \
  --no-daemon
./gradlew :fixthis-mcp:test \
  --tests "*ConsolePreviewRoutesTest" \
  --tests "*ConsoleNavigationRoutesTest" \
  --no-daemon
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 10: Run full local CI**

Run:

```bash
npm run ci:local
```

Expected:

```text
[verify-ci-local] complete
```

- [ ] **Step 11: Commit**

```bash
git add \
  fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadiness.kt \
  fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt \
  fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFiles.kt \
  fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt \
  fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewRoutes.kt \
  fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewScreenshotResponder.kt
git commit -m "fix: restore detekt-clean local ci"
```

---

## Task 2: Fix the Perf Regression Workflow Exit Code

**Files:**
- Modify: `.github/workflows/perf-report.yml`
- Create: `scripts/perf/perf-workflow-contract-test.mjs`
- Modify: `package.json`
- Modify: `scripts/verify-ci-local.sh`

- [ ] **Step 1: Write the workflow contract test**

Create `scripts/perf/perf-workflow-contract-test.mjs`:

```javascript
import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const workflow = fs.readFileSync(path.join(repoRoot, ".github/workflows/perf-report.yml"), "utf8");

test("perf compare step preserves compare-perf exit code through tee", () => {
  const compareStep = workflow.match(/- name: Compare against committed baseline[\s\S]*?(?=\n      - name:)/);
  assert.ok(compareStep, "Compare against committed baseline step not found");
  const body = compareStep[0];

  assert.match(body, /shell:\s*bash/);
  assert.match(body, /set -o pipefail/);
  assert.match(body, /compare_status=0/);
  // Literal `compare_status=$?` at end-of-line. Both `$` and `?` must be
  // escaped: `\$?` would mean "optional dollar" and silently match nothing,
  // hiding a regression in the workflow.
  assert.match(body, /compare_status=\$\?$/m);
  assert.match(body, /echo "exit=\$compare_status" >> "\$GITHUB_OUTPUT"/);
  assert.match(body, /continue-on-error:\s*true/);
});
```

Run it before changing the workflow:

```bash
node --test scripts/perf/perf-workflow-contract-test.mjs
```

Expected before the workflow fix:

```text
not ok
```

- [ ] **Step 2: Fix `.github/workflows/perf-report.yml`**

Replace the compare step with:

```yaml
      - name: Compare against committed baseline
        id: compare
        shell: bash
        run: |
          set -o pipefail

          latest=$(ls -t output/perf/run-*.json | head -1)
          compare_status=0
          node scripts/perf/compare-perf.mjs docs/perf/baseline-2026-05-16.json "$latest" \
            | tee /tmp/perf-report.md || compare_status=$?
          echo "exit=$compare_status" >> "$GITHUB_OUTPUT"
        continue-on-error: true
```

The `continue-on-error` line stays so the summary and artifact steps still run.
The final "Fail on regression" step remains the hard failure.

- [ ] **Step 3: Add the perf contract test to scripts**

Update `package.json`:

```json
"perf:test": "node --test scripts/perf/parse-gradle-profile-test.mjs scripts/perf/aggregate-test.mjs scripts/perf/compare-perf-test.mjs scripts/perf/perf-workflow-contract-test.mjs",
```

Update `scripts/verify-ci-local.sh` after release package tests:

```bash
run_step "npm run perf:test"
```

Update `.github/workflows/ci.yml` in the `console-js` job after release package
tests:

```yaml
      - name: Run perf contract tests
        run: npm run perf:test
```

- [ ] **Step 4: Verify**

Run:

```bash
npm run perf:test
npm run ci:local:fast
```

Expected:

```text
# pass
[verify-ci-local] complete
```

- [ ] **Step 5: Commit**

```bash
git add \
  .github/workflows/perf-report.yml \
  .github/workflows/ci.yml \
  package.json \
  scripts/verify-ci-local.sh \
  scripts/perf/perf-workflow-contract-test.mjs
git commit -m "ci: preserve perf regression exit code"
```

---

## Task 3: Add Release Tarball SHA-256 Verification

**Files:**
- Modify: `scripts/package-cli-release.sh`
- Modify: `scripts/package-cli-release-test.mjs`
- Modify: `.github/workflows/release-cli-mcp.yml`
- Modify: `scripts/install-fixthis.sh`
- Modify: `scripts/install-fixthis-test.mjs`
- Modify: `npm/fixthis/scripts/postinstall.js`
- Modify: `scripts/npm-wrapper-test.mjs`
- Modify: `docs/contributing/release-process.md`
- Modify: `docs/contributing/release-readiness.md`

- [ ] **Step 1: Generate a checksum sidecar during packaging**

In `scripts/package-cli-release.sh`, add:

```bash
sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}
```

After the `tar -czf` line, write the sidecar:

```bash
CHECKSUM="$ARCHIVE.sha256"
printf '%s  %s\n' "$(sha256_file "$ARCHIVE")" "$(basename "$ARCHIVE")" > "$CHECKSUM"
echo "$ARCHIVE"
echo "$CHECKSUM"
```

- [ ] **Step 2: Update package test for checksum generation**

In `scripts/package-cli-release-test.mjs`, after asserting the archive exists:

```javascript
const checksum = `${archive}.sha256`;
assert.equal(existsSync(checksum), true);
assert.match(
  readFileSync(checksum, "utf8"),
  /^[a-f0-9]{64}  fixthis-cli-mcp-v9\.8\.7\.tar\.gz\n$/,
);
```

Run:

```bash
node --test scripts/package-cli-release-test.mjs
```

Expected:

```text
# pass
```

- [ ] **Step 3: Attach checksum in release workflow**

Update `.github/workflows/release-cli-mcp.yml`:

```yaml
          path: |
            build/release/fixthis-cli-mcp-${{ env.FIXTHIS_RELEASE_VERSION }}.tar.gz
            build/release/fixthis-cli-mcp-${{ env.FIXTHIS_RELEASE_VERSION }}.tar.gz.sha256
```

and:

```yaml
          files: |
            build/release/fixthis-cli-mcp-${{ env.FIXTHIS_RELEASE_VERSION }}.tar.gz
            build/release/fixthis-cli-mcp-${{ env.FIXTHIS_RELEASE_VERSION }}.tar.gz.sha256
```

- [ ] **Step 4: Verify checksum in `install-fixthis.sh`**

Add flags/state:

```bash
CHECKSUM_FILE=""
CHECKSUM_VALUE=""
ALLOW_UNCHECKED_LOCAL_ARCHIVE=0
```

Add usage lines:

```text
                                    [--checksum-file <path>]
                                    [--sha256 <digest>]
                                    [--allow-unchecked-local-archive]
```

Add flag parsing:

```bash
        --checksum-file)
            [[ $# -ge 2 ]] || { echo "[install] --checksum-file requires a value" >&2; exit 2; }
            CHECKSUM_FILE="$2"; shift 2 ;;
        --sha256)
            [[ $# -ge 2 ]] || { echo "[install] --sha256 requires a value" >&2; exit 2; }
            CHECKSUM_VALUE="$2"; shift 2 ;;
        --allow-unchecked-local-archive)
            ALLOW_UNCHECKED_LOCAL_ARCHIVE=1; shift ;;
```

Add helpers:

```bash
sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

verify_archive_checksum() {
    local archive="$1"
    local expected="$2"
    local actual
    actual="$(sha256_file "$archive")"
    if [[ "$actual" != "$expected" ]]; then
        echo "[install] checksum mismatch for $archive" >&2
        echo "[install] expected: $expected" >&2
        echo "[install] actual:   $actual" >&2
        exit 1
    fi
}
```

For network downloads, download the sidecar:

```bash
CHECKSUM_FILE="$WORK_DIR/$(basename "$ARCHIVE").sha256"
curl -fL \
    "https://github.com/$REPO/releases/download/$VERSION/$(basename "$ARCHIVE").sha256" \
    -o "$CHECKSUM_FILE"
```

Before extraction:

```bash
if [[ -n "$CHECKSUM_VALUE" ]]; then
    verify_archive_checksum "$ARCHIVE" "$CHECKSUM_VALUE"
elif [[ -n "$CHECKSUM_FILE" || -f "$ARCHIVE.sha256" ]]; then
    checksum_source="${CHECKSUM_FILE:-$ARCHIVE.sha256}"
    verify_archive_checksum "$ARCHIVE" "$(awk '{print $1}' "$checksum_source")"
elif [[ "$ALLOW_UNCHECKED_LOCAL_ARCHIVE" -ne 1 ]]; then
    echo "[install] local archives require --checksum-file, --sha256, or --allow-unchecked-local-archive" >&2
    exit 2
fi
```

- [ ] **Step 5: Add shell installer tests**

In `scripts/install-fixthis-test.mjs`, compute a checksum for the fixture archive:

```javascript
const checksum = spawnSync("shasum", ["-a", "256", archive], { encoding: "utf8" });
assert.equal(checksum.status, 0, checksum.stderr || checksum.stdout);
const checksumFile = `${archive}.sha256`;
writeFileSync(checksumFile, checksum.stdout);
```

Pass the checksum file to the installer:

```javascript
"--checksum-file",
checksumFile,
```

Add a failure test:

```javascript
test("install-fixthis rejects a local archive with a wrong checksum", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-install-bad-checksum-"));
  try {
    mkdirSync(join(root, "scripts"), { recursive: true });
    copyFileSync(join(repoRoot, "scripts/install-fixthis.sh"), join(root, "scripts/install-fixthis.sh"));
    chmodSync(join(root, "scripts/install-fixthis.sh"), 0o755);
    const archive = join(root, "fixthis-cli-mcp-v9.8.7.tar.gz");
    writeFileSync(archive, "not a real archive");
    const checksumFile = `${archive}.sha256`;
    writeFileSync(checksumFile, `${"0".repeat(64)}  fixthis-cli-mcp-v9.8.7.tar.gz\n`);

    const result = spawnSync(
      "bash",
      ["scripts/install-fixthis.sh", "--archive", archive, "--checksum-file", checksumFile],
      { cwd: root, encoding: "utf8" },
    );

    assert.equal(result.status, 1);
    assert.match(result.stderr, /checksum mismatch/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
```

- [ ] **Step 6: Verify checksum in npm postinstall**

In `npm/fixthis/scripts/postinstall.js`, import crypto:

```javascript
import { createHash } from "node:crypto";
```

Add helpers:

```javascript
function sha256File(file) {
  const hash = createHash("sha256");
  hash.update(readFileSync(file));
  return hash.digest("hex");
}

function expectedSha256(text) {
  const value = text.trim().split(/\s+/)[0];
  if (!/^[a-f0-9]{64}$/i.test(value)) {
    throw new Error("release checksum file is malformed");
  }
  return value.toLowerCase();
}

function verifyArchiveChecksum(archive, checksumText) {
  const expected = expectedSha256(checksumText);
  const actual = sha256File(archive);
  if (actual !== expected) {
    throw new Error(`release archive checksum mismatch: expected ${expected}, got ${actual}`);
  }
}
```

When downloading from GitHub Release, also download:

```javascript
const checksumUrl = `${url}.sha256`;
const checksumPath = `${archive}.sha256`;
await download(checksumUrl, checksumPath);
verifyArchiveChecksum(archive, readFileSync(checksumPath, "utf8"));
```

For `FIXTHIS_RELEASE_ARCHIVE`, require either `FIXTHIS_RELEASE_ARCHIVE_SHA256`
or a sibling `.sha256`:

```javascript
if (process.env.FIXTHIS_RELEASE_ARCHIVE_SHA256) {
  verifyArchiveChecksum(archive, process.env.FIXTHIS_RELEASE_ARCHIVE_SHA256);
} else if (existsSync(`${archive}.sha256`)) {
  verifyArchiveChecksum(archive, readFileSync(`${archive}.sha256`, "utf8"));
} else {
  throw new Error("FIXTHIS_RELEASE_ARCHIVE requires FIXTHIS_RELEASE_ARCHIVE_SHA256 or a sibling .sha256 file");
}
```

- [ ] **Step 7: Add npm checksum tests**

In `scripts/npm-wrapper-test.mjs`, after creating the archive:

```javascript
const checksum = spawnSync("shasum", ["-a", "256", archive], { encoding: "utf8" });
assert.equal(checksum.status, 0, checksum.stderr || checksum.stdout);
writeFileSync(`${archive}.sha256`, checksum.stdout);
```

Add a failure test using `FIXTHIS_RELEASE_ARCHIVE_SHA256: "0".repeat(64)` and
asserting `/checksum mismatch/`.

- [ ] **Step 8: Update release docs**

In `docs/contributing/release-process.md`, add the checksum asset beside the
tarball:

```markdown
- `fixthis-cli-mcp-X.Y.Z.tar.gz`
- `fixthis-cli-mcp-X.Y.Z.tar.gz.sha256`
```

In `docs/contributing/release-readiness.md`, add:

```markdown
- [ ] Release tarball checksum sidecar exists and both shell/npm installers
      verify SHA-256 before extraction.
```

- [ ] **Step 9: Verify**

Run:

```bash
npm run release:package:test
npm run release:npm:test
npm run ci:local:fast
```

Expected:

```text
# pass
[verify-ci-local] complete
```

- [ ] **Step 10: Commit**

```bash
git add \
  .github/workflows/release-cli-mcp.yml \
  scripts/package-cli-release.sh \
  scripts/package-cli-release-test.mjs \
  scripts/install-fixthis.sh \
  scripts/install-fixthis-test.mjs \
  npm/fixthis/scripts/postinstall.js \
  scripts/npm-wrapper-test.mjs \
  docs/contributing/release-process.md \
  docs/contributing/release-readiness.md
git commit -m "fix: verify release package checksums"
```

---

## Task 4: Add a Detekt Baseline Ratchet

**Files:**
- Create: `config/detekt/baseline-budget.json`
- Create: `scripts/check-detekt-baseline-budget.mjs`
- Create: `scripts/check-detekt-baseline-budget-test.mjs`
- Modify: `package.json`
- Modify: `scripts/verify-ci-local.sh`
- Modify: `.github/workflows/ci.yml`
- Modify: `docs/contributing/required-checks.md`

- [ ] **Step 1: Create the budget file**

Create `config/detekt/baseline-budget.json`:

```json
{
  "config/detekt/baseline-app.xml": 54,
  "config/detekt/baseline-fixthis-cli.xml": 70,
  "config/detekt/baseline-fixthis-compose-core.xml": 57,
  "config/detekt/baseline-fixthis-compose-sidekick.xml": 86,
  "config/detekt/baseline-fixthis-gradle-plugin.xml": 9,
  "config/detekt/baseline-fixthis-mcp.xml": 419
}
```

- [ ] **Step 2: Write the checker**

Create `scripts/check-detekt-baseline-budget.mjs`:

```javascript
#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export function countBaselineIds(xmlText) {
  return [...xmlText.matchAll(/<ID>/g)].length;
}

export function evaluateBudgets(budgets, readFile) {
  return Object.entries(budgets).map(([file, budget]) => {
    const count = countBaselineIds(readFile(file));
    return {
      file,
      budget,
      count,
      status: count > budget ? "over" : count < budget ? "improved" : "equal",
    };
  });
}

function main() {
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const budgetPath = path.join(repoRoot, "config/detekt/baseline-budget.json");
  const budgets = JSON.parse(fs.readFileSync(budgetPath, "utf8"));
  const results = evaluateBudgets(
    budgets,
    (file) => fs.readFileSync(path.join(repoRoot, file), "utf8"),
  );
  let failed = false;
  for (const result of results) {
    const message = `${result.file}: ${result.count}/${result.budget}`;
    if (result.status === "over") {
      console.error(`[detekt-baseline] over budget: ${message}`);
      failed = true;
    } else if (result.status === "improved") {
      console.log(`[detekt-baseline] improved: ${message}`);
    } else {
      console.log(`[detekt-baseline] ok: ${message}`);
    }
  }
  if (failed) process.exit(1);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}
```

- [ ] **Step 3: Write checker tests**

Create `scripts/check-detekt-baseline-budget-test.mjs`:

```javascript
import { test } from "node:test";
import assert from "node:assert/strict";
import { countBaselineIds, evaluateBudgets } from "./check-detekt-baseline-budget.mjs";

test("countBaselineIds counts detekt ID entries", () => {
  assert.equal(countBaselineIds("<SmellBaseline><ID>a</ID><ID>b</ID></SmellBaseline>"), 2);
});

test("evaluateBudgets marks growth as over budget", () => {
  const results = evaluateBudgets(
    { "baseline.xml": 1 },
    () => "<SmellBaseline><ID>a</ID><ID>b</ID></SmellBaseline>",
  );
  assert.deepEqual(results[0], {
    file: "baseline.xml",
    budget: 1,
    count: 2,
    status: "over",
  });
});

test("evaluateBudgets marks lower counts as improved", () => {
  const results = evaluateBudgets(
    { "baseline.xml": 2 },
    () => "<SmellBaseline><ID>a</ID></SmellBaseline>",
  );
  assert.equal(results[0].status, "improved");
});
```

- [ ] **Step 4: Wire the checker into scripts and CI**

Update `package.json`:

```json
"detekt:baseline:check": "node scripts/check-detekt-baseline-budget.mjs",
```

Update `scripts/verify-ci-local.sh` after doc checks:

```bash
run_step "npm run detekt:baseline:check"
```

Update `.github/workflows/ci.yml` in the `console-js` job:

```yaml
      - name: Check detekt baseline budget
        run: npm run detekt:baseline:check
```

- [ ] **Step 5: Document the ratchet**

Add to `docs/contributing/required-checks.md`:

```markdown
- Detekt baseline files are ratcheted by
  `npm run detekt:baseline:check`. Reducing a baseline count is allowed and
  should be followed by lowering `config/detekt/baseline-budget.json`.
  Increasing a budget requires a code-review explanation.
```

- [ ] **Step 6: Verify**

Run:

```bash
node --test scripts/check-detekt-baseline-budget-test.mjs
npm run detekt:baseline:check
npm run ci:local:fast
```

Expected:

```text
# pass
[detekt-baseline] ok: config/detekt/baseline-fixthis-mcp.xml: 419/419
[verify-ci-local] complete
```

- [ ] **Step 7: Commit**

```bash
git add \
  .github/workflows/ci.yml \
  package.json \
  scripts/verify-ci-local.sh \
  config/detekt/baseline-budget.json \
  scripts/check-detekt-baseline-budget.mjs \
  scripts/check-detekt-baseline-budget-test.mjs \
  docs/contributing/required-checks.md
git commit -m "ci: ratchet detekt baseline debt"
```

---

## Task 5: Make Connected and Compatibility Promotion Executable

**Files:**
- Modify: `scripts/required-checks-observation.mjs`
- Create: `scripts/required-checks-observation-test.mjs`
- Modify: `package.json`
- Modify: `docs/contributing/required-checks.md`
- Modify: `docs/contributing/connected-tests.md`
- Modify: `docs/reference/compatibility.md`
- Modify: `docs/contributing/release-readiness.md`

- [ ] **Step 1: Refactor observation logic into testable exports**

Replace the top of `scripts/required-checks-observation.mjs` with exported
rules and pure helpers:

```javascript
#!/usr/bin/env node
import { execFileSync } from "node:child_process";

export const workflowRules = {
  ".github/workflows/ci.yml": { requiredSuccesses: 7 },
  ".github/workflows/codeql.yml": { requiredSuccesses: 7 },
  ".github/workflows/connected-tests.yml": { requiredSuccesses: 14 },
  ".github/workflows/nightly-compat.yml": { requiredSuccesses: 1 },
};

export function consecutiveSuccesses(runs) {
  let count = 0;
  for (const run of runs) {
    if (run.status !== "completed") continue;
    if (run.conclusion === "success") count += 1;
    else break;
  }
  return count;
}

export function summarizeWorkflow(workflow, runs, rule = workflowRules[workflow]) {
  const completedSuccesses = runs.filter((run) => run.status === "completed" && run.conclusion === "success");
  const streak = consecutiveSuccesses(runs);
  const requiredSuccesses = rule?.requiredSuccesses ?? 7;
  return {
    workflow,
    firstGreenInSample: completedSuccesses.at(-1)?.createdAt ?? null,
    consecutiveGreenCompletedRunsFromLatest: streak,
    requiredSuccesses,
    ready: streak >= requiredSuccesses,
    latestUrl: runs[0]?.url ?? null,
  };
}
```

Keep `ghJson` for CLI mode, but make it call these helpers.

- [ ] **Step 2: Add CLI flags**

Add argument parsing:

```javascript
function parseArgs(argv) {
  const options = { json: false, requireReady: [] };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--json") {
      options.json = true;
    } else if (arg === "--require-ready") {
      const value = argv[++i] ?? "";
      options.requireReady = value.split(",").filter(Boolean);
    } else if (arg === "--help" || arg === "-h") {
      options.help = true;
    } else {
      throw new Error(`unknown flag: ${arg}`);
    }
  }
  return options;
}
```

Map shorthand names:

```javascript
const workflowAliases = {
  ci: ".github/workflows/ci.yml",
  codeql: ".github/workflows/codeql.yml",
  connected: ".github/workflows/connected-tests.yml",
  "connected-tests": ".github/workflows/connected-tests.yml",
  compat: ".github/workflows/nightly-compat.yml",
  "nightly-compat": ".github/workflows/nightly-compat.yml",
};
```

If `--json` is set, print `JSON.stringify(summaries, null, 2)`. If
`--require-ready` is set and any required summary is not ready, set
`process.exitCode = 1`.

- [ ] **Step 3: Add observation tests**

Create `scripts/required-checks-observation-test.mjs`:

```javascript
import { test } from "node:test";
import assert from "node:assert/strict";
import { consecutiveSuccesses, summarizeWorkflow } from "./required-checks-observation.mjs";

const success = (createdAt = "2026-05-17T00:00:00Z") => ({
  status: "completed",
  conclusion: "success",
  createdAt,
  url: "https://example.test/success",
});
const failure = () => ({
  status: "completed",
  conclusion: "failure",
  createdAt: "2026-05-16T00:00:00Z",
  url: "https://example.test/failure",
});
const pending = () => ({
  status: "in_progress",
  conclusion: null,
  createdAt: "2026-05-18T00:00:00Z",
  url: "https://example.test/pending",
});

test("consecutiveSuccesses ignores pending latest runs and stops at failure", () => {
  assert.equal(consecutiveSuccesses([pending(), success(), success(), failure(), success()]), 2);
});

test("connected tests require fourteen consecutive green completed runs", () => {
  const runs = Array.from({ length: 14 }, (_, index) => success(`2026-05-${17 - index}T00:00:00Z`));
  const summary = summarizeWorkflow(
    ".github/workflows/connected-tests.yml",
    runs,
    { requiredSuccesses: 14 },
  );
  assert.equal(summary.ready, true);
  assert.equal(summary.consecutiveGreenCompletedRunsFromLatest, 14);
});

test("compatibility is not ready before its required window", () => {
  const summary = summarizeWorkflow(
    ".github/workflows/nightly-compat.yml",
    [failure(), success()],
    { requiredSuccesses: 1 },
  );
  assert.equal(summary.ready, false);
});
```

- [ ] **Step 4: Wire tests**

Update `package.json`:

```json
"checks:observation:test": "node --test scripts/required-checks-observation-test.mjs",
```

Update `scripts/verify-ci-local.sh`:

```bash
run_step "npm run checks:observation:test"
```

- [ ] **Step 5: Update docs**

In `docs/contributing/required-checks.md`, replace the manual copy-only
language with:

````markdown
Run:

```bash
npm run checks:observation -- --json
npm run checks:observation -- --require-ready connected-tests,nightly-compat
```

The first command prints the evidence table as JSON. The second command exits
nonzero until the connected-test and lower-bound compatibility observation
windows are satisfied.
````

In `docs/contributing/connected-tests.md`, add:

````markdown
Before promoting connected tests, run:

```bash
npm run checks:observation -- --require-ready connected-tests
```

Do not remove `continue-on-error` or add PR branch protection until this command
passes and the latest failing/flaky artifacts have been triaged.
````

In `docs/reference/compatibility.md`, add:

````markdown
Before advertising a lower-bound compatibility promotion, run:

```bash
npm run checks:observation -- --require-ready nightly-compat
```
````

In `docs/contributing/release-readiness.md`, add a pre-tag checkbox:

```markdown
- [ ] `npm run checks:observation -- --json` output captured for the release
      issue, and any non-ready scheduled gate is explicitly accepted.
```

- [ ] **Step 6: Verify**

Run:

```bash
npm run checks:observation:test
npm run checks:observation -- --help
npm run ci:local:fast
```

Expected:

```text
# pass
[verify-ci-local] complete
```

- [ ] **Step 7: Commit**

```bash
git add \
  package.json \
  scripts/verify-ci-local.sh \
  scripts/required-checks-observation.mjs \
  scripts/required-checks-observation-test.mjs \
  docs/contributing/required-checks.md \
  docs/contributing/connected-tests.md \
  docs/reference/compatibility.md \
  docs/contributing/release-readiness.md
git commit -m "ci: make scheduled gate promotion auditable"
```

---

## Task 6: Add a Real Release/Minify Fixture Gate

**Files:**
- Create: `fixthis-gradle-plugin/src/functionalTest/resources/release-consumer-fixture/settings.gradle.kts`
- Create: `fixthis-gradle-plugin/src/functionalTest/resources/release-consumer-fixture/build.gradle.kts`
- Create: `fixthis-gradle-plugin/src/functionalTest/resources/release-consumer-fixture/app/build.gradle.kts`
- Create: `fixthis-gradle-plugin/src/functionalTest/resources/release-consumer-fixture/app/src/main/AndroidManifest.xml`
- Create: `fixthis-gradle-plugin/src/functionalTest/resources/release-consumer-fixture/app/src/main/kotlin/com/example/fixthisfixture/MainActivity.kt`
- Create: `fixthis-gradle-plugin/src/functionalTest/kotlin/io/github/beyondwin/fixthis/gradle/ReleaseConsumerFixtureTest.kt`
- Modify: `fixthis-gradle-plugin/build.gradle.kts`
- Modify: `docs/contributing/release-readiness.md`

- [ ] **Step 1: Create fixture settings**

Create `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

rootProject.name = "fixthis-release-consumer-fixture"
include(":app")
```

- [ ] **Step 2: Create fixture root build file**

Create `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}
```

- [ ] **Step 3: Create fixture app build file**

Create `app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.fixthisfixture"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.fixthisfixture"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.12.1")
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("io.github.beyondwin.fixthis:fixthis-compose-sidekick:0.0.0-fixture")
}
```

- [ ] **Step 4: Create fixture manifest and activity**

Create `app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="FixThis Fixture"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Create `MainActivity.kt`:

```kotlin
package com.example.fixthisfixture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Text("FixThis release fixture")
        }
    }
}
```

- [ ] **Step 5: Add functional test that publishes locally and builds fixture**

Create `ReleaseConsumerFixtureTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ReleaseConsumerFixtureTest {
    @Test
    fun `release consumer fixture assembles minified release without FixThis startup provider`() {
        val fixtureDir = File(System.getProperty("fixthis.releaseConsumerFixture.path"))
        assumeTrue("Android SDK required for release consumer fixture", System.getenv("ANDROID_HOME") != null)
        assertTrue("Fixture directory missing: ${fixtureDir.absolutePath}", fixtureDir.isDirectory)

        val result = GradleRunner.create()
            .withProjectDir(fixtureDir)
            .withArguments(":app:assembleRelease", "--stacktrace")
            .forwardOutput()
            .build()

        assertTrue(result.task(":app:assembleRelease")?.outcome == SUCCESS)

        val mergedManifest = fixtureDir.resolve(
            "app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml",
        )
        assertTrue("Merged release manifest missing: ${mergedManifest.absolutePath}", mergedManifest.isFile)
        val manifest = mergedManifest.readText()
        assertFalse(manifest.contains("FixThisInitializer"))
        assertFalse(manifest.contains("androidx.startup.InitializationProvider"))
    }
}
```

If publishing the sidekick as `0.0.0-fixture` is not already available, add a
dedicated Gradle task in `fixthis-gradle-plugin/build.gradle.kts` that runs the
fixture only after local publication:

```kotlin
tasks.named<Test>("functionalTest") {
    systemProperty(
        "fixthis.releaseConsumerFixture.path",
        layout.projectDirectory.dir("src/functionalTest/resources/release-consumer-fixture")
            .asFile.absolutePath,
    )
}
```

If the fixture cannot resolve the local sidekick artifact, add a separate
root-level publication task in a follow-up commit before enabling this test in
CI. Do not point the fixture at remote unpublished artifacts.

- [ ] **Step 6: Document fixture gate**

Add to `docs/contributing/release-readiness.md`:

```markdown
- [ ] Release/minify consumer fixture passed, or the release issue records why
      Android SDK fixture execution was deferred:

  ```bash
  ./gradlew :fixthis-gradle-plugin:functionalTest --tests "*ReleaseConsumerFixtureTest" --no-daemon
  ```
```

- [ ] **Step 7: Verify**

Run:

```bash
./gradlew :fixthis-gradle-plugin:functionalTest \
  --tests "*ReleaseGuardTest" \
  --tests "*SidekickConsumerRulesTest" \
  --tests "*ReleaseConsumerFixtureTest" \
  --no-daemon
```

Expected on a machine with Android SDK and a resolvable local sidekick fixture
artifact:

```text
BUILD SUCCESSFUL
```

If the fixture is skipped because `ANDROID_HOME` is missing, record that in the
release issue and verify the structural tests still pass.

- [ ] **Step 8: Commit**

```bash
git add \
  fixthis-gradle-plugin/build.gradle.kts \
  fixthis-gradle-plugin/src/functionalTest/resources/release-consumer-fixture \
  fixthis-gradle-plugin/src/functionalTest/kotlin/io/github/beyondwin/fixthis/gradle/ReleaseConsumerFixtureTest.kt \
  docs/contributing/release-readiness.md
git commit -m "test: add release consumer minify fixture"
```

---

## Final Verification

- [ ] **Step 1: Run full local checks**

```bash
npm run ci:local
npm audit
npm audit --omit=dev
npm run release:npm:test
npm run checks:observation:test
```

Expected:

```text
[verify-ci-local] complete
found 0 vulnerabilities
# pass
```

- [ ] **Step 2: Run release-specific Gradle checks**

```bash
./gradlew :fixthis-gradle-plugin:validatePlugins --no-daemon
./gradlew :fixthis-gradle-plugin:functionalTest --no-daemon
```

Expected:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 3: Inspect generated artifacts**

```bash
git status --short
git diff --check
git ls-files .fixthis output build local.properties node_modules
```

Expected:

```text
# git status shows only intended source/doc changes before commit
# git diff --check has no output
# git ls-files command has no output
```

## Self-Review

- Spec coverage: covers all audit findings: red local CI, perf gate exit code,
  connected/compat informational risk, release checksum verification, detekt
  baseline debt, and release/minify fixture gap.
- Placeholder scan: no task depends on placeholder tokens or unspecified follow-up work for
  acceptance. The release fixture task explicitly records the Android SDK/local
  publication condition because that is an environmental gate.
- Compatibility: no persisted MCP JSON fields or bridge protocol fields change.
- Rollback: each task is independently revertible. If the fixture is unstable,
  revert Task 6 only; Tasks 1-5 remain valuable and low coupling.
