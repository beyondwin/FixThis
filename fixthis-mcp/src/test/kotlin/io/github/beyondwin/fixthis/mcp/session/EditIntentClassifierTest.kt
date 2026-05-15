package io.github.beyondwin.fixthis.mcp.session

import org.junit.Assert.assertEquals
import org.junit.Test

class EditIntentClassifierTest {
    @Test
    fun detectsKoreanAndEnglishVisualEditIntents() {
        assertEquals(EditSurfaceKindDto.CONTAINER_COLOR, EditIntentClassifier.classify("여기 배경 초록색").primaryKind)
        assertEquals(EditSurfaceKindDto.TEXT_COLOR, EditIntentClassifier.classify("여기 글자 파란색").primaryKind)
        assertEquals(EditSurfaceKindDto.TYPOGRAPHY, EditIntentClassifier.classify("여기 텍스트 더크게").primaryKind)
        assertEquals(EditSurfaceKindDto.SPACING, EditIntentClassifier.classify("여기 아래 바텀마진 8dp더").primaryKind)
        assertEquals(EditSurfaceKindDto.TEXT_COLOR, EditIntentClassifier.classify("make this label red").primaryKind)
    }

    @Test
    fun returnsUnknownForContentOnlyFeedback() {
        assertEquals(EditSurfaceKindDto.UNKNOWN, EditIntentClassifier.classify("Rename this to Checkout").primaryKind)
    }
}
