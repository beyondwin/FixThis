# Source Coordinate Staleness Detection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI 에이전트가 옛 좌표를 그대로 수정하지 않도록, MCP가 돌려주는 모든 `SourceCandidate`의 발췌(`excerpt`)를 호스트의 실제 소스와 대조해 `stale` 플래그를 매기고, `fixthis_status` 응답에 디바이스 설치본보다 새로운 소스 파일 수를 요약한 `installStale` 힌트를 추가한다.

**Architecture:** 두 검사를 모두 호스트(`fixthis-mcp`) 측에 둬서 Gradle 플러그인은 손대지 않는다.

1. **per-candidate**: `TargetEvidenceService.sourceCandidatesFor()`가 만든 후보를 `SourceCandidateStalenessChecker`가 받아, 각 `(file, line)`을 `projectRoot` 기준으로 읽고 `SourceIndex`에 저장된 `excerpt`와 비교. 일치 → `stale = false`, 불일치 → `stale = true` + `staleReason`. 모델은 nullable 필드만 추가해 호환성 보존.
2. **global**: `BridgeStatus`에 사이드킥의 `PackageManager.getPackageInfo(pkg, 0).lastUpdateTime`을 `installEpochMillis: Long?`로 추가(Gradle 빌드 변경 0). 호스트의 `HostSourceFreshnessProbe`가 SourceIndex에 등재된 파일들의 mtime을 stat해서 `installEpochMillis`보다 새로운 파일 수를 카운트하고, `fixthis_status`가 `installStale`/`installStaleReason`/`installStaleHint`를 노출. 사이드킥이 옛 버전이라 `installEpochMillis`가 null이면 글로벌 힌트 자체를 생략.

**Tech Stack:** Kotlin 1.9 + kotlinx.serialization (compose-core, sidekick, MCP); JUnit 5 (MCP 단위 테스트); JUnit 4 + Robolectric (sidekick 단위 테스트).

---

## Conventions

- **테스트 러너:**
  - compose-core: `./gradlew :fixthis-compose-core:test`
  - sidekick (Robolectric): `./gradlew :fixthis-compose-sidekick:testDebugUnitTest`
  - MCP: `./gradlew :fixthis-mcp:test`
  - 전체 회귀(plan 마지막 회귀용):
    ```bash
    ./gradlew :fixthis-compose-core:test \
              :fixthis-cli:test \
              :fixthis-mcp:test \
              :fixthis-compose-sidekick:testDebugUnitTest \
              :fixthis-gradle-plugin:test
    ```
- **JSON 라운드트립**: 추가 필드는 `kotlinx.serialization` 기본값(`null`/`false`)으로 옛 페이로드를 안전하게 디코드해야 한다. 모든 모델 변경 태스크는 "옛 JSON 디코드" 케이스를 명시적으로 검증한다.
- **커밋 스타일**: 소문자 scope, imperative, 한 태스크 = 한 커밋. 본문은 선택.
- **Stale 의미 합의**: `stale = true`는 "이 좌표를 그대로 신뢰하지 말라"는 신호. `stale = null`은 "검증 불가(파일 부재 등)" — 거짓-안전을 의도한 ABSENT semantics. `stale = false`는 "발췌 일치, 비교적 신뢰 가능".

## File map

**Created:**

- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SourceCandidateStalenessChecker.kt`
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbe.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SourceCandidateStalenessCheckerTest.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbeTest.kt`
- `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeStatusInstallEpochTest.kt`

**Modified:**

- `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt` — `SourceCandidate`에 `stale`, `staleReason` 추가
- `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/model/ModelsTest.kt` — 직렬화 라운드트립 보강 (없으면 만들고, 있으면 추가 케이스만)
- `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt` — `BridgeStatus`에 `installEpochMillis` 추가 + `AndroidBridgeEnvironment.status()`에서 채움
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt` — checker 결합
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt` — stale 케이스 추가
- `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt` — `fixthis_status`에서 probe 호출
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisToolsTest.kt` — installStale 케이스 추가 (파일이 없으면 새로 만든다)
- `docs/mcp.md` — `stale`, `installStale` 필드 문서화
- `docs/troubleshooting.md` — "코드는 바꿨는데 결과가 옛 좌표" 가이드 한 섹션
- `CHANGELOG.md` — 항목 추가

---

## Task 1 — `SourceCandidate`에 `stale`/`staleReason` 추가 (compose-core)

**Files:**

- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt:97-110`
- Test: `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/model/SourceCandidateSerializationTest.kt` (create)

**Goal:** `SourceCandidate`에 nullable `stale: Boolean?`, `staleReason: String?` 필드를 추가하고, 옛 JSON(필드 없음)이 안전하게 디코드되는지 검증한다.

- [ ] **Step 1: 실패하는 테스트 작성**

  Create `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/model/SourceCandidateSerializationTest.kt`:

  ```kotlin
  package io.beyondwin.fixthis.compose.core.model

  import kotlinx.serialization.json.Json
  import kotlin.test.Test
  import kotlin.test.assertEquals
  import kotlin.test.assertNull
  import kotlin.test.assertTrue

  class SourceCandidateSerializationTest {
      private val json = Json { ignoreUnknownKeys = true }

      @Test
      fun `stale defaults to null when not set`() {
          val candidate = SourceCandidate(
              file = "Foo.kt",
              score = 0.5,
              confidence = SelectionConfidence.MEDIUM,
          )
          assertNull(candidate.stale)
          assertNull(candidate.staleReason)
      }

      @Test
      fun `legacy JSON without stale fields decodes with nulls`() {
          val legacy = """{"file":"Foo.kt","score":0.5,"confidence":"MEDIUM"}"""
          val decoded = json.decodeFromString(SourceCandidate.serializer(), legacy)
          assertEquals("Foo.kt", decoded.file)
          assertNull(decoded.stale)
          assertNull(decoded.staleReason)
      }

      @Test
      fun `JSON round-trip preserves stale fields`() {
          val original = SourceCandidate(
              file = "Foo.kt",
              line = 42,
              score = 0.9,
              confidence = SelectionConfidence.HIGH,
              stale = true,
              staleReason = "excerpt mismatch",
          )
          val text = json.encodeToString(SourceCandidate.serializer(), original)
          val decoded = json.decodeFromString(SourceCandidate.serializer(), text)
          assertEquals(original, decoded)
          assertTrue(decoded.stale == true)
      }
  }
  ```

- [ ] **Step 2: 테스트 실패 확인**

  Run: `./gradlew :fixthis-compose-core:test --tests SourceCandidateSerializationTest`

  Expected: 컴파일 실패 — `Unresolved reference: stale` (필드 미존재).

