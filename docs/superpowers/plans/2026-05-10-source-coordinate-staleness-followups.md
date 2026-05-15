# Source Coordinate Staleness Detection — Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 부모 plan(`2026-05-10-source-coordinate-staleness-detection.md`)이 머지된 뒤 남은 7개 잔존 리스크 카테고리(C1–C7) 중 구현 가능한 부분을 처리한다. C3·C4는 트리거 조건 명시 후 deferred, C7은 코드 변경 없는 운영 체크리스트.

**Parent feature:** merge commit `eebc675` on `main` (branch `source-coordinate-staleness-detection-20260510-024649`).

**Architecture:** 변경은 모두 두 지점에 국지화된다.
1. `HostSourceFreshnessProbe.evaluate(...)` — projectRoot 오설정 감지 분기 추가 (C2).
2. `CompactHandoffRenderer` + `FeedbackQueueFormatter` — `stale = true`일 때 마커 한 줄 추가 (C6).

C1은 `scripts/fixthis-smoke.sh`에 `--check-staleness` 플래그를 더한다 (Bash, 코틀린 변경 없음). C5는 기존 테스트 파일 6개의 자문 사항을 일괄 정리한다.

**Tech Stack:** Kotlin 1.9 + kotlinx.serialization (compose-core, sidekick, MCP); JUnit 4 + kotlin.test (MCP 단위 테스트); Bash 4 (smoke script).

---

## Conventions

- **테스트 러너:**
  - MCP: `./gradlew :fixthis-mcp:test`
  - 전체 회귀:
    ```bash
    ./gradlew :fixthis-compose-core:test \
              :fixthis-cli:test \
              :fixthis-mcp:test \
              :fixthis-compose-sidekick:testDebugUnitTest \
              :fixthis-gradle-plugin:test
    ```
  - 현재 baseline: `tests=621 failures=0` (parent merge 직후 측정).
- **커밋 스타일**: 부모 plan과 동일 — 소문자 scope, imperative, 한 태스크 = 한 커밋.
- **호환성**: 모든 변경은 부가형. JSON 필드 이름 불변, 브릿지 프로토콜 미변경, 기존 markdown 라인 형태는 유지하고 마커만 append.
- **C6 stale 마커 포맷**: `⚠ stale: <reason>` — 후행 append. `false` / `null`인 경우 출력에 등장하지 않는다.

## File map

**Created:**

- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/SourceCandidateStaleRenderingTest.kt` (선택 — 기존 테스트 파일 확장으로 충분하면 생성하지 않음)

**Modified:**

- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbe.kt` — projectRoot 오설정 감지 (C2)
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbeTest.kt` — C2 단위 테스트 + C5 advisory 정리
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/SourceCandidateStalenessCheckerTest.kt` — C5: 1MB cap 테스트 + import/네이밍 정리
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt` — C5: prefix 오타 수정
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisToolsStatusTest.kt` — C5: 테스트 명 수정
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt` — stale 마커 (C6)
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt` — stale 마커 (C6)
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt` — C6 렌더 검증
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt` — C6 렌더 검증
- `scripts/fixthis-smoke.sh` — `--check-staleness` 플래그 (C1)
- `docs/troubleshooting.md` — C2 새 reason 문구를 진단 가이드에 추가
- `CHANGELOG.md` — 항목 추가

---

## Task 1 — C5: Reviewer advisory 일괄 정리

**Files:**

- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/SourceCandidateStalenessCheckerTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt:91`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbeTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisToolsStatusTest.kt`

**Goal:** 부모 plan 실행 중 Reviewer가 자문(advisory)으로만 남긴 6개 코드 품질 항목을 일괄 정리. 기능 변경 없음, 순수 테스트 정리.

