# Stale Binary Detection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `fixthis-mcp` 콘솔이 stale Kotlin/sidekick 바이너리에 연결됐을 때 silent fail 하지 않고 사용자에게 명시적으로 알리도록 한다. 운영 절차(스크립트+문서)와 자동 감지(build-time embedding + banner) 를 단계적으로 도입.

**Architecture:** 세 가지 layer 의 타임스탬프(콘솔 JS bundle / fixthis-mcp JAR / sidekick APK) 를 build time 에 임베드하고, 콘솔 boot 시 `/api/server-version` 으로 비교해 mismatch 를 dismissible 배너로 surface 한다. BridgeStatus DTO 에 `sidekickBuildEpochMs` 를 nullable 로 추가하고 `BridgeProtocol.VERSION` 을 강제 검증한다.

**Tech Stack:** Vanilla ES module JS console concatenated by `scripts/build-console-assets.mjs`; Kotlin/Gradle (sidekick + fixthis-mcp); 기존 `FeedbackConsoleServerTest.kt` 의 string-presence 어서션 패턴 그대로 사용.

**Reference spec:** `docs/superpowers/specs/2026-05-10-stale-binary-detection-design.md`
**Reference impl details:** `docs/superpowers/specs/2026-05-10-stale-binary-detection-implementation-details.md`

---

## File Structure

### Phase 1 — Documentation + Helper Script

**Modify:**
- `CLAUDE.md` — "After Code Changes" 섹션 추가

**Create:**
- `scripts/restart-console.sh` — pkill + installDist + 재시작 헬퍼

### Phase 2 — Build-time Embedding + Console Banner

**Modify:**
- `scripts/build-console-assets.mjs` — bundle 상단에 `ConsoleBuildEpochMs` / `ConsoleBuildGitSha` 임베드 + module 순서에 `staleness.js` 포함
- `fixthis-mcp/build.gradle.kts` — `generateBuildInfo` 태스크 + `compileKotlin` dependency
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleHttpServer.kt` — `/api/server-version` 핸들러 추가 (실제 파일 이름이 다를 수 있음 — `:fixthis-mcp:test` 의 console http server 위치를 확인할 것)
- `fixthis-mcp/src/main/console/main.js` — boot sequence 끝에 `checkServerStaleness()` 호출
- `fixthis-mcp/src/main/resources/console/index.html` — staleness banner element 추가
- `fixthis-mcp/src/main/resources/console/styles.css` — banner 스타일
- `fixthis-mcp/src/main/resources/console/app.js` — auto-regenerated bundle

**Create:**
- `fixthis-mcp/src/main/console/staleness.js` — checkServerStaleness, renderStalenessBanner, dismissable state
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/BuildInfo.kt` — Gradle generated, **do not commit; .gitignore the generated dir**

**Test (modify):**
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

**Test (create):**
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ServerVersionEndpointTest.kt`

### Phase 3 — BridgeStatus Version + Protocol Compat

**Modify:**
- `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocol.kt` — VERSION bump + `sidekickBuildEpochMs: Long?` 필드 추가
- `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt` — `status()` 에서 `sidekickBuildEpochMs` 채움
- `fixthis-compose-sidekick/build.gradle.kts` — `generateBuildInfo` 태스크
- `fixthis-mcp/src/main/console/staleness.js` — `MinimumSupportedProtocolVersion` 추가 + `checkProtocolCompat`
- `fixthis-mcp/src/main/console/connection.js` — `applyConnectionStatus` 끝에서 `checkProtocolCompat(status)` 호출
- `CLAUDE.md` (또는 `CONTRIBUTING.md`) — protocol VERSION bump PR 가이드

**Create:**
- `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/BuildInfo.kt` — Gradle generated

**Test (modify):**
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`
- `fixthis-compose-sidekick/src/test/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocolTest.kt` (또는 인접한 테스트 파일)

### Phase 4 — Build + Docs

**Modify:**
- `CHANGELOG.md`

**Run:**
- `node scripts/build-console-assets.mjs`
- `./gradlew :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-mcp:installDist :fixthis-cli:installDist`
- `bash scripts/restart-console.sh && curl -s http://localhost:60006/api/server-version | jq .`

---

# Phase 1 — Documentation + Helper Script

## Task 1: Add "After Code Changes" section to CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Read CLAUDE.md to find insertion point**

```bash
grep -n "^## " CLAUDE.md
```

Pick a logical position — recommended: 직후 of "## Build Commands" 섹션 끝.

- [ ] **Step 2: Insert section**

```markdown
## After Code Changes — Restart Console Stack

`fixthis-mcp` 의 console JS 는 `--console-assets-dir` 로 source 디렉토리에서 라이브 리드되지만, **Kotlin 서버 코드는 JAR 메모리에 고정**되어 프로세스 재시작이 필요합니다.

어떤 코드 변경이든 머지 후 또는 fixthis-mcp/sidekick 코드를 수정한 뒤에는 다음을 실행하세요:

```bash
bash scripts/restart-console.sh                  # JAR 만 재빌드 + 재시작
bash scripts/restart-console.sh --with-app       # 샘플 APK 도 재빌드 + 재설치
```

자세한 진단 절차와 stale binary 감지 가이드는 `docs/superpowers/specs/2026-05-10-stale-binary-detection-design.md` 참고.
\```
```

- [ ] **Step 3: Verify**

```bash
grep -c "After Code Changes — Restart Console Stack" CLAUDE.md
```

Expected: `1`.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "$(cat <<'EOF'
docs(claude-md): add restart-console-stack guidance after code changes

