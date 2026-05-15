package io.github.beyondwin.fixthis.compose.sidekick.inspect

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.compose.core.redaction.RedactionPolicy

class SemanticsNodeMapper(
    private val redactEditableText: Boolean = true,
) {
    fun map(
        root: ComposeRootInfo,
        treeKind: TreeKind,
        node: SemanticsNode,
    ): FixThisNode {
        val config = node.config
        val textProperties = config.toFixThisTextProperties(redactEditableText)

        return FixThisNode(
            uid = uidFor(root.index, treeKind, node),
            composeNodeId = node.id,
            rootIndex = root.index,
            treeKind = treeKind,
            boundsInWindow = node.boundsInWindow(root),
            text = textProperties.text,
            editableText = textProperties.editableText,
            contentDescription = textProperties.contentDescription,
            role = config.safeGet(SemanticsProperties.Role)?.toString(),
            testTag = config.safeGet(SemanticsProperties.TestTag),
            stateDescription = textProperties.stateDescription,
            selected = config.safeGet(SemanticsProperties.Selected),
            enabled = !config.has(SemanticsProperties.Disabled),
            actions = config.actionNames(),
            isPassword = textProperties.isPassword,
            isSensitive = textProperties.isSensitive,
            path = node.path(),
            rawProperties = textProperties.rawProperties,
        )
    }

    private fun uidFor(rootIndex: Int, treeKind: TreeKind, node: SemanticsNode): String = "compose:$rootIndex:${treeKind.name.lowercase()}:${node.id}"

    private fun SemanticsNode.boundsInWindow(root: ComposeRootInfo): FixThisRect {
        val bounds = boundsInRoot
        return FixThisRect(
            left = root.boundsInWindow.left + bounds.left,
            top = root.boundsInWindow.top + bounds.top,
            right = root.boundsInWindow.left + bounds.right,
            bottom = root.boundsInWindow.top + bounds.bottom,
        )
    }

    private fun SemanticsNode.path(): List<String> = runCatching {
        generateSequence(this) { it.parent }
            .toList()
            .asReversed()
            .map { ancestor ->
                if (ancestor.isRoot) {
                    "root"
                } else {
                    "node:${ancestor.id}"
                }
            }
    }.getOrElse {
        listOf("node:$id")
    }

    private fun SemanticsConfiguration.actionNames(): List<String> = actionKeys.mapNotNull { (name, key) -> name.takeIf { has(key) } }

    private fun <T> SemanticsConfiguration.safeGet(key: SemanticsPropertyKey<T>): T? = runCatching { getOrNull(key) }.getOrNull()

    private fun SemanticsConfiguration.has(key: SemanticsPropertyKey<*>): Boolean = any { it.key == key }

    private companion object {
        val actionKeys = listOf(
            "GetTextLayoutResult" to SemanticsActions.GetTextLayoutResult,
            "OnClick" to SemanticsActions.OnClick,
            "OnLongClick" to SemanticsActions.OnLongClick,
            "ScrollBy" to SemanticsActions.ScrollBy,
            "ScrollToIndex" to SemanticsActions.ScrollToIndex,
            "SetProgress" to SemanticsActions.SetProgress,
            "SetText" to SemanticsActions.SetText,
            "SetTextSubstitution" to SemanticsActions.SetTextSubstitution,
            "ShowTextSubstitution" to SemanticsActions.ShowTextSubstitution,
            "ClearTextSubstitution" to SemanticsActions.ClearTextSubstitution,
            "InsertTextAtCursor" to SemanticsActions.InsertTextAtCursor,
            "OnImeAction" to SemanticsActions.OnImeAction,
            "SetSelection" to SemanticsActions.SetSelection,
            "CopyText" to SemanticsActions.CopyText,
            "CutText" to SemanticsActions.CutText,
            "PasteText" to SemanticsActions.PasteText,
            "Expand" to SemanticsActions.Expand,
            "Collapse" to SemanticsActions.Collapse,
            "Dismiss" to SemanticsActions.Dismiss,
            "RequestFocus" to SemanticsActions.RequestFocus,
            "PageUp" to SemanticsActions.PageUp,
            "PageDown" to SemanticsActions.PageDown,
            "PageLeft" to SemanticsActions.PageLeft,
            "PageRight" to SemanticsActions.PageRight,
            "CustomActions" to SemanticsActions.CustomActions,
        )
    }
}

