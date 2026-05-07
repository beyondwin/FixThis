package io.beyondwin.fixthis.compose.console.studio.inspector

import io.beyondwin.fixthis.compose.console.studio.model.AnnotationStatus
import io.beyondwin.fixthis.compose.console.studio.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorDisplayHelpersTest {
    @Test
    fun rowCommentPreviewUsesItalicPlaceholderForBlankComments() {
        val preview = annotationRowCommentPreview("   ")

        assertEquals("No comment", preview.text)
        assertTrue(preview.isPlaceholder)
    }

    @Test
    fun rowCommentPreviewKeepsNonBlankCommentsUnchanged() {
        val preview = annotationRowCommentPreview("Check balance card spacing")

        assertEquals("Check balance card spacing", preview.text)
        assertFalse(preview.isPlaceholder)
    }

    @Test
    fun statusPillLabelsUsePrototypeUppercaseText() {
        assertEquals("OPEN", annotationStatusPillLabel(AnnotationStatus.OPEN))
        assertEquals("IN-PROGRESS", annotationStatusPillLabel(AnnotationStatus.IN_PROGRESS))
        assertEquals("RESOLVED", annotationStatusPillLabel(AnnotationStatus.RESOLVED))
    }

    @Test
    fun statusDisplayTextUsesDetailSegmentText() {
        assertEquals("Open", annotationStatusDisplayText(AnnotationStatus.OPEN))
        assertEquals("In progress", annotationStatusDisplayText(AnnotationStatus.IN_PROGRESS))
        assertEquals("Resolved", annotationStatusDisplayText(AnnotationStatus.RESOLVED))
    }

    @Test
    fun severityDisplayTextUsesDetailSegmentText() {
        assertEquals("High", severityDisplayText(Severity.HIGH))
        assertEquals("Med", severityDisplayText(Severity.MED))
        assertEquals("Low", severityDisplayText(Severity.LOW))
    }
}