Task: 1 (stale-binary-detection)
Risk: low
Files: CLAUDE.md
EOF
)"
```

---

## Task 2: Create scripts/restart-console.sh

**Files:**
- Create: `scripts/restart-console.sh`

- [ ] **Step 1: Verify scripts/ directory and existing patterns**

```bash
ls scripts/
```

- [ ] **Step 2: Create the script**

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

WITH_APP=false
DRY_RUN=false
EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-app) WITH_APP=true; shift ;;
    --dry-run)  DRY_RUN=true;  shift ;;
    *)          EXTRA_ARGS+=("$1"); shift ;;
  esac
done

run() {
  if $DRY_RUN; then
    echo "DRY: $*"
  else
    "$@"
  fi
}

echo "→ Killing existing fixthis-mcp / fixthis-cli console processes…"
run pkill -f 'fixthis-mcp.*--console' 2>/dev/null || true
run pkill -f 'fixthis-cli.*console'   2>/dev/null || true

# 명명된 screen 세션 정리 (이름 패턴: fixthis-console-*)
if command -v screen >/dev/null 2>&1; then
  screen -ls 2>/dev/null \
    | awk '/[0-9]+\.fixthis-console-[0-9]+/ {print $1}' \
    | while IFS= read -r session; do
        [[ -n "$session" ]] && run screen -S "$session" -X quit 2>/dev/null || true
      done
fi

echo "→ Building distributions (incremental gradle)…"
cd "$ROOT"
run ./gradlew :fixthis-mcp:installDist :fixthis-cli:installDist

if $WITH_APP; then
  echo "→ Rebuilding + reinstalling sample APK…"
  run ./gradlew :app:assembleDebug :app:installDebug
fi

echo "→ Starting console (Ctrl-C to exit)…"
exec "$ROOT/fixthis-cli/build/install/fixthis/bin/fixthis" console \
  --package io.github.beyondwin.fixthis.sample \
  "${EXTRA_ARGS[@]}"
```

- [ ] **Step 3: Make executable + verify**

```bash
chmod +x scripts/restart-console.sh
bash -n scripts/restart-console.sh                # syntax check
bash scripts/restart-console.sh --dry-run         # logic check (no real action)
```

Expected: dry-run prints what would run; no errors.

- [ ] **Step 4: Manual smoke (optional)**

```bash
# 별도 터미널에서 fixthis 콘솔이 떠 있는지 확인 후
bash scripts/restart-console.sh
# 새 인스턴스가 떴고, 옛 PID 가 사라졌는지 확인
ps aux | grep -E 'fixthis-(mcp|cli).*console' | grep -v grep
```

- [ ] **Step 5: Commit**

```bash
git add scripts/restart-console.sh
git commit -m "$(cat <<'EOF'
feat(scripts): add restart-console.sh helper for clean console restart

Task: 2 (stale-binary-detection)
Risk: low
Files: scripts/restart-console.sh
EOF
)"
```

---

# Phase 2 — Build-time Embedding + Console Banner

## Task 3: Embed ConsoleBuildEpochMs and ConsoleBuildGitSha in app.js bundle

**Files:**
- Modify: `scripts/build-console-assets.mjs`
- Modify: `fixthis-mcp/src/main/resources/console/app.js` (auto-regenerated)
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun consoleBundleEmbedsBuildEpochAndGitSha() {
    val html = FeedbackConsoleAssets.indexHtml
    assertTrue(html.contains("const ConsoleBuildEpochMs = "), "must embed build epoch")
    assertTrue(html.contains("const ConsoleBuildGitSha = '"), "must embed git sha")
}
```

- [ ] **Step 2: Run, verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*consoleBundleEmbedsBuildEpoch*' -i
```

Expected: fail.

- [ ] **Step 3: Modify build-console-assets.mjs**

Read `scripts/build-console-assets.mjs` first. Locate the concat output construction. Add at the top of the emit:

```javascript
import { execSync } from 'node:child_process';

function gitShortSha() {
  try {
    return execSync('git rev-parse --short HEAD', { encoding: 'utf-8' }).trim();
  } catch {
    return 'unknown';
  }
}

function roundedBuildEpoch() {
  // Round to minute to avoid app.js dirty diff on every rebuild within the same minute.
  const now = Date.now();
  return Math.floor(now / 60000) * 60000;
}

const buildHeader = [
  `            const ConsoleBuildEpochMs = ${roundedBuildEpoch()};`,
  `            const ConsoleBuildGitSha = '${gitShortSha()}';`,
].join('\n');
```

Inject `buildHeader` into the module sequence — recommended position: directly after `state.js` content, before `api.js` content. The byte-equality test `generatedConsoleAppMatchesConsoleSourceModules` will then need the header to be considered part of `state.js`'s emission OR a separately tracked virtual module.

**Decision (per impl details §1):** treat the header as a virtual prefix to `state.js`. Modify `generatedConsoleAppMatchesConsoleSourceModules` to construct expected bytes accordingly (see Task 3a if separate, otherwise inline here).

- [ ] **Step 4: Update byte-equality test**

In `FeedbackConsoleServerTest.kt`, locate `generatedConsoleAppMatchesConsoleSourceModules` (~line 480-507). Augment expected bytes to include `buildHeader` after state.js's emission. The exact header pattern test should also assert on a regex (since epoch varies):

```kotlin
private val buildHeaderPattern = Regex(
    """^\s*const ConsoleBuildEpochMs = \d+;\s*const ConsoleBuildGitSha = '[a-f0-9]+';""",
    RegexOption.MULTILINE
)
```

- [ ] **Step 5: Rebuild + run tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: green. New test PASS, byte-equality test still PASS.

