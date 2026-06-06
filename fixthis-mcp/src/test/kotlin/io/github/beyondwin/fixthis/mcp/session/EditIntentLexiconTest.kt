@file:Suppress("MaxLineLength")

package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.mcp.session.editsurface.EditIntentLexicon
import io.github.beyondwin.fixthis.mcp.session.editsurface.RawEditIntentSignal
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
