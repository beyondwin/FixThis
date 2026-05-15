# FixThis Agent Handoff 개선 플랜 — 분석 및 수정 제안

> 대상: PointPatch 시절 작성된 "Stable Evidence Handoff(Phase 1) + Composable Identity(Phase 2)" 플랜
> 현재 코드 상태: `io.github.beyondwin.fixthis.*` 패키지로 리네임 완료, Compose BOM `2026.04.01`, Kotlin `2.2.21`, AGP `9.1.1`.
> 검토 관점: 시니어 소프트웨어 아키텍트의 디자인 리뷰. 채택/수정/보류 결정과 그 근거를 코드 레벨에서 기록하는 것이 목적이다.

> Stable Target Evidence v1 사후 보정: v1은 nullable additive `targetEvidence`를 추가하므로 public schema와 bridge protocol은 `1.0`을 유지한다. `ui-tooling-data`는 stable path 요구사항이 아니며, sample 골든 패스 태그는 `sample/` 앱에 있어야 한다. semantics subtree 렌더링은 stable UID parent chain이 없으므로 v1 범위 밖이다.

> main 병합 후 구현 보정: 현재 `main`은 기존 in-app overlay feedback capture와 `fixthis_get_ui_feedback` 경로를 제거하고 MCP browser console의 frozen preview/session 흐름을 표준 경로로 둔다. 따라서 Stable Target Evidence v1의 현재 구현은 `FeedbackSessionService.savePreviewFeedbackItems`에서 저장 시점의 `SnapshotDto.roots.mergedNodes`와 source index를 사용해 `AnnotationDto.targetEvidence`를 산출한다. 과거 overlay capture 경로 언급은 historical review context로만 본다.

---

## 1. 전체 플랜 평가

### 1.1 핵심 아이디어의 타당성

이 플랜이 정조준하고 있는 문제는 두 가지다.

1. **Agent가 받는 컨텍스트의 불안정성**: 현재 `FixThisMarkdownFormatter.format()`는 단일 마크다운만 생성하고, JSON은 `FixThisAnnotation` 직렬화를 그대로 흘려보낸다. 따라서 동일한 화면에서 두 번째 Sign In 버튼을 탭하더라도 Agent가 이를 구분할 수 있는 단서(occurrence, ordinal)가 없다.
2. **소스 매칭의 신뢰도가 출력 형식에 묻혀 있다**: `SourceMatcher`는 점수 비례로 confidence를 산출하지만, 출력에서는 "1번 후보"라는 사실만 강조되고 "이 후보가 *왜* 1번이 되었는가"는 `matchReasons`로만 흐릿하게 노출된다. Agent가 잘못된 파일을 수정할 위험이 그대로 노출된다.

이 두 문제를 "**JSON evidence는 항상 풍부하게, Markdown은 detail mode에 따라 압축**"으로 푸는 방향성은 옳다. 특히 *"compact / precise / full" 토글은 **마크다운 출력만** 바꾼다*는 정책은, 현재 `fixthis_read_feedback`이 `application/json`과 `text/markdown` 두 컨텐츠를 동시에 반환하는 구조와 정합성이 좋다. JSON 스키마의 안정성을 깨지 않고도 사람이/agent가 보기 좋은 표면을 따로 가져갈 수 있다.

### 1.2 기존 아키텍처와의 정합성

플랜은 `PointPatch*` 명명을 그대로 쓰고 있는데 현재 코드는 `FixThis*`로 전부 리네임이 끝난 상태다(`FixThisAnnotation`, `FixThisNode`, `FixThisMarkdownFormatter`, `fixthis-mcp/tools/FixThisTools.kt`). 따라서 **모든 신규 클래스/메서드는 `FixThis` 네임스페이스에 맞춰 다시 명명해야 한다**. 예를 들어:

- 플랜의 `pointpatch_read_feedback(detailMode)` → 현재는 이미 `fixthis_read_feedback`이 `FixThisTools`에 존재한다. 즉 새 도구가 아니라 *기존 도구에 `detailMode` 파라미터를 추가*하는 작업이 된다. 이는 플랜이 가정한 "신규/대체 도구"보다 표면적이 작고 호환성 부담이 작다.
- 플랜의 `targetEvidence`는 결국 `AnnotationDto`(SessionDtoModels.kt)와 `FixThisAnnotation`(Models.kt) 사이 어딘가에 살아야 한다. 어디에 두느냐에 따라 호환성 영향이 크게 달라지므로 뒤에서 별도 절로 다룬다.

또 하나 중요한 정합성 이슈: 플랜은 "JSON은 항상 full"을 가정하지만, **현재 `fixthis_read_feedback`은 `FeedbackQueueFormatter.toJson(session)`을 통해 `SessionDto` 전체를 그대로 직렬화**한다. 즉 "JSON evidence"가 별도의 모델로 존재하는 게 아니라 세션 도큐먼트의 직렬화다. 이 사실을 무시하면 "evidence JSON" 모델을 새로 만들고도 정작 어떤 도구가 그것을 반환하는지가 모호해진다 — 뒤에서 *어디에 붙일지*를 정하는 절을 둔다.

### 1.3 결론

- **방향성**: 채택. detailMode 정책과 evidence 분리는 옳다.
- **명명/위치 가정**: 거의 모두 수정 필요. 플랜은 코드 인벤토리를 한 번 더 맞춰서 다시 써야 한다.
- **Phase 분리**: 합리적. Phase 2(Composable Identity)는 실험 플래그로 격리하지 않으면 BOM 업그레이드마다 깨질 위험이 매우 높다(이유는 §4에서).

---

## 2. Phase 1 항목별 분석

### 2.1 `targetEvidence` 모델

플랜의 의도는 다음의 묶음을 한 곳에 모은 구조를 만드는 것이다.

```
targetEvidence {
  targetKind, userRequest, identityHint, occurrence,
  semanticsStructure, sourceInterpretation, screenshots
}
```

#### 이슈

