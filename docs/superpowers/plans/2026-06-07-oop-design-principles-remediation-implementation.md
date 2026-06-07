# OOP 설계 원칙 리메디에이션 (엄브렐라) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 세 군데의 설계 부채(CLI 커맨드 결합, SourceMatcher 책임 배치, 세션 델리게이트 잔여 정리)를 동작 보존 리팩토링으로 제거한다.

**Architecture:** Track A는 setup 도메인 로직을 순수 `SetupService`로 추출하고 누적 결과를 명시 주입(`SetupReport`)으로 받아 전역 가변 싱글톤·argv 재파싱·stdout 해킹을 모두 제거한다. Track B는 점수 가중치를 `SourceSignalKind` 열거 상수 필드로 옮기고 weight-hit 계산을 데이터 주인 `SourceIndexEntry`로 이동한다. Track C는 익명 `Pair`를 명명 타입으로, 생성자 if/else를 단일 경로로 정리한다. 전 과정 기존 테스트 녹색 유지.

**Tech Stack:** Kotlin, Gradle, Clikt(CLI), kotlinx.serialization, JUnit + 기존 테스트 하니스.

Related spec: [`../specs/2026-06-07-oop-design-principles-remediation-detailed-spec.md`](../specs/2026-06-07-oop-design-principles-remediation-detailed-spec.md)

---

## File Structure

**Track A** (`fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/`)
- Create: `SetupReport.kt` — 누적 결과 컬렉터(전역 아님).
- Create: `SetupService.kt` — `writeConfigs`/`installAgent` 도메인 동작.
- Create: `SetupRequest.kt` — 커맨드 옵션 → 값 객체(`SetupRequest`, `InstallRequest`).
- Modify: `SetupCommand.kt` — 커맨드들을 얇은 어댑터로; 전역/argv/ stdout 해킹 삭제.
- Modify: `TwoPhaseConfigCommit.kt:97-101` — 전역 push 제거, 커밋 결과를 호출자에 반환.
- Test: `SetupServiceTest.kt`(신규), 기존 `*SetupCommandTest`/`InstallAgent*Test`/`TwoPhaseConfigCommitTest` 갱신.

**Track B** (`fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/`)
- Modify: `SourceIndex.kt:45-64` — `SourceSignalKind`에 `baseMatchWeight` 생성자 인자.
- Create: `SourceIndexEntryMatching.kt` — weight-hit 확장(엔트리 소유 책임).
- Modify: `SourceMatcher.kt` — 가중치 확장 프로퍼티/`LAYOUT_RENDERER_BASE_WEIGHT` 및 weight-hit 본문 삭제, 이동분 호출로 교체.
- Test: 기존 `SourceMatcher*Test` + 코퍼스 정밀도 게이트; 신규 `SourceSignalKindWeightTest`.

**Track C** (`fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/`)
- Modify: `event/eventlog/SessionCompactionCoordinator.kt:34-40` — `compactionFailureSink` nullable 수용 + 내부 폴백.
- Modify: `store/FeedbackSessionStoreDelegate.kt:82-95, 452-482` — 단일 경로 생성자; `EventBackedMutation<T>` 명명 타입.

---

## Track A — CLI setup 커맨드 결합 해체

### Task A1: 골든 출력 캐릭터라이제이션 테스트 (안전망 먼저)

리팩토링 전, `install-agent --json` 출력과 `setup --write` echo를 문자열로 고정한다.

**Files:**
- Test: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupGoldenOutputTest.kt`

- [ ] **Step 1: 캐릭터라이제이션 테스트 작성**

임시 프로젝트 루트를 만들고 `install-agent --json --dry-run --project-dir <tmp>`를
실행해 stdout JSON을 캡처, 핵심 필드(`applied`, `skipped`, `errors`, `next`,
`readiness`, `restartRequired`)의 존재와 형태를 단언한다. 그리고 `setup --write
--dry-run --target claude --project-dir <tmp>`의 echo 라인 prefix(`Target:`,
`Path:`)를 단언한다.

```kotlin
@Test
fun `install-agent json dry-run emits stable report shape`() {
    val tmp = createTempProjectRoot()  // .fixthis/project.json 포함 헬퍼
    val out = captureStdout {
        InstallAgentCommand().parse(
            listOf("--json", "--dry-run", "--project-dir", tmp.absolutePath, "--target", "local"),
        )
    }
    val json = fixThisJson.parseToJsonElement(out).jsonObject
    assertTrue("applied" in json)
    assertTrue("skipped" in json)
    assertTrue("next" in json)
    assertTrue("restartRequired" in json)
}
```

- [ ] **Step 2: 테스트 통과 확인 (현재 코드 기준 녹색)**

Run: `./gradlew :fixthis-cli:test --tests "*SetupGoldenOutputTest"`
Expected: PASS — 이 테스트는 리팩토링 전/후 모두 통과해야 하는 회귀 가드.

- [ ] **Step 3: Commit**

```bash
git add fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupGoldenOutputTest.kt
git commit -m "test(cli): characterize setup/install-agent output before refactor"
```

### Task A2: `SetupReport` + `SetupRequest` 값 객체 추가

**Files:**
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupReport.kt`
- Create: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupRequest.kt`

- [ ] **Step 1: 타입 작성**

```kotlin
// SetupReport.kt
package io.github.beyondwin.fixthis.cli.commands

