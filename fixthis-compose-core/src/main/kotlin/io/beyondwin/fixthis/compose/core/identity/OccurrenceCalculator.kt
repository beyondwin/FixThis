package io.beyondwin.fixthis.compose.core.identity

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.IdentityHint
import io.beyondwin.fixthis.compose.core.model.Occurrence
import io.beyondwin.fixthis.compose.core.model.OccurrenceSignature
import io.beyondwin.fixthis.compose.core.model.OccurrenceSignatureType

object OccurrenceCalculator {
    fun calculate(
        selectedNode: FixThisNode?,
        nodes: List<FixThisNode>,
        identityHint: IdentityHint?,
    ): Occurrence? {
        if (selectedNode == null) return null

        val signature = selectedNode.signature(identityHint) ?: return null
        val matching = nodes
            .filter { node -> node.signature(identityHint) == signature }
            .sortedWith(
                compareBy<FixThisNode> { it.boundsInWindow.top }
                    .thenBy { it.boundsInWindow.left }
                    .thenBy { it.uid },
            )
        val ordinal = matching.indexOfFirst { it.uid == selectedNode.uid }
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: return null

        return Occurrence(
            signature = signature,
            count = matching.size,
            selectedOrdinal = ordinal,
        )
    }

    private fun FixThisNode.signature(identityHint: IdentityHint?): OccurrenceSignature? {
        val composableName = identityHint?.composableNameHint?.takeUnlessBlank()
        val variant = identityHint?.variantHint?.takeUnlessBlank()
        val parsedTag = TestTagConvention.parse(testTag)
        if (
            composableName != null &&
            variant != null &&
            parsedTag?.composableName == composableName &&
            parsedTag.variant == variant
        ) {
            return OccurrenceSignature(
                type = OccurrenceSignatureType.IDENTITY_HINT,
                value = "$composableName:$variant",
            )
        }

        testTag?.takeUnlessBlankAfterTrim()?.let { tag ->
            return OccurrenceSignature(type = OccurrenceSignatureType.TEST_TAG, value = tag)
        }

        if (isPassword || isSensitive) return null

        val roleValue = role?.takeUnlessBlank() ?: return null
        text.firstNonBlankOrNull()?.let { value ->
            return OccurrenceSignature(type = OccurrenceSignatureType.ROLE_PLUS_TEXT, value = "$roleValue:$value")
        }
        contentDescription.firstNonBlankOrNull()?.let { value ->
            return OccurrenceSignature(
                type = OccurrenceSignatureType.ROLE_PLUS_CONTENT_DESCRIPTION,
                value = "$roleValue:$value",
            )
        }
        return null
    }

    private fun List<String>.firstNonBlankOrNull(): String? = firstNotNullOfOrNull { it.takeUnlessBlank() }

    private fun String.takeUnlessBlank(): String? = trim().replace(Regex("\\s+"), " ").takeUnless { it.isBlank() }

    private fun String.takeUnlessBlankAfterTrim(): String? = trim().takeUnless { it.isBlank() }
}
