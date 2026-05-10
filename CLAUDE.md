# FixThis

See [AGENTS.md](AGENTS.md) for project overview, MCP setup, AI workflow, and constraints.

## Build Commands

```bash
# All unit tests
./gradlew :fixthis-compose-core:test \
          :fixthis-cli:test \
          :fixthis-mcp:test \
          :fixthis-compose-sidekick:testDebugUnitTest \
          :fixthis-gradle-plugin:test

# Build sample app
./gradlew :app:assembleDebug

# Build CLI + MCP distributions (required before fixthis setup or fixthis run)
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist

# Rebundle console JS modules after editing fixthis-mcp/src/main/console/*.js
node scripts/build-console-assets.mjs

# JS console asset syntax check (the bundled output)
node --check fixthis-mcp/src/main/resources/console/app.js

# Full lint+diff check
git diff --check
```

The console JS sources live as ES modules under `fixthis-mcp/src/main/console/`. They are concatenated into the served `fixthis-mcp/src/main/resources/console/app.js` by `scripts/build-console-assets.mjs`. Rebundle after editing any module, then re-run `installDist` to refresh the packaged JAR.

## After Code Changes — Restart Console Stack

`fixthis-mcp` 의 console JS 는 `--console-assets-dir` 로 source 디렉토리에서 라이브 리드되지만, **Kotlin 서버 코드는 JAR 메모리에 고정**되어 프로세스 재시작이 필요합니다.

어떤 코드 변경이든 머지 후 또는 fixthis-mcp/sidekick 코드를 수정한 뒤에는 다음을 실행하세요:

```bash
bash scripts/restart-console.sh                  # JAR 만 재빌드 + 재시작
bash scripts/restart-console.sh --with-app       # 샘플 APK 도 재빌드 + 재설치
```

자세한 진단 절차와 stale binary 감지 가이드는 `docs/superpowers/specs/2026-05-10-stale-binary-detection-design.md` 참고.

## Module Map

```
:app (sample/)            — validation sample app; Gradle path :app, sources in sample/
:fixthis-compose-core     — pure Kotlin domain: models, use cases, formatters, source matching
:fixthis-compose-sidekick — debug Android runtime: bridge server, semantics, screenshots
:fixthis-gradle-plugin    — source-index generation, debug dependency injection
:fixthis-cli              — desktop CLI: run, doctor, setup, mcp, console commands
:fixthis-mcp              — stdio MCP server + local HTTP feedback console
```

## Architecture Invariants

- `:fixthis-compose-core` has no knowledge of MCP, CLI, Android UI surfaces, or `.fixthis/` file layout. All modules translate their DTOs and state into core domain contracts explicitly.
- `:app` Gradle project path maps to `sample/` source directory.
- Persisted MCP JSON field names (`items`, `screens`, `itemId`, `screenId`) are compatibility contracts — do not rename them. See `session/SessionDtoModels.kt` and `console/AnnotationRequestModels.kt`.
- The Android sidekick does not host an MCP or HTTP server. Only the desktop `fixthis-mcp` process does.
- `Send Agent` in the browser console is local persistence only. It does not call any external AI API.

## Console UI Development

Pass `--console-assets-dir` to read HTML/CSS/JS directly from source instead of the packaged JAR:

```bash
fixthis-cli/build/install/fixthis/bin/fixthis console \
  --package io.beyondwin.fixthis.sample \
  --console-assets-dir "$PWD/fixthis-mcp/src/main/resources/console"
```

This is for contributors iterating on console UI. Normal users use packaged resources.

## Connected Test Notes

`./gradlew connectedAndroidTest` requires an unlocked interactive emulator or device. A physical device reporting `device` in `adb devices` can still fail Compose hierarchy discovery behind a lockscreen. See [docs/troubleshooting.md](docs/troubleshooting.md).
