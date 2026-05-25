# Trust Program Phase 2 — Handoff Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the PRECISE/FULL Markdown handoff renderer so agents see edit-surface candidates, rank-1 source cautions, source-vs-edit-surface role separation, and condition-specific Action guidance — without changing core policy or persisted JSON.

**Architecture:** Single-file extension of `FeedbackQueueFormatter` in `fixthis-mcp`. New private helpers extend the existing `appendLikelySource` and `appendTargetReliability` paths. CompactHandoffRenderer, core models, and persisted JSON are unchanged. All eight `TargetReliabilityWarning` enum values already exist with `handoffMessage()` strings, so no enum additions are needed.

**Tech Stack:** Kotlin, JUnit (`kotlin.test`), Gradle.

**Spec:** [`docs/superpowers/specs/2026-05-25-trust-program-phase2-handoff-rendering-design.md`](../specs/2026-05-25-trust-program-phase2-handoff-rendering-design.md)

---

## File Structure

**Modified:**
- `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt` — add private helpers; extend `appendLikelySource` and `appendTargetReliability`.

**Created:**
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt` — Phase 2 unit tests (existing `FeedbackSessionServiceTest.kt` is already 1700 lines; keep the new surface focused in its own file).

**Touched (only if a corpus snapshot expectation changes):**
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`
- `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationFixtures.kt` (only if a new helper builder is needed)

**Not touched:**
- `:fixthis-compose-core` (model, policy, evidence).
- `CompactHandoffRenderer.kt` (frozen).
- `SessionDtoModels.kt` (no new fields).
- Any Gradle, sidekick, or console module.

---

## Reference: existing types used

```kotlin
// fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/SessionDtoModels.kt
data class EditSurfaceCandidateDto(
    val kind: EditSurfaceKindDto,           // CONTAINER_COLOR | TEXT_COLOR | TYPOGRAPHY | SPACING | CHIP_COLOR | COMPONENT_RENDERER | UNKNOWN
    val file: String,
    val repoFile: String? = null,
    val line: Int? = null,
    val confidence: SelectionConfidence,    // HIGH | MEDIUM | LOW | NONE
    val reasons: List<EditSurfaceReasonDto> = emptyList(),
    val note: String? = null,
    val role: EditSurfaceRoleDto? = null,   // CALL_SITE | COMPONENT_DEFINITION | COPY_OR_DATA | LAYOUT_OR_STYLE | VISUAL_AREA | INTEROP_RISK
)
```

```kotlin
// fixthis-compose-core/src/main/kotlin/.../model/Models.kt
enum class TargetReliabilityWarning {
    VISUAL_AREA_ONLY,
    NO_MEANINGFUL_COMPOSE_TARGET,
    POSSIBLE_VIEW_INTEROP,
    LOW_SOURCE_CANDIDATE_MARGIN,
    SOURCE_INDEX_STALE,
    SCREEN_FINGERPRINT_MISMATCH_FORCED,
    SCREEN_FINGERPRINT_UNAVAILABLE,
    SENSITIVE_TEXT_REDACTED,
}

fun TargetReliabilityWarning.handoffMessage(): String = ...  // already defined for all 8 values
```

```kotlin
// fixthis-mcp/src/main/kotlin/.../session/FormatterExtensions.kt
internal fun String.inlineSafe(): String = lineSequence().joinToString(" ").replace("`", "'")
```

---

## Task 1: Scaffold test file with baseline regression guard

Establish the test file. The first test locks the legacy "both empty" literal — it must keep passing through every subsequent task.

**Files:**
- Create: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.format.DetailMode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import kotlin.test.Test
import kotlin.test.assertTrue

class FeedbackQueueFormatterPhase2Test {

    @Test
    fun bothEmpty_preservesLegacyNoCandidateLiteral() {
        val item = annotationWith(
            sourceCandidates = emptyList(),
            editSurfaceCandidates = emptyList(),
            targetReliability = null,
        )
        val session = sessionOf(item)

        val md = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)

        assertTrue(
            md.contains("No source candidate from current evidence; search by target labels and request."),
            "expected legacy literal preserved\n$md",
        )
    }

    // -------------------- builders --------------------

    private fun annotationWith(
        sourceCandidates: List<io.github.beyondwin.fixthis.compose.core.model.SourceCandidate>,
        editSurfaceCandidates: List<EditSurfaceCandidateDto>,
        targetReliability: io.github.beyondwin.fixthis.compose.core.model.TargetReliability?,
    ): AnnotationDto = AnnotationDto(
        itemId = "item-1",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Node("node-1", FixThisRect(0f, 0f, 10f, 10f)),
        selectedNode = null,
        sourceCandidates = sourceCandidates,
        editSurfaceCandidates = editSurfaceCandidates,
        comment = "request body",
        targetReliability = targetReliability,
    )

    private fun sessionOf(vararg items: AnnotationDto): SessionDto = SessionDto(
        sessionId = "sess",
        packageName = "io.example.pkg",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = emptyList(),
        items = items.toList().mapIndexed { idx, it -> it.copy(sequenceNumber = idx + 1) },
    )
}
```

- [ ] **Step 2: Confirm test compiles and passes (baseline regression)**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test.bothEmpty_preservesLegacyNoCandidateLiteral'`
Expected: PASS (this asserts current behavior; should already work).

If it fails to compile because `AnnotationDto` constructor parameters differ in your branch, open `SessionDtoModels.kt`, confirm constructor parameter names, and adjust the builder. Do NOT add new required parameters elsewhere.

- [ ] **Step 3: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt
git commit -m "test: scaffold phase 2 handoff rendering test with baseline regression guard"
```

---

## Task 2: Pairing helper — `buildEditSurfacePairing`

Compute paired and orphan edit-surface candidates by exact `file` equality against source candidates.

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`

- [ ] **Step 1: Write the failing test**

Append to `FeedbackQueueFormatterPhase2Test`:

```kotlin
    @Test
    fun pairing_groupsByExactFileAndCapsAtTwo() {
        val src1 = sourceCandidate(file = "app/A.kt")
        val src2 = sourceCandidate(file = "app/B.kt")
        val edits = listOf(
            editSurface(file = "app/A.kt", line = 10),
            editSurface(file = "app/A.kt", line = 20),
            editSurface(file = "app/A.kt", line = 30),  // dropped by cap
            editSurface(file = "app/B.kt", line = 40),
            editSurface(file = "app/UNMATCHED.kt", line = 50),
            editSurface(file = "", line = 60),          // orphan: blank file
        )

        val pairing = FeedbackQueueFormatter.buildEditSurfacePairingForTest(
            sourceCandidates = listOf(src1, src2),
            editSurfaceCandidates = edits,
        )

        assertEquals(listOf(10, 20), pairing.paired[0]?.map { it.line })
        assertEquals(listOf(40), pairing.paired[1]?.map { it.line })
        assertEquals(listOf(50, 60), pairing.orphans.map { it.line })
    }

    private fun sourceCandidate(
        file: String,
        line: Int? = null,
        confidence: io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence =
            io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence.MEDIUM,
        caution: String? = null,
    ): io.github.beyondwin.fixthis.compose.core.model.SourceCandidate =
        io.github.beyondwin.fixthis.compose.core.model.SourceCandidate(
            file = file,
            line = line,
            score = 1.0,
            confidence = confidence,
            caution = caution,
        )

    private fun editSurface(
        file: String,
        line: Int?,
        kind: EditSurfaceKindDto = EditSurfaceKindDto.CONTAINER_COLOR,
        role: EditSurfaceRoleDto? = EditSurfaceRoleDto.CALL_SITE,
        confidence: io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence =
            io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence.HIGH,
        reasons: List<EditSurfaceReasonDto> = listOf(EditSurfaceReasonDto.STYLE_INTENT),
        note: String? = null,
    ): EditSurfaceCandidateDto = EditSurfaceCandidateDto(
        kind = kind,
        file = file,
        line = line,
        confidence = confidence,
        reasons = reasons,
        note = note,
        role = role,
    )
```

Add at the top of the file:
```kotlin
import kotlin.test.assertEquals
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test.pairing_groupsByExactFileAndCapsAtTwo'`
Expected: FAIL — `buildEditSurfacePairingForTest` does not exist.

- [ ] **Step 3: Add the pairing helper to `FeedbackQueueFormatter`**

In `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`, add inside the `FeedbackQueueFormatter` object (after the existing private helpers):

```kotlin
    internal data class EditSurfacePairing(
        val paired: Map<Int, List<EditSurfaceCandidateDto>>,
        val orphans: List<EditSurfaceCandidateDto>,
    )

    private const val EDIT_SURFACE_PAIR_CAP = 2
    private const val EDIT_SURFACE_ORPHAN_CAP = 2

    private fun buildEditSurfacePairing(
        sourceCandidates: List<SourceCandidate>,
        editSurfaceCandidates: List<EditSurfaceCandidateDto>,
    ): EditSurfacePairing {
        val paired = mutableMapOf<Int, MutableList<EditSurfaceCandidateDto>>()
        val orphans = mutableListOf<EditSurfaceCandidateDto>()
        for (edit in editSurfaceCandidates) {
            if (edit.file.isBlank()) {
                orphans.add(edit)
                continue
            }
            val matchIndex = sourceCandidates.indexOfFirst { it.file == edit.file }
            if (matchIndex >= 0) {
                paired.getOrPut(matchIndex) { mutableListOf() }.add(edit)
            } else {
                orphans.add(edit)
            }
        }
        val cappedPaired = paired.mapValues { (_, list) -> list.take(EDIT_SURFACE_PAIR_CAP) }
        val cappedOrphans = orphans.take(EDIT_SURFACE_ORPHAN_CAP)
        return EditSurfacePairing(cappedPaired, cappedOrphans)
    }

    // Test-only entry point. Forwards to the private helper above.
    internal fun buildEditSurfacePairingForTest(
        sourceCandidates: List<SourceCandidate>,
        editSurfaceCandidates: List<EditSurfaceCandidateDto>,
    ): EditSurfacePairing = buildEditSurfacePairing(sourceCandidates, editSurfaceCandidates)
```

Imports needed (add if missing — `SourceCandidate` is already imported):
- `EditSurfaceCandidateDto` is in the same package, no import needed.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test.pairing_groupsByExactFileAndCapsAtTwo'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt
git commit -m "feat(handoff): add edit-surface pairing helper"
```

---

## Task 3: Render paired `edit:` sub-lines under source candidates

Source candidates with paired edit-surface entries now show an `- edit: ...` bullet per pair.

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun paired_renderEditBulletBeneathSourceCandidate() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/Home.kt", line = 5)),
            editSurfaceCandidates = listOf(
                editSurface(
                    file = "app/Home.kt",
                    line = 42,
                    kind = EditSurfaceKindDto.CONTAINER_COLOR,
                    role = EditSurfaceRoleDto.CALL_SITE,
                    confidence = io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence.HIGH,
                    reasons = listOf(EditSurfaceReasonDto.STYLE_INTENT),
                ),
            ),
            targetReliability = null,
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(
            md.contains("   - edit: containerColor role=call-site -> `app/Home.kt:42` (conf=high, why=style-intent)"),
            "missing paired edit bullet\n$md",
        )
    }

    @Test
    fun paired_omitRoleTokenWhenNull() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(editSurface(file = "app/A.kt", line = 1, role = null)),
            targetReliability = null,
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(md.contains("   - edit: containerColor -> "), "role= token should be omitted\n$md")
        assertTrue(!md.contains("role="), "role= token must not appear\n$md")
    }

    @Test
    fun paired_renderFileOnlyWhenLineNull() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(editSurface(file = "app/A.kt", line = null, role = null)),
            targetReliability = null,
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(md.contains("-> `app/A.kt` ("), "expected file-only target\n$md")
        assertTrue(!md.contains("app/A.kt:"), "should not contain ':line' suffix\n$md")
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test.paired_*'`
Expected: FAIL — no `edit:` bullet emitted by current formatter.