- [ ] **Step 6: Commit**

```bash
git add scripts/build-console-assets.mjs \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "$(cat <<'EOF'
feat(console): embed build epoch and git sha into bundled app.js

Task: 3 (stale-binary-detection)
Risk: mid
Files: build-console-assets.mjs, app.js, FeedbackConsoleServerTest.kt
EOF
)"
```

---

## Task 4: Generate fixthis-mcp BuildInfo.kt at build time

**Files:**
- Modify: `fixthis-mcp/build.gradle.kts`
- Create (generated): `fixthis-mcp/build/generated/source/buildinfo/main/kotlin/io/github/beyondwin/fixthis/mcp/BuildInfo.kt`
- Test (create): `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/BuildInfoTest.kt`

- [ ] **Step 1: Write failing test**

Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/BuildInfoTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp

import kotlin.test.Test
import kotlin.test.assertTrue

class BuildInfoTest {
    @Test
    fun buildInfoExposesBuildEpoch() {
        assertTrue(BuildInfo.BUILD_EPOCH_MS > 0L, "build epoch must be set")
    }

    @Test
    fun buildInfoExposesGitSha() {
        assertTrue(BuildInfo.GIT_SHA.isNotBlank(), "git sha must be set")
        assertTrue(BuildInfo.GIT_SHA.length in 4..40, "git sha length plausible")
    }
}
```

- [ ] **Step 2: Verify FAIL (compile error — BuildInfo doesn't exist)**

```bash
./gradlew :fixthis-mcp:test --tests '*BuildInfoTest*' -i
```

Expected: compile error.

- [ ] **Step 3: Add generateBuildInfo task to fixthis-mcp/build.gradle.kts**

Read `fixthis-mcp/build.gradle.kts` first. Append:

```kotlin
val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/source/buildinfo/main/kotlin")
    val gitShaProvider = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText
    val nowProvider = providers.provider {
        // Round to minute for incremental-build determinism.
        (System.currentTimeMillis() / 60_000L) * 60_000L
    }
    outputs.dir(outputDir)
    inputs.property("gitSha", gitShaProvider.map { it.trim().ifBlank { "unknown" } })
    inputs.property("buildEpoch", nowProvider)
    doLast {
        val sha = gitShaProvider.get().trim().ifBlank { "unknown" }
        val epoch = nowProvider.get()
        val target = outputDir.get().file("io/github/beyondwin/fixthis/mcp/BuildInfo.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            package io.github.beyondwin.fixthis.mcp
            object BuildInfo {
                const val BUILD_EPOCH_MS: Long = ${epoch}L
                const val GIT_SHA: String = "$sha"
            }
            """.trimIndent()
        )
    }
}
kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateBuildInfo.map { it.outputs.files })
}
tasks.named("compileKotlin") { dependsOn(generateBuildInfo) }
```

- [ ] **Step 4: Add generated dir to .gitignore**

Append to `fixthis-mcp/.gitignore` (create if missing) or to root `.gitignore`:

```
fixthis-mcp/build/generated/source/buildinfo/
```

- [ ] **Step 5: Rebuild + run tests**

```bash
./gradlew :fixthis-mcp:test --tests '*BuildInfoTest*' -i
```

Expected: green.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/build.gradle.kts \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/BuildInfoTest.kt \
        .gitignore
git commit -m "$(cat <<'EOF'
feat(mcp): generate BuildInfo.kt with build epoch and git sha

Task: 4 (stale-binary-detection)
Risk: mid
Files: build.gradle.kts, BuildInfoTest.kt, .gitignore
EOF
)"
```

---

## Task 5: Add /api/server-version HTTP endpoint

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleHttpServer.kt` (정확한 파일은 grep 으로 확인)
- Test (create): `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ServerVersionEndpointTest.kt`

- [ ] **Step 1: Locate the HTTP handler routing**

```bash
grep -rn '"/api/' fixthis-mcp/src/main/kotlin/ | grep -v test
```

핸들러를 라우팅하는 파일을 찾는다 (예: `FeedbackConsoleHttpServer.kt` 또는 `McpConsoleHandler.kt`).

- [ ] **Step 2: Write failing test**

Create `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ServerVersionEndpointTest.kt`:

```kotlin
package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.mcp.BuildInfo
import io.github.beyondwin.fixthis.compose.sidekick.bridge.BridgeProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerVersionEndpointTest {
    @Test
    fun serverVersionEndpointReturnsBuildInfo() {
        // 패턴: 기존 FeedbackConsoleServerTest 의 fakeExchange 패턴을 차용
        val response = invokeEndpoint("/api/server-version")
        assertEquals(200, response.statusCode)
        val json = Json.parseToJsonElement(response.body).jsonObject
        assertTrue(json["serverBuildEpochMs"]?.jsonPrimitive?.long == BuildInfo.BUILD_EPOCH_MS)
        assertTrue(json["serverGitSha"]?.jsonPrimitive?.content == BuildInfo.GIT_SHA)
        assertTrue(json["bridgeProtocolVersion"]?.jsonPrimitive?.int == BridgeProtocol.VERSION)
    }
}
```

(`invokeEndpoint` 는 기존 FeedbackConsoleServerTest 의 helper 를 따라 작성하거나 인접한 test file 에서 차용. 정확한 패턴은 그 파일을 참고.)

- [ ] **Step 3: Verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*ServerVersionEndpoint*' -i
```

Expected: fail (404 or compile error).

- [ ] **Step 4: Implement the endpoint**

라우팅 파일에서 기존 `/api/connection`, `/api/sessions` 핸들러 직후에 추가:

```kotlin
"/api/server-version" -> {
    exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    exchange.responseHeaders.add("Cache-Control", "no-store")
    val payload = buildJsonObject {
        put("serverBuildEpochMs", BuildInfo.BUILD_EPOCH_MS)
        put("serverGitSha", BuildInfo.GIT_SHA)
        put("bridgeProtocolVersion", BridgeProtocol.VERSION)
    }.toString()
    val bytes = payload.toByteArray(Charsets.UTF_8)
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}
```

(실제 패턴은 인접 핸들러를 참고. JSON 빌더는 프로젝트가 쓰는 라이브러리에 맞춤.)

- [ ] **Step 5: Run tests**

```bash
./gradlew :fixthis-mcp:test --tests '*ServerVersionEndpoint*' --tests '*FeedbackConsoleServerTest*' -i
```

Expected: green.

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/ \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/ServerVersionEndpointTest.kt
git commit -m "$(cat <<'EOF'
feat(mcp): add /api/server-version endpoint exposing build info

Task: 5 (stale-binary-detection)
Risk: mid
Files: FeedbackConsoleHttpServer.kt (or actual file), ServerVersionEndpointTest.kt
EOF
)"
```

---

## Task 6: Create staleness.js module

**Files:**
- Create: `fixthis-mcp/src/main/console/staleness.js`
- Modify: `scripts/build-console-assets.mjs` — module 순서에 `staleness.js` 포함
- Modify: `fixthis-mcp/src/main/resources/console/app.js` (auto-regenerated)
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `FeedbackConsoleServerTest.kt`:

```kotlin
@Test
fun stalenessModuleExposesCheckAndRender() {
    val html = FeedbackConsoleAssets.indexHtml
    assertTrue(html.contains("const StaleThresholdMs"), "threshold const must exist")
    assertTrue(html.contains("async function checkServerStaleness"), "check function must exist")
    assertTrue(html.contains("function renderStalenessBanner"), "render function must exist")
}

@Test
fun stalenessCheckHandlesMissingEndpoint() {
    val html = FeedbackConsoleAssets.indexHtml
    val body = javascriptFunctionBody(html, "checkServerStaleness")
    // 404 = stale signal; network error = silent
    assertTrue(body.contains("resp.status === 404") || body.contains("!resp.ok"),
        "must treat 404 as stale signal")
}
```

- [ ] **Step 2: Verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*staleness*' -i
```

Expected: fail.

- [ ] **Step 3: Create staleness.js**

```javascript
            // staleness.js — detects stale fixthis-mcp / sidekick by comparing build epochs.
            const StaleThresholdMs = 5 * 60 * 1000;
            const StalenessDismissKey = 'fixthis.console.stalenessDismissedHash';

            async function checkServerStaleness() {
              try {
                const resp = await fetch('/api/server-version');
                if (resp.status === 404) {
                  // /api/server-version 없는 옛 fixthis-mcp = stale Kotlin
                  renderStalenessBanner({
                    severity: 'warning',
                    headline: 'Server is older than this console',
                    detail: 'Restart fixthis-mcp to apply the latest server code.',
                    hash: 'pre-version-endpoint',
                  });
                  return;
                }
                if (!resp.ok) return;  // 그 외 에러는 ambiguous — silent
                const server = await resp.json();
                const drift = Math.abs(server.serverBuildEpochMs - ConsoleBuildEpochMs);
                if (drift > StaleThresholdMs) {
                  const hash = `${server.serverGitSha}-${ConsoleBuildGitSha}`;
                  renderStalenessBanner({
                    severity: 'warning',
                    headline: 'Server build is older than this console',
                    detail: `Client ${ConsoleBuildGitSha} → Server ${server.serverGitSha}. Restart fixthis-mcp.`,
                    hash,
                  });
                }
              } catch (_err) {
                // network or JSON error — silent
              }
            }

            function renderStalenessBanner(info) {
              const banner = document.getElementById('stalenessBanner');
              if (!banner) return;
              const dismissed = sessionStorage.getItem(StalenessDismissKey);
              if (dismissed === info.hash) return;
              banner.dataset.severity = info.severity || 'warning';
              banner.querySelector('[data-headline]').textContent = info.headline;
              banner.querySelector('[data-detail]').textContent = info.detail;
              banner.dataset.hash = info.hash;
              banner.hidden = false;
            }

            document.getElementById('stalenessBanner')?.querySelector('[data-dismiss]')
              ?.addEventListener('click', (event) => {
                const banner = event.currentTarget.closest('#stalenessBanner');
                if (!banner) return;
                sessionStorage.setItem(StalenessDismissKey, banner.dataset.hash || '');
                banner.hidden = true;
              });
```

- [ ] **Step 4: Update build-console-assets.mjs module order**

추가 위치 — `state.js` (1번) 직후, `api.js` 직전. 이유: `ConsoleBuildEpochMs` 가 state.js 직후 임베드된 헤더에 정의되고, staleness 가 그것을 참조하기 때문. 정확한 순서는 impl details §1 참고.

```javascript
const sources = [
  'state.js',
  // (build header injects ConsoleBuildEpochMs / ConsoleBuildGitSha here)
  'staleness.js',  // ← 추가
  'api.js',
  'connection.js',
  // ...
];
```

- [ ] **Step 5: Update generatedConsoleAppMatchesConsoleSourceModules**

`FeedbackConsoleServerTest.kt` 의 byte-equality 테스트도 sources 배열에 `staleness.js` 를 추가해야 한다.

- [ ] **Step 6: Rebuild + run tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

Expected: green.

- [ ] **Step 7: Commit**

```bash
git add fixthis-mcp/src/main/console/staleness.js \
        scripts/build-console-assets.mjs \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "$(cat <<'EOF'
feat(console): add staleness.js with build-epoch comparison and dismissable banner

Task: 6 (stale-binary-detection)
Risk: mid
Files: staleness.js, build-console-assets.mjs, app.js, FeedbackConsoleServerTest.kt
EOF
)"
```

---

## Task 7: Add staleness banner element + styles

**Files:**
- Modify: `fixthis-mcp/src/main/resources/console/index.html`
- Modify: `fixthis-mcp/src/main/resources/console/styles.css`
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
@Test
fun stalenessBannerElementExistsInHtml() {
    val html = FeedbackConsoleAssets.indexHtml
    assertTrue(html.contains("id=\"stalenessBanner\""), "banner element must exist")
    assertTrue(html.contains("data-headline"), "banner must have headline slot")
    assertTrue(html.contains("data-detail"), "banner must have detail slot")
    assertTrue(html.contains("data-dismiss"), "banner must have dismiss button")
}

@Test
fun stalenessBannerStylesExistInCss() {
    val css = FeedbackConsoleAssets.stylesCss
    assertTrue(css.contains("#stalenessBanner") || css.contains(".staleness-banner"))
}
```

(`FeedbackConsoleAssets.stylesCss` 가 존재하지 않으면 인접 패턴 — `FeedbackConsoleAssets.indexHtml` 의 inline style 에 검사 — 으로 적응)

- [ ] **Step 2: Verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*staleness*' -i
```

Expected: fail.

- [ ] **Step 3: Add banner element**

`index.html` 의 `<body>` 직후 (또는 main wrapper 의 첫 자식으로):

```html
<div id="stalenessBanner" class="staleness-banner" role="alert" hidden>
  <span data-headline></span>
  <code data-detail></code>
  <button type="button" data-dismiss aria-label="Dismiss">×</button>
</div>
```

- [ ] **Step 4: Add styles**

```css
.staleness-banner {
  position: sticky;
  top: 0;
  z-index: 100;
  background: #fff7e6;
  border-bottom: 1px solid #f0c000;
  color: #5a4500;
  padding: 8px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}
.staleness-banner[data-severity="critical"] {
  background: #ffe6e6;
  border-bottom-color: #d04040;
  color: #5a0000;
}
.staleness-banner [data-headline] { font-weight: 600; }
.staleness-banner [data-detail] {
  font-family: ui-monospace, SFMono-Regular, monospace;
  opacity: 0.85;
}
.staleness-banner [data-dismiss] {
  margin-left: auto;
  background: transparent;
  border: 0;
  font-size: 18px;
  cursor: pointer;
  color: inherit;
}
```

- [ ] **Step 5: Rebuild + tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/resources/console/index.html \
        fixthis-mcp/src/main/resources/console/styles.css \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "$(cat <<'EOF'
feat(console): add staleness banner element and styles

Task: 7 (stale-binary-detection)
Risk: low
Files: index.html, styles.css, app.js, FeedbackConsoleServerTest.kt
EOF
)"
```

---

## Task 8: Wire checkServerStaleness into main.js boot sequence

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `fixthis-mcp/src/main/resources/console/app.js` (auto-regenerated)
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
@Test
fun bootSequenceCallsCheckServerStaleness() {
    val html = FeedbackConsoleAssets.indexHtml
    assertTrue(html.contains("checkServerStaleness().catch"),
        "boot must invoke checkServerStaleness with a catch")
}
```

- [ ] **Step 2: Verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*bootSequenceCallsCheck*' -i
```

- [ ] **Step 3: Modify main.js**

`main.js` 의 boot 종료 부분 (현재 `startSessionsPolling()` 직후) 에 추가:

```javascript
            refresh()
              .then(() => {
                if (shouldAutoFetchPreview()) return refreshPreview();
                return null;
              })
              .then(() => {
                startHeartbeatPolling();
                startLivePreviewPolling();
                startSessionsPolling();
                checkServerStaleness().catch(() => { /* silent */ });   // ← 추가
              })
              .catch(showError);
```

- [ ] **Step 4: Rebuild + tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/console/main.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "$(cat <<'EOF'
feat(console): invoke checkServerStaleness on boot

Task: 8 (stale-binary-detection)
Risk: low
Files: main.js, app.js, FeedbackConsoleServerTest.kt
EOF
)"
```

---

# Phase 3 — BridgeStatus Version + Protocol Compat

## Task 9: Add sidekickBuildEpochMs to BridgeStatus and bump VERSION

**Files:**
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocol.kt`
- Test: `fixthis-compose-sidekick/src/test/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocolTest.kt` (또는 인접 테스트)

- [ ] **Step 1: Read BridgeProtocol.kt and check current VERSION + DTO shape**

```bash
grep -n "VERSION\|BridgeStatus" fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocol.kt
```

- [ ] **Step 2: Write failing test**

기존 `BridgeProtocolTest.kt` 또는 `BridgeStatusInstallEpochTest.kt` 패턴을 차용. 추가:

```kotlin
@Test
fun bridgeStatusIncludesSidekickBuildEpoch() {
    val status = BridgeStatus(
        // 기존 필수 필드 모두 채움
        // ...
        sidekickBuildEpochMs = 1234567890L,
    )
    val json = Json.encodeToString(BridgeStatus.serializer(), status)
    assertTrue(json.contains("\"sidekickBuildEpochMs\":1234567890"))
}

@Test
fun bridgeProtocolVersionBumpedForBuildEpochField() {
    // 옛 VERSION (이번 변경 전 값) 보다 커야 함. 정확한 값은 머지 시점 결정.
    assertTrue(BridgeProtocol.VERSION >= EXPECTED_NEW_VERSION)
}
```

- [ ] **Step 3: Verify FAIL**

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests '*BridgeProtocol*' -i
```

- [ ] **Step 4: Add field + bump VERSION**

`BridgeProtocol.kt` 에서 `BridgeStatus` data class 에 nullable 필드 추가:

```kotlin
@Serializable
data class BridgeStatus(
    // ... 기존 필드 ...
    val sidekickBuildEpochMs: Long? = null,
)

object BridgeProtocol {
    const val VERSION: Int = <current + 1>
}
```

- [ ] **Step 5: Run tests**

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest -i
```

Expected: green.

- [ ] **Step 6: Commit**

```bash
git add fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocol.kt \
        fixthis-compose-sidekick/src/test/kotlin/
git commit -m "$(cat <<'EOF'
feat(sidekick): add sidekickBuildEpochMs to BridgeStatus and bump protocol VERSION

Task: 9 (stale-binary-detection)
Risk: mid
Files: BridgeProtocol.kt, BridgeProtocolTest.kt
EOF
)"
```

---

## Task 10: Generate sidekick BuildInfo.kt and populate sidekickBuildEpochMs in status()

**Files:**
- Modify: `fixthis-compose-sidekick/build.gradle.kts`
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`
- Test: 인접 테스트

- [ ] **Step 1: Write failing test**

```kotlin
@Test
fun statusReportsSidekickBuildEpoch() {
    // 인접 BridgeServerTest 패턴 차용
    val status = inspect.status()
    assertTrue(status.sidekickBuildEpochMs != null && status.sidekickBuildEpochMs > 0L)
}
```

- [ ] **Step 2: Verify FAIL**

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests '*statusReportsSidekickBuildEpoch*' -i
```

- [ ] **Step 3: Add generateBuildInfo task**

Same pattern as Task 4 but for sidekick. `fixthis-compose-sidekick/build.gradle.kts`:

```kotlin
val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/source/buildinfo/main/kotlin")
    val gitShaProvider = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText
    val nowProvider = providers.provider {
        (System.currentTimeMillis() / 60_000L) * 60_000L
    }
    outputs.dir(outputDir)
    inputs.property("gitSha", gitShaProvider.map { it.trim().ifBlank { "unknown" } })
    inputs.property("buildEpoch", nowProvider)
    doLast {
        val sha = gitShaProvider.get().trim().ifBlank { "unknown" }
        val epoch = nowProvider.get()
        val target = outputDir.get().file("io/github/beyondwin/fixthis/compose/sidekick/BuildInfo.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            package io.github.beyondwin.fixthis.compose.sidekick
            object BuildInfo {
                const val BUILD_EPOCH_MS: Long = ${epoch}L
                const val GIT_SHA: String = "$sha"
            }
            """.trimIndent()
        )
    }
}
android.sourceSets.named("main") {
    java.srcDir(generateBuildInfo.map { it.outputs.files })
}
tasks.matching { it.name == "compileDebugKotlin" || it.name == "compileReleaseKotlin" }
    .configureEach { dependsOn(generateBuildInfo) }
