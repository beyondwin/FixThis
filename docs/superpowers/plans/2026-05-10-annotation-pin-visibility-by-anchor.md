# Annotation Pin Visibility by Anchor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 라이브 프리뷰 폴링이나 화면 전환에도 어노테이션 핀이 "현재 캔버스에 그려진 스크린의 실제 anchor 가 살아있는가" 라는 단일 규칙으로 자연스럽게 표시되거나 사라지도록 한다. 한 세션 안에 여러 화면의 어노테이션이 섞여 있어도, 디바이스가 끊겼거나 stale 프레임이어도, 세션 사이를 오가도, 사용자에게 일관된 UX 가 되어야 한다.

**Architecture:** 모든 변경은 브라우저 측 콘솔 JS 에 한정된다. Kotlin/MCP 서버, 데이터 모델, persistence 는 손대지 않는다.

1. **per-pin visibility predicate**: `rendering.js`의 `renderSelectionOverlay()` 안에서 saved evidence 핀 필터를 `item.screenId === visibleScreen.screenId` 에서 **anchor-aware predicate** 로 교체한다.
   - `target.nodeUid` 가 있으면 → 현재 캔버스에 표시된 스크린의 노드 트리에 그 uid 가 살아있을 때만 표시.
   - `target.nodeUid` 가 없으면(visual_area) → 기존대로 `screenId` 일치할 때만 표시(area 는 anchor 가 없으므로 fallback).
2. **node-uid index helper**: 표시 대상 스크린의 `roots[].mergedNodes[].uid` + `roots[].unmergedNodes[].uid` 를 한 번 모아 `Set` 으로 만드는 작은 헬퍼를 `preview.js` 에 추가하고, 매 호출에서 재사용한다.
3. **bundle 동기화**: `node scripts/build-console-assets.mjs` 로 `fixthis-mcp/src/main/resources/console/app.js` 를 갱신해 `generatedConsoleAppMatchesConsoleSourceModules` 테스트가 통과하도록 한다.

**Tech Stack:** Vanilla JS ES module concatenation (no toolchain). Kotlin 1.9 + JUnit 5 (콘솔 자산 동등성 테스트만 영향). Node 20+ (bundling script).

---

## Conventions

- **테스트 러너:**
  - 콘솔 자산 동등성 + 라우팅: `./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest"`
  - 회귀 한 줄 (이 변경에는 충분):
    ```bash
    ./gradlew :fixthis-mcp:test
    node --check fixthis-mcp/src/main/resources/console/app.js
    ```
  - 더 넓은 회귀가 필요하면 `CLAUDE.md` 의 전체 테스트 명령 사용.
- **JS 번들 동기화**: `fixthis-mcp/src/main/console/*.js` 를 수정한 모든 태스크는 같은 커밋에서 `node scripts/build-console-assets.mjs` 를 실행해 `fixthis-mcp/src/main/resources/console/app.js` 까지 동시 업데이트해야 한다. `generatedConsoleAppMatchesConsoleSourceModules` 가 강제한다.
- **수동 검증 필수**: JS 단위 테스트 인프라가 없으므로 각 태스크 끝에 명시된 manual smoke 시나리오를 디바이스/에뮬레이터로 한 번 돌려본다.
- **커밋 스타일**: 소문자 scope, imperative, 한 태스크 = 한 커밋. 본문에는 어떤 케이스를 어떻게 다루는지 한 줄 정리.
- **모델/JSON 변경 없음**: 본 플랜 전체는 브라우저-only. 어노테이션 DTO, 프리뷰 DTO 의 와이어 포맷은 변경하지 않는다.

## File map

**Created:** 없음 (브라우저-only 변경, 새 파일 없이 기존 모듈을 좁게 수정).

**Modified:**

- `fixthis-mcp/src/main/console/preview.js` — `visibleScreenNodeUids(screen)` 헬퍼 추가
- `fixthis-mcp/src/main/console/rendering.js` — `renderSelectionOverlay()` 의 saved-evidence 필터를 anchor-aware predicate 로 교체
- `fixthis-mcp/src/main/resources/console/app.js` — 위 두 모듈 변경을 반영한 번들 (수동 편집 금지, `node scripts/build-console-assets.mjs` 결과 그대로)
- `CHANGELOG.md` — UX-fix 항목 한 줄 추가 (지금 동작과 새 동작 한 문장씩)
- `docs/superpowers/specs/2026-05-10-annotation-pin-visibility-by-anchor-implementation-details.md` — 함께 작성된 deep companion

