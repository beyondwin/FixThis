# Edit Intent Analyzer v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace comment-only edit intent classification with a small deterministic analyzer that combines Korean/English trigger phrases with Android/Compose target evidence.

**Architecture:** Keep persisted DTOs and compact handoff grammar stable. Add a pure text lexicon, add a Compose-aware analyzer, and route `EditSurfaceCandidateService` through the analyzer so edit-surface hints use selected node, owner tag, and source candidate evidence. Preserve the existing `EditIntentClassifier` as a compatibility wrapper.

**Tech Stack:** Kotlin, kotlinx.serialization DTOs, JUnit 4, Gradle module `:fixthis-mcp`.

---

## File Structure

- Create `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentLexicon.kt`
  - Owns Korean/English trigger matching only.
  - Returns a set of raw intent signals.
- Create `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentAnalyzer.kt`
  - Combines raw signals with `AnnotationDto`, `SnapshotDto?`, `FixThisNode`, target owner tags, and `SourceCandidate.ownerComposable`.
  - Returns the existing `EditIntent` data class.
- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentClassifier.kt`
  - Keep `EditIntent`.
  - Keep `EditIntentClassifier.classify(comment)` as a compatibility wrapper.
- Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt`
  - Replace comment-only classification with analyzer.
  - Derive component owner from selected node tag first, then resolved owner tag.
- Add `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentLexiconTest.kt`
  - Tests Korean/English trigger matching and content-only suppression inputs.
- Add `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentAnalyzerTest.kt`
  - Tests Compose-aware classification.
- Add `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EditSurfaceCandidateServiceTest.kt`
  - Tests candidate service routing with analyzer output.
- Modify `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentClassifierTest.kt`
  - Keep legacy wrapper behavior covered.

## Task 1: Add Pure Text Lexicon

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentLexicon.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentLexiconTest.kt`

- [ ] **Step 1: Write the failing lexicon test**

Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentLexiconTest.kt`:

```kotlin
@file:Suppress("MaxLineLength")

package io.beyondwin.fixthis.mcp.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditIntentLexiconTest {
    @Test
    fun detectsKoreanAndEnglishColorStyleSignals() {
        assertEquals(setOf(RawEditIntentSignal.COLOR_STYLE, RawEditIntentSignal.TEXT_STYLE), EditIntentLexicon.classify("여기 글자 파란색"))
        assertEquals(setOf(RawEditIntentSignal.COLOR_STYLE, RawEditIntentSignal.BACKGROUND_STYLE), EditIntentLexicon.classify("카드 배경 초록색"))
        assertEquals(setOf(RawEditIntentSignal.COLOR_STYLE, RawEditIntentSignal.TEXT_STYLE), EditIntentLexicon.classify("make this label red"))
        assertEquals(setOf(RawEditIntentSignal.COLOR_STYLE, RawEditIntentSignal.BACKGROUND_STYLE), EditIntentLexicon.classify("card color green"))
        assertEquals(setOf(RawEditIntentSignal.COLOR_STYLE, RawEditIntentSignal.TEXT_STYLE), EditIntentLexicon.classify("텍스트컬러 보라색"))
    }

    @Test
    fun detectsKoreanAndEnglishTypographySignals() {
        assertEquals(setOf(RawEditIntentSignal.TYPOGRAPHY), EditIntentLexicon.classify("여기 텍스트 더크게"))
        assertEquals(setOf(RawEditIntentSignal.TYPOGRAPHY), EditIntentLexicon.classify("글자 크기 작게"))
        assertEquals(setOf(RawEditIntentSignal.TYPOGRAPHY), EditIntentLexicon.classify("make the text size bigger"))
    }

    @Test
    fun detectsKoreanAndEnglishSpacingSignals() {
        assertEquals(setOf(RawEditIntentSignal.SPACING), EditIntentLexicon.classify("여기 아래 바텀마진 8dp더"))
        assertEquals(setOf(RawEditIntentSignal.SPACING), EditIntentLexicon.classify("패딩 간격 줄여줘"))
        assertEquals(setOf(RawEditIntentSignal.SPACING), EditIntentLexicon.classify("add bottom margin 8dp"))
    }

    @Test
    fun detectsContentOnlyWithoutDiscardingExplicitStyleSignals() {
        assertEquals(setOf(RawEditIntentSignal.CONTENT_ONLY), EditIntentLexicon.classify("Rename this to Checkout"))
        assertEquals(setOf(RawEditIntentSignal.CONTENT_ONLY), EditIntentLexicon.classify("문구를 결제로 변경"))
        assertTrue(EditIntentLexicon.classify("change text color to red").contains(RawEditIntentSignal.CONTENT_ONLY))
        assertTrue(EditIntentLexicon.classify("change text color to red").contains(RawEditIntentSignal.COLOR_STYLE))
        assertTrue(EditIntentLexicon.classify("change text color to red").contains(RawEditIntentSignal.TEXT_STYLE))
    }

    @Test
    fun returnsEmptySetForUnsupportedOrEmptyComments() {
        assertEquals(emptySet<RawEditIntentSignal>(), EditIntentLexicon.classify(""))
        assertEquals(emptySet<RawEditIntentSignal>(), EditIntentLexicon.classify("   "))
        assertEquals(emptySet<RawEditIntentSignal>(), EditIntentLexicon.classify("여기 좀 이상함"))
    }
}
```