```

(Android 모듈이므로 `kotlin.sourceSets` 가 아니라 `android.sourceSets`. 정확한 wiring 은 `:fixthis-compose-sidekick:tasks` 로 확인.)

- [ ] **Step 4: Update BridgeServer.status() to include sidekickBuildEpochMs**

```kotlin
override suspend fun status(): BridgeStatus {
    // ... 기존 인스펙션 코드 ...
    return BridgeStatus(
        // ... 기존 필드 ...
        sidekickBuildEpochMs = BuildInfo.BUILD_EPOCH_MS,
    )
}
```

- [ ] **Step 5: .gitignore 업데이트**

```
fixthis-compose-sidekick/build/generated/source/buildinfo/
```

- [ ] **Step 6: Run tests**

```bash
./gradlew :fixthis-compose-sidekick:testDebugUnitTest -i
```

- [ ] **Step 7: Commit**

```bash
git add fixthis-compose-sidekick/build.gradle.kts \
        fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt \
        fixthis-compose-sidekick/src/test/ \
        .gitignore
git commit -m "$(cat <<'EOF'
feat(sidekick): generate BuildInfo and populate sidekickBuildEpochMs in status()

Task: 10 (stale-binary-detection)
Risk: mid
Files: build.gradle.kts, BridgeServer.kt, .gitignore, sidekick tests
EOF
)"
```

---

## Task 11: Add MinimumSupportedProtocolVersion + checkProtocolCompat in console

**Files:**
- Modify: `fixthis-mcp/src/main/console/staleness.js`
- Modify: `fixthis-mcp/src/main/console/connection.js` — `applyConnectionStatus` 끝에 호출 추가
- Modify: `fixthis-mcp/src/main/resources/console/app.js` (auto-regenerated)
- Test: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
@Test
fun stalenessExposesMinimumProtocolVersion() {
    val html = FeedbackConsoleAssets.indexHtml
    assertTrue(html.contains("const MinimumSupportedProtocolVersion"),
        "must expose minimum supported protocol version constant")
    assertTrue(html.contains("function checkProtocolCompat"),
        "must expose checkProtocolCompat function")
}

@Test
fun applyConnectionStatusCallsCheckProtocolCompat() {
    val html = FeedbackConsoleAssets.indexHtml
    val body = javascriptFunctionBody(html, "applyConnectionStatus")
    assertTrue(body.contains("checkProtocolCompat"),
        "applyConnectionStatus must invoke checkProtocolCompat")
}
```

