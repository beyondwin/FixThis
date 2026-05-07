package io.beyondwin.fixthis.compose.core.identity

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.IdentityHint
import io.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.beyondwin.fixthis.compose.core.model.IdentityHintSource

object IdentityHintFactory {
    fun from(node: FixThisNode?): IdentityHint? {
        if (node == null) return null

        TestTagConvention.parse(node.testTag)?.let { parsed ->
            return IdentityHint(
                composableNameHint = parsed.composableName,
                variantHint = parsed.variant,
                stableLabel = node.stableSemanticLabel(),
                source = IdentityHintSource.TEST_TAG_CONVENTION,
                confidence = IdentityHintConfidence.HIGH,
            )
        }

        val label = node.stableSemanticLabel()
        return label?.let {
            IdentityHint(
                stableLabel = it,
                source = IdentityHintSource.SEMANTICS,
                confidence = IdentityHintConfidence.MEDIUM,
            )
        }
    }

    private fun FixThisNode.stableSemanticLabel(): String? =
        listOfNotNull(
            role?.clean(),
            text.firstOrNull()?.clean(),
            contentDescription.firstOrNull()?.clean(),
            testTagLabelComponent(),
        )
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .takeUnless { it.isBlank() }

    private fun FixThisNode.testTagLabelComponent(): String? =
        testTag
            ?.takeIf { TestTagConvention.parse(it) == null }
            ?.clean()
            ?.takeUnless { it.isBlank() }
            ?.let { "#$it" }

    private fun String.clean(): String = trim().replace(Regex("\\s+"), " ")
}