- [ ] **Step 3: `SourceCandidate`에 필드 추가**

  Edit `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt:97-110`:

  ```kotlin
  @Serializable
  data class SourceCandidate(
      val file: String,
      val line: Int? = null,
      val score: Double,
      val matchedTerms: List<String> = emptyList(),
      val matchReasons: List<String> = emptyList(),
      val confidence: SelectionConfidence,
      val ranking: Int? = null,
      val scoreMargin: Double? = null,
      val evidenceStrength: SourceEvidenceStrength? = null,
      val riskFlags: List<SourceCandidateRisk> = emptyList(),
      val caution: String? = null,
      val stale: Boolean? = null,
      val staleReason: String? = null,
  )
  ```

- [ ] **Step 4: 테스트 통과 확인**

  Run: `./gradlew :fixthis-compose-core:test --tests SourceCandidateSerializationTest`

  Expected: PASS, 3 tests.

- [ ] **Step 5: 모듈 회귀 확인**

  Run: `./gradlew :fixthis-compose-core:test`

  Expected: PASS. 기존 테스트가 새 nullable 필드 때문에 깨지지 않아야 한다.

- [ ] **Step 6: 커밋**

  ```bash
  git add fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/model/Models.kt \
          fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/model/SourceCandidateSerializationTest.kt
  git commit -m "core: add nullable stale fields to SourceCandidate"
  ```

---

## Task 2 — `SourceCandidateStalenessChecker` 도입 (MCP)

**Files:**

- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SourceCandidateStalenessChecker.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SourceCandidateStalenessCheckerTest.kt`

**Goal:** `(SourceIndex, projectRoot, candidates)`을 받아, 각 candidate의 `(file, line)`에 해당하는 라이브 소스 라인을 trim해서 `SourceIndex`의 `excerpt`(이미 trim 저장)와 비교한다. 다음 5가지 경우를 분리해 처리:

- 일치 → `stale = false`, `staleReason = null`
- 불일치 → `stale = true`, `staleReason = "excerpt mismatch"`
- 호스트에 파일이 없음 → `stale = true`, `staleReason = "file not found on host"`
- 라인 번호 범위 초과 → `stale = true`, `staleReason = "line out of range"`
- `entry.excerpt`가 비어있거나 `candidate.line`이 null(예: XML 리소스 entry) → 검증 불가, 기존 candidate 그대로 반환(`stale = null`)

호스트 파일 경로는 `projectRoot` 하위로 제한하고, 한 파일당 최대 1MB까지만 읽는다.

- [ ] **Step 1: 실패하는 테스트 작성**

  Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SourceCandidateStalenessCheckerTest.kt`:

  ```kotlin
  package io.beyondwin.fixthis.mcp.session

  import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
  import io.beyondwin.fixthis.compose.core.model.SourceCandidate
  import io.beyondwin.fixthis.compose.core.source.SourceIndex
  import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
  import org.junit.jupiter.api.Test
  import org.junit.jupiter.api.io.TempDir
  import java.nio.file.Path
  import kotlin.io.path.writeText
  import kotlin.test.assertEquals
  import kotlin.test.assertNull
  import kotlin.test.assertTrue

  class SourceCandidateStalenessCheckerTest {

      @Test
      fun `marks candidate as fresh when excerpt matches host source`(@TempDir tmp: Path) {
          val file = tmp.resolve("Sample.kt")
          file.writeText("package x\n\nfun greet() = \"hi\"\n")
          val index = SourceIndex(
              entries = listOf(SourceIndexEntry(file = "Sample.kt", line = 3, excerpt = "fun greet() = \"hi\"")),
          )
          val candidate = candidate(file = "Sample.kt", line = 3)
          val checker = SourceCandidateStalenessChecker(tmp.toFile())

          val result = checker.annotate(listOf(candidate), index).single()

          assertEquals(false, result.stale)
          assertNull(result.staleReason)
      }

      @Test
      fun `marks candidate stale when excerpt does not match`(@TempDir tmp: Path) {
          val file = tmp.resolve("Sample.kt")
          file.writeText("package x\n\nfun greet() = \"bye\"\n")
          val index = SourceIndex(
              entries = listOf(SourceIndexEntry(file = "Sample.kt", line = 3, excerpt = "fun greet() = \"hi\"")),
          )
          val candidate = candidate(file = "Sample.kt", line = 3)
          val checker = SourceCandidateStalenessChecker(tmp.toFile())

          val result = checker.annotate(listOf(candidate), index).single()

          assertEquals(true, result.stale)
          assertEquals("excerpt mismatch", result.staleReason)
      }

      @Test
      fun `marks candidate stale when host file is missing`(@TempDir tmp: Path) {
          val index = SourceIndex(
              entries = listOf(SourceIndexEntry(file = "Missing.kt", line = 1, excerpt = "fun foo() {}")),
          )
          val candidate = candidate(file = "Missing.kt", line = 1)
          val checker = SourceCandidateStalenessChecker(tmp.toFile())

          val result = checker.annotate(listOf(candidate), index).single()

          assertEquals(true, result.stale)
          assertEquals("file not found on host", result.staleReason)
      }

      @Test
      fun `marks candidate stale when line is out of range`(@TempDir tmp: Path) {
          val file = tmp.resolve("Tiny.kt")
          file.writeText("package x\n")
          val index = SourceIndex(
              entries = listOf(SourceIndexEntry(file = "Tiny.kt", line = 99, excerpt = "fun foo() {}")),
          )
          val candidate = candidate(file = "Tiny.kt", line = 99)
          val checker = SourceCandidateStalenessChecker(tmp.toFile())

          val result = checker.annotate(listOf(candidate), index).single()

          assertEquals(true, result.stale)
          assertEquals("line out of range", result.staleReason)
      }

      @Test
      fun `leaves stale null when entry has no excerpt or candidate has no line`(@TempDir tmp: Path) {
          val file = tmp.resolve("Strings.xml")
          file.writeText("<resources><string name=\"x\">v</string></resources>")
          val index = SourceIndex(
              entries = listOf(SourceIndexEntry(file = "Strings.xml", line = null, excerpt = null)),
          )
          val candidate = candidate(file = "Strings.xml", line = null)
          val checker = SourceCandidateStalenessChecker(tmp.toFile())

          val result = checker.annotate(listOf(candidate), index).single()

          assertNull(result.stale)
          assertNull(result.staleReason)
      }

      @Test
      fun `rejects paths that escape project root`(@TempDir tmp: Path) {
          val index = SourceIndex(
              entries = listOf(SourceIndexEntry(file = "../escape.kt", line = 1, excerpt = "boom")),
          )
          val candidate = candidate(file = "../escape.kt", line = 1)
          val checker = SourceCandidateStalenessChecker(tmp.toFile())

          val result = checker.annotate(listOf(candidate), index).single()

          assertEquals(true, result.stale)
          assertTrue(result.staleReason!!.startsWith("path escapes project root"))
      }

      private fun candidate(file: String, line: Int?): SourceCandidate =
          SourceCandidate(
              file = file,
              line = line,
              score = 1.0,
              confidence = SelectionConfidence.HIGH,
          )
  }
  ```

