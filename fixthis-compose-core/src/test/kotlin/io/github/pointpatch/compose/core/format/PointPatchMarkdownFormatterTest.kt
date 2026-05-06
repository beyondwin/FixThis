package io.github.pointpatch.compose.core.format

import io.github.pointpatch.compose.core.model.ActivityInfo
import io.github.pointpatch.compose.core.model.AppInfo
import io.github.pointpatch.compose.core.model.PointPatchAnnotation
import io.github.pointpatch.compose.core.model.PointPatchError
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.ScreenshotInfo
import io.github.pointpatch.compose.core.model.SelectionConfidence
import io.github.pointpatch.compose.core.model.SelectionInfo
import io.github.pointpatch.compose.core.model.SelectionKind
import io.github.pointpatch.compose.core.model.SelectionSource
import io.github.pointpatch.compose.core.model.SourceCandidate
import io.github.pointpatch.compose.core.model.TapPoint
import io.github.pointpatch.compose.core.model.TreeKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointPatchMarkdownFormatterTest {
    @Test
    fun includesUserCommentAndSelectedUiDetails() {
        val markdown = PointPatchMarkdownFormatter.format(
            annotation(
                userComment = "Make the primary CTA clearer",
                selectedNode = node(
                    uid = "pay-button",
                    text = listOf("Pay now"),
                    role = "Button",
                    actions = listOf("OnClick")
                )
            )
        )

        assertTrue(markdown.contains("PointPatch Compose Feedback"))
        assertTrue(markdown.contains("Make the primary CTA clearer"))
        assertTrue(markdown.contains("Pay now"))
        assertTrue(markdown.contains("- UID: pay-button"))
        assertTrue(markdown.contains("- Tree: MERGED"))
        assertTrue(markdown.contains("- Actions: OnClick"))
    }

    @Test
    fun rendersBlankAndEmptyValuesAsNone() {
        val markdown = PointPatchMarkdownFormatter.format(annotation(userComment = ""))

        assertTrue(markdown.contains("(No comment)"))
        assertTrue(markdown.contains("## Selected UI\n- none"))
        assertTrue(markdown.contains("## Nearby context\n- none"))
        assertTrue(markdown.contains("## Source candidates\n- none"))
        assertTrue(markdown.contains("## Search hints\n- none"))
        assertTrue(markdown.contains("## Screenshot\n- none"))
    }

    @Test
    fun prefersDesktopScreenshotPathsAndFormatsSourceCandidates() {
        val markdown = PointPatchMarkdownFormatter.format(
            annotation(
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/java/CheckoutScreen.kt",
                        line = 42,
                        score = 0.91,
                        matchedTerms = listOf("Pay"),
                        matchReasons = listOf("text match", "role match"),
                        confidence = SelectionConfidence.HIGH
                    )
                ),
                searchHints = listOf("Pay now Button"),
                screenshot = ScreenshotInfo(
                    fullPath = "/sdcard/pointpatch/full.png",
                    cropPath = "/sdcard/pointpatch/crop.png",
                    desktopFullPath = "/tmp/pointpatch/full.png",
                    desktopCropPath = "/tmp/pointpatch/crop.png"
                )
            )
        )

        assertTrue(markdown.contains("`sample/src/main/java/CheckoutScreen.kt:42`"))
        assertTrue(markdown.contains("score: 0.91"))
        assertTrue(markdown.contains("text match, role match"))
        assertTrue(markdown.contains("/tmp/pointpatch/full.png"))
        assertTrue(markdown.contains("/tmp/pointpatch/crop.png"))
        assertFalse(markdown.contains("/sdcard/pointpatch/full.png"))
    }

    @Test
    fun rendersFreeFormMarkdownAsInertText() {
        val injected = "# Fake heading\n- injected"
        val markdown = PointPatchMarkdownFormatter.format(
            annotation(
                userComment = injected,
                selectedNode = node(
                    uid = "uid`with`ticks\n- injected",
                    text = listOf(injected),
                    contentDescription = listOf(injected),
                    role = "Button\n- injected",
                    testTag = "cta`tag",
                    actions = listOf("OnClick\n- injected")
                ),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "Checkout`Screen.kt\n- injected",
                        line = 42,
                        score = 0.91,
                        matchedTerms = listOf(injected),
                        matchReasons = listOf(injected),
                        confidence = SelectionConfidence.HIGH
                    )
                ),
                searchHints = listOf(injected),
                screenshot = ScreenshotInfo(captureFailedReason = injected),
                errors = listOf(
                    PointPatchError(
                        code = "capture\n- injected",
                        message = injected,
                        details = mapOf("path`key" to injected)
                    )
                )
            )
        )

        val outsideFences = linesOutsideCodeFences(markdown)
        assertFalse(outsideFences.contains("# Fake heading"))
        assertFalse(outsideFences.contains("- injected"))
        assertTrue(markdown.contains("```text\n# Fake heading\n- injected\n```"))
        assertTrue(markdown.contains("\\# Fake heading\\n\\- injected"))
    }

    @Test
    fun jsonFormatterOmitsNullsAndComputedRectPropertiesButKeepsDefaults() {
        val json = PointPatchJsonFormatter.format(annotation(userComment = "Check spacing"))
        val root = Json.parseToJsonElement(json).jsonObject
        val app = root.getValue("app").jsonObject

        assertTrue(json.contains("\"schemaVersion\": \"1.0\""))
        assertTrue(json.contains("\"platform\": \"android-compose\""))
        assertFalse(root.containsKey("selectedNode"))
        assertFalse(root.containsKey("screenshot"))
        assertFalse(app.containsKey("versionName"))
        assertTrue(root.containsKey("candidatesAtPoint"))
        assertTrue(root.containsKey("scopeCandidates"))
        assertTrue(root.containsKey("nearbyNodes"))
        assertTrue(root.containsKey("errors"))

        val rootWithRect = Json.parseToJsonElement(
            PointPatchJsonFormatter.format(annotation(selectedNode = node(uid = "node-1")))
        ).jsonObject
        val bounds = rootWithRect.getValue("selectedNode")
            .jsonObject
            .getValue("boundsInWindow")
            .jsonObject
        assertFalse(bounds.containsKey("width"))
        assertFalse(bounds.containsKey("height"))
        assertFalse(bounds.containsKey("area"))
    }

    private fun annotation(
        userComment: String = "Please inspect this",
        selectedNode: PointPatchNode? = null,
        sourceCandidates: List<SourceCandidate> = emptyList(),
        searchHints: List<String> = emptyList(),
        screenshot: ScreenshotInfo? = null,
        errors: List<PointPatchError> = emptyList()
    ): PointPatchAnnotation = PointPatchAnnotation(
        id = "annotation-1",
        createdAtEpochMillis = 1_714_000_000_000,
        app = AppInfo(packageName = "io.github.pointpatch.sample", debuggable = true),
        activity = ActivityInfo(className = "MainActivity"),
        tap = TapPoint(xInWindow = 12f, yInWindow = 34f),
        selection = SelectionInfo(
            kind = SelectionKind.SEMANTICS_NODE,
            confidence = SelectionConfidence.HIGH,
            selectedUid = selectedNode?.uid,
            source = SelectionSource.TAP_SELECT
        ),
        selectedNode = selectedNode,
        sourceCandidates = sourceCandidates,
        searchHints = searchHints,
        screenshot = screenshot,
        userComment = userComment,
        errors = errors
    )

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        contentDescription: List<String> = emptyList(),
        role: String? = null,
        testTag: String? = null,
        actions: List<String> = emptyList()
    ): PointPatchNode = PointPatchNode(
        uid = uid,
        composeNodeId = 7,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = PointPatchRect(left = 1f, top = 2f, right = 101f, bottom = 52f),
        text = text,
        contentDescription = contentDescription,
        role = role,
        testTag = testTag,
        actions = actions
    )

    private fun linesOutsideCodeFences(markdown: String): Set<String> {
        var inFence = false
        return markdown.lineSequence()
            .filter { line ->
                if (line.startsWith("```")) {
                    inFence = !inFence
                    false
                } else {
                    !inFence
                }
            }
            .toSet()
    }
}