1. **이미 비슷한 정보가 두 군데에 흩어져 있다.**
   - `FixThisAnnotation` (compose-core): `selection`, `selectedNode`, `nearbyNodes`, `sourceCandidates`, `screenshot`, `userComment`. 직렬화는 `kotlinx.serialization`.
   - `AnnotationDto` (mcp/session): `target`, `selectedNode`, `nearbyNodes`, `sourceCandidates`, `screenshotCrop`, `comment`, `delivery`, `status`. `FixThisAnnotation` 대비 전체 화면 스크린샷이 빠져있고 세션/배치 메타데이터가 추가된 형태.
   - 즉 `targetEvidence`는 *세 번째 표현*이 되기 쉽다. 이건 데이터 모델 부채를 가속시킨다.

2. **identityHint와 occurrence가 어디에서 계산되어야 하는가?** 플랜은 명시하지 않는다. 후보는 셋이다.
   - (a) 디바이스 사이드 sidekick(`SemanticsInspector` 호출 직후)
   - (b) compose-core formatter 진입 시
   - (c) MCP 서버 사이드(`FeedbackSessionService` 또는 도구 호출부)

   `occurrence`는 *현재 화면의 모든 노드 셋*에 대한 카운트가 필요하므로 inspect 결과가 있어야 한다. `FixThisAnnotation.nearbyNodes`만으로는 부족하다(`NearbyNodeCollector`는 반경 480px, 최대 12개로 자른다 — `SelectionOptions`). 따라서 **이 카운트를 BridgeServer 측에서 산출하든가, `FixThisAnnotation`에 "전체 화면 노드 인덱스"를 별도로 동봉해야 한다**. 플랜의 "screen 전체 카운트"는 현재 데이터로는 계산 불가다.

#### 수정 제안

- `targetEvidence`를 새 최상위 모델로 만들지 말고, **기존 `FixThisAnnotation`에 `targetEvidence: TargetEvidence?` 필드를 옵셔널로 추가**한다. nullable additive evidence이므로 public schema version은 `1.0`을 유지하고 디폴트 값으로 하위 호환을 유지한다. `AnnotationDto`에도 동일한 필드를 매핑해 `SessionDomainMappers`에서 통과시킨다.
- `occurrence` 계산은 **전체 merged semantics node snapshot이 살아 있는 시점**에 수행한다. 현재 MCP console 구현에서는 Save가 frozen preview를 persisted screen/items로 승격할 때 `FeedbackSessionService.savePreviewFeedbackItems`가 selectedNode를 결정한 직후 `SnapshotDto.roots.mergedNodes`를 한번 더 훑는다. 비용은 mergedNodes O(N) × 비교 키 5종으로 충분히 싸다(보통 N < 500).
- identityHint 계산은 **순수 함수**로 만들어 compose-core(`io.github.beyondwin.fixthis.compose.core.identity` 패키지 신설)에 둔다. 디바이스/서버 양쪽에서 재사용 가능해야 한다.

### 2.2 testTag 컨벤션 파서 (`comp:AppPrimaryButton:primary`)

플랜은 `comp:<ComposableName>:<variant>` 포맷을 인식하고 `composableNameHint`, `variantHint`, `hintSource=testTag_convention`, `confidence=high`를 산출하자고 한다.

#### 이슈

1. **컨벤션의 적용 범위가 정의되어 있지 않다.** 사용자가 `comp:` 접두를 안 쓰면 그냥 평문 testTag로 처리한다는 건 합리적인데, 그렇다면 *왜 sample app부터 이 컨벤션을 적용하지 않는가*라는 질문이 따라온다. Stable Target Evidence v1의 골든 패스 태그는 `sample/` 앱에 도입되어야 한다. `fixthis-compose-overlay`의 `StudioTestTags`는 overlay 내부 UI 태그이므로 sample handoff 동작을 증명하지 않는다.

2. **충돌 가능성**: 콜론(`:`)은 일반 testTag에서도 흔히 쓰일 수 있고, 자동 생성된 testTag가 콜론 두 개를 우연히 가질 수 있다. 컨벤션 prefix를 `comp:`로 두면 일치 확률이 거의 0에 가깝지만, 정규식은 *반드시 anchor* 시켜야 한다. 플랜의 본문에는 정규식 명세가 없다. 다음과 같은 엄격한 명세를 못 박아야 한다.

   ```
   ^comp:([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9_-]+)$
   ```

   variant 부분에 더 다양한 문자(예: `primary/sm`)를 허용할지 미리 결정하지 않으면 추후 확장에서 또 깨진다.

3. **confidence=high의 정의가 불명확.** `SourceMatcher`의 `SelectionConfidence`는 누적 점수 기반(`HIGH ≥ 100.0`)이다. 컨벤션 매칭으로 도출된 hint가 별도의 confidence 차원을 갖는다면, **이 confidence와 `SourceCandidate.confidence`가 충돌하지 않도록 *다른 enum이거나 다른 필드*임을 분리**해야 한다. 같은 `SelectionConfidence`를 재사용하면 후일 "이 후보의 HIGH가 어디서 온 건지" 구분이 안 된다. 추천: `IdentityHintSource { TEST_TAG_CONVENTION, EXPLICIT_HINT, DERIVED, NONE }`와 `IdentityHintConfidence { HIGH, MEDIUM, LOW }`를 별도로 둔다.

#### 수정 제안

- `compose-core`에 `TestTagConvention` 객체를 신설:
  ```kotlin
  object TestTagConvention {
      private val pattern = Regex("^comp:([A-Za-z][A-Za-z0-9]*):([A-Za-z0-9_-]+)$")
      data class Parsed(val composableName: String, val variant: String)
      fun parse(testTag: String?): Parsed? = testTag?.let { pattern.matchEntire(it) }
          ?.let { Parsed(it.groupValues[1], it.groupValues[2]) }
  }
  ```
- 적용은 `IdentityHint.from(node)`에서 `TestTagConvention.parse(node.testTag)`을 우선 시도하고, 없으면 role+text 폴백을 둔다.
- sample 앱 화면/컴포넌트에 `comp:*` 컨벤션 태그를 도입하는 작업을 *Phase 1의 명시적 완료 기준*에 포함시켜야 데모가 가능하다. overlay-only `StudioTestTags` 변경은 골든 패스 증거가 아니다.