**Touched but not edited (참고용 기준):**

- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt` — `generatedConsoleAppMatchesConsoleSourceModules` 가 자동으로 변경된 번들을 검증한다. 새 테스트는 추가하지 않는다(JS 로직 단위 테스트 인프라 없음).
- `fixthis-mcp/src/main/console/sessions-polling.js`, `annotations.js` — 이미 다른 이슈로 수정된 상태(코멘트 textarea 폴링 가드 등)에 의존하지만, 본 플랜에서 추가 수정은 하지 않는다.

---

## Background

### 현재 동작 (regression)

`renderSelectionOverlay()` 는 표시 대상 스크린(`latestScreen()`)의 `screenId` 와 일치하는 saved item 만 그린다.

```js
// rendering.js:73-79 (현재)
if (!addItemsFlow) {
  const visibleScreen = latestScreen();
  if (visibleScreen?.screenId) {
    const screenSavedItems = savedEvidenceItems()
      .filter(item => item.screenId === visibleScreen.screenId);
    if (screenSavedItems.length) renderSavedEvidenceOverlay(overlay, image, screenSavedItems);
  }
}
```

`latestScreen()` 은 라이브 프리뷰가 있으면 그것을 우선한다.

```js
// preview.js:85-95 (현재)
function latestScreen() {
  if (addItemsFlow) return addItemsFlow.screen;
  if (focusedSavedItemId) { ... 그 item 의 screen ... }
  return state.preview?.screen || latestPersistedScreen();
}
```

라이브 프리뷰는 매 캡처마다 새 `screenId` 를 부여받기 때문에, 사용자가 세션을 열면 1초 폴링 후 즉시 모든 핀이 사라지고, history sidebar 의 세션 행을 다시 클릭해야(=`openSession` → `invalidatePreviewContext()` → `state.preview = null`) persisted 화면과 함께 핀이 다시 등장한다.

### 왜 단순히 screenId 가드를 풀면 안 되는가

직전 fix(`2026-05-09-annotation-screen-mismatch-fix`)는 정확히 "screen 1 의 핀이 screen 2 프리뷰 위에 그려지는" 회귀를 막기 위해 이 가드를 추가했다. 가드를 통째로 제거하면 그 회귀가 다시 살아난다. 한 세션이 여러 화면 어노테이션을 보유할 수 있다는 사용자 요구를 고려하면, 핀 가시성 판정은 **per-screen** 이 아니라 **per-annotation** 으로 내려가야 한다.

### 왜 node uid 매치인가

각 어노테이션의 `target` 은 두 종류다(`SessionDtoModels.kt:103-112`).

- `Node(nodeUid: String, boundsInWindow)` — `target.type = "semantics_node"`, `target.nodeUid` 보유.
- `Area(boundsInWindow)` — `target.type = "visual_area"`, anchor 없음.

`nodeUid` 는 `compose:<rootIndex>:<treeKind>:<SemanticsNode.id>` 형식으로 사이드킥에서 발급한다(`fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/inspect/SemanticsNodeMapper.kt:47-48`). 같은 Compose 컴포지션이 살아있는 동안 SemanticsNode 의 id 는 안정적이므로, 같은 화면을 재캡처하면 uid 가 보존된다. 다른 화면으로 네비게이트하면 컴포지션이 갈리며 uid 도 달라진다. 즉 uid presence 자체가 "anchor 가 현재 캡처에 살아있는가" 를 직접 답한다.

`Area` 는 anchor 가 없어 같은 판정이 불가능하다. 사용자 워크플로 상 area 는 컴포넌트가 잡히지 않을 때의 fallback 이므로, 보수적으로 "원본 캡처에서만 표시" 를 유지한다.

### 모든 케이스에서의 기대 동작

| 상황 | 표시되는 캔버스 | 표시되는 핀 | 근거 |
|---|---|---|---|
| 세션 막 열림, 라이브 미수신 | 가장 최근 persisted 스크린 | 그 스크린에 매칭되는 saved 핀 (uid 매치, 또는 screenId 매치) | `latestPersistedScreen()` fallback + uid 매치 자기 자신 |
| 라이브 폴링이 같은 화면 재캡처 | 새 라이브 스크린 | 같은 logical screen 에 anchor 가 살아있는 saved 핀 | uid 보존 |
| 사용자가 앱 안에서 다른 화면으로 네비게이트 | 새 라이브 스크린 | 직전 화면 핀은 사라지고, 새 화면에 anchor 가 있는 핀만 등장 | 컴포지션 교체로 uid 변경 |
| 디바이스 끊김 / 스크린 잠김 | persisted fallback 또는 stale 프리뷰 | 그 displayed 스크린에 매칭되는 핀 | 같은 단일 규칙 |
| 세션 전환(history 클릭) | `openSession` 이 invalidate → persisted 시작 | 새 세션의 핀들이 anchor 매치 기준으로 표시 | `invalidatePreviewContext()` + `resetAnnotationComposerState()` 가 이미 처리 |
| 핀 클릭(`focusedSavedItemId`) | 그 핀의 원본 screen 으로 캔버스 전환 | 그 screen 위의 모든 saved 핀 | 자기 자신 매칭 (uid + screenId 모두) |
| Annotate 모드(`addItemsFlow`) | frozen `addItemsFlow.screen` | pending 핀들 (saved-evidence 오버레이는 이미 비활성) | 본 변경 영향 없음, 기존 `if (!addItemsFlow)` 가드 유지 |
| Area 어노테이션, 다른 화면에서 라이브 폴링 | 라이브 스크린 | area 핀 사라짐 | nodeUid 없음 → screenId 매치 fallback 실패 |

### 알려진 한계

- **Area 어노테이션의 다중 화면 가시성**: anchor 가 없으므로 라이브 폴링 중 원본 캡처가 아닌 프레임에서는 보이지 않는다. 이는 의도된 보수적 동작. 컴포넌트 어노테이션을 권장.
- **Compose recomposition 으로 인한 일시적 uid 부재**: 드물게 nodeId 가 잠깐 재할당되는 경우 핀이 깜빡일 수 있다. 본 플랜에서는 단순 규칙으로 시작하고, 사용자 보고가 발생하면 800ms hysteresis 를 별도 follow-up 으로 추가한다.

---

## Task 1 — `visibleScreenNodeUids` 헬퍼 추가 (preview.js)

**Files:** `fixthis-mcp/src/main/console/preview.js`

### 목표

표시 대상 스크린의 모든 노드 uid 를 `Set<string>` 으로 모으는 순수 함수를 한 곳에 둔다. `rendering.js` 에서 매 오버레이 렌더 호출마다 부르므로 O(N) 이지만, screen 당 노드 수는 수백 정도로 충분히 가볍다.

### 변경 사항

`preview.js` 의 `latestPersistedScreen()` 정의 직전(혹은 직후) 위치에 다음 헬퍼를 추가한다:

```js
function visibleScreenNodeUids(screen) {
  const uids = new Set();
  if (!screen) return uids;
  (screen.roots || []).forEach(root => {
    (root.mergedNodes || []).forEach(node => { if (node?.uid) uids.add(node.uid); });
    (root.unmergedNodes || []).forEach(node => { if (node?.uid) uids.add(node.uid); });
  });
  return uids;
}
```

`renderSelectionOverlay` 가 호출 시점에 이 함수를 부른다. 메모이제이션은 도입하지 않는다(같은 screen 객체 참조여도 외부 mutation 가능성을 신뢰하지 않으며, 호출 빈도가 1초당 한두 번이고 입력 크기가 작다).

### Validation

- [ ] `node --check fixthis-mcp/src/main/console/preview.js`
- [ ] 헬퍼가 `null`/`undefined` screen, 빈 `roots`, `mergedNodes`/`unmergedNodes` 누락 케이스에서 throw 하지 않고 빈 Set 을 돌려주는지 코드로 확인 (조건부 로직 점검).

### Commit

`feat(console): add visibleScreenNodeUids helper`

---

## Task 2 — saved-evidence 핀 필터 anchor-aware 로 교체 (rendering.js)

**Files:** `fixthis-mcp/src/main/console/rendering.js`

### 목표

`renderSelectionOverlay()` 안의 saved-evidence 필터 한 곳을, 위에서 정의한 `visibleScreenNodeUids` 와 결합해 per-annotation 판정으로 바꾼다. 다른 분기(numbered feedback overlay, currentSelection, hover-preview, dragPreview)는 손대지 않는다.

### 현재 코드

`rendering.js:72-79`

```js
renderNumberedFeedbackOverlay(overlay, image);
if (!addItemsFlow) {
  const visibleScreen = latestScreen();
  if (visibleScreen?.screenId) {
    const screenSavedItems = savedEvidenceItems().filter(item => item.screenId === visibleScreen.screenId);
    if (screenSavedItems.length) renderSavedEvidenceOverlay(overlay, image, screenSavedItems);
  }
}
```

### 새 코드

```js
renderNumberedFeedbackOverlay(overlay, image);
if (!addItemsFlow) {
  const visibleScreen = latestScreen();
  if (visibleScreen?.screenId) {
    const visibleUids = visibleScreenNodeUids(visibleScreen);
    const screenSavedItems = savedEvidenceItems().filter(item => {
      const nodeUid = item?.target?.nodeUid;
      if (nodeUid) return visibleUids.has(nodeUid);
      return item.screenId === visibleScreen.screenId;
    });
    if (screenSavedItems.length) renderSavedEvidenceOverlay(overlay, image, screenSavedItems);
  }
}
```

판정 흐름:

1. `addItemsFlow` 면 saved 오버레이 자체가 비활성 (기존 동작 유지).
2. `visibleScreen` 이 없으면 그릴 게 없다 (기존 가드 유지).
3. node-anchored item: `target.nodeUid` 가 현재 화면 노드 트리에 있을 때만 표시.
4. area-anchored item (`target.nodeUid` 없음 ↔ `target.type === "visual_area"`): 기존 `screenId` 일치 fallback.

`renderSavedEvidenceOverlay` 에 넘기는 item 배열의 형태는 그대로이므로, 그 함수의 시그니처/내부 로직은 변경하지 않는다.

### Validation

- [ ] `node --check fixthis-mcp/src/main/console/rendering.js`
- [ ] diff 가 위 단일 블록에만 한정되는지 `git diff fixthis-mcp/src/main/console/rendering.js` 로 확인.

### Commit

`fix(console): show pins by anchor presence on visible screen`

---

## Task 3 — 번들 재생성 + 콘솔 자산 테스트 통과

**Files:** `fixthis-mcp/src/main/resources/console/app.js`

### 목표

수정된 두 source 모듈을 반영한 번들을 생성한다. 수동 편집 금지 — 반드시 스크립트로 만든 결과를 그대로 커밋한다. `generatedConsoleAppMatchesConsoleSourceModules` 가 byte-equal 비교를 강제하므로 사람의 손이 닿으면 깨진다.

### 절차

```bash
node scripts/build-console-assets.mjs
node --check fixthis-mcp/src/main/resources/console/app.js
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.FeedbackConsoleServerTest.generatedConsoleAppMatchesConsoleSourceModules"
```

### Validation

- [ ] `git diff fixthis-mcp/src/main/resources/console/app.js` 가 Task 1+2 의 source 변경과 정확히 매칭되는 두 hunk만 보여주는지 확인.
- [ ] 위 Gradle 테스트 PASS.

### Commit

태스크 1, 2 와 같은 커밋에 `app.js` 를 함께 묶거나, 별도 커밋으로 `chore(console): regenerate app.js` 로 분리한다. 이 저장소 관행은 source 와 같은 커밋에 포함하는 쪽이 일반적이다(직전 `2026-05-09-annotation-screen-mismatch-fix` 참고).

---

## Task 4 — manual smoke 시나리오

**Files:** 없음 (수동 검증 단계).

### 목표

본 변경의 핵심 가치는 UX 일관성이므로, JS 단위 테스트 인프라가 없는 만큼 디바이스/에뮬레이터에서 모든 케이스를 한 번씩 손으로 확인한다.

### 빌드/실행

```bash
./gradlew :fixthis-cli:installDist :fixthis-mcp:installDist
fixthis-cli/build/install/fixthis/bin/fixthis console \
  --package io.github.beyondwin.fixthis.sample \
  --console-assets-dir "$PWD/fixthis-mcp/src/main/resources/console"