- [ ] **Step 2: Run the lexicon test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditIntentLexiconTest" --no-daemon
```

Expected: FAIL because `RawEditIntentSignal` and `EditIntentLexicon` are unresolved.

- [ ] **Step 3: Implement the lexicon**

Create `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentLexicon.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session

internal enum class RawEditIntentSignal {
    COLOR_STYLE,
    BACKGROUND_STYLE,
    TEXT_STYLE,
    TYPOGRAPHY,
    SPACING,
    CONTENT_ONLY,
}

internal object EditIntentLexicon {
    fun classify(comment: String): Set<RawEditIntentSignal> {
        val normalized = comment.lowercase().trim()
        if (normalized.isEmpty()) return emptySet()

        val signals = linkedSetOf<RawEditIntentSignal>()
        if (normalized.hasAny("색", "색상", "컬러", "파란", "빨간", "초록", "보라", "color", "blue", "red", "green", "purple")) {
            signals += RawEditIntentSignal.COLOR_STYLE
        }
        if (normalized.hasAny("배경", "배경색", "카드색", "background", "container", "card color")) {
            signals += RawEditIntentSignal.BACKGROUND_STYLE
        }
        if (normalized.hasAny("글자", "글자색", "텍스트", "텍스트색", "label", "text color", "label color")) {
            signals += RawEditIntentSignal.TEXT_STYLE
        }
        if (normalized.hasAny("크게", "작게", "폰트", "글씨", "글자 크기", "텍스트 크기", "더크게", "더 크게", "font", "type", "text size", "bigger", "smaller", "larger")) {
            signals += RawEditIntentSignal.TYPOGRAPHY
        }
        if (normalized.hasAny("마진", "패딩", "간격", "바텀마진", "아래", "여백", "dp", "margin", "padding", "spacing", "gap", "bottom", "top")) {
            signals += RawEditIntentSignal.SPACING
        }
        if (normalized.hasAny("문구", "텍스트를", "이름", "바꿔", "변경", "rename", "change text", "copy", "label to", "text to")) {
            signals += RawEditIntentSignal.CONTENT_ONLY
        }
        return signals
    }

    private fun String.hasAny(vararg tokens: String): Boolean = tokens.any { contains(it.lowercase()) }
}
```

- [ ] **Step 4: Run the lexicon test and verify it passes**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditIntentLexiconTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentLexicon.kt fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentLexiconTest.kt
git commit -m "feat(mcp): add edit intent lexicon"
```

## Task 2: Add Compose-Aware Analyzer

**Files:**
- Create: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentAnalyzer.kt`
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentClassifier.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentAnalyzerTest.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentClassifierTest.kt`

- [ ] **Step 1: Write the failing analyzer test**

Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentAnalyzerTest.kt`:

```kotlin
@file:Suppress("LongParameterList", "MaxLineLength")