internal data class FixThisTextProperties(
    val text: List<String>,
    val editableText: String?,
    val contentDescription: List<String>,
    val stateDescription: String?,
    val isPassword: Boolean,
    val isSensitive: Boolean,
    val rawProperties: Map<String, String>,
)

internal fun SemanticsConfiguration.toFixThisTextProperties(redactEditableText: Boolean): FixThisTextProperties {
    val text = safeGet(SemanticsProperties.Text).orEmpty().map { it.text }
    val editableText = safeGet(SemanticsProperties.EditableText)?.text
    val contentDescription = safeGet(SemanticsProperties.ContentDescription).orEmpty()
    val stateDescription = safeGet(SemanticsProperties.StateDescription)
    val isPassword = has(SemanticsProperties.Password)
    val hasSensitiveData = safeGet(SemanticsProperties.IsSensitiveData) == true
    val redacted = RedactionPolicy.apply(
        isPassword = isPassword,
        editableText = editableText,
        text = text,
        redactEditableText = redactEditableText,
    )
    val isSensitive = isPassword || redacted.redacted || hasSensitiveData
    val redactTextLikeProperties = isPassword || hasSensitiveData

    return FixThisTextProperties(
        text = when {
            isPassword -> redacted.text
            hasSensitiveData && text.isNotEmpty() -> listOf(REDACTED_TEXT)
            else -> redacted.text
        },
        editableText = when {
            isPassword -> redacted.editableText
            hasSensitiveData && editableText != null -> REDACTED_TEXT
            else -> redacted.editableText
        },
        contentDescription = when {
            isPassword && contentDescription.isNotEmpty() -> listOf(REDACTED_PASSWORD_TEXT)
            hasSensitiveData && contentDescription.isNotEmpty() -> listOf(REDACTED_TEXT)
            else -> contentDescription
        },
        stateDescription = when {
            isPassword && stateDescription != null -> REDACTED_PASSWORD_TEXT
            hasSensitiveData && stateDescription != null -> REDACTED_TEXT
            else -> stateDescription
        },
        isPassword = isPassword,
        isSensitive = isSensitive,
        rawProperties = rawProperties(
            redactEditableText = redacted.redacted || hasSensitiveData,
            redactTextLikeProperties = redactTextLikeProperties,
            isPassword = isPassword,
        ),
    )
}

private const val REDACTED_TEXT = "<redacted>"
private const val REDACTED_PASSWORD_TEXT = "<redacted-password>"

private fun SemanticsConfiguration.rawProperties(
    redactEditableText: Boolean,
    redactTextLikeProperties: Boolean,
    isPassword: Boolean,
): Map<String, String> = associate { entry ->
    val keyName = entry.key.name
    keyName to entry.value.safeRawValue(keyName, redactEditableText, redactTextLikeProperties, isPassword)
}

private fun Any?.safeRawValue(
    keyName: String,
    redactEditableText: Boolean,
    redactTextLikeProperties: Boolean,
    isPassword: Boolean,
): String = when {
    isPassword && keyName.isRedactableTextProperty() -> REDACTED_PASSWORD_TEXT
    redactEditableText && keyName == SemanticsProperties.EditableText.name -> "<redacted-editable-text>"
    redactEditableText && keyName == SemanticsProperties.InputText.name -> "<redacted-editable-text>"
    redactTextLikeProperties && keyName.isRedactableTextProperty() -> REDACTED_TEXT
    else -> toString()
}

private fun String.isRedactableTextProperty(): Boolean = contains("Text", ignoreCase = true) ||
    contains("Input", ignoreCase = true) ||
    this == SemanticsProperties.ContentDescription.name ||
    this == SemanticsProperties.StateDescription.name

private fun <T> SemanticsConfiguration.safeGet(key: SemanticsPropertyKey<T>): T? = runCatching { getOrNull(key) }.getOrNull()

private fun SemanticsConfiguration.has(key: SemanticsPropertyKey<*>): Boolean = any { it.key == key }