- [ ] **Step 1: 1MB cap 테스트 추가** (`SourceCandidateStalenessCheckerTest.kt`)

  파일 끝 `}` 직전에 추가:

  ```kotlin
  @Test
  fun `marks candidate stale when host file exceeds 1 MB cap`() {
      val tmp = tempDir()
      val big = File(tmp, "Big.kt")
      // exactly 1 MB + 1 byte to trip the > MaxBytesToRead branch
      big.writeText("a".repeat(1_048_577))
      val index = SourceIndex(
          entries = listOf(SourceIndexEntry(file = "Big.kt", line = 1, excerpt = "a")),
      )
      val candidate = candidate(file = "Big.kt", line = 1)
      val checker = SourceCandidateStalenessChecker(tmp)

      val result = checker.annotate(listOf(candidate), index).single()

      assertEquals(true, result.stale)
      assertEquals("file too large to verify", result.staleReason)
  }

  @Test
  fun `does not flag size when host file is exactly at 1 MB cap`() {
      val tmp = tempDir()
      val cap = File(tmp, "Cap.kt")
      // exactly 1 MB — should NOT trip > MaxBytesToRead (strict greater-than)
      cap.writeText("a".repeat(1_048_576))
      val index = SourceIndex(
          entries = listOf(SourceIndexEntry(file = "Cap.kt", line = 1, excerpt = "a".repeat(1_048_576))),
      )
      val candidate = candidate(file = "Cap.kt", line = 1)
      val checker = SourceCandidateStalenessChecker(tmp)

      val result = checker.annotate(listOf(candidate), index).single()

      // file is fine size-wise; line 1 matches excerpt → fresh
      assertEquals(false, result.stale)
  }
  ```

- [ ] **Step 2: import / 헬퍼 컨벤션 정리** (`SourceCandidateStalenessCheckerTest.kt`)

  파일 상단의 import를 sibling tests(예: `FeedbackSessionPersistenceTest.kt`)에 맞춘다:

  ```kotlin
  // 기존
  import org.junit.Test

  // 변경 후
  import kotlin.test.Test
  ```

  파일 하단 `tempDir()` helper에 prefix 인자 추가:

  ```kotlin
  // 기존
  private fun tempDir(): File =
      kotlin.io.path.createTempDirectory(prefix = "fixthis-staleness-").toFile().also { it.deleteOnExit() }

  // 변경 후
  private fun tempDir(prefix: String = "fixthis-staleness-"): File =
      kotlin.io.path.createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }
  ```

  > 주의: `kotlin.test.Test`로 바꿀 때 `@Test` annotation은 동일하게 동작한다. JUnit 4 `@Test`는 모든 sibling tests에서 사용되지 않는 게 아니라면 그대로 둬도 됨 — sibling 테스트 파일들을 grep해서 다수파를 따른다. 다수가 `org.junit.Test`라면 변경하지 않는다.

- [ ] **Step 3: prefix 오타 수정** (`TargetEvidenceServiceTest.kt:91`)

  ```kotlin
  // 기존
  val tmpDir = kotlin.io.path.createTempDirectory(prefix = "fixthis-staleness-tes-").toFile()

  // 변경 후
  val tmpDir = kotlin.io.path.createTempDirectory(prefix = "fixthis-staleness-test-").toFile()
  ```

- [ ] **Step 4: inconclusive 테스트 단언 강화** (`HostSourceFreshnessProbeTest.kt`)

  ```kotlin
  // 기존
  assertTrue(result.reason!!.contains("install epoch"))

  // 변경 후 — spec §7.3가 정확한 문자열을 요구
  assertEquals("install epoch unavailable; older sidekick", result.reason)
  ```

- [ ] **Step 5: 테스트 명 정정** (`FixThisToolsStatusTest.kt`)

  ```kotlin
  // 기존
  fun `status omits installStale when sidekick lacks installEpochMillis`()

  // 변경 후 — spec §7.2가 installStale을 항상 emit하라고 명시함; 빠지지 않음
  fun `status reports installStale=false with inconclusive reason when sidekick lacks installEpochMillis`()
  ```