### 2.3 occurrence counter

플랜은 5종 시그니처(role+text, role+contentDescription, testTag, testTag prefix, identityHint) 모두에 대해 카운트하고 selectedOrdinal을 함께 보내자고 한다.

#### 이슈

1. **다중 시그니처를 모두 보내면 노이즈**가 생긴다. Agent가 어떤 카운트를 신뢰할지 모르게 된다. 단일 *최선의* signature에 대한 count + ordinal만 출력하고, 나머지는 디버그 모드(full)에서만 노출하는 게 맞다.
2. **VISUAL_AREA 선택일 때**: `SelectionInfo.kind == VISUAL_AREA`이면 selectedNode가 없거나 의미가 약하다(`AnnotationTargetDto.Area`). occurrence는 *영역 기반*에서는 정의 불가다. 이 케이스를 명시적으로 `Occurrence.Unavailable`로 처리해야 한다.
3. **TAP_POINT 폴백** (`NodeSelector.select`가 atPoint.isEmpty()일 때): selectedNode가 null이다. 같은 처리.
4. **MERGED vs UNMERGED**: 현재 `inspectCurrentScreen`은 두 트리를 모두 반환한다. occurrence를 어느 트리 기준으로 셀지 정해야 한다. Stable Target Evidence v1은 captured merged semantics nodes 기준으로 count/ordinal을 산출한다. `FixThisNode.path`를 stable UID parent chain처럼 취급해 부모 환원이나 subtree 렌더링을 구현하는 것은 v1 범위 밖이다.

#### 수정 제안

- 데이터 모델:
  ```kotlin
  @Serializable
  data class Occurrence(
      val signature: OccurrenceSignature, // ROLE_PLUS_TEXT | TEST_TAG | TEST_TAG_PREFIX | IDENTITY_HINT
      val count: Int,
      val selectedOrdinal: Int, // 1-based, 0 if not applicable
  )
  ```
- 선택 알고리즘: identityHint가 있으면 그것을 우선, 다음으로 testTag(컨벤션 매칭이면 prefix까지 검사), 다음으로 role+text. 첫 번째로 count > 0을 만족하는 시그니처를 채택한다. *모든* 시그니처를 다 보내지 않는다.
- VISUAL_AREA / TAP_POINT 폴백에서는 `targetEvidence.occurrence = null`로 두고 markdown은 "Occurrence: not applicable for area selection"으로 출력.

### 2.4 Semantics subtree formatter

플랜의 트리 출력 예시는 다음과 같다.

```
[Button] text="Sign In" testTag="comp:AppPrimaryButton:primary"
├─ Icon contentDescription="lock"
└─ Text "Sign In"
```

#### 이슈

1. **현재 데이터 모델로 subtree 추출이 어렵다.** `FixThisNode.path`는 stable UID parent chain이 아니고, 자식 관계도 직접 저장되어 있지 않다. `inspectCurrentScreen`은 노드를 평탄화한 리스트로 반환한다.

   `SemanticsInspector.inspectTree`는 `getAllSemanticsNodes(...)`만 호출하고, 부모-자식 관계를 별도로 구성하지 않는다. 따라서 "선택된 노드의 subtree"를 사후 재구성하려면 다음 중 하나가 필요하다.
   - (a) `FixThisNode`에 `parentUid: String?` 또는 `childrenUids: List<String>`를 추가
   - (b) sidekick에서 트리를 만들 때 SemanticsNode의 children/parent를 따라 별도 인덱스를 산출

   (a)가 더 안전하다. `getAllSemanticsNodes`가 반환하는 SemanticsNode에는 `.parent`, `.children`이 있고 안정적이다. `SemanticsNodeMapper.map`에서 부모 uid 한 줄 추가가 가장 적은 침습이다.

2. **mergedTree로 그릴지 unmergedTree로 그릴지가 명시되어 있지 않다.** 플랜의 Note는 "semantics tree, not Composable tree"라고만 한다. *어느* semantics tree인지가 빠졌다. 사용자/agent가 본 화면의 시각 구조에 가까운 것은 unmerged이지만, 타깃 식별에는 merged가 더 의미가 있다. **출력은 merged 기준이 옳다** — Button 같은 merge 단위가 그대로 노드가 되기 때문에 "Button > Icon > Text"가 깨끗하게 나온다.

3. **깊이 제한이 없다.** 화면 전체가 LazyColumn 안에 있다면 subtree가 200개를 넘을 수 있다. **maxDepth=3, maxNodesPerLevel=8** 같은 cutoff을 명시해야 한다. compact/precise/full 모드가 그 cutoff을 다르게 가지면 자연스럽다.

4. **role 표기 일관성**: `SemanticsNodeMapper`에서 `SemanticsProperties.Role`은 `toString()`으로 직렬화된다. 출력 시 `"Role(Button)"` 같은 형태가 들어올 수 있다. compose-core의 redaction/format 단계에서 정규화가 필요하다 — 현재 `FixThisMarkdownFormatter.appendNode`도 raw `role` 문자열을 그대로 쓴다.

#### 수정 제안

- Stable Target Evidence v1에서는 subtree 렌더링을 제외한다. future work로 재개하려면 먼저 stable parent/children UID contract를 추가해야 한다.
- 그 이후에만 compose-core `format/SubtreeRenderer.kt` 같은 순수 렌더러를 검토한다.
- merged 트리 기준이 디폴트라는 방향은 유지하되, stable UID parent chain 없이 출력하지 않는다.

### 2.5 Screenshot 개선 (highlight + padded crop)

플랜:
- highlight: 전체 화면에 dim + selection highlight 합성
- padded crop: 30% padding, 최소 48dp
- `readScreenshot(kind=highlight)` 추가

#### 이슈

1. **dim+highlight 합성 시점**: 현재 `ScreenshotCapturer.capture`는 PixelCopy로 decorView를 그대로 찍는다. 오버레이 호스트는 일시적으로 INVISIBLE 처리된다(`hideFixThisOverlayHosts`). highlight 합성은 *캡쳐 후* 비트맵 위에 Canvas로 그려야 한다. dim은 알파 0.4 사각형 + 선택 영역만 clearOp + 외곽선이면 충분하다. ScreenshotStore.save에서 highlight 비트맵을 한 장 더 만들어 저장하는 것이 깔끔하다.

