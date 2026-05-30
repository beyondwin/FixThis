package io.github.beyondwin.fixthis.compose.core.identity

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

    @Test
    fun parsesScreenColonConvention() {
        val parsed = TestTagConvention.parse("screen:CartScreen:checkout")

        assertEquals("CartScreen", parsed?.composableName)
        assertEquals("checkout", parsed?.variant)
    }

    @Test
    fun parsesCompDotConvention() {
        val parsed = TestTagConvention.parse("comp.PrimaryButton.submit")

        assertEquals("PrimaryButton", parsed?.composableName)
        assertEquals("submit", parsed?.variant)
    }

    @Test
    fun parsesScreenDotConvention() {
        val parsed = TestTagConvention.parse("screen.Profile.avatar")

        assertEquals("Profile", parsed?.composableName)
        assertEquals("avatar", parsed?.variant)
    }

    @Test
    fun rejectsTagsOutsideEnumeratedSet() {
        assertNull(TestTagConvention.parse("widget:Foo:bar"))
        assertNull(TestTagConvention.parse("comp-Foo-bar"))
        assertNull(TestTagConvention.parse("screen:Foo"))
        assertNull(TestTagConvention.parse("comp.Foo."))
        assertNull(TestTagConvention.parse("screen.Foo."))
    }
}