- [ ] **Step 6: 모듈 테스트 통과 확인**

  ```bash
  ./gradlew :fixthis-mcp:test
  ```

  Expected: 기존 354(또는 그 이상) 테스트 + 1MB cap 테스트 2개 추가 = 356+ tests, 0 failures.

- [ ] **Step 7: 커밋**

  ```bash
  git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/SourceCandidateStalenessCheckerTest.kt \
          fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt \
          fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbeTest.kt \
          fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisToolsStatusTest.kt
  git commit -m "test(mcp): clean up staleness reviewer advisories"
  ```

---

## Task 2 — C2: projectRoot 오설정 감지

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbe.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbeTest.kt`
- Modify: `docs/troubleshooting.md`

**Goal:** 모든 인덱스 등재 파일이 호스트에 존재하지 않을 때 (zero-of-N) projectRoot 오설정으로 추정되는 distinct inconclusive 결과를 반환. 정상 stale 시그널을 가짜 misconfig로 오인하지 않도록 임계는 *완전 부재* 0%.

- [ ] **Step 1: 실패하는 테스트 작성**

  `HostSourceFreshnessProbeTest.kt`에 추가:

  ```kotlin
  @Test
  fun `flags possible projectRoot misconfiguration when zero indexed files exist on host`() {
      val tmp = tempDir()
      // tmp는 비어있음 — 인덱스가 가리키는 파일은 하나도 존재하지 않는다.
      val installed = 1_700_000_000_000L
      val index = SourceIndex(entries = listOf(
          SourceIndexEntry(file = "missing/A.kt", line = 1, excerpt = "a"),
          SourceIndexEntry(file = "missing/B.kt", line = 1, excerpt = "b"),
          SourceIndexEntry(file = "missing/C.kt", line = 1, excerpt = "c"),
      ))
      val probe = HostSourceFreshnessProbe(tmp)

      val result = probe.evaluate(index, installEpochMillis = installed)

      assertFalse(result.installStale)
      assertEquals(0, result.newerFileCount)
      assertEquals(3, result.totalIndexedFiles)
      assertTrue(
          result.reason!!.startsWith("projectRoot may be misconfigured"),
          "got: ${result.reason}",
      )
  }

  @Test
  fun `does not flag misconfiguration when at least one indexed file exists`() {
      val tmp = tempDir()
      val installed = 1_700_000_000_000L
      // 한 파일만 존재 — 부분 dirty라 misconfig 아님
      val one = File(tmp, "Exists.kt").also { it.writeText("a") }
      one.setLastModified(installed - 60_000)
      val index = SourceIndex(entries = listOf(
          SourceIndexEntry(file = "Exists.kt", line = 1, excerpt = "a"),
          SourceIndexEntry(file = "Missing.kt", line = 1, excerpt = "b"),
      ))
      val probe = HostSourceFreshnessProbe(tmp)

      val result = probe.evaluate(index, installEpochMillis = installed)

      assertFalse(result.installStale)
      // 기존 코드 경로를 탔으므로 reason은 misconfig 메시지가 아니어야 한다 (신선한 상태이므로 null)
      assertFalse(result.reason?.startsWith("projectRoot may be misconfigured") == true)
  }
  ```

- [ ] **Step 2: 테스트 실패 확인**

  ```bash
  ./gradlew :fixthis-mcp:test --tests HostSourceFreshnessProbeTest
  ```

  Expected: `flags possible projectRoot misconfiguration` 단언 실패.

- [ ] **Step 3: probe에 가드 추가**

  Edit `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbe.kt`. `evaluate(...)` 본문에서 `installEpochMillis == null` 분기 다음, `canonicalRoot`/`files` 계산 다음에:

  ```kotlin
  fun evaluate(sourceIndex: SourceIndex, installEpochMillis: Long?): HostSourceFreshnessResult {
      if (installEpochMillis == null) {
          return HostSourceFreshnessResult(
              installStale = false,
              newerFileCount = 0,
              totalIndexedFiles = sourceIndex.entries.map { it.file }.distinct().size,
              installedAtEpochMillis = null,
              sampleNewerFiles = emptyList(),
              reason = "install epoch unavailable; older sidekick",
          )
      }
      val canonicalRoot = projectRoot.canonicalFile
      val files = sourceIndex.entries.map { it.file }.distinct()

      // C2 — projectRoot 오설정 가드
      if (files.isNotEmpty()) {
          val existsCount = files.count { relative ->
              val resolved = runCatching { File(canonicalRoot, relative).canonicalFile }.getOrNull()
              resolved != null &&
                  resolved.path.startsWith(canonicalRoot.path + File.separator) &&
                  resolved.isFile
          }
          if (existsCount == 0) {
              return HostSourceFreshnessResult(
                  installStale = false,
                  newerFileCount = 0,
                  totalIndexedFiles = files.size,
                  installedAtEpochMillis = installEpochMillis,
                  sampleNewerFiles = emptyList(),
                  reason = "projectRoot may be misconfigured: 0 of ${files.size} indexed files exist on host",
              )
          }
      }

      val newer = files.mapNotNull { relative ->
          // ... 기존 코드 그대로 ...
      }
      // 기존 stale/non-stale 분기 그대로
  }
  ```

  **주의:** 가드는 stat을 두 번 한다 (existsCount + 그 다음 mapNotNull). 정상 경로에서 비용이 두 배지만, real project size에서 무시 가능. 코드 가독성 우선.

- [ ] **Step 4: 테스트 통과 확인**

  ```bash
  ./gradlew :fixthis-mcp:test --tests HostSourceFreshnessProbeTest
  ```

  Expected: 새 misconfig 테스트 + partial-dirty 테스트 PASS, 기존 4개도 통과.

- [ ] **Step 5: troubleshooting 가이드 갱신**

  `docs/troubleshooting.md`의 "Source coordinates point to old code" 섹션 끝에 한 단락 추가:

  ```markdown
  If `fixthis_status` reports `installStaleReason: "projectRoot may be misconfigured: 0 of <N> indexed files exist on host"`, the MCP CLI was likely launched from the wrong directory. Re-run from the project root (the directory containing `settings.gradle.kts`).
  ```

- [ ] **Step 6: 모듈 회귀**

  ```bash
  ./gradlew :fixthis-mcp:test
  ```

  Expected: ALL pass.

- [ ] **Step 7: 커밋**

  ```bash
  git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbe.kt \
          fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbeTest.kt \
          docs/troubleshooting.md
  git commit -m "mcp: detect projectRoot misconfiguration in freshness probe"
  ```

---

## Task 3 — C6: handoff Markdown에 stale 마커 추가

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt:110`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt`

**Goal:** 에이전트가 markdown handoff만 보고도 stale 후보를 인지할 수 있도록, `stale = true`인 후보에 `⚠ stale: <reason>` 마커를 추가한다. `false` / `null`은 출력 변경 없음.

- [ ] **Step 1: 실패하는 테스트 추가** (`CompactHandoffRendererTest.kt`)

  파일에 새 `@Test` 추가:

  ```kotlin
  @Test
  fun renderEmitsStaleMarkerOnRank1WhenCandidateIsStale() {
      val staleCandidate = SourceCandidate(
          file = "sample/Foo.kt",
          line = 42,
          score = 0.9,
          confidence = SelectionConfidence.HIGH,
          stale = true,
          staleReason = "excerpt mismatch",
      )
      val session = sessionWith(candidate = staleCandidate)

      val md = CompactHandoffRenderer.render(session)

      assertTrue(md.contains("⚠ stale: excerpt mismatch"), "expected stale marker, got:\n$md")
  }

  @Test
  fun renderOmitsStaleMarkerWhenCandidateIsFresh() {
      val freshCandidate = SourceCandidate(
          file = "sample/Foo.kt",
          line = 42,
          score = 0.9,
          confidence = SelectionConfidence.HIGH,
          stale = false,
          staleReason = null,
      )
      val session = sessionWith(candidate = freshCandidate)

      val md = CompactHandoffRenderer.render(session)

      assertTrue(!md.contains("⚠ stale"), "expected no stale marker, got:\n$md")
  }

  @Test
  fun renderOmitsStaleMarkerWhenCandidateIsUnverifiable() {
      val nullCandidate = SourceCandidate(
          file = "sample/Strings.xml",
          line = null,
          score = 0.5,
          confidence = SelectionConfidence.MEDIUM,
          stale = null,
          staleReason = null,
      )
      val session = sessionWith(candidate = nullCandidate)

      val md = CompactHandoffRenderer.render(session)

      assertTrue(!md.contains("⚠ stale"), "expected no stale marker for null stale, got:\n$md")
  }

  // 헬퍼 — 기존 테스트의 sessionDto 빌더가 있으면 그걸 재사용. 없다면:
  private fun sessionWith(candidate: SourceCandidate): SessionDto {
      // 기존 sessionDto 패턴을 따라 작성. 핵심: AnnotationDto.sourceCandidates에 위 candidate가 들어가도록.
      // ... (기존 테스트 fixture 헬퍼 패턴 차용) ...
  }
  ```

  **Important:** `sessionWith(candidate)` 헬퍼는 기존 테스트의 `SessionDto`/`AnnotationDto` 빌더 패턴을 그대로 따른다. `sourceCandidates = listOf(candidate)`만 다르게.

- [ ] **Step 2: 테스트 실패 확인**

  ```bash
  ./gradlew :fixthis-mcp:test --tests CompactHandoffRendererTest
  ```

  Expected: 새 stale 마커 테스트 단언 실패 (`⚠ stale` 부재).

- [ ] **Step 3: `CompactHandoffRenderer.formatCandidateLine`에 마커 추가**

  Edit `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt:169`. 현재 `formatCandidateLine` 함수가 candidate 라인을 만든다. 끝부분에 stale 마커를 append:

  ```kotlin
  private fun formatCandidateLine(
      candidate: SourceCandidate,
      rank: Int,
      computedMargin: Double?,
      sourceRoot: String?,
  ): String {
      val base = /* 기존 구성: file:line, confidence, why=tokens, etc. */
      // … existing logic …
      return if (candidate.stale == true) {
          val reason = candidate.staleReason ?: "unspecified"
          "$base ⚠ stale: $reason"
      } else {
          base
      }
  }
  ```

  **호환성 주의:** `formatCandidateLine`의 기존 반환값에 단순히 suffix를 붙이는 변경. 기존 substring 단언은 그대로 통과한다.

- [ ] **Step 4: `FeedbackQueueFormatter`에도 동일 마커 추가**

  Edit `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt:110`. 현재:

  ```kotlin
  appendLine("${index + 1}. `${candidate.fileWithLine()}` ${candidate.markdownConfidence(target)} confidence")
  ```

  변경:

  ```kotlin
  val staleSuffix = if (candidate.stale == true) {
      " ⚠ stale: ${candidate.staleReason ?: "unspecified"}"
  } else ""
  appendLine("${index + 1}. `${candidate.fileWithLine()}` ${candidate.markdownConfidence(target)} confidence$staleSuffix")
  ```

- [ ] **Step 5: `FeedbackQueueFormatterTest.kt`에 동일한 3-state 테스트 추가**

  Step 1과 동일 구조. 기존 fixture 헬퍼 활용. 검증 대상은 `FeedbackQueueFormatter.format(...)` (정확한 함수명은 파일 확인 후 사용).

- [ ] **Step 6: 모듈 테스트**

  ```bash
  ./gradlew :fixthis-mcp:test
  ```

  Expected: 새 stale 마커 테스트 6개 (각 formatter별 3개) PASS, 기존 모두 PASS.

- [ ] **Step 7: 커밋**

  ```bash
  git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRenderer.kt \
          fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt \
          fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt \
          fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterTest.kt
  git commit -m "mcp: render stale candidates with ⚠ marker in handoff Markdown"
  ```

---

## Task 4 — C1: 실기기 E2E smoke 플래그

**Files:**

- Modify: `scripts/fixthis-smoke.sh`
- Modify: `docs/troubleshooting.md` (한 줄 — `--check-staleness` 플래그 언급)

**Goal:** `scripts/fixthis-smoke.sh --check-staleness`가 실 디바이스에서 staleness round-trip을 수동 검증한다. 기존 smoke flow와 호환.

- [ ] **Step 1: 기존 smoke 스크립트 구조 파악**

  ```bash
  head -80 scripts/fixthis-smoke.sh
  grep -n "case\|while\|--" scripts/fixthis-smoke.sh | head -20
  ```

  현재 인자 처리 패턴(예: `getopts` vs 수동 case)을 확인하고 그 방식을 따른다.

- [ ] **Step 2: `--check-staleness` 플래그 핸들러 추가**

  스크립트 상단 인자 파싱 블록에 추가:

  ```bash
  CHECK_STALENESS=0
  # ... 기존 인자 case 블록에 추가 ...
      --check-staleness)
          CHECK_STALENESS=1
          shift
          ;;
  ```

  스크립트 끝 (정상 path가 끝난 직후, exit 전)에 새 함수 호출:

  ```bash
  if [[ "$CHECK_STALENESS" == "1" ]]; then
      run_staleness_round_trip
  fi
  ```

- [ ] **Step 3: `run_staleness_round_trip` 함수 정의**

  스크립트 어딘가 (다른 함수 정의 옆)에 추가:

  ```bash
  run_staleness_round_trip() {
      echo "[staleness] round-trip check starting"

      # 0) 깨끗한 baseline 보장 — 빌드 + 설치 + cold launch
      ./gradlew :app:installDebug
      adb shell am force-stop "${PACKAGE:-io.github.beyondwin.fixthis.sample}"
      sleep 1
      adb shell am start -n "${PACKAGE:-io.github.beyondwin.fixthis.sample}/.MainActivity" || true
      sleep 2

      local baseline_status
      baseline_status=$(call_mcp_status)
      assert_json_field "$baseline_status" '.installStale' 'false' \
          "[staleness] baseline expected installStale=false"

      # 1) 추적 파일 mtime을 미래로 밀어 stale 유발
      local touched_file="sample/src/main/kotlin/io/github/beyondwin/fixthis/sample/MainActivity.kt"
      local original_mtime
      original_mtime=$(stat -f "%m" "$touched_file")
      trap "touch -t $(date -r "$original_mtime" '+%Y%m%d%H%M.%S') '$touched_file'" EXIT

      touch -A 010000 "$touched_file"  # +1 hour
      sleep 1

      local stale_status
      stale_status=$(call_mcp_status)
      assert_json_field "$stale_status" '.installStale' 'true' \
          "[staleness] after mtime bump expected installStale=true"
      assert_json_contains "$stale_status" '.newerSourceFiles' "MainActivity.kt" \
          "[staleness] newerSourceFiles should list touched file"

      # 2) 재설치로 복구
      ./gradlew :app:installDebug
      adb shell am force-stop "${PACKAGE:-io.github.beyondwin.fixthis.sample}"
      sleep 1
      adb shell am start -n "${PACKAGE:-io.github.beyondwin.fixthis.sample}/.MainActivity" || true
      sleep 2

      local restored_status
      restored_status=$(call_mcp_status)
      assert_json_field "$restored_status" '.installStale' 'false' \
          "[staleness] after reinstall expected installStale=false"

      echo "[staleness] round-trip OK"
  }

  call_mcp_status() {
      ./gradlew :fixthis-cli:run --quiet --args="status --json --package ${PACKAGE:-io.github.beyondwin.fixthis.sample}" 2>/dev/null
  }

  assert_json_field() {
      local json="$1" path="$2" expected="$3" msg="$4"
      local actual
      actual=$(echo "$json" | jq -r "$path")
      if [[ "$actual" != "$expected" ]]; then
          echo "FAIL: $msg (got: $actual)"
          exit 1
      fi
  }

  assert_json_contains() {
      local json="$1" path="$2" needle="$3" msg="$4"
      if ! echo "$json" | jq -r "$path[]" | grep -q "$needle"; then
          echo "FAIL: $msg"
          exit 1
      fi
  }
  ```

  **주의:**
  - `call_mcp_status`는 CLI가 `status --json` 같은 모드를 지원해야 한다. 현재 CLI에 그런 모드가 없으면, 대신 `bridge.status()` raw output을 파싱하는 방식으로 작성. **Implementer가 이 단계에서 `:fixthis-cli`의 실제 인터페이스 확인 후 적절한 호출 방식 선택**.
  - `touch -A` 옵션은 macOS BSD `touch`. Linux GNU `touch`에서는 `touch -d "+1 hour"` 형태 필요. 스크립트가 macOS-only인지 확인.
  - `jq`가 필요하다 — Implementer가 스크립트 시작에 `command -v jq >/dev/null || { echo "[staleness] requires jq"; exit 2; }` 가드 추가.

- [ ] **Step 4: 수동 실행 + 동작 확인**

  연결된 디바이스에서:

  ```bash
  ./scripts/fixthis-smoke.sh --check-staleness
  ```

  Expected: `[staleness] round-trip OK` 메시지로 정상 종료. 실패 시 어느 phase에서 실패했는지 명시.

- [ ] **Step 5: troubleshooting 가이드에 한 줄 추가**

  `docs/troubleshooting.md` "Source coordinates point to old code" 섹션 마지막에:

  ```markdown
  To verify the round-trip end-to-end on a connected device, run:

  \`\`\`bash
  ./scripts/fixthis-smoke.sh --check-staleness
  \`\`\`
  ```