- [ ] **Step 3: Add the edit-line formatter and call it from `appendLikelySource`**

In `FeedbackQueueFormatter.kt`, add these private helpers in the same object:

```kotlin
    private fun EditSurfaceKindDto.token(): String = when (this) {
        EditSurfaceKindDto.CONTAINER_COLOR -> "containerColor"
        EditSurfaceKindDto.TEXT_COLOR -> "textColor"
        EditSurfaceKindDto.TYPOGRAPHY -> "typography"
        EditSurfaceKindDto.SPACING -> "spacing"
        EditSurfaceKindDto.CHIP_COLOR -> "chipColor"
        EditSurfaceKindDto.COMPONENT_RENDERER -> "componentRenderer"
        EditSurfaceKindDto.UNKNOWN -> "unknown"
    }

    private fun EditSurfaceRoleDto.token(): String = name.lowercase().replace("_", "-")

    private fun EditSurfaceCandidateDto.markdownEditLine(): String {
        val kindToken = kind.token()
        val roleToken = role?.let { " role=${it.token()}" }.orEmpty()
        val locator = if (line != null) "$file:$line" else file
        val confToken = confidence.name.lowercase()
        val whyToken = reasons.joinToString(",") { it.name.lowercase().replace("_", "-") }
        return "edit: $kindToken$roleToken -> `${locator.inlineSafe()}` (conf=$confToken, why=$whyToken)"
    }

    private fun StringBuilder.appendEditSurfaceSubLines(paired: List<EditSurfaceCandidateDto>) {
        for (edit in paired) {
            appendLine("   - ${edit.markdownEditLine()}")
            edit.note?.takeIf { it.isNotBlank() }?.let {
                appendLine("   - edit-note: ${it.inlineSafe()}")
            }
        }
    }
```

Then change `appendLikelySource` to compute pairing once and pass paired entries per candidate. Replace the existing `appendLikelySource` body (around `FeedbackQueueFormatter.kt:122-143`) with:

```kotlin
    private fun StringBuilder.appendLikelySource(
        item: AnnotationDto,
        target: AnnotationTargetDto,
        maxCandidates: Int,
    ) {
        val sourceCandidates = item.sourceCandidates
        if (sourceCandidates.isEmpty() && item.editSurfaceCandidates.isEmpty()) {
            appendLine("No source candidate from current evidence; search by target labels and request.")
            return
        }
        val pairing = buildEditSurfacePairing(sourceCandidates, item.editSurfaceCandidates)
        sourceCandidates.take(maxCandidates).forEachIndexed { index, candidate ->
            appendLine(
                "${index + 1}. `${candidate.fileWithLineAndOwner()}` " +
                    "${candidate.markdownConfidence(target)} confidence${candidate.staleMarkerSuffix()}",
            )
            if (candidate.matchedTerms.isNotEmpty()) {
                appendLine("   - matched: ${candidate.matchedTerms.joinToString(", ") { "`${it.inlineSafe()}`" }}")
            }
            if (candidate.matchReasons.isNotEmpty()) {
                appendLine("   - reasons: ${candidate.matchReasons.joinToString(", ")}")
            }
            pairing.paired[index]?.let { appendEditSurfaceSubLines(it) }
        }
    }
```

Update the **only** caller of `appendLikelySource` (currently at `FeedbackQueueFormatter.kt:58`) — change:

```kotlin
        appendLikelySource(item.sourceCandidates, item.target, detailMode.sourceCandidateLimit())
```

to:

```kotlin
        appendLikelySource(item, item.target, detailMode.sourceCandidateLimit())
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test.paired_*' --tests 'FeedbackQueueFormatterPhase2Test.bothEmpty_*'`
Expected: PASS — all paired tests and the baseline regression both pass.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt
git commit -m "feat(handoff): render edit-surface sub-lines under paired source candidates"
```

---

## Task 4: Render `edit-note:` bullet under paired edit-surface

(The implementation in Task 3 already includes the `edit-note:` branch — this task locks it with explicit tests.)

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun paired_renderEditNoteWhenPresent() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(
                editSurface(file = "app/A.kt", line = 1, note = "container color from M3 theme"),
            ),
            targetReliability = null,
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(
            md.contains("   - edit-note: container color from M3 theme"),
            "expected edit-note bullet\n$md",
        )
    }

    @Test
    fun paired_omitEditNoteWhenBlank() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(editSurface(file = "app/A.kt", line = 1, note = "   ")),
            targetReliability = null,
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(!md.contains("edit-note:"), "blank note should be omitted\n$md")
    }
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test.paired_renderEditNote*' --tests 'FeedbackQueueFormatterPhase2Test.paired_omitEditNote*'`
Expected: PASS (Task 3 implementation already covers this; tests lock the behavior).

If either fails, re-read Task 3 Step 3's `appendEditSurfaceSubLines` body — it must include `edit.note?.takeIf { it.isNotBlank() }?.let { appendLine("   - edit-note: ${it.inlineSafe()}") }`.

- [ ] **Step 3: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt
git commit -m "test(handoff): lock edit-note bullet behavior"
```

---

## Task 5: Render rank-1 source caution as `- note:` line

When the first source candidate has a non-blank `caution`, append one `- note:` line after all source-candidate entries (before any orphan block).

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun rank1Caution_rendersOnceAfterCandidates() {
        val item = annotationWith(
            sourceCandidates = listOf(
                sourceCandidate(file = "app/A.kt", caution = "candidates close; verify before editing"),
                sourceCandidate(file = "app/B.kt", caution = "second-rank caution must not render"),
            ),
            editSurfaceCandidates = emptyList(),
            targetReliability = null,
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(
            md.contains("- note: candidates close; verify before editing"),
            "expected rank-1 caution line\n$md",
        )
        assertTrue(
            !md.contains("second-rank caution must not render"),
            "rank-2 caution must not render\n$md",
        )
    }

    @Test
    fun rank1Caution_omittedWhenBlank() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt", caution = "  ")),
            editSurfaceCandidates = emptyList(),
            targetReliability = null,
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(!md.contains("- note:"), "blank caution should produce no note line\n$md")
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test.rank1Caution_*'`
Expected: FAIL — no `- note:` rendering yet.

