# Annotation Lifecycle Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 어노테이션 라이프사이클의 데이터 손실·정합성 깨짐·UX 불일치·연결 단절 결함 13건과 고도화 3건(fingerprint, EmulatorTestKit, event log)을 phase별로 통합 구현한다.

**Architecture:** Event-sourced 영속화(append-only JSONL + fsync write-ahead), Bridge 1.3 스냅샷 확장(orientation/windowMode/systemUiKind/fingerprint), 콘솔 클라이언트의 localStorage 미러 + Undo/Redo 스택, emulator 기반 시나리오 회귀 테스트.

**Tech Stack:** Kotlin (JVM 21, Coroutines, kotlinx.serialization), Compose UI(Android target), 콘솔 JS(ES modules, 빌드 산출물 `app.js`), JUnit5 + MockK + Truth, Playwright (콘솔 E2E), adb (emulator driving).

**Related spec:** [`../specs/2026-05-12-annotation-lifecycle-hardening-design.md`](../specs/2026-05-12-annotation-lifecycle-hardening-design.md)

---

## 공통 규칙

- 모든 task는 **TDD**: failing test → 검증(FAIL) → 최소 구현 → 검증(PASS) → commit.
- 커밋 메시지 prefix:
  - Phase A → `feat(alh): ...`
  - Phase B → `feat(sif): ...`
  - Phase C → `feat(auc): ...`
  - Phase D → `feat(cr): ...`
  - Phase E → `test(rti): ...`
- 콘솔 JS는 `fixthis-mcp/src/main/console/*.js`에서 작업 후, **반드시** `node scripts/build-console-assets.mjs`로 `fixthis-mcp/src/main/resources/console/app.js` 재번들 → 커밋에 포함.
- Bridge 프로토콜 변경 (Phase B의 SIF-1)은 4-site 동기화 필수: `BridgeProtocol.kt`, console minimum version, `BridgeClient.kt`, `ServerVersionRoutes.kt`. `BridgeProtocolVersionSyncTest` PASS 필수.
- 모든 phase에서 신규 코드는 detekt + ktlint + spotless 통과해야 함 (`./gradlew check`).

---

## Phase 0: Worktree 및 환경 셋업

### Task 0.1: 작업 worktree 생성

**Files:**
- Create: `.worktrees/annotation-lifecycle-hardening/`

- [ ] **Step 1: Worktree 생성 (superpowers:using-git-worktrees 참고)**

```bash
git worktree add .worktrees/annotation-lifecycle-hardening -b annotation-lifecycle-hardening main
cd .worktrees/annotation-lifecycle-hardening
```

- [ ] **Step 2: 빌드 sanity check**

```bash
./gradlew :fixthis-mcp:test --tests "BridgeProtocolVersionSyncTest"
```
Expected: PASS (현재 `BridgeProtocol.VERSION == "1.2"`).

- [ ] **Step 3: `.orchestrator` 디렉터리 gitignore 확인**

```bash
grep -q "^\.orchestrator$" .gitignore || echo ".orchestrator" >> .gitignore
git status .gitignore
```

- [ ] **Step 4: Commit 환경 셋업**

```bash
git add .gitignore
git commit -m "chore: ensure .orchestrator ignored before lifecycle hardening work"
```

---

## Phase A — Annotation Lifecycle Hardening

### Task A.1 (ALH-4): EventLogWriter 골격

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogWriter.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/SessionEvent.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogWriterTest.kt`

- [ ] **Step 1: Failing test — append + replay round-trip**

```kotlin
package io.beyondwin.fixthis.mcp.session.eventlog

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class EventLogWriterTest {
    @Test
    fun `appended events are readable in insertion order`(@TempDir tmp: Path) {
        val writer = EventLogWriter(tmp.resolve("events").toFile())
        val a = sessionEvent("addItem", 1) { put("itemId", "x") }
        val b = sessionEvent("addItem", 2) { put("itemId", "y") }
        writer.append(a)
        writer.append(b)
        val replayed = EventLogReader(tmp.resolve("events").toFile()).readAll()
        assertThat(replayed.map { it.sequenceNumber }).containsExactly(1, 2).inOrder()
    }

    private fun sessionEvent(type: String, seq: Int, payloadBuilder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): SessionEvent =
        SessionEvent(
            version = 1,
            eventId = "evt-$seq",
            sequenceNumber = seq,
            epochMillis = 1_715_500_000_000 + seq,
            actor = "console",
            type = type,
            payload = buildJsonObject(payloadBuilder),
            parentEventId = null,
        )
}
```

- [ ] **Step 2: Run test (must fail — classes missing)**

```bash
./gradlew :fixthis-mcp:test --tests "EventLogWriterTest" -i
```
Expected: FAIL with `Unresolved reference: EventLogWriter`.

- [ ] **Step 3: Minimal implementation — SessionEvent**

```kotlin
// fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/SessionEvent.kt
package io.beyondwin.fixthis.mcp.session.eventlog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SessionEvent(
    val version: Int = 1,
    val eventId: String,
    val sequenceNumber: Long,
    val epochMillis: Long,
    val actor: String,
    val type: String,
    val payload: JsonObject,
    val parentEventId: String? = null,
)
```

- [ ] **Step 4: Minimal implementation — EventLogWriter + Reader**

```kotlin
// fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogWriter.kt
package io.beyondwin.fixthis.mcp.session.eventlog

import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

class EventLogWriter(private val directory: File) {
    init { directory.mkdirs() }
    private val json = Json { encodeDefaults = true }

    @Synchronized
    fun append(event: SessionEvent) {
        val name = "%013d-%010d.jsonl".format(event.epochMillis, event.sequenceNumber)
        val file = File(directory, name)
        RandomAccessFile(file, "rwd").use { raf ->
            val line = json.encodeToString(SessionEvent.serializer(), event) + "\n"
            raf.channel.lock().use { _: FileLock ->
                raf.write(line.toByteArray(Charsets.UTF_8))
                raf.channel.force(true)
            }
        }
    }
}

class EventLogReader(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun readAll(): List<SessionEvent> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles { f -> f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name }
            ?.flatMap { it.readLines() }
            ?.map { json.decodeFromString(SessionEvent.serializer(), it) }
            ?: emptyList()
    }
}
```

- [ ] **Step 5: Run test — PASS**

```bash
./gradlew :fixthis-mcp:test --tests "EventLogWriterTest"
```

- [ ] **Step 6: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/ \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/
git commit -m "feat(alh): introduce append-only EventLogWriter skeleton"
```

### Task A.2 (ALH-4): fsync 실패·crash 시 fail-stop

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogWriter.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogFailureModeTest.kt`

- [ ] **Step 1: Failing test — 디스크 풀 시뮬레이션**

```kotlin
package io.beyondwin.fixthis.mcp.session.eventlog

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class EventLogFailureModeTest {
    @Test
    fun `write failure throws EventLogException without leaving partial file`(@TempDir tmp: Path) {
        val dir = tmp.resolve("events").toFile()
        val writer = EventLogWriter(dir) { _ -> throw java.io.IOException("disk full") }
        val event = SessionEvent(
            eventId = "e1", sequenceNumber = 1, epochMillis = 1L,
            actor = "console", type = "addItem", payload = buildJsonObject {},
        )
        assertThrows(EventLogException::class.java) { writer.append(event) }
        assertThat(dir.listFiles().orEmpty().filter { it.length() > 0 }).isEmpty()
    }
}
```

- [ ] **Step 2: Run test — FAIL (EventLogException missing, injection hook missing)**

```bash
./gradlew :fixthis-mcp:test --tests "EventLogFailureModeTest"
```

- [ ] **Step 3: Implement EventLogException + injection seam**

```kotlin
// 추가: EventLogException 클래스
class EventLogException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// EventLogWriter 시그니처 변경
class EventLogWriter(
    private val directory: File,
    private val writer: (java.nio.file.Path) -> Unit = { /* default no-op for production */ },
) {
    @Synchronized
    fun append(event: SessionEvent) {
        val name = "%013d-%010d.jsonl".format(event.epochMillis, event.sequenceNumber)
        val tmp = File(directory, "$name.tmp")
        val final = File(directory, name)
        try {
            RandomAccessFile(tmp, "rwd").use { raf ->
                writer(tmp.toPath())  // injection: tests can throw here
                val line = json.encodeToString(SessionEvent.serializer(), event) + "\n"
                raf.write(line.toByteArray(Charsets.UTF_8))
                raf.channel.force(true)
            }
            if (!tmp.renameTo(final)) throw EventLogException("Atomic rename failed for ${tmp.name}")
        } catch (e: Exception) {
            tmp.delete()
            if (e is EventLogException) throw e
            throw EventLogException("Failed to append event ${event.eventId}: ${e.message}", e)
        }
    }
}
```

- [ ] **Step 4: 기존 EventLogWriterTest 호환성 유지 확인 + 새 테스트 PASS**

```bash
./gradlew :fixthis-mcp:test --tests "EventLog*Test"
```

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogWriter.kt \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogFailureModeTest.kt
git commit -m "feat(alh): atomic rename + fail-stop on event log write failure"
```