2. **padding 30%, min 48dp의 충돌**: 셀렉션 bounds가 매우 작으면(예: 20dp 체크박스) 30%는 6dp이고, min 48dp가 더 크다. 반대로 큰 영역(예: 화면 절반)은 30%가 너무 커져서 거의 전체 화면이 된다. **`max(bounds * 1.3, bounds + 48dp on each side)`** 같이 두 룰의 합리적 결합을 명세해야 한다. `ScreenshotStore.toCropRect`는 px 단위만 다루므로 dp 변환은 호출자에서 해 주어야 한다(현재 ScreenshotStore는 Context를 갖고 있으니 density 접근은 가능).

3. **`readScreenshot(kind=highlight)` 보안**: 현재 `BridgeServer.readScreenshot`은 `kind`를 `full|crop`으로 강제한다(`require(kind == "full" || kind == "crop")`). 추가 시 화이트리스트만 확장하면 되지만, **path resolution 부분에서 highlight path가 어디 저장되는지 ScreenshotInfo에 필드를 추가해야 한다**. 즉 `ScreenshotInfo`에 `highlightPath: String?`, `paddedCropPath: String?` 두 필드를 추가하고, BridgeServer의 path 분기에 케이스를 더한다.

4. **저장소 비용**: 한 캡쳐당 최대 4장(full / crop / padded crop / highlight)이 된다. 한 장당 ARGB_8888 PNG는 압축 후에도 화면당 수백 KB ~ 수 MB. **저장 정책**(예: 같은 세션 내 N개 초과 시 LRU)이 필요하다. 현재 `ScreenshotStore.screenshotDirectory()`는 일자 폴더로 누적만 한다.

5. **PixelCopy 실패 시 Canvas 폴백**: 현재 폴백은 `decorView.draw(Canvas(bitmap))`. SurfaceView/하드웨어 가속 텍스처가 비어 보일 수 있다(이미 알려진 한계). highlight 합성은 *후처리*이므로 폴백 비트맵에서도 동일하게 동작한다 — 이건 장점이다.

#### 수정 제안

- `ScreenshotInfo`에 `highlightPath`, `paddedCropPath`, `desktopHighlightPath`, `desktopPaddedCropPath` 추가.
- `ScreenshotStore.save`를 다음과 같이 확장:
  ```kotlin
  data class StoreOptions(val makeHighlight: Boolean, val padCropPx: Int)
  ```
  `padCropPx`는 호출자(예: `ScreenshotCapturer.capture`)가 `density.toPx(48.dp)`로 산출.
- highlight 렌더링은 별도 `HighlightCompositor` 객체로 분리(테스트 용이성).
- `BridgeServer.readScreenshot`의 화이트리스트에 `highlight`, `paddedCrop` 추가. `MaxScreenshotReadBytes = 16MB`는 그대로. 단 highlight는 풀스크린 dim+highlight라 항상 풀 사이즈와 비슷해 16MB에 근접할 수 있다 — 이 크기를 줄이려면 PNG → JPEG(quality 85)을 *highlight에 한해서* 허용해야 한다. 다만 `hasPngHeader` 검사가 있으니, JPEG를 허용하려면 헤더 검사를 분기시켜야 한다.

### 2.6 `fixthis_read_feedback(detailMode)` (플랜의 `pointpatch_read_feedback`)

플랜의 모드별 출력:
- compact: 코멘트 + 타깃 + top 1 source + 핵심 caution + highlight
- precise(default): identity + occurrence + semantics short + top 3 sources + highlight + crop
- full: top 5 sources + nearby + score breakdown + 긴 semantics + 풀스크린샷

#### 이슈

1. **현재 `fixthis_read_feedback`은 *세션* 도구다.** Item 단위가 아니라 세션 큐를 출력한다(`FeedbackQueueFormatter.toMarkdown(session)`). `itemId` 인자는 *focus*만 담당한다. detailMode를 어디에 적용할지 정해야 한다 — 가장 자연스러운 결정은:
   - itemId가 주어졌을 때 → detailMode가 적용되는 단일 아이템 출력으로 전환
   - itemId 없을 때 → detailMode는 각 아이템에 일괄 적용되거나, *compact 강제* (큐가 길수록 풀 출력은 비현실적)

2. **`FeedbackQueueFormatter`는 `compose-core`가 아니라 `fixthis-mcp/session/` 모듈에 있다.** 즉 detailMode 적용은 *fixthis-mcp* 측 코드다. 한편 단일 annotation의 markdown 렌더는 `compose-core`의 `FixThisMarkdownFormatter`다. detailMode가 두 곳에 모두 분기를 만든다는 뜻이다 — 중복을 막기 위해 **단일 아이템 렌더는 `FixThisMarkdownFormatter`에 detailMode 파라미터를 받게 하고, 큐 렌더는 `FeedbackQueueFormatter`가 그것을 호출**하는 형태가 깔끔하다.

3. **`detailMode` 파라미터 명세 누락**: enum vs string? compact/precise/full만? "minimal" 등 추가? **enum으로 시작하고 와이어 프로토콜에서는 lowercase string로 직렬화** 권장(다른 enum들과 패턴 통일: `FeedbackDelivery`, `AnnotationStatusDto`).

4. **JsonObject vs Markdown의 비대칭**: 플랜은 "JSON은 항상 full"이라고 한다. 그런데 현재 `fixthis_read_feedback`은 `toJson(session)`을 그대로 반환한다 — 그러면 정의상 항상 full이 맞다. 이 정책은 그대로 유지 가능하다. 단 detailMode가 `compact`여도 *JSON에는 풀 evidence가 들어 있음*을 description에 명시해야 agent가 혼란을 겪지 않는다.

#### 수정 제안

- `compose-core`:
  ```kotlin
  enum class DetailMode { COMPACT, PRECISE, FULL }
  object FixThisMarkdownFormatter {
      fun format(annotation: FixThisAnnotation, detail: DetailMode = DetailMode.PRECISE): String { ... }
  }
  ```
