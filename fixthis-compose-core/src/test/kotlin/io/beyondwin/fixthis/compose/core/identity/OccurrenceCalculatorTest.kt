package io.beyondwin.fixthis.compose.core.identity

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.IdentityHint
import io.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.beyondwin.fixthis.compose.core.model.OccurrenceSignatureType
import io.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OccurrenceCalculatorTest {
    @Test
    fun countsAndRanksNodesWithSameIdentityHint() {
        val first = node(uid = "a", testTag = "comp:AppPrimaryButton:primary", top = 0f)
        val second = node(uid = "b", testTag = "comp:AppPrimaryButton:primary", top = 60f)
        val hint = IdentityHint(
            composableNameHint = "AppPrimaryButton",
            variantHint = "primary",
            source = IdentityHintSource.TEST_TAG_CONVENTION,
            confidence = IdentityHintConfidence.HIGH,
        )

        val occurrence = OccurrenceCalculator.calculate(
            selectedNode = second,
            nodes = listOf(first, second),
            identityHint = hint,
        )

        assertEquals(OccurrenceSignatureType.IDENTITY_HINT, occurrence?.signature?.type)
        assertEquals("AppPrimaryButton:primary", occurrence?.signature?.value)
        assertEquals(2, occurrence?.count)
        assertEquals(2, occurrence?.selectedOrdinal)
    }

    @Test
    fun fallsBackToEdgeTrimmedExactTestTag() {
        val selected = node(uid = "a", testTag = "  plainTag  ", top = 0f)

        val occurrence = OccurrenceCalculator.calculate(
            selectedNode = selected,
            nodes = listOf(selected),
            identityHint = null,
        )

        assertEquals(OccurrenceSignatureType.TEST_TAG, occurrence?.signature?.type)
        assertEquals("plainTag", occurrence?.signature?.value)
        assertEquals(1, occurrence?.count)
        assertEquals(1, occurrence?.selectedOrdinal)
    }

    @Test
    fun doesNotCollapseInternalWhitespaceForExactTestTag() {
        val first = node(uid = "a", testTag = "  plain   Tag  ", top = 0f)
        val second = node(uid = "b", testTag = "plain Tag", top = 60f)

        val occurrence = OccurrenceCalculator.calculate(
            selectedNode = first,
            nodes = listOf(first, second),
            identityHint = null,
        )

        assertEquals(OccurrenceSignatureType.TEST_TAG, occurrence?.signature?.type)
        assertEquals("plain   Tag", occurrence?.signature?.value)
        assertEquals(1, occurrence?.count)
        assertEquals(1, occurrence?.selectedOrdinal)
    }

    @Test
    fun countsRolePlusTextSignatureWhenNoIdentityOrTestTag() {
        val first = node(uid = "a", text = listOf("Retry"), role = "Button", top = 0f)
        val second = node(uid = "b", text = listOf("Retry"), role = "Button", top = 60f)

        val occurrence = OccurrenceCalculator.calculate(
            selectedNode = second,
            nodes = listOf(first, second),
            identityHint = null,
        )

        assertEquals(OccurrenceSignatureType.ROLE_PLUS_TEXT, occurrence?.signature?.type)
        assertEquals("Button:Retry", occurrence?.signature?.value)
        assertEquals(2, occurrence?.count)
        assertEquals(2, occurrence?.selectedOrdinal)
    }

    @Test
    fun countsRolePlusContentDescriptionWhenTextMissing() {
        val first = node(uid = "a", contentDescription = listOf("Settings"), role = "Button", top = 0f)
        val second = node(uid = "b", contentDescription = listOf("Settings"), role = "Button", top = 60f)

        val occurrence = OccurrenceCalculator.calculate(
            selectedNode = first,
            nodes = listOf(second, first),
            identityHint = null,
        )

        assertEquals(OccurrenceSignatureType.ROLE_PLUS_CONTENT_DESCRIPTION, occurrence?.signature?.type)
        assertEquals("Button:Settings", occurrence?.signature?.value)
        assertEquals(2, occurrence?.count)
        assertEquals(1, occurrence?.selectedOrdinal)
    }

    @Test
    fun usesUidOrderingWhenMatchingNodesHaveSameTopAndLeft() {
        val firstByUid = node(uid = "a", text = listOf("Retry"), role = "Button", top = 0f, left = 0f)
        val secondByUid = node(uid = "b", text = listOf("Retry"), role = "Button", top = 0f, left = 0f)

        val occurrence = OccurrenceCalculator.calculate(
            selectedNode = secondByUid,
            nodes = listOf(secondByUid, firstByUid),
            identityHint = null,
        )

        assertEquals(OccurrenceSignatureType.ROLE_PLUS_TEXT, occurrence?.signature?.type)
        assertEquals("Button:Retry", occurrence?.signature?.value)
        assertEquals(2, occurrence?.count)
        assertEquals(2, occurrence?.selectedOrdinal)
    }

    @Test
    fun avoidsRedactedTextSignatureForSensitiveNodes() {
        val selected = node(uid = "password", text = listOf("<redacted>"), role = "TextField", isSensitive = true)

        val occurrence = OccurrenceCalculator.calculate(
            selectedNode = selected,
            nodes = listOf(selected),
            identityHint = null,
        )

        assertNull(occurrence)
    }

    @Test
    fun returnsNullWhenSelectedNodeIsNull() {
        assertNull(OccurrenceCalculator.calculate(selectedNode = null, nodes = emptyList(), identityHint = null))
    }

    private fun node(
        uid: String,
        testTag: String? = null,
        text: List<String> = emptyList(),
        contentDescription: List<String> = emptyList(),
        role: String? = null,
        top: Float = 0f,
        left: Float = 0f,
        isSensitive: Boolean = false,
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(left, top, left + 100f, top + 48f),
        text = text,
        contentDescription = contentDescription,
        role = role,
        testTag = testTag,
        isSensitive = isSensitive,
    )
}