- [ ] **Step 2: 테스트 실패 확인**

  Run: `./gradlew :fixthis-mcp:test --tests SourceCandidateStalenessCheckerTest`

  Expected: 컴파일 실패 — `SourceCandidateStalenessChecker` 미존재.

- [ ] **Step 3: 구현**

  Create `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SourceCandidateStalenessChecker.kt`:

  ```kotlin
  package io.beyondwin.fixthis.mcp.session

  import io.beyondwin.fixthis.compose.core.model.SourceCandidate
  import io.beyondwin.fixthis.compose.core.source.SourceIndex
  import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
  import java.io.File

  /**
   * Re-reads the host's source files at each candidate's `(file, line)` and compares
   * the live trimmed line against the source-index excerpt. Marks mismatches as stale
   * so AI agents do not edit coordinates that no longer match the live code.
   */
  class SourceCandidateStalenessChecker(private val projectRoot: File) {

      fun annotate(candidates: List<SourceCandidate>, sourceIndex: SourceIndex): List<SourceCandidate> {
          if (candidates.isEmpty()) return candidates
          val byKey = sourceIndex.entries.associateBy { entryKey(it.file, it.line) }
          val canonicalRoot = projectRoot.canonicalFile
          return candidates.map { candidate ->
              val entry = byKey[entryKey(candidate.file, candidate.line)]
              annotate(candidate, entry, canonicalRoot)
          }
      }

      private fun annotate(candidate: SourceCandidate, entry: SourceIndexEntry?, canonicalRoot: File): SourceCandidate {
          val expected = entry?.excerpt?.takeIf { it.isNotBlank() } ?: return candidate
          val line = candidate.line ?: return candidate

          val resolved = runCatching { File(canonicalRoot, candidate.file).canonicalFile }.getOrNull()
              ?: return candidate.flagStale("file not found on host")
          if (!resolved.path.startsWith(canonicalRoot.path + File.separator) && resolved != canonicalRoot) {
              return candidate.flagStale("path escapes project root: ${candidate.file}")
          }
          if (!resolved.isFile) return candidate.flagStale("file not found on host")
          if (resolved.length() > MaxBytesToRead) return candidate.flagStale("file too large to verify")

          val live = readLine(resolved, line) ?: return candidate.flagStale("line out of range")
          return if (live.trim() == expected.trim()) {
              candidate.copy(stale = false, staleReason = null)
          } else {
              candidate.flagStale("excerpt mismatch")
          }
      }

      private fun readLine(file: File, lineNumber: Int): String? {
          if (lineNumber < 1) return null
          file.bufferedReader().use { reader ->
              var current = 0
              while (true) {
                  val l = reader.readLine() ?: return null
                  current++
                  if (current == lineNumber) return l
              }
          }
      }

      private fun SourceCandidate.flagStale(reason: String): SourceCandidate =
          copy(stale = true, staleReason = reason)

      private fun entryKey(file: String, line: Int?): String = "$file::${line ?: -1}"

      private companion object {
          const val MaxBytesToRead: Long = 1_048_576L
      }
  }
  ```

- [ ] **Step 4: 테스트 통과 확인**

  Run: `./gradlew :fixthis-mcp:test --tests SourceCandidateStalenessCheckerTest`

  Expected: PASS, 6 tests.

- [ ] **Step 5: 커밋**

  ```bash
  git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SourceCandidateStalenessChecker.kt \
          fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SourceCandidateStalenessCheckerTest.kt
  git commit -m "mcp: add SourceCandidateStalenessChecker"
  ```

---

## Task 3 — `TargetEvidenceService.sourceCandidatesFor`에 staleness 결합

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt:20-23,264-272`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt` (생성자에 `projectRoot` 전달; 이미 있을 가능성 큼)

**Goal:** `TargetEvidenceService`가 candidates를 만든 직후 staleness checker를 통과시키도록 한다. 생성자에 `projectRoot: File`를 받고, 기본값은 `File(".").canonicalFile`로 둔다.

- [ ] **Step 1: 실패하는 테스트 추가**

  Open `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt`. 기존 테스트 클래스 끝에 다음 케이스를 추가한다 (이미 존재하는 import는 중복 없이):

  ```kotlin
  @Test
  fun `buildFeedbackItem marks stale when host source diverges from index excerpt`(@TempDir tmp: Path) {
      val src = tmp.resolve("sample/src/main/kotlin/com/example/Foo.kt")
      java.nio.file.Files.createDirectories(src.parent)
      src.writeText("package com.example\n\n@Composable\nfun NewName() {}\n")
      val sourceIndex = SourceIndex(entries = listOf(
          SourceIndexEntry(
              file = "sample/src/main/kotlin/com/example/Foo.kt",
              line = 4,
              excerpt = "fun OldName() {}",
              symbols = listOf("OldName"),
          ),
      ))
      val service = TargetEvidenceService(
          bridge = FakeFixThisBridge(),
          sourceIndexRegistry = SourceIndexRegistry(),
          projectRoot = tmp.toFile(),
      )
      val screen = stubSnapshotWithComposable(symbol = "OldName")  // 기존 헬퍼

      val item = service.buildFeedbackItem(
          screen = screen,
          sourceIndex = sourceIndex,
          targetType = FeedbackTargetType.NODE,
          bounds = screen.singleNodeBounds(),
          nodeUid = screen.singleNodeUid(),
          comment = "needs rename",
          allowBlankComment = false,
          writtenStatus = AnnotationStatusDto.OPEN,
      )

      val candidate = item.sourceCandidates.single { it.file.endsWith("Foo.kt") }
      assertEquals(true, candidate.stale)
      assertEquals("excerpt mismatch", candidate.staleReason)
  }
  ```

  > 만약 `stubSnapshotWithComposable`/`singleNodeBounds`/`singleNodeUid` 헬퍼가 기존 테스트 파일에 없다면, `TargetEvidenceServiceTest.kt` 파일 안의 기존 헬퍼들을 그대로 활용하거나(파일 내부에 이미 fixture builder가 있음) 한 번만 작성한다. 헬퍼 작성을 새로 한다면 같은 PR 안에서 테스트 파일 상단에 private 함수로 둔다.