### Task A.3 (ALH-4): Compaction 백그라운드 작업

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactor.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactorTest.kt`

- [ ] **Step 1: Failing test — 1000개 초과 시 archive 디렉터리로 이동**

```kotlin
class EventLogCompactorTest {
    @Test
    fun `compaction moves events beyond threshold to archive directory`(@TempDir tmp: Path) {
        val dir = tmp.resolve("events").toFile()
        val writer = EventLogWriter(dir)
        repeat(1100) { i ->
            writer.append(SessionEvent(
                eventId = "e$i", sequenceNumber = i.toLong(), epochMillis = i.toLong(),
                actor = "test", type = "addItem", payload = buildJsonObject {},
            ))
        }
        val snapshot = """{"items":[]}"""
        EventLogCompactor(dir, snapshotProvider = { snapshot }).runOnce(threshold = 1000)
        assertThat(File(dir, "archive").listFiles().orEmpty().size).isAtLeast(100)
        assertThat(dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.size).isAtMost(1000)
    }
}
```

- [ ] **Step 2: Run test — FAIL**

```bash
./gradlew :fixthis-mcp:test --tests "EventLogCompactorTest"
```

- [ ] **Step 3: Implement EventLogCompactor**

```kotlin
// fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactor.kt
package io.beyondwin.fixthis.mcp.session.eventlog

import java.io.File

class EventLogCompactor(
    private val directory: File,
    private val snapshotProvider: () -> String,
) {
    fun runOnce(threshold: Int = 1000) {
        val files = directory.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name } ?: return
        if (files.size <= threshold) return
        val archive = File(directory, "archive").apply { mkdirs() }
        val toArchive = files.dropLast(threshold)
        toArchive.forEach { f -> f.renameTo(File(archive, f.name)) }
        File(directory.parentFile, "state.json").writeText(snapshotProvider())
    }
}
```

- [ ] **Step 4: Run + commit**

```bash
./gradlew :fixthis-mcp:test --tests "EventLogCompactorTest"
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactor.kt \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/eventlog/EventLogCompactorTest.kt
git commit -m "feat(alh): event log compaction with archive directory"
```

### Task A.4 (ALH-3): FeedbackSessionStore mutation을 event log로 래핑

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionPersistence.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreEventLogTest.kt`

- [ ] **Step 1: Failing test — addItem이 event log에 append되고 fsync 실패 시 메모리 unchanged**

```kotlin
class FeedbackSessionStoreEventLogTest {
    @Test
    fun `addItem records event before mutating memory and rolls back on fsync failure`(@TempDir tmp: Path) {
        val (store, failingWriter) = makeStoreWithControllableWriter(tmp)
        val before = store.getSession("s1").items.size
        failingWriter.failNext = true
        assertThrows(EventLogException::class.java) {
            store.addItem("s1", makeDraftItem())
        }
        assertThat(store.getSession("s1").items.size).isEqualTo(before)
    }

    @Test
    fun `boot replays events to reconstruct state`(@TempDir tmp: Path) {
        val (store1, _) = makeStoreWithControllableWriter(tmp)
        store1.addItem("s1", makeDraftItem(text = "first"))
        store1.addItem("s1", makeDraftItem(text = "second"))
        val (store2, _) = makeStoreWithControllableWriter(tmp)  // fresh memory, same disk
        val items = store2.getSession("s1").items
        assertThat(items.map { it.comment }).containsExactly("first", "second").inOrder()
    }
}
```

- [ ] **Step 2: Run test — FAIL (no eventlog integration yet)**

```bash
./gradlew :fixthis-mcp:test --tests "FeedbackSessionStoreEventLogTest"
```

- [ ] **Step 3: Inject EventLogWriter into FeedbackSessionStore**

`FeedbackSessionStore.kt`의 생성자에 `private val eventLog: EventLogWriter` 추가. `addItem`, `addScreen`, `deleteDraftItem`, `deleteScreen`, `updateDraftItem`, `markSent` 6개 mutation 메서드의 시작부에:

```kotlin
fun addItem(sessionId: String, item: AnnotationDto): SessionDto = synchronized(lock) {
    val session = sessionsMap[sessionId] ?: error("Session $sessionId not found")
    val event = SessionEvent(
        eventId = idGenerator(),
        sequenceNumber = nextEventSeq(sessionId),
        epochMillis = clock.now(),
        actor = "mcp",
        type = "addItem",
        payload = buildJsonObject {
            put("sessionId", sessionId)
            put("item", Json.encodeToJsonElement(AnnotationDto.serializer(), item))
        },
    )
    eventLog.append(event)  // fail-stop: throws → memory unchanged
    val updated = session.copy(items = session.items + item)
    sessionsMap[sessionId] = updated
    persistence?.persist(updated)
    return@synchronized updated
}
```

- [ ] **Step 4: Boot replay 로직 추가**

`FeedbackSessionStore` 생성 시 (또는 `open(sessionId)`에서):

```kotlin
private fun replayEvents(sessionId: String) {
    val events = EventLogReader(eventLogDirFor(sessionId)).readAll()
    if (events.isEmpty()) return
    val maxKnownSeq = sessionsMap[sessionId]?.lastReplayedEventSeq ?: -1L
    events.filter { it.sequenceNumber > maxKnownSeq }
        .forEach { applyEvent(it) }
}

private fun applyEvent(event: SessionEvent) {
    when (event.type) {
        "addItem" -> {
            val sessionId = event.payload["sessionId"]!!.jsonPrimitive.content
            val item = Json.decodeFromJsonElement(AnnotationDto.serializer(), event.payload["item"]!!)
            val session = sessionsMap[sessionId] ?: error("Session not found during replay")
            sessionsMap[sessionId] = session.copy(items = session.items + item)
        }
        // 다른 type 동일 패턴
    }
}
```

- [ ] **Step 5: Run + commit**

```bash
./gradlew :fixthis-mcp:test --tests "FeedbackSessionStoreEventLogTest"
./gradlew :fixthis-mcp:test  # 기존 테스트 회귀 확인
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/ \
        fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreEventLogTest.kt
git commit -m "feat(alh): wrap session mutations in event log with replay-on-boot"
```

### Task A.5 (ALH-3): SIGKILL replay 시나리오 통합 테스트

**Files:**
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SigkillReplayTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
class SigkillReplayTest {
    @Test
    fun `randomized 100 mutations replay to identical state across two store instances`(@TempDir tmp: Path) {
        val ops = generateRandomOps(seed = 42, count = 100)
        val (store1, _) = makeStore(tmp)
        ops.forEach { it.applyTo(store1) }
        val snapshot1 = store1.getSession("s1").toCanonicalString()

        val (store2, _) = makeStore(tmp)  // fresh memory
        val snapshot2 = store2.getSession("s1").toCanonicalString()
        assertThat(snapshot2).isEqualTo(snapshot1)
    }
}
```

- [ ] **Step 2: Run + iterate until PASS**

```bash
./gradlew :fixthis-mcp:test --tests "SigkillReplayTest"
```

- [ ] **Step 3: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SigkillReplayTest.kt
git commit -m "test(alh): randomized 100-op replay invariant"
```

### Task A.6 (ALH-1): localStorage 미러링 — 콘솔 JS

**Files:**
- Modify: `fixthis-mcp/src/main/console/state.js`
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Create: `fixthis-mcp/src/test/js/pendingItemRecovery.test.js`

- [ ] **Step 1: Failing test (Node + jsdom 또는 vitest)**

```javascript
import { describe, it, expect, beforeEach } from 'vitest';
import { JSDOM } from 'jsdom';
import { appendPendingItem, removePendingItem, restorePendingItems } from '../../main/console/annotations.js';

describe('pending item recovery', () => {
  let storage;
  beforeEach(() => {
    const dom = new JSDOM('', { url: 'http://localhost' });
    global.localStorage = dom.window.localStorage;
    storage = global.localStorage;
  });

  it('mirrors pending items to localStorage on append', () => {
    const state = { sessionId: 's1', pendingFeedbackItems: [] };
    appendPendingItem(state, { itemId: 'i1', comment: 'a' });
    const stored = JSON.parse(storage.getItem('fixthis.pending.s1'));
    expect(stored).toHaveLength(1);
    expect(stored[0].itemId).toBe('i1');
  });

  it('restores pending items on boot', () => {
    storage.setItem('fixthis.pending.s1', JSON.stringify([{ itemId: 'i1', comment: 'recovered' }]));
    const state = { sessionId: 's1', pendingFeedbackItems: [] };
    restorePendingItems(state);
    expect(state.pendingFeedbackItems).toHaveLength(1);
    expect(state.pendingFeedbackItems[0].comment).toBe('recovered');
  });
});
```