package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditIntentAnalyzerTest {
    @Test
    fun returnsChipColorForColorRequestInsideChipComponent() {
        val chip = node("resolved-chip", text = listOf("Resolved"), testTag = "comp:StatusChip:resolved")
        val item = item(comment = "여기 보라색", selectedNode = chip)

        val intent = EditIntentAnalyzer.analyze(item, screenWith(chip))

        assertEquals(EditSurfaceKindDto.CHIP_COLOR, intent.primaryKind)
        assertTrue(intent.reasons.contains(EditSurfaceReasonDto.STYLE_INTENT))
        assertTrue(intent.reasons.contains(EditSurfaceReasonDto.COMPONENT_DEFINITION))
    }

    @Test
    fun returnsTextColorForTextNodeColorRequest() {
        val owner = node("metric-card", testTag = "comp:MetricCard:summary", bounds = FixThisRect(0f, 0f, 300f, 200f), path = listOf("root", "card"))
        val label = node("metric-label", text = listOf("Resolved this week"), bounds = FixThisRect(20f, 40f, 220f, 80f), path = listOf("root", "card", "label"))
        val item = item(comment = "여기 글자 파란색", selectedNode = label)

        val intent = EditIntentAnalyzer.analyze(item, screenWith(owner, label))

        assertEquals(EditSurfaceKindDto.TEXT_COLOR, intent.primaryKind)
        assertTrue(intent.reasons.contains(EditSurfaceReasonDto.STYLE_INTENT))
    }

    @Test
    fun returnsContainerColorForBackgroundRequest() {
        val card = node("metric-card", testTag = "comp:MetricCard:summary")
        val item = item(comment = "카드 배경 초록색", selectedNode = card)

        val intent = EditIntentAnalyzer.analyze(item, screenWith(card))

        assertEquals(EditSurfaceKindDto.CONTAINER_COLOR, intent.primaryKind)
        assertTrue(intent.reasons.contains(EditSurfaceReasonDto.STYLE_INTENT))
    }

    @Test
    fun returnsTypographyForTextSizeRequestOnTextNode() {
        val title = node("section-title", text = listOf("Priority feedback"))
        val item = item(comment = "여기 텍스트 더크게", selectedNode = title)

        val intent = EditIntentAnalyzer.analyze(item, screenWith(title))

        assertEquals(EditSurfaceKindDto.TYPOGRAPHY, intent.primaryKind)
        assertTrue(intent.reasons.contains(EditSurfaceReasonDto.TYPOGRAPHY_INTENT))
    }

    @Test
    fun returnsSpacingForMarginOrDpRequest() {
        val card = node("feedback-card", testTag = "comp:FeedbackCard:priority")
        val item = item(comment = "여기 아래 바텀마진 8dp 더", selectedNode = card)

        val intent = EditIntentAnalyzer.analyze(item, screenWith(card))

        assertEquals(EditSurfaceKindDto.SPACING, intent.primaryKind)
        assertTrue(intent.reasons.contains(EditSurfaceReasonDto.LAYOUT_INTENT))
    }

    @Test
    fun returnsUnknownForContentOnlyFeedback() {
        val button = node("checkout-button", text = listOf("Continue"))
        val item = item(comment = "Rename this to Checkout", selectedNode = button)

        val intent = EditIntentAnalyzer.analyze(item, screenWith(button))

        assertEquals(EditSurfaceKindDto.UNKNOWN, intent.primaryKind)
        assertEquals(emptyList<EditSurfaceReasonDto>(), intent.reasons)
    }

    @Test
    fun commentOnlyCompatibilityKeepsLegacyClassifierBehavior() {
        assertEquals(EditSurfaceKindDto.CONTAINER_COLOR, EditIntentClassifier.classify("여기 배경 초록색").primaryKind)
        assertEquals(EditSurfaceKindDto.TEXT_COLOR, EditIntentClassifier.classify("make this label red").primaryKind)
        assertEquals(EditSurfaceKindDto.UNKNOWN, EditIntentClassifier.classify("Rename this to Checkout").primaryKind)
    }

    private fun item(comment: String, selectedNode: FixThisNode): AnnotationDto = AnnotationDto(
        itemId = "item-${selectedNode.uid}",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Node(selectedNode.uid, selectedNode.boundsInWindow),
        selectedNode = selectedNode,
        sourceCandidates = listOf(sourceCandidate(selectedNode.uid)),
        comment = comment,
    )

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        testTag: String? = null,
        bounds: FixThisRect = FixThisRect(0f, 0f, 120f, 80f),
        path: List<String> = listOf("root", uid),
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
        testTag = testTag,
        path = path,
    )

    private fun screenWith(vararg nodes: FixThisNode): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 1L,
        displayName = "MainActivity",
        roots = listOf(SnapshotRootDto(rootIndex = 0, boundsInWindow = FixThisRect(0f, 0f, 400f, 800f), mergedNodes = nodes.toList())),
    )

    private fun sourceCandidate(owner: String): SourceCandidate = SourceCandidate(
        file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/$owner.kt",
        line = 20,
        score = 0.8,
        confidence = SelectionConfidence.MEDIUM,
    )
}
```

- [ ] **Step 2: Run the analyzer test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditIntentAnalyzerTest" --no-daemon
```