- [ ] **Step 2: 테스트 실패 확인**

  Run: `./gradlew :fixthis-mcp:test --tests TargetEvidenceServiceTest.buildFeedbackItem*stale*`

  Expected: 컴파일 실패 (`projectRoot` 인자 미존재) 또는 단언 실패(`stale`이 null).

- [ ] **Step 3: `TargetEvidenceService` 시그니처 + 결합 구현**

  Edit `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt:20-23`:

  ```kotlin
  class TargetEvidenceService(
      private val bridge: FixThisBridge,
      private val sourceIndexRegistry: SourceIndexRegistry,
      private val projectRoot: File = File(".").canonicalFile,
      private val stalenessChecker: SourceCandidateStalenessChecker = SourceCandidateStalenessChecker(projectRoot),
  ) {
  ```

  파일 상단 import 블록에 추가:

  ```kotlin
  import java.io.File
  ```

  Edit `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt:264-272`:

  ```kotlin
  private fun sourceCandidatesFor(
      sourceIndex: SourceIndex?,
      selectedNode: FixThisNode?,
      nearbyNodes: List<FixThisNode>,
      activityName: String?,
  ): List<SourceCandidate> {
      val raw = sourceIndex
          ?.takeIf { it.entries.isNotEmpty() }
          ?.let { SourceMatcher.match(it, selectedNode, nearbyNodes, activityName) }
          .orEmpty()
      if (raw.isEmpty() || sourceIndex == null) return raw
      return stalenessChecker.annotate(raw, sourceIndex)
  }
  ```

- [ ] **Step 4: 호출부 갱신**

  `FeedbackSessionService` 등에서 `TargetEvidenceService`를 만드는 자리들을 찾는다:

  ```bash
  grep -rn "TargetEvidenceService(" fixthis-mcp/src/main/kotlin/
  ```

  생성자 호출이 있는 곳마다 `projectRoot = projectRoot`를 전달한다. 이미 `projectRoot`를 들고 다니는 클래스(예: `FeedbackSessionService`, `FixThisTools`)에서는 그 필드를 그대로 넘기면 된다. 새로 만들 필요는 없다.

- [ ] **Step 5: 테스트 통과 확인**

  Run: `./gradlew :fixthis-mcp:test --tests TargetEvidenceServiceTest`

  Expected: PASS, 새 케이스 포함 모든 케이스 통과.

- [ ] **Step 6: 모듈 회귀 확인**

  Run: `./gradlew :fixthis-mcp:test`

  Expected: PASS. `FeedbackDraftService`/`FeedbackSessionService` 의존 테스트가 깨지지 않아야 한다.

- [ ] **Step 7: 커밋**

  ```bash
  git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt \
          fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt \
          fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceServiceTest.kt
  # 다른 호출부 파일이 변경됐다면 함께 add
  git commit -m "mcp: flag stale source candidates against host source"
  ```

---

## Task 4 — `BridgeStatus.installEpochMillis` 추가 (sidekick)

**Files:**

- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt:209-240,370-387`
- Create: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeStatusInstallEpochTest.kt`

**Goal:** `BridgeStatus`에 `installEpochMillis: Long?`를 추가하고, `AndroidBridgeEnvironment.status()`에서 `PackageManager.getPackageInfo(packageName, 0).lastUpdateTime`을 채운다. 옛 페이로드(필드 없음)는 `null`로 디코드되어야 한다.

> **호환성 메모:** Task 1과 같은 패턴으로 nullable + 기본값. 옛 5-arg 보조 생성자(`BridgeServer.kt:222-239`)는 `installEpochMillis = null`로 위임만 추가한다.

- [ ] **Step 1: 실패하는 테스트 작성**

  Create `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeStatusInstallEpochTest.kt`:

  ```kotlin
  package io.beyondwin.fixthis.compose.sidekick.bridge

  import org.junit.Test
  import kotlinx.serialization.json.Json
  import kotlin.test.assertEquals
  import kotlin.test.assertNull

  class BridgeStatusInstallEpochTest {
      private val json = Json { ignoreUnknownKeys = true }

      @Test
      fun `installEpochMillis defaults to null`() {
          val status = BridgeStatus(
              activity = null,
              rootsCount = 0,
              sidekickVersion = "1.0.0",
              bridgeProtocolVersion = "1.0.0",
              sourceIndexAvailable = false,
          )
          assertNull(status.installEpochMillis)
      }

      @Test
      fun `JSON round-trip preserves installEpochMillis`() {
          val original = BridgeStatus(
              activity = "Foo",
              rootsCount = 1,
              sidekickVersion = "1.0.0",
              bridgeProtocolVersion = "1.0.0",
              sourceIndexAvailable = true,
              installEpochMillis = 1_700_000_000_000L,
          )
          val text = json.encodeToString(BridgeStatus.serializer(), original)
          val decoded = json.decodeFromString(BridgeStatus.serializer(), text)
          assertEquals(original.installEpochMillis, decoded.installEpochMillis)
      }

      @Test
      fun `legacy JSON without installEpochMillis decodes to null`() {
          val legacy = """{"rootsCount":1,"sidekickVersion":"1.0.0","bridgeProtocolVersion":"1.0.0","sourceIndexAvailable":true}"""
          val decoded = json.decodeFromString(BridgeStatus.serializer(), legacy)
          assertNull(decoded.installEpochMillis)
      }
  }
  ```

- [ ] **Step 2: 테스트 실패 확인**

  Run: `./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests BridgeStatusInstallEpochTest`

  Expected: 컴파일 실패 — `installEpochMillis` 미존재.