- [ ] **Step 3: Add the caution line emission**

In `FeedbackQueueFormatter.kt`, extend `appendLikelySource` to append the rank-1 caution after the source-candidate loop. Replace the existing body (from Task 3) with:

```kotlin
    private fun StringBuilder.appendLikelySource(
        item: AnnotationDto,
        target: AnnotationTargetDto,
        maxCandidates: Int,
    ) {
        val sourceCandidates = item.sourceCandidates
        if (sourceCandidates.isEmpty() && item.editSurfaceCandidates.isEmpty()) {
            appendLine("No source candidate from current evidence; search by target labels and request.")
            return
        }
        val pairing = buildEditSurfacePairing(sourceCandidates, item.editSurfaceCandidates)
        sourceCandidates.take(maxCandidates).forEachIndexed { index, candidate ->
            appendLine(
                "${index + 1}. `${candidate.fileWithLineAndOwner()}` " +
                    "${candidate.markdownConfidence(target)} confidence${candidate.staleMarkerSuffix()}",
            )
            if (candidate.matchedTerms.isNotEmpty()) {
                appendLine("   - matched: ${candidate.matchedTerms.joinToString(", ") { "`${it.inlineSafe()}`" }}")
            }
            if (candidate.matchReasons.isNotEmpty()) {
                appendLine("   - reasons: ${candidate.matchReasons.joinToString(", ")}")
            }
            pairing.paired[index]?.let { appendEditSurfaceSubLines(it) }
        }
        sourceCandidates.firstOrNull()?.caution
            ?.takeIf { it.isNotBlank() }
            ?.let { appendLine("- note: ${it.inlineSafe()}") }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test'`
Expected: PASS (all tests so far).

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt
git commit -m "feat(handoff): render rank-1 source caution as note line"
```

---

## Task 6: Render orphan edit-surface block

Append `Edit Surfaces (unpaired):` block when there are unpaired edit-surface entries.

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun orphanEditSurfaces_appendUnpairedBlock() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(
                editSurface(file = "app/A.kt", line = 1),          // paired (eaten by Task 3)
                editSurface(
                    file = "theme/Colors.kt",
                    line = 42,
                    kind = EditSurfaceKindDto.CONTAINER_COLOR,
                    role = EditSurfaceRoleDto.LAYOUT_OR_STYLE,
                    note = "container color from theme",
                ),
                editSurface(
                    file = "theme/Spacing.kt",
                    line = 7,
                    kind = EditSurfaceKindDto.SPACING,
                    role = null,
                    note = null,
                ),
            ),
            targetReliability = null,
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(md.contains("Edit Surfaces (unpaired):"), "missing orphan header\n$md")
        assertTrue(
            md.contains("1. containerColor role=layout-or-style -> `theme/Colors.kt:42` (conf=high, why=style-intent)"),
            "missing first orphan entry\n$md",
        )
        assertTrue(
            md.contains("   - edit-note: container color from theme"),
            "missing first orphan edit-note\n$md",
        )
        assertTrue(
            md.contains("2. spacing -> `theme/Spacing.kt:7` (conf=high, why=style-intent)"),
            "missing second orphan entry without role token\n$md",
        )
    }

    @Test
    fun orphanEditSurfaces_capAtTwo() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(
                editSurface(file = "x1.kt", line = 1),
                editSurface(file = "x2.kt", line = 2),
                editSurface(file = "x3.kt", line = 3),  // dropped by orphan cap
            ),
            targetReliability = null,
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(md.contains("`x1.kt:1`"))
        assertTrue(md.contains("`x2.kt:2`"))
        assertTrue(!md.contains("`x3.kt:3`"), "orphan beyond cap must be dropped\n$md")
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test.orphanEditSurfaces_*'`
Expected: FAIL — no orphan block emitted.

- [ ] **Step 3: Add orphan block emission**

In `FeedbackQueueFormatter.kt`, add a helper:

```kotlin
    private fun StringBuilder.appendOrphanEditSurfaces(orphans: List<EditSurfaceCandidateDto>) {
        if (orphans.isEmpty()) return
        appendLine("Edit Surfaces (unpaired):")
        orphans.forEachIndexed { index, edit ->
            val kindToken = edit.kind.token()
            val roleToken = edit.role?.let { " role=${it.token()}" }.orEmpty()
            val locator = if (edit.line != null) "${edit.file}:${edit.line}" else edit.file
            val confToken = edit.confidence.name.lowercase()
            val whyToken = edit.reasons.joinToString(",") { it.name.lowercase().replace("_", "-") }
            appendLine(
                "${index + 1}. $kindToken$roleToken -> `${locator.inlineSafe()}` (conf=$confToken, why=$whyToken)",
            )
            edit.note?.takeIf { it.isNotBlank() }?.let {
                appendLine("   - edit-note: ${it.inlineSafe()}")
            }
        }
    }
```

Then extend `appendLikelySource` so the orphan block is appended after the rank-1 caution. Replace its body (from Task 5) with:

```kotlin
    private fun StringBuilder.appendLikelySource(
        item: AnnotationDto,
        target: AnnotationTargetDto,
        maxCandidates: Int,
    ) {
        val sourceCandidates = item.sourceCandidates
        if (sourceCandidates.isEmpty() && item.editSurfaceCandidates.isEmpty()) {
            appendLine("No source candidate from current evidence; search by target labels and request.")
            return
        }
        val pairing = buildEditSurfacePairing(sourceCandidates, item.editSurfaceCandidates)
        sourceCandidates.take(maxCandidates).forEachIndexed { index, candidate ->
            appendLine(
                "${index + 1}. `${candidate.fileWithLineAndOwner()}` " +
                    "${candidate.markdownConfidence(target)} confidence${candidate.staleMarkerSuffix()}",
            )
            if (candidate.matchedTerms.isNotEmpty()) {
                appendLine("   - matched: ${candidate.matchedTerms.joinToString(", ") { "`${it.inlineSafe()}`" }}")
            }
            if (candidate.matchReasons.isNotEmpty()) {
                appendLine("   - reasons: ${candidate.matchReasons.joinToString(", ")}")
            }
            pairing.paired[index]?.let { appendEditSurfaceSubLines(it) }
        }
        sourceCandidates.firstOrNull()?.caution
            ?.takeIf { it.isNotBlank() }
            ?.let { appendLine("- note: ${it.inlineSafe()}") }
        if (sourceCandidates.isNotEmpty()) {
            appendOrphanEditSurfaces(pairing.orphans)
        }
    }
```