- `fixthis-mcp/session/FeedbackQueueFormatter.toMarkdown(session, detail)` 시그니처를 추가.
- `FixThisTools` `fixthis_read_feedback` schema에 `detailMode`(enum) 추가, 디폴트 `precise`.
- description에 "JSON evidence는 detailMode와 무관하게 풀 데이터를 반환합니다" 명시.

### 2.7 콘솔/UX 토글

플랜: `[Compact] [Precise] [Full]` 버튼이 컨솔 UI에 추가된다. 정책상 토글은 마크다운 출력만 바꾼다.

#### 이슈

1. **두 가지 토글 위치 후보**가 있다.
   - (a) `fixthis-compose-overlay`의 `FixThisToolbar` (디바이스 화면)
   - (b) `fixthis-mcp/console/FeedbackConsoleAssets`(데스크톱 콘솔)

   문맥상 detailMode는 *agent 핸드오프 시 마크다운 형태*를 결정하므로 디바이스가 아니라 콘솔 측이 맞다. 디바이스 토글은 의미가 약하다 — 캡쳐 시점의 evidence 양이 바뀌는 게 아니므로 매번 정책만 바꾸는 것은 사용자 혼란을 야기한다.

2. **세션 단위 vs 아이템 단위**: 토글이 세션 전체를 갈아엎으면 큐가 길 때 무거워진다. 토글을 *프리뷰 영역*에만 적용하고, 실제 핸드오프 마크다운은 *전송 시점*에 결정하는 모델이 안전하다. 즉 `FeedbackHandoffBatch.markdownSnapshot`이 어떤 detailMode로 만들어졌는지 메타데이터로 남겨야 한다.

3. **persistence**: 토글 선택을 사용자가 다음 세션에서도 기억하길 원하는가? 일단은 *세션 메모리*에만 두고, 영속화는 후속 작업으로.

#### 수정 제안

- 토글은 데스크톱 콘솔(MCP-served HTML)에만 둔다. 디바이스 오버레이 토글은 도입하지 않는다.
- `FeedbackHandoffBatch`에 `detailMode: DetailMode = PRECISE` 필드 추가, `markdownSnapshot`이 어떤 모드로 산출되었는지 기록한다.

---

## 3. Phase 2 분석 — Composable Identity (실험)

플랜은 `LocalInspectionTables`, `CompositionData.mapTree`, `parseSourceInformation`, callSiteCandidate, definitionCandidate, wraps 등을 활용해 **Composable 함수명/호출 경로/소스 위치**까지 식별하려 한다.

### 3.1 API 안정성 / 버전 호환성

이 영역은 Compose UI tooling의 *내부* API에 가깝고 마이너 버전마다 깨진다. 구체적으로:

- **`LocalInspectionTables`**: `androidx.compose.ui.tooling`(또는 `tooling-data`)의 Inspectable 진영 API. 정확한 위치는 Compose 1.x 시리즈에서 `androidx.compose.ui.tooling.data` 내부였고, 일부는 `@InternalComposeApi` 또는 `@OptIn` 요구다. 현재 프로젝트 Compose BOM `2026.04.01`은 (BOM 표기상) Compose 1.8.x 라인을 가리킨다 — 이 버전에서 `LocalInspectionTables`의 API surface는 안정 보장이 없다.
- **`CompositionData.mapTree` / `parseSourceInformation`**: `androidx.compose.ui.tooling.data` 패키지 함수. **현재 프로젝트는 `ui-tooling-data` 의존성을 stable path에 추가하지 않았다**. Phase 2에서 추가한다면 optional experimental dependency로 두고 Compose BOM을 따라야 한다. 의존성 추가 비용은 작지만, *runtime*에서만 필요한 게 아니라 inspectable 트리 활성화는 컴포지션 단계의 런타임 효과이므로 *프로덕션 빌드에서도 항상 잡혀야 한다는 부담*이 있다.
- **`@Composable` 함수의 source position 정보**: Compose Compiler가 만들어 넣는 `sourceInformation` 문자열을 파싱해야 한다. 포맷은 비공식이고 컴파일러 버전에 의존한다. Compose Compiler 1.5+에서도 라인 인코딩 방식이 살짝 바뀐 적이 있다. **이 사실 자체가 Phase 2가 영구 실험인 이유다**.

### 3.2 활성화 메커니즘

플랜은 "별도 플래그"라고 했지만 *어디에 두는 플래그*인지 정해야 한다. 후보:

- `BuildConfig.DEBUG && BuildConfig.FIXTHIS_COMPOSABLE_IDENTITY_ENABLED`
- `gradle.properties`의 `fixthis.composableIdentity=true`
- 런타임 `SidekickSession` 토글

권장: **gradle.properties → BuildConfig 상수 → `BridgeEnvironment.experimentalIdentityEnabled` 노출**. MCP 도구 출력에 `experimentalIdentity: true|false`를 함께 보내 agent가 그 데이터의 신뢰도를 안다.

### 3.3 InspectionTables 활성화 부담

`LocalInspectionTables`는 *Composable 트리에 inspect 정보를 보존*시키기 위해 컴포지션 루트에서 `CompositionLocalProvider`로 주입되어 있어야 한다. Compose Preview는 자동으로 활성화하지만 **프로덕션 Activity에서는 기본 활성화되지 않는다**. 따라서 sidekick이 켜지면 자동으로 inspectable 모드를 강제하는 부수효과가 들어가야 한다. 이건 다음을 의미한다:

- 모든 ComposeView/AbstractComposeView가 inspectable 모드로 컴포지션해야 한다 — `androidx.compose.ui.platform.AbstractComposeView`에는 `setContent` 외에 `setComposableContent`처럼 직접 주입할 후크가 없다. 사용자가 제공한 컴포지션을 우회해서 wrapping해야 한다는 뜻이다. 매우 침습적이다.
- 또는 `tooling-data`의 `Slot` 인스펙션을 통해 *기존 컴포지션을 재활용*해서 트리 정보를 끄집어내는 방법(`AbstractComposeView`의 `compositionContext` 추적)을 써야 한다 — 이쪽이 덜 침습적이지만 코드는 더 까다롭다.