- [ ] **Step 3: 데이터 클래스 갱신**

  Edit `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt:209-240`:

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
  ) {
      constructor(
          activity: String?,
          rootsCount: Int,
          sidekickVersion: String,
          bridgeProtocolVersion: String,
          sourceIndexAvailable: Boolean,
      ) : this(
          activity = activity,
          rootsCount = rootsCount,
          sidekickVersion = sidekickVersion,
          bridgeProtocolVersion = bridgeProtocolVersion,
          sourceIndexAvailable = sourceIndexAvailable,
          capabilities = BridgeCapabilities(),
          screenInteractive = null,
          keyguardLocked = null,
          appForeground = null,
          pictureInPicture = null,
          installEpochMillis = null,
      )
  }
  ```

- [ ] **Step 4: 테스트 통과 확인 (필드만)**

  Run: `./gradlew :fixthis-compose-sidekick:testDebugUnitTest --tests BridgeStatusInstallEpochTest`

  Expected: PASS, 3 tests.

- [ ] **Step 5: status()에서 lastUpdateTime 채움**

  Edit `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt:370-387`:

  ```kotlin
  override suspend fun status(): BridgeStatus {
      val inspection = inspectCurrentScreen()
      val sourceIndexResult = readSourceIndex()
      val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
      val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
      val resumedActivity = lifecycleCallbacks.lastResumedActivity()
      return BridgeStatus(
          activity = inspection.activity,
          rootsCount = inspection.roots.size,
          sidekickVersion = sidekickVersion,
          bridgeProtocolVersion = BridgeProtocol.VERSION,
          sourceIndexAvailable = sourceIndexResult.sourceIndexAvailable,
          screenInteractive = powerManager?.isInteractive,
          keyguardLocked = keyguardManager?.isKeyguardLocked,
          appForeground = lifecycleCallbacks.isAppForeground(),
          pictureInPicture = resumedActivity?.isInPictureInPictureMode,
          installEpochMillis = readInstallEpochMillis(),
      )
  }

  private fun readInstallEpochMillis(): Long? = runCatching {
      context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
  }.getOrNull()
  ```

  > `getPackageInfo`는 SDK 33부터 `getPackageInfo(String, PackageInfoFlags)` 시그니처가 권장되지만, 정수 0(`PackageInfoFlags.of(0)`이 아닌)도 모든 minSdk에서 호환된다. minSdk가 33+면 `PackageInfoFlags.of(0L)` 시그니처를 쓴다.

- [ ] **Step 6: 모듈 전체 테스트**

  Run: `./gradlew :fixthis-compose-sidekick:testDebugUnitTest`

  Expected: PASS. 기존 `BridgeStatusAvailabilityTest` 등이 새 nullable 필드로 깨지지 않는지 확인.

- [ ] **Step 7: 커밋**

  ```bash
  git add fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt \
          fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeStatusInstallEpochTest.kt
  git commit -m "sidekick: expose APK install epoch on BridgeStatus"
  ```

---

## Task 5 — `HostSourceFreshnessProbe` 도입 (MCP)

**Files:**

- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbe.kt`
- Create: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbeTest.kt`

**Goal:** SourceIndex와 `installEpochMillis`를 받아, index에 등재된 파일들의 `lastModified()`를 stat하고 `installEpochMillis`보다 새로운 파일을 카운트한다. 결과는 다음 데이터 클래스로 노출한다:

```kotlin
data class HostSourceFreshnessResult(
    val installStale: Boolean,        // newerFileCount > 0
    val newerFileCount: Int,          // 0 if not stale
    val totalIndexedFiles: Int,
    val installedAtEpochMillis: Long?,
    val sampleNewerFiles: List<String>, // 최대 3개, 디버깅용
    val reason: String?,              // 사람이 읽는 한 줄
)
```

`installEpochMillis`가 `null`이면 `installStale = false`, `reason = "install epoch unavailable; older sidekick"` 같은 식으로 inconclusive를 표현한다(글로벌 검사를 비활성화).

- [ ] **Step 1: 실패하는 테스트 작성**

  Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbeTest.kt`:

  ```kotlin
  package io.beyondwin.fixthis.mcp.session

  import io.beyondwin.fixthis.compose.core.source.SourceIndex
  import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
  import org.junit.jupiter.api.Test
  import org.junit.jupiter.api.io.TempDir
  import java.nio.file.Path
  import kotlin.io.path.writeText
  import kotlin.test.assertEquals
  import kotlin.test.assertFalse
  import kotlin.test.assertTrue

  class HostSourceFreshnessProbeTest {

      @Test
      fun `flags newer files compared to install epoch`(@TempDir tmp: Path) {
          val installed = 1_700_000_000_000L
          val newer = tmp.resolve("New.kt").also { it.writeText("a") }
          newer.toFile().setLastModified(installed + 60_000)
          val older = tmp.resolve("Old.kt").also { it.writeText("b") }
          older.toFile().setLastModified(installed - 60_000)
          val index = SourceIndex(entries = listOf(
              SourceIndexEntry(file = "New.kt", line = 1, excerpt = "a"),
              SourceIndexEntry(file = "Old.kt", line = 1, excerpt = "b"),
          ))
          val probe = HostSourceFreshnessProbe(tmp.toFile())

          val result = probe.evaluate(index, installEpochMillis = installed)

          assertTrue(result.installStale)
          assertEquals(1, result.newerFileCount)
          assertEquals(2, result.totalIndexedFiles)
          assertEquals(listOf("New.kt"), result.sampleNewerFiles)
      }

      @Test
      fun `not stale when no file is newer`(@TempDir tmp: Path) {
          val installed = 1_700_000_000_000L
          val file = tmp.resolve("X.kt").also { it.writeText("x") }
          file.toFile().setLastModified(installed - 60_000)
          val index = SourceIndex(entries = listOf(
              SourceIndexEntry(file = "X.kt", line = 1, excerpt = "x"),
          ))
          val probe = HostSourceFreshnessProbe(tmp.toFile())

          val result = probe.evaluate(index, installEpochMillis = installed)

          assertFalse(result.installStale)
          assertEquals(0, result.newerFileCount)
      }

      @Test
      fun `inconclusive when install epoch is null`(@TempDir tmp: Path) {
          val index = SourceIndex(entries = listOf(
              SourceIndexEntry(file = "X.kt", line = 1, excerpt = "x"),
          ))
          val probe = HostSourceFreshnessProbe(tmp.toFile())

          val result = probe.evaluate(index, installEpochMillis = null)

          assertFalse(result.installStale)
          assertTrue(result.reason!!.contains("install epoch"))
      }

      @Test
      fun `deduplicates by entry file path`(@TempDir tmp: Path) {
          val installed = 1_700_000_000_000L
          val file = tmp.resolve("Dup.kt").also { it.writeText("a") }
          file.toFile().setLastModified(installed + 60_000)
          val index = SourceIndex(entries = listOf(
              SourceIndexEntry(file = "Dup.kt", line = 1, excerpt = "a"),
              SourceIndexEntry(file = "Dup.kt", line = 5, excerpt = "b"),
          ))
          val probe = HostSourceFreshnessProbe(tmp.toFile())

          val result = probe.evaluate(index, installEpochMillis = installed)

          assertEquals(1, result.newerFileCount)
          assertEquals(1, result.totalIndexedFiles)
      }
  }
  ```