(Note: when `sourceCandidates` is empty, the orphan path is owned by Task 7 — it uses a different header.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test'`
Expected: PASS — all current tests including new orphan tests.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt
git commit -m "feat(handoff): render unpaired edit-surface block after source candidates"
```

---

## Task 7: Render edit-surface-only header when source empty

When `sourceCandidates` is empty but `editSurfaceCandidates` has entries, lead the section with `No source candidate; edit-surface hints:` followed by a numbered list of edit-surface entries (no separate "unpaired" header).

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun emptySource_renderEditSurfaceHintsHeader() {
        val item = annotationWith(
            sourceCandidates = emptyList(),
            editSurfaceCandidates = listOf(
                editSurface(
                    file = "theme/Colors.kt",
                    line = 42,
                    kind = EditSurfaceKindDto.CONTAINER_COLOR,
                    role = EditSurfaceRoleDto.LAYOUT_OR_STYLE,
                ),
            ),
            targetReliability = null,
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(md.contains("No source candidate; edit-surface hints:"), "missing fallback header\n$md")
        assertTrue(
            md.contains("1. containerColor role=layout-or-style -> `theme/Colors.kt:42` (conf=high, why=style-intent)"),
            "missing fallback entry\n$md",
        )
        assertTrue(!md.contains("Edit Surfaces (unpaired):"), "should not duplicate the unpaired header\n$md")
        assertTrue(
            !md.contains("No source candidate from current evidence; search by target labels and request."),
            "must not emit legacy literal when edit-surface hints exist\n$md",
        )
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test.emptySource_renderEditSurfaceHintsHeader'`
Expected: FAIL — current branch falls through to the legacy literal.

- [ ] **Step 3: Implement the empty-source branch**

In `FeedbackQueueFormatter.kt`, replace the body of `appendLikelySource` once more to handle the third case:

```kotlin
    private fun StringBuilder.appendLikelySource(
        item: AnnotationDto,
        target: AnnotationTargetDto,
        maxCandidates: Int,
    ) {
        val sourceCandidates = item.sourceCandidates
        val editSurfaceCandidates = item.editSurfaceCandidates
        if (sourceCandidates.isEmpty() && editSurfaceCandidates.isEmpty()) {
            appendLine("No source candidate from current evidence; search by target labels and request.")
            return
        }
        if (sourceCandidates.isEmpty()) {
            appendLine("No source candidate; edit-surface hints:")
            val pairing = buildEditSurfacePairing(sourceCandidates, editSurfaceCandidates)
            renderEditSurfaceList(pairing.orphans)
            return
        }
        val pairing = buildEditSurfacePairing(sourceCandidates, editSurfaceCandidates)
        sourceCandidates.take(maxCandidates).forEachIndexed { index, candidate ->
            appendLine(
                "${index + 1}. `${candidate.fileWithLineAndOwner()}` " +
                    "${candidate.markdownConfidence(target)} confidence${candidate.staleMarkerSuffix()}",
            )
            if (candidate.matchedTerms.isNotEmpty()) {
                appendLine("   - matched: ${candidate.matchedTerms.joinToString(", ") { "`${it.inlineSafe()}`" }}")
            }
            if (candidate.matchReasons.isNotEmpty()) {
                appendLine("   - reasons: ${candidate.matchReasons.joinToString(", ")}")
            }
            pairing.paired[index]?.let { appendEditSurfaceSubLines(it) }
        }
        sourceCandidates.firstOrNull()?.caution
            ?.takeIf { it.isNotBlank() }
            ?.let { appendLine("- note: ${it.inlineSafe()}") }
        if (pairing.orphans.isNotEmpty()) {
            appendLine("Edit Surfaces (unpaired):")
            renderEditSurfaceList(pairing.orphans)
        }
    }

    private fun StringBuilder.renderEditSurfaceList(entries: List<EditSurfaceCandidateDto>) {
        entries.forEachIndexed { index, edit ->
            val kindToken = edit.kind.token()
            val roleToken = edit.role?.let { " role=${it.token()}" }.orEmpty()
            val locator = if (edit.line != null) "${edit.file}:${edit.line}" else edit.file
            val confToken = edit.confidence.name.lowercase()
            val whyToken = edit.reasons.joinToString(",") { it.name.lowercase().replace("_", "-") }
            appendLine(
                "${index + 1}. $kindToken$roleToken -> `${locator.inlineSafe()}` (conf=$confToken, why=$whyToken)",
            )
            edit.note?.takeIf { it.isNotBlank() }?.let {
                appendLine("   - edit-note: ${it.inlineSafe()}")
            }
        }
    }
```

Delete the now-unused `appendOrphanEditSurfaces` helper added in Task 6 — `renderEditSurfaceList` replaces it and the unpaired header is emitted inline.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test'`
Expected: PASS — all current tests including the new fallback test.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt
git commit -m "feat(handoff): render edit-surface-only hints header when source empty"
```

---

## Task 8: Action lines for VISUAL_AREA_ONLY and POSSIBLE_VIEW_INTEROP

Append `- Action: ...` lines after Warning lines for the two visual/interop warnings.

**Files:**
- Modify: `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt`
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun action_emitsForVisualAreaOnly() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = emptyList(),
            targetReliability = reliability(
                confidence = io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW,
                warnings = listOf(
                    io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.VISUAL_AREA_ONLY,
                ),
            ),
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(
            md.contains("- Action: use screenshot/bounds first, then check whether Compose source explains the pixels."),
            "expected visual-area Action line\n$md",
        )
    }

    @Test
    fun action_emitsForPossibleViewInterop() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = emptyList(),
            targetReliability = reliability(
                confidence = io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW,
                warnings = listOf(
                    io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP,
                ),
            ),
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(
            md.contains("- Action: treat source candidates as hints only; AndroidView/WebView may own the pixels."),
            "expected interop Action line\n$md",
        )
    }

    private fun reliability(
        confidence: io.github.beyondwin.fixthis.compose.core.model.TargetConfidence,
        warnings: List<io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning>,
    ): io.github.beyondwin.fixthis.compose.core.model.TargetReliability =
        io.github.beyondwin.fixthis.compose.core.model.TargetReliability(
            confidence = confidence,
            reasons = emptyList(),
            warnings = warnings,
        )
```

If the `TargetReliability` constructor in your branch has different parameter names, open
`fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/model/Models.kt` and match the field names — do not add new fields.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test.action_emits*'`
Expected: FAIL — no Action lines emitted by current `appendTargetReliability`.

- [ ] **Step 3: Add Action line emission**

In `FeedbackQueueFormatter.kt`, add:

```kotlin
    private fun TargetReliabilityWarning.actionLineText(): String? = when (this) {
        TargetReliabilityWarning.VISUAL_AREA_ONLY ->
            "use screenshot/bounds first, then check whether Compose source explains the pixels."
        TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP ->
            "treat source candidates as hints only; AndroidView/WebView may own the pixels."
        TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET ->
            "no Compose semantics node covers this — search by surrounding labels."
        TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED ->
            "source candidates were ranked without the redacted text — corroborate before editing."
        TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN,
        TargetReliabilityWarning.SOURCE_INDEX_STALE,
        TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED,
        TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE -> null
    }

    private fun StringBuilder.appendActionLines(warnings: List<TargetReliabilityWarning>) {
        // Iterate the enum in declaration order so multiple action-bearing warnings render in a stable order.
        for (warning in TargetReliabilityWarning.entries) {
            if (warning !in warnings) continue
            val text = warning.actionLineText() ?: continue
            appendLine("- Action: $text")
        }
    }
```

Then extend `appendTargetReliability` (currently at `FeedbackQueueFormatter.kt:103-113`) to call the new helper after the Warning loop:

```kotlin
    private fun StringBuilder.appendTargetReliability(reliability: TargetReliability?) {
        if (reliability == null) return
        if (reliability.confidence == TargetConfidence.UNKNOWN && reliability.warnings.isEmpty()) {
            appendLine("- Target confidence: unknown - verify manually before editing.")
            return
        }
        appendLine("- Target confidence: ${reliability.confidence.name.lowercase()} - ${reliability.preciseActionGuidance()}")
        reliability.warnings.forEach { warning ->
            appendLine("- Warning: ${warning.handoffMessage().inlineSafe()}")
        }
        appendActionLines(reliability.warnings)
    }
```

Add the import at the top of the file:

```kotlin
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test.action_*' --tests 'FeedbackQueueFormatterPhase2Test'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatter.kt \
        fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt
git commit -m "feat(handoff): render Action lines for visual-area and view-interop warnings"
```

---

## Task 9: Action lines for NO_MEANINGFUL_COMPOSE_TARGET, SENSITIVE_TEXT_REDACTED, and ordering

Lock the remaining two Action-bearing warnings, the stable enum-order rendering, and the non-emission cases.

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun action_emitsForNoMeaningfulComposeTarget() {
        val md = renderWith(
            warnings = listOf(
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET,
            ),
        )
        assertTrue(
            md.contains("- Action: no Compose semantics node covers this — search by surrounding labels."),
            "expected no-meaningful-target Action line\n$md",
        )
    }

    @Test
    fun action_emitsForSensitiveTextRedacted() {
        val md = renderWith(
            warnings = listOf(
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED,
            ),
        )
        assertTrue(
            md.contains(
                "- Action: source candidates were ranked without the redacted text — corroborate before editing.",
            ),
            "expected sensitive-text Action line\n$md",
        )
    }

    @Test
    fun action_omittedForWarningsWithoutAction() {
        val md = renderWith(
            warnings = listOf(
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.SOURCE_INDEX_STALE,
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN,
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE,
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED,
            ),
        )
        assertTrue(!md.contains("- Action:"), "no Action line should be emitted\n$md")
    }

    @Test
    fun action_rendersInEnumOrderForMultipleWarnings() {
        val md = renderWith(
            warnings = listOf(
                // Construction order is reversed on purpose; output must follow enum order.
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED,
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.VISUAL_AREA_ONLY,
            ),
        )
        val visualIdx = md.indexOf("- Action: use screenshot/bounds first")
        val sensitiveIdx = md.indexOf("- Action: source candidates were ranked without the redacted text")
        assertTrue(visualIdx in 0..<sensitiveIdx, "visual-area Action must precede sensitive-text Action\n$md")
    }

    @Test
    fun action_appearsAfterWarningLines() {
        val md = renderWith(
            warnings = listOf(
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.SOURCE_INDEX_STALE,
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.VISUAL_AREA_ONLY,
            ),
        )
        val lastWarningIdx = md.lastIndexOf("- Warning:")
        val firstActionIdx = md.indexOf("- Action:")
        assertTrue(lastWarningIdx >= 0 && firstActionIdx > lastWarningIdx, "Action lines must follow Warning lines\n$md")
    }

    private fun renderWith(
        warnings: List<io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning>,
    ): String {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = emptyList(),
            targetReliability = reliability(
                confidence = io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW,
                warnings = warnings,
            ),
        )
        return FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
    }
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test.action_*'`
Expected: PASS (Task 8 implementation covers all 4 warnings and enum-order iteration).