Expected: FAIL because `EditIntentAnalyzer` is unresolved.

- [ ] **Step 3: Implement the analyzer**

Create `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentAnalyzer.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.SourceCandidate

internal object EditIntentAnalyzer {
    fun analyze(item: AnnotationDto, screen: SnapshotDto?): EditIntent {
        val owner = TargetOwnerResolver.resolve(item, screen)
        val ownerTag = owner?.node?.testTag
        return analyze(
            comment = item.comment,
            selectedNode = item.selectedNode,
            ownerTag = ownerTag,
            sourceCandidates = item.sourceCandidates,
        )
    }

    fun analyzeCommentOnly(comment: String): EditIntent = analyze(
        comment = comment,
        selectedNode = null,
        ownerTag = null,
        sourceCandidates = emptyList(),
    )

    private fun analyze(
        comment: String,
        selectedNode: FixThisNode?,
        ownerTag: String?,
        sourceCandidates: List<SourceCandidate>,
    ): EditIntent {
        val signals = EditIntentLexicon.classify(comment)
        if (signals.isEmpty()) return unknown()
        if (signals == setOf(RawEditIntentSignal.CONTENT_ONLY)) return unknown()

        return when {
            RawEditIntentSignal.SPACING in signals ->
                EditIntent(EditSurfaceKindDto.SPACING, listOf(EditSurfaceReasonDto.LAYOUT_INTENT))
            RawEditIntentSignal.TYPOGRAPHY in signals && (selectedNode == null || selectedNode.isTextLike()) ->
                EditIntent(EditSurfaceKindDto.TYPOGRAPHY, listOf(EditSurfaceReasonDto.TYPOGRAPHY_INTENT))
            RawEditIntentSignal.COLOR_STYLE in signals -> colorIntent(signals, selectedNode, ownerTag, sourceCandidates)
            else -> unknown()
        }
    }

    private fun colorIntent(
        signals: Set<RawEditIntentSignal>,
        selectedNode: FixThisNode?,
        ownerTag: String?,
        sourceCandidates: List<SourceCandidate>,
    ): EditIntent = when {
        isChipLike(selectedNode, ownerTag, sourceCandidates) ->
            EditIntent(
                EditSurfaceKindDto.CHIP_COLOR,
                listOf(EditSurfaceReasonDto.STYLE_INTENT, EditSurfaceReasonDto.COMPONENT_DEFINITION),
            )
        RawEditIntentSignal.BACKGROUND_STYLE in signals ->
            EditIntent(EditSurfaceKindDto.CONTAINER_COLOR, listOf(EditSurfaceReasonDto.STYLE_INTENT))
        RawEditIntentSignal.TEXT_STYLE in signals || selectedNode.isTextLike() ->
            EditIntent(EditSurfaceKindDto.TEXT_COLOR, listOf(EditSurfaceReasonDto.STYLE_INTENT))
        else -> unknown()
    }

    private fun isChipLike(
        selectedNode: FixThisNode?,
        ownerTag: String?,
        sourceCandidates: List<SourceCandidate>,
    ): Boolean {
        val terms = buildList {
            selectedNode?.testTag?.let(::add)
            ownerTag?.let(::add)
            sourceCandidates.mapNotNullTo(this) { it.ownerComposable }
        }
        return terms.any { term ->
            term.contains("chip", ignoreCase = true) ||
                term.contains("badge", ignoreCase = true) ||
                term.contains("pill", ignoreCase = true)
        }
    }

    private fun FixThisNode?.isTextLike(): Boolean = this != null && (
        text.isNotEmpty() ||
            editableText?.isNotBlank() == true ||
            role.equals("Text", ignoreCase = true)
        )

    private fun unknown(): EditIntent = EditIntent(EditSurfaceKindDto.UNKNOWN, emptyList())
}
```

