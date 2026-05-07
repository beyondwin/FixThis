package io.beyondwin.fixthis.compose.core.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TestTagConventionTest {
    @Test
    fun parsesStrictCompTag() {
        val parsed = TestTagConvention.parse("comp:AppPrimaryButton:primary")

        assertEquals("AppPrimaryButton", parsed?.composableName)
        assertEquals("primary", parsed?.variant)
    }

    @Test
    fun rejectsPartialOrUnanchoredTags() {
        assertNull(TestTagConvention.parse("comp:Foo"))
        assertNull(TestTagConvention.parse("comp::primary"))
        assertNull(TestTagConvention.parse("comp:Foo:"))
        assertNull(TestTagConvention.parse("xcomp:Foo:primary"))
        assertNull(TestTagConvention.parse("comp:Foo:primary:extra"))
        assertNull(TestTagConvention.parse("studio:tool:select"))
    }

    @Test
    fun acceptsVariantHyphenAndUnderscore() {
        val underscoreParsed = TestTagConvention.parse("comp:QueueRow:empty_state")
        val hyphenParsed = TestTagConvention.parse("comp:QueueRow:empty-state")

        assertEquals("QueueRow", underscoreParsed?.composableName)
        assertEquals("empty_state", underscoreParsed?.variant)
        assertEquals("QueueRow", hyphenParsed?.composableName)
        assertEquals("empty-state", hyphenParsed?.variant)
    }
}
