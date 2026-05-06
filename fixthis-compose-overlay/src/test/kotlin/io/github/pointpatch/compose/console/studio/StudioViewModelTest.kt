package io.github.pointpatch.compose.console.studio

import androidx.compose.ui.geometry.Offset
import io.github.pointpatch.compose.console.studio.model.Annotation
import io.github.pointpatch.compose.console.studio.model.AnnotationStatus
import io.github.pointpatch.compose.console.studio.model.RectPercent
import io.github.pointpatch.compose.console.studio.model.Severity
import io.github.pointpatch.compose.console.studio.model.StudioTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioViewModelTest {
    @Test
    fun saveOpenAndDeleteSnapshotMaintainHistoryAndSessionState() {
        val ids = IdSequence("snap-1", "snap-2", "ann-unused")
        val vm = StudioViewModel(
            clock = { 123_456L },
            idFactory = ids::next,
            author = "kim",
            initialAnnotations = listOf(testAnnotation(id = "ann-1", label = "Region 1", comment = "Original")),
        )
        vm.setDraftTitle("Checkout fixes")
        vm.updateAnnotation("missing") { it }

        vm.saveSnapshot()

        assertEquals(1, vm.historyCount)
        assertEquals("snap-1", vm.activeSnapId)
        assertEquals("Checkout fixes", vm.snapshots.single().title)
        assertEquals("kim", vm.snapshots.single().author)
        assertEquals(123_456L, vm.snapshots.single().createdAtEpochMillis)
        assertEquals("Original", vm.snapshots.single().annotations.single().comment)

        vm.updateAnnotation("ann-1") { it.copy(comment = "Changed after save") }
        vm.setDraftTitle("Working copy")
        vm.openSnapshot("snap-1")

        assertEquals("Checkout fixes", vm.draftTitle)
        assertNull(vm.selectedId)
        assertEquals("Original", vm.annotations.single().comment)

        vm.deleteSnapshot("snap-1")

        assertEquals(0, vm.historyCount)
        assertNull(vm.activeSnapId)
        assertEquals("New session", vm.draftTitle)
        assertTrue(vm.annotations.isEmpty())
        assertEquals(StudioTool.SELECT, vm.tool)
    }

    @Test
    fun saveSnapshotCopiesCurrentAnnotationsAndOpeningSnapshotDoesNotShareFutureEdits() {
        val ids = IdSequence("snap-original", "snap-edited")
        val vm = StudioViewModel(
            clock = { 100L },
            idFactory = ids::next,
            author = "kim",
            initialAnnotations = listOf(testAnnotation(id = "ann-1", label = "Region 1", comment = "Before")),
        )
        vm.setDraftTitle("Original")

        vm.saveSnapshot()
        vm.updateAnnotation("ann-1") {
            it.copy(label = "Edited label", comment = "After", status = AnnotationStatus.RESOLVED)
        }
        vm.setDraftTitle("Edited")
        vm.saveSnapshot()

        assertEquals(listOf("snap-edited", "snap-original"), vm.snapshots.map { it.id })
        assertEquals("Before", vm.snapshots[1].annotations.single().comment)
        assertEquals(AnnotationStatus.OPEN, vm.snapshots[1].annotations.single().status)

        vm.openSnapshot("snap-original")
        vm.updateAnnotation("ann-1") { it.copy(comment = "Working copy only") }

        assertEquals("Before", vm.snapshots[1].annotations.single().comment)
        assertEquals("Working copy only", vm.annotations.single().comment)
    }

    @Test
    fun deletingActiveSnapshotOpensFirstRemainingSnapshotOrStartsNewSession() {
        val ids = IdSequence("older", "newer")
        val vm = StudioViewModel(
            clock = { 100L },
            idFactory = ids::next,
            author = "kim",
            initialAnnotations = listOf(testAnnotation(id = "ann-1", label = "Region 1")),
        )
        vm.setDraftTitle("Older")
        vm.saveSnapshot()
        vm.setDraftTitle("Newer")
        vm.updateAnnotation("ann-1") { it.copy(label = "Region 1 edited") }
        vm.saveSnapshot()

        vm.openSnapshot("older")
        vm.deleteSnapshot("older")

        assertEquals("newer", vm.activeSnapId)
        assertEquals("Newer", vm.draftTitle)
        assertEquals("Region 1 edited", vm.annotations.single().label)

        vm.deleteSnapshot("newer")

        assertNull(vm.activeSnapId)
        assertEquals("New session", vm.draftTitle)
        assertTrue(vm.annotations.isEmpty())
    }

    @Test
    fun countsAndAnnotationMutationsReflectCurrentAnnotations() {
        val vm = StudioViewModel(
            initialAnnotations = listOf(
                testAnnotation(id = "open", status = AnnotationStatus.OPEN),
                testAnnotation(id = "progress", status = AnnotationStatus.IN_PROGRESS),
                testAnnotation(id = "resolved", status = AnnotationStatus.RESOLVED),
            ),
        )

        assertEquals(3, vm.annotationsCount)
        assertEquals(1, vm.openCount)
        assertEquals(1, vm.inProgressCount)
        assertEquals(1, vm.resolvedCount)
        assertTrue(vm.canSaveSnapshot)

        vm.selectAnnotation("progress")
        vm.updateAnnotation("progress") {
            it.copy(severity = Severity.HIGH, status = AnnotationStatus.RESOLVED, comment = "Done")
        }

        assertEquals("Done", vm.selectedAnnotation?.comment)
        assertEquals(1, vm.openCount)
        assertEquals(0, vm.inProgressCount)
        assertEquals(2, vm.resolvedCount)

        vm.deleteAnnotation("progress")

        assertNull(vm.selectedId)
        assertNull(vm.selectedAnnotation)
        assertEquals(2, vm.annotationsCount)
        assertEquals(listOf("open", "resolved"), vm.annotations.map { it.id })
    }

    @Test
    fun dragCreatesRegionOnlyAfterMovementAndSizeThresholds() {
        val ids = IdSequence("region-1")
        val vm = StudioViewModel(idFactory = ids::next)
        vm.setTool(StudioTool.ANNOTATE)

        vm.beginDrag(Offset(10f, 20f), widgetTag = null)
        vm.updateDrag(Offset(10.61f, 20.1f))

        assertTrue(vm.dragMoved)
        assertRectPercent(RectPercent(10f, 20f, 0.61f, 0.1f), vm.draggingRect)

        vm.endDrag()

        assertTrue(vm.annotations.isEmpty())
        assertEquals(StudioTool.ANNOTATE, vm.tool)
        assertFalse(vm.dragMoved)
        assertNull(vm.draggingRect)

        vm.beginDrag(Offset(30f, 40f), widgetTag = null)
        vm.updateDrag(Offset(27f, 36f))
        vm.endDrag()

        val created = vm.annotations.single()
        assertEquals("region-1", created.id)
        assertEquals("Region 1", created.label)
        assertEquals(Severity.MED, created.severity)
        assertEquals(AnnotationStatus.OPEN, created.status)
        assertEquals("", created.comment)
        assertRectPercent(RectPercent(27f, 36f, 3f, 4f), created.rectPercent)
        assertEquals(created.id, vm.selectedId)
        assertEquals(StudioTool.SELECT, vm.tool)
    }

    @Test
    fun widgetClickWithoutWidgetBoundsKeepsPointerFallbackAndReturnsToSelect() {
        val ids = IdSequence("widget-1")
        val vm = StudioViewModel(idFactory = ids::next)
        vm.setTool(StudioTool.ANNOTATE)

        vm.beginDrag(
            percent = Offset(42f, 24f),
            widgetTag = "login-button",
            widgetBoundsPercent = null,
        )
        vm.updateDrag(Offset(42.3f, 24.2f))
        vm.endDrag()

        val created = vm.annotations.single()
        assertEquals("widget-1", created.id)
        assertEquals("Login button", created.label)
        assertEquals(Severity.MED, created.severity)
        assertEquals(AnnotationStatus.OPEN, created.status)
        assertEquals("", created.comment)
        assertRectPercent(RectPercent(42f, 24f, 6f, 6f), created.rectPercent)
        assertEquals(created.id, vm.selectedId)
        assertEquals(StudioTool.SELECT, vm.tool)
    }

    @Test
    fun widgetStartDragBelowRegionSizeUsesWidgetBounds() {
        val ids = IdSequence("widget-1")
        val vm = StudioViewModel(idFactory = ids::next)
        vm.setTool(StudioTool.ANNOTATE)

        vm.beginDrag(
            percent = Offset(42f, 24f),
            widgetTag = "login-button",
            widgetBoundsPercent = RectPercent(18f, 12f, 30f, 9f),
        )
        vm.updateDrag(Offset(43f, 25f))
        vm.endDrag()

        val created = vm.annotations.single()
        assertEquals("widget-1", created.id)
        assertEquals("Login button", created.label)
        assertRectPercent(RectPercent(18f, 12f, 30f, 9f), created.rectPercent)
        assertEquals(created.id, vm.selectedId)
        assertEquals(StudioTool.SELECT, vm.tool)
    }

    @Test
    fun widgetStartRegionSizedDragCreatesRegionInsteadOfWidgetAnnotation() {
        val ids = IdSequence("region-1")
        val vm = StudioViewModel(idFactory = ids::next)
        vm.setTool(StudioTool.ANNOTATE)

        vm.beginDrag(
            percent = Offset(42f, 24f),
            widgetTag = "login-button",
            widgetBoundsPercent = RectPercent(18f, 12f, 30f, 9f),
        )
        vm.updateDrag(Offset(47f, 29f))
        vm.endDrag()

        val created = vm.annotations.single()
        assertEquals("region-1", created.id)
        assertEquals("Region 1", created.label)
        assertRectPercent(RectPercent(42f, 24f, 5f, 5f), created.rectPercent)
        assertEquals(created.id, vm.selectedId)
        assertEquals(StudioTool.SELECT, vm.tool)
    }

    @Test
    fun widgetClickUsesSuppliedWidgetBoundsAndReturnsToSelect() {
        val ids = IdSequence("widget-1")
        val vm = StudioViewModel(idFactory = ids::next)
        vm.setTool(StudioTool.ANNOTATE)

        vm.beginDrag(
            percent = Offset(42f, 24f),
            widgetTag = "login-button",
            widgetBoundsPercent = RectPercent(18f, 12f, 30f, 9f),
        )
        vm.updateDrag(Offset(42.3f, 24.2f))
        vm.endDrag()

        val created = vm.annotations.single()
        assertEquals("widget-1", created.id)
        assertEquals("Login button", created.label)
        assertEquals(Severity.MED, created.severity)
        assertEquals(AnnotationStatus.OPEN, created.status)
        assertEquals("", created.comment)
        assertRectPercent(RectPercent(18f, 12f, 30f, 9f), created.rectPercent)
        assertEquals(created.id, vm.selectedId)
        assertEquals(StudioTool.SELECT, vm.tool)
    }

    @Test
    fun dragPercentIsClampedToPreviewBounds() {
        val ids = IdSequence("region-1")
        val vm = StudioViewModel(idFactory = ids::next)
        vm.setTool(StudioTool.ANNOTATE)

        vm.beginDrag(Offset(-5f, 105f), widgetTag = null)
        vm.updateDrag(Offset(110f, -20f))

        assertRectPercent(RectPercent(0f, 0f, 100f, 100f), vm.draggingRect)

        vm.endDrag()

        assertRectPercent(RectPercent(0f, 0f, 100f, 100f), vm.annotations.single().rectPercent)
    }

    @Test
    fun newSessionResetsDraftSelectionToolAnnotationsAndDragState() {
        val vm = StudioViewModel(idFactory = IdSequence("ann-1")::next)
        vm.setDraftTitle("Existing")
        vm.setTool(StudioTool.ANNOTATE)
        vm.beginDrag(Offset(1f, 2f), widgetTag = null)
        vm.updateDrag(Offset(4f, 5f))
        vm.endDrag()

        assertFalse(vm.annotations.isEmpty())

        vm.setTool(StudioTool.ANNOTATE)
        vm.beginDrag(Offset(9f, 9f), widgetTag = null)
        vm.updateDrag(Offset(12f, 12f))
        vm.newSession()

        assertNull(vm.activeSnapId)
        assertTrue(vm.annotations.isEmpty())
        assertEquals("New session", vm.draftTitle)
        assertNull(vm.selectedId)
        assertEquals(StudioTool.SELECT, vm.tool)
        assertNull(vm.draggingRect)
        assertFalse(vm.dragMoved)
    }
}

private class IdSequence(private vararg val values: String) {
    private var index = 0

    fun next(): String = values[index++]
}

private fun assertRectPercent(expected: RectPercent, actual: RectPercent?) {
    requireNotNull(actual)
    assertEquals(expected.x, actual.x, 0.0001f)
    assertEquals(expected.y, actual.y, 0.0001f)
    assertEquals(expected.w, actual.w, 0.0001f)
    assertEquals(expected.h, actual.h, 0.0001f)
}

private fun testAnnotation(
    id: String,
    label: String = id,
    severity: Severity = Severity.MED,
    status: AnnotationStatus = AnnotationStatus.OPEN,
    comment: String = "",
    rectPercent: RectPercent = RectPercent(1f, 2f, 3f, 4f),
): Annotation =
    Annotation(
        id = id,
        label = label,
        severity = severity,
        status = status,
        comment = comment,
        rectPercent = rectPercent,
    )