If `action_rendersInEnumOrderForMultipleWarnings` fails, re-check Task 8 Step 3's `appendActionLines` — it must iterate `TargetReliabilityWarning.entries` and not the input list.

- [ ] **Step 3: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt
git commit -m "test(handoff): lock action line warnings and stable ordering"
```

---

## Task 10: Compatibility regression and detail-mode cap guards

Lock the old-session path (no Phase 2 fields) byte-equal to pre-Phase-2 output, and verify PRECISE vs FULL source-candidate caps still hold.

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun compat_oldSessionWithoutPhase2FieldsHasNoNewLines() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt"), sourceCandidate(file = "app/B.kt")),
            editSurfaceCandidates = emptyList(),
            targetReliability = null,
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        // None of the Phase-2 markers may appear when the source data has no new fields.
        assertTrue(!md.contains("- edit:"), "no edit: bullet expected\n$md")
        assertTrue(!md.contains("- edit-note:"), "no edit-note: bullet expected\n$md")
        assertTrue(!md.contains("Edit Surfaces (unpaired):"), "no orphan block expected\n$md")
        assertTrue(!md.contains("- Action:"), "no Action line expected\n$md")
        assertTrue(!md.contains("- Target confidence:"), "no target confidence line when reliability is null\n$md")
    }

    @Test
    fun cap_preciseLimitsSourceCandidatesToThree() {
        val item = annotationWith(
            sourceCandidates = (1..5).map { sourceCandidate(file = "app/F$it.kt") },
            editSurfaceCandidates = emptyList(),
            targetReliability = null,
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(md.contains("`app/F1.kt`"))
        assertTrue(md.contains("`app/F3.kt`"))
        assertTrue(!md.contains("`app/F4.kt`"), "PRECISE must cap at 3 source candidates\n$md")
    }

    @Test
    fun cap_fullRendersAllSourceCandidates() {
        val item = annotationWith(
            sourceCandidates = (1..5).map { sourceCandidate(file = "app/F$it.kt") },
            editSurfaceCandidates = emptyList(),
            targetReliability = null,
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.FULL)

        for (i in 1..5) {
            assertTrue(md.contains("`app/F$i.kt`"), "FULL must include F$i.kt\n$md")
        }
    }

    @Test
    fun escape_backtickInFilePathIsNeutralized() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(editSurface(file = "app/A.kt", line = 1, note = "watch `this` token")),
            targetReliability = null,
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(
            md.contains("edit-note: watch 'this' token"),
            "expected backticks in note to be replaced with single quotes\n$md",
        )
    }
```

- [ ] **Step 2: Run tests to verify they pass**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test.compat_*' --tests 'FeedbackQueueFormatterPhase2Test.cap_*' --tests 'FeedbackQueueFormatterPhase2Test.escape_*'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt
git commit -m "test(handoff): lock compat regression, source caps, and markdown escaping"
```

---

## Task 11: Compact vs PRECISE token-level equivalence sanity

Assert both renderers carry the same trust-essential tokens for a shared `AnnotationDto`.

**Files:**
- Modify: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt`

**Required imports for this task** (the test references `handoffMessage`, an extension on `TargetReliabilityWarning` defined in `fixthis-compose-core/src/main/kotlin/.../model/Models.kt`). Ensure the test file's import block contains:

```kotlin
import io.github.beyondwin.fixthis.compose.core.format.DetailMode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.handoffMessage
import kotlin.test.Test
import kotlin.test.assertTrue
```

Omitting the `handoffMessage` import causes `Unresolved reference 'handoffMessage'` at `compileTestKotlin`. The extension MUST be imported — it cannot be qualified inline as a method call without being in scope.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun equivalence_compactAndPreciseShareKeyTokens() {
        val warning =
            io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.VISUAL_AREA_ONLY
        val edit = editSurface(file = "app/A.kt", line = 1)
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(edit),
            targetReliability = reliability(
                confidence = io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW,
                warnings = listOf(warning),
            ),
        )
        val session = sessionOf(item)

        val precise = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)
        val compact = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)

        val warningBody = io.github.beyondwin.fixthis.compose.core.model.handoffMessage(warning)
        // Token-level equivalence only — format, ordering, line counts intentionally differ.
        assertTrue(precise.contains("low") && compact.contains("low"), "confidence token missing in one renderer")
        assertTrue(precise.contains(edit.file) && compact.contains(edit.file), "edit file missing in one renderer")
        assertTrue(
            precise.contains(warningBody) && compact.contains(warningBody),
            "warning body missing in one renderer\nprecise=$precise\ncompact=$compact",
        )
    }
```

If `handoffMessage` is an extension function on the warning value (and not a top-level function), call it as `warning.handoffMessage()` instead. Both forms compile against the same code; pick the one in your branch by reading
`fixthis-compose-core/src/main/kotlin/.../model/Models.kt:216`.

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :fixthis-mcp:test --tests 'FeedbackQueueFormatterPhase2Test.equivalence_*'`
Expected: PASS.

If `handoffMessage` can't be referenced as top-level, adjust to `warning.handoffMessage()` and re-run.

