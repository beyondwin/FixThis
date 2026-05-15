package io.github.beyondwin.fixthis.compose.core.model

import io.github.beyondwin.fixthis.compose.core.domain.snapshot.DomainError
import io.github.beyondwin.fixthis.compose.core.domain.ui.DomainRect
import io.github.beyondwin.fixthis.compose.core.domain.ui.SemanticsNodeSnapshot
import io.github.beyondwin.fixthis.compose.core.domain.ui.SemanticsTreeKind

fun DomainRect.toFixThisRect(): FixThisRect = FixThisRect(left, top, right, bottom)

fun FixThisRect.toDomainRect(): DomainRect = DomainRect(left, top, right, bottom)

fun SemanticsNodeSnapshot.toFixThisNode(): FixThisNode = FixThisNode(
    uid = uid,
    composeNodeId = composeNodeId,
    rootIndex = rootIndex,
    treeKind = when (treeKind) {
        SemanticsTreeKind.MERGED -> TreeKind.MERGED
        SemanticsTreeKind.UNMERGED -> TreeKind.UNMERGED
    },
    boundsInWindow = boundsInWindow.toFixThisRect(),
    text = text,
    editableText = editableText,
    contentDescription = contentDescription,
    role = role,
    testTag = testTag,
    stateDescription = stateDescription,
    selected = selected,
    enabled = enabled,
    actions = actions,
    isPassword = isPassword,
    isSensitive = isSensitive,
    path = path,
    rawProperties = rawProperties,
)

fun FixThisNode.toDomainSemanticsNode(): SemanticsNodeSnapshot = SemanticsNodeSnapshot(
    uid = uid,
    composeNodeId = composeNodeId,
    rootIndex = rootIndex,
    treeKind = when (treeKind) {
        TreeKind.MERGED -> SemanticsTreeKind.MERGED
        TreeKind.UNMERGED -> SemanticsTreeKind.UNMERGED
    },
    boundsInWindow = boundsInWindow.toDomainRect(),
    text = text,
    editableText = editableText,
    contentDescription = contentDescription,
    role = role,
    testTag = testTag,
    stateDescription = stateDescription,
    selected = selected,
    enabled = enabled,
    actions = actions,
    isPassword = isPassword,
    isSensitive = isSensitive,
    path = path,
    rawProperties = rawProperties,
)

fun DomainError.toFixThisError(): FixThisError = FixThisError(code, message, details)

fun FixThisError.toDomainError(): DomainError = DomainError(code, message, details)