플랜은 이 활성화 부담을 전혀 언급하지 않는다. **명시적으로 "Phase 2는 sample 앱에서만 골든 패스를 검증한다"로 범위를 줄여야 현실적**이다.

### 3.4 callSiteCandidate vs definitionCandidate vs wraps

플랜의 의도는 다음과 같다.
- callSiteCandidate: `MyScreen.kt:128`(이 Button을 호출한 곳)
- definitionCandidate: `AppPrimaryButton.kt:42`(이 Composable의 정의)
- wraps: `androidx.compose.material3.Button`(내부적으로 무엇을 감쌌는지)

이 분리는 매우 가치 있다. 그러나 `parseSourceInformation`이 반환하는 정보로는 **(a) call-site의 파일/라인은 비교적 안정적으로 추출**되지만, **(b) "이 함수가 어떤 Composable을 wrapping하는지"는 다음 자식 노드가 무엇인가에서 휴리스틱으로 추론**해야 한다. 항상 하나가 아닐 수 있다(예: `Box(...) { Text(...); Icon(...) }`). 이 휴리스틱이 모호하면 plan의 가치가 줄어든다 — *wraps* 필드는 *List<String>*이거나 *Optional*이어야 한다.

### 3.5 결론

- Phase 2는 **sample 앱 한정 실험**으로 못 박는다.
- Stable Target Evidence v1은 `ui-tooling-data`를 요구하지 않는다. Phase 2에서 추가한다면 optional experimental dependency로 선언하고 Compose BOM을 따르며, 별도 기본 버전 pinning은 피한다.
- `wraps`는 단일 문자열이 아니라 `wrapsCandidates: List<String>`로 정의.
- 모든 산출물에 `experimental: true` 플래그 + Compose runtime 버전을 함께 기록한다.

---

## 4. 테스트 계획 검토

플랜에는 테스트 계획이 명시되어 있지 않다(원본 텍스트에 "테스트" 절이 없다). 검토자가 채워 넣을 것을 가정해 부족한 케이스를 정리한다.

### 4.1 누락 가능성이 큰 단위 테스트

- `TestTagConvention.parse`: 정상/오정형(`comp:Foo`, `comp::var`, `comp:Foo:`, `:Foo:bar`, 빈 문자열, 매우 긴 입력) — 정규식 회귀에 필수.
- `OccurrenceCalculator`: 동일 시그니처 N개 노드, ordinal 계산, MERGED-only/UNMERGED-only edge, VISUAL_AREA에서의 `null` 반환.
- Future subtree renderer: stable parent/children UID contract가 생긴 뒤 cutoff(maxDepth/maxNodesPerLevel) 동작, 사이클 방지, redacted 노드 표시(`<redacted>` 등 — `RedactionPolicy`가 이미 적용됨)를 검증한다.
- `FixThisMarkdownFormatter` × `DetailMode` 전수: compact/precise/full 각각의 골든 마크다운(snapshot test). 현재 `FixThisMarkdownFormatterTest`가 단일 모드만 검사한다.
- `FeedbackQueueFormatter`: detailMode 전파, itemId focus와의 상호작용.

### 4.2 기존 테스트와의 충돌

- `FixThisAnnotation`에 새 필드(`targetEvidence` 등)가 추가되면 `kotlinx.serialization`이 자동으로 default를 받지만, **JSON 라운드트립 골든 테스트가 있다면 깨진다**. `SourceMatcherTest`, `NodeSelectorTest`, `FixThisMarkdownFormatterTest`, `FeedbackQueueFormatterTest`가 영향권. 사전 점검 필수.
- `FeedbackSessionPersistence`가 디스크에 직렬화한 과거 세션(JSON)을 다시 읽을 수 있는가? `schemaVersion = "1.0"` 폴백이 필요하다.

### 4.3 비현실적 테스트 항목

- "디바이스에서 30%/48dp 패딩이 정확히 적용되었는지 픽셀 단위 검증" — 엔드투엔드로는 어렵다. 픽셀 검증은 `ScreenshotStore` 단위 테스트 수준에서 *입력 bounds → 산출 IntCropRect* 함수형 검증으로 충분하다. 디바이스에서는 *시각적 확인*만.
- Phase 2의 callSiteCandidate 정확도를 정량 평가하려는 시도 — 안정 API가 아니므로 *snapshot test*는 곧 깨진다. 대신 sample 앱 골든 시나리오 하나만 두고 회귀 알람으로 활용한다.

---

## 5. 작업 순서 재평가

플랜은 항목 7가지를 **명시적 순서 없이** 나열한다. 의존성을 기준으로 재정렬하면 다음과 같다.

```
[A] 데이터 모델 변경
    A1. FixThisNode parent/children UID contract 검토 (v1 범위 밖)
    A2. ScreenshotInfo에 highlightPath / paddedCropPath 추가
    A3. FixThisAnnotation.targetEvidence 추가 (디폴트 null)
    A4. AnnotationDto / SessionDomainMappers 매핑

[B] 순수 함수 / 디바이스 비의존 로직
    B1. TestTagConvention.parse (compose-core)
    B2. OccurrenceCalculator (compose-core; 입력은 List<FixThisNode> + selectedUid)
    B3. IdentityHint.from (compose-core)
    B4. SubtreeRenderer (compose-core; stable UID parent chain 이후)
    B5. DetailMode enum + FixThisMarkdownFormatter(detail) 분기

[C] 디바이스 사이드 통합
    C1. SemanticsNodeMapper parent/children UID contract는 subtree future work로 보류
    C2. 캡쳐 파이프라인에서 occurrence/identityHint 산출 후 targetEvidence 주입
    C3. ScreenshotCapturer에 highlight 합성 + padded crop 추가
    C4. BridgeServer.readScreenshot kind 화이트리스트 확장

[D] MCP 사이드 통합
    D1. FeedbackQueueFormatter.toMarkdown(session, detail)
    D2. fixthis_read_feedback에 detailMode 인자 추가
    D3. 콘솔 HTML에 [Compact|Precise|Full] 토글
    D4. FeedbackHandoffBatch.detailMode 기록

[E] sample 앱 마이그레이션
    E1. comp:* 컨벤션으로 testTag 일괄 변경
    E2. 골든 시나리오 1개(예: HomeScreen 메인 CTA)에서 evidence dump 확인

[F] Phase 2 (실험)
    F1. ui-tooling-data optional experimental 의존성 검토
    F2. sample 앱 한정 LocalInspectionTables wiring
    F3. callSiteCandidate 휴리스틱 + experimental flag로 가드
```

