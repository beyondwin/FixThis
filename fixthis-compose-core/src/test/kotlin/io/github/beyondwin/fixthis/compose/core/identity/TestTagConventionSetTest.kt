package io.github.beyondwin.fixthis.compose.core.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestTagConventionSetTest {
    @Test
    fun defaultSetParsesColonAndDotForms() {
        assertEquals("PrimaryButton", TestTagConventionSet.Default.parse("comp:PrimaryButton:checkout")?.composableName)
        assertEquals("Settings", TestTagConventionSet.Default.parse("screen.Settings.heading")?.composableName)
        assertNull(TestTagConventionSet.Default.parse("MyScreen_button"))
    }

    @Test
    fun customPatternStringsParseUnderscoreScheme() {
        val set = TestTagConventionSet.fromPatternStrings(
            listOf("^([A-Za-z][A-Za-z0-9]*)_([A-Za-z0-9-]+)$"),
        )
        assertEquals("MyScreen", set.parse("MyScreen_button")?.composableName)
        assertEquals("button", set.parse("MyScreen_button")?.variant)
    }

    @Test
    fun emptyPatternStringsFallBackToDefault() {
        val set = TestTagConventionSet.fromPatternStrings(emptyList())
        assertEquals("PrimaryButton", set.parse("comp:PrimaryButton:checkout")?.composableName)
    }

    @Test
    fun validationRejectsUnanchoredOrOverlongPatterns() {
        assertTrue(TestTagConventionValidation.validate("^([A-Za-z]+)_([A-Za-z0-9]+)$").isValid)
        assertFalse(TestTagConventionValidation.validate("([A-Za-z]+)_(.+)").isValid) // not anchored
        assertFalse(TestTagConventionValidation.validate("^" + "a".repeat(300) + "$").isValid) // too long
        assertFalse(TestTagConventionValidation.validate("^([A-Za-z]+)$").isValid) // needs 2 groups
    }
}