```

브라우저로 `http://localhost:<port>` 접속.

### 시나리오

각 시나리오에서 콘솔의 캔버스 영역과 우측 Annotations 패널을 동시에 본다.

- [ ] **S1: 같은 화면 라이브 폴링.** 어노테이션 1, 2 를 만들고 1초 폴링을 그대로 둔다. 핀이 매 폴링마다 깜빡이거나 사라지지 않아야 한다.
- [ ] **S2: 다른 화면으로 네비게이트.** 샘플 앱에서 다른 탭/스크린으로 이동한다. 직전 화면 핀이 사라져야 하며, 새 화면에 어노테이션이 있다면 그것만 떠야 한다.
- [ ] **S3: 다시 원래 화면으로 복귀.** S2 에서 돌아오면 원래 핀이 다시 등장해야 한다 (uid 가 보존되므로).
- [ ] **S4: 세션 전환.** History sidebar 에서 다른 세션을 클릭한다. 새 세션의 핀만 나타나야 한다.
- [ ] **S5: 새 세션 만들기 → 빈 캔버스.** + 버튼으로 새 세션을 시작한다. 핀이 없어야 한다.
- [ ] **S6: 디바이스 끊김.** USB 분리 또는 `Clear selection`. 마지막 프레임 또는 persisted 화면과 함께 그 화면의 핀이 표시되어야 한다.
- [ ] **S7: 화면 잠금.** 디바이스 잠금 → blocked 오버레이가 뜨고, 풀면 다시 라이브로 복귀하면서 핀이 자연스럽게 매칭되어야 한다.
- [ ] **S8: Annotate 모드.** 좌측 상단 Annotate 버튼으로 진입. addItemsFlow 가 활성화되며 saved 핀이 사라지고 pending 핀만 보여야 한다 (기존 동작 유지).
- [ ] **S9: 핀 클릭으로 focus.** 좌측 패널 또는 캔버스 위 핀을 클릭. 캔버스가 그 핀의 원본 화면으로 바뀌고, 같은 화면의 다른 핀들이 함께 보여야 한다.
- [ ] **S10: Area 어노테이션.** 컴포넌트가 아닌 빈 영역을 드래그해 area annotation 을 만든 뒤 다른 화면으로 네비게이트한다. area 핀은 사라져야 한다(원본 캡처에서만 표시되는 fallback).
- [ ] **S11: 코멘트 입력 중 핀 깜빡임 없음.** 핀을 클릭해 detail 진입 → 코멘트 textarea 에 입력 시작 → 1초 폴링이 진행되어도 textarea 가 새로 그려지지 않고(이미 다른 fix 로 보장됨) 핀도 안정해야 한다.