핵심 의존성 흐름:
- **A → B → C** (모델이 먼저 살아야 함수도 채울 수 있다)
- **B는 A에 비해 거의 병렬 가능**(stable parent/children UID contract를 쓰지 않는 함수는 즉시 가능)
- **C3(스크린샷 합성)은 A2 없이도 가능**하지만, BridgeServer 노출(C4)은 A2가 선결.
- **E는 모든 단계의 검증 통로**다. 플랜은 sample을 부수적으로 다루지만, 실제로는 *데모 가능 증거*가 sample을 통해서만 나온다.
- **F는 항상 마지막**, 그리고 *언제든 보류*.

---

## 6. 완료 기준 검토

플랜이 가정한 전형적인 출력 예시(compact mode 기준):

```
Request: 로그인 버튼 색이 너무 흐려요
Target: Button (testTag=comp:AppPrimaryButton:primary, ordinal 1/1)
Top source: app/src/main/.../AppPrimaryButton.kt:42 (high)
Caution: testTag 컨벤션 매칭으로 confidence high
[highlight.png]
```

이 출력이 *실제로* 나오려면 다음이 모두 성립해야 한다.

| 가정 | 검증 |
|---|---|
| `comp:AppPrimaryButton:primary` 같은 testTag가 sample에 존재 | 골든 패스 태그는 `sample/` 앱에 있어야 한다. overlay의 `StudioTestTags`는 sample handoff 증거가 아니다. |
| `SourceMatcher`가 testTag prefix(`AppPrimaryButton`)를 *컨벤션 인식 기반*으로 가중치를 더 부여 | 현재 SourceMatcher는 testTag 매칭에 +55 가중치만 준다. *컨벤션 일치*는 별도 가산이 필요하다. **SourceMatcher 보완 필요**. |
| `AppPrimaryButton.kt`가 source-index에 등재되어 있고, testTag 텀과 매칭 가능 | `GenerateFixThisSourceIndexTask`가 무엇을 인덱싱하는지 확인 필요(아래 §7). |
| highlight.png가 BridgeServer를 통해 agent에게 전달 가능 | `readScreenshot(kind=highlight)` 추가가 선결. |

따라서 플랜의 *완료* 기준에는 다음이 명시되어야 한다.

- sample 앱에 최소 3개 이상의 `comp:*` testTag 존재, 그 중 하나는 동일 컴포넌트가 N>1개 등장하는 사례.
- SourceIndex가 해당 컴포넌트의 정의 파일을 가지고 있고, testTag-기반 매칭으로 confidence=HIGH가 나옴을 단위 테스트로 보장.
- end-to-end 시나리오: sample 앱 → MCP console Add/Save → `fixthis_read_feedback` JSON에 `targetEvidence.identityHint.composableNameHint == "AppPrimaryButton"` 확인.

이 세 항목이 빠지면 플랜이 *완료*되었더라도 데모는 동작하지 않는다.

### 잘못된 가정 정리

1. "occurrence는 화면 전체에서 셀 수 있다" → 현재 `nearbyNodes`만으로는 불가.
2. "subtree 출력이 가능하다" → stable UID parent chain이 없어 v1 범위 밖이다.
3. "JSON evidence는 단일 모델이다" → 실제로는 `FixThisAnnotation` + `AnnotationDto`로 이중화되어 있다.
4. "fixthis_read_feedback은 신규 도구다" → 이미 존재한다.
5. "Compose ui-tooling-data를 통한 callSiteCandidate가 안정적으로 동작한다" → 비공식 API.

---

## 7. 추가 리스크 및 고려사항

### 7.1 SourceIndex가 어떤 텀을 인덱싱하는가

`SourceMatcher`는 `SourceIndexEntry`의 다음 필드들을 매칭에 쓴다: `text`, `stringResources`, `symbols`, `excerpt`, `contentDescriptions`, `testTags`, `roles`, `activityNames`. 즉 **Gradle 플러그인이 `comp:AppPrimaryButton:primary`를 testTag로 인식해서 인덱스에 넣고 있어야** 컨벤션 매칭이 가산된다. `GenerateFixThisSourceIndexTask`의 정규식이 `Modifier.testTag("...")` 패턴을 파싱하는지 확인하는 것이 선결 — 만약 파싱이 단순 문자열 리터럴 추출이라면 `comp:` 컨벤션도 그대로 들어가긴 한다. **파싱이 컨벤션을 분해해서 `composableNameHint`까지 인덱스에 별도 필드로 저장하면** SourceMatcher의 가산 로직을 매우 깔끔하게 만들 수 있다. 즉 *Gradle 플러그인까지의 경로*가 플랜에 빠져 있다.

### 7.2 redaction 정책과의 충돌

`RedactionPolicy.apply`는 password / sensitive flag 노드의 text/contentDescription/editableText를 `<redacted>`로 마스킹한다. 그런데 `OccurrenceCalculator`가 `role + text` 시그니처로 카운트하면, 동일한 `<redacted>` 텍스트끼리 합쳐지는 부작용이 생긴다(예: 두 개의 패스워드 필드가 동일한 시그니처로 1+1=2로 잡힘). 의도와 정확히 일치하면 문제없지만, `selectedOrdinal` 표기가 *비밀번호 필드의 위치를 우회 노출*할 수 있다는 사이드 채널 우려가 있다(낮은 위험).

권장: occurrence signature는 *redacted 텍스트인 경우 testTag만 사용*하고, redaction 후 텍스트는 시그니처에서 제외.

### 7.3 BOM 1.8.x → CompositionLocal API 차이