- [ ] **Step 2: Verify FAIL**

```bash
./gradlew :fixthis-mcp:test --tests '*ProtocolCompat*' --tests '*MinimumProtocol*' -i
```

- [ ] **Step 3: Update staleness.js**

```javascript
const MinimumSupportedProtocolVersion = <BridgeProtocol.VERSION 의 현재 값>;

function checkProtocolCompat(status) {
  const v = status?.bridgeProtocolVersion ?? 0;
  if (v < MinimumSupportedProtocolVersion) {
    renderStalenessBanner({
      severity: 'critical',
      headline: `Bridge protocol v${v} is too old`,
      detail: `Reinstall the sample APK (./gradlew :app:installDebug) to update sidekick.`,
      hash: `protocol-${v}`,
    });
  }
}

function checkSidekickBuildEpoch(status) {
  const sidekickEpoch = status?.sidekickBuildEpochMs;
  if (typeof sidekickEpoch !== 'number') return;
  const drift = Math.abs(sidekickEpoch - ConsoleBuildEpochMs);
  if (drift > StaleThresholdMs) {
    renderStalenessBanner({
      severity: 'warning',
      headline: 'Sample app sidekick is older than this console',
      detail: `Reinstall the sample APK to refresh.`,
      hash: `sidekick-${sidekickEpoch}`,
    });
  }
}
```