internal class SetupReport {
    val applied = mutableListOf<InstallAgentJsonReport.Applied>()
    val skipped = mutableListOf<InstallAgentJsonReport.Skipped>()
    val errors = mutableListOf<InstallAgentJsonReport.ErrorEntry>()
}
```

```kotlin
// SetupRequest.kt
package io.github.beyondwin.fixthis.cli.commands

import java.io.File

internal data class SetupRequest(
    val packageName: String?,
    val projectRoot: File,
    val target: String,
    val serverName: String,
    val write: Boolean,
    val dryRun: Boolean,
    val fullDiff: Boolean = false,
    val verbose: Boolean = false,
)

internal data class InstallRequest(
    val packageName: String?,
    val projectRoot: File,
    val target: String,
    val serverName: String,
    val applyGradlePlugin: Boolean,
    val pluginVersion: String,
    val dryRun: Boolean,
    val verbose: Boolean,
)
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :fixthis-cli:compileKotlin`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupReport.kt \
        fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupRequest.kt
git commit -m "feat(cli): add SetupReport and SetupRequest value objects"
```

### Task A3: `TwoPhaseConfigCommit`에서 전역 push 제거

**Files:**
- Modify: `fixthis-cli/.../commands/TwoPhaseConfigCommit.kt:97-103`

- [ ] **Step 1: 실패 테스트 — commit이 커밋된 plan 목록을 반환한다**

`TwoPhaseConfigCommitTest`에 추가:

```kotlin
@Test
fun `commit returns committed plans instead of writing global state`() {
    val plans = listOf(SetupWritePlan("claude", "project", tmpFile(), "{}"))
    val committed = TwoPhaseConfigCommit().commit(plans)   // 반환형 List<SetupWritePlan>
    assertEquals(listOf("claude"), committed.map { it.writerName })
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :fixthis-cli:test --tests "*TwoPhaseConfigCommitTest*commit returns committed*"`
Expected: FAIL — 현재 `commit`은 `Unit` 반환, `SetupRunResults`에 직접 push.

- [ ] **Step 3: 구현 — `commit`이 `List<SetupWritePlan>` 반환, 전역 push 삭제**

`TwoPhaseConfigCommit.kt`에서 `:97-101`의 `SetupRunResults.applied.get() += …` 블록을
삭제하고 `committed` 리스트를 반환하도록 시그니처 변경:

```kotlin
fun commit(plans: List<SetupWritePlan>): List<SetupWritePlan> {
    ...
    staged.forEach { (plan, stagingFile) ->
        // ... move/fallback/forceDirectory 그대로 ...
        committed += plan
        emit("Wrote ${plan.writerName} MCP config (${plan.scope}): ${plan.configFile.absolutePath}")
    }
    rollbacks.values.filterNotNull().forEach { it.delete() }
    return committed
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :fixthis-cli:test --tests "*TwoPhaseConfigCommitTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/.../commands/TwoPhaseConfigCommit.kt fixthis-cli/.../commands/TwoPhaseConfigCommitTest.kt
git commit -m "refactor(cli): TwoPhaseConfigCommit returns committed plans, no global state"
```

### Task A4: `SetupService` 추출 (writeConfigs)

**Files:**
- Create: `fixthis-cli/.../commands/SetupService.kt`
- Test: `fixthis-cli/.../commands/SetupServiceTest.kt`

- [ ] **Step 1: 실패 테스트 — writeConfigs가 report.applied를 채운다**

