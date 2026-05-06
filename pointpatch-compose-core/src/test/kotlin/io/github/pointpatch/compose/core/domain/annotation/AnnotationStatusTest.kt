package io.github.pointpatch.compose.core.domain.annotation

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationStatusTest {
    @Test
    fun statusGroupsMatchProductBuckets() {
        assertEquals(
            listOf(
                AnnotationStatus.OPEN,
                AnnotationStatus.IN_PROGRESS,
                AnnotationStatus.RESOLVED,
                AnnotationStatus.WONT_FIX,
                AnnotationStatus.NEEDS_CLARIFICATION,
            ),
            AnnotationStatus.values().toList(),
        )
        assertEquals(AnnotationStatus.Group.OPEN, AnnotationStatus.OPEN.group)
        assertEquals(AnnotationStatus.Group.OPEN, AnnotationStatus.NEEDS_CLARIFICATION.group)
        assertEquals(AnnotationStatus.Group.IN_PROGRESS, AnnotationStatus.IN_PROGRESS.group)
        assertEquals(AnnotationStatus.Group.RESOLVED, AnnotationStatus.RESOLVED.group)
        assertEquals(AnnotationStatus.Group.RESOLVED, AnnotationStatus.WONT_FIX.group)
    }
}