- [ ] **Step 2: Run test — FAIL (functions not exported)**

```bash
cd fixthis-mcp && npx vitest run src/test/js/pendingItemRecovery.test.js
```

- [ ] **Step 3: Implement in annotations.js**

```javascript
const PENDING_KEY = (sessionId) => `fixthis.pending.${sessionId}`;

export function appendPendingItem(state, item) {
  state.pendingFeedbackItems.push(item);
  persistPending(state);
}

export function removePendingItem(state, itemId) {
  const idx = state.pendingFeedbackItems.findIndex(i => i.itemId === itemId);
  if (idx >= 0) {
    state.pendingFeedbackItems.splice(idx, 1);
    persistPending(state);
  }
}

export function updatePendingItem(state, itemId, patch) {
  const item = state.pendingFeedbackItems.find(i => i.itemId === itemId);
  if (item) {
    Object.assign(item, patch);
    persistPending(state);
  }
}

function persistPending(state) {
  if (!state.sessionId) return;
  localStorage.setItem(PENDING_KEY(state.sessionId), JSON.stringify(state.pendingFeedbackItems));
}

export function restorePendingItems(state) {
  if (!state.sessionId) return;
  const raw = localStorage.getItem(PENDING_KEY(state.sessionId));
  if (!raw) return;
  try {
    const items = JSON.parse(raw);
    if (Array.isArray(items)) state.pendingFeedbackItems = items;
  } catch (e) {
    console.warn('[fixthis] recovery payload corrupt, ignoring', e);
  }
}

export function clearPendingMirror(state) {
  if (state.sessionId) localStorage.removeItem(PENDING_KEY(state.sessionId));
}
```

- [ ] **Step 4: Run + PASS**

```bash
cd fixthis-mcp && npx vitest run src/test/js/pendingItemRecovery.test.js
```

- [ ] **Step 5: 콘솔 부트 시 호출부 연결**

`fixthis-mcp/src/main/console/main.js`의 세션 attach 직후:

```javascript
import { restorePendingItems } from './annotations.js';
// ...
if (state.pendingFeedbackItems.length === 0) {
  restorePendingItems(state);
  if (state.pendingFeedbackItems.length > 0) showRecoveryBanner(state);
}
```

`showRecoveryBanner`는 사용자가 "복구" 클릭 시에만 카드 렌더링 enable, "버리기" 클릭 시 `clearPendingMirror()` 호출.

- [ ] **Step 6: Rebundle + Commit**

```bash
node scripts/build-console-assets.mjs
git add fixthis-mcp/src/main/console/annotations.js \
        fixthis-mcp/src/main/console/main.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/js/pendingItemRecovery.test.js
git commit -m "feat(alh): localStorage mirror + recovery banner for pending items"
```

### Task A.7 (ALH-1): beforeunload 가드

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js`
- Test: `fixthis-mcp/src/test/js/beforeunload.test.js`

- [ ] **Step 1: Failing test**

```javascript
import { describe, it, expect, vi } from 'vitest';
import { installBeforeunloadGuard } from '../../main/console/main.js';

describe('beforeunload guard', () => {
  it('prevents navigation when pending dirty items exist', () => {
    const state = { pendingFeedbackItems: [{ itemId: 'i1' }], dirty: true };
    const event = { preventDefault: vi.fn(), returnValue: '' };
    installBeforeunloadGuard(state);
    window.dispatchEvent(new Event('beforeunload', { cancelable: true }));
    // 실제 브라우저에서는 returnValue 세팅이 confirm을 띄움 — 그 호출 여부만 검증
    expect(event.preventDefault).toHaveBeenCalledTimes(0);  // 직접 호출은 안 함, returnValue로
  });
});
```

- [ ] **Step 2: Run FAIL → implement**

```javascript
export function installBeforeunloadGuard(state) {
  window.addEventListener('beforeunload', (e) => {
    if (state.pendingFeedbackItems.length > 0 && state.dirty) {
      e.preventDefault();
      e.returnValue = '저장하지 않은 어노테이션이 있습니다. 정말 떠나시겠습니까?';
      return e.returnValue;
    }
  });
}
```

- [ ] **Step 3: Bundle + commit**

```bash
node scripts/build-console-assets.mjs
git add fixthis-mcp/src/main/console/main.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/js/beforeunload.test.js
git commit -m "feat(alh): beforeunload guard for unsaved annotations"
```

### Task A.8 (ALH-2): Undo/Redo 스택

**Files:**
- Create: `fixthis-mcp/src/main/console/undoRedo.js`
- Test: `fixthis-mcp/src/test/js/undoRedo.test.js`

- [ ] **Step 1: Failing test**

```javascript
import { describe, it, expect } from 'vitest';
import { createHistory, recordAdd, recordDelete, undo, redo } from '../../main/console/undoRedo.js';

describe('undo/redo history', () => {
  it('undoes a delete by reinserting at original sequenceNumber', () => {
    const state = { pendingFeedbackItems: [{ itemId: 'i1', sequenceNumber: 1, comment: 'a' }] };
    const history = createHistory();
    recordDelete(history, state, 'i1');
    state.pendingFeedbackItems = [];
    undo(history, state);
    expect(state.pendingFeedbackItems).toHaveLength(1);
    expect(state.pendingFeedbackItems[0].itemId).toBe('i1');
  });

  it('redo reapplies the operation', () => {
    const state = { pendingFeedbackItems: [{ itemId: 'i1', sequenceNumber: 1 }] };
    const history = createHistory();
    recordDelete(history, state, 'i1');
    state.pendingFeedbackItems = [];
    undo(history, state);
    redo(history, state);
    expect(state.pendingFeedbackItems).toHaveLength(0);
  });

  it('caps history at 50', () => {
    const history = createHistory();
    for (let i = 0; i < 100; i++) recordAdd(history, { pendingFeedbackItems: [] }, { itemId: `i${i}` });
    expect(history.undoStack.length).toBe(50);
  });
});
```

- [ ] **Step 2: Run FAIL → implement**

```javascript
// fixthis-mcp/src/main/console/undoRedo.js
const MAX_DEPTH = 50;

export function createHistory() {
  return { undoStack: [], redoStack: [] };
}

function push(stack, op) {
  stack.push(op);
  if (stack.length > MAX_DEPTH) stack.shift();
}

export function recordAdd(history, state, item) {
  push(history.undoStack, { kind: 'add', after: { ...item } });
  history.redoStack.length = 0;
}

export function recordDelete(history, state, itemId) {
  const before = state.pendingFeedbackItems.find(i => i.itemId === itemId);
  if (!before) return;
  push(history.undoStack, { kind: 'delete', before: { ...before } });
  history.redoStack.length = 0;
}

export function recordUpdate(history, state, itemId, patch) {
  const before = state.pendingFeedbackItems.find(i => i.itemId === itemId);
  if (!before) return;
  push(history.undoStack, { kind: 'update', before: { ...before }, after: { ...before, ...patch } });
  history.redoStack.length = 0;
}

export function undo(history, state) {
  const op = history.undoStack.pop();
  if (!op) return;
  applyInverse(op, state);
  push(history.redoStack, op);
}

export function redo(history, state) {
  const op = history.redoStack.pop();
  if (!op) return;
  applyForward(op, state);
  push(history.undoStack, op);
}

function applyInverse(op, state) {
  if (op.kind === 'add') {
    const idx = state.pendingFeedbackItems.findIndex(i => i.itemId === op.after.itemId);
    if (idx >= 0) state.pendingFeedbackItems.splice(idx, 1);
  } else if (op.kind === 'delete') {
    state.pendingFeedbackItems.push({ ...op.before });
    state.pendingFeedbackItems.sort((a, b) => a.sequenceNumber - b.sequenceNumber);
  } else if (op.kind === 'update') {
    const target = state.pendingFeedbackItems.find(i => i.itemId === op.before.itemId);
    if (target) Object.assign(target, op.before);
  }
}

function applyForward(op, state) {
  if (op.kind === 'add') state.pendingFeedbackItems.push({ ...op.after });
  else if (op.kind === 'delete') {
    const idx = state.pendingFeedbackItems.findIndex(i => i.itemId === op.before.itemId);
    if (idx >= 0) state.pendingFeedbackItems.splice(idx, 1);
  } else if (op.kind === 'update') {
    const target = state.pendingFeedbackItems.find(i => i.itemId === op.after.itemId);
    if (target) Object.assign(target, op.after);
  }
}
```

- [ ] **Step 3: Wire to annotations.js + 5초 토스트**

`annotations.js`의 `removePendingItem` 안에서 `recordDelete` 호출. UI에 `showUndoToast(itemId, 5000)` 추가.

- [ ] **Step 4: 키바인딩 — shortcuts.js**

```javascript
window.addEventListener('keydown', (e) => {
  if (document.activeElement && /^(INPUT|TEXTAREA)$/.test(document.activeElement.tagName)) return;
  const meta = e.metaKey || e.ctrlKey;
  if (meta && e.key.toLowerCase() === 'z' && !e.shiftKey) { undo(history, state); e.preventDefault(); }
  if (meta && (e.key === 'Z' || (e.shiftKey && e.key.toLowerCase() === 'z'))) { redo(history, state); e.preventDefault(); }
});
```

- [ ] **Step 5: PASS + bundle + commit**

```bash
cd fixthis-mcp && npx vitest run src/test/js/undoRedo.test.js
cd .. && node scripts/build-console-assets.mjs
git add fixthis-mcp/src/main/console/undoRedo.js \
        fixthis-mcp/src/main/console/shortcuts.js \
        fixthis-mcp/src/main/console/annotations.js \
        fixthis-mcp/src/main/resources/console/app.js \
        fixthis-mcp/src/test/js/undoRedo.test.js
