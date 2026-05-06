# Design: Zero-Setup Agent Configuration

**상태**: 제안  
**대상 버전**: V1.1  
**관련 모듈**: `fixthis-cli`

---

## 1. 문제 정의

### 1.1 현재 유저 경험

신규 유저가 FixThis를 Claude Code 또는 Codex에 연결하려면 다음을 직접 수행해야 한다.

```
1. 레포 클론
2. ./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
3. ANDROID_HOME / adb PATH 파악
4. 연결된 디바이스 serial 확인 (adb devices)
5. 각 AI 클라이언트 설정 파일 위치 파악
6. 아래 형식에 맞게 수동 작성
```

**Codex에 실제로 들어가 있는 설정 (현재)**:

```toml
[mcp_servers.fixthis_sample]
command = "/bin/zsh"
args = [
  "-lc",
  "ANDROID_HOME=/Users/kws/Library/Android/sdk ANDROID_SERIAL='adb-R3CN60LXW3L-...' PATH=.../platform-tools:$PATH /절대경로/fixthis mcp --package io.beyondwin.fixthis.sample --project-dir /절대경로"
]
```

비교: Playwright MCP 설정 전체:

```toml
[mcp_servers.playwright]
command = "npx"
args = ["-y", "@playwright/mcp@latest"]
```

### 1.2 근본 원인 분석

문제는 두 레이어로 분리된다.

**레이어 1 — 환경 변수 누락**

`Adb.defaultAdbExecutable()`은 이미 `ANDROID_HOME`을 읽는다. 그러나 MCP 클라이언트가 프로세스를 실행할 때 셸 프로파일(`.zshrc`, `.bash_profile`)을 거치지 않아 `ANDROID_HOME`이 비어 있다. 이것이 `/bin/zsh -lc` wrapper가 필요한 이유다.

```kotlin
// Adb.kt — 이미 올바른 코드, 환경 문제가 근본 원인
companion object {
    fun defaultAdbExecutable(): String {
        val androidHome = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
        if (androidHome != null) {
            val adb = File(androidHome, "platform-tools/adb")
            if (adb.exists()) return adb.absolutePath
        }
        return "adb"  // ← PATH에도 없으면 실패
    }
}
```

**레이어 2 — 설정 파일 수동 작성**

`fixthis setup` 명령은 JSON을 stdout에 출력하기만 하고, 실제 파일에 쓰지 않는다. 유저가 올바른 파일 위치를 찾아 형식에 맞게 직접 작성해야 한다.

---

## 2. 목표 및 비목표

### 목표

- `fixthis setup --write` 한 명령으로 Claude Code / Codex 양쪽 설정 완료
- zsh wrapper 없이 동작하는 MCP 설정 생성
- 기존 설정 파일을 안전하게 merge (덮어쓰지 않음)
- 디바이스가 없거나 ANDROID_HOME을 못 찾아도 설정 파일은 생성 (런타임에 재시도)

### 비목표

- 바이너리 배포 (Homebrew, GitHub Releases) — 별도 작업
- Windows 지원 — V1 범위 외
- 자동 PATH 등록 — 별도 `fixthis install` 커맨드로 분리
- 제3 AI 클라이언트 (Cursor, Windsurf 등) — 확장 설계로 대응

---

## 3. 설계

### 3.1 명령어 인터페이스

```
fixthis setup [--package <id>] [--project-dir <path>]
                 [--write]                     ← 신규: 파일에 자동 기록
                 [--target claude|codex|all]   ← 신규: 대상 선택 (기본값: all)
                 [--dry-run]                   ← 기존 동작 유지 (stdout만)
```

`--write` 없이 실행하면 기존과 동일하게 JSON을 stdout에 출력한다.  
`--write` 시에는 파일에 기록하고 결과를 사람이 읽기 좋게 출력한다.

**예시 출력 (`--write` 성공 시)**:

```
ANDROID_HOME  ~/Library/Android/sdk  (auto-detected)
device        Pixel 7 (adb-R3CN60LXW3L-...)
fixthis    /절대경로/fixthis-cli/build/install/fixthis/bin/fixthis

✓  .claude/settings.json   (merged)
✓  ~/.codex/config.toml    (merged)

Restart your AI client to apply the changes.
```