```kotlin
@Test
fun `writeConfigs records applied targets in injected report`() {
    val report = SetupReport()
    val emitted = mutableListOf<String>()
    val service = SetupService(report = report, emit = { emitted += it })
    service.writeConfigs(
        SetupRequest(packageName = "com.x", projectRoot = tmpRoot, target = "claude",
            serverName = "fixthis", write = true, dryRun = false),
    )
    assertEquals(listOf("claude"), report.applied.map { it.target })
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :fixthis-cli:test --tests "*SetupServiceTest*writeConfigs records*"`
Expected: FAIL — `SetupService` 미존재.

- [ ] **Step 3: 구현 — `SetupCommand.run`의 --write 경로를 서비스로 이동**

`SetupCommand.run()` 의 `validServerName`/`AndroidSdkLocator`/`McpExecutableLocator`/
`buildMcpConfigEntry`/`SetupPlanner.buildWritePlans` → `TwoPhaseConfigCommit(emit=…)
.commit(plans)` 흐름을 `SetupService.writeConfigs(request)`로 그대로 옮긴다.
`commit`이 반환한 plan들을 `report.applied`에 기록:

```kotlin
internal class SetupService(
    private val report: SetupReport,
    private val emit: (String) -> Unit = {},
) {
    fun writeConfigs(request: SetupRequest) {
        val client = BridgeClient(projectRoot = request.projectRoot)
        val resolvedPackage = failAsCliError { client.resolvePackageName(request.packageName) }
        val validServerName = validateMcpServerName(request.serverName)
        val sdk = AndroidSdkLocator.find()
        val executable = McpExecutableLocator.find()
        warnIfMissing(sdk, executable, resolvedPackage, request.projectRoot)   // 기존 echo 경고
        val entry = buildMcpConfigEntry(resolvedPackage, request.projectRoot, validServerName, sdk, executable)
        val plans = SetupPlanner.buildWritePlans(SetupPlanner.selectedWriters(request.target), request.projectRoot, entry)
        if (request.dryRun) {
            plans.forEach { plan -> renderDryRun(plan, request.fullDiff).forEach(emit) }
            return
        }
        val committed = TwoPhaseConfigCommit(emit = emit).commit(plans)
        committed.forEach { plan ->
            report.applied += InstallAgentJsonReport.Applied(plan.writerName, plan.configFile.absolutePath, plan.scope)
        }
    }
}
```