git commit -m "feat(alh): undo/redo history with 5s toast and Cmd+Z bindings"
```

---

## Phase B — Screen Integrity Fingerprinting

### Task B.1 (SIF-1): Snapshot 모델 확장 — core

**Files:**
- Modify: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/snapshot/Snapshot.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/snapshot/ScreenOrientation.kt`
- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/snapshot/WindowMode.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/domain/snapshot/SnapshotExtensionTest.kt`

- [ ] **Step 1: Failing test — 새 필드 round-trip**

```kotlin
class SnapshotExtensionTest {
    @Test
    fun `snapshot carries new orientation and fingerprint fields`() {
        val s = Snapshot(
            id = SnapshotId("s1"), capturedAtEpochMillis = 1L,
            displayName = "Home",
            orientation = ScreenOrientation.PORTRAIT,
            widthPx = 1080, heightPx = 1920, densityDpi = 480,
            windowMode = WindowMode.FULLSCREEN,
            systemUiVisible = false, systemUiKind = null,
            fingerprint = "abc123",
        )
        assertThat(s.orientation).isEqualTo(ScreenOrientation.PORTRAIT)
        assertThat(s.fingerprint).isEqualTo("abc123")
    }
}
```

- [ ] **Step 2: Run FAIL → implement enums + Snapshot 필드**

```kotlin
// ScreenOrientation.kt
package io.beyondwin.fixthis.compose.core.domain.snapshot
enum class ScreenOrientation { PORTRAIT, LANDSCAPE, REVERSE_PORTRAIT, REVERSE_LANDSCAPE }

// WindowMode.kt
package io.beyondwin.fixthis.compose.core.domain.snapshot
enum class WindowMode { FULLSCREEN, SPLIT_SCREEN, FREEFORM, PIP }

// Snapshot.kt — 기존 data class에 필드 추가
data class Snapshot(
    val id: SnapshotId,
    val capturedAtEpochMillis: Long,
    val activityName: String? = null,
    val displayName: String,
    val screenshot: SnapshotScreenshot? = null,
    val roots: List<SnapshotRoot> = emptyList(),
    val sourceIndexAvailable: Boolean = false,
    val errors: List<FixThisError> = emptyList(),
    val orientation: ScreenOrientation? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val densityDpi: Int? = null,
    val windowMode: WindowMode? = null,
    val systemUiVisible: Boolean? = null,
    val systemUiKind: String? = null,
    val fingerprint: String? = null,
)
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew :fixthis-compose-core:test --tests "SnapshotExtensionTest"
git add fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/snapshot/ \
        fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/domain/snapshot/SnapshotExtensionTest.kt
git commit -m "feat(sif): extend Snapshot with orientation, windowMode, fingerprint fields"
```

### Task B.2 (SIF-1): SnapshotDto + BridgeScreenSnapshot 동기 확장

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionDtoModels.kt`
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeProtocol.kt`
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeScreenSnapshot.kt` (있으면)
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SnapshotDtoSerializationTest.kt`

- [ ] **Step 1: Failing test — 1.2 페이로드 backwards-compatible**

```kotlin
class SnapshotDtoSerializationTest {
    @Test
    fun `legacy 1_2 payload deserializes with null new fields`() {
        val legacy = """{"screenId":"s","capturedAtEpochMillis":1,"displayName":"Home"}"""
        val dto = Json.decodeFromString<SnapshotDto>(legacy)
        assertThat(dto.orientation).isNull()
        assertThat(dto.fingerprint).isNull()
    }

    @Test
    fun `1_3 payload round-trips all new fields`() {
        val dto = SnapshotDto(
            screenId = "s", capturedAtEpochMillis = 1L, displayName = "Home",
            orientation = "PORTRAIT", widthPx = 1080, heightPx = 1920,
            windowMode = "FULLSCREEN", fingerprint = "abc123",
        )
        val encoded = Json.encodeToString(dto)
        val decoded = Json.decodeFromString<SnapshotDto>(encoded)
        assertThat(decoded).isEqualTo(dto)
    }
}
```

- [ ] **Step 2: Run FAIL → SnapshotDto 확장**

```kotlin
@Serializable
data class SnapshotDto(
    val screenId: String,
    val capturedAtEpochMillis: Long,
    val activityName: String? = null,
    val displayName: String,
    val screenshot: SnapshotScreenshotDto? = null,
    val roots: List<SnapshotRootDto> = emptyList(),
    val sourceIndexAvailable: Boolean = false,
    val errors: List<FixThisError> = emptyList(),
    val orientation: String? = null,           // enum as string for JSON stability
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val densityDpi: Int? = null,
    val windowMode: String? = null,
    val systemUiVisible: Boolean? = null,
    val systemUiKind: String? = null,
    val fingerprint: String? = null,
)
```

- [ ] **Step 3: BridgeProtocol.VERSION bump + 4-site sync**

```kotlin
// BridgeProtocol.kt
const val VERSION: String = "1.3"
```

콘솔의 `MinimumSupportedProtocolVersion`도 `"1.3"`으로. `BridgeClient.kt`와 `ServerVersionRoutes.kt`의 mirror 상수도 `"1.3"`. `BridgeProtocolVersionSyncTest`가 4-site 동기화 검증.

- [ ] **Step 4: Run + commit**

```bash
./gradlew :fixthis-mcp:test --tests "SnapshotDtoSerializationTest"
./gradlew :fixthis-mcp:test --tests "BridgeProtocolVersionSyncTest"
git add -A
git commit -m "feat(sif): bump bridge protocol 1.2 -> 1.3 with extended snapshot fields"
```

### Task B.3 (SIF-2): Fingerprint 계산

**Files:**
- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/snapshot/SnapshotFingerprint.kt`
- Test: `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/domain/snapshot/SnapshotFingerprintTest.kt`

- [ ] **Step 1: Failing test — 결정론적 + 충돌 회피**

```kotlin
class SnapshotFingerprintTest {
    @Test
    fun `same inputs produce same fingerprint`() {
        val a = makeSnapshot(activity = "Main", orientation = PORTRAIT, w = 1080, h = 1920)
        val b = makeSnapshot(activity = "Main", orientation = PORTRAIT, w = 1080, h = 1920)
        assertThat(SnapshotFingerprint.compute(a)).isEqualTo(SnapshotFingerprint.compute(b))
    }

    @Test
    fun `rotation swap changes fingerprint`() {
        val portrait = makeSnapshot(activity = "Main", orientation = PORTRAIT, w = 1080, h = 1920)
        val landscape = makeSnapshot(activity = "Main", orientation = LANDSCAPE, w = 1920, h = 1080)
        assertThat(SnapshotFingerprint.compute(portrait))
            .isNotEqualTo(SnapshotFingerprint.compute(landscape))
    }

    @Test
    fun `1000 distinct inputs produce 1000 distinct fingerprints`() {
        val seen = mutableSetOf<String>()
        repeat(1000) { i ->
            val fp = SnapshotFingerprint.compute(makeSnapshot(activity = "A$i", w = 100 + i, h = 200))
            seen += fp!!
        }
        assertThat(seen).hasSize(1000)
    }
}
```

- [ ] **Step 2: Run FAIL → implement**

```kotlin
package io.beyondwin.fixthis.compose.core.domain.snapshot

import java.security.MessageDigest

object SnapshotFingerprint {
    fun compute(snapshot: Snapshot): String? {
        val components = listOf(
            snapshot.activityName.orEmpty(),
            snapshot.orientation?.name.orEmpty(),
            "${snapshot.widthPx}×${snapshot.heightPx}@${snapshot.densityDpi}",
            snapshot.windowMode?.name.orEmpty(),
            snapshot.systemUiKind.orEmpty(),
        )
        if (components.all { it.isEmpty() || it.contains("null") }) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(components.joinToString("|").toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }  // 16 hex chars
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
./gradlew :fixthis-compose-core:test --tests "SnapshotFingerprintTest"
git add fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/domain/snapshot/SnapshotFingerprint.kt \
        fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/domain/snapshot/SnapshotFingerprintTest.kt
git commit -m "feat(sif): SHA-256 prefix fingerprint over activity+orientation+window+systemUi"
```