### 3.2 ANDROID_HOME 자동 탐색

`Adb.kt`의 `defaultAdbExecutable()`을 유지하면서, 설정 파일에 명시적 env 블록을 추가하는 방식으로 문제를 해결한다. 런타임 탐색은 새로운 `AndroidSdkLocator` 객체가 담당한다.

```kotlin
// 신규 파일: fixthis-cli/.../AndroidSdkLocator.kt
object AndroidSdkLocator {
    data class SdkLocation(val home: File, val adb: File, val source: String)

    fun find(): SdkLocation? {
        return candidates()
            .filter { it.adb.canExecute() }
            .firstOrNull()
    }

    private fun candidates(): Sequence<SdkLocation> = sequence {
        // 1. 명시적 환경 변수 (가장 신뢰도 높음)
        yieldSdk(System.getenv("ANDROID_HOME"), "ANDROID_HOME env")
        yieldSdk(System.getenv("ANDROID_SDK_ROOT"), "ANDROID_SDK_ROOT env")

        // 2. OS별 기본 설치 경로
        val home = System.getProperty("user.home")
        yieldSdk("$home/Library/Android/sdk", "macOS default")
        yieldSdk("$home/Android/Sdk", "Linux default")
        yieldSdk("${System.getenv("LOCALAPPDATA")}/Android/Sdk", "Windows default")

        // 3. Android Studio 설치 경로 탐색
        yieldSdk("$home/.android/sdk", "fallback")
    }

    private suspend fun SequenceScope<SdkLocation>.yieldSdk(path: String?, source: String) {
        path?.takeIf { it.isNotBlank() } ?: return
        val home = File(path)
        val adb = home.resolve("platform-tools/adb")
        if (home.isDirectory) yield(SdkLocation(home = home, adb = adb, source = source))
    }
}
```

### 3.3 설정 파일 Writer 구조

```
AgentConfigWriter (인터페이스)
├── ClaudeConfigWriter   → .claude/settings.json
└── CodexConfigWriter    → ~/.codex/config.toml
```

```kotlin
interface AgentConfigWriter {
    val name: String
    fun configFile(): File?          // 설정 파일 경로 (없으면 null → 스킵)
    fun merge(current: String?, entry: McpConfigEntry): String
}
```

`merge()`는 순수 함수다. 파일 I/O는 `SetupCommand`가 단독으로 담당한다. 이렇게 하면 Writer를 테스트할 때 파일시스템이 필요 없다.

#### 3.3.1 ClaudeConfigWriter

Claude Code는 프로젝트 로컬 설정을 `.claude/settings.json`에서 읽는다.

```json
{
  "mcpServers": {
    "fixthis": {
      "command": "/절대경로/fixthis",
      "args": ["mcp", "--package", "...", "--project-dir", "..."],
      "env": {
        "ANDROID_HOME": "/절대경로/sdk"
      }
    }
  }
}
```

`env` 필드가 핵심이다. 이것이 zsh wrapper 없이 ANDROID_HOME을 전달하는 방법이다. Claude Code MCP 서버 스펙의 `env` 필드를 활용한다.

merge 로직:
1. 파일이 없으면 새로 생성
2. 있으면 `mcpServers.fixthis` 키만 교체, 나머지 보존
3. kotlinx.serialization.json의 `JsonObject` 조작으로 구현 (외부 의존성 불필요)

#### 3.3.2 CodexConfigWriter

Codex는 `~/.codex/config.toml`을 사용한다.

```toml
[mcp_servers.fixthis]
command = "/절대경로/fixthis"
args = ["mcp", "--package", "...", "--project-dir", "..."]

[mcp_servers.fixthis.env]
ANDROID_HOME = "/절대경로/sdk"
```

TOML 파싱 접근법 선택:

| 방법 | 장점 | 단점 |
|------|------|------|
| `ktoml` 라이브러리 추가 | 완전한 파싱 | 의존성 추가 |
| Regex 기반 섹션 교체 | 의존성 없음 | 엣지케이스 위험 |
| 라인 기반 섹션 교체 | 의존성 없음, 안전 | 구현 복잡도 중간 |