- [ ] **Step 4: Convert the legacy classifier into a wrapper**

Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentClassifier.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session

internal data class EditIntent(
    val primaryKind: EditSurfaceKindDto,
    val reasons: List<EditSurfaceReasonDto>,
)

internal object EditIntentClassifier {
    fun classify(comment: String): EditIntent = EditIntentAnalyzer.analyzeCommentOnly(comment)
}
```

- [ ] **Step 5: Run analyzer and legacy classifier tests**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditIntentAnalyzerTest" --tests "*EditIntentClassifierTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentAnalyzer.kt fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentClassifier.kt fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentAnalyzerTest.kt
git commit -m "feat(mcp): analyze edit intent with compose evidence"
```

## Task 3: Route Edit Surface Candidate Service Through Analyzer

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt`
- Test: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EditSurfaceCandidateServiceTest.kt`

- [ ] **Step 1: Write the failing service test**

Create `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EditSurfaceCandidateServiceTest.kt`:

```kotlin
@file:Suppress("LongParameterList", "MaxLineLength")

package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSurfaceCandidateServiceTest {
    @Test
    fun buildsChipColorCandidateFromSelectedComponentTag() {
        val chip = node("resolved-chip", text = listOf("Resolved"), testTag = "comp:StatusChip:resolved")
        val item = item(
            comment = "여기 보라색",
            selectedNode = chip,
            sourceCandidates = listOf(
                sourceCandidate(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/StatusChip.kt",
                    matchedTerms = listOf("StatusChip"),
                    ownerComposable = "StatusChip",
                ),
            ),
        )

        val candidates = EditSurfaceCandidateService.build(item, screenWith(chip))

        assertEquals(1, candidates.size)
        assertEquals(EditSurfaceKindDto.CHIP_COLOR, candidates.single().kind)
        assertTrue(candidates.single().file.endsWith("StatusChip.kt"))
        assertTrue(candidates.single().reasons.contains(EditSurfaceReasonDto.STYLE_INTENT))
        assertTrue(candidates.single().reasons.contains(EditSurfaceReasonDto.COMPONENT_DEFINITION))
    }

    @Test
    fun doesNotBuildCandidateForContentOnlyFeedback() {
        val button = node("checkout-button", text = listOf("Continue"), testTag = "comp:PrimaryButton:default")
        val item = item(
            comment = "Rename this to Checkout",
            selectedNode = button,
            sourceCandidates = listOf(
                sourceCandidate(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/PrimaryButton.kt",
                    matchedTerms = listOf("PrimaryButton"),
                    ownerComposable = "PrimaryButton",
                ),
            ),
        )

        val candidates = EditSurfaceCandidateService.build(item, screenWith(button))

        assertEquals(emptyList<EditSurfaceCandidateDto>(), candidates)
    }

    @Test
    fun keepsOwnerBasedTextColorCandidateForTextInsideTaggedContainer() {
        val owner = node("metric-card", testTag = "comp:MetricCard:summary", bounds = FixThisRect(0f, 0f, 300f, 200f), path = listOf("root", "card"))
        val label = node("metric-label", text = listOf("Resolved this week"), bounds = FixThisRect(20f, 40f, 220f, 80f), path = listOf("root", "card", "label"))
        val item = item(
            comment = "여기 글자 파란색",
            selectedNode = label,
            sourceCandidates = listOf(
                sourceCandidate(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/MetricCard.kt",
                    matchedTerms = listOf("MetricCard"),
                    ownerComposable = "MetricCard",
                ),
            ),
        )

        val candidates = EditSurfaceCandidateService.build(item, screenWith(owner, label))

        assertEquals(1, candidates.size)
        assertEquals(EditSurfaceKindDto.TEXT_COLOR, candidates.single().kind)
        assertTrue(candidates.single().file.endsWith("MetricCard.kt"))
    }

    private fun item(
        comment: String,
        selectedNode: FixThisNode,
        sourceCandidates: List<SourceCandidate>,
    ): AnnotationDto = AnnotationDto(
        itemId = "item-${selectedNode.uid}",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Node(selectedNode.uid, selectedNode.boundsInWindow),
        selectedNode = selectedNode,
        sourceCandidates = sourceCandidates,
        comment = comment,
    )

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        testTag: String? = null,
        bounds: FixThisRect = FixThisRect(0f, 0f, 120f, 80f),
        path: List<String> = listOf("root", uid),
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
        testTag = testTag,
        path = path,
    )

    private fun screenWith(vararg nodes: FixThisNode): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 1L,
        displayName = "MainActivity",
        roots = listOf(SnapshotRootDto(rootIndex = 0, boundsInWindow = FixThisRect(0f, 0f, 400f, 800f), mergedNodes = nodes.toList())),
    )

    private fun sourceCandidate(
        file: String,
        matchedTerms: List<String>,
        ownerComposable: String?,
    ): SourceCandidate = SourceCandidate(
        file = file,
        line = 20,
        score = 0.8,
        matchedTerms = matchedTerms,
        matchReasons = listOf("selected text"),
        confidence = SelectionConfidence.MEDIUM,
        ownerComposable = ownerComposable,
    )
}
```