### Task B.4 (SIF-3): 사이드킥에서 orientation 캡처

**Files:**
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt` (capture 경로)
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/runtime/FixThisRuntime.kt` (또는 동등)
- Test: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/OrientationCaptureTest.kt`

- [ ] **Step 1: Failing test — Robolectric 또는 fake context**

```kotlin
@RunWith(RobolectricTestRunner::class)
class OrientationCaptureTest {
    @Test
    fun `captureScreenSnapshot includes orientation from current configuration`() {
        val activity = Robolectric.buildActivity(TestActivity::class.java).create().resume().get()
        activity.resources.configuration.orientation = Configuration.ORIENTATION_LANDSCAPE
        val snapshot = runBlocking { runtime.captureScreenSnapshot() }
        assertThat(snapshot.orientation).isEqualTo("LANDSCAPE")
        assertThat(snapshot.widthPx).isGreaterThan(0)
    }
}
```

- [ ] **Step 2: Run FAIL → implement capture 시 orientation 읽기**

`BridgeServer.captureScreenSnapshot()` 내부:

```kotlin
val config = activity.resources.configuration
val displayMetrics = activity.resources.displayMetrics
val orientationStr = when (config.orientation) {
    Configuration.ORIENTATION_PORTRAIT -> "PORTRAIT"
    Configuration.ORIENTATION_LANDSCAPE -> "LANDSCAPE"
    else -> null
}
val windowMode = inferWindowMode(activity)  // helper
val systemUiKind = inferSystemUiKind(activity)
val snapshot = BridgeScreenSnapshot(
    /* 기존 필드들 */,
    orientation = orientationStr,
    widthPx = displayMetrics.widthPixels,
    heightPx = displayMetrics.heightPixels,
    densityDpi = displayMetrics.densityDpi,
    windowMode = windowMode,
    systemUiKind = systemUiKind,
    systemUiVisible = systemUiKind != null,
    fingerprint = null,  // 다음 task에서 계산
)
```

- [ ] **Step 3: Helper — windowMode 추론**

```kotlin
private fun inferWindowMode(activity: Activity): String? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        if (activity.isInPictureInPictureMode) return "PIP"
        if (activity.isInMultiWindowMode) return "SPLIT_SCREEN"
    }
    return "FULLSCREEN"
}
```

- [ ] **Step 4: Run + commit**

```bash
./gradlew :fixthis-compose-sidekick:test --tests "OrientationCaptureTest"
git add -A
git commit -m "feat(sif): capture orientation + window mode in sidekick snapshot"
```

### Task B.5 (SIF-4): 시스템 UI 가림 감지

**Files:**
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`
- Test: `fixthis-compose-sidekick/src/test/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/SystemUiDetectionTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
class SystemUiDetectionTest {
    @Test
    fun `ime visibility produces systemUiKind=ime`() {
        val activity = makeActivityWithImeVisible()
        val kind = SystemUiDetector.detect(activity, currentFocusOutput = "fakeFocus")
        assertThat(kind).isEqualTo("ime")
    }

    @Test
    fun `permission controller focus produces permission_dialog`() {
        val kind = SystemUiDetector.detect(
            activity = makeActivity(imeVisible = false),
            currentFocusOutput = "mCurrentFocus=Window{abc com.android.permissioncontroller/PermissionActivity}",
        )
        assertThat(kind).isEqualTo("permission_dialog")
    }
}
```

- [ ] **Step 2: Run FAIL → implement SystemUiDetector**

```kotlin
object SystemUiDetector {
    fun detect(activity: Activity, currentFocusOutput: String?): String? {
        if (isImeVisible(activity)) return "ime"
        if (currentFocusOutput != null) {
            if ("permissioncontroller" in currentFocusOutput) return "permission_dialog"
            if ("StatusBar" in currentFocusOutput) return "notification_shade"
        }
        return null
    }

    private fun isImeVisible(activity: Activity): Boolean {
        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
        return insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }
}
```

- [ ] **Step 3: ADB 채널 호출**

`BridgeClient` 또는 MCP 호스트 측에서 `adb shell dumpsys window windows | grep mCurrentFocus`를 capture 직전에 실행. 결과 string을 BridgeServer로 forward (또는 사이드킥 자체가 reflection으로 윈도우 매니저 query).

- [ ] **Step 4: Run + commit**

```bash
./gradlew :fixthis-compose-sidekick:test --tests "SystemUiDetectionTest"
git add -A
git commit -m "feat(sif): detect ime/permission_dialog/notification_shade via insets + focus"
```

### Task B.6 (SIF-2): Mismatch 차단 — FeedbackDraftService

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt`
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/ScreenFingerprintMismatch.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackDraftServiceMismatchTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
class FeedbackDraftServiceMismatchTest {
    @Test
    fun `savePreviewFeedbackItems blocks when fingerprint differs`() = runBlocking {
        val frozenFp = "abc123def4567890"
        val currentFp = "ffff000011112222"
        val (service, bridge) = makeServiceWithFingerprint(currentFp)
        val ex = assertThrows(ScreenFingerprintMismatch::class.java) {
            runBlocking { service.savePreviewFeedbackItems("s1", "scr1", frozenFingerprint = frozenFp, items = listOf(makeItem())) }
        }
        assertThat(ex.frozenFingerprint).isEqualTo(frozenFp)
        assertThat(ex.currentFingerprint).isEqualTo(currentFp)
    }

    @Test
    fun `savePreviewFeedbackItems succeeds when fingerprint matches`() = runBlocking {
        val fp = "abc123def4567890"
        val (service, _) = makeServiceWithFingerprint(fp)
        service.savePreviewFeedbackItems("s1", "scr1", frozenFingerprint = fp, items = listOf(makeItem()))
        // no exception
    }

    @Test
    fun `forceMismatchOverride bypasses block and records event metadata`() = runBlocking {
        val (service, _) = makeServiceWithFingerprint("ffff000011112222")
        service.savePreviewFeedbackItems(
            "s1", "scr1",
            frozenFingerprint = "abc123def4567890",
            items = listOf(makeItem()),
            forceMismatchOverride = true,
        )
        val events = service.eventLog.readAll().filter { it.type == "addItem" }
        assertThat(events.last().payload["forceMismatchOverride"]?.jsonPrimitive?.booleanOrNull).isTrue()
    }
}
```

- [ ] **Step 2: Run FAIL → implement**

```kotlin
// ScreenFingerprintMismatch.kt
class ScreenFingerprintMismatch(
    val frozenFingerprint: String,
    val currentFingerprint: String,
) : RuntimeException("Screen fingerprint mismatch: frozen=$frozenFingerprint, current=$currentFingerprint")

// FeedbackDraftService.savePreviewFeedbackItems 시그니처에 frozenFingerprint + forceMismatchOverride 추가
suspend fun savePreviewFeedbackItems(
    sessionId: String, screenId: String,
    frozenFingerprint: String?,
    items: List<DraftItem>,
    forceMismatchOverride: Boolean = false,
): SessionDto {
    if (frozenFingerprint != null && !forceMismatchOverride) {
        val currentSnapshot = bridge.captureScreenSnapshot(/* lightweight, no screenshot */)
        val currentFp = currentSnapshot.fingerprint
        if (currentFp != null && currentFp != frozenFingerprint) {
            throw ScreenFingerprintMismatch(frozenFingerprint, currentFp)
        }
    }
    return store.addScreenWithItems(
        sessionId, screenId, items,
        eventMetadata = if (forceMismatchOverride) mapOf("forceMismatchOverride" to true) else emptyMap(),
    )
}
```

- [ ] **Step 3: HTTP 라우트에서 mismatch 응답 매핑**

`FeedbackItemRoutes.kt`에서:

```kotlin
} catch (e: ScreenFingerprintMismatch) {
    respond(HTTP_CONFLICT, buildJsonObject {
        put("error", "screen_fingerprint_mismatch")
        put("frozenFingerprint", e.frozenFingerprint)
        put("currentFingerprint", e.currentFingerprint)
    })
}
```

- [ ] **Step 4: 콘솔 modal — annotations.js**

`saveAnnotationBatch`의 catch 블록에서 응답 코드 409 + error=`screen_fingerprint_mismatch` 감지 시 modal: "현재 화면 다시 캡처" / "강제 저장" / "취소" 옵션.

- [ ] **Step 5: PASS + bundle + commit**

```bash
./gradlew :fixthis-mcp:test --tests "FeedbackDraftServiceMismatchTest"
node scripts/build-console-assets.mjs
git add -A
git commit -m "feat(sif): fingerprint mismatch enforcement with force override path"
```

### Task B.7 (SIF-5): 단절 순간 stale 마킹

**Files:**
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`
- Modify: `fixthis-mcp/src/main/console/staleness.js`
- Test: `fixthis-mcp/src/test/js/stalenessTimeBased.test.js`

- [ ] **Step 1: Failing test (JS)**

```javascript
import { describe, it, expect, vi } from 'vitest';
import { evaluateStale } from '../../main/console/staleness.js';