- [ ] **Step 2: 테스트 실패 확인**

  Run: `./gradlew :fixthis-mcp:test --tests HostSourceFreshnessProbeTest`

  Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

  Create `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbe.kt`:

  ```kotlin
  package io.beyondwin.fixthis.mcp.session

  import io.beyondwin.fixthis.compose.core.source.SourceIndex
  import java.io.File

  data class HostSourceFreshnessResult(
      val installStale: Boolean,
      val newerFileCount: Int,
      val totalIndexedFiles: Int,
      val installedAtEpochMillis: Long?,
      val sampleNewerFiles: List<String>,
      val reason: String?,
  )

  /**
   * Compares mtime of every file referenced by the on-device source-index against the
   * APK's install epoch. Used by `fixthis_status` to surface a one-line warning when
   * the agent should reinstall before trusting source coordinates.
   */
  class HostSourceFreshnessProbe(private val projectRoot: File) {

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
          val newer = files.mapNotNull { relative ->
              val resolved = runCatching { File(canonicalRoot, relative).canonicalFile }.getOrNull()
                  ?: return@mapNotNull null
              if (!resolved.path.startsWith(canonicalRoot.path + File.separator)) return@mapNotNull null
              if (!resolved.isFile) return@mapNotNull null
              if (resolved.lastModified() > installEpochMillis) relative else null
          }
          val stale = newer.isNotEmpty()
          return HostSourceFreshnessResult(
              installStale = stale,
              newerFileCount = newer.size,
              totalIndexedFiles = files.size,
              installedAtEpochMillis = installEpochMillis,
              sampleNewerFiles = newer.take(SampleSize),
              reason = if (stale) {
                  "${newer.size} of ${files.size} indexed source files changed after the installed APK was built"
              } else {
                  null
              },
          )
      }

      private companion object {
          const val SampleSize = 3
      }
  }
  ```

- [ ] **Step 4: 테스트 통과 확인**

  Run: `./gradlew :fixthis-mcp:test --tests HostSourceFreshnessProbeTest`

  Expected: PASS, 4 tests.

- [ ] **Step 5: 커밋**

  ```bash
  git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbe.kt \
          fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/HostSourceFreshnessProbeTest.kt
  git commit -m "mcp: add HostSourceFreshnessProbe"
  ```

---

## Task 6 — `fixthis_status` 응답에 `installStale*` 필드 추가

**Files:**

- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt:45-65,87-101`
- Modify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisToolsTest.kt` (없으면 신규 생성; 기존 테스트 디렉토리에 다른 tools 테스트가 있는지 먼저 확인)

**Goal:** `fixthis_status`가 다음 필드를 추가로 노출:

```json
{
  "installStale": true,
  "installStaleReason": "12 of 134 indexed source files changed after the installed APK was built",
  "installStaleHint": "Run ./gradlew :app:installDebug then cold-launch the app",
  "installedAtEpochMillis": 1700000000000,
  "newerSourceFiles": ["sample/src/main/kotlin/.../Foo.kt", ...]   // 최대 3개
}
```

`sourceIndexAvailable == false`이거나 `installEpochMillis == null`이면 `installStale = false`, `installStaleReason`은 inconclusive 메시지. 기존 필드(`deviceConnected`, `bridge` 등)는 유지.

probe는 sourceIndex 페치를 트리거할 수 있으므로, `bridge.readSourceIndex(packageName)`가 실패하면 글로벌 검사를 조용히 건너뛴다(부가 기능이 status를 부수면 안 됨).

- [ ] **Step 1: 실패하는 테스트 작성**

  먼저 기존 위치 확인:

  ```bash
  ls fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/tools/ 2>/dev/null
  ```

  존재하지 않으면 디렉토리를 만들고, 다음 파일을 생성한다:

  Create/append `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisToolsStatusTest.kt`:

  ```kotlin
  package io.beyondwin.fixthis.mcp.tools

  import io.beyondwin.fixthis.compose.core.source.SourceIndex
  import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
  import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
  import kotlinx.coroutines.runBlocking
  import kotlinx.serialization.json.JsonObject
  import kotlinx.serialization.json.JsonPrimitive
  import kotlinx.serialization.json.boolean
  import kotlinx.serialization.json.buildJsonObject
  import kotlinx.serialization.json.jsonArray
  import kotlinx.serialization.json.jsonObject
  import kotlinx.serialization.json.jsonPrimitive
  import kotlinx.serialization.json.put
  import org.junit.jupiter.api.Test
  import org.junit.jupiter.api.io.TempDir
  import java.nio.file.Path
  import kotlin.io.path.createDirectories
  import kotlin.io.path.writeText
  import kotlin.test.assertEquals
  import kotlin.test.assertTrue

  class FixThisToolsStatusTest {

      @Test
      fun `status surfaces installStale when newer source exists`(@TempDir tmp: Path) {
          val installed = 1_700_000_000_000L
          val src = tmp.resolve("sample/src/main/kotlin/Foo.kt")
          src.parent.createDirectories()
          src.writeText("fun foo() {}")
          src.toFile().setLastModified(installed + 120_000)

          val bridge = FakeFixThisBridge(
              statusOverride = buildJsonObject {
                  put("activity", "Foo")
                  put("rootsCount", 1)
                  put("sourceIndexAvailable", true)
                  put("installEpochMillis", installed)
              },
              sourceIndexOverride = buildJsonObject {
                  put("sourceIndexAvailable", true)
                  put("sourceIndex", indexJson(SourceIndex(entries = listOf(
                      SourceIndexEntry(file = "sample/src/main/kotlin/Foo.kt", line = 1, excerpt = "fun foo() {}"),
                  ))))
              },
          )
          val tools = FixThisTools(
              bridge = bridge,
              defaultPackageName = "io.beyondwin.fixthis.sample",
              projectRoot = tmp.toFile(),
          )

          val response = runBlocking {
              tools.call("fixthis_status", JsonObject(emptyMap()))
          }
          val payload = response.firstStructuredPayload()

          assertEquals(true, payload["installStale"]?.jsonPrimitive?.boolean)
          assertTrue(payload["installStaleReason"]!!.jsonPrimitive.content.contains("indexed source files"))
          assertTrue(payload["newerSourceFiles"]!!.jsonArray.size >= 1)
      }

      @Test
      fun `status omits installStale when sidekick lacks installEpochMillis`(@TempDir tmp: Path) {
          val bridge = FakeFixThisBridge(
              statusOverride = buildJsonObject {
                  put("activity", "Foo")
                  put("rootsCount", 1)
                  put("sourceIndexAvailable", true)
                  // installEpochMillis intentionally absent
              },
              sourceIndexOverride = buildJsonObject {
                  put("sourceIndexAvailable", true)
                  put("sourceIndex", indexJson(SourceIndex()))
              },
          )
          val tools = FixThisTools(
              bridge = bridge,
              defaultPackageName = "io.beyondwin.fixthis.sample",
              projectRoot = tmp.toFile(),
          )

          val payload = runBlocking { tools.call("fixthis_status", JsonObject(emptyMap())) }
              .firstStructuredPayload()

          assertEquals(false, payload["installStale"]?.jsonPrimitive?.boolean)
          assertTrue(payload["installStaleReason"]!!.jsonPrimitive.content.contains("install epoch"))
      }

      // 헬퍼 — 기존 테스트가 사용하는 패턴을 따른다. `firstStructuredPayload`는 jsonToolResult가
      // structuredContent.payload 또는 동등한 곳에 JsonObject를 둔다. 기존 테스트의 추출 헬퍼가 있으면 그것을 재사용.
      private fun JsonObject.firstStructuredPayload(): JsonObject =
          this["structuredContent"]?.jsonObject
              ?: this["content"]?.jsonArray?.first()?.jsonObject?.get("text")?.let {
                  kotlinx.serialization.json.Json.parseToJsonElement(it.jsonPrimitive.content).jsonObject
              }
              ?: error("Cannot locate structured payload in $this")

      private fun indexJson(index: SourceIndex) =
          kotlinx.serialization.json.Json.encodeToJsonElement(SourceIndex.serializer(), index)
  }
  ```

  > `FakeFixThisBridge`는 이미 `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FakeFixThisBridge.kt`에 존재한다. 거기에 `statusOverride`/`sourceIndexOverride` 매개변수가 없다면 추가한다 — 기존 status를 그대로 돌려주던 곳에 override 값이 있으면 그걸 우선 반환하는 식이다. 변경 시 같은 커밋에 묶는다.