- [ ] **Step 2: Run the service test and verify it fails**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditSurfaceCandidateServiceTest" --no-daemon
```

Expected: FAIL on the chip case because the service currently calls the comment-only classifier and derives component owner only from the resolved owner, not the selected node tag.

- [ ] **Step 3: Route service through analyzer and selected-node component identity**

Modify `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt`:

```kotlin
package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.identity.TestTagConvention
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate

internal object EditSurfaceCandidateService {
    fun build(
        item: AnnotationDto,
        screen: SnapshotDto?,
    ): List<EditSurfaceCandidateDto> {
        val intent = EditIntentAnalyzer.analyze(item, screen)
        if (intent.primaryKind == EditSurfaceKindDto.UNKNOWN) return emptyList()

        val owner = TargetOwnerResolver.resolve(item, screen)
        val ownerComposable = componentNameFrom(item.selectedNode?.testTag)
            ?: componentNameFrom(owner?.node?.testTag)
        val candidates = mutableListOf<EditSurfaceCandidateDto>()

        ownerComposable?.let { composable ->
            item.sourceCandidates.firstOrNull { candidate ->
                candidate.file.substringAfterLast('/').removeSuffix(".kt") == composable ||
                    candidate.matchedTerms.any { it == composable } ||
                    candidate.ownerComposable == composable
            }?.let { source ->
                candidates += source.toEditSurface(
                    kind = intent.primaryKind,
                    confidence = SelectionConfidence.MEDIUM,
                    reasons = intent.reasons +
                        EditSurfaceReasonDto.TARGET_OWNER +
                        EditSurfaceReasonDto.COMPONENT_DEFINITION,
                )
            }
        }

        if (candidates.isEmpty()) {
            item.sourceCandidates.firstOrNull { it.matchReasons.contains("selected text") }?.let { source ->
                candidates += source.toEditSurface(
                    kind = intent.primaryKind,
                    confidence = SelectionConfidence.MEDIUM,
                    reasons = intent.reasons + EditSurfaceReasonDto.SELECTED_TEXT_RENDERER,
                )
            }
        }

        if (intent.primaryKind == EditSurfaceKindDto.SPACING) {
            item.sourceCandidates.firstOrNull()?.let { source ->
                candidates += source.toEditSurface(
                    kind = EditSurfaceKindDto.SPACING,
                    confidence = SelectionConfidence.LOW,
                    reasons = intent.reasons + EditSurfaceReasonDto.CALL_SITE,
                )
            }
        }

        return candidates.distinctBy { it.file to it.line }.take(2)
    }