- [ ] **Step 4: Wire into connection.js**

`applyConnectionStatus` 끝부분 (현재 `renderPreviewRegion()` 호출 직후):

```javascript
              renderPreviewRegion();
              checkProtocolCompat(status);          // ← 추가
              checkSidekickBuildEpoch(status);      // ← 추가
            }
```

- [ ] **Step 5: Rebuild + tests**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest*' -i
```

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/console/staleness.js \
        fixthis-mcp/src/main/console/connection.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt
git commit -m "$(cat <<'EOF'
feat(console): protocol compat check and sidekick build-epoch staleness

Task: 11 (stale-binary-detection)
Risk: mid
Files: staleness.js, connection.js, app.js, FeedbackConsoleServerTest.kt
EOF
)"
```

---

## Task 12: Document protocol VERSION bump rule

**Files:**
- Modify: `CLAUDE.md` (또는 `CONTRIBUTING.md` 가 있으면 그것)

- [ ] **Step 1: Locate insertion point**

```bash
grep -n "^## " CLAUDE.md
```

- [ ] **Step 2: Add section**

```markdown
## Bridge Protocol Compatibility

`BridgeStatus` 또는 `BridgeProtocol` 의 시그니처를 바꾸는 PR 은 다음을 같이 변경합니다:

1. `fixthis-compose-sidekick/.../bridge/BridgeProtocol.kt` 의 `VERSION` 을 +1 bump
2. 콘솔의 `MinimumSupportedProtocolVersion` (`fixthis-mcp/src/main/console/staleness.js`) 도 같은 값으로 bump (새 필드를 콘솔이 직접 사용하는 경우)

런타임에 `bridgeProtocolVersion < MinimumSupportedProtocolVersion` 이 검출되면 콘솔이 빨간 staleness banner 를 띄웁니다.
```