**결정: 라인 기반 섹션 교체**. Codex config의 `[mcp_servers.*]` 섹션은 구조가 단순하고, 전체 TOML 파싱은 불필요하다. `ktoml` 같은 의존성을 추가하지 않는다.

merge 로직:
1. 기존 파일을 줄 단위로 읽음
2. `[mcp_servers.fixthis]` ~ 다음 `[` 섹션 사이를 찾아 교체
3. 없으면 파일 끝에 append
4. 다른 섹션은 그대로 보존

### 3.4 McpConfigEntry 모델

```kotlin
data class McpConfigEntry(
    val serverName: String,          // "fixthis"
    val command: String,             // 절대경로 or "fixthis"
    val args: List<String>,
    val env: Map<String, String>,    // ANDROID_HOME 등
)
```

`env`가 비어 있으면 Writer들은 env 블록을 생략한다.

### 3.5 SetupCommand 변경

```kotlin
class SetupCommand : CoreCliktCommand(name = "setup") {
    private val packageName by option(...)
    private val projectDir by option(...)
    private val write by option("--write", help = "Write config to agent settings files").flag()
    private val target by option("--target").choice("claude", "codex", "all").default("all")
    private val dryRun by option("--dry-run").flag()

    override fun run() {
        val root = File(projectDir).canonicalFile
        val client = BridgeClient(projectRoot = root)
        val resolvedPackage = failAsCliError { client.resolvePackageName(packageName) }

        val sdk = AndroidSdkLocator.find()
        val binaryPath = McpExecutableLocator.find()  // 기존 로직 재사용

        val entry = McpConfigEntry(
            serverName = "fixthis",
            command = binaryPath?.absolutePath ?: "fixthis",
            args = listOf("mcp", "--package", resolvedPackage, "--project-dir", root.absolutePath),
            env = buildMap {
                sdk?.let { put("ANDROID_HOME", it.home.absolutePath) }
            },
        )

        if (!write) {
            // 기존 동작: JSON stdout
            echo(fixThisJson.encodeToString(buildMcpClientConfig(resolvedPackage, root)))
            return
        }

        val writers = buildWriters(target)
        writers.forEach { writer ->
            val result = applyWriter(writer, entry, dryRun)
            echo(result.summary())
        }
    }
}
```

### 3.6 디바이스 Serial 처리

현재 Codex 설정에 `ANDROID_SERIAL`이 하드코딩되어 있다. 이는 무선 ADB 환경에서 특정 디바이스를 지정하기 위한 것이다.

**결정: `--write` 시 ANDROID_SERIAL은 포함하지 않는다.**

이유:
- Serial은 연결 세션마다 달라질 수 있다 (특히 무선 ADB)
- 설정 파일에 박히면 디바이스 변경 시 수동 편집 필요
- FixThis CLI는 `adb.devices()`로 연결된 디바이스를 자동 감지한다
- 여러 디바이스가 연결된 경우에는 `doctor` 명령이 안내

단, `--serial` 옵션을 명시적으로 전달하면 env에 포함:

```
fixthis setup --write --serial adb-R3CN60LXW3L-...
```

---

## 4. 파일 변경 목록

| 파일 | 변경 유형 | 내용 |
|------|----------|------|
| `fixthis-cli/.../commands/SetupCommand.kt` | 수정 | `--write`, `--target`, `--dry-run` 옵션 추가 |
| `fixthis-cli/.../AgentConfigWriter.kt` | 신규 | Writer 인터페이스 + `McpConfigEntry` 모델 |
| `fixthis-cli/.../AndroidSdkLocator.kt` | 신규 | ANDROID_HOME 자동 탐색 |
| `fixthis-cli/.../ClaudeConfigWriter.kt` | 신규 | `.claude/settings.json` merge 구현 |
| `fixthis-cli/.../CodexConfigWriter.kt` | 신규 | `~/.codex/config.toml` merge 구현 |
| `fixthis-cli/.../commands/SetupCommandTest.kt` | 신규 | Writer 선택, 옵션 파싱 테스트 |
| `fixthis-cli/.../ClaudeConfigWriterTest.kt` | 신규 | merge 로직 단위 테스트 |
| `fixthis-cli/.../CodexConfigWriterTest.kt` | 신규 | TOML 섹션 교체 단위 테스트 |
| `docs/mcp.md` | 수정 | `--write` 옵션 문서 추가 |