각 시나리오를 통과하지 못하면 implementation-details 문서의 "Failure modes" 섹션을 참고해 원인 후보를 추적한다.

---

## Task 5 — CHANGELOG 업데이트

**Files:** `CHANGELOG.md`

### 목표

사용자 향 한 줄 요약. "어떤 회귀가 있었고, 어떤 규칙으로 풀었는가" 를 짧게 적는다.

### 예시 항목

```
- fix(console): keep annotation pins visible across live preview polls by
  matching anchors per-annotation; pins now appear/disappear naturally as the
  device navigates between screens within a session.
```

### Validation

- [ ] CHANGELOG 의 "Unreleased" 또는 다음 릴리스 섹션에 위 한 줄 추가.

### Commit

위 source/번들 커밋과 함께 묶어도 되고, 별도 `docs(changelog): note pin visibility fix` 로 분리해도 된다.

---

## 회귀 회피 체크리스트

본 변경이 의도하지 않은 곳을 건드리지 않았다는 self-check.

- [ ] `latestScreen()` 의 시그니처/반환 우선순위는 그대로다 (addItemsFlow → focusedSavedItemId → live → persisted).
- [ ] `screenImageUrl()`, `renderPreviewRegion()`, `renderPreviewOnly()` 코드는 변경되지 않았다.
- [ ] `mergeSessionIntoState()` 의 in-flight 코멘트 보존 로직은 그대로다.
- [ ] sessions polling 가드(`isEditingAnnotation()`) 는 영향 없다.
- [ ] Area 어노테이션의 원본 캡처 표시 동작은 그대로다.
- [ ] `addItemsFlow` 분기에서 saved 오버레이가 여전히 비활성이다.
- [ ] 콘솔 번들의 모듈 순서/포맷은 변경되지 않았다.

## Out of scope

- nodeId 안정성을 보강하기 위한 backend-side 식별자 추가.
- area 어노테이션을 다른 화면에서도 보이게 만드는 새 anchor 시스템.
- 핀 깜빡임 hysteresis (별도 follow-up 으로만 고려).
- preview-session 동기화 일반론 (이전 `console-state-sync-design.md` 영역).