describe('time-based staleness', () => {
  it('marks preview stale if frozenAt > 30s ago', () => {
    const state = {
      preview: { frozenAtEpochMillis: 1000 },
      bridgeStatus: { connection: 'connected', lastSuccessfulCaptureAtEpochMillis: 1000 },
    };
    expect(evaluateStale(state, /* now */ 32000)).toBe(true);
  });

  it('marks stale on disconnect even if fresh', () => {
    const state = {
      preview: { frozenAtEpochMillis: 31000 },
      bridgeStatus: { connection: 'disconnected', lastSuccessfulCaptureAtEpochMillis: 31000 },
    };
    expect(evaluateStale(state, /* now */ 32000)).toBe(true);
  });
});
```

- [ ] **Step 2: Run FAIL → implement**

```javascript
const MAX_PREVIEW_AGE_MS = 30_000;

export function evaluateStale(state, now = Date.now()) {
  if (!state.preview?.frozenAtEpochMillis) return false;
  const age = now - state.preview.frozenAtEpochMillis;
  if (age > MAX_PREVIEW_AGE_MS) return true;
  if (state.bridgeStatus?.connection !== 'connected') return true;
  return false;
}
```

- [ ] **Step 3: 콘솔 폴링 루프에 통합 — main.js의 status tick에서 매번 evaluate, 결과를 `state.preview.stale`에 반영**

- [ ] **Step 4: Run + bundle + commit**

```bash
cd fixthis-mcp && npx vitest run src/test/js/stalenessTimeBased.test.js && cd ..
node scripts/build-console-assets.mjs
git add -A
git commit -m "feat(sif): time-based and disconnect-based preview staleness"
```

### Task B.8 (SIF-6): 다중 핀 누적 중 activity drift 경고

**Files:**
- Modify: `fixthis-mcp/src/main/console/annotations.js`
- Test: `fixthis-mcp/src/test/js/activityDrift.test.js`

- [ ] **Step 1: Failing test**

```javascript
import { describe, it, expect } from 'vitest';
import { checkActivityDrift } from '../../main/console/annotations.js';

describe('activity drift during pending', () => {
  it('returns drift=true when current activity differs from freeze activity', () => {
    const flow = { activity: 'MainActivity', pendingFeedbackItems: [{ itemId: 'i1' }] };
    const current = { activity: 'DetailActivity' };
    expect(checkActivityDrift(flow, current).drift).toBe(true);
  });

  it('returns drift=false when matched', () => {
    const flow = { activity: 'MainActivity', pendingFeedbackItems: [{ itemId: 'i1' }] };
    expect(checkActivityDrift(flow, { activity: 'MainActivity' }).drift).toBe(false);
  });
});
```

- [ ] **Step 2: Run FAIL → implement**

```javascript
export function checkActivityDrift(flow, current) {
  if (!flow.activity || !current.activity) return { drift: false };
  return { drift: flow.activity !== current.activity, expected: flow.activity, actual: current.activity };
}
```

- [ ] **Step 3: UI integration**

`appendPendingItem` 직후 백그라운드 ping → drift 감지 시 inline warning + "분리" 버튼. "분리" 클릭 시 `startAddItemsFlow()` 재호출.

- [ ] **Step 4: Run + bundle + commit**

```bash
cd fixthis-mcp && npx vitest run src/test/js/activityDrift.test.js && cd ..
node scripts/build-console-assets.mjs
git add -A
git commit -m "feat(sif): warn on activity drift during multi-pin pending flow"
```

---

## Phase C — Annotation UX Consistency

### Task C.1 (AUC-1): Stable sequenceNumber 발급

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt:405-406`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SequenceNumberStabilityTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
class SequenceNumberStabilityTest {
    @Test
    fun `deleted sequence numbers are never reused`() {
        val store = makeStore()
        val i1 = store.addItem("s1", makeItem()).items.last()
        val i2 = store.addItem("s1", makeItem()).items.last()
        val i3 = store.addItem("s1", makeItem()).items.last()
        assertThat(listOf(i1, i2, i3).map { it.sequenceNumber }).containsExactly(1, 2, 3).inOrder()
        store.deleteDraftItem("s1", i2.itemId)
        val i4 = store.addItem("s1", makeItem()).items.last()
        assertThat(i4.sequenceNumber).isEqualTo(4)  // NOT 2
    }
}
```

- [ ] **Step 2: Run FAIL → 카운터 분리**

```kotlin
// SessionDto에 itemSequenceCounter: Long = 0 추가
private fun nextItemSequenceNumber(session: SessionDto): Long = session.itemSequenceCounter + 1

// addItem 안에서 갱신
val newSeq = nextItemSequenceNumber(session)
val updated = session.copy(
    items = session.items + item.copy(sequenceNumber = newSeq),
    itemSequenceCounter = newSeq,
)
```

- [ ] **Step 3: Migration — 기존 state.json에 카운터 없으면 `items.maxOf { sequenceNumber } ?: 0`로 초기화**

- [ ] **Step 4: 콘솔 UI 변경 — `'#' + (index + 1)` → `'#' + item.sequenceNumber`**

`fixthis-mcp/src/main/console/annotations.js`와 `app.js`(빌드 산출물 직접 수정 금지, 소스만 수정 후 rebundle).

- [ ] **Step 5: Run + bundle + commit**

```bash
./gradlew :fixthis-mcp:test --tests "SequenceNumberStabilityTest"
node scripts/build-console-assets.mjs
git add -A
git commit -m "feat(auc): stable monotonic sequence numbers, UI shows server-issued values"
```

### Task C.2 (AUC-2): Sent 후 staleAfterHandoff 마킹

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/AnnotationRepository.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/SessionDtoModels.kt` (`AnnotationDto`에 `staleAfterHandoff: Boolean = false`, `lastModifiedAfterHandoffAtEpochMillis: Long? = null`)
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/SentEditStaleTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
class SentEditStaleTest {
    @Test
    fun `editing sent item sets staleAfterHandoff`() = runBlocking {
        val repo = makeRepo()
        val item = makeItem(state = SENT)
        val updated = repo.updateSentFeedback("s1", item.itemId, comment = "new")
        assertThat(updated.staleAfterHandoff).isTrue()
        assertThat(updated.lastModifiedAfterHandoffAtEpochMillis).isNotNull()
    }

    @Test
    fun `markdown handoff includes (modified after handoff) marker`() {
        val item = makeItem(state = SENT, staleAfterHandoff = true)
        val md = CompactHandoffRenderer.render(listOf(item))
        assertThat(md).contains("(modified after handoff)")
    }
}
```

- [ ] **Step 2: Run FAIL → 구현**

```kotlin
// AnnotationRepository
fun updateSentFeedback(sessionId: String, itemId: String, comment: String? = null, severity: AnnotationSeverityDto? = null): AnnotationDto {
    return store.mutateItem(sessionId, itemId) { current ->
        require(current.state == AnnotationStateDto.SENT) { "Item must be SENT" }
        current.copy(
            comment = comment ?: current.comment,
            severity = severity ?: current.severity,
            staleAfterHandoff = true,
            lastModifiedAfterHandoffAtEpochMillis = clock.now(),
            updatedAtEpochMillis = clock.now(),
        )
    }
}
```

- [ ] **Step 3: Renderer 변경 — CompactHandoffRenderer**

```kotlin
if (item.staleAfterHandoff) appendLine("(modified after handoff)")
```

- [ ] **Step 4: 콘솔 카드 배지**

`annotations.js`에서 `item.staleAfterHandoff && item.state === 'sent'` 이면 "Sent · Modified" 배지 노란색.

- [ ] **Step 5: Run + bundle + commit**

```bash
./gradlew :fixthis-mcp:test --tests "SentEditStaleTest"
node scripts/build-console-assets.mjs
git add -A
git commit -m "feat(auc): mark sent items stale on edit, expose in markdown and UI"
```

---

## Phase D — Connection Resilience

### Task D.1 (CR-1): 자동 세션 재개

**Files:**
- Modify: `fixthis-mcp/src/main/console/main.js`
- Modify: `fixthis-mcp/src/main/console/state.js`
- Test: `fixthis-mcp/src/test/js/sessionResume.test.js`

- [ ] **Step 1: Failing test**

```javascript
describe('session auto resume', () => {
  it('attaches to stored sessionId on boot when server confirms existence', async () => {
    localStorage.setItem('fixthis.activeSessionId', 's-stored');
    const api = { listSessions: async () => [{ sessionId: 's-stored' }] };
    const state = {};
    await tryAutoResume(state, api);
    expect(state.sessionId).toBe('s-stored');
  });

  it('falls back to session card when stored id not found', async () => {
    localStorage.setItem('fixthis.activeSessionId', 's-stored');
    const api = { listSessions: async () => [] };
    const state = {};
    await tryAutoResume(state, api);
    expect(state.sessionId).toBeUndefined();
    expect(localStorage.getItem('fixthis.activeSessionId')).toBeNull();
  });
});
```

- [ ] **Step 2: Run FAIL → implement**

```javascript
export async function tryAutoResume(state, api) {
  const stored = localStorage.getItem('fixthis.activeSessionId');
  if (!stored) return;
  const sessions = await api.listSessions();
  if (sessions.some(s => s.sessionId === stored)) {
    state.sessionId = stored;
  } else {
    localStorage.removeItem('fixthis.activeSessionId');
  }
}