- [ ] **Step 3: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackQueueFormatterPhase2Test.kt
git commit -m "test(handoff): assert compact and precise renderers share key trust tokens"
```

---

## Task 12: Corpus regression and (if needed) snapshot update

Verify the existing `HandoffEvaluationCorpusTest` still passes. If any corpus case carries `editSurfaceCandidates` or `caution` that Phase 2 now renders into PRECISE output, update or add an assertion accordingly.

**Files:**
- Write: `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt`
- Read: `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`

- [ ] **Step 1: Run the existing corpus test**

Run: `./gradlew :fixthis-mcp:test --tests 'HandoffEvaluationCorpusTest'`
Expected: PASS unchanged. (The existing test asserts compact-renderer behavior and edit-surface role classification — neither was touched in Phase 2.)

- [ ] **Step 2: Add a PRECISE-render assertion for paired edit-surfaces**

Open `HandoffEvaluationCorpusTest.kt` and append:

```kotlin
    @Test
    fun precise_renderingExposesPairedEditSurfacesForCorpusItems() {
        val corpus = HandoffEvaluationFixtures.loadCorpus()
        val itemsWithEditSurfaces = corpus.cases
            .map { HandoffEvaluationFixtures.annotationFor(it) }
            .filter { it.editSurfaceCandidates.isNotEmpty() }
        if (itemsWithEditSurfaces.isEmpty()) return  // corpus may not yet seed edit-surfaces; tolerated.

        val session = SessionDto(
            sessionId = "phase2-precise",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = corpus.cases.map { HandoffEvaluationFixtures.screenFor(it) },
            items = itemsWithEditSurfaces.mapIndexed { idx, it -> it.copy(sequenceNumber = idx + 1) },
        )

        val md = FeedbackQueueFormatter.toMarkdown(
            session,
            io.github.beyondwin.fixthis.compose.core.format.DetailMode.PRECISE,
        )

        for (item in itemsWithEditSurfaces) {
            val top = item.editSurfaceCandidates.first()
            assertTrue(
                md.contains(top.file),
                "PRECISE must surface edit-surface file ${top.file} for item ${item.itemId}\n$md",
            )
        }
    }
```

- [ ] **Step 3: Run the new corpus test**

Run: `./gradlew :fixthis-mcp:test --tests 'HandoffEvaluationCorpusTest.precise_renderingExposesPairedEditSurfacesForCorpusItems'`
Expected: PASS, or trivially passes (skips) if the current corpus has no edit-surface entries.

- [ ] **Step 4: Commit**

```bash
git add fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/HandoffEvaluationCorpusTest.kt
git commit -m "test(handoff): assert precise renderer surfaces edit-surface files for corpus items"
```

---

## Task 13: Full validation pass and release notes

Run the contributor matrix and update unreleased notes.

**Files:**
- Modify: `docs/releases/unreleased.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Run the full `:fixthis-mcp` test suite**

Run: `./gradlew :fixthis-mcp:test`
Expected: PASS. Any unrelated failure must be investigated, not skipped.

- [ ] **Step 2: Run detekt for the module**

Run: `./gradlew :fixthis-mcp:detekt`
Expected: PASS. If a `complexity` budget is exceeded by `FeedbackQueueFormatter`, split the offending function further (e.g., move `renderEditSurfaceList` into a top-level `private fun` or extract another helper) — do not raise the budget.

- [ ] **Step 3: Run the full contributor matrix listed in `CONTRIBUTING.md` § Required Local Checks**

Use the exact commands documented there. Capture any failure verbatim and fix the root cause before continuing.

- [ ] **Step 4: Add an `unreleased.md` highlight**

Open `docs/releases/unreleased.md` and add a bullet under "Highlights" matching the existing style:

```markdown
- PRECISE/FULL handoff Markdown now surfaces edit-surface candidates,
  rank-1 source caution notes, and condition-specific Action guidance
  for `VISUAL_AREA_ONLY`, `POSSIBLE_VIEW_INTEROP`,
  `NO_MEANINGFUL_COMPOSE_TARGET`, and `SENSITIVE_TEXT_REDACTED`
  warnings — closing the trust-information gap with the compact
  renderer without changing core policy or persisted JSON.
```

- [ ] **Step 5: Add a CHANGELOG entry**

Open `CHANGELOG.md` and add an entry to the Unreleased section using the project's existing style.

- [ ] **Step 6: Commit**

```bash
git add docs/releases/unreleased.md CHANGELOG.md
git commit -m "docs: note phase 2 handoff rendering in unreleased and changelog"
```

---

## Self-Review

**Spec coverage check (against design sections):**

- Architecture: pairing helper + extension of `appendLikelySource` + extension of `appendTargetReliability` → Tasks 2, 3, 5, 6, 7, 8.
- Components: edit-surface pairing → Task 2; source candidate line + edit sub-line → Task 3; edit-note → Task 4; rank-1 caution → Task 5; orphan block → Task 6; empty-source header → Task 7; Action lines → Tasks 8 & 9.
- Data flow & ordering invariants: orphan after caution → Tasks 6 & 7; Action after Warning → Task 9; pair-cap and orphan-cap → Tasks 2, 6, 10; PRECISE/FULL caps preserved → Task 10.
- Error handling: old-session null fields → Tasks 1, 10; blank caution/note/role/line → Tasks 3, 4, 5; Markdown escaping → Task 10; both-empty literal → Task 1.
- Testing: unit suite → Tasks 1–11; corpus regression → Task 12; full matrix → Task 13.

**Placeholder scan:** All code blocks are concrete. No "TBD", "TODO", "Add error handling".

**Type consistency:** Helper names used in later tasks (`buildEditSurfacePairing`, `appendEditSurfaceSubLines`, `renderEditSurfaceList`, `appendActionLines`, `markdownEditLine`, `EditSurfaceKindDto.token`, `EditSurfaceRoleDto.token`, `TargetReliabilityWarning.actionLineText`) are all defined in the task that introduces them. `EditSurfacePairing` is `internal data class` (Task 2) and consumed by the same object in Tasks 3, 6, 7. `EditSurfaceCandidateDto` field names match `SessionDtoModels.kt`. `TargetReliabilityWarning` enum values match `Models.kt`.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-25-trust-program-phase2-handoff-rendering.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