- [ ] **Step 6: 커밋**

  ```bash
  git add scripts/fixthis-smoke.sh docs/troubleshooting.md
  git commit -m "scripts: add --check-staleness round-trip flag to fixthis-smoke"
  ```

---

## Task 5 — CHANGELOG + 전체 회귀

**Files:**

- Modify: `CHANGELOG.md`

**Goal:** Tasks 1–4 변경사항을 한 항목으로 묶어 unreleased에 기록하고, 5모듈 회귀로 마무리.

- [ ] **Step 1: CHANGELOG 항목 추가**

  최상단 unreleased에:

  ```markdown
  - Improved: `fixthis_status` distinguishes a likely `projectRoot` misconfiguration ("0 of N indexed files exist on host") from genuine staleness. Compact and queue handoff markdown now mark stale source candidates with `⚠ stale: <reason>` so AI agents notice without inspecting raw JSON. `scripts/fixthis-smoke.sh --check-staleness` runs an end-to-end round-trip against a connected device.
  ```

- [ ] **Step 2: 전체 회귀**

  ```bash
  ./gradlew :fixthis-compose-core:test \
            :fixthis-cli:test \
            :fixthis-mcp:test \
            :fixthis-compose-sidekick:testDebugUnitTest \
            :fixthis-gradle-plugin:test
  ```

  Expected: 전부 PASS. 새 테스트로 baseline 621 → ~628+ (1MB cap 2개 + projectRoot misconfig 2개 + stale rendering 6개 = 약 10개 신규).