- [ ] **Step 2: 테스트 실패 확인**

  Run: `./gradlew :fixthis-mcp:test --tests FixThisToolsStatusTest`

  Expected: 컴파일 실패 또는 단언 실패.

- [ ] **Step 3: `FixThisTools` 갱신**

  Edit `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt:45-65`:

  ```kotlin
  class FixThisTools(
      private val bridge: FixThisBridge = CliFixThisBridge(BridgeClient()),
      private val defaultPackageName: String? = null,
      private val projectRoot: File = File(".").canonicalFile,
      private val feedbackService: FeedbackSessionService = FeedbackSessionService(
          bridge = bridge,
          store = FeedbackSessionStore(
              persistence = FeedbackSessionPersistence(FeedbackSessionPaths(projectRoot)),
          ),
          projectRoot = projectRoot.absolutePath,
          defaultPackageName = defaultPackageName,
      ),
      private val consoleAssetsDir: File? = null,
      private val consolePort: Int = 0,
      private val freshnessProbe: HostSourceFreshnessProbe = HostSourceFreshnessProbe(projectRoot),
  ) {
  ```

  Edit `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt:87-101`:

  ```kotlin
  "fixthis_status" -> bridgeToolResult {
      val packageName = resolvePackageName(arguments)
      val status = bridge.status(packageName)
      cacheStatus(packageName, status)
      val freshness = evaluateFreshness(packageName, status)
      jsonToolResult(buildJsonObject {
          put("deviceConnected", true)
          put("packageName", packageName)
          put("appRunning", status["activity"] != null)
          put("sidekickConnected", true)
          put("currentActivity", status["activity"] ?: JsonPrimitive(""))
          put("composeRoots", status["rootsCount"] ?: JsonPrimitive(0))
          put("sourceIndexAvailable", status["sourceIndexAvailable"] ?: JsonPrimitive(false))
          put("installStale", JsonPrimitive(freshness.installStale))
          freshness.reason?.let { put("installStaleReason", JsonPrimitive(it)) }
          if (freshness.installStale) {
              put("installStaleHint", JsonPrimitive("Run ./gradlew :app:installDebug then cold-launch the app"))
          }
          freshness.installedAtEpochMillis?.let { put("installedAtEpochMillis", JsonPrimitive(it)) }
          put("newerSourceFiles", buildJsonArray { freshness.sampleNewerFiles.forEach { add(JsonPrimitive(it)) } })
          put("bridge", status)
      })
  }
  ```

  파일 하단(다른 private helper 근처)에 추가:

  ```kotlin
  private suspend fun evaluateFreshness(
      packageName: String,
      status: JsonObject,
  ): HostSourceFreshnessResult {
      val sourceIndexAvailable = status["sourceIndexAvailable"]?.jsonPrimitive?.booleanOrNull == true
      val installEpoch = status["installEpochMillis"]?.jsonPrimitive?.longOrNull
      if (!sourceIndexAvailable) {
          return HostSourceFreshnessResult(
              installStale = false,
              newerFileCount = 0,
              totalIndexedFiles = 0,
              installedAtEpochMillis = installEpoch,
              sampleNewerFiles = emptyList(),
              reason = "source index not available",
          )
      }
      val raw = runCatching { bridge.readSourceIndex(packageName) }.getOrNull()
      val indexElement = raw?.get("sourceIndex")
      val sourceIndex = indexElement?.let {
          runCatching { McpProtocol.json.decodeFromJsonElement<SourceIndex>(it) }.getOrNull()
      } ?: return HostSourceFreshnessResult(
          installStale = false,
          newerFileCount = 0,
          totalIndexedFiles = 0,
          installedAtEpochMillis = installEpoch,
          sampleNewerFiles = emptyList(),
          reason = "source index could not be read",
      )
      return freshnessProbe.evaluate(sourceIndex, installEpoch)
  }
  ```

  Import 보강 (이미 있는 것은 무시):

  ```kotlin
  import io.beyondwin.fixthis.compose.core.source.SourceIndex
  import io.beyondwin.fixthis.mcp.session.HostSourceFreshnessProbe
  import io.beyondwin.fixthis.mcp.session.HostSourceFreshnessResult
  import kotlinx.serialization.json.booleanOrNull
  import kotlinx.serialization.json.buildJsonArray
  import kotlinx.serialization.json.decodeFromJsonElement
  import kotlinx.serialization.json.longOrNull
  ```

- [ ] **Step 4: 테스트 통과 확인**

  Run: `./gradlew :fixthis-mcp:test --tests FixThisToolsStatusTest`

  Expected: PASS, 2 tests.

- [ ] **Step 5: 모듈 회귀**

  Run: `./gradlew :fixthis-mcp:test`

  Expected: PASS.