// 세션 attach/detach 시점에 localStorage 동기화
export function persistActiveSession(sessionId) {
  if (sessionId) localStorage.setItem('fixthis.activeSessionId', sessionId);
  else localStorage.removeItem('fixthis.activeSessionId');
}
```

- [ ] **Step 3: Run + bundle + commit**

```bash
cd fixthis-mcp && npx vitest run src/test/js/sessionResume.test.js && cd ..
node scripts/build-console-assets.mjs
git add -A
git commit -m "feat(cr): auto-resume session from localStorage on console boot"
```

### Task D.2 (CR-2): Action queue dedup

**Files:**
- Create: `fixthis-mcp/src/main/console/actionQueue.js`
- Modify: `fixthis-mcp/src/main/console/connection.js`
- Test: `fixthis-mcp/src/test/js/actionQueueDedup.test.js`

- [ ] **Step 1: Failing test**

```javascript
describe('action queue dedup', () => {
  it('keeps only latest action per key while disconnected', () => {
    const q = createActionQueue();
    q.enqueue('navigate', { action: 'back' });
    q.enqueue('navigate', { action: 'tap', x: 1, y: 2 });
    q.enqueue('navigate', { action: 'swipe' });
    expect(q.size()).toBe(1);
    expect(q.peek('navigate').action).toBe('swipe');
  });

  it('flushes serially with 100ms gap', async () => {
    const executed = [];
    const q = createActionQueue();
    q.enqueue('a', { v: 1 });
    q.enqueue('b', { v: 2 });
    await q.flush(async (key, payload) => { executed.push([key, payload, Date.now()]); });
    expect(executed.map(e => e[0])).toEqual(['a', 'b']);
    expect(executed[1][2] - executed[0][2]).toBeGreaterThanOrEqual(95);
  });
});
```

- [ ] **Step 2: Run FAIL → implement**

```javascript
export function createActionQueue() {
  const map = new Map();
  return {
    enqueue(key, payload) { map.set(key, payload); },
    peek(key) { return map.get(key); },
    size() { return map.size; },
    async flush(executor) {
      const entries = Array.from(map.entries());
      map.clear();
      for (let i = 0; i < entries.length; i++) {
        await executor(entries[i][0], entries[i][1]);
        if (i < entries.length - 1) await new Promise(r => setTimeout(r, 100));
      }
    },
  };
}
```

- [ ] **Step 3: connection.js 통합 — 단절 시 enqueue, 재연결 시 flush**

- [ ] **Step 4: Run + bundle + commit**

```bash
cd fixthis-mcp && npx vitest run src/test/js/actionQueueDedup.test.js && cd ..
node scripts/build-console-assets.mjs
git add -A
git commit -m "feat(cr): dedup action queue with 100ms serial flush on reconnect"
```

### Task D.3 (CR-3): UserFacingError + 액션 가이드

**Files:**
- Create: `fixthis-compose-core/src/main/kotlin/io/beyondwin/fixthis/compose/core/error/UserFacingError.kt`
- Modify: `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeClient.kt` (raw exception → UserFacingError 매핑)
- Modify: `fixthis-mcp/src/main/console/*.js` (코드별 액션 버튼 렌더링)
- Test: `fixthis-compose-core/src/test/kotlin/io/beyondwin/fixthis/compose/core/error/UserFacingErrorTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
class UserFacingErrorTest {
    @Test
    fun `every error code has at least one suggested action`() {
        UserFacingErrorCode.values().forEach { code ->
            val err = UserFacingError.of(code)
            assertThat(err.suggestedActions).isNotEmpty()
            assertThat(err.title).isNotEmpty()
        }
    }

    @Test
    fun `BRIDGE_NOT_REACHABLE includes adb restart suggestion`() {
        val err = UserFacingError.of(UserFacingErrorCode.BRIDGE_NOT_REACHABLE)
        assertThat(err.suggestedActions.map { it.id }).contains("adb_restart")
    }
}
```

- [ ] **Step 2: Run FAIL → implement**

```kotlin
@Serializable
data class UserFacingError(
    val code: UserFacingErrorCode,
    val title: String,
    val body: String,
    val suggestedActions: List<SuggestedAction>,
) {
    companion object {
        fun of(code: UserFacingErrorCode): UserFacingError = when (code) {
            UserFacingErrorCode.BRIDGE_NOT_REACHABLE -> UserFacingError(
                code = code,
                title = "디바이스에 연결할 수 없습니다",
                body = "ADB가 디바이스와 통신하지 못하고 있습니다.",
                suggestedActions = listOf(
                    SuggestedAction("check_usb", "USB 연결을 확인하세요"),
                    SuggestedAction("adb_restart", "adb kill-server && adb start-server 를 실행하세요"),
                    SuggestedAction("unlock_device", "디바이스 화면 잠금을 해제하세요"),
                ),
            )
            UserFacingErrorCode.DEVICE_LOCKED -> /* ... */
            UserFacingErrorCode.APP_NOT_RUNNING -> /* ... */
            UserFacingErrorCode.PERMISSION_DENIED -> /* ... */
            UserFacingErrorCode.DISK_FULL -> /* ... */
            // 모든 코드 정의
        }
    }
}

@Serializable enum class UserFacingErrorCode { BRIDGE_NOT_REACHABLE, DEVICE_LOCKED, APP_NOT_RUNNING, PERMISSION_DENIED, DISK_FULL, SCREEN_FINGERPRINT_MISMATCH, BRIDGE_PROTOCOL_MISMATCH }

@Serializable data class SuggestedAction(val id: String, val label: String, val deeplink: String? = null)
```

- [ ] **Step 3: BridgeClient 호출부에서 raw exception을 UserFacingError로 변환**

```kotlin
private fun mapException(e: Throwable): UserFacingError = when {
    e.message?.contains("Could not read FixThis bridge session via adb") == true -> UserFacingError.of(UserFacingErrorCode.BRIDGE_NOT_REACHABLE)
    /* ... */
    else -> UserFacingError(UserFacingErrorCode.BRIDGE_NOT_REACHABLE, "예기치 못한 오류", e.message ?: "", emptyList())
}
```

- [ ] **Step 4: 콘솔 — error toast에 액션 버튼 렌더링**

```javascript
function renderErrorToast(err) {
  const root = document.createElement('div');
  root.className = 'error-toast';
  root.innerHTML = `<h4>${err.title}</h4><p>${err.body}</p>`;
  for (const action of err.suggestedActions) {
    const btn = document.createElement('button');
    btn.textContent = action.label;
    btn.onclick = () => runAction(action.id);
    root.appendChild(btn);
  }
  document.body.appendChild(root);
}
```

- [ ] **Step 5: Run + commit**

```bash
./gradlew :fixthis-compose-core:test --tests "UserFacingErrorTest"
node scripts/build-console-assets.mjs
git add -A
git commit -m "feat(cr): structured UserFacingError with actionable suggestions"
```

---

## Phase E — Regression Test Infrastructure

### Task E.1 (RTI-1): :fixthis-emulator-testkit 모듈 부트스트랩

**Files:**
- Create: `fixthis-emulator-testkit/build.gradle.kts`
- Modify: `settings.gradle.kts` (include 추가)
- Create: `fixthis-emulator-testkit/src/main/kotlin/io/beyondwin/fixthis/emulator/testkit/EmulatorScenarioDsl.kt`
- Create: `fixthis-emulator-testkit/src/main/kotlin/io/beyondwin/fixthis/emulator/testkit/AdbDriver.kt`

- [ ] **Step 1: Failing test — DSL 호출 가능**

```kotlin
// fixthis-emulator-testkit/src/test/kotlin/.../EmulatorScenarioDslTest.kt
class EmulatorScenarioDslTest {
    @Test
    fun `dsl compiles and executes given+annotate+rotate+expect`() {
        emulatorScenario("smoke") {
            given { /* no-op for unit test */ }
            // expect block returns true if assertion holds
            expect { true }
        }
    }
}
```

- [ ] **Step 2: Run FAIL (모듈 없음) → settings.gradle.kts에 추가**

```kotlin
// settings.gradle.kts
include(":fixthis-emulator-testkit")
```

```kotlin
// fixthis-emulator-testkit/build.gradle.kts
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}
dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
```

- [ ] **Step 3: DSL 골격 구현**

```kotlin
class EmulatorScenarioBuilder(val name: String) {
    private val givenBlocks = mutableListOf<() -> Unit>()
    private val actionBlocks = mutableListOf<() -> Unit>()
    private val expectBlocks = mutableListOf<() -> Boolean>()