---

## 5. 테스트 전략

Writer들은 순수 함수(`merge(current: String?, entry: McpConfigEntry): String`)로 설계했기 때문에 파일시스템 없이 단위 테스트 가능하다.

### ClaudeConfigWriter 테스트 케이스

```kotlin
// 1. 빈 파일 → 새로 생성
merge(null, entry) shouldBe validJson(...)

// 2. 기존 다른 mcpServer가 있을 때 → fixthis만 교체, 나머지 보존
merge("""{"mcpServers":{"playwright":...}}""", entry) shouldContain "playwright"

// 3. 기존 fixthis가 있을 때 → 덮어씀
merge("""{"mcpServers":{"fixthis":{"command":"old"}}}""", entry) shouldContain newCommand

// 4. env 비어 있을 때 → env 필드 생략
merge(null, entryWithoutEnv) shouldNotContain "env"
```

### CodexConfigWriter 테스트 케이스

```kotlin
// 1. 빈 파일 → 섹션 append
merge(null, entry) shouldContain "[mcp_servers.fixthis]"

// 2. 기존 다른 mcp_server 존재 → 보존
merge("[mcp_servers.playwright]\ncommand = \"npx\"", entry) shouldContain "playwright"

// 3. 기존 fixthis 섹션 → 교체
merge("[mcp_servers.fixthis]\ncommand = \"old\"", entry) shouldNotContain "old"

// 4. fixthis가 마지막 섹션이 아닐 때 → 다음 섹션까지만 교체
```

---

## 6. 구현 순서

### Phase 1: 환경 변수 문제 해결 (즉시 효과)

1. `AndroidSdkLocator.kt` 구현 + 테스트
2. `McpConfigEntry` 모델 정의
3. `SetupCommand`에 `--write` flag 추가 (Writer 없이 출력 형식만 변경)
4. 생성되는 JSON에 `env` 필드 포함

이것만으로도 zsh wrapper 없이 동작하는 설정 JSON을 얻을 수 있다. 유저가 수동으로 붙여넣더라도 훨씬 단순해진다.

### Phase 2: 자동 파일 기록

5. `ClaudeConfigWriter` 구현 + 테스트
6. `CodexConfigWriter` 구현 + 테스트
7. `SetupCommand`에 실제 파일 write 연결
8. 문서 업데이트

### Phase 3: 바이너리 접근성 개선 (별도 작업)

9. `fixthis install` 커맨드 — `~/.local/bin` 에 심볼릭 링크
10. GitHub Releases 바이너리 배포

Phase 3는 본 설계 범위 밖이며, Phase 1-2가 완료된 후 진행한다.

---

## 7. 열린 질문

1. **Codex global vs project-level 설정**: Codex는 `~/.codex/config.toml` (글로벌)만 지원하는가, 아니면 프로젝트 로컬 설정도 가능한가? Claude는 `.claude/settings.json`(프로젝트)과 `~/.claude/settings.json`(글로벌) 양쪽을 지원한다. 현재 설계는 Codex는 글로벌, Claude는 프로젝트 로컬로 기록한다.

2. **기존 fixthis 설정 감지**: `--write` 실행 시 이미 설정이 존재하면 overwrite 전에 확인 메시지를 보여줄 것인가, 조용히 덮어쓸 것인가? 현재 설계는 조용히 merge (덮어씀).

3. **빌드되지 않은 경우**: `McpExecutableLocator.find()`가 null을 반환하면 `command = "fixthis"`를 넣는다. 이 경우 PATH에 `fixthis`가 없으면 MCP 클라이언트 실행 시 실패한다. 경고 메시지 출력 필요.