    private fun componentNameFrom(testTag: String?): String? = TestTagConvention.parse(testTag)?.composableName

    private fun SourceCandidate.toEditSurface(
        kind: EditSurfaceKindDto,
        confidence: SelectionConfidence,
        reasons: List<EditSurfaceReasonDto>,
    ): EditSurfaceCandidateDto = EditSurfaceCandidateDto(
        kind = kind,
        file = file,
        repoFile = repoFile,
        line = line,
        confidence = confidence,
        reasons = reasons.distinct(),
        note = "source candidate identifies data text; editSurface identifies likely rendering code".takeIf {
            kind == EditSurfaceKindDto.TEXT_COLOR || kind == EditSurfaceKindDto.TYPOGRAPHY
        },
    )
}
```

- [ ] **Step 4: Run the service test and verify it passes**

Run:

```bash
./gradlew :fixthis-mcp:test --tests "*EditSurfaceCandidateServiceTest" --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/EditSurfaceCandidateServiceTest.kt
git commit -m "feat(mcp): route edit surface candidates through analyzer"
```

## Task 4: Run Focused Regression Suite

**Files:**
- Verify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentLexicon.kt`
- Verify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentAnalyzer.kt`
- Verify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditIntentClassifier.kt`
- Verify: `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/EditSurfaceCandidateService.kt`
- Verify: `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CopyPromptEditSurfaceRendererTest.kt`

- [ ] **Step 1: Run focused MCP session tests**

Run:

```bash
./gradlew :fixthis-mcp:test \
  --tests "*EditIntentLexiconTest" \
  --tests "*EditIntentAnalyzerTest" \
  --tests "*EditIntentClassifierTest" \
  --tests "*EditSurfaceCandidateServiceTest" \
  --tests "*CopyPromptEditSurfaceRendererTest" \
  --no-daemon
```

Expected: PASS.

- [ ] **Step 2: Run full MCP tests**

Run:

```bash
./gradlew :fixthis-mcp:test --no-daemon
```

Expected: PASS.

- [ ] **Step 3: Check reference docs impact**

Run:

```bash
git diff -- docs/reference/output-schema.md docs/reference/mcp-tools.md docs/reference/feedback-console-contract.md
```

Expected: no diff. This implementation changes internal classification behavior but does not rename fields, add enum values, or change compact handoff grammar.

- [ ] **Step 4: Inspect final diff**

Run:

```bash
git diff --stat HEAD
git diff -- fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session
```

Expected: diff contains only lexicon/analyzer additions, classifier wrapper simplification, candidate service routing, and related tests.

- [ ] **Step 5: Commit any verification-only test fixes**

If Step 1 or Step 2 required small test corrections, commit only those corrections:

```bash
git add fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session
git commit -m "test(mcp): cover edit intent analyzer regressions"
```

If no corrections were needed, do not create an empty commit.

## Self-Review

- Spec coverage:
  - Korean/English-only scope is implemented by `EditIntentLexicon`.
  - No LLM or network path is introduced.
  - Compose evidence is implemented by `EditIntentAnalyzer`.
  - Unknown/ambiguous comments produce `UNKNOWN`.
  - DTO field names and Markdown grammar remain unchanged.
- Placeholder scan:
  - The plan contains concrete file paths, code snippets, commands, and expected outcomes.
- Type consistency:
  - `RawEditIntentSignal`, `EditIntentLexicon`, `EditIntentAnalyzer`, `EditIntent`, `EditSurfaceKindDto`, `EditSurfaceReasonDto`, `AnnotationDto`, and `SnapshotDto` names match the planned code.