- [ ] **Step 3: Verify + commit**

```bash
grep -c "Bridge Protocol Compatibility" CLAUDE.md
git add CLAUDE.md
git commit -m "$(cat <<'EOF'
docs(claude-md): add bridge protocol VERSION bump rule

Task: 12 (stale-binary-detection)
Risk: low
Files: CLAUDE.md
EOF
)"
```

---

# Phase 4 — Build + Docs

## Task 13: Final build, full sweep, CHANGELOG

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Rebuild bundle + full unit-test sweep**

```bash
node scripts/build-console-assets.mjs
./gradlew :fixthis-compose-core:test \
          :fixthis-cli:test \
          :fixthis-mcp:test \
          :fixthis-compose-sidekick:testDebugUnitTest \
          :fixthis-gradle-plugin:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: installDist + verify endpoint**

```bash
./gradlew :fixthis-mcp:installDist :fixthis-cli:installDist
bash scripts/restart-console.sh &
sleep 3
curl -s http://localhost:60006/api/server-version | jq .
# Expected JSON with serverBuildEpochMs, serverGitSha, bridgeProtocolVersion
```

- [ ] **Step 3: Lint**

```bash
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

- [ ] **Step 4: CHANGELOG entry**

`CHANGELOG.md` 의 `## Unreleased` 아래 prepend:

```markdown
### Added
- `scripts/restart-console.sh` 헬퍼 — stale fixthis-mcp/cli 프로세스를 종료하고 incremental gradle 빌드 + 재시작을 단일 커맨드로. `--with-app` 으로 샘플 APK 도 같이 갱신.
- 콘솔 boot 시 자동 staleness 감지 — `fixthis-mcp` JAR 또는 sidekick 빌드가 5분 이상 차이 나면 dismissable banner 로 알림. `/api/server-version` 엔드포인트 + `BridgeStatus.sidekickBuildEpochMs` 필드 추가.

### Changed
- `BridgeProtocol.VERSION` bump (sidekickBuildEpochMs 필드 추가). 옛 sample APK + 새 콘솔 조합이면 빨간 banner.
```

- [ ] **Step 5: Manual smoke (recommended)**

옛 fixthis-mcp 인스턴스를 띄운 채 (예: 새 빌드 전에 시작), 새로 빌드된 콘솔 JS 를 새 브라우저 탭에서 로드 → 노란 banner 출현 확인. dismiss 후 reload → 같은 hash 면 안 뜨는지 확인.

