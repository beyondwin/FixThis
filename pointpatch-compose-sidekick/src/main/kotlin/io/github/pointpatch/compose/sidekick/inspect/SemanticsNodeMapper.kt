package io.github.pointpatch.compose.sidekick.inspect

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.TreeKind
import io.github.pointpatch.compose.core.redaction.RedactionPolicy

class SemanticsNodeMapper(
    private val redactEditableText: Boolean = true,
) {
    fun map(
        root: ComposeRootInfo,
        treeKind: TreeKind,
        node: SemanticsNode,
    ): PointPatchNode {
        val config = node.config
        val text = config.safeGet(SemanticsProperties.Text).orEmpty().map { it.text }
        val editableText = config.safeGet(SemanticsProperties.EditableText)?.text
        val isPassword = config.has(SemanticsProperties.Password)
        val redacted = RedactionPolicy.apply(
            isPassword = isPassword,
            editableText = editableText,
            text = text,
            redactEditableText = redactEditableText,
        )

        return PointPatchNode(
            uid = uidFor(root.index, treeKind, node),
            composeNodeId = node.id,
            rootIndex = root.index,
            treeKind = treeKind,
            boundsInWindow = node.boundsInWindow(root),
            text = redacted.text,
            editableText = redacted.editableText,
            contentDescription = config.safeGet(SemanticsProperties.ContentDescription).orEmpty(),
            role = config.safeGet(SemanticsProperties.Role)?.toString(),
            testTag = config.safeGet(SemanticsProperties.TestTag),
            stateDescription = config.safeGet(SemanticsProperties.StateDescription),
            selected = config.safeGet(SemanticsProperties.Selected),
            enabled = !config.has(SemanticsProperties.Disabled),
            actions = config.actionNames(),
            isPassword = isPassword,
            isSensitive = isPassword ||
                redacted.redacted ||
                config.safeGet(SemanticsProperties.IsSensitiveData) == true,
            path = node.path(),
            rawProperties = config.rawProperties(redacted.redacted, isPassword),
        )
    }

    private fun uidFor(rootIndex: Int, treeKind: TreeKind, node: SemanticsNode): String =
        "compose:$rootIndex:${treeKind.name.lowercase()}:${node.id}"

    private fun SemanticsNode.boundsInWindow(root: ComposeRootInfo): PointPatchRect {
        val bounds = boundsInRoot
        return PointPatchRect(
            left = root.boundsInWindow.left + bounds.left,
            top = root.boundsInWindow.top + bounds.top,
            right = root.boundsInWindow.left + bounds.right,
            bottom = root.boundsInWindow.top + bounds.bottom,
        )
    }

    private fun SemanticsNode.path(): List<String> =
        runCatching {
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

    private fun SemanticsConfiguration.actionNames(): List<String> =
        actionKeys.mapNotNull { (name, key) -> name.takeIf { has(key) } }

    private fun SemanticsConfiguration.rawProperties(redacted: Boolean, isPassword: Boolean): Map<String, String> =
        associate { entry ->
            val keyName = entry.key.name
            keyName to entry.value.safeRawValue(keyName, redacted, isPassword)
        }

    private fun Any?.safeRawValue(keyName: String, redacted: Boolean, isPassword: Boolean): String =
        when {
            isPassword && keyName.isTextLike() -> "<redacted-password>"
            redacted && keyName == SemanticsProperties.EditableText.name -> "<redacted-editable-text>"
            redacted && keyName == SemanticsProperties.InputText.name -> "<redacted-editable-text>"
            redacted && keyName.isTextLike() -> "<redacted>"
            else -> toString()
        }

    private fun String.isTextLike(): Boolean =
        contains("Text", ignoreCase = true) || contains("Input", ignoreCase = true)

    private fun <T> SemanticsConfiguration.safeGet(key: SemanticsPropertyKey<T>): T? =
        runCatching { getOrNull(key) }.getOrNull()

    private fun SemanticsConfiguration.has(key: SemanticsPropertyKey<*>): Boolean =
        any { it.key == key }

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