`SetupCommand.run()`은 --write가 아닐 때만 client config JSON을 echo, 아니면
`SetupService(report, ::echo).writeConfigs(request)` 호출하는 얇은 어댑터로 축소.
`SetupRunResults`·`applyWritePlan`·`renderDryRunOutput`의 중복 echo는 서비스로 이관.

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :fixthis-cli:test --tests "*SetupServiceTest*" --tests "*SetupGoldenOutputTest*"`
Expected: PASS — 골든 출력 불변.

- [ ] **Step 5: Commit**

```bash
git add fixthis-cli/.../commands/SetupService.kt fixthis-cli/.../commands/SetupServiceTest.kt fixthis-cli/.../commands/SetupCommand.kt
git commit -m "refactor(cli): extract SetupService.writeConfigs from SetupCommand"
```

### Task A5: `SetupService.installAgent` 추출 + 커맨드 직접 호출로 배선

**Files:**
- Modify: `fixthis-cli/.../commands/SetupService.kt`, `SetupCommand.kt`(Init/InstallAgent)

- [ ] **Step 1: 실패 테스트 — InstallAgent가 argv 재파싱 없이 서비스를 호출**

```kotlin
@Test
fun `installAgent populates report applied and skipped without nested parse`() {
    val report = SetupReport()
    SetupService(report, emit = {}).installAgent(
        InstallRequest(packageName = "com.x", projectRoot = tmpRoot, target = "local",
            serverName = "fixthis", applyGradlePlugin = false, pluginVersion = "x", dryRun = true, verbose = false),
    )
    assertTrue(report.applied.isNotEmpty() || report.skipped.isNotEmpty())
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :fixthis-cli:test --tests "*SetupServiceTest*installAgent populates*"`
Expected: FAIL — `installAgent` 미존재.

- [ ] **Step 3: 구현 — InitCommand의 install 아티팩트 로직을 서비스로 이동**

`InitCommand.writeInstallArtifacts`(gradle 플러그인 적용 + `AgentSetupFiles.write`)와
`runSetupWrite`를 `SetupService.installAgent`로 합친다. 내부에서 `writeConfigs`를
**직접 함수 호출**(argv 재파싱 아님)하고, gradle/agent 파일은 기존
`GradlePluginInstaller.apply`/`AgentSetupFiles.write`를 그대로 호출하되 `emit`을 주입.
`InstallAgentCommand.run()`은:

```kotlin
override fun run() {
    val root = File(projectDir).canonicalFile
    val decision = GlobalScopeGuard.decide(root, allowGlobal = allowGlobal)
    val effectiveTarget = resolveEffectiveTarget(decision, target)   // 기존 when 로직 유지
    val report = SetupReport()
    val earlySkipped = earlySkipForGlobal(decision, target)          // 기존 로직 유지
    report.skipped += earlySkipped
    if (effectiveTarget == "none") { emitNoneAndAbort(report) ; return }
    val emit: (String) -> Unit = if (json) ({}) else ::echo          // System.setOut 대체
    SetupService(report, emit).installAgent(
        InstallRequest(packageName, root, effectiveTarget, serverName,
            applyGradlePlugin = !skipGradlePlugin, pluginVersion, dryRun, verbose),
    )
    if (json) echo(renderJsonReport(root, report))                   // report에서 직접 구성
    failByExitCode(report)                                            // 기존 종료코드 로직
}
```

`captureStdoutWhenJson`/`System.setOut`/`SetupRunResults`/`InitCommand().parse`/
`SetupCommand().parse` 전부 삭제. `installAgentTopLevelReadiness`는 `report`를 받도록
시그니처만 조정.

- [ ] **Step 4: 전역 참조 0건 확인**

Run: `grep -rn "SetupRunResults\|System.setOut\|InitCommand().parse\|SetupCommand().parse" fixthis-cli/src/main`
Expected: 출력 없음.

- [ ] **Step 5: 전체 CLI 테스트 + 골든 통과**

Run: `./gradlew :fixthis-cli:test`
Expected: PASS (801 라인 회귀 가드 + 골든 동등).

- [ ] **Step 6: 죽은 코드 삭제 + Commit**

`SetupRunResults` object, `runWritePlansAtomic*`/`applyWritePlan*ForTest`,
`Pair<Triple<…>>` 시그니처, `captureStdoutWhenJson` 삭제.

```bash
git add -A fixthis-cli/src
git commit -m "refactor(cli): commands call SetupService directly; drop global state, argv re-parse, stdout hijack"
```

### Task A6: Track A 완료 게이트

- [ ] **Step 1: 매트릭스 + 린트**

Run: `./gradlew :fixthis-cli:test spotlessCheck detekt && git diff --check`
Expected: PASS. 실패 시 baseline 갱신 금지 — 원인 수정.

---

## Track B — SourceMatcher 책임 재배치

### Task B1: 가중치를 `SourceSignalKind` 열거 상수로 (EJ Item 34)

**Files:**
- Modify: `fixthis-compose-core/.../source/SourceIndex.kt:45-64`
- Modify: `fixthis-compose-core/.../source/SourceMatcher.kt:46, 508-530`
- Test: `fixthis-compose-core/.../source/SourceSignalKindWeightTest.kt`(신규)

- [ ] **Step 1: 실패 테스트 — enum이 가중치를 노출 + 직렬화 이름 불변**

```kotlin
@Test
fun `enum exposes baseMatchWeight matching legacy table`() {
    assertEquals(1.15, SourceSignalKind.STRICT_COMP_TEST_TAG.baseMatchWeight, 0.0)
    assertEquals(0.75, SourceSignalKind.LAYOUT_RENDERER.baseMatchWeight, 0.0)
    assertEquals(0.0, SourceSignalKind.SHARED_COMPONENT.baseMatchWeight, 0.0)
    assertEquals(0.35, SourceSignalKind.ARBITRARY_STRING_LITERAL.baseMatchWeight, 0.0)
}

@Test
fun `enum serializes by name despite added ctor arg`() {
    val s = SourceSignal(SourceSignalKind.UI_TEXT, "x")
    val round = Json.decodeFromString<SourceSignal>(Json.encodeToString(s))
    assertEquals(SourceSignalKind.UI_TEXT, round.kind)
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :fixthis-compose-core:test --tests "*SourceSignalKindWeightTest*"`
Expected: FAIL — `baseMatchWeight` 미존재.

- [ ] **Step 3: 구현 — 스펙의 enum 정의로 교체 + 매처 확장 삭제**

`SourceIndex.kt`의 `enum class SourceSignalKind`를 스펙 Track B-1의 인자형 정의로
교체. `SourceMatcher.kt`의 `private val SourceSignalKind.baseMatchWeight ... when {…}`
(`:508-530`)과 `LAYOUT_RENDERER_BASE_WEIGHT`(`:46`) 삭제. `bestSignalHit`의
`signal.kind.baseMatchWeight` 참조는 이제 enum 멤버를 가리킨다(코드 변경 불필요).

- [ ] **Step 4: 통과 확인**

Run: `./gradlew :fixthis-compose-core:test`
Expected: PASS — 기존 `SourceMatcher*Test` 포함 전부 녹색.

- [ ] **Step 5: Commit**

```bash
git add fixthis-compose-core/.../source/SourceIndex.kt fixthis-compose-core/.../source/SourceMatcher.kt \
        fixthis-compose-core/.../source/SourceSignalKindWeightTest.kt
git commit -m "refactor(core): move match weights into SourceSignalKind enum (EJ Item 34)"
```

### Task B2: weight-hit를 `SourceIndexEntry`로 이동 (오브젝트 6-1)

**Files:**
- Create: `fixthis-compose-core/.../source/SourceIndexEntryMatching.kt`
- Modify: `fixthis-compose-core/.../source/SourceMatcher.kt:393-545`

- [ ] **Step 1: 이동 — 순수 move refactor**

`SourceMatcher.kt`에서 다음을 잘라 `SourceIndexEntryMatching.kt`로 옮긴다(본문 글자
그대로, 가시성을 `internal`로):
`WeightHit`, `textLikeWeightHit`, `contentDescriptionWeightHit`, `testTagWeightHit`,
`conventionComposableWeightHit`, `roleWeightHit`, `activityWeightHit`,
`signalOrLegacyWeightHit`, `bestSignalHit`, `legacyWeight`, `matchesAny`,
`normalizedForMatch`. `SourceMatcher`는 `entry.textLikeWeightHit(term)` 등 호출만 유지.
`SourceMatcher` 내부에서 `normalizedForMatch`를 쓰는 곳(`:359` evidenceKey)은 같은
패키지 `internal` 확장을 그대로 참조.

```kotlin
// SourceIndexEntryMatching.kt
package io.github.beyondwin.fixthis.compose.core.source

internal data class WeightHit(val weight: Double, val signalKind: SourceSignalKind?, val viaLegacy: Boolean)

internal fun SourceIndexEntry.textLikeWeightHit(term: String): WeightHit = signalOrLegacyWeightHit(
    term = term,
    kinds = setOf(SourceSignalKind.UI_TEXT, SourceSignalKind.STRING_RESOURCE_RESOLVED,
        SourceSignalKind.STRING_RESOURCE, SourceSignalKind.ARBITRARY_STRING_LITERAL),
    legacyCandidates = text + stringResources + symbols + listOfNotNull(excerpt),
)
// ... 나머지 함수 동일 이동 ...
internal fun String.normalizedForMatch(): String = trim().lowercase().replace(Regex("\\s+"), " ")
```

- [ ] **Step 2: 컴파일 + 전체 테스트 통과 확인**

Run: `./gradlew :fixthis-compose-core:test`
Expected: PASS — 점수 결과 불변(코퍼스 정밀도 게이트 포함).

- [ ] **Step 3: diff가 순수 이동인지 셀프 리뷰**

`git diff`에서 추가/삭제 라인이 동일 본문의 이동임을 확인(로직 변형 0).

- [ ] **Step 4: Commit**

```bash
git add fixthis-compose-core/.../source/SourceIndexEntryMatching.kt fixthis-compose-core/.../source/SourceMatcher.kt
git commit -m "refactor(core): move weight-hit computation onto SourceIndexEntry (Tell-Don't-Ask)"
```

### Task B3: Track B 완료 게이트

- [ ] **Step 1**

Run: `./gradlew :fixthis-compose-core:test spotlessCheck detekt && git diff --check`
Expected: PASS.

---

## Track C — 세션 델리게이트 마무리

### Task C1: `compactionFailureSink` nullable 수용 → 단일 경로

**Files:**
- Modify: `fixthis-mcp/.../event/eventlog/SessionCompactionCoordinator.kt:34-40`
- Modify: `fixthis-mcp/.../store/FeedbackSessionStoreDelegate.kt:82-95`

- [ ] **Step 1: 코디네이터가 nullable sink를 받아 내부 폴백**

```kotlin
internal class SessionCompactionCoordinator(
    private val eventLogCompactorProvider: ((sessionId: String) -> EventLogCompactionTask)?,
    private val eventLogCompactionThreshold: Int,
    compactionFailureSink: ((sessionId: String, cause: Throwable) -> Unit)? = null,
    private val clock: () -> Long,
) {
    private val compactionFailureSink = compactionFailureSink ?: ::logCompactionFailure
    // ... 이후 본문은 this.compactionFailureSink 사용 ...
}
```

- [ ] **Step 2: 델리게이트 단일 경로로 통합**

`FeedbackSessionStoreDelegate.kt:82-95`의 if/else를 단일 생성으로 교체:

```kotlin
private val compactionCoordinator = SessionCompactionCoordinator(
    eventLogCompactorProvider = eventLogCompactorProvider,
    eventLogCompactionThreshold = eventLogCompactionThreshold,
    compactionFailureSink = compactionFailureSink,
    clock = clock,
)
```

- [ ] **Step 3: 테스트 통과 확인**

Run: `./gradlew :fixthis-mcp:test --tests "*Compaction*"`
Expected: PASS — sink 미주입 시 기본 WARN, 주입 시 커스텀 호출 불변.

- [ ] **Step 4: Commit**

```bash
git add fixthis-mcp/.../event/eventlog/SessionCompactionCoordinator.kt fixthis-mcp/.../store/FeedbackSessionStoreDelegate.kt
git commit -m "refactor(mcp): single-path compaction coordinator construction"
```

### Task C2: 익명 `Pair` → `EventBackedMutation<T>`

**Files:**
- Modify: `fixthis-mcp/.../store/FeedbackSessionStoreDelegate.kt:206-482`

- [ ] **Step 1: 명명 타입 도입 + prepare 콜백/래퍼 갱신**

델리게이트 파일 하단(혹은 같은 패키지 동반 파일)에 추가:

```kotlin
internal data class EventBackedMutation<T>(
    val payload: JsonObject,
    val apply: () -> T,
)
```

`withEventBackedMutation`/`withOptionalEventBackedMutation`의 `prepare` 타입을
`() -> EventBackedMutation<T>` / `() -> EventBackedMutation<T>?`로 바꾸고 분해부를
`val (payload, mutate) = prepare()` → `val m = prepare(); … m.payload … m.apply()`로
교체. 각 뮤테이션(`addScreen`, `deleteScreen`, `addItem`, … `deleteDraftItem`)의
`SessionEventPayloadFactory.… (…) to { … }` 반환을
`EventBackedMutation(SessionEventPayloadFactory.…(…)) { … }`로 교체.

- [ ] **Step 2: 전체 세션 테스트 통과 확인**

Run: `./gradlew :fixthis-mcp:test`
Expected: PASS — 이벤트 append 순서/fail-stop/compaction 타이밍 불변(~482 테스트).

- [ ] **Step 3: Commit**

```bash
git add fixthis-mcp/.../store/FeedbackSessionStoreDelegate.kt
git commit -m "refactor(mcp): name event-backed mutation type (drop anonymous Pair)"
```

### Task C3: Track C 완료 게이트

- [ ] **Step 1**

Run: `./gradlew :fixthis-mcp:test spotlessCheck detekt && git diff --check`
Expected: PASS.

---

## 최종 검증 (엄브렐라 완료)

- [ ] **Step 1: 전체 매트릭스**

Run: `./gradlew :fixthis-cli:test :fixthis-compose-core:test :fixthis-mcp:test spotlessCheck detekt`
Expected: PASS.

- [ ] **Step 2: 잔여 결합 0건 확인**

Run: `grep -rn "SetupRunResults\|System.setOut" fixthis-cli/src/main`
Expected: 출력 없음.

- [ ] **Step 3: `git diff --check` 통과**

---

## Self-Review 결과

- **Spec coverage:** Track A(전역상태/argv/stdout/쌍둥이) → A2–A6, Track B(enum/weight-hit) → B1–B2, Track C(Pair/생성자) → C1–C2 로 전 항목 매핑됨.
- **Placeholder scan:** 각 코드 스텝에 구체 시그니처·본문 제시. "기존 로직 유지" 표기는 글자 그대로 이동(move)을 의미하며 대상 라인 범위를 명시.
- **Type consistency:** `SetupReport`/`SetupRequest`/`InstallRequest`/`SetupService`/`EventBackedMutation<T>`/`WeightHit`/`baseMatchWeight` 명칭이 트랙 전체에서 일관.
- **확인 전제:** C1은 `SessionCompactionCoordinator`가 기본 sink를 갖는다는 점(코드 확인 완료)을 전제로 nullable 수용으로 전환.