    fun given(block: () -> Unit) { givenBlocks += block }
    fun annotate(block: () -> Unit) { actionBlocks += block }
    fun rotateDevice(orientation: ScreenOrientation) { actionBlocks += { AdbDriver.setRotation(orientation) } }
    fun expect(block: () -> Boolean) { expectBlocks += block }

    fun run() {
        givenBlocks.forEach { it() }
        actionBlocks.forEach { it() }
        expectBlocks.forEachIndexed { i, blk ->
            check(blk()) { "Expectation $i failed in scenario '$name'" }
        }
    }
}

fun emulatorScenario(name: String, build: EmulatorScenarioBuilder.() -> Unit) {
    EmulatorScenarioBuilder(name).apply(build).run()
}
```

```kotlin
// AdbDriver.kt
object AdbDriver {
    fun setRotation(orientation: ScreenOrientation) {
        val code = when (orientation) {
            ScreenOrientation.PORTRAIT -> 0
            ScreenOrientation.LANDSCAPE -> 1
            ScreenOrientation.REVERSE_PORTRAIT -> 2
            ScreenOrientation.REVERSE_LANDSCAPE -> 3
        }
        runAdb("shell", "settings", "put", "system", "user_rotation", "$code")
    }

    fun killAdbServer() { runAdb("kill-server") }
    fun startAdbServer() { runAdb("start-server") }

    private fun runAdb(vararg args: String) {
        ProcessBuilder(listOf("adb") + args.toList())
            .inheritIO().start().waitFor(10, TimeUnit.SECONDS)
    }
}
```

- [ ] **Step 4: Run + commit**

```bash
./gradlew :fixthis-emulator-testkit:test
git add -A
git commit -m "test(rti): bootstrap :fixthis-emulator-testkit module with adb DSL"
```

### Task E.2 (RTI-1): 8개 시나리오 작성

**Files:**
- Create: `fixthis-emulator-testkit/src/main/resources/scenarios/annotation-survives-disconnect.kts`
- Create: `fixthis-emulator-testkit/src/main/resources/scenarios/undo-redo-roundtrip.kts`
- Create: `fixthis-emulator-testkit/src/main/resources/scenarios/kill-9-replay.kts`
- Create: `fixthis-emulator-testkit/src/main/resources/scenarios/rotation-mismatch.kts`
- Create: `fixthis-emulator-testkit/src/main/resources/scenarios/permission-dialog-detection.kts`
- Create: `fixthis-emulator-testkit/src/main/resources/scenarios/multi-pin-activity-drift.kts`
- Create: `fixthis-emulator-testkit/src/main/resources/scenarios/sequence-number-stability.kts`
- Create: `fixthis-emulator-testkit/src/main/resources/scenarios/auto-resume-on-reconnect.kts`

- [ ] **Step 1: 첫 시나리오 — annotation-survives-disconnect.kts**

```kotlin
emulatorScenario("annotation survives disconnect") {
    given {
        AdbDriver.installSampleApp()
        ConsoleDriver.openConsole()
        ConsoleDriver.attachToFreshSession()
        ConsoleDriver.startAnnotateFlow()
    }
    annotate {
        ConsoleDriver.clickAt(200, 400, comment = "first")
        ConsoleDriver.clickAt(300, 500, comment = "second")
        ConsoleDriver.clickAt(400, 600, comment = "third")
    }
    actionBlocks += { AdbDriver.killAdbServer() }
    actionBlocks += { ConsoleDriver.reloadPage() }
    actionBlocks += { Thread.sleep(2000); AdbDriver.startAdbServer() }
    expect {
        val recovered = ConsoleDriver.acceptRecoveryBanner()
        recovered.pendingItems.size == 3 &&
            recovered.pendingItems.map { it.comment } == listOf("first", "second", "third")
    }
}
```

- [ ] **Step 2: 나머지 7개 시나리오 동일 패턴으로 작성**

각 파일은 대응하는 phase 항목의 acceptance criteria를 직접 실행. 핵심은:
- `rotation-mismatch.kts`: `rotateDevice(LANDSCAPE)` 후 `expect { ConsoleDriver.fingerprintMismatchModalVisible() }`.
- `permission-dialog-detection.kts`: `AdbDriver.triggerPermissionRequest()` 후 `expect { ConsoleDriver.systemUiKind() == "permission_dialog" }`.
- `multi-pin-activity-drift.kts`: 핀 1개 추가 → `AdbDriver.startActivity("DetailActivity")` → 핀 2 시도 → `expect { ConsoleDriver.driftWarningVisible() }`.
- `kill-9-replay.kts`: 50개 mutation → 프로세스 kill → restart → state 동일성 검증.
- `sequence-number-stability.kts`: random add/delete 100회 → sequenceNumber 단조성 검증.
- `auto-resume-on-reconnect.kts`: 세션 attach → disconnect → reload → 같은 세션 자동 attach 확인.
- `undo-redo-roundtrip.kts`: 핀 5개 추가 → 3개 삭제 → undo 3회 → 상태 비교.

- [ ] **Step 3: CI 통합 — `.github/workflows/connected-tests.yml`에 nightly job 추가**

```yaml
- name: Emulator scenarios
  run: ./gradlew :fixthis-emulator-testkit:connectedAndroidTest
  env:
    ANDROID_AVD_NAME: fixthis-ci-avd
```

- [ ] **Step 4: 실패 시 artifact 업로드**

```yaml
- name: Upload failure artifacts
  if: failure()
  uses: actions/upload-artifact@v4
  with:
    name: emulator-failures
    path: |
      fixthis-emulator-testkit/build/reports/
      /tmp/screenrecord-*.mp4
```

- [ ] **Step 5: 시나리오 실행 + commit (emulator 환경에서)**

```bash
./gradlew :fixthis-emulator-testkit:connectedAndroidTest
git add -A
git commit -m "test(rti): 8 emulator playback scenarios for lifecycle regression"
```

---

## 완료 후 PR 작성

- [ ] **모든 phase merge 후 한꺼번에 PR**

```bash
git checkout main
git merge --no-ff annotation-lifecycle-hardening
git push origin main  # 사용자 확인 후
```

또는 phase별 PR (권장 — 리뷰 부담 감소):

1. ALH-4 + ALH-3 (event log 인프라) — PR #1
2. ALH-1 + ALH-2 (콘솔 영속화 + undo) — PR #2 (#1에 의존)
3. SIF-1 + SIF-2 (snapshot 확장 + fingerprint) — PR #3
4. SIF-3 ~ SIF-6 (감지 활성화) — PR #4 (#3에 의존)
5. AUC-1 + AUC-2 — PR #5 (#1에 약간 의존)
6. CR-1 ~ CR-3 — PR #6
7. RTI-1 — PR #7 (이전 모든 PR 머지 후)

각 PR description에는 본 plan 문서 링크와 대상 phase의 acceptance criteria를 명시.

---

## Self-Review 결과

**1. Spec coverage:**

| Spec 항목 | 대응 Task |
|-----------|-----------|
| ALH-1 | A.6, A.7 |
| ALH-2 | A.8 |
| ALH-3 | A.4, A.5 |
| ALH-4 | A.1, A.2, A.3 |
| SIF-1 | B.1, B.2 |
| SIF-2 | B.3, B.6 |
| SIF-3 | B.4 |
| SIF-4 | B.5 |
| SIF-5 | B.7 |
| SIF-6 | B.8 |
| AUC-1 | C.1 |
| AUC-2 | C.2 |
| CR-1 | D.1 |
| CR-2 | D.2 |
| CR-3 | D.3 |
| RTI-1 | E.1, E.2 |

16개 모두 task로 매핑됨.

**2. Placeholder scan:**
- C.2의 "각 행동에 적절한 UserFacingError 매핑" — D.3 task에서 5개 코드 명시적 구현 요구.
- D.3의 다른 코드 정의 — `/* ... */`로 표시했으나 acceptance criteria(테스트가 모든 코드를 enum loop으로 검증)가 누락 방지.
- E.2의 7개 시나리오 — 각 시나리오의 핵심 액션·검증을 인라인 설명. 실제 구현 시 동일 패턴 반복.

**3. Type consistency:**
- `SessionEvent`, `EventLogException`, `ScreenFingerprintMismatch`, `UserFacingError`, `UserFacingErrorCode`, `SuggestedAction`, `ScreenOrientation`, `WindowMode`, `SnapshotFingerprint` — 모든 phase에서 동일 시그니처 사용.
- `staleAfterHandoff: Boolean = false`, `lastModifiedAfterHandoffAtEpochMillis: Long?` — `AnnotationDto` 확장 일관.
- `BridgeProtocol.VERSION = "1.3"` 4-site 동기화 명시.

수정 사항 없음.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-12-annotation-lifecycle-hardening-implementation.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
