package io.beyondwin.fixthis.compose.core.identity

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityHintFactoryTest {
    @Test
    fun usesCompTestTagAsHighConfidenceIdentity() {
        val hint = IdentityHintFactory.from(
            node(
                testTag = "comp:AppPrimaryButton:primary",
                text = listOf("Sign In"),
                role = "Button",
            ),
        )

        assertEquals("AppPrimaryButton", hint?.composableNameHint)
        assertEquals("primary", hint?.variantHint)
        assertEquals("Button Sign In", hint?.stableLabel)
        assertEquals(IdentityHintSource.TEST_TAG_CONVENTION, hint?.source)
        assertEquals(IdentityHintConfidence.HIGH, hint?.confidence)
    }

    @Test
    fun fallsBackToSemanticsWhenNoConventionExists() {
        val hint = IdentityHintFactory.from(
            node(
                testTag = "plainTag",
                text = listOf("Pay now"),
                role = "Button",
            ),
        )

        assertNull(hint?.composableNameHint)
        assertEquals("Button Pay now #plainTag", hint?.stableLabel)
        assertEquals(IdentityHintSource.SEMANTICS, hint?.source)
        assertEquals(IdentityHintConfidence.MEDIUM, hint?.confidence)
    }

    @Test
    fun returnsNullForNullNode() {
        assertNull(IdentityHintFactory.from(null))
    }

    private fun node(
        testTag: String? = null,
        text: List<String> = emptyList(),
        role: String? = null,
    ): FixThisNode =
        FixThisNode(
            uid = "compose:0:merged:1",
            composeNodeId = 1,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(0f, 0f, 100f, 48f),
            text = text,
            role = role,
            testTag = testTag,
        )
}