Compose 1.8.x에서 `LocalInspectionTables`의 정확한 위치/타입은 1.5/1.6 시점과 다르다. `ui-tooling-data`는 Stable Target Evidence v1 요구사항이 아니다. Phase 2 도입 전에 optional experimental dependency로 선언하고 Compose BOM과 맞춰 sample 앱에서 단독 호출 한 줄이 컴파일되는지부터 검증해야 한다.

### 7.4 protocol versioning

`BridgeProtocol.VERSION`이 존재한다(상태 응답에 노출). nullable additive evidence는 bridge protocol을 깨지 않으므로 Stable Target Evidence v1에서는 bump하지 않는다. future breaking protocol change가 생기면 sidekick의 `BridgeProtocol.VERSION`과 CLI의 `BridgeProtocolVersion` 상수를 함께 업데이트해 구버전 sidekick과 신버전 MCP가 섞일 때 조기 실패하도록 한다.

### 7.5 multi-Activity / dialog overlay

`ComposeRootFinder`는 decorView부터 모든 RootForTest를 모은다. Dialog/PopupWindow는 자체 윈도우라 decorView 트리에 없으면 누락된다(`AbstractComposeView`가 PopupWindow에 들어 있는 경우). occurrence 카운트가 "현재 보이는 모든 노드"를 가정한다면, 이 누락은 카운트를 부정확하게 만든다. 플랜 검증 시 dialog/overlay 시나리오를 sample에 포함시키는 게 좋다.

### 7.6 highlight 합성에서 dim의 시각 자산화

dim된 풀스크린샷은 *agent가 보는 메인 자산*이 된다. 너무 진한 dim(α≈0.6)은 주변 컨텍스트를 망가뜨려 agent가 화면 종류를 식별하기 어렵게 한다. α=0.4 + 흰색 외곽선 2dp + 청색 내부 8dp 하이라이트 정도가 합리적. 이 값은 디자인 토큰화하여 `compose-overlay`의 `FixThisHighlightLayer` 컬러와 통일하는 게 일관성에 좋다(현재 `FixThisHighlightLayer`는 `Color(0xFF00A3FF)` + alpha 0.16).

### 7.7 마크다운 escape의 함정

`FixThisMarkdownFormatter.markdownInline`이 모든 특수문자를 escape한다. subtree의 트리 그림(`├─`, `└─`)이 UTF-8이라 영향은 없지만, identityHint의 `composableNameHint`가 영문/숫자가 아닐 가능성(예: 한글)을 고려할 때 escape 정책이 그대로 적용되면 출력이 가독성을 잃는다. 현재 정책 그대로 두면 안전하나, *컨벤션 hint는 escape하지 않고 `\``로 감싼 inline code*로 출력하는 것이 정보 보존 면에서 낫다. compact 모드에서는 더더욱.

---

## 8. 권고 요약

| # | 권고 | 근거 |
|---|---|---|
| 1 | 모든 신규 명명을 `FixThis*`로 통일 | 코드는 이미 리네임 완료. 플랜 본문은 PointPatch 기준 |
| 2 | `targetEvidence`는 `FixThisAnnotation`의 옵셔널 필드로 추가 | 기존 모델 부채 최소화 |
| 3 | Stable parent/children UID contract를 future work로 분리 | subtree 렌더링의 전제이며 v1 범위 밖 |
| 4 | identityHint/occurrence 계산은 sidekick 캡쳐 파이프라인에 둠 | 화면 전체 노드가 살아 있는 시점 필요 |
| 5 | testTag 컨벤션 정규식을 명시적으로 anchor 시킴 | 구조적 충돌 방지 |
| 6 | SourceMatcher에 컨벤션 매칭 가산 추가 | 컨벤션 hint가 confidence 산출에 반영되도록 |
| 7 | `fixthis_read_feedback`은 *기존 도구에 detailMode 추가* | 신규 도구가 아님 |
| 8 | DetailMode 분기는 `FixThisMarkdownFormatter`와 `FeedbackQueueFormatter`에 전달; JSON은 항상 full | 단일 진실원 유지 |
| 9 | highlight/paddedCrop 경로를 `ScreenshotInfo`에 추가, BridgeServer 화이트리스트 확장 | I/O 보안 정책 유지 |
| 10 | sample 앱을 `comp:*` 컨벤션으로 마이그레이션 | 데모/검증의 전제 |
| 11 | Phase 2는 sample 한정 실험으로 격리, BOM 회귀 감시 | 비공식 API 의존 |
| 12 | nullable additive evidence에는 `BridgeProtocol.VERSION`을 유지; breaking change 때 sidekick/CLI 상수를 함께 올림 | 혼합 버전 안전 |
| 13 | redacted 노드는 occurrence 시그니처에서 텍스트 제외 | 사이드 채널 회피 |
| 14 | `FeedbackHandoffBatch.detailMode` 기록 | 산출물 추적성 |
| 15 | Phase 2 도구 출력에 `experimental: true` + Compose runtime 버전 동봉 | agent 신뢰도 신호 |

---

## 부록 A. 영향 받는 파일/모듈 인벤토리

- `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt` — 스키마 추가
- `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt` — DetailMode
- `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt` — 컨벤션 가산
- `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/identity/` (신설) — TestTagConvention, IdentityHint, OccurrenceCalculator
- `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/inspect/SemanticsNodeMapper.kt` — stable parent/children UID contract를 추가할 때 영향
- `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/screenshot/{ScreenshotCapturer,ScreenshotStore}.kt` — highlight/paddedCrop
- `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt` — readScreenshot kind 확장, future breaking protocol changes only
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt` — fixthis_read_feedback에 detailMode
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt` — DetailMode 분기
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/{SessionDtoModels,SessionDomainMappers,FeedbackHandoffModels}.kt` — DTO 매핑
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleAssets.kt` — 토글 UI
- `fixthis-gradle-plugin/src/main/kotlin/.../task/GenerateFixThisSourceIndexTask.kt` — 컨벤션 분해 인덱싱(권고 6 연계)
- `sample/src/main/java/io/github/beyondwin/fixthis/sample/**` — testTag 마이그레이션
- `gradle/libs.versions.toml` — optional experimental `ui-tooling-data` 추가 검토(Phase 2)