- [ ] **Step 3: 커밋**

  ```bash
  git add CHANGELOG.md
  git commit -m "docs: changelog for staleness detection follow-ups"
  ```

---

## Operational tasks (코드 변경 없음 — C7)

다음은 사용자가 직접 실행. 한 번뿐. 셸 명령만 있고 결과 검증 한 줄.

- [ ] **워크트리 정리** — 부모 plan 머지 완료, 워크트리 더 이상 불필요:

  ```bash
  # /Users/kws/source/android/FixThis 에서:
  git worktree remove ../worktrees/source-coordinate-staleness-detection-20260510-024649
  git branch -d source-coordinate-staleness-detection-20260510-024649
  ```

  **검증:** `git worktree list`에 더 이상 등장하지 않는다.

- [ ] **Stash hygiene** — 부모 plan 시작 시 stash해둔 launcher PNG 수정과 main의 `262e02a chore(sample): refresh launcher icon...`를 비교:

  ```bash
  git stash show -p stash@{0}
  git show 262e02a -- 'sample/src/main/res/mipmap-*/ic_launcher.png'
  ```

  - 동일하다면: `git stash drop stash@{0}`.
  - 다르다면: stash 내용을 별도 브랜치로 살린다 (`git stash branch launcher-rework stash@{0}`).