- [ ] **Step 6: Commit**

```bash
git add CHANGELOG.md fixthis-mcp/src/main/resources/console/app.js
git commit -m "$(cat <<'EOF'
chore: rebundle app.js and add CHANGELOG entry for stale-binary detection

Task: 13 (stale-binary-detection)
Risk: mid
Files: CHANGELOG.md, app.js
EOF
)"
```

---

## Self-Review

(Performed by the plan author before handing off.)

**Spec coverage:**
- G1 (운영 절차 단순화): Tasks 1, 2 ✓
- G2 (CLAUDE.md 명문화): Task 1 + Task 12 ✓
- G3 (콘솔 staleness 자동 감지 + banner): Tasks 3-8 ✓
- G4 (BridgeStatus + protocol compat): Tasks 9-12 ✓

**Placeholder scan:** 다음 항목은 plan 시점에 정확한 값/경로를 모르므로 implementer 가 확인 필요로 명시:
- Task 5: `/api/server-version` 라우팅 파일의 정확한 이름 (`grep -rn '"/api/'` 로 찾는다)
- Task 9: 현재 `BridgeProtocol.VERSION` 값 (코드 확인 필요, EXPECTED_NEW_VERSION 도 그것 + 1)
- Task 10: Android Gradle 의 sourceSets API (kotlin.sourceSets vs android.sourceSets)
- Task 11: `MinimumSupportedProtocolVersion` 의 정확한 정수값 — BridgeProtocol.VERSION 의 현재 값

**Type/name consistency:**
- `ConsoleBuildEpochMs`, `ConsoleBuildGitSha` — `state.js` 직후 단일 선언, staleness.js 가 참조
- `StaleThresholdMs` — staleness.js 단일 선언
- `MinimumSupportedProtocolVersion` — staleness.js 단일 선언
- `checkServerStaleness`, `checkProtocolCompat`, `checkSidekickBuildEpoch`, `renderStalenessBanner` — 모두 staleness.js 정의, main.js / connection.js 호출
- `BuildInfo` — fixthis-mcp 와 sidekick 각각의 패키지에 별도 객체 (충돌 없음)

**Forward-reference handling:**
- staleness.js 가 `ConsoleBuildEpochMs` 와 `ConsoleBuildGitSha` 참조 — 둘은 build-console-assets.mjs 가 state.js 직후에 임베드. 번들 순서: state.js → (build header) → staleness.js → api.js → ...
- main.js 가 `checkServerStaleness` 호출 — staleness.js 가 main.js 보다 앞에 있으므로 OK
- connection.js 가 `checkProtocolCompat`, `checkSidekickBuildEpoch` 호출 — 둘 다 staleness.js 정의. staleness.js 를 connection.js 보다 앞에 두어야 함 → 모듈 순서 `state.js → staleness.js → api.js → connection.js` 가 그것을 만족.

**Test coverage gaps (manual / E2E):**
- 옛 fixthis-mcp 띄운 채 새 콘솔 접속 → 노란 banner — Task 13 manual smoke
- 옛 sample APK + 새 fixthis-mcp → 빨간 banner — Task 13 manual smoke (또는 별도)
- banner dismiss persistence (sessionStorage) — 자동 검증 어려움, 수동
- `/api/server-version` 부재 케이스 (404) — Task 6 의 검증 어서션 + 수동 확인

**Open Questions (spec 의 Q1–Q7 중 plan 단계 결정):**
- Q1 (epoch 라운딩): **분 단위(60s)** 채택 — 같은 분 내 재빌드는 dirty diff 없음. Tasks 3, 4, 10 모두 같은 패턴.
- Q2 (banner dismiss): **sessionStorage + hash 기반** — 같은 mismatch 는 다시 안 뜨고, 새 hash 는 다시 뜸. Task 6 구현.
- Q3 (/api/server-version stability): **internal 으로 둠** — 외부 도구가 의존하지 않게 README 등에 명시 안 함.
- Q4 (멀티 sidekick 인스턴스): out of scope. 본 plan 은 in-process sidekick 만.
- Q5 (CI lint VERSION bump 강제): out of scope. Task 12 가 가이드만 추가.
- Q6 (`--no-rebuild` 플래그): out of scope. gradle UP-TO-DATE 가 빠르므로 불필요.
- Q7 (멀티 머신 시계 동기): NTP 가정. 별도 검증 안 함.

**Risk 재평가:**
- Phase 1 — risk 낮음. 단순 doc + script.
- Phase 2 — risk 중간. build-console-assets.mjs 의 byte-equality 테스트가 이번 변경과 충돌 가능. `staleness.js` 모듈 순서 결정도 신중.
- Phase 3 — risk 중간. Android Gradle 의 sourceSets 와이어가 fixthis-mcp 의 kotlin sourceSets 와 패턴이 다를 가능성. `:fixthis-compose-sidekick:tasks` 로 검증 필요.

No additional fixes required.

---

## Execution Handoff

**Plan saved to `docs/superpowers/plans/2026-05-10-stale-binary-detection.md`. 두 가지 실행 옵션:**

**1. Subagent-Driven (recommended for Phase 2+)** — Phase 별 fresh subagent 디스패치, review/verify 게이트. ~3.5–6 시간 (Phase 1+2) / ~반일 (전체).

**2. Inline Execution** — Phase 1 만 단독으로 인라인 실행 (~30분). Phase 2 이상은 subagent 권장.

**3. Multi-agent-executor** — `/kws-skills:kws-claude-multi-agent-executor` 로 spec + plan 동시 인계.

**Which approach?**
