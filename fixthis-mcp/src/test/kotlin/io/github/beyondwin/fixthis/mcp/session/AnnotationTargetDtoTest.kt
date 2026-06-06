package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import kotlin.test.Test
import kotlin.test.assertEquals

class AnnotationTargetDtoTest {
    @Test
    fun boundsInWindowIsReadableThroughTheSealedInterface() {
        val rect = FixThisRect(1f, 2f, 3f, 4f)
        val targets: List<AnnotationTargetDto> = listOf(
            AnnotationTargetDto.Area(rect),
            AnnotationTargetDto.Node(nodeUid = "n1", boundsInWindow = rect),
        )
        targets.forEach { assertEquals(rect, it.boundsInWindow) }
    }
}
