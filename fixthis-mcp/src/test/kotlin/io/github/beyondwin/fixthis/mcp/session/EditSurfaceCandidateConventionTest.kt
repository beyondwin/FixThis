package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.identity.TestTagConventionSet
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import kotlin.test.Test
import kotlin.test.assertTrue

class EditSurfaceCandidateConventionTest {
    @Test
    fun customConventionResolvesOwnerEditSurface() {
        val bounds = FixThisRect(0f, 0f, 100f, 100f)
        val node = FixThisNode(
            uid = "n",
            composeNodeId = 1,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = bounds,
            text = listOf("Title"),
            testTag = "MyScreen_title",
            path = listOf("root"),
        )
        val item = AnnotationDto(
            itemId = "i",
            screenId = "s",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Node(node.uid, bounds),
            selectedNode = node,
            sourceCandidates = listOf(
                SourceCandidate(
                    file = "app/MyScreen.kt",
                    line = 10,
                    score = 0.9,
                    confidence = SelectionConfidence.MEDIUM,
                    matchReasons = listOf("selected testTag convention composable"),
                    matchedTerms = listOf("MyScreen"),
                    ownerComposable = "MyScreen",
                ),
            ),
            comment = "make this heading red",
        )
        val conventions = TestTagConventionSet.fromPatternStrings(listOf("^([A-Za-z][A-Za-z0-9]*)_([A-Za-z0-9-]+)$"))
        val candidates = EditSurfaceCandidateService.build(item, screen = null, conventions = conventions)
        assertTrue(
            candidates.any { it.file == "app/MyScreen.kt" },
            "expected owner edit-surface for custom convention; got $candidates",
        )
    }
}