- [ ] **Step 6: 커밋**

  ```bash
  git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt \
          fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisToolsStatusTest.kt \
          fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FakeFixThisBridge.kt
  git commit -m "mcp: surface install-stale hint on fixthis_status"
  ```

---

## Task 7 — 문서 + CHANGELOG

**Files:**

- Modify: `docs/mcp.md`
- Modify: `docs/troubleshooting.md`
- Modify: `CHANGELOG.md`

**Goal:** 새 동작을 발견 가능하게 문서화한다. 큰 추가는 안 한다 — 한 항목씩.

- [ ] **Step 1: `docs/mcp.md`의 `fixthis_status` 섹션에 다음을 추가**

  `fixthis_status` 응답 설명이 있는 곳에 한 단락:

  ```markdown
  Responses also include an `installStale` boolean and `installStaleReason` string. When `true`, the host has source files modified after the device APK's last install time, so source coordinates returned by other tools may not match what you can edit; reinstall the debug APK before trusting them. `newerSourceFiles` lists up to three example paths. When `installEpochMillis` is unavailable (older sidekick), the check is skipped and `installStale` is `false` with an explanatory reason.
  ```

  `SourceCandidate` 스키마 설명이 있는 곳에 한 줄:

  ```markdown
  Each `SourceCandidate` may also carry `stale: true | false | null` and `staleReason`. `true` means the host source line no longer matches the index excerpt — do not edit by file:line; locate the symbol elsewhere first. `null` means the candidate could not be verified (no excerpt or no line, e.g. an XML resource entry).
  ```

- [ ] **Step 2: `docs/troubleshooting.md`에 한 섹션 추가**

  ```markdown
  ## "Source coordinates point to old code"

  Symptom: After editing app code, `fixthis_read_feedback` returns paths/lines that don't match what you see in the editor.

  Cause: The debug APK on the device was built before your changes. The on-device `fixthis-source-index.json` still references old line numbers.

  Fix:

  ```bash
  ./gradlew :app:installDebug
  ```

  Cold-launch the app once so the sidekick re-reads the new index. `fixthis_status` will then report `installStale: false`. Per-candidate `stale: true` flags also clear.
  ```

- [ ] **Step 3: `CHANGELOG.md`에 항목 추가**

  최상단 미공개(unreleased) 섹션 또는 다음 릴리스 노트에:

  ```markdown
  - Added: `fixthis_status` now reports `installStale` plus an `installStaleHint` when host source files are newer than the installed APK; each `SourceCandidate` carries `stale`/`staleReason` so AI agents can detect coordinates that no longer match host source.
  ```

- [ ] **Step 4: 전체 테스트 회귀**

  Run:
  ```bash
  ./gradlew :fixthis-compose-core:test \
            :fixthis-cli:test \
            :fixthis-mcp:test \
            :fixthis-compose-sidekick:testDebugUnitTest \
            :fixthis-gradle-plugin:test
  ```

  Expected: 전부 PASS.

- [ ] **Step 5: 커밋**

  ```bash
  git add docs/mcp.md docs/troubleshooting.md CHANGELOG.md
  git commit -m "docs: document install-staleness signals on fixthis_status and SourceCandidate"
  ```

---

## Out of scope (의도적으로 미포함)

- **Gradle 플러그인 변경**: build epoch을 `fixthis-build-info.json`에 baking하는 옵션은 더 정확하지만, `PackageManager.lastUpdateTime`으로 충분하므로 본 plan에서는 도입 안 함. 추후 정확도 이슈가 보이면 별도 plan으로.
- **신규 추가/이동된 파일 감지**: probe는 source-index에 등재된 파일만 본다. 인덱스에 없는 새 파일은 글로벌 신호에서 빠진다. 이는 의도적 단순화 — per-candidate 검증이 그 빈자리를 메운다.
- **자동 재설치 (`installDebug` 트리거)**: 외부 프로젝트 호환성 때문에 별도 옵트인 plan 사안.
- **Compose 시멘틱 트리 vs 소스 검증**: 화면에 보이는 텍스트 vs 인덱스의 텍스트 비교는 별개 문제(노이즈 큼). 본 plan 외.

## Risk register

- **R1: Excerpt trim 차이로 거짓 양성** — Gradle 플러그인이 `line.trim()`으로 저장(`GenerateFixThisSourceIndexTask.kt:284`). checker도 trim 비교. 일치해야 함. 만약 Gradle이 normalize 방식을 바꾸면 양쪽이 같이 깨진다 — Task 1 직렬화 테스트가 잡지는 못하지만 Task 3의 통합 테스트가 잡는다.
- **R2: ProjectRoot가 잘못 설정** — `fixthis_status`를 임의 디렉토리에서 호출하면 모든 파일이 "missing"으로 잡혀 거짓 양성. CLI가 invoking shell의 cwd를 그대로 쓰므로(`File(".").canonicalFile`), `fixthis_status`가 reasoning 도중 `installStaleReason: "X of Y indexed source files missing on host"` 같은 메시지를 보이면 cwd 의심을 가이드에 적어둔다(troubleshooting). 본 plan에서는 별도 가드 안 둔다 — 문서로 충분.
- **R3: Stat 비용** — 일반적인 소스 인덱스(수백 파일)에선 무시할 수준. 인덱스가 1만 파일을 넘기면 이슈가 될 수 있으나, 현재 그런 규모를 다루지 않음. 추후 캐시 도입은 별도 작업.
- **R4: APK install 시각의 시계 왜곡** — 디바이스/호스트 시계가 어긋나면 거짓 양성/음성. 일반적으로 ADB 환경은 같은 머신이라 무시 가능.

---

## Self-review checklist (after implementation)

각 태스크 PR 머지 시 다음을 확인:

1. `SourceCandidate`/`BridgeStatus`의 새 필드가 모두 nullable + 기본값으로 호환성 유지.
2. `installEpochMillis`가 null인 경우(옛 sidekick) `fixthis_status`가 깨지지 않고 inconclusive 메시지를 낸다.
3. per-candidate `stale = true`인 케이스가 prompt(`fixthis_read_feedback`의 markdown 출력)에 반영되어 에이전트가 알아챌 수 있는지 — 안 보인다면 후속으로 `FeedbackQueueFormatter` 출력에 stale 마커를 넣을 plan을 따로 만든다(본 plan은 데이터 노출까지).
4. README/AGENTS.md의 도구 카탈로그가 이 변경을 다른 곳에서 참조하지 않는지 grep 확인:
   ```bash
   grep -rn "fixthis_status\|sourceCandidates" AGENTS.md README.md docs/
   ```
   참조가 있다면 문서를 함께 갱신.