- [ ] **인프라(서브에이전트 `Not logged in`) 회피** — 코드/플랜 외 항목. 동일 증상 재발 시 `claude /login` 후 재시도. 지금은 액션 없음.

---

## Out of scope (의도적으로 미포함)

- **C3 stat I/O 최적화**: trigger 발생 전까지 보류 (parent §15, this spec §9).
- **C4 clock-skew tolerance**: 사용자 보고 후 도입 (parent §15, this spec §9).
- **Build-time content digest** in `fixthis-build-info.json`: 별도 plan.
- **CLI subcommand `fixthis stale`**: 별도 plan.
- **Auto-reinstall opt-in**: 별도 plan.
- **Index-aware new-file detection**: 별도 plan.

## Risk register

- **R1: Smoke 스크립트가 디바이스 상태를 더럽힘** — `trap`으로 mtime 복구, `am force-stop`으로 앱 정리. 그래도 디바이스가 mid-script 분리되면 mtime이 미래 시각으로 남을 수 있음. mitigated by: `trap` + 사용자에게 "디바이스 연결 끊지 말 것" 안내.
- **R2: `⚠` 문자가 일부 터미널에서 깨짐** — UTF-8 미지원 환경. 영향: 시각적 노이즈만, 동작 무관. 핵심 정보 `stale: <reason>`은 ASCII로 그대로 보임.
- **R3: C2 가드의 false positive (정상 partial-dirty를 misconfig로 오인)** — 가드는 *모든* 파일이 부재할 때만 트리거. 정상 stale (일부 파일 mtime 변경)은 한 파일이라도 존재하므로 영향 없음. parent §10 row "indexed file present but with a fresh mtime"과 직교.
- **R4: Smoke 스크립트의 macOS/Linux 호환성** — `touch -A`는 BSD only. Linux는 `touch -d`. Implementer가 `uname -s` 분기 또는 `python -c` fallback로 처리.

## Self-review checklist (after implementation)

각 태스크 머지 시:

1. C2 가드는 `installEpochMillis == null` 분기 *다음*에 위치. 순서 바뀌면 inconclusive 우선순위가 깨짐.
2. C6 stale 마커는 `false`/`null`에 출력 변경 없음. 기존 golden 테스트 통과 확인.
3. C5 cleanup 후 `:fixthis-mcp:test` 카운트가 baseline 대비 ≥+2 (1MB cap 테스트 2개).
4. C1 smoke 스크립트는 디바이스 미연결 시 명확한 에러로 종료 (`adb devices` 가드).
5. CHANGELOG 항목은 한 줄, parent feature와 혼동되지 않게 "Improved:" 접두사 사용 (parent는 "Added:").
