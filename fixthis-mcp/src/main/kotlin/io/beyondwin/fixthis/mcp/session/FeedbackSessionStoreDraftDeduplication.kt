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
