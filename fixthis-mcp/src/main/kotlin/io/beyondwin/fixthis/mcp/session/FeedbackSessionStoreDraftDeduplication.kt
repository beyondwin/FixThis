package io.beyondwin.fixthis.mcp.session

internal fun matchingClientDraftItems(session: SessionDto, items: List<AnnotationDto>): List<AnnotationDto> {
    val existingItemsByClientKey = session.items.mapNotNull { item ->
        item.clientDraftKey()?.let { key -> key to item }
    }.toMap()
    return items.mapNotNull { item ->
        item.clientDraftKey()?.let { key -> existingItemsByClientKey[key] }
    }
}

internal fun duplicateScreenFor(
    session: SessionDto,
    duplicateItems: List<AnnotationDto>,
    requestedScreen: SnapshotDto,
): SnapshotDto = duplicateItems.firstOrNull()?.screenId
    ?.let { screenId -> session.screens.firstOrNull { it.screenId == screenId } }
    ?: session.screens.firstOrNull { it.screenId == requestedScreen.screenId }
    ?: requestedScreen

internal fun screenForIncomingBatch(
    session: SessionDto,
    duplicateItems: List<AnnotationDto>,
    requestedScreen: SnapshotDto,
    idGenerator: () -> String,
    now: Long,
): SnapshotDto {
    val duplicateScreen = duplicateScreenFor(session, duplicateItems, requestedScreen)
    if (duplicateItems.isNotEmpty()) return duplicateScreen
    return requestedScreen.copy(
        screenId = if (requestedScreen.screenId == "pending") idGenerator() else requestedScreen.screenId,
        capturedAtEpochMillis = now,
    )
}

internal fun appendScreenIfMissing(session: SessionDto, screen: SnapshotDto): List<SnapshotDto> =
    if (session.screens.any { it.screenId == screen.screenId }) {
        session.screens
    } else {
        session.screens + screen
    }

internal fun createScreenItems(
    session: SessionDto,
    screen: SnapshotDto,
    items: List<AnnotationDto>,
    now: Long,
    idGenerator: () -> String,
): List<AnnotationDto> {
    val firstSequence = session.migratedNextItemSequenceNumber()
    return items.mapIndexed { index, item ->
        item.copy(
            itemId = if (item.itemId == "pending") idGenerator() else item.itemId,
            screenId = screen.screenId,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
            sequenceNumber = item.sequenceNumber ?: firstSequence + index,
            delivery = FeedbackDelivery.DRAFT,
        )
    }
}

internal fun AnnotationDto.clientDraftKey(): String? {
    val workspaceId = clientWorkspaceId?.takeIf { it.isNotBlank() }
    val draftItemId = clientDraftItemId?.takeIf { it.isNotBlank() }
    return if (workspaceId == null || draftItemId == null) null else "$workspaceId\u0000$draftItemId"
}

internal fun AnnotationDto.legacySemanticDraftKey(): String? {
    if (clientDraftKey() != null) return null
    val commentKey = comment.trim().takeIf { it.isNotEmpty() } ?: return null
    return "${target.semanticTypeKey()}\u0000${target.semanticNodeKey()}\u0000${target.semanticBoundsKey()}\u0000$commentKey"
}

internal fun incomingSemanticDraftKey(item: AnnotationDto): String? {
    if (item.clientDraftKey() == null) return null
    val commentKey = item.comment.trim().takeIf { it.isNotEmpty() } ?: return null
    return "${item.target.semanticTypeKey()}\u0000${item.target.semanticNodeKey()}\u0000${item.target.semanticBoundsKey()}\u0000$commentKey"
}

private fun AnnotationTargetDto.semanticTypeKey(): String = when (this) {
    is AnnotationTargetDto.Node -> "node"
    is AnnotationTargetDto.Area -> "area"
}

private fun AnnotationTargetDto.semanticNodeKey(): String = when (this) {
    is AnnotationTargetDto.Node -> nodeUid
    is AnnotationTargetDto.Area -> ""
}

private fun AnnotationTargetDto.semanticBoundsKey(): String {
    val bounds = when (this) {
        is AnnotationTargetDto.Node -> boundsInWindow
        is AnnotationTargetDto.Area -> boundsInWindow
    }
    return listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        .joinToString(",") { kotlin.math.round(it).toInt().toString() }
}

internal fun existingLegacySemanticKeysForScreen(
    session: SessionDto,
    requestedScreen: SnapshotDto,
): Set<String> {
    val candidateScreenIds = buildSet {
        add(requestedScreen.screenId)
        session.screens
            .find { it.fingerprint != null && it.fingerprint == requestedScreen.fingerprint }
            ?.let { add(it.screenId) }
    }
    return session.items
        .filter { item -> item.screenId in candidateScreenIds }
        .mapNotNull { it.legacySemanticDraftKey() }
        .toSet()
}
